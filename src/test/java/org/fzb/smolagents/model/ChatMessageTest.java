package org.fzb.smolagents.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fzb.smolagents.core.AgentInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证聊天消息关联规则及 JSON 输入值的防御性复制。
 */
class ChatMessageTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 验证工具响应消息必须包含对应的调用 ID。
     */
    @Test
    void toolMessagesRequireCallIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatMessage(MessageRole.TOOL, List.of(), List.of(), null, null)
        );
    }

    /**
     * 验证工具调用参数在构造后不可被外部修改。
     *
     * @throws Exception JSON 测试数据解析失败时抛出
     */
    @Test
    void toolCallArgumentsAreDefensivelyCopied() throws Exception {
        ObjectNode mutableArguments = (ObjectNode) mapper.readTree("{\"value\":1}");
        ToolCall call = new ToolCall("call-1", "sample", mutableArguments);

        mutableArguments.put("value", 2);
        ((ObjectNode) call.arguments()).put("value", 3);

        assertEquals(1, call.arguments().get("value").asInt());
    }

    /**
     * 验证 Agent 输入中的额外 JSON 参数不会暴露可变内部状态。
     *
     * @throws Exception JSON 测试数据解析失败时抛出
     */
    @Test
    void additionalArgumentsAreDefensivelyCopied() throws Exception {
        ObjectNode value = (ObjectNode) mapper.readTree("{\"nested\":true}");
        AgentInput input = new AgentInput("task", Map.of("context", value));

        value.put("nested", false);

        assertTrue(input.additionalArguments().get("context").get("nested").asBoolean());
    }

    /**
     * 验证结构化输出 schema 在创建和读取时均进行防御性复制。
     */
    @Test
    void generationOptionsDefensivelyCopyResponseSchema() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        GenerationOptions options = new GenerationOptions(null, null, null, schema);

        schema.put("type", "string");
        ((ObjectNode) options.responseSchema()).put("type", "array");

        assertEquals("object", options.responseSchema().path("type").asText());
    }
}
