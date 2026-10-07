package org.fzb.smolagents.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.agent.CodeBlockTags;
import org.fzb.smolagents.core.ModelOutputException;
import org.fzb.smolagents.core.SerializationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证代码块/JSON 提取、截断、安全转换和名称校验工具函数。
 */
class AgentUtilsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证文本围栏和自定义 literal code tags 都能提取多段代码。
     */
    @Test
    void extractsMultipleMarkdownAndCustomCodeBlocks() {
        String markdown = "Reasoning\n```python\nprint(1)\n```\n```py\nprint(2)\n```";
        String custom = "Before <py>first()</py> between <py>second()</py> after";

        assertEquals("print(1)\n\nprint(2)", AgentUtils.extractCodeFromText(markdown));
        assertEquals(
                "first()\n\nsecond()",
                AgentUtils.extractCodeFromText(custom, new CodeBlockTags("<py>", "</py>"))
        );
        assertNull(AgentUtils.extractCodeFromText("plain output"));
    }

    /**
     * 验证大括号、转义引号和 JSON 花括号字符串不会混淆 blob 边界。
     *
     * @throws Exception 测试期望 JSON 构造失败时抛出
     */
    @Test
    void parsesJsonObjectFromSurroundingText() throws Exception {
        AgentUtils.ParsedJsonBlob parsed = AgentUtils.parseJsonBlob(
                "tool output: {\"nested\":{\"value\":\"} and \\\\\\\" text\"},\"n\":4} trailing text"
        );

        assertEquals("tool output: ", parsed.prefix());
        assertEquals(" trailing text", parsed.suffix());
        assertEquals(4, parsed.value().path("n").asInt());
        assertEquals("} and \\\" text", parsed.value().path("nested").path("value").asText());
        assertThrows(ModelOutputException.class, () -> AgentUtils.parseJsonBlob("no object"));
    }

    /**
     * 验证截断保留 Unicode code points 的首尾，不会切断代理项。
     */
    @Test
    void truncatesTextByUnicodeCharactersAndPreservesShortInputs() {
        assertEquals("short", AgentUtils.truncateContent("short", 8));
        String truncated = AgentUtils.truncateContent("甲乙丙丁戊己", 4);

        assertTrue(truncated.startsWith("甲乙"));
        assertTrue(truncated.endsWith("戊己"));
        assertTrue(truncated.contains("below 4 characters"));
        assertFalse(truncated.contains("\uFFFD"));
    }

    /**
     * 验证名称判断遵守标识符和 keyword 约束。
     */
    @Test
    void validatesPythonLikeNames() {
        assertTrue(AgentUtils.isValidName("_agent_变量2"));
        assertFalse(AgentUtils.isValidName(""));
        assertFalse(AgentUtils.isValidName("agent-name"));
        assertFalse(AgentUtils.isValidName("class"));
        assertFalse(AgentUtils.isValidName("type"));
        assertFalse(AgentUtils.isValidName(null));
    }

    /**
     * 验证终端清理与安全 JSON 转换拒绝未经允许的 Java 类。
     */
    @Test
    void sanitizesControlsAndRejectsUnknownObjectSerialization() {
        assertEquals("x\\x01y\nz", AgentUtils.sanitizeForConsole("x\u0001y\nz"));
        assertEquals("�", AgentUtils.sanitizeForConsole(new byte[]{(byte) 0xff}));
        assertEquals(MAPPER.createObjectNode().put("value", 1),
                AgentUtils.makeJsonSerializable(Map.of("value", 1)));
        assertThrows(SerializationException.class, () -> AgentUtils.makeJsonSerializable(new CustomObject()));
    }

    /**
     * 用于断言自定义对象不进入安全 JSON allowlist 的测试类型。
     */
    private static final class CustomObject {
    }
}
