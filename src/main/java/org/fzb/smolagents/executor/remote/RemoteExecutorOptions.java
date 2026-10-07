package org.fzb.smolagents.executor.remote;

import java.time.Duration;
import java.util.Objects;

/**
 * 远程 Jupyter 执行的超时及内存边界。
 *
 * @param setupTimeout 创建/删除内核及建立 WebSocket 的时限
 * @param defaultExecutionTimeout 请求未指定时采用的执行时限
 * @param maxOutputChars 最多保留的标准输出字符数
 * @param maxMessageChars 单条 Jupyter WebSocket 消息和结果 JSON 的最大字符数
 * @param maxHttpResponseBytes 内核生命周期 HTTP 响应的最大字节数
 */
public record RemoteExecutorOptions(
        Duration setupTimeout,
        Duration defaultExecutionTimeout,
        int maxOutputChars,
        int maxMessageChars,
        int maxHttpResponseBytes
) {
    /**
     * 校验各项边界，防止无限等待或无限制缓冲。
     *
     * @param setupTimeout 建立远程资源的时限
     * @param defaultExecutionTimeout 默认代码执行时限
     * @param maxOutputChars 标准输出上限
     * @param maxMessageChars 单条协议消息上限
     * @param maxHttpResponseBytes HTTP 响应上限
     */
    public RemoteExecutorOptions {
        Objects.requireNonNull(setupTimeout, "setupTimeout");
        Objects.requireNonNull(defaultExecutionTimeout, "defaultExecutionTimeout");
        if (setupTimeout.isZero() || setupTimeout.isNegative()
                || defaultExecutionTimeout.isZero() || defaultExecutionTimeout.isNegative()) {
            throw new IllegalArgumentException("remote executor timeouts must be positive");
        }
        if (maxOutputChars < 0 || maxOutputChars > 8 * 1024 * 1024
                || maxMessageChars < 1024 || maxMessageChars > 8 * 1024 * 1024
                || maxHttpResponseBytes < 1024 || maxHttpResponseBytes > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("remote executor response bounds are invalid");
        }
    }

    /**
     * 返回保守的默认配置。
     *
     * @return 有界默认配置
     */
    public static RemoteExecutorOptions defaults() {
        return new RemoteExecutorOptions(
                Duration.ofSeconds(20),
                Duration.ofSeconds(60),
                64 * 1024,
                1024 * 1024,
                64 * 1024
        );
    }
}
