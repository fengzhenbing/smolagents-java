package org.fzb.smolagents.tool;

import org.fzb.smolagents.core.ToolExecutionException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 可由 Agent 按 JSON 参数调用的工具契约。
 */
public interface Tool {
    /**
     * 返回工具名称、描述和输入 JSON Schema。
     *
     * @return 工具定义
     */
    ToolSpec spec();

    /**
     * 使用已校验的 JSON 参数执行工具逻辑。
     *
     * @param arguments 工具参数
     * @param context 当前调用上下文
     * @return 工具执行结果
     * @throws ToolExecutionException 工具执行失败时抛出
     */
    ToolResult execute(JsonNode arguments, ToolExecutionContext context) throws ToolExecutionException;
}
