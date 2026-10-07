package org.fzb.smolagents.memory;

import org.fzb.smolagents.core.AgentValue;

import java.util.Objects;

/**
 * 关联一次模型工具调用与对应的工具输出。
 *
 * @param callId 模型工具调用 ID
 * @param toolName 工具名称
 * @param result 工具输出值
 */
public record ToolObservation(String callId, String toolName, AgentValue result) {
    /**
     * 校验调用、工具及结果信息。
     *
     * @param callId 模型工具调用 ID
     * @param toolName 工具名称
     * @param result 工具输出值
     */
    public ToolObservation {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        Objects.requireNonNull(result, "result");
    }
}
