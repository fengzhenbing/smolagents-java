package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.fzb.smolagents.core.CancellationToken;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 loopback server 验证网页读取、HTML 转换、截断和地址限制。
 */
class VisitWebpageToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证 HTML 主体被转换为 Markdown 并去除可执行或非内容节点。
     *
     * @throws Exception 测试 JSON 参数解析失败时抛出
     */
    @Test
    void convertsHtmlToMarkdownAndSkipsActiveContent() throws Exception {
        String html = """
                <!doctype html>
                <html><head><title>Not body text</title><script>unsafe()</script></head>
                <body>
                <h1>Guide</h1>
                <p>Hello <strong>world</strong>. See <a href="/docs">docs</a>.</p>
                <ul><li>First</li><li>Second</li></ul>
                <pre><code>value = 42
                print(value)</code></pre>
                </body></html>
                """;
        try (StubServer server = new StubServer(200, "text/html; charset=utf-8", html)) {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new VisitWebpageTool(40_000, Duration.ofSeconds(2), true));
            ToolResult result = registry.invoke(
                    new ToolCall("webpage-call", "visit_webpage", MAPPER.readTree(
                            "{\"url\":\"" + server.endpoint() + "\"}"
                    )),
                    context("webpage-call")
            );

            String markdown = assertInstanceOf(ContentPart.Text.class, result.content().getFirst()).text();
            assertTrue(markdown.contains("# Guide"));
            assertTrue(markdown.contains("Hello **world**. See [docs](" + server.baseUri() + "/docs)."));
            assertTrue(markdown.contains("- First\n- Second"));
            assertTrue(
                    markdown.contains("```\nvalue = 42\nprint(value)\n```"),
                    markdown
            );
            assertFalse(markdown.contains("unsafe()"));
            assertFalse(markdown.contains("Not body text"));
        }
    }

    /**
     * 验证 plain text 响应支持截断，并明确拒绝重定向和非成功状态。
     *
     * @throws Exception 测试 JSON 参数解析失败时抛出
     */
    @Test
    void truncatesPlainTextAndDoesNotFollowRedirects() throws Exception {
        try (StubServer server = new StubServer(200, "text/plain; charset=utf-8", "abcdefghij")) {
            VisitWebpageTool tool = new VisitWebpageTool(4, Duration.ofSeconds(2), true);
            ToolResult result = tool.execute(
                    MAPPER.readTree("{\"url\":\"" + server.endpoint() + "\"}"),
                    context("webpage-truncate")
            );
            String text = assertInstanceOf(ContentPart.Text.class, result.content().getFirst()).text();

            assertEquals("abcd\n..._This content has been truncated to stay below 4 characters_...\n", text);
        }
        try (StubServer server = new StubServer(302, "text/plain", "redirect")) {
            ToolExecutionException failure = assertThrows(
                    ToolExecutionException.class,
                    () -> new VisitWebpageTool(100, Duration.ofSeconds(2), true).execute(
                            MAPPER.readTree("{\"url\":\"" + server.endpoint() + "\"}"),
                            context("webpage-redirect")
                    )
            );
            assertTrue(failure.getMessage().contains("HTTP 302"));
        }
    }

    /**
     * 验证默认网络策略拒绝 loopback 目标，且凭据 URL 不会被访问。
     *
     * @throws Exception 测试 JSON 参数解析失败时抛出
     */
    @Test
    void blocksPrivateAddressesAndCredentialUrlsByDefault() throws Exception {
        try (StubServer server = new StubServer(200, "text/plain", "private")) {
            VisitWebpageTool tool = new VisitWebpageTool(100, Duration.ofSeconds(2));
            ToolExecutionException privateAddress = assertThrows(
                    ToolExecutionException.class,
                    () -> tool.execute(
                            MAPPER.readTree("{\"url\":\"" + server.endpoint() + "\"}"),
                            context("webpage-private")
                    )
            );
            ToolExecutionException credentials = assertThrows(
                    ToolExecutionException.class,
                    () -> tool.execute(
                            MAPPER.readTree("{\"url\":\"http://user:secret@example.com/\"}"),
                            context("webpage-credentials")
                    )
            );

            assertTrue(privateAddress.getMessage().contains("non-public"));
            assertTrue(credentials.getMessage().contains("without credentials"));
        }
    }

    /**
     * 创建独立工具运行上下文。
     *
     * @param callId 工具调用标识
     * @return 工具执行上下文
     */
    private static ToolExecutionContext context(String callId) {
        return new ToolExecutionContext(UUID.randomUUID(), callId, new CancellationToken());
    }

    /**
     * 提供可配置状态码和内容类型的本地网页服务。
     */
    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;

        /**
         * 启动固定响应的 loopback endpoint。
         *
         * @param status HTTP 状态码
         * @param contentType 响应内容类型
         * @param body 响应正文
         * @throws IOException 服务启动失败时抛出
         */
        private StubServer(int status, String contentType, String body) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", contentType);
                if (status >= 300 && status < 400) {
                    exchange.getResponseHeaders().add("Location", "/redirect-target");
                }
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();
        }

        /**
         * 返回本地测试页面地址。
         *
         * @return loopback endpoint
         */
        private URI endpoint() {
            return URI.create(baseUri() + "/page");
        }

        /**
         * 返回本地 server origin。
         *
         * @return scheme、host 与端口
         */
        private String baseUri() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
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
