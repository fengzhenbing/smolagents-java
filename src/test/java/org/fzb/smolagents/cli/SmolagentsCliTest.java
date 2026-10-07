package org.fzb.smolagents.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 CLI 参数解析、帮助输出和失败退出码，不访问模型或公网服务。
 */
class SmolagentsCliTest {
    /**
     * 验证命令行解析 model、action、多个工具、步数和时限。
     */
    @Test
    void parsesSupportedOptionsAndJoinsPromptWords() {
        CommandLineOptions options = SmolagentsCli.parseArguments(new String[]{
                "--model-type", "huggingface",
                "--model-id", "org/model",
                "--provider", "test-provider",
                "--action-type", "tool_calling",
                "--tools", "web_search,wikipedia_search",
                "--imports", "numpy,json",
                "--import", "pathlib",
                "--tool", "user_input",
                "--max-steps", "9",
                "--timeout-seconds", "45",
                "answer", "this", "question"
        });

        assertEquals("huggingface", options.modelType());
        assertEquals("org/model", options.modelId());
        assertEquals("test-provider", options.provider());
        assertEquals("tool_calling", options.actionType());
        assertEquals(List.of("web_search", "wikipedia_search", "user_input"), options.tools());
        assertEquals(List.of("numpy", "json", "pathlib"), options.additionalAuthorizedImports());
        assertEquals(9, options.maxSteps());
        assertEquals(Duration.ofSeconds(45), options.timeout());
        assertEquals("answer this question", options.prompt());
    }

    /**
     * 验证无 prompt 时采用项目默认值并允许通过 stdin 进入简易交互模式。
     */
    @Test
    void defaultsToLocalOllamaAndCodeAgent() {
        CommandLineOptions options = SmolagentsCli.parseArguments(new String[0]);

        assertEquals("ollama", options.modelType());
        assertEquals("gemma4:26b", options.modelId());
        assertEquals("code", options.actionType());
        assertEquals(List.of("web_search"), options.tools());
        assertEquals(6, options.maxSteps());
        assertEquals("api", options.whisperBackend());
        assertTrue(options.stream());
        assertNull(options.prompt());
    }

    /**
     * 验证浏览器工具与本地 Transformers Whisper 的显式配置选项。
     */
    @Test
    void parsesVisualBrowserAndTransformersWhisperConfiguration() {
        CommandLineOptions options = SmolagentsCli.parseArguments(new String[]{
                "--model-type", "ollama",
                "--vision",
                "--action-type", "tool_calling",
                "--chrome-path", "C:\\tools\\chrome.exe",
                "--browser-headless",
                "--tools", "visual_browser,transcriber",
                "--whisper-backend", "transformers",
                "--whisper-model", "D:\\models\\whisper",
                "--whisper-python", "D:\\venv\\Scripts\\python.exe",
                "--whisper-device", "cuda:0",
                "--allow-whisper-download",
                "task"
        });

        assertEquals("C:\\tools\\chrome.exe", options.chromePath());
        assertTrue(options.browserHeadless());
        assertTrue(options.modelVision());
        assertEquals("transformers", options.whisperBackend());
        assertEquals("D:\\models\\whisper", options.whisperModel());
        assertEquals("D:\\venv\\Scripts\\python.exe", options.whisperPython());
        assertEquals("cuda:0", options.whisperDevice());
        assertTrue(options.allowWhisperDownload());
    }

