package org.fzb.smolagents.core;

/**
 * 表示同一个有状态 Agent 会话正被另一次运行占用。
 */
public final class ConcurrentRunException extends SmolagentsException {
    /**
     * 创建并发运行错误。
     *
     * @param message 错误描述
     */
    public ConcurrentRunException(String message) {
        super(message);
    }
}
