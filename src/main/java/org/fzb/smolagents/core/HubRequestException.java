package org.fzb.smolagents.core;

/**
 * 表示 Hugging Face Hub 请求或远端元数据处理失败。
 */
public final class HubRequestException extends SmolagentsException {
    /** 失败响应中的 HTTP 状态码；非 HTTP 错误为 -1。 */
    private final int statusCode;

    /**
     * 创建不关联 HTTP 状态码的 Hub 错误。
     *
     * @param message 错误描述
     */
    public HubRequestException(String message) {
        this(message, -1, null);
    }

    /**
     * 创建包含 HTTP 状态码的 Hub 错误。
     *
     * @param message 错误描述
     * @param statusCode HTTP 状态码；非 HTTP 错误使用 -1
     */
    public HubRequestException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    /**
     * 创建保留原始原因的 Hub 错误。
     *
     * @param message 错误描述
     * @param cause 原始异常
     */
    public HubRequestException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    /**
     * 创建包含状态码和可选原因的 Hub 错误。
     *
     * @param message 错误描述
     * @param statusCode HTTP 状态码；非 HTTP 错误使用 -1
     * @param cause 原始异常；可为空
     */
    private HubRequestException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * 返回 Hub 请求的 HTTP 状态码。
     *
     * @return HTTP 状态码；若错误并非 HTTP 响应则为 -1
     */
    public int statusCode() {
        return statusCode;
    }
}
