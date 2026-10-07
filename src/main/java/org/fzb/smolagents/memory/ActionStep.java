package org.fzb.smolagents.memory;

import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.TokenUsage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 记录一次模型动作、调用观察结果和 token 用量。
 *
 * @param stepNumber 动作序号
 * @param startedAt 动作开始时间
 * @param duration 动作总耗时
 * @param modelOutput 模型 assistant 输出
 * @param observations 工具调用观察结果
 * @param tokenUsage 模型响应的 token 用量
 * @param error 本动作的校验错误；无错误时为空
 */
public record ActionStep(
        int stepNumber,
        Instant startedAt,
        Duration duration,
        ChatMessage modelOutput,
        List<ToolObservation> observations,
        TokenUsage tokenUsage,
        String error
) implements MemoryStep {
    /**
     * 创建不含错误的动作步骤，保留原有构造方式。
     *
     * @param stepNumber 动作序号
     * @param startedAt 开始时间
     * @param duration 动作耗时
     * @param modelOutput 模型输出
     * @param observations 工具观察结果
     * @param tokenUsage 模型用量
     */
    public ActionStep(
            int stepNumber,
            Instant startedAt,
            Duration duration,
            ChatMessage modelOutput,
            List<ToolObservation> observations,
            TokenUsage tokenUsage
    ) {
        this(stepNumber, startedAt, duration, modelOutput, observations, tokenUsage, null);
    }

    /**
     * 校验动作时间、模型输出及其观察结果。
     *
     * @param stepNumber 动作序号
     * @param startedAt 开始时间
     * @param duration 动作耗时
     * @param modelOutput 模型输出
     * @param observations 工具观察结果
     * @param tokenUsage 模型用量
     * @param error 本动作的校验错误；无错误时为空
     */
    public ActionStep {
        if (stepNumber < 0) {
            throw new IllegalArgumentException("stepNumber must not be negative");
        }
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        Objects.requireNonNull(modelOutput, "modelOutput");
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        Objects.requireNonNull(tokenUsage, "tokenUsage");
        if (error != null && error.isBlank()) {
            throw new IllegalArgumentException("error must not be blank when provided");
        }
    }
}
