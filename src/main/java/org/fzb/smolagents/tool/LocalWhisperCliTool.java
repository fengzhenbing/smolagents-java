package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ToolExecutionException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 通过调用方指定的本地 OpenAI Whisper CLI 模型文件执行音频转录。
 *
 * <p>可执行文件和 checkpoint 均必须显式配置并已存在；参数使用 {@link ProcessBuilder}
 * 列表传递，不经过 shell，不下载模型。每次调用将音频写入独立临时目录，运行结束后只清理
 * 本工具创建的输入、输出文件和空临时目录。此适配器使用 OpenAI Whisper CLI，不是
 * Transformers pipeline，也不用于不可信 CLI 二进制。</p>
 */
public final class LocalWhisperCliTool implements Tool {
    private static final int MAX_SUPPORTED_AUDIO_BYTES = 25 * 1024 * 1024;
    private static final int MAX_TRANSCRIPT_BYTES = 1024 * 1024;

    private final Path executable;
    private final Path modelFile;
    private final Duration timeout;
    private final int maxAudioBytes;
    private final CommandRunner commandRunner;
    private final ToolSpec spec = new ToolSpec(
            "local_whisper",
            "Transcribe audio with an explicitly configured local OpenAI Whisper CLI and checkpoint.",
            SpeechToTextTool.createSchema(),
            true
    );

    /**
     * 创建本地 Whisper CLI 工具；不自动发现二进制文件或下载模型。
     *
     * @param executable OpenAI Whisper CLI 可执行文件
     * @param modelFile 本机已存在的 Whisper checkpoint 文件
     * @param timeout 单次 Whisper 命令最大运行时长
     * @param maxAudioBytes 允许的最大解码音频字节数，最多 25 MiB
     */
    public LocalWhisperCliTool(Path executable, Path modelFile, Duration timeout, int maxAudioBytes) {
        this(executable, modelFile, timeout, maxAudioBytes, LocalWhisperCliTool::runWhisperProcess);
    }

