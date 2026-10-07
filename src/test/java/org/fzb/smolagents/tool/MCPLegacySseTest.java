package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.model.ContentPart;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 loopback server 验证 legacy MCP HTTP+SSE message event transport。
 */
class MCPLegacySseTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证 endpoint event 握手、独立 JSON-RPC POST、SSE response 和认证头。
     *
     * @throws Exception 本地 SSE server 或 JSON 参数处理失败时抛出
     */
    @Test
    void discoversAndCallsToolsThroughLegacySseTransport() throws Exception {
        try (StubServer server = new StubServer()) {
            MCPClient client = new MCPClient(
                    new MCPClient.SseServerParameters(
                            "legacy-sse",
                            server.sseEndpoint(),
                            Map.of("Authorization", "Bearer sse-test-token"),
                            Duration.ofSeconds(5)
                    ),
                    true,
                    true
            );
            try (client) {
                Tool echo = client.getTools().stream()
                        .filter(tool -> tool.spec().name().equals("echo"))
                        .findFirst()
                        .orElseThrow();
                ToolResult result = echo.execute(
                        MAPPER.readTree("{\"text\":\"legacy\"}"),
                        context("sse-call")
                );

                assertEquals("sse:legacy", assertInstanceOf(
                        ContentPart.Text.class,
                        result.content().getFirst()
                ).text());
                assertEquals("sse", result.structuredContent().path("transport").asText());
                assertTrue(server.methods().containsAll(
                        List.of("initialize", "notifications/initialized", "tools/list", "tools/call")
                ));
                assertTrue(server.authorizationHeaders().stream()
                        .allMatch("Bearer sse-test-token"::equals));
            }
        }
    }

    /**
     * 创建独立工具上下文。
     *
     * @param callId 调用 ID
     * @return 工具调用上下文
     */
    private static ToolExecutionContext context(String callId) {
        return new ToolExecutionContext(UUID.randomUUID(), callId, new CancellationToken());
    }

    /**
     * 提供同时实现 SSE event stream 和 JSON-RPC POST endpoint 的本地服务。
     */
    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;
        private final CountDownLatch releaseStream = new CountDownLatch(1);
        private final CountDownLatch streamReady = new CountDownLatch(1);
        private final List<String> methods = new CopyOnWriteArrayList<>();
        private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
        private volatile OutputStream sseOutput;

        /**
         * 启动本机 SSE MCP fixture。
         *
         * @throws IOException 无法监听本机端口时抛出
         */
        private StubServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/sse", this::openEventStream);
            server.createContext("/messages", this::handleMessage);
            server.start();
        }

        /**
         * 打开 SSE response 并先发布 legacy endpoint event。
         *
         * @param exchange 当前 GET exchange
         * @throws IOException response 输出失败时抛出
         */
        private void openEventStream(HttpExchange exchange) throws IOException {
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            sseOutput = exchange.getResponseBody();
            try {
                writeEvent("endpoint", "/messages?session_id=loopback");
                streamReady.countDown();
                releaseStream.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        }

        /**
         * 处理 POST JSON-RPC 并通过 SSE event stream 返回请求响应。
         *
         * @param exchange 当前 POST exchange
         * @throws IOException 请求或 response 读写失败时抛出
         */
        private void handleMessage(HttpExchange exchange) throws IOException {
            try (exchange) {
                authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                JsonNode request = MAPPER.readTree(exchange.getRequestBody());
                String method = request.path("method").asText();
                methods.add(method);
                if ("notifications/initialized".equals(method)) {
                    exchange.sendResponseHeaders(202, -1);
                    return;
                }
                JsonNode response = responseFor(request);
                if (!streamReady.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                writeEvent("message", response.toString());
                exchange.sendResponseHeaders(202, -1);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while writing MCP SSE response", error);
            }
        }

        /**
         * 构造初始化、工具发现或工具调用的 JSON-RPC response。
         *
         * @param request JSON-RPC request
         * @return 对应 response object
         */
        private static JsonNode responseFor(JsonNode request) {
            String method = request.path("method").asText();
            JsonNode result = switch (method) {
                case "initialize" -> MAPPER.createObjectNode()
                        .put("protocolVersion", "2024-11-05")
                        .set("capabilities", MAPPER.createObjectNode());
                case "tools/list" -> {
                    var schema = MAPPER.createObjectNode();
                    schema.put("type", "object");
                    schema.set("properties", MAPPER.createObjectNode()
                            .set("text", MAPPER.createObjectNode().put("type", "string")));
                    schema.putArray("required").add("text");
                    var tool = MAPPER.createObjectNode()
                            .put("name", "echo")
                            .put("description", "SSE echo")
                            .set("inputSchema", schema);
                    yield MAPPER.createObjectNode().set("tools", MAPPER.createArrayNode().add(tool));
                }
                case "tools/call" -> {
                    var callResult = MAPPER.createObjectNode();
                    callResult.set("content", MAPPER.createArrayNode().add(MAPPER.createObjectNode()
                            .put("type", "text")
                            .put("text", "sse:" + request.path("params").path("arguments").path("text").asText())));
                    callResult.set("structuredContent", MAPPER.createObjectNode().put("transport", "sse"));
                    callResult.put("isError", false);
                    yield callResult;
                }
                default -> throw new IllegalArgumentException("Unexpected MCP method: " + method);
            };
            var response = MAPPER.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", request.path("id").deepCopy());
            response.set("result", result);
            return response;
        }

        /**
         * 将 SSE event 写入持久 GET response。
         *
         * @param name event 名称
         * @param data event data
         * @throws IOException response 已关闭或无法写入时抛出
         */
        private synchronized void writeEvent(String name, String data) throws IOException {
            String event = "event: " + name + "\ndata: " + data + "\n\n";
            sseOutput.write(event.getBytes(StandardCharsets.UTF_8));
            sseOutput.flush();
        }

        /**
         * 返回 legacy SSE GET endpoint。
         *
         * @return loopback SSE URI
         */
        private URI sseEndpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/sse");
        }

        /**
         * 返回收到的 JSON-RPC 方法序列。
         *
         * @return 方法名列表
         */
        private List<String> methods() {
            return List.copyOf(methods);
        }

        /**
         * 返回所有 GET/POST 请求收到的 Authorization header。
         *
         * @return 认证头列表
         */
        private List<String> authorizationHeaders() {
            return List.copyOf(authorizationHeaders);
        }

        /**
         * 释放 SSE handler 并停止 loopback server。
         */
        @Override
        public void close() {
            releaseStream.countDown();
            server.stop(0);
        }
    }
}
