package org.fzb.smolagents.agent;

import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.memory.AgentMemory;

/**
 * 在 Agent 接受最终答案前对答案进行验证。
 */
@FunctionalInterface
public interface FinalAnswerCheck {
    /**
     * 验证候选答案；返回 {@code false} 或抛出异常会让 Agent 记录错误并继续运行。
     *
     * @param finalAnswer 候选最终答案
     * @param memory 当前会话记忆；尚不包含正在校验的动作步骤
     * @param agent 当前 Agent 运行时，可用于查询工具和记忆
     * @return 答案有效时为 {@code true}
     * @throws Exception 校验器执行失败时抛出，错误会成为动作步骤的一部分
     */
    boolean isValid(AgentValue finalAnswer, AgentMemory memory, AgentRuntime agent) throws Exception;
}
