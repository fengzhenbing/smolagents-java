package org.fzb.smolagents.core;

import java.time.Duration;
import java.util.Objects;

/**
 * 一次 Agent 运行的可覆盖选项。
 *
 * @param reset 是否在开始运行前清空会话记忆
 * @param maxSteps 本次运行的最大动作步数；{@code null} 表示采用 Agent 默认值
 * @param returnFullResult 是否要求调用方保留完整结果语义
 * @param timeout 本次运行的最长时长；{@code null} 表示不额外限制
 * @param cancellation 本次运行的取消令牌
 */
public record RunOptions(
        boolean reset,
        Integer maxSteps,
        boolean returnFullResult,
        Duration timeout,
        CancellationToken cancellation
) {
    /**
     * 校验步数、超时和取消令牌。
     *
     * @param reset 是否清空记忆
     * @param maxSteps 最大动作步数
     * @param returnFullResult 是否返回完整结果语义
     * @param timeout 最长运行时长
     * @param cancellation 取消令牌
     */
    public RunOptions {
        if (maxSteps != null && maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be at least 1");
        }
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Objects.requireNonNull(cancellation, "cancellation");
    }

    /**
     * 创建重置记忆且不覆盖 Agent 默认步数的选项。
     *
     * @return 默认运行选项
     */
    public static RunOptions defaults() {
        return new RunOptions(true, null, false, null, new CancellationToken());
    }
}
