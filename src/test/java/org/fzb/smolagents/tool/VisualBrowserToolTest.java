package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.model.ContentPart;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证视觉浏览器工具的动作 allowlist、安全输入边界和多模态结果。
 */
class VisualBrowserToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证导航动作传入 browser backend，并同时返回可供视觉模型读取的 PNG。
     */
    @Test
    void navigatesAndReturnsScreenshotWithVisiblePageContext() {
        AtomicReference<VisualBrowserTool.BrowserAction> action = new AtomicReference<>();
        VisualBrowserTool tool = new VisualBrowserTool(
                Duration.ofSeconds(2),
                true,
                fake(action, new byte[]{1, 2, 3}, new AtomicBoolean())
        );

        ToolResult result = tool.execute(arguments("""
                {"action":"navigate","url":"https://example.com"}
                """), context());

        assertEquals("navigate", action.get().name());
        assertEquals("https://example.com", action.get().url());
        assertTrue(((ContentPart.Text) result.content().getFirst()).text().contains("Example page"));
        ContentPart.Image image = assertInstanceOf(ContentPart.Image.class, result.content().get(1));
        assertArrayEquals(new byte[]{1, 2, 3}, image.data());
        assertEquals("image/png", image.mimeType());
    }

    /**
     * 验证浏览器目标默认拒绝本机/private host，且无任意 JavaScript 执行动作。
     */
    @Test
    void rejectsPrivateTargetsAndUnknownOrMalformedActions() {
        AtomicBoolean invoked = new AtomicBoolean();
        VisualBrowserTool tool = new VisualBrowserTool(
                Duration.ofSeconds(2),
                false,
                fake(new AtomicReference<>(), null, invoked)
        );

        assertThrows(ToolExecutionException.class,
                () -> tool.execute(arguments("{\"action\":\"navigate\",\"url\":\"http://127.0.0.1/\"}"),
                        context()));
        assertThrows(ToolExecutionException.class,
                () -> tool.execute(arguments("{\"action\":\"execute\",\"text\":\"alert(1)\"}"), context()));
        assertThrows(ToolExecutionException.class,
                () -> tool.execute(arguments("{\"action\":\"click\"}"), context()));
        assertFalse(invoked.get());
    }

    /**
     * 验证键盘、selector 和滚动值仅映射到 allowlisted browser action 参数。
     */
    @Test
    void validatesAndClampsActionParameters() {
        AtomicReference<VisualBrowserTool.BrowserAction> action = new AtomicReference<>();
        VisualBrowserTool tool = new VisualBrowserTool(
                Duration.ofSeconds(2),
                true,
                fake(action, null, new AtomicBoolean())
        );

        tool.execute(arguments("""
                {"action":"scroll","scrollY":90000}
                """), context());
        assertEquals(5_000, action.get().scrollY());
        assertThrows(ToolExecutionException.class,
                () -> tool.execute(arguments("{\"action\":\"press\",\"key\":\"F12\"}"), context()));
        assertThrows(ToolExecutionException.class,
                () -> tool.execute(arguments("{\"action\":\"click\",\"selector\":\"div\\nbody\"}"), context()));
    }

    private static com.fasterxml.jackson.databind.JsonNode arguments(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private static ToolExecutionContext context() {
        return new ToolExecutionContext(UUID.randomUUID(), "browser-test-call", new CancellationToken());
    }

    private static VisualBrowserTool.BrowserBackend fake(
            AtomicReference<VisualBrowserTool.BrowserAction> action,
            byte[] screenshot,
            AtomicBoolean invoked
    ) {
        return new VisualBrowserTool.BrowserBackend() {
            @Override
            public VisualBrowserTool.BrowserResult execute(
                    VisualBrowserTool.BrowserAction value,
                    Duration timeout,
                    CancellationToken cancellation
            ) {
                invoked.set(true);
                action.set(value);
                return new VisualBrowserTool.BrowserResult(
                        value.url().isBlank() ? "https://example.com/current" : value.url(),
                        "Example page",
                        "Visible browser text",
                        screenshot
                );
            }

            @Override
            public void close() {
            }
        };
    }
}
