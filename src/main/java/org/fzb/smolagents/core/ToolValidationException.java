package org.fzb.smolagents.core;

/**
 * 表示工具调用参数未通过 JSON Schema 校验。
 */
public final class ToolValidationException extends SmolagentsException {
    /**
     * 创建工具参数错误。
     *
     * @param message 错误描述
     */
    public ToolValidationException(String message) {
        super(message);
    }

    /**
     * 创建保留底层校验原因的工具参数错误。
     *
     * @param message 错误描述
     * @param cause 原始异常
     */
    public ToolValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
