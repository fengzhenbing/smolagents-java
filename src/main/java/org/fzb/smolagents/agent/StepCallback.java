package org.fzb.smolagents.agent;

import org.fzb.smolagents.memory.MemoryStep;

/**
 * 接收 Agent 运行期间完成的记忆步骤。
 */
@FunctionalInterface
public interface StepCallback {
    /**
     * 处理已完成的步骤；回调异常会使当前 Agent run 失败。
     *
     * @param step 已生成但尚未追加到会话记忆的步骤
     * @param agent 当前 Agent 运行时
     */
    void onStep(MemoryStep step, AgentRuntime agent);
}
