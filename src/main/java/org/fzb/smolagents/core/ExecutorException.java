package org.fzb.smolagents.core;

/**
 * 表示代码执行器、运行进程或沙箱执行失败。
 */
public final class ExecutorException extends SmolagentsException {
    /**
     * 创建执行器错误。
     *
     * @param message 错误描述
     */
    public ExecutorException(String message) {
        super(message);
    }

    /**
     * 创建保留底层执行原因的执行器错误。
     *
     * @param message 错误描述
     * @param cause 原始异常
     */
    public ExecutorException(String message, Throwable cause) {
        super(message, cause);
    }
}
