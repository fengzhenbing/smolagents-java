package org.fzb.smolagents.tool;

import org.fzb.smolagents.core.CancellationToken;

import java.util.Objects;
import java.util.UUID;

/**
 * 提供工具调用期间可使用的运行标识和取消信号。
 *
 * @param runId 所属 Agent run ID
 * @param callId 本次工具调用 ID
 * @param cancellation 运行取消令牌
 */
public record ToolExecutionContext(UUID runId, String callId, CancellationToken cancellation) {
    /**
     * 校验运行、调用和取消信息。
     *
     * @param runId 所属 Agent run ID
     * @param callId 本次工具调用 ID
     * @param cancellation 运行取消令牌
     */
    public ToolExecutionContext {
        Objects.requireNonNull(runId, "runId");
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        Objects.requireNonNull(cancellation, "cancellation");
    }
}
