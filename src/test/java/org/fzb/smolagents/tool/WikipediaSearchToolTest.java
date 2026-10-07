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
 * 使用本机 HTTP stub 验证 Wikipedia 工具的参数、页面映射和错误边界。
 */
class WikipediaSearchToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证摘要请求遵循配置并格式化标题、extract 与规范 URL。
     *
     * @throws Exception stub server 或 JSON 参数处理失败时抛出
     */
    @Test
    void readsWikipediaSummaryWithRequiredUserAgent() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        AtomicReference<String> userAgent = new AtomicReference<>();
        try (StubServer server = new StubServer(200, """
                {"query":{"pages":[
                  {"pageid":42,"title":"Java (programming language)",
                   "fullurl":"https://en.wikipedia.org/wiki/Java_(programming_language)",
                   "extract":"Java is a programming language."}
                ]}}
                """, rawQuery, userAgent)) {
            WikipediaSearchTool tool = new WikipediaSearchTool(
                    "SmolagentsJavaTests/1.0 (https://example.test/contact)",
                    "en",
                    WikipediaSearchTool.ContentType.SUMMARY,
                    WikipediaSearchTool.ExtractFormat.WIKI,
                    server.endpoint(),
                    Duration.ofSeconds(2)
            );
            ToolRegistry registry = new ToolRegistry();
            registry.register(tool);
            ToolResult result = registry.invoke(
                    new ToolCall("wiki-call", "wikipedia_search", MAPPER.readTree(
                            "{\"query\":\"Java (programming language)\"}"
                    )),
                    context("wiki-call")
            );

            ContentPart.Text text = assertInstanceOf(ContentPart.Text.class, result.content().getFirst());
            assertEquals(
                    "✅ **Wikipedia Page:** Java (programming language)"
                            + "\n\n**Content:** Java is a programming language."
                            + "\n\n🔗 **Read more:** https://en.wikipedia.org/wiki/Java_(programming_language)",
                    text.text()
            );
            assertTrue(rawQuery.get().contains("exintro=1"));
            assertTrue(rawQuery.get().contains("explaintext=1"));
            assertTrue(rawQuery.get().contains("titles=Java+%28programming+language%29"));
            assertEquals("SmolagentsJavaTests/1.0 (https://example.test/contact)", userAgent.get());
        }
    }

    /**
     * 验证缺失页面返回明确提示，并允许 HTML extract 格式配置。
     *
     * @throws Exception stub server 或 JSON 参数处理失败时抛出
     */
    @Test
    void reportsMissingPageWithoutFabricatingContent() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        try (StubServer server = new StubServer(
                200,
                "{\"query\":{\"pages\":[{\"ns\":0,\"title\":\"Unknown topic\",\"missing\":true}]}}",
                rawQuery,
                null
        )) {
            WikipediaSearchTool tool = new WikipediaSearchTool(
                    "SmolagentsJavaTests/1.0",
                    "en",
                    WikipediaSearchTool.ContentType.TEXT,
                    WikipediaSearchTool.ExtractFormat.HTML,
                    server.endpoint(),
                    Duration.ofSeconds(2)
            );
            ToolResult result = tool.execute(
                    MAPPER.readTree("{\"query\":\"Unknown topic\"}"),
                    context("wiki-missing")
            );

            assertEquals("No Wikipedia page found for 'Unknown topic'. Try a different query.",
                    assertInstanceOf(ContentPart.Text.class, result.content().getFirst()).text());
            assertTrue(rawQuery.get().contains("explaintext=0"));
            assertFalse(rawQuery.get().contains("exintro="));
        }
    }

    /**
     * 验证无效语言配置和服务端失败会得到明确错误。
     */
    @Test
    void rejectsInvalidLanguageAndReportsHttpFailure() throws Exception {
        assertThrows(
                ConfigurationException.class,
                () -> new WikipediaSearchTool(
                        "test",
                        "en&evil=true",
                        WikipediaSearchTool.ContentType.TEXT,
                        WikipediaSearchTool.ExtractFormat.WIKI
                )
        );
        try (StubServer server = new StubServer(503, "unavailable", null, null)) {
            WikipediaSearchTool tool = new WikipediaSearchTool(
                    "SmolagentsJavaTests/1.0",
                    "en",
                    WikipediaSearchTool.ContentType.SUMMARY,
                    WikipediaSearchTool.ExtractFormat.WIKI,
                    server.endpoint(),
                    Duration.ofSeconds(2)
            );
            ToolExecutionException failure = assertThrows(
                    ToolExecutionException.class,
                    () -> tool.execute(
                            MAPPER.readTree("{\"query\":\"Java\"}"),
                            context("wiki-http-failure")
                    )
            );
            assertTrue(failure.getMessage().contains("HTTP 503"));
        }
    }

    /**
     * 创建独立工具执行上下文。
     *
     * @param callId 工具调用标识
     * @return 工具执行上下文
     */
    private static ToolExecutionContext context(String callId) {
        return new ToolExecutionContext(UUID.randomUUID(), callId, new CancellationToken());
    }

    /**
     * 提供返回固定 JSON 的 loopback MediaWiki endpoint。
     */
    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;

        /**
         * 启动固定状态和响应体的本地测试 endpoint。
         *
         * @param status HTTP 状态码
         * @param response 固定 JSON 响应
         * @param rawQuery 接收请求 query 的可选引用
         * @param userAgent 接收 user-agent header 的可选引用
         * @throws IOException 服务启动失败时抛出
         */
        private StubServer(
                int status,
                String response,
                AtomicReference<String> rawQuery,
                AtomicReference<String> userAgent
        ) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/w/api.php", exchange -> {
                if (rawQuery != null) {
                    rawQuery.set(exchange.getRequestURI().getRawQuery());
                }
                if (userAgent != null) {
                    userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
                }
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
        }

        /**
         * 返回本地 MediaWiki API 地址。
         *
         * @return loopback URI
         */
        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/w/api.php");
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
