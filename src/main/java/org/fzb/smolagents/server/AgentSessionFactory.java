package org.fzb.smolagents.server;

import org.fzb.smolagents.agent.Agent;

/**
 * 为一个独立的 Web 会话创建新的 Agent 实例。
 */
@FunctionalInterface
public interface AgentSessionFactory {
    /**
     * 创建与指定会话 ID 绑定的 Agent。
     *
     * @param sessionId 服务生成的稳定会话标识
     * @return 只能由该会话独占使用的 Agent
     */
    Agent create(String sessionId);

    /**
     * 释放会话 Agent 及由该 factory 持有的额外工具/执行器资源。
     *
     * @param sessionId 会话标识
     * @param agent 即将销毁的 Agent
     */
    default void closeSession(String sessionId, Agent agent) {
        if (agent instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception error) {
                throw new IllegalStateException("Failed to close Agent session " + sessionId, error);
            }
        }
    }
}
