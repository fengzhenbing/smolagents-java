package org.fzb.smolagents.executor;

import java.util.Objects;

/**
 * 描述代码执行失败及可选的源代码行号。
 *
 * @param type 错误类型
 * @param message 错误消息
 * @param lineNumber 出错行号；未知时为空
 */
public record ExecutorFailure(String type, String message, Integer lineNumber) {
    /**
     * 校验失败类型和行号。
     *
     * @param type 错误类型
     * @param message 错误消息
     * @param lineNumber 出错行号
     */
    public ExecutorFailure {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        Objects.requireNonNull(message, "message");
        if (lineNumber != null && lineNumber < 1) {
            throw new IllegalArgumentException("lineNumber must be positive");
        }
    }
}
