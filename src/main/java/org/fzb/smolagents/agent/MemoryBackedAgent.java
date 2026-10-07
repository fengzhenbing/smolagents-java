package org.fzb.smolagents.agent;

import org.fzb.smolagents.memory.AgentMemory;

/**
 * 暴露可显式恢复的 Agent 会话记忆，以便持久化适配器保存/恢复对话。
 */
public interface MemoryBackedAgent extends Agent {
    /**
     * 返回此 Agent 的会话记忆。
     *
     * @return 当前会话记忆
     */
    AgentMemory memory();

    /**
     * 恢复兼容系统提示词的安全记忆快照。
     *
     * @param memory 已验证解码的 AgentMemory
     */
    void restoreMemory(AgentMemory memory);
}
