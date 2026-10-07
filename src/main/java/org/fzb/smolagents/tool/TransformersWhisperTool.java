package org.fzb.smolagents.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ExecutorException;
import org.fzb.smolagents.core.ToolExecutionException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 通过持久本地 Python worker 和 Transformers Whisper 模型转录音频。
 *
 * <p>需要用户自行安装 {@code torch}、{@code transformers}、{@code accelerate} 和
 * {@code soundfile}。默认仅从本地 HF cache/checkpoint 读取；只有显式配置
 * {@code localFilesOnly=false} 时才允许 Transformers 从 Hugging Face Hub 下载模型。
 * 本工具不执行模型仓库 Python remote code，也不使用 pickle 传输调用数据。</p>
 */
public final class TransformersWhisperTool implements Tool, AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WORKER_RESOURCE =
            "/org/fzb/smolagents/tool/transformers_whisper_worker.py";
    private static final String EOF_MARKER = "\u0000<whisper-worker-eof>";
    private static final int MAX_ENCODED_AUDIO_BYTES = 34 * 1024 * 1024;
    private static final int MAX_TRANSCRIPT_CHARS = 1_000_000;
    private static final int MAX_PROTOCOL_LINE_CHARS = 2 * 1024 * 1024;
    private static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(3);

    private final String modelId;
    private final String pythonExecutable;
    private final String device;
    private final boolean localFilesOnly;
    private final Duration startupTimeout;
    private final Duration requestTimeout;
    private final int maxAudioBytes;
    private final Path configuredWorkerScript;
    private final ToolSpec spec = new ToolSpec(
            "transcriber",
            "Transcribe audio with a locally installed Transformers Whisper model.",
            SpeechToTextTool.createSchema(),
            true
    );

    private BlockingQueue<String> protocolLines;
    private Process process;
    private BufferedWriter workerInput;
    private Path workerScript;
    private boolean closed;

    /**
     * 创建仅使用本地 cache/checkpoint 的标准 Whisper large-v3-turbo 工具。
     */
    public TransformersWhisperTool() {
        this(
                "openai/whisper-large-v3-turbo",
                defaultPythonExecutable(),
                "auto",
                true,
                DEFAULT_STARTUP_TIMEOUT,
                DEFAULT_REQUEST_TIMEOUT,
                25 * 1024 * 1024
        );
    }

    /**
     * 创建具有显式模型来源策略的本地 Transformers Whisper 工具。
     *
     * @param modelId 本地 checkpoint 路径或 Hub model ID
     * @param pythonExecutable Python 解释器可执行文件；只允许命令本身，不接受 shell 参数
     * @param device auto、cpu、cuda、cuda:N 或 mps
     * @param localFilesOnly true 时禁止 Transformers 下载模型；false 时显式允许 Hub 下载
     * @param startupTimeout worker 加载模型并完成初始化的最长时间
     * @param requestTimeout 单次转录最长时间
     * @param maxAudioBytes 最大解码音频字节数，范围 1-25 MiB
     */
    public TransformersWhisperTool(
            String modelId,
            String pythonExecutable,
            String device,
            boolean localFilesOnly,
            Duration startupTimeout,
            Duration requestTimeout,
            int maxAudioBytes
    ) {
        this(modelId, pythonExecutable, device, localFilesOnly, startupTimeout, requestTimeout, maxAudioBytes, null);
    }

    /**
     * 使用受限参数创建工具；包内脚本参数仅供确定性协议测试替身使用。
     *
     * @param modelId checkpoint 路径或 Hub model ID
     * @param pythonExecutable Python 命令
     * @param device 模型推理设备
     * @param localFilesOnly 禁止/允许 Transformers 下载模型
     * @param startupTimeout 模型加载最长时限
     * @param requestTimeout 单次生成最长时限
     * @param maxAudioBytes 单次音频最大长度
     * @param configuredWorkerScript 可空的测试 worker 脚本路径
     */
    TransformersWhisperTool(
            String modelId,
            String pythonExecutable,
            String device,
            boolean localFilesOnly,
            Duration startupTimeout,
            Duration requestTimeout,
            int maxAudioBytes,
            Path configuredWorkerScript
    ) {
        if (modelId == null || modelId.isBlank() || modelId.length() > 512
                || modelId.contains("\r") || modelId.contains("\n")) {
            throw new ConfigurationException("Whisper modelId must be non-empty and must not contain line breaks");
        }
        if (pythonExecutable == null || pythonExecutable.isBlank()
                || pythonExecutable.contains("\r") || pythonExecutable.contains("\n")) {
            throw new ConfigurationException("pythonExecutable must be a single command or executable path");
        }
        if (device == null || !device.matches("auto|cpu|cuda(?::[0-9]+)?|mps")) {
            throw new ConfigurationException("Whisper device must be auto, cpu, cuda[:index], or mps");
        }
        if (startupTimeout == null || startupTimeout.isZero() || startupTimeout.isNegative()) {
            throw new ConfigurationException("startupTimeout must be positive");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new ConfigurationException("requestTimeout must be positive");
        }
        if (maxAudioBytes < 1 || maxAudioBytes > 25 * 1024 * 1024) {
            throw new ConfigurationException("maxAudioBytes must be between 1 and 25 MiB");
        }
        this.modelId = modelId;
        this.pythonExecutable = pythonExecutable;
        this.device = device;
        this.localFilesOnly = localFilesOnly;
        this.startupTimeout = startupTimeout;
        this.requestTimeout = requestTimeout;
        this.maxAudioBytes = maxAudioBytes;
        this.configuredWorkerScript = configuredWorkerScript;
    }

    /**
     * 返回本地 Transformers Whisper tool schema。
     *
     * @return 音频 data、MIME type 和可选语言输入定义
     */
    @Override
    public ToolSpec spec() {
        return spec;
    }

    /**
     * 通过持久 worker 执行音频解码、特征提取和 Whisper 生成。
     *
     * @param arguments base64 audio 和可选语言 hint
     * @param context 当前工具调用上下文
     * @return 本地识别文本
     */
    @Override
    public synchronized ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
        if (closed) {
            throw new ExecutorException("Transformers Whisper tool is closed");
        }
        Objects.requireNonNull(context, "context");
        CancellationToken cancellation = context.cancellation();
        cancellation.throwIfCancelled();
        String encoded = arguments.path("audio").path("data").asText("");
        String mimeType = arguments.path("audio").path("mimeType").asText("");
        if (encoded.isBlank() || encoded.length() > MAX_ENCODED_AUDIO_BYTES) {
            throw new ToolExecutionException("audio.data is missing or exceeds the maximum encoded audio size");
        }
        if (!mimeType.matches("audio/[A-Za-z0-9.+-]+")) {
            throw new ToolExecutionException("audio.mimeType must be an audio MIME type");
        }
        byte[] audio = SpeechToTextTool.decodeAudio(encoded);
        if (audio.length == 0 || audio.length > maxAudioBytes) {
            throw new ToolExecutionException("Audio size exceeds the configured limit");
        }
        String language = SpeechToTextTool.audioLanguage(arguments);
        try {
            ensureStarted(cancellation);
            String requestId = UUID.randomUUID().toString();
            var request = MAPPER.createObjectNode()
                    .put("type", "transcribe")
                    .put("id", requestId)
                    .put("audio", Base64.getEncoder().encodeToString(audio))
                    .put("mimeType", mimeType);
            if (language != null) {
                request.put("language", language);
            }
            writeMessage(request);
            JsonNode response = awaitResponse(requestTimeout, cancellation);
            if (!requestId.equals(response.path("id").asText())) {
                throw terminateWorker("Whisper worker returned a mismatched request ID", null);
            }
            if ("error".equals(response.path("type").asText())) {
                throw new ToolExecutionException(
                        "Transformers Whisper failed: " + response.path("error").asText("unknown error"));
            }
            if (!"result".equals(response.path("type").asText())) {
                throw terminateWorker("Whisper worker returned an unexpected protocol message", null);
            }
            String transcript = response.path("text").asText("");
            if (transcript.isBlank() || transcript.length() > MAX_TRANSCRIPT_CHARS) {
                throw new ToolExecutionException("Transformers Whisper returned empty or oversized transcript");
            }
            return ToolResult.text(transcript);
        } catch (IOException error) {
            throw terminateWorker("Transformers Whisper worker communication failed", error);
        } catch (ExecutorException error) {
            throw new ToolExecutionException(error.getMessage(), error);
        }
    }

    /**
     * 加载模型并确认 Transformers worker 可用，便于调用方在服务启动阶段预热。
     *
     * @throws ExecutorException Python 包或 checkpoint 加载失败时抛出
     */
    public synchronized void warmUp() {
        if (closed) {
            throw new ExecutorException("Transformers Whisper tool is closed");
        }
        ensureStarted(new CancellationToken());
    }

    /**
     * 优雅关闭本地推理进程并清理只由本工具创建的临时 worker 脚本。
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (process != null && process.isAlive()) {
            try {
                writeMessage(MAPPER.createObjectNode().put("type", "shutdown"));
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (IOException error) {
                process.destroyForcibly();
                throw new ExecutorException("Failed to send Whisper worker shutdown", error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new ExecutorException("Interrupted while closing Whisper worker", error);
            } finally {
                process = null;
                workerInput = null;
            }
        }
        if (workerScript != null && configuredWorkerScript == null) {
            try {
                Files.deleteIfExists(workerScript);
            } catch (IOException error) {
                throw new ExecutorException("Failed to remove temporary Whisper worker script", error);
            } finally {
                workerScript = null;
            }
        }
    }

    /**
     * 延迟启动 worker，并等待模型完成加载。
     *
     * @param cancellation 当前调用取消令牌
     */
    private void ensureStarted(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        if (process != null && process.isAlive()) {
            return;
        }
        stopWorker();
        try {
            Path script = resolveWorkerScript();
            process = new ProcessBuilder(
                    pythonExecutable,
                    "-u",
                    script.toString(),
                    modelId,
                    device,
                    Boolean.toString(localFilesOnly)
            )
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException error) {
            throw new ConfigurationException("Unable to start configured Python executable", error);
        }
        protocolLines = new LinkedBlockingQueue<>();
        workerInput = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startReader(process.getInputStream(), protocolLines);
        JsonNode ready = awaitResponse(startupTimeout, cancellation);
        if ("startup_error".equals(ready.path("type").asText())) {
            stopWorker();
            throw new ConfigurationException(
                    "Transformers Whisper initialization failed; install torch, transformers, accelerate and soundfile: "
                            + ready.path("error").asText("unknown initialization error")
            );
        }
        if (!"ready".equals(ready.path("type").asText())) {
            throw terminateWorker("Whisper worker did not report ready", null);
        }
    }

    /**
     * 返回打包 worker 脚本副本或校验调用方提供的脚本路径。
     *
     * @return 本次 worker 要执行的 Python 脚本
     * @throws IOException 资源缺失或临时脚本读写失败
     */
    private Path resolveWorkerScript() throws IOException {
        if (configuredWorkerScript != null) {
            if (!Files.isRegularFile(configuredWorkerScript)) {
                throw new ConfigurationException("Configured Whisper worker script is not a file");
            }
            return configuredWorkerScript.toAbsolutePath().normalize();
        }
        Path script = Files.createTempFile("smolagents-transformers-whisper-", ".py");
        try (InputStream source = TransformersWhisperTool.class.getResourceAsStream(WORKER_RESOURCE)) {
            if (source == null) {
                Files.deleteIfExists(script);
                throw new IOException("Missing packaged Transformers Whisper worker resource");
            }
            Files.copy(source, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            workerScript = script;
            return script;
        } catch (IOException error) {
            Files.deleteIfExists(script);
            throw error;
        }
    }

    /**
     * 在 daemon 线程中按行读取 Python worker 协议输出。
     *
     * @param stream worker 标准输出
     * @param destination 有界行长检查后的响应队列
     */
    private static void startReader(InputStream stream, BlockingQueue<String> destination) {
        Thread reader = new Thread(() -> {
            try (InputStreamReader input = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                StringBuilder line = new StringBuilder();
                int value;
                while ((value = input.read()) != -1) {
                    if (value == '\n') {
                        destination.put(line.toString());
                        line.setLength(0);
                    } else if (value != '\r') {
                        if (line.length() >= MAX_PROTOCOL_LINE_CHARS) {
                            destination.put("{\"type\":\"protocol_error\",\"error\":\"response line too large\"}");
                            break;
                        }
                        line.append((char) value);
                    }
                }
            } catch (IOException error) {
                destination.offer("{\"type\":\"reader_error\",\"error\":\"worker output failed\"}");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                destination.offer(EOF_MARKER);
            }
        }, "smolagents-transformers-whisper-stdout");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * 等待下一条 JSON 响应，并在超时、取消或协议错误时停止 worker。
     *
     * @param timeout 响应等待时限
     * @param cancellation 当前调用取消令牌
     * @return worker 响应对象
     */
    private JsonNode awaitResponse(Duration timeout, CancellationToken cancellation) {
        long deadline = System.nanoTime() + timeout.toNanos();
        BlockingQueue<String> lines = protocolLines;
        if (lines == null) {
            throw new ExecutorException("Transformers Whisper worker is not running");
        }
        while (true) {
            if (cancellation.isCancelled()) {
                throw terminateWorker("Transformers Whisper inference was cancelled", null);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw terminateWorker("Transformers Whisper exceeded timeout " + timeout, null);
            }
            String line;
            try {
                line = lines.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw terminateWorker("Interrupted while waiting for Transformers Whisper", error);
            }
            if (line == null) {
                if (process == null || !process.isAlive()) {
                    throw terminateWorker("Transformers Whisper worker exited unexpectedly", null);
                }
                continue;
            }
            if (EOF_MARKER.equals(line)) {
                throw terminateWorker("Transformers Whisper worker closed its output", null);
            }
            try {
                return MAPPER.readTree(line);
            } catch (JsonProcessingException error) {
                throw terminateWorker("Transformers Whisper worker returned invalid JSON", error);
            }
        }
    }

    /**
     * 向 worker stdin 写入一条 JSONL 协议消息。
     *
     * @param json 协议消息对象
     * @throws IOException worker 已退出或编码/写入失败
     */
    private void writeMessage(JsonNode json) throws IOException {
        if (workerInput == null || process == null || !process.isAlive()) {
            throw new IOException("Transformers Whisper worker is not running");
        }
        workerInput.write(MAPPER.writeValueAsString(json));
        workerInput.newLine();
        workerInput.flush();
    }

    /**
     * 停止可能处于未知协议状态的 worker 并构造执行异常。
     *
     * @param message 明确的失败原因
     * @param cause 可空根因
     * @return 待抛出的 worker 执行异常
     */
    private ExecutorException terminateWorker(String message, Throwable cause) {
        stopWorker();
        return cause == null ? new ExecutorException(message) : new ExecutorException(message, cause);
    }

    /**
     * 强制停止 worker 并清除进程通信对象。
     */
    private void stopWorker() {
        if (process != null) {
            process.destroyForcibly();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    throw new ExecutorException("Unable to terminate Transformers Whisper worker");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ExecutorException("Interrupted while stopping Transformers Whisper worker", error);
            } finally {
                process = null;
                workerInput = null;
                protocolLines = null;
            }
        }
    }

    /**
     * 按系统属性、环境变量、默认命令的优先级查找 Python executable。
     *
     * @return Python executable 路径或命令名
     */
    private static String defaultPythonExecutable() {
        String configured = System.getProperty("smolagents.python");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String environment = System.getenv("SMOLAGENTS_PYTHON");
        return environment == null || environment.isBlank() ? "python" : environment;
    }
}
