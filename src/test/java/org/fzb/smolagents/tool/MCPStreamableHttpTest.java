package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.model.ContentPart;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 loopback HTTP server 验证 MCP Streamable HTTP JSON-RPC 行为。
 */
class MCPStreamableHttpTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证 HTTP 初始化、session/version header、工具调用及 session DELETE。
     *
     * @throws Exception 本地服务或 JSON 数据处理失败时抛出
     */
    @Test
    void initializesCallsToolsAndTerminatesSession() throws Exception {
        try (StubServer server = new StubServer(false)) {
            MCPClient.StreamableHttpServerParameters parameters = parameters(server.endpoint());
            MCPClient client = new MCPClient(parameters, true, true);

            Tool echo = client.getTools().stream()
                    .filter(tool -> tool.spec().name().equals("echo"))
                    .findFirst()
                    .orElseThrow();
            ToolResult result = echo.execute(
                    MAPPER.readTree("{\"text\":\"from http\"}"),
                    context("http-echo")
            );

            assertEquals("http:from http", text(result));
            assertEquals(MAPPER.readTree("{\"transport\":\"streamable-http\"}"), result.structuredContent());
            assertTrue(server.requests().stream().anyMatch(
                    request -> "initialize".equals(request.path("method").asText())
            ));
            assertTrue(server.requests().stream().anyMatch(
                    request -> "notifications/initialized".equals(request.path("method").asText())
            ));
            assertTrue(server.requests().stream().anyMatch(
                    request -> "tools/call".equals(request.path("method").asText())
            ));
            client.close();

            assertEquals("session-test", server.deletedSession());
            assertTrue(server.protocolVersions().stream().anyMatch("2024-11-05"::equals));
        }
    }

    /**
     * 验证 HTTP SSE response event 同样可承载同步 MCP tools/list 响应。
     *
     * @throws Exception 本地服务或 JSON 数据处理失败时抛出
     */
    @Test
    void parsesServerSentEventResponseForRpcRequest() throws Exception {
        try (StubServer server = new StubServer(true)) {
            try (MCPClient client = new MCPClient(parameters(server.endpoint()), true, false)) {
                assertTrue(client.getTools().stream().anyMatch(tool -> tool.spec().name().equals("echo")));
            }
            assertEquals("session-test", server.deletedSession());
        }
    }

    /**
     * 验证托管工具集合工厂支持 Streamable HTTP server。
     *
     * @throws Exception 本地服务或资源处理失败时抛出
     */
    @Test
    void createsManagedCollectionFromStreamableHttp() throws Exception {
        try (StubServer server = new StubServer(false);
             ToolCollection collection = ToolCollection.fromMcp(parameters(server.endpoint()), true, false)) {
            assertTrue(collection.tools().stream().anyMatch(tool -> tool.spec().name().equals("echo")));
        }
    }

    /**
     * 创建使用两秒请求超时的本机 HTTP server 参数。
     *
     * @param endpoint 本地 MCP endpoint
     * @return MCP HTTP 配置
     */
    private static MCPClient.StreamableHttpServerParameters parameters(URI endpoint) {
        return new MCPClient.StreamableHttpServerParameters(
                "http-test",
                endpoint,
                Map.of("Authorization", "Bearer loopback-test"),
                Duration.ofSeconds(2)
        );
    }

    /**
     * 创建本地工具执行上下文。
     *
     * @param callId 本次工具调用 ID
     * @return 独立执行上下文
     */
    private static ToolExecutionContext context(String callId) {
        return new ToolExecutionContext(UUID.randomUUID(), callId, new CancellationToken());
    }

    /**
     * 提取工具结果中的第一段文本。
     *
     * @param result MCP 工具结果
     * @return 文本内容
     */
    private static String text(ToolResult result) {
        return assertInstanceOf(ContentPart.Text.class, result.content().getFirst()).text();
    }

    /**
     * 提供 Streamable HTTP JSON-RPC server stub。
     */
    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;
        private final boolean useSseForToolsList;
        private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        private final List<String> protocolVersions = new CopyOnWriteArrayList<>();
        private volatile String deletedSession;

        /**
         * 启动 loopback MCP endpoint。
         *
         * @param useSseForToolsList 是否将 tools/list 的结果封装为 SSE
         * @throws IOException 无法监听本机端口时抛出
         */
        private StubServer(boolean useSseForToolsList) throws IOException {
            this.useSseForToolsList = useSseForToolsList;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", exchange -> {
                try (exchange) {
                    if ("DELETE".equals(exchange.getRequestMethod())) {
                        deletedSession = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    String protocolVersion = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
                    if (protocolVersion != null) {
                        protocolVersions.add(protocolVersion);
                    }
                    JsonNode request = MAPPER.readTree(exchange.getRequestBody());
                    requests.add(request);
                    String method = request.path("method").asText();
                    if ("notifications/initialized".equals(method)) {
                        exchange.sendResponseHeaders(202, -1);
                        return;
                    }
                    JsonNode response = responseFor(request);
                    exchange.getResponseHeaders().add("Mcp-Session-Id", "session-test");
                    if (useSseForToolsList && "tools/list".equals(method)) {
                        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
                        send(exchange, "event: message\ndata: " + response + "\n\n");
                    } else {
                        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                        send(exchange, response.toString());
                    }
                }
            });
            server.start();
        }

        /**
         * 创建对应 MCP method 的 response JSON-RPC envelope。
         *
         * @param request 已接收请求
         * @return JSON-RPC response
         */
        private static JsonNode responseFor(JsonNode request) {
            String method = request.path("method").asText();
            JsonNode result = switch (method) {
                case "initialize" -> MAPPER.createObjectNode()
                        .put("protocolVersion", "2024-11-05")
                        .set("capabilities", MAPPER.createObjectNode().putObject("tools"));
                case "tools/list" -> {
                    var inputSchema = MAPPER.createObjectNode();
                    inputSchema.put("type", "object");
                    inputSchema.set("properties", MAPPER.createObjectNode()
                            .set("text", MAPPER.createObjectNode().put("type", "string")));
                    inputSchema.putArray("required").add("text");
                    var echoTool = MAPPER.createObjectNode()
                            .put("name", "echo")
                            .put("description", "Echo text.")
                            .set("inputSchema", inputSchema);
                    yield MAPPER.createObjectNode().set("tools", MAPPER.createArrayNode().add(echoTool));
                }
                case "tools/call" -> {
                    var callResult = MAPPER.createObjectNode();
                    callResult.set("content", MAPPER.createArrayNode().add(MAPPER.createObjectNode()
                            .put("type", "text")
                            .put("text", "http:" + request.path("params").path("arguments").path("text").asText())));
                    callResult.set("structuredContent", MAPPER.createObjectNode().put("transport", "streamable-http"));
                    callResult.put("isError", false);
                    yield callResult;
                }
                default -> throw new IllegalArgumentException("Unexpected MCP method: " + method);
            };
            var envelope = MAPPER.createObjectNode().put("jsonrpc", "2.0");
            envelope.set("id", request.path("id").deepCopy());
            envelope.set("result", result);
            return envelope;
        }

        /**
         * 返回本机 MCP endpoint。
         *
         * @return loopback URI
         */
        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        }

        /**
         * 返回已收到的协议请求快照。
         *
         * @return JSON-RPC requests
         */
        private List<JsonNode> requests() {
            return List.copyOf(requests);
        }

        /**
         * 返回收到的客户端协议版本头。
         *
         * @return 协议版本头值列表
         */
        private List<String> protocolVersions() {
            return List.copyOf(protocolVersions);
        }

        /**
         * 返回客户端 session 关闭请求使用的 session ID。
         *
         * @return MCP session ID
         */
        private String deletedSession() {
            return deletedSession;
        }

        /**
         * 发送固定成功状态和响应体。
         *
         * @param exchange HTTP exchange
         * @param body response 内容
         * @throws IOException response 写入失败时抛出
         */
        private static void send(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        /**
         * 停止本地 endpoint。
         */
        @Override
        public void close() {
            server.stop(0);
        }
    }
}