    /**
     * 创建转录工具并绑定一个受控命令执行器。
     *
     * @param executable OpenAI Whisper CLI 可执行文件
     * @param modelFile 本机 checkpoint
     * @param timeout 命令运行时限
     * @param maxAudioBytes 最大音频尺寸
     * @param commandRunner 实际命令执行器
     */
    LocalWhisperCliTool(
            Path executable,
            Path modelFile,
            Duration timeout,
            int maxAudioBytes,
            CommandRunner commandRunner
    ) {
        this.executable = requireLocalFile(executable, "Whisper CLI executable");
        this.modelFile = requireLocalFile(modelFile, "Whisper model checkpoint");
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofDays(1)) > 0) {
            throw new ConfigurationException("Whisper timeout must be positive and at most one day");
        }
        if (maxAudioBytes < 1 || maxAudioBytes > MAX_SUPPORTED_AUDIO_BYTES) {
            throw new ConfigurationException("maxAudioBytes must be between 1 and 25 MiB");
        }
        this.timeout = timeout;
        this.maxAudioBytes = maxAudioBytes;
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner");
    }

    /**
     * 返回本地 Whisper 转录工具 schema。
     *
     * @return base64 音频及可选 language 输入 schema
     */
    @Override
    public ToolSpec spec() {
        return spec;
    }

    /**
     * 解码音频、在临时目录执行本地模型并返回转录文本。
     *
     * @param arguments 包含 audio.data、audio.mimeType 和可选 language 的参数
     * @param context 当前工具调用上下文
     * @return Whisper 识别的纯文本
     */
    @Override
    public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
        Objects.requireNonNull(context, "context");
        CancellationToken cancellation = context.cancellation();
        cancellation.throwIfCancelled();
        JsonNode audio = arguments.path("audio");
        String encoded = audio.path("data").asText("");
        String mimeType = audio.path("mimeType").asText("");
        // 步骤一：拒绝过大或格式无效的音频，在创建临时文件前完成解码校验。
        if (encoded.isBlank()) {
            throw new ToolExecutionException("audio.data must contain base64-encoded audio");
        }
        long maximumEncodedLength = ((long) maxAudioBytes + 2) / 3 * 4;
        if (encoded.length() > maximumEncodedLength) {
            throw new ToolExecutionException("Encoded audio exceeds the configured maximum size");
        }
        if (!mimeType.matches("audio/[A-Za-z0-9.+-]+")) {
            throw new ToolExecutionException("audio.mimeType must be an audio MIME type");
        }
        byte[] audioBytes = SpeechToTextTool.decodeAudio(encoded);
        if (audioBytes.length == 0 || audioBytes.length > maxAudioBytes) {
            throw new ToolExecutionException("Audio size is outside the configured range");
        }
        String language = SpeechToTextTool.audioLanguage(arguments);
        String extension = localAudioExtension(mimeType);

        // 步骤二：以独立目录保存本次输入和 JSON 输出，退出时由 workspace 精确清理。
        try (TemporaryWorkspace workspace = TemporaryWorkspace.create(
                extension
        )) {
            Files.write(workspace.audioFile(), audioBytes, StandardOpenOption.CREATE_NEW);
            List<String> command = buildCommand(workspace, language);
            int exitCode = commandRunner.run(command, workspace.directory(), timeout, cancellation);
            cancellation.throwIfCancelled();
            if (exitCode != 0) {
                throw new ToolExecutionException("Local Whisper CLI exited with code " + exitCode);
            }
            if (!Files.isRegularFile(workspace.transcriptFile(), LinkOption.NOFOLLOW_LINKS)) {
                throw new ToolExecutionException("Local Whisper CLI did not create its JSON transcript");
            }
            long outputSize = Files.size(workspace.transcriptFile());
            if (outputSize > MAX_TRANSCRIPT_BYTES) {
                throw new ToolExecutionException("Local Whisper transcript exceeded the maximum size");
            }
            // 步骤三：只接受预期 JSON 文件中的 text 字段作为工具结果。
            String transcript = Files.readString(workspace.transcriptFile(), StandardCharsets.UTF_8);
            return ToolResult.text(SpeechToTextTool.parseTranscript(transcript));
        } catch (IOException error) {
            throw new ToolExecutionException("Local Whisper file operation failed", error);
        }
    }

    /**
     * 组装无 shell 的固定 CLI 参数列表。
     *
     * @param workspace 当前调用临时目录
     * @param language 可选 language 提示
     * @return ProcessBuilder 命令数组
     */
    private List<String> buildCommand(TemporaryWorkspace workspace, String language) {
        List<String> command = new ArrayList<>(List.of(
                executable.toString(),
                workspace.audioFile().toString(),
                "--model",
                modelFile.toString(),
                "--output_dir",
                workspace.directory().toString(),
                "--output_format",
                "json",
                "--task",
                "transcribe"
        ));
        if (language != null) {
            if (language.length() > 32) {
                throw new ToolExecutionException("language code exceeds the maximum length");
            }
            command.add("--language");
            command.add(language);
        }
        return List.copyOf(command);
    }

    /**
     * 将明确支持的音频 MIME type 映射为 ffmpeg 可识别的文件扩展名。
     *
     * @param mimeType 音频 MIME type
     * @return 安全临时文件扩展名
     */
    private static String localAudioExtension(String mimeType) {
        return switch (mimeType.toLowerCase(java.util.Locale.ROOT)) {
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/wav", "audio/x-wav", "audio/wave" -> "wav";
            case "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a";
            case "audio/webm" -> "webm";
            case "audio/ogg", "audio/oga" -> "ogg";
            case "audio/flac", "audio/x-flac" -> "flac";
            case "audio/aac", "audio/aacp" -> "aac";
            default -> throw new ToolExecutionException(
                    "Local Whisper CLI does not support audio MIME type " + mimeType
            );
        };
    }

    /**
     * 启动 Whisper 子进程，并在取消或到期时强制结束进程。
     *
     * @param command 固定参数列表
     * @param workingDirectory 临时工作目录
     * @param timeout 最长运行时间
     * @param cancellation 当前取消令牌
     * @return 进程退出码
     */
    private static int runWhisperProcess(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            CancellationToken cancellation
    ) {
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException error) {
            throw new ToolExecutionException("Unable to start the configured Whisper CLI", error);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            // 每 50ms 检查取消/超时，避免本地 CLI 在 Agent 取消后继续占用 GPU 或 CPU。
            while (true) {
                cancellation.throwIfCancelled();
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    process.destroyForcibly();
                    throw new ToolExecutionException("Local Whisper CLI exceeded its timeout");
                }
                if (process.waitFor(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(50)),
                        TimeUnit.NANOSECONDS)) {
                    return process.exitValue();
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ToolExecutionException("Waiting for local Whisper CLI was interrupted", error);
        } catch (RuntimeException error) {
            process.destroyForcibly();
            throw error;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 解析调用方配置的本地二进制或模型 checkpoint。
     *
     * @param path 输入路径
     * @param description 路径用途
     * @return 已解析的普通文件路径
     */
    private static Path requireLocalFile(Path path, String description) {
        Objects.requireNonNull(path, description);
        try {
            Path realPath = path.toRealPath();
            if (!Files.isRegularFile(realPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new ConfigurationException(description + " must be an existing regular file");
            }
            return realPath;
        } catch (IOException error) {
            throw new ConfigurationException("Unable to resolve " + description, error);
        }
    }

    /**
     * 负责单次转录的隔离目录及固定临时文件。
     */
    private static final class TemporaryWorkspace implements AutoCloseable {
        private final Path directory;
        private final Path audioFile;
        private final Path transcriptFile;

        /**
         * 创建包含固定输入/JSON 输出路径的临时工作区。
         *
         * @param directory 临时目录
         * @param extension 输入音频扩展名
         */
        private TemporaryWorkspace(Path directory, String extension) {
            this.directory = directory;
            this.audioFile = directory.resolve("input." + extension);
            this.transcriptFile = directory.resolve("input.json");
        }

        /**
         * 创建本次调用独有的系统临时目录。
         *
         * @param extension 输入音频文件扩展名
         * @return 临时工作区
         * @throws IOException 临时目录创建失败
         */
        private static TemporaryWorkspace create(String extension) throws IOException {
            return new TemporaryWorkspace(Files.createTempDirectory("smolagents-whisper-"), extension);
        }

        /**
         * 返回本次转录隔离目录。
         *
         * @return 临时目录路径
         */
        private Path directory() {
            return directory;
        }

        /**
         * 返回写入的临时音频路径。
         *
         * @return 输入音频文件路径
         */
        private Path audioFile() {
            return audioFile;
        }

        /**
         * 返回 CLI 预期生成的 JSON 文件路径。
         *
         * @return 转录 JSON 输出路径
         */
        private Path transcriptFile() {
            return transcriptFile;
        }

        /**
         * 删除本工具创建的固定文件和其独有临时目录，不执行宽泛递归删除。
         *
         * @throws IOException 临时文件清理失败
         */
        @Override
        public void close() throws IOException {
            Files.deleteIfExists(audioFile);
            Files.deleteIfExists(transcriptFile);
            Files.deleteIfExists(directory);
        }
    }

    /**
     * 本地 Whisper CLI 的命令边界，用于隔离进程编排测试。
     */
    @FunctionalInterface
    interface CommandRunner {
        /**
         * 执行一次命令列表。
         *
         * @param command 命令和参数
         * @param workingDirectory 临时工作目录
         * @param timeout 超时时长
         * @param cancellation 取消令牌
         * @return 进程退出码
         */
        int run(
                List<String> command,
                Path workingDirectory,
                Duration timeout,
                CancellationToken cancellation
        ) throws IOException;
    }
}
