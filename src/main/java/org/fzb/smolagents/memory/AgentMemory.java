package org.fzb.smolagents.memory;

import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.MessageRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 保存会话系统提示词与有序步骤，并映射为下一轮模型上下文。
 */
public final class AgentMemory {
    private final List<MemoryStep> steps = new ArrayList<>();
    private final String systemPrompt;

    /**
     * 创建带有固定系统提示词的 Agent 记忆。
     *
     * @param systemPrompt Agent 系统提示词
     */
    public AgentMemory(String systemPrompt) {
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
    }

    /**
     * 使用已校验的记忆快照恢复一个会话。
     *
     * @param systemPrompt 固定系统提示词
     * @param initialSteps 已持久化的步骤
     */
    public AgentMemory(String systemPrompt, List<? extends MemoryStep> initialSteps) {
        this(systemPrompt);
        Objects.requireNonNull(initialSteps, "initialSteps");
        if (initialSteps.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("initialSteps must not contain null values");
        }
        steps.addAll(initialSteps);
    }

    /**
     * 创建系统提示词为空的记忆，主要用于独立记忆操作和测试。
     */
    public AgentMemory() {
        this("");
    }

    /**
     * 返回该会话固定的系统提示词。
     *
     * @return 系统提示词
     */
    public String systemPrompt() {
        return systemPrompt;
    }

    /**
     * 向记忆末尾追加步骤。
     *
     * @param step 新记忆步骤
     */
    public synchronized void append(MemoryStep step) {
        steps.add(Objects.requireNonNull(step, "step"));
    }

    /**
     * 返回不可变的步骤快照。
     *
     * @return 按追加顺序排列的步骤
     */
    public synchronized List<MemoryStep> snapshot() {
        return List.copyOf(steps);
    }

    /**
     * 清除全部会话步骤，同时保留系统提示词。
     */
    public synchronized void reset() {
        steps.clear();
    }

    /**
     * 将系统提示词和记忆步骤转换为模型聊天消息。
     *
     * @param summaryMode 是否省略可压缩的历史文本
     * @return 不可变的消息快照
     */
    public synchronized List<ChatMessage> toMessages(boolean summaryMode) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.text(MessageRole.SYSTEM, systemPrompt));
        for (MemoryStep step : steps) {
            if (step instanceof TaskStep taskStep) {
                messages.add(ChatMessage.text(MessageRole.USER, "New task:\n" + taskStep.task()));
            } else if (step instanceof PlanningStep planningStep) {
                if (!summaryMode) {
                    messages.add(ChatMessage.text(MessageRole.ASSISTANT, planningStep.plan().strip()));
                    messages.add(ChatMessage.text(MessageRole.USER, "Now proceed and carry out this plan."));
                }
            } else if (step instanceof ActionStep actionStep) {
                if (actionStep.modelOutput() != null && !summaryMode) {
                    messages.add(actionStep.modelOutput());
                } else if (actionStep.modelOutput() != null && !actionStep.modelOutput().toolCalls().isEmpty()) {
                    messages.add(new ChatMessage(
                            MessageRole.ASSISTANT,
                            List.of(),
                            actionStep.modelOutput().toolCalls(),
                            actionStep.modelOutput().name(),
                            null,
                            actionStep.modelOutput().tokenUsage()
                    ));
                }
                for (ToolObservation observation : actionStep.observations()) {
                    messages.add(ChatMessage.toolResult(
                            observation.toolName(),
                            observation.callId(),
                            contentFor(observation.result())
                    ));
                }
                if (actionStep.error() != null && !hasRecordedToolFailure(actionStep)) {
                    messages.add(ChatMessage.text(
                            MessageRole.USER,
                            "Error:\n" + actionStep.error()
                                    + "\nNow let's retry: take care not to repeat previous errors! "
                                    + "If you have retried several times, try a completely different approach."
                    ));
                }
            }
        }
        return List.copyOf(messages);
    }

    /**
     * 判断错误是否已包含在关联工具 observation 中，避免同一失败重复进入模型上下文。
     *
     * @param actionStep 当前动作步骤
     * @return observation 已记录该工具失败时为 {@code true}
     */
    private static boolean hasRecordedToolFailure(ActionStep actionStep) {
        for (ToolObservation observation : actionStep.observations()) {
            for (ContentPart part : observation.result().content()) {
                if (!(part instanceof ContentPart.Text textPart)
                        || !textPart.text().startsWith("Error: ")) {
                    continue;
                }
                String detail = textPart.text().substring("Error: ".length()).split("\\R", 2)[0];
                if (!detail.isBlank() && actionStep.error().contains(detail)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 将工具结果转换为聊天消息内容，并保留结构化 JSON。
     *
     * @param value 工具结果值
     * @return 可发送给模型的内容片段
     */
    private static List<ContentPart> contentFor(AgentValue value) {
        List<ContentPart> content = new ArrayList<>(value.content());
        if (value.structuredContent() != null) {
            content.add(new ContentPart.Json(value.structuredContent()));
        }
        return List.copyOf(content);
    }
}
