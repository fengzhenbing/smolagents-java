package org.fzb.smolagents.core;

/**
 * 表示模型输出不符合消息或动作协议。
 */
public final class ModelOutputException extends ModelException {
    /**
     * 创建模型输出错误。
     *
     * @param message 错误描述
     */
    public ModelOutputException(String message) {
        super(message);
    }

    /**
     * 创建保留解析原因的模型输出错误。
     *
     * @param message 错误描述
     * @param cause 原始解析异常
     */
    public ModelOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
