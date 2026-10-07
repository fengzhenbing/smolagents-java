package org.fzb.smolagents.core;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用于在运行线程之间安全传递取消请求的轻量令牌。
 */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /**
     * 创建初始状态为未取消的令牌。
     */
    public CancellationToken() {
    }

    /**
     * 首次调用时设置取消标志。
     *
     * @return 本次调用是否完成了从未取消到已取消的状态转换
     */
    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    /**
     * 查询是否已请求取消。
     *
     * @return 已取消时为 {@code true}
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 在当前执行点检查取消标志。
     *
     * @throws AgentRunException 已请求取消时抛出
     */
    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new AgentRunException("Agent run was cancelled");
        }
    }
}
