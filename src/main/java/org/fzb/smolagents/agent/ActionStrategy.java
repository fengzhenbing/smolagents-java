package org.fzb.smolagents.agent;

import java.util.List;
import org.fzb.smolagents.model.ChatMessage;

/**
 * 定义 Agent 每一步的模型决策和达到步数上限后的答复策略。
 */
public interface ActionStrategy {
    /**
     * 根据当前消息和工具定义执行一步动作。
     *
     * @param turn 本步输入快照
     * @param context 本次运行上下文
     * @return 继续执行或已经完成的动作结果
     */
    ActionOutcome perform(AgentTurn turn, RunContext context);

    /**
     * 在 Agent 事件流运行中执行一步动作。
     *
     * @param turn 本步输入快照
     * @param context 本次运行上下文
     * @return 继续执行或已经完成的动作结果
     */
    default ActionOutcome performStreaming(AgentTurn turn, RunContext context) {
        return perform(turn, context);
    }

    /**
     * 在动作步数耗尽时生成兜底答案。
     *
     * @param messages 当前对话消息
     * @param context 本次运行上下文
     * @return 最终答案及其 token 用量
     * @throws IllegalStateException 默认实现不支持兜底回答时抛出
     */
    default FinalAnswerResponse provideFinalAnswer(List<ChatMessage> messages, RunContext context) {
        throw new IllegalStateException("this action strategy does not support max-step final answers");
    }
}
