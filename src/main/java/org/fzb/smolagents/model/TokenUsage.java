package org.fzb.smolagents.model;

import java.util.OptionalLong;

/**
 * 表示模型调用的输入和输出 token 数量，允许 Provider 未返回统计值。
 *
 * @param promptTokens 输入 token 数；未知时为空
 * @param completionTokens 输出 token 数；未知时为空
 */
public record TokenUsage(OptionalLong promptTokens, OptionalLong completionTokens) {
    /**
     * 校验 token 数量非负，未知值必须使用空 Optional。
     *
     * @param promptTokens 输入 token 数
     * @param completionTokens 输出 token 数
     */
    public TokenUsage {
        if (promptTokens == null || completionTokens == null) {
            throw new IllegalArgumentException("token counts must use OptionalLong.empty() when unknown");
        }
        if ((promptTokens.isPresent() && promptTokens.getAsLong() < 0)
                || (completionTokens.isPresent() && completionTokens.getAsLong() < 0)) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    /**
     * 创建两项用量均未知的统计值。
     *
     * @return 未知用量
     */
    public static TokenUsage unknown() {
        return new TokenUsage(OptionalLong.empty(), OptionalLong.empty());
    }

    /**
     * 创建已知输入和输出 token 数的统计值。
     *
     * @param promptTokens 输入 token 数
     * @param completionTokens 输出 token 数
     * @return 已知用量
     */
    public static TokenUsage of(long promptTokens, long completionTokens) {
        return new TokenUsage(OptionalLong.of(promptTokens), OptionalLong.of(completionTokens));
    }

    /**
     * 计算总 token 数；任一部分未知时总数也未知。
     *
     * @return 可用时返回输入与输出 token 总和
     */
    public OptionalLong totalTokens() {
        if (promptTokens.isEmpty() || completionTokens.isEmpty()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.addExact(promptTokens.getAsLong(), completionTokens.getAsLong()));
    }

    /**
     * 合并两次调用的用量；任一部分未知时保留未知状态。
     *
     * @param other 待累加的用量
     * @return 合并后的统计值
     */
    public TokenUsage plus(TokenUsage other) {
        if (promptTokens.isEmpty() || completionTokens.isEmpty()
                || other.promptTokens.isEmpty() || other.completionTokens.isEmpty()) {
            return unknown();
        }
        return of(
                Math.addExact(promptTokens.getAsLong(), other.promptTokens.getAsLong()),
                Math.addExact(completionTokens.getAsLong(), other.completionTokens.getAsLong())
        );
    }
}