    /**
     * 验证浏览器工具没有视觉能力声明或 Chrome binary 时拒绝配置。
     */
    @Test
    void requiresChromePathAndVisionForVisualBrowser() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SmolagentsCli.parseArguments(new String[]{"--tools", "visual_browser"})
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SmolagentsCli.parseArguments(new String[]{
                        "--model-type", "ollama",
                        "--chrome-path", "C:\\tools\\chrome.exe",
                        "--tools", "visual_browser"
                })
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SmolagentsCli.parseArguments(new String[]{
                        "--model-type", "ollama",
                        "--vision",
                        "--chrome-path", "C:\\tools\\chrome.exe",
                        "--tools", "visual_browser"
                })
        );
    }

    /**
     * 验证 CLI 可显式关闭流式 delta 并只输出最终答案。
     */
    @Test
    void supportsNoStreamFlag() {
        CommandLineOptions options = SmolagentsCli.parseArguments(
                new String[]{"--no-stream", "task"}
        );

        assertEquals(false, options.stream());
        assertEquals("task", options.prompt());
    }

    /**
     * 验证 help 不创建模型，且参数错误有非零退出码与使用说明。
     */
    @Test
    void printsHelpAndRejectsInvalidOptionsWithoutModelCalls() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        SmolagentsCli cli = new SmolagentsCli();

        int helpStatus = cli.run(
                new String[]{"--help"},
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        int invalidStatus = cli.run(
                new String[]{"--max-steps", "0", "task"},
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );

        assertEquals(0, helpStatus);
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("Usage: smolagents"));
        assertEquals(2, invalidStatus);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("positive integer"));
    }

    /**
     * 验证未知工具和空交互 prompt 以配置错误码退出。
     */
    @Test
    void rejectsUnknownToolsAndEmptyInteractivePrompt() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream error = new PrintStream(stderr, true, StandardCharsets.UTF_8);
        SmolagentsCli cli = new SmolagentsCli();

        int unknownToolStatus = cli.run(
                new String[]{"--tools", "not-a-tool", "task"},
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                error
        );
        int emptyPromptStatus = cli.run(
                new String[0],
                new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                error
        );

        assertEquals(2, unknownToolStatus);
        assertEquals(2, emptyPromptStatus);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("Unknown built-in tool"));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("non-empty task prompt"));
    }

    /**
     * 验证 provider 与 model type 不兼容时在解析阶段拒绝配置。
     */
    @Test
    void rejectsProviderFlagForNonHuggingFaceModel() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SmolagentsCli.parseArguments(new String[]{"--provider", "test-provider"})
        );
    }

    /**
     * 验证 CLI 以 stream 模式运行 CodeAgent，并在模型流结束后输出最终答案。
     *
     * @throws Exception 测试 endpoint 启动或 Agent 执行失败时抛出
     */
    @Test
    void streamsOpenAiCompatibleCodeAgentOutput() throws Exception {
        Assumptions.assumeTrue(isPythonAvailable(), "CPython is required for the CodeAgent CLI test");
        try (StubServer server = new StubServer()) {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            SmolagentsCli cli = new SmolagentsCli();

            int exitCode = cli.run(
                    new String[]{
                            "--model-type", "openai-compatible",
                            "--api-base", server.baseUrl().toString(),
                            "--model-id", "cli-test",
                            "--action-type", "code",
                            "--tools", "",
                            "--max-steps", "2",
                            "--timeout-seconds", "20",
                            "Return the integer forty-two."
                    },
                    new ByteArrayInputStream(new byte[0]),
                    new PrintStream(stdout, true, StandardCharsets.UTF_8),
                    new PrintStream(stderr, true, StandardCharsets.UTF_8)
            );

            assertEquals(0, exitCode, stderr.toString(StandardCharsets.UTF_8));
            assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("final_answer(42)"));
            assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("42"));
            assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("not a security sandbox"));
        }
    }

    /**
     * 确认当前进程可运行默认 Python 命令。
     *
     * @return Python 命令可执行时为 true
     */
    private static boolean isPythonAvailable() {
        try {
            Process process = new ProcessBuilder("python", "--version").redirectErrorStream(true).start();
            return process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * 在本机返回固定 OpenAI-compatible SSE 模型输出。
     */
    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;

        /**
         * 启动 Chat Completions SSE stub。
         *
         * @throws IOException 无法绑定本机端口时抛出
         */
        private StubServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    String body = """
                            data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"```python\\nfinal_answer(42)\\n```"}}]}

                            data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                            data: [DONE]

                            """;
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }

        /**
         * 返回 OpenAI-compatible API base URL。
         *
         * @return 本地 /v1 URI
         */
        private java.net.URI baseUrl() {
            return java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        }

        /**
         * 停止本地 SSE 服务。
         */
        @Override
        public void close() {
            server.stop(0);
        }
    }

}
