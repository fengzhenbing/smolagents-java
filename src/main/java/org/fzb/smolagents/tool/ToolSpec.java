package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Set;

/**
 * 暴露给模型的工具元数据及 JSON Schema 输入约束。
 *
 * @param name 唯一工具名称
 * @param description 提供给模型的工具说明
 * @param inputSchema JSON Schema object
 * @param enabled 工具是否可注册和调用
 * @param outputType Python 兼容的工具结果类型
 * @param outputSchema 可选的结构化结果 JSON Schema
 */
public record ToolSpec(
        String name,
        String description,
        JsonNode inputSchema,
        boolean enabled,
        String outputType,
        JsonNode outputSchema
) {
    private static final Set<String> SUPPORTED_OUTPUT_TYPES = Set.of(
            "string", "boolean", "integer", "number", "image", "audio", "array", "object", "any", "null"
    );

    /**
     * 保留旧构造方式，并为尚未声明输出类型的工具使用兼容默认值。
     *
     * @param name 唯一工具名称
     * @param description 工具说明
     * @param inputSchema JSON Schema object
     * @param enabled 是否启用
     */
    public ToolSpec(String name, String description, JsonNode inputSchema, boolean enabled) {
        this(name, description, inputSchema, enabled, "final_answer".equals(name) ? "any" : "string", null);
    }

    /**
     * 创建包含显式输出类型的工具规格。
     *
     * @param name 唯一工具名称
     * @param description 工具说明
     * @param inputSchema JSON Schema object
     * @param enabled 是否启用
     * @param outputType Python 兼容的工具结果类型
     */
    public ToolSpec(String name, String description, JsonNode inputSchema, boolean enabled, String outputType) {
        this(name, description, inputSchema, enabled, outputType, null);
    }

    /**
     * 校验工具名称、输入/输出 schema 与输出类型，并复制 schema 树。
     *
     * @param name 唯一工具名称
     * @param description 工具说明
     * @param inputSchema JSON Schema object
     * @param enabled 是否启用
     * @param outputType Python 兼容的工具结果类型
     * @param outputSchema 可选的结构化结果 JSON Schema
     */
    public ToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        if (!inputSchema.isObject()) {
            throw new IllegalArgumentException("tool input schema must be a JSON object");
        }
        JsonNode type = inputSchema.get("type");
        if (type != null && (!type.isTextual() || !"object".equals(type.asText()))) {
            throw new IllegalArgumentException("tool input schema type must be object");
        }
        Objects.requireNonNull(outputType, "outputType");
        if (!SUPPORTED_OUTPUT_TYPES.contains(outputType)) {
            throw new IllegalArgumentException("unsupported tool output type: " + outputType);
        }
        if (outputSchema != null && !outputSchema.isObject()) {
            throw new IllegalArgumentException("tool output schema must be a JSON object");
        }
        inputSchema = inputSchema.deepCopy();
        outputSchema = outputSchema == null ? null : outputSchema.deepCopy();
    }

    /**
     * 返回 schema 的防御性副本。
     *
     * @return JSON Schema 副本
     */
    @Override
    public JsonNode inputSchema() {
        return inputSchema.deepCopy();
    }

    /**
     * 返回结构化输出 schema 的防御性副本。
     *
     * @return JSON Schema 副本；没有结构化输出契约时返回 null
     */
    @Override
    public JsonNode outputSchema() {
        return outputSchema == null ? null : outputSchema.deepCopy();
    }
}
