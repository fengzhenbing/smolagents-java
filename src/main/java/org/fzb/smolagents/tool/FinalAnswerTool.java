package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.core.ToolValidationException;
import org.fzb.smolagents.model.ContentPart;

import java.util.List;

/**
 * 将模型给出的 {@code answer} 参数转换为 Agent 最终答案的内置工具。
 */
public final class FinalAnswerTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ToolSpec spec = new ToolSpec(
            "final_answer",
            "Provides a final answer to the given problem.",
            createSchema(),
            true,
            "any"
    );

    /**
     * 创建内置最终答案工具。
     */
    public FinalAnswerTool() {
    }

    /**
     * 返回 final_answer 的 JSON Schema 定义。
     *
     * @return 工具定义
     */
    @Override
    public ToolSpec spec() {
        return spec;
    }

    /**
     * 验证并提取最终答案值。
     *
     * @param arguments 包含 answer 字段的 JSON object
     * @param context 当前工具调用上下文
     * @return 可保留文本或结构化 JSON 的工具结果
     */
    @Override
    public ToolResult execute(JsonNode arguments, ToolExecutionContext context) throws ToolExecutionException {
        JsonNode answer = arguments.get("answer");
        if (answer == null) {
            throw new ToolValidationException("final_answer requires an answer");
        }
        AgentValue value;
        if (answer.isTextual()) {
            value = AgentValue.text(answer.asText());
        } else {
            value = new AgentValue(List.of(new ContentPart.Json(answer)), answer);
        }
        return new ToolResult(value.content(), value.structuredContent());
    }

    /**
     * 构造要求 answer 字段且不允许额外字段的 JSON Schema。
     *
     * @return final_answer 输入 schema
     */
    private static JsonNode createSchema() {
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", MAPPER.createObjectNode().set(
                "answer",
                MAPPER.createObjectNode().put("description", "The final answer to the problem")
        ));
        schema.putArray("required").add("answer");
        schema.put("additionalProperties", false);
        return schema;
    }
}
