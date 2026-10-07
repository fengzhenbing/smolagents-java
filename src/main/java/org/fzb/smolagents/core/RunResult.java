package org.fzb.smolagents.core;

import org.fzb.smolagents.memory.MemoryStep;
import org.fzb.smolagents.model.TokenUsage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 保存一次运行的答案、终止状态、步骤快照和统计信息。
 *
 * @param runId 本次运行的唯一标识
 * @param output 最终答案；失败运行可为空
 * @param status 终止状态
 * @param steps 运行结束时的完整记忆快照
 * @param tokenUsage 本次可确认的 token 用量
 * @param startedAt 运行开始时间
 * @param duration 运行耗时
 */
public record RunResult(
        UUID runId,
        AgentValue output,
        RunStatus status,
        List<MemoryStep> steps,
        TokenUsage tokenUsage,
        Instant startedAt,
        Duration duration
) {
    /**
     * 校验运行结果字段及成功状态的答案约束。
     *
     * @param runId 本次运行的唯一标识
     * @param output 最终答案
     * @param status 终止状态
     * @param steps 完整记忆快照
     * @param tokenUsage token 用量
     * @param startedAt 开始时间
     * @param duration 运行耗时
     */
    public RunResult {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(status, "status");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        Objects.requireNonNull(tokenUsage, "tokenUsage");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        if (status == RunStatus.SUCCESS && output == null) {
            throw new IllegalArgumentException("successful run must have an output");
        }
    }
}
