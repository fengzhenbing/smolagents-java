package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.model.ContentPart;
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
 * 使用 loopback HTTP stub 验证多后端网页搜索行为。
 */
class WebSearchToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证 DuckDuckGo Lite 的标题、跨行摘要和显示 URL 被解析，并限制结果数。
     *
     * @throws Exception 本地服务或 JSON 参数处理失败时抛出
     */
    @Test
    void parsesDuckDuckGoLiteResultsAndLimitsOutput() throws Exception {
        String html = """
                <table>
                  <tr><td><a class="result-link" href="https://redirect.example/first">First</a></td></tr>
                  <tr><td class="result-snippet">One <b>detail</b></td></tr>
                  <tr><td><span class="link-text">example.com/first</span></td></tr>
                  <tr><td><a class="result-link" href="https://redirect.example/second">Second</a></td></tr>
                  <tr><td class="result-snippet">Two</td></tr>
                  <tr><td><span class="link-text">example.com/second</span></td></tr>
                </table>
                """;
        AtomicReference<String> query = new AtomicReference<>();
        try (StubServer server = new StubServer(200, "text/html; charset=utf-8", html, query, null, null, null)) {
            ToolResult result = tool(server.endpoint(), WebSearchTool.SearchEngine.DUCKDUCKGO, 1, null)
                    .execute(arguments("coffee & tea"), context("duckduckgo"));
            String text = text(result);

            assertEquals("## Search Results\n\n[First](https://example.com/first)\nOne detail", text);
            assertTrue(query.get().contains("q=coffee+%26+tea"));
            assertFalse(text.contains("Second"));
        }
    }

    /**
     * 验证 Bing RSS 使用安全 XML 解析并提取结果。
     *
     * @throws Exception 本地服务或 JSON 参数处理失败时抛出
     */
    @Test
    void parsesBingRssResults() throws Exception {
        String rss = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss><channel><item>
                  <title>First</title>
                  <link>https://example.com/first</link>
                  <description>One &amp; two</description>
                </item></channel></rss>
                """;
        AtomicReference<String> query = new AtomicReference<>();
        try (StubServer server = new StubServer(200, "application/rss+xml", rss, query, null, null, null)) {
            String text = text(tool(server.endpoint(), WebSearchTool.SearchEngine.BING, 10, null)
                    .execute(arguments("research"), context("bing")));

            assertEquals("## Search Results\n\n[First](https://example.com/first)\nOne & two", text);
            assertTrue(query.get().contains("q=research"));
            assertTrue(query.get().contains("format=rss"));
        }
    }

    /**
     * 验证 Exa 的 API key 仅通过请求 header 发送，且 highlights 合并为摘要。
     *
     * @throws Exception 本地服务或 JSON 参数处理失败时抛出
     */
    @Test
    void postsAuthenticatedExaSearchAndFormatsHighlights() throws Exception {
        String response = """
                {"results":[
                  {"title":"First","url":"https://example.com/first","highlights":["One","detail"]},
                  {"url":"https://example.com/second","highlights":[]}
                ]}
                """;
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        try (StubServer server = new StubServer(200, "application/json", response, null, method, body, apiKey)) {
            String text = text(tool(server.endpoint(), WebSearchTool.SearchEngine.EXA, 1, "test-exa-key")
                    .execute(arguments("java agents"), context("exa")));

            assertEquals("## Search Results\n\n[First](https://example.com/first)\nOne detail", text);
            assertEquals("POST", method.get());
            assertEquals("test-exa-key", apiKey.get());
            assertTrue(body.get().contains("\"query\":\"java agents\""));
            assertTrue(body.get().contains("\"numResults\":1"));
            assertFalse(body.get().contains("test-exa-key"));
        }
    }

    /**
     * 验证 HTTP、无结果和无效 JSON 响应都显式报告失败。
     *
     * @throws Exception 本地服务或 JSON 参数处理失败时抛出
     */
    @Test
    void reportsHttpEmptyAndMalformedResponses() throws Exception {
        try (StubServer server = new StubServer(503, "text/plain", "unavailable")) {
            ToolExecutionException httpFailure = assertThrows(
                    ToolExecutionException.class,
                    () -> tool(server.endpoint(), WebSearchTool.SearchEngine.BING, 10, null)
                            .execute(arguments("query"), context("http-error"))
            );
            assertTrue(httpFailure.getMessage().contains("HTTP 503"));
        }
        try (StubServer server = new StubServer(200, "application/rss+xml", "<rss><channel/></rss>")) {
            ToolExecutionException empty = assertThrows(
                    ToolExecutionException.class,
                    () -> tool(server.endpoint(), WebSearchTool.SearchEngine.BING, 10, null)
                            .execute(arguments("query"), context("empty"))
            );
            assertTrue(empty.getMessage().contains("No results found"));
        }
        try (StubServer server = new StubServer(200, "application/json", "{invalid")) {
            ToolExecutionException malformed = assertThrows(
                    ToolExecutionException.class,
                    () -> tool(server.endpoint(), WebSearchTool.SearchEngine.EXA, 10, "test-key")
                            .execute(arguments("query"), context("malformed"))
            );
            assertTrue(malformed.getMessage().contains("invalid JSON"));
        }
    }

    /**
     * 创建带本地 endpoint 的搜索工具。
     *
     * @param endpoint loopback 服务 endpoint
     * @param engine 搜索后端
     * @param maxResults 最大结果数
     * @param apiKey Exa 测试凭据
     * @return 配置完成的搜索工具
     */
    private static WebSearchTool tool(
            URI endpoint,
            WebSearchTool.SearchEngine engine,
            int maxResults,
            String apiKey
    ) {
        return new WebSearchTool(maxResults, engine, endpoint, apiKey, Duration.ofSeconds(2));
    }

    /**
     * 解析单个测试 query 参数。
     *
     * @param query 搜索词
     * @return JSON 参数对象
     * @throws Exception 测试 JSON 无法解析时抛出
     */
    private static com.fasterxml.jackson.databind.JsonNode arguments(String query) throws Exception {
        return MAPPER.createObjectNode().put("query", query);
    }

    /**
     * 提取纯文本工具结果。
     *
     * @param result 工具结果
     * @return 第一段文本
     */
    private static String text(ToolResult result) {
        return assertInstanceOf(ContentPart.Text.class, result.content().getFirst()).text();
    }

    /**
     * 创建独立的工具执行上下文。
     *
     * @param callId 本次调用标识
     * @return 新建上下文
     */
    private static ToolExecutionContext context(String callId) {
        return new ToolExecutionContext(UUID.randomUUID(), callId, new CancellationToken());
    }

    /**
     * 提供固定 HTTP response 的本机测试服务。
     */
    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;

        /**
         * 启动固定响应的 loopback HTTP 服务。
         *
         * @param status HTTP 状态码
         * @param contentType response MIME 类型
         * @param response 固定 response body
         * @throws IOException 无法启动本机 HTTP 服务时抛出
         */
        private StubServer(int status, String contentType, String response) throws IOException {
            this(status, contentType, response, null, null, null, null);
        }

        /**
         * 启动固定响应并可捕获请求信息的 loopback HTTP 服务。
         *
         * @param status HTTP 状态码
         * @param contentType response MIME 类型
         * @param response 固定 response body
         * @param query 接收原始 query string 的可选引用
         * @param method 接收 HTTP method 的可选引用
         * @param body 接收 request body 的可选引用
         * @param apiKey 接收 Exa header 的可选引用
         * @throws IOException 无法启动本机 HTTP 服务时抛出
         */
        private StubServer(
                int status,
                String contentType,
                String response,
                AtomicReference<String> query,
                AtomicReference<String> method,
                AtomicReference<String> body,
                AtomicReference<String> apiKey
        ) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                if (query != null) {
                    query.set(exchange.getRequestURI().getRawQuery());
                }
                if (method != null) {
                    method.set(exchange.getRequestMethod());
                }
                if (body != null) {
                    body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                }
                if (apiKey != null) {
                    apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();
        }

        /**
         * 返回本机搜索 endpoint。
         *
         * @return loopback URI
         */
        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
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
