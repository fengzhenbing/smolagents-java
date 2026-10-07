package org.fzb.smolagents.core;

/**
 * smolagents Java API 的运行时异常基类。
 */
public class SmolagentsException extends RuntimeException {
    /**
     * 创建带有诊断信息的异常。
     *
     * @param message 错误描述
     */
    public SmolagentsException(String message) {
        super(message);
    }

    /**
     * 创建保留原始原因的异常。
     *
     * @param message 错误描述
     * @param cause 原始异常
     */
    public SmolagentsException(String message, Throwable cause) {
        super(message, cause);
    }
}
