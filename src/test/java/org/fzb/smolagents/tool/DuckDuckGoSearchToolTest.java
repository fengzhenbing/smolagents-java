package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 loopback endpoint 验证 DuckDuckGo 默认工具适配。
 */
class DuckDuckGoSearchToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证 DuckDuckGo Lite 返回摘要、显示 URL 和 query 参数。
     *
     * @throws Exception 本地服务或 JSON 测试参数处理失败时抛出
     */
    @Test
    void searchesLiteEndpointAndFormatsResults() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        String html = """
                <table>
                  <tr><td><a class="result-link" href="https://redirect.example/">Example</a></td></tr>
                  <tr><td class="result-snippet">Search summary</td></tr>
                  <tr><td><span class="link-text">example.org/page</span></td></tr>
                </table>
                """;
        try (StubServer server = new StubServer(html, rawQuery)) {
            DuckDuckGoSearchTool tool = new DuckDuckGoSearchTool(
                    5,
                    0.0,
                    server.endpoint(),
                    Duration.ofSeconds(2)
            );
            ToolResult result = tool.execute(
                    MAPPER.readTree("{\"query\":\"smolagents\"}"),
                    context("ddg-search")
            );

            assertEquals(
                    "## Search Results\n\n[Example](https://example.org/page)\nSearch summary",
                    assertInstanceOf(ContentPart.Text.class, result.content().getFirst()).text()
            );
            assertTrue(rawQuery.get().contains("q=smolagents"));
        }
    }

    /**
     * 验证无效速率限制在构造期报告配置错误。
     */
    @Test
    void rejectsNegativeRateLimit() {
        assertThrows(
                ConfigurationException.class,
                () -> new DuckDuckGoSearchTool(
                        10,
                        -1.0,
                        URI.create("https://search.example.test"),
                        Duration.ofSeconds(1)
                )
        );
    }

    /**
     * 为单次搜索创建独立工具上下文。
     *
     * @param callId 工具调用标识
     * @return 工具执行上下文
     */
    private static ToolExecutionContext context(String callId) {
        return new ToolExecutionContext(UUID.randomUUID(), callId, new CancellationToken());
    }

    /**
     * 提供固定 HTML 响应的本地 HTTP server。
     */
    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;

        /**
         * 启动固定 HTML 响应服务。
         *
         * @param html 固定页面内容
         * @param query 接收原始 query string 的引用
         * @throws IOException 无法绑定本机端口时抛出
         */
        private StubServer(String html, AtomicReference<String> query) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                query.set(exchange.getRequestURI().getRawQuery());
                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();
        }

        /**
         * 返回本地 DuckDuckGo Lite endpoint。
         *
         * @return loopback URL
         */
        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/lite/");
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
