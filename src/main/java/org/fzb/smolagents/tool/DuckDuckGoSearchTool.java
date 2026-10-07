package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * 以 DuckDuckGo Lite HTML endpoint 实现默认工具集中的 DuckDuckGo 文本搜索。
 *
 * <p>该实现不依赖 Python {@code ddgs} SDK；与 SDK-backed 实现相比，不暴露其专属选项，
 * 但保留 query、最大结果数、速率限制和 Markdown 输出契约。</p>
 */
public final class DuckDuckGoSearchTool implements Tool {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private final WebSearchTool delegate;
    private final Long minimumIntervalNanos;
    private final Object rateLimitLock = new Object();
    private long nextRequestNanos;

    /**
     * 使用 10 条结果和每秒 1 次请求的默认配置创建工具。
     */
    public DuckDuckGoSearchTool() {
        this(10, 1.0);
    }

    /**
     * 使用指定结果上限和可选速率限制创建工具。
     *
     * @param maxResults 最多返回的结果数
     * @param rateLimit 每秒最大请求数；null 或 0 表示禁用限制
     */
    public DuckDuckGoSearchTool(int maxResults, Double rateLimit) {
        this(
                maxResults,
                rateLimit,
                URI.create("https://lite.duckduckgo.com/lite/"),
                DEFAULT_TIMEOUT
        );
    }

    /**
     * 使用可配置 endpoint 创建工具，供部署环境和本地测试注入。
     *
     * @param maxResults 最多返回的结果数
     * @param rateLimit 每秒最大请求数；null 或 0 表示禁用限制
     * @param endpoint DuckDuckGo Lite HTML endpoint
     * @param timeout 单次 HTTP 请求超时
     */
    public DuckDuckGoSearchTool(int maxResults, Double rateLimit, URI endpoint, Duration timeout) {
        if (rateLimit != null && (!Double.isFinite(rateLimit) || rateLimit < 0)) {
            throw new ConfigurationException("rateLimit must be finite and non-negative, or null");
        }
        this.minimumIntervalNanos = rateLimit == null || rateLimit == 0
                ? null
                : Math.max(1L, (long) (1_000_000_000.0 / rateLimit));
        this.delegate = new WebSearchTool(
                maxResults,
                WebSearchTool.SearchEngine.DUCKDUCKGO,
                Objects.requireNonNull(endpoint, "endpoint"),
                null,
                Objects.requireNonNull(timeout, "timeout")
        );
    }

    /**
     * 返回 DuckDuckGo 搜索工具定义。
     *
     * @return query 字符串 schema
     */
    @Override
    public ToolSpec spec() {
        return delegate.spec();
    }

    /**
     * 等待速率窗口后执行 DuckDuckGo Lite 搜索。
     *
     * @param arguments 已验证的 query 参数
     * @param context 当前工具调用上下文
     * @return 搜索结果 Markdown
     */
    @Override
    public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
        Objects.requireNonNull(context, "context");
        awaitRateLimit(context.cancellation());
        return delegate.execute(arguments, context);
    }

    /**
     * 在等待请求间隔时定期检查取消状态。
     *
     * @param cancellation 当前工具调用取消令牌
     */
    private void awaitRateLimit(CancellationToken cancellation) {
        if (minimumIntervalNanos == null) {
            return;
        }
        long deadline;
        synchronized (rateLimitLock) {
            long now = System.nanoTime();
            deadline = Math.max(now, nextRequestNanos);
            nextRequestNanos = deadline + minimumIntervalNanos;
        }
        long remaining = deadline - System.nanoTime();
        while (remaining > 0) {
            cancellation.throwIfCancelled();
            try {
                long sleepMillis = Math.max(1, Math.min(100, remaining / 1_000_000));
                Thread.sleep(sleepMillis);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new org.fzb.smolagents.core.ToolExecutionException(
                        "Interrupted while waiting for DuckDuckGo rate limit",
                        error
                );
            }
            remaining = deadline - System.nanoTime();
        }
    }
}
