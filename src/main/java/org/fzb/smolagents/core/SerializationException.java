package org.fzb.smolagents.core;

/**
 * 表示安全序列化或反序列化失败。
 */
public final class SerializationException extends SmolagentsException {
    /**
     * 创建安全序列化异常。
     *
     * @param message 错误描述
     */
    public SerializationException(String message) {
        super(message);
    }

    /**
     * 创建保留原始原因的安全序列化异常。
     *
     * @param message 错误描述
     * @param cause 原始异常
     */
    public SerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
