package org.fzb.smolagents.memory;

import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.MessageRole;
import org.fzb.smolagents.model.TokenUsage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 记录模型生成的执行计划及其用量。
 *
 * @param stepNumber 记忆步骤序号
 * @param startedAt 计划生成开始时间
 * @param plan 计划文本
 * @param tokenUsage 计划生成用量
 * @param modelInputMessages 生成计划时发送给模型的消息
 * @param modelOutputMessage 模型返回的原始计划消息
 */
public record PlanningStep(
        int stepNumber,
        Instant startedAt,
        String plan,
        TokenUsage tokenUsage,
        List<ChatMessage> modelInputMessages,
        ChatMessage modelOutputMessage
)
        implements MemoryStep {
    /**
     * 校验步骤序号、时间、计划和用量。
     *
     * @param stepNumber 记忆步骤序号
     * @param startedAt 开始时间
     * @param plan 计划文本
     * @param tokenUsage 模型用量
     * @param modelInputMessages 模型输入消息
     * @param modelOutputMessage 模型原始输出消息
     */
    public PlanningStep {
        if (stepNumber < 0) {
            throw new IllegalArgumentException("stepNumber must not be negative");
        }
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(tokenUsage, "tokenUsage");
        modelInputMessages = List.copyOf(Objects.requireNonNull(modelInputMessages, "modelInputMessages"));
        Objects.requireNonNull(modelOutputMessage, "modelOutputMessage");
        if (modelInputMessages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("modelInputMessages must not contain null values");
        }
    }

    /**
     * 创建兼容旧调用方的计划记录，并以格式化计划作为原始 assistant 内容。
     *
     * @param stepNumber 记忆步骤序号
     * @param startedAt 计划生成开始时间
     * @param plan 计划文本
     * @param tokenUsage 计划生成用量
     */
    public PlanningStep(int stepNumber, Instant startedAt, String plan, TokenUsage tokenUsage) {
        this(
                stepNumber,
                startedAt,
                plan,
                tokenUsage,
                List.of(),
                ChatMessage.text(MessageRole.ASSISTANT, plan)
        );
    }
}
