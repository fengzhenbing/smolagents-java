package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 校验工具参数是否满足工具声明的 JSON Schema。
 */
@FunctionalInterface
public interface ToolArgumentValidator {
    /**
     * 验证参数对象并在不符合 schema 时抛出异常。
     *
     * @param schema 工具输入 JSON Schema
     * @param arguments 实际 JSON 参数
     */
    void validate(JsonNode schema, JsonNode arguments);
}
