package org.fzb.smolagents.core;

/**
 * 表示 Agent、模型或工具装配配置无效。
 */
public final class ConfigurationException extends SmolagentsException {
    /**
     * 创建配置错误。
     *
     * @param message 错误描述
     */
    public ConfigurationException(String message) {
        super(message);
    }

    /**
     * 创建保留配置解析原始原因的错误。
     *
     * @param message 错误描述
     * @param cause 配置解析失败的原始原因
     */
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
