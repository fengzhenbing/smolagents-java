package org.fzb.smolagents.core;

/**
 * 表示工具已通过参数校验，但执行期间发生业务或运行时错误。
 */
public final class ToolExecutionException extends SmolagentsException {
    /**
     * 创建工具执行错误。
     *
     * @param message 错误描述
     */
    public ToolExecutionException(String message) {
        super(message);
    }

    /**
     * 创建保留原始执行原因的工具错误。
     *
     * @param message 错误描述
     * @param cause 原始异常
     */
    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
