package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.model.ContentPart;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可选地启动本机 headless Chrome，对 CDP 页面读取和 PNG 截图做 smoke test。
 */
class VisualBrowserToolIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证已安装的 Chrome 可通过 CDP 导航 loopback 页面、点击按钮并返回真实截图。
     */
    @Test
    void navigatesClicksAndCapturesLocalPageWhenExplicitlyEnabled() throws Exception {
        Assumptions.assumeTrue(
                "1".equals(System.getenv("SMOLAGENTS_BROWSER_TESTS")),
                "Set SMOLAGENTS_BROWSER_TESTS=1 to launch a local Chrome process"
        );
        String configured = System.getenv("CHROME_PATH");
        Path chrome = configured == null || configured.isBlank()
                ? Path.of("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe")
                : Path.of(configured);
        Assumptions.assumeTrue(Files.isRegularFile(chrome), "Configured Chrome executable does not exist");

        HttpServer fixture = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                0
        );
        AtomicInteger requests = new AtomicInteger();
        fixture.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] html = """
                    <!doctype html><html><head><title>Local browser fixture</title></head>
                    <body><button id="action" onclick="document.querySelector('#result').textContent='BUTTON CLICKED'">
                    Click me</button><p id="result">READY</p></body></html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var body = exchange.getResponseBody()) {
                body.write(html);
            }
        });
        fixture.start();
        try (VisualBrowserTool tool = new VisualBrowserTool(chrome, Duration.ofSeconds(15), true, true)) {
            URI page = URI.create("http://127.0.0.1:" + fixture.getAddress().getPort() + "/");
            ToolResult pageResult = execute(tool, "{\"action\":\"navigate\",\"url\":\"" + page + "\"}");
            String initialText = ((ContentPart.Text) pageResult.content().getFirst()).text();
            assertTrue(initialText.contains("READY"), initialText + " requests=" + requests.get());

            ToolResult result = execute(tool, "{\"action\":\"click\",\"selector\":\"#action\"}");
            String visibleText = ((ContentPart.Text) result.content().getFirst()).text();
            assertTrue(visibleText.contains("BUTTON CLICKED"), visibleText);
            ContentPart.Image image = (ContentPart.Image) result.content().get(1);
            assertEquals("image/png", image.mimeType());
            assertTrue(image.data().length > 100);
            assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G'},
                    java.util.Arrays.copyOf(image.data(), 4));
        } finally {
            fixture.stop(0);
        }
    }

    /**
     * 使用一个 Tool action 执行 smoke test 操作。
     *
     * @param tool CDP 浏览器工具
     * @param arguments JSON action 参数
     * @return 页面文本和截图
     * @throws Exception action 参数解析失败
     */
    private static ToolResult execute(VisualBrowserTool tool, String arguments) throws Exception {
        return tool.execute(
                MAPPER.readTree(arguments),
                new ToolExecutionContext(
                        UUID.randomUUID(),
                        "browser-smoke",
                        new CancellationToken()
                )
        );
    }
}
