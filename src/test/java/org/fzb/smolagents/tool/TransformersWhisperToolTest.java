package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ExecutorException;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.model.ContentPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 JSONL 进程 fixture 验证可选 Transformers Whisper worker 的工具协议。
 */
class TransformersWhisperToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    private Path temporaryDirectory;

    /**
     * 验证预热后的同一 worker 可接收多条音频请求，并传递模型与语言设置。
     *
     * @throws Exception 测试脚本复制或转录调用失败
     */
    @Test
    void keepsAResidentWorkerAndReturnsWhisperTranscripts() throws Exception {
        Path worker = fixtureWorker();
        try (TransformersWhisperTool tool = tool(worker, "local/checkpoint", true, "cpu")) {
            tool.warmUp();
            assertEquals(
                    "local/checkpoint:cpu:true:en:3",
                    text(tool.execute(arguments("AQID", "audio/wav", "en"), context(new CancellationToken())))
            );
            assertEquals(
                    "local/checkpoint:cpu:true:None:2",
                    text(tool.execute(arguments("AQI=", "audio/wav", null), context(new CancellationToken())))
            );
        }
    }

    /**
     * 验证下载须显式启用、GPU 选项受到配置校验，并处理无效音频协议输入。
     */
    @Test
    void validatesModelDownloadDeviceAndAudioPolicies() throws Exception {
        Path worker = fixtureWorker();
        assertThrows(
                ConfigurationException.class,
                () -> tool(worker, "model", true, "cuda:0 junk")
        );
        try (TransformersWhisperTool tool = tool(worker, "model", false, "auto")) {
            assertEquals("model:auto:false:None:1",
                    text(tool.execute(arguments("AQ==", "audio/wav", null), context(new CancellationToken()))));
            assertThrows(
                    ToolExecutionException.class,
                    () -> tool.execute(arguments("not base64!", "audio/wav", null), context(new CancellationToken()))
            );
        }
    }

    /**
     * 验证 worker 缺依赖、意外 EOF、乱序请求 ID、畸形 JSON 和错误输出均不会伪造成功。
     *
     * @throws Exception fixture worker 无法建立时抛出
     */
    @Test
    void reportsWorkerStartupAndProtocolFailuresExplicitly() throws Exception {
        Path worker = fixtureWorker();
        TransformersWhisperTool startupFailure = tool(worker, "startup-error", true, "cpu");
        ConfigurationException startup = assertThrows(ConfigurationException.class, startupFailure::warmUp);
        assertTrue(startup.getMessage().contains("initialization failed"));
        startupFailure.close();

        TransformersWhisperTool earlyEof = tool(worker, "startup-eof", true, "cpu");
        ExecutorException eof = assertThrows(ExecutorException.class, earlyEof::warmUp);
        assertTrue(eof.getMessage().contains("closed its output"));
        earlyEof.close();

        try (TransformersWhisperTool mismatch = tool(worker, "mismatch", true, "cpu")) {
            ToolExecutionException failure = assertThrows(
                    ToolExecutionException.class,
                    () -> mismatch.execute(arguments("AQ==", "audio/wav", null), context(new CancellationToken()))
            );
            assertTrue(failure.getMessage().contains("mismatched request ID"));
        }

        try (TransformersWhisperTool malformed = tool(worker, "invalid-response", true, "cpu")) {
            ToolExecutionException failure = assertThrows(
                    ToolExecutionException.class,
                    () -> malformed.execute(arguments("AQ==", "audio/wav", null), context(new CancellationToken()))
            );
            assertTrue(failure.getMessage().contains("invalid JSON"));
        }

        try (TransformersWhisperTool empty = tool(worker, "empty-transcript", true, "cpu")) {
            assertThrows(
                    ToolExecutionException.class,
                    () -> empty.execute(arguments("AQ==", "audio/wav", null), context(new CancellationToken()))
            );
        }

        try (TransformersWhisperTool inferenceError = tool(worker, "error-response", true, "cpu")) {
            ToolExecutionException failure = assertThrows(
                    ToolExecutionException.class,
                    () -> inferenceError.execute(
                            arguments("AQ==", "audio/wav", null),
                            context(new CancellationToken())
                    )
            );
            assertTrue(failure.getMessage().contains("fixture inference error"));
            assertEquals(
                    "error-response:cpu:true:None:1",
                    text(inferenceError.execute(
                            arguments("AQ==", "audio/wav", null),
                            context(new CancellationToken())
                    ))
            );
        }
    }

    /**
     * 验证取消模型生成会终止本地 worker，而不是等待静默模型响应。
     *
     * @throws Exception 测试 worker 或线程等待失败
     */
    @Test
    void cancellationTerminatesTheLocalWorker() throws Exception {
        CancellationToken cancellation = new CancellationToken();
        TransformersWhisperTool tool = tool(fixtureWorker(), "hang", true, "cpu");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult> future = executor.submit(
                    () -> tool.execute(arguments("AQ==", "audio/wav", null), context(cancellation))
            );
            Thread.sleep(150);
            cancellation.cancel();
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> future.get(3, TimeUnit.SECONDS)
            );
            assertInstanceOf(ToolExecutionException.class, failure.getCause());
        } finally {
            tool.close();
            executor.shutdownNow();
        }
    }

    private Path fixtureWorker() throws IOException {
        Path path = temporaryDirectory.resolve("whisper-worker-fixture.py");
        try (InputStream input = getClass().getResourceAsStream("/whisper_worker_fixture.py")) {
            if (input == null) {
                throw new IOException("Missing test Transformers Whisper worker fixture");
            }
            Files.copy(input, path);
        }
        return path;
    }

    private TransformersWhisperTool tool(Path worker, String model, boolean localFilesOnly, String device) {
        return new TransformersWhisperTool(
                model,
                pythonExecutable(),
                device,
                localFilesOnly,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                1024,
                worker
        );
    }

    private static String pythonExecutable() {
        String configured = System.getProperty("smolagents.python");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String environment = System.getenv("SMOLAGENTS_PYTHON");
        return environment == null || environment.isBlank() ? "python" : environment;
    }

    private static com.fasterxml.jackson.databind.JsonNode arguments(String audio, String mime, String language) {
        var input = MAPPER.createObjectNode();
        input.set("audio", MAPPER.createObjectNode().put("data", audio).put("mimeType", mime));
        if (language != null) {
            input.put("language", language);
        }
        return input;
    }

    private static ToolExecutionContext context(CancellationToken cancellation) {
        return new ToolExecutionContext(UUID.randomUUID(), "transformers-whisper-test", cancellation);
    }

    private static String text(ToolResult result) {
        return assertInstanceOf(ContentPart.Text.class, result.content().getFirst()).text();
    }
}
