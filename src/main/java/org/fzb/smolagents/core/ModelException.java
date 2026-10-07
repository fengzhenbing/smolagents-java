package org.fzb.smolagents.core;

/**
 * 表示模型调用、传输或 Provider 协议处理失败。
 */
public class ModelException extends SmolagentsException {
    /**
     * 创建模型错误。
     *
     * @param message 错误描述
     */
    public ModelException(String message) {
        super(message);
    }

    /**
     * 创建保留原始异常原因的模型错误。
     *
     * @param message 错误描述
     * @param cause 原始异常
     */
    public ModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
