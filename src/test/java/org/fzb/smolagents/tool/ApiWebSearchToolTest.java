package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 loopback HTTP stub 验证 API 网页搜索请求和错误处理。
 */
class ApiWebSearchToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证 query/header 参数被正确发送，并将结构化结果格式化为 Markdown。
     *
     * @throws Exception stub server 或 JSON 参数处理失败时抛出
     */
    @Test
    void sendsAuthenticatedSearchAndFormatsResults() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        try (StubServer server = new StubServer(200, """
                {"web":{"results":[
                  {"title":"First","url":"https://example.com/1","description":"One"},
                  {"title":"Second","url":"https://example.com/2"}
                ]}}
                """, rawQuery, apiKey)) {
            ToolRegistry registry = new ToolRegistry();
            registry.register(tool(server.endpoint(), "local-test-key"));
            ToolResult result = registry.invoke(
                    new ToolCall("search-success", "web_search", MAPPER.readTree(
                            "{\"query\":\"coffee & tea\"}"
                    )),
                    context("search-success")
            );

            ContentPart.Text text = assertInstanceOf(ContentPart.Text.class, result.content().getFirst());
            assertEquals(
                    "## Search Results\n\n1. [First](https://example.com/1)\nOne\n\n"
                            + "2. [Second](https://example.com/2)\n",
                    text.text()
            );
            assertTrue(rawQuery.get().contains("q=coffee+%26+tea"));
            assertTrue(rawQuery.get().contains("count=10"));
            assertEquals("local-test-key", apiKey.get());
        }
    }

    /**
     * 验证 API 非成功状态不会被误报成空结果。
     *
     * @throws Exception stub server 或 JSON 参数处理失败时抛出
     */
    @Test
    void reportsHttpFailureWithoutReturningSuccessShapedResult() throws Exception {
        try (StubServer server = new StubServer(429, "{\"error\":\"rate limited\"}", null, null)) {
            ToolExecutionException failure = assertThrows(
                    ToolExecutionException.class,
                    () -> tool(server.endpoint(), "test-key").execute(
                            MAPPER.readTree("{\"query\":\"test\"}"),
                            context("search-http-error")
                    )
            );

            assertTrue(failure.getMessage().contains("HTTP 429"));
            assertFalse(failure.getMessage().contains("test-key"));
        }
    }

    /**
     * 验证缺少默认 API 凭据时在配置阶段给出明确错误。
     */
    @Test
    void requiresCredentialsWhenNoCustomHeadersAreConfigured() {
        assertThrows(
                ConfigurationException.class,
                () -> new ApiWebSearchTool(
                        URI.create("https://search.example.test/api"),
                        "",
                        Map.of(),
                        Map.of(),
                        null,
                        Duration.ofSeconds(1)
                )
        );
    }

    /**
     * 使用无速率限制的本地 endpoint 创建工具。
     *
     * @param endpoint 本地 API 地址
     * @param apiKey 测试用凭据
     * @return 配置完成的搜索工具
     */
    private static ApiWebSearchTool tool(URI endpoint, String apiKey) {
        return new ApiWebSearchTool(
                endpoint,
                apiKey,
                null,
                Map.of("count", "10"),
                null,
                Duration.ofSeconds(2)
        );
    }

    /**
     * 创建使用独立运行标识和取消令牌的工具上下文。
     *
     * @param callId 工具调用标识
     * @return 工具执行上下文
     */
    private static ToolExecutionContext context(String callId) {
        return new ToolExecutionContext(UUID.randomUUID(), callId, new CancellationToken());
    }

    /**
     * 提供本机 loopback HTTP endpoint，避免测试依赖外部 API 和密钥。
     */
    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;

        /**
         * 启动返回固定响应体的本地搜索 API stub。
         *
         * @param status HTTP 状态码
         * @param response 固定 JSON 响应
         * @param rawQuery 接收原始 query string 的可选引用
         * @param apiKey 接收认证 header 的可选引用
         * @throws IOException loopback 服务启动失败时抛出
         */
        private StubServer(
                int status,
                String response,
                AtomicReference<String> rawQuery,
                AtomicReference<String> apiKey
        ) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/search", exchange -> {
                if (rawQuery != null) {
                    rawQuery.set(exchange.getRequestURI().getRawQuery());
                }
                if (apiKey != null) {
                    apiKey.set(exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
                }
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
        }

        /**
         * 返回 stub 的搜索 endpoint。
         *
         * @return 本机 HTTP endpoint
         */
        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
        }

        /**
         * 停止本地 stub 服务。
         */
        @Override
        public void close() {
            server.stop(0);
        }
    }
}
