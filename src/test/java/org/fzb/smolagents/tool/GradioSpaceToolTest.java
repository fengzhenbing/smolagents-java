package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 loopback Gradio API stub 验证 Space tool 参数映射和结果处理。
 */
class GradioSpaceToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    private Path temporaryDirectory;

    /**
     * 验证参数 schema、Gradio data 顺序、认证和 SSE 文本结果。
     *
     * @throws Exception 本机 server 或 JSON fixture 处理失败时抛出
     */
    @Test
    void sendsOrderedArgumentsAndReturnsTextFromSse() throws Exception {
        AtomicReference<String> postBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        try (StubServer server = new StubServer("[\"hello from Space\"]", postBody, authorization)) {
            GradioSpaceTool tool = create(server.baseUrl(), "text_tool", "test-space-token");
            ToolRegistry registry = new ToolRegistry();
            registry.register(tool);
            ToolResult result = registry.invoke(
                    new ToolCall("space-call", "text_tool", MAPPER.readTree(
                            "{\"question\":\"world\",\"limit\":3}"
                    )),
                    context("space-call")
            );

            assertEquals("hello from Space", text(result));
            assertEquals("[\"world\",3]", MAPPER.readTree(postBody.get()).path("data").toString());
            assertEquals("Bearer test-space-token", authorization.get());
        }
    }

    /**
     * 验证标准 Gradio 文件 URL 映射为安全的图片 URI 内容片段。
     *
     * @throws Exception 本机 server 或 JSON fixture 处理失败时抛出
     */
    @Test
    void mapsImageUrlToTypedToolContent() throws Exception {
        try (StubServer server = new StubServer("[{\"url\":\"https://cdn.example.test/generated.png\"}]",
                null, null)) {
            GradioSpaceTool tool = new GradioSpaceTool(
                    "image_tool",
                    "Generate an image.",
                    server.baseUrl(),
                    "predict",
                    List.of(),
                    null,
                    Duration.ofSeconds(2),
                    true
            );

            ToolResult result = tool.execute(MAPPER.createObjectNode(), context("image-call"));

            ContentPart.Image image = assertInstanceOf(ContentPart.Image.class, result.content().getFirst());
            assertEquals(URI.create("https://cdn.example.test/generated.png"), image.uri());
            assertEquals("image/png", image.mimeType());
        }
    }

    /**
     * 验证同一 Space origin 的媒体 URL 被有界读取为图片 bytes。
     *
     * @throws Exception loopback server 或 HTTP fixture 处理失败时抛出
     */
    @Test
    void fetchesSameOriginImageBytesForVisionCapableConsumers() throws Exception {
        try (StubServer server = new StubServer(
                "[{\"url\":\"__SPACE_BASE__/gradio_api/file/image.png\"}]",
                null,
                null
        )) {
            GradioSpaceTool tool = new GradioSpaceTool(
                    "same_origin_image",
                    "Return a generated image.",
                    server.baseUrl(),
                    "predict",
                    List.of(),
                    null,
                    Duration.ofSeconds(2),
                    true
            );

            ToolResult result = tool.execute(MAPPER.createObjectNode(), context("same-origin-image"));

            ContentPart.Image image = assertInstanceOf(ContentPart.Image.class, result.content().getFirst());
            assertEquals("image/png", image.mimeType());
            assertArrayEquals(new byte[]{1, 2, 3}, image.data());
        }
    }

    /**
     * 验证从 Gradio API info 发现参数类型、默认值与认证信息。
     *
     * @throws Exception loopback server 或 JSON fixture 处理失败时抛出
     */
    @Test
    void discoversNamedApiSchemaWithoutExecutingSpaceCode() throws Exception {
        try (StubServer server = new StubServer("[\"hello\"]", null, null)) {
            GradioSpaceTool tool = GradioSpaceTool.discover(
                    "discovered_tool",
                    "Tool created from public API metadata.",
                    server.baseUrl(),
                    "predict",
                    "space-token",
                    Duration.ofSeconds(2),
                    true
            );
            JsonNode schema = tool.spec().inputSchema();

            assertEquals("string", schema.path("properties").path("prompt").path("type").asText());
            assertEquals("Step count.", schema.path("properties").path("steps").path("description").asText());
            assertEquals("[\"integer\",\"null\"]",
                    schema.path("properties").path("steps").path("type").toString());
            assertEquals("[\"prompt\"]", schema.path("required").toString());
            assertEquals("Bearer space-token", server.infoAuthorization.get());
        }
    }

    /**
     * 验证显式白名单内的本地文件通过 Gradio upload API 传输并转换成 FileData。
     *
     * @throws Exception 临时目录、测试 server 或文件处理失败时抛出
     */
    @Test
    void uploadsOnlyAuthorizedLocalFilesBeforeCallingSpace() throws Exception {
        Path allowedRoot = Files.createDirectory(temporaryDirectory.resolve("allowed"));
        Path deniedRoot = Files.createDirectory(temporaryDirectory.resolve("denied"));
        Path uploadFile = Files.writeString(allowedRoot.resolve("sample.png"), "image-data");
        Path deniedFile = Files.writeString(deniedRoot.resolve("secret.png"), "private");
        AtomicReference<String> uploadBody = new AtomicReference<>();
        AtomicReference<String> callBody = new AtomicReference<>();
        try (StubServer server = new StubServer("[\"uploaded\"]", null, null, uploadBody, callBody)) {
            GradioSpaceTool tool = GradioSpaceTool.discover(
                    "file_tool",
                    "Upload an image to the Space.",
                    server.baseUrl(),
                    "upload",
                    "space-token",
                    Duration.ofSeconds(2),
                    true,
                    new GradioSpaceTool.FileUploadPolicy(List.of(allowedRoot), 1_024)
            );

            ToolResult result = tool.execute(fileArguments(uploadFile), context("file-call"));

            assertEquals("uploaded", text(result));
            assertTrue(uploadBody.get().contains("name=\"files\"; filename=\"sample.png\""));
            assertTrue(uploadBody.get().contains("image-data"));
            assertEquals("Bearer space-token", server.uploadAuthorization.get());
            assertEquals("filepath", tool.spec().inputSchema()
                    .path("properties").path("image").path("format").asText());
            JsonNode fileData = MAPPER.readTree(callBody.get()).path("data").get(0);
            assertEquals("/tmp/gradio/opaque-upload", fileData.path("path").asText());
            assertEquals("gradio.FileData", fileData.path("meta").path("_type").asText());

            ToolExecutionException denied = assertThrows(
                    ToolExecutionException.class,
                    () -> tool.execute(fileArguments(deniedFile), context("denied-file-call"))
            );
            assertTrue(denied.getMessage().contains("outside"));

            GradioSpaceTool limitedTool = GradioSpaceTool.discover(
                    "limited_file_tool",
                    "Upload a small image.",
                    server.baseUrl(),
                    "upload",
                    null,
                    Duration.ofSeconds(2),
                    true,
                    new GradioSpaceTool.FileUploadPolicy(List.of(allowedRoot), 4)
            );
            ToolExecutionException oversized = assertThrows(
                    ToolExecutionException.class,
                    () -> limitedTool.execute(fileArguments(uploadFile), context("oversized-file-call"))
            );
            assertTrue(oversized.getMessage().contains("size limit"));
            assertEquals(1, server.uploadRequests.get());
        }
    }

    /**
     * 验证 Gradio SSE 响应超过上限时在被完整缓冲前失败。
     *
     * @throws Exception loopback HTTP server 启动失败时抛出
     */
    @Test
    void rejectsOversizedSseResponse() throws Exception {
        String oversizedData = "x".repeat(4 * 1024 * 1024);
        try (StubServer server = new StubServer("[\"" + oversizedData + "\"]", null, null)) {
            ToolExecutionException error = assertThrows(
                    ToolExecutionException.class,
                    () -> create(server.baseUrl(), "large_response_tool", null)
                            .execute(MAPPER.createObjectNode(), context("large-response"))
            );
            assertTrue(error.getMessage().contains("maximum size"), error.getMessage());
        }
    }

    /**
     * 验证 Gradio error SSE 和不安全 endpoint 配置会显式失败。
     *
     * @throws Exception 本机 server 或 JSON fixture 处理失败时抛出
     */
    @Test
    void reportsGradioErrorsAndRequiresExplicitInsecureHttpOptIn() throws Exception {
        try (StubServer server = new StubServer("[null,\"space failed\"]", null, null)) {
            ToolExecutionException failure = assertThrows(
                    ToolExecutionException.class,
                    () -> create(server.baseUrl(), "error_tool", null)
                            .execute(MAPPER.createObjectNode(), context("space-error"))
            );
            assertTrue(failure.getMessage().contains("space failed"));
        }
        assertThrows(
                ConfigurationException.class,
                () -> new GradioSpaceTool(
                        "unsafe",
                        "test",
                        URI.create("http://example.com"),
                        "predict",
                        List.of(),
                        Duration.ofSeconds(1)
                )
        );
        assertThrows(
                ConfigurationException.class,
                () -> new GradioSpaceTool(
                        "missing_policy",
                        "test",
                        URI.create("https://example.com"),
                        "predict",
                        List.of(new GradioSpaceTool.Argument(
                                "image",
                                "Image input.",
                                GradioSpaceTool.ValueType.FILE
                        )),
                        null,
                        Duration.ofSeconds(1),
                        false
                )
        );
        assertThrows(
                ConfigurationException.class,
                () -> new GradioSpaceTool(
                        "private_space",
                        "test",
                        URI.create("https://127.0.0.1"),
                        "predict",
                        List.of(),
                        Duration.ofSeconds(1)
                )
        );
    }

    /**
     * 创建具有两个顺序参数的 loopback Space 工具。
     *
     * @param baseUrl 本地 Gradio Space
     * @param name Agent 工具名
     * @param apiKey 可选测试 key
     * @return configured tool
     */
    private static GradioSpaceTool create(URI baseUrl, String name, String apiKey) {
        return new GradioSpaceTool(
                name,
                "Answer the question.",
                baseUrl,
                "predict",
                List.of(
                        new GradioSpaceTool.Argument("question", "Question text.", GradioSpaceTool.ValueType.STRING),
                        new GradioSpaceTool.Argument("limit", "Maximum count.", GradioSpaceTool.ValueType.INTEGER)
                ),
                apiKey,
                Duration.ofSeconds(2),
                true
        );
    }

    /**
     * 创建每次独立的工具调用上下文。
     *
     * @param callId 模型工具调用 ID
     * @return tool context
     */
    private static ToolExecutionContext context(String callId) {
        return new ToolExecutionContext(UUID.randomUUID(), callId, new CancellationToken());
    }

    /**
     * 提取纯文本工具结果。
     *
     * @param result Gradio tool result
     * @return 文本值
     */
    private static String text(ToolResult result) {
        return assertInstanceOf(ContentPart.Text.class, result.content().getFirst()).text();
    }

    /**
     * 创建引用单个本地文件路径的工具调用参数。
     *
     * @param path 调用方提供的本地路径
     * @return file input JSON
     */
    private static JsonNode fileArguments(Path path) {
        return MAPPER.createObjectNode().put("image", path.toString());
    }

    /**
     * 以可配置 complete event data 回复 Gradio REST/SSE 请求。
     */
    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;
        private final String resultData;
        private final AtomicReference<String> postBody;
        private final AtomicReference<String> authorization;
        private final AtomicReference<String> infoAuthorization = new AtomicReference<>();
        private final AtomicReference<String> uploadBody;
        private final AtomicReference<String> callBody;
        private final AtomicReference<String> uploadAuthorization = new AtomicReference<>();
        private final AtomicInteger uploadRequests = new AtomicInteger();

        /**
         * 启动本地 /gradio_api/call/predict endpoint。
         *
         * @param resultData JSON encoded complete event data
         * @param postBody 接收 JSON POST body 的可选引用
         * @param authorization 接收 bearer token 的可选引用
         * @throws IOException 无法监听本机 HTTP 端口时抛出
         */
        private StubServer(
                String resultData,
                AtomicReference<String> postBody,
                AtomicReference<String> authorization
        ) throws IOException {
            this(resultData, postBody, authorization, null, null);
        }

        /**
         * 启动本地 stub，并可选捕获 Gradio 文件上传和 call JSON 请求。
         *
         * @param resultData SSE complete event 的 JSON data
         * @param postBody 接收 JSON call body 的可选引用
         * @param authorization 接收 call bearer token 的可选引用
         * @param uploadBody 接收 multipart 文件上传 body 的可选引用
         * @param callBody 接收 Gradio call JSON body 的可选引用
         * @throws IOException 无法监听本机 HTTP 端口时抛出
         */
        private StubServer(
                String resultData,
                AtomicReference<String> postBody,
                AtomicReference<String> authorization,
                AtomicReference<String> uploadBody,
                AtomicReference<String> callBody
        ) throws IOException {
            this.resultData = resultData;
            this.postBody = postBody;
            this.authorization = authorization;
            this.uploadBody = uploadBody;
            this.callBody = callBody;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/gradio_api/info", exchange -> {
                try (exchange) {
                    infoAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    String body = """
                            {
                              "named_endpoints": {
                                "/predict": {
                                  "parameters": [
                                    {
                                      "parameter_name": "prompt",
                                      "type": {"type": "string"},
                                      "python_type": {"description": "Input prompt."},
                                      "parameter_has_default": false
                                    },
                                    {
                                      "parameter_name": "steps",
                                      "type": {"type": "integer"},
                                      "python_type": {"description": "Step count."},
                                      "parameter_has_default": true
                                    }
                                  ],
                                  "returns": [{"component": "Textbox"}]
                                }
                                ,
                                "/upload": {
                                  "parameters": [
                                    {
                                      "parameter_name": "image",
                                      "type": {"type": "file"},
                                      "python_type": {"description": "Local image file."},
                                      "parameter_has_default": false
                                    }
                                  ],
                                  "returns": [{"component": "Textbox"}]
                                }
                              }
                            }
                            """;
                    send(exchange, 200, body, "application/json");
                }
            });
            server.createContext("/gradio_api/call", exchange -> {
                try (exchange) {
                    if (authorization != null) {
                        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    }
                    if ("POST".equals(exchange.getRequestMethod())) {
                        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (postBody != null) {
                            postBody.set(body);
                        }
                        if (callBody != null) {
                            callBody.set(body);
                        }
                        send(exchange, 200, "{\"event_id\":\"event-1\"}", "application/json");
                    } else {
                        String event = "event: complete\ndata: "
                                + this.resultData.replace("__SPACE_BASE__", baseUrl().toString())
                                + "\n\n";
                        send(exchange, 200, event, "text/event-stream");
                    }
                }
            });
            server.createContext("/gradio_api/file/image.png", exchange -> {
                try (exchange) {
                    byte[] body = new byte[]{1, 2, 3};
                    exchange.getResponseHeaders().add("Content-Type", "image/png");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.createContext("/gradio_api/upload", exchange -> {
                try (exchange) {
                    uploadRequests.incrementAndGet();
                    uploadAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    if (uploadBody != null) {
                        uploadBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    } else {
                        exchange.getRequestBody().readAllBytes();
                    }
                    send(exchange, 200, "[\"/tmp/gradio/opaque-upload\"]", "application/json");
                }
            });
            server.start();
        }

        /**
         * 返回本地 Gradio origin。
         *
         * @return loopback base URL
         */
        private URI baseUrl() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        /**
         * 写入 HTTP status 和 response body。
         *
         * @param exchange HTTP exchange
         * @param status HTTP code
         * @param body response body
         * @param contentType response MIME
         * @throws IOException response 写入失败
         */
        private static void send(
                com.sun.net.httpserver.HttpExchange exchange,
                int status,
                String body,
                String contentType
        ) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        /**
         * 停止本机测试服务。
         */
        @Override
        public void close() {
            server.stop(0);
        }
    }
}
