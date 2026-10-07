package org.fzb.smolagents.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 表示模型输出的一次结构化工具调用。
 *
 * @param id Provider 提供或适配器生成的调用标识
 * @param name 工具名称
 * @param arguments JSON object 类型的调用参数
 */
public record ToolCall(String id, String name, JsonNode arguments) {
    /**
     * 校验调用标识、名称和 JSON 参数，并复制参数树。
     *
     * @param id 工具调用标识
     * @param name 工具名称
     * @param arguments JSON object 参数
     */
    public ToolCall {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("tool call id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        Objects.requireNonNull(arguments, "arguments");
        if (!arguments.isObject()) {
            throw new IllegalArgumentException("tool call arguments must be a JSON object");
        }
        arguments = arguments.deepCopy();
    }

    /**
     * 返回调用参数的防御性副本。
     *
     * @return JSON object 副本
     */
    @Override
    public JsonNode arguments() {
        return arguments.deepCopy();
    }
}
