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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 loopback HTTP stub 验证 SerpApi 和 Serper 搜索契约。
 */
class GoogleSearchToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证 SerpApi 请求参数、年份过滤及自然结果格式化。
     *
     * @throws Exception 本地服务或 JSON 测试数据处理失败时抛出
     */
    @Test
    void sendsSerpApiRequestWithYearFilterAndFormatsResults() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        try (StubServer server = new StubServer(200, """
                {"organic_results":[{
                  "title":"First","link":"https://example.com/first","date":"Jan 2024",
                  "source":"Example","snippet":"An example result."
                }]}
                """, rawQuery)) {
            GoogleSearchTool tool = tool(GoogleSearchTool.Provider.SERPAPI, server.endpoint(), "serp-test-key");
            ToolRegistry registry = new ToolRegistry();
            registry.register(tool);
            ToolResult result = registry.invoke(
                    new ToolCall(
                            "google-serpapi",
                            "web_search",
                            MAPPER.readTree("{\"query\":\"java agents\",\"filter_year\":2024}")
                    ),
                    context("google-serpapi")
            );

            assertEquals(
                    "## Search Results\n0. [First](https://example.com/first)"
                            + "\nDate published: Jan 2024\nSource: Example\nAn example result.",
                    text(result)
            );
            assertTrue(rawQuery.get().contains("q=java+agents"));
            assertTrue(rawQuery.get().contains("engine=google"));
            assertTrue(rawQuery.get().contains("google_domain=google.com"));
            assertTrue(rawQuery.get().contains("api_key=serp-test-key"));
            assertTrue(rawQuery.get().contains("tbs=cdr%3A1%2Ccd_min%3A01%2F01%2F2024%2Ccd_max%3A12%2F31%2F2024"));
        }
    }

    /**
     * 验证 Serper 使用 organic 字段，并允许省略 nullable 年份过滤。
     *
     * @throws Exception 本地服务或 JSON 测试数据处理失败时抛出
     */
    @Test
    void sendsSerperRequestAndAcceptsNullYearFilter() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        try (StubServer server = new StubServer(200, """
                {"organic":[{"title":"Result","link":"https://example.com","snippet":"Summary"}]}
                """, rawQuery)) {
            GoogleSearchTool tool = tool(GoogleSearchTool.Provider.SERPER, server.endpoint(), "serper-test-key");
            ToolRegistry registry = new ToolRegistry();
            registry.register(tool);
            ToolResult result = registry.invoke(
                    new ToolCall(
                            "google-serper",
                            "web_search",
                            MAPPER.readTree("{\"query\":\"python\",\"filter_year\":null}")
                    ),
                    context("google-serper")
            );

            assertEquals("## Search Results\n0. [Result](https://example.com)\nSummary", text(result));
            assertTrue(rawQuery.get().contains("api_key=serper-test-key"));
            assertTrue(rawQuery.get().contains("q=python"));
            assertFalse(rawQuery.get().contains("tbs="));
        }
    }

    /**
     * 验证缺少结果、空结果、HTTP 错误和缺失凭据均有明确行为。
     *
     * @throws Exception 本地服务或 JSON 测试数据处理失败时抛出
     */
    @Test
    void reportsMissingEmptyAndHttpErrorsAndRequiresCredentials() throws Exception {
        try (StubServer server = new StubServer(200, "{\"search_metadata\":{}}", null)) {
            ToolExecutionException missing = assertThrows(
                    ToolExecutionException.class,
                    () -> tool(GoogleSearchTool.Provider.SERPAPI, server.endpoint(), "test-key")
                            .execute(MAPPER.readTree("{\"query\":\"query\"}"), context("missing"))
            );
            assertTrue(missing.getMessage().contains("No results found"));
        }
        try (StubServer server = new StubServer(200, "{\"organic\":[]}", null)) {
            String empty = text(tool(GoogleSearchTool.Provider.SERPER, server.endpoint(), "test-key")
                    .execute(MAPPER.readTree("{\"query\":\"query\",\"filter_year\":2022}"), context("empty")));
            assertTrue(empty.contains("filter year=2022"));
        }
        try (StubServer server = new StubServer(429, "{\"error\":\"rate limited\"}", null)) {
            ToolExecutionException httpFailure = assertThrows(
                    ToolExecutionException.class,
                    () -> tool(GoogleSearchTool.Provider.SERPAPI, server.endpoint(), "test-key")
                            .execute(MAPPER.readTree("{\"query\":\"query\"}"), context("http-error"))
            );
            assertTrue(httpFailure.getMessage().contains("HTTP 429"));
        }
        assertThrows(
                ConfigurationException.class,
                () -> new GoogleSearchTool(
                        GoogleSearchTool.Provider.SERPAPI,
                        "",
                        URI.create("https://search.example.test/search"),
                        Duration.ofSeconds(1)
                )
        );
    }

    /**
     * 验证年份参数的整数约束由 schema 校验提前执行。
     *
     * @throws Exception JSON 测试数据处理失败时抛出
     */
    @Test
    void rejectsInvalidYearBeforeMakingRequest() throws Exception {
        try (StubServer server = new StubServer(200, "{\"organic\":[]}", null)) {
            ToolRegistry registry = new ToolRegistry();
            registry.register(tool(GoogleSearchTool.Provider.SERPER, server.endpoint(), "test-key"));
            assertThrows(
                    org.fzb.smolagents.core.ToolValidationException.class,
                    () -> registry.invoke(
                            new ToolCall(
                                    "invalid-year",
                                    "web_search",
                                    MAPPER.readTree("{\"query\":\"query\",\"filter_year\":\"2024\"}")
                            ),
                            context("invalid-year")
                    )
            );
        }
    }

    /**
     * 创建指向 loopback 测试服务的搜索工具。
     *
     * @param provider 搜索 API provider
     * @param endpoint 本地 HTTP endpoint
     * @param apiKey 测试 API key
     * @return 已配置的搜索工具
     */
    private static GoogleSearchTool tool(
            GoogleSearchTool.Provider provider,
            URI endpoint,
            String apiKey
    ) {
        return new GoogleSearchTool(provider, apiKey, endpoint, Duration.ofSeconds(2));
    }

    /**
     * 提取工具结果中的第一段文本。
     *
     * @param result 工具执行结果
     * @return 文本内容
     */
    private static String text(ToolResult result) {
        return assertInstanceOf(ContentPart.Text.class, result.content().getFirst()).text();
    }

    /**
     * 创建独立的工具执行上下文。
     *
     * @param callId 工具调用 ID
     * @return 新上下文
     */
    private static ToolExecutionContext context(String callId) {
        return new ToolExecutionContext(UUID.randomUUID(), callId, new CancellationToken());
    }

    /**
     * 提供固定 JSON response 的本机 HTTP 服务。
     */
    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;

        /**
         * 启动 loopback response stub。
         *
         * @param status HTTP 状态码
         * @param response 固定 JSON response
         * @param rawQuery 接收请求原始 query string 的可选引用
         * @throws IOException 无法启动本机 HTTP server 时抛出
         */
        private StubServer(int status, String response, AtomicReference<String> rawQuery) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                if (rawQuery != null) {
                    rawQuery.set(exchange.getRequestURI().getRawQuery());
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();
        }

        /**
         * 返回本地搜索 endpoint。
         *
         * @return loopback URI
         */
        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
        }

        /**
         * 停止本地测试服务。
         */
        @Override
        public void close() {
            server.stop(0);
        }
    }
}
