package org.fzb.smolagents.memory;

import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.model.TokenUsage;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 记录 Agent 面向调用方的最终答案。
 *
 * @param stepNumber 记忆步骤序号
 * @param startedAt 最终答案步骤时间
 * @param duration 最终答案生成耗时
 * @param answer 最终输出值
 * @param tokenUsage 本步骤额外产生的模型用量
 */
public record FinalAnswerStep(
        int stepNumber,
        Instant startedAt,
        Duration duration,
        AgentValue answer,
        TokenUsage tokenUsage
) implements MemoryStep {
    /**
     * 校验步骤序号、时间、答案及用量。
     *
     * @param stepNumber 记忆步骤序号
     * @param startedAt 开始时间
     * @param duration 步骤耗时
     * @param answer 最终输出值
     * @param tokenUsage 本步骤模型用量
     */
    public FinalAnswerStep {
        if (stepNumber < 0) {
            throw new IllegalArgumentException("stepNumber must not be negative");
        }
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(tokenUsage, "tokenUsage");
    }
}
