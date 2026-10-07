package org.fzb.smolagents.adapters;

import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ModelException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 为可重试的模型 HTTP 请求提供有界同步重试策略。
 *
 * <p>只重试网络 I/O、HTTP 408/429 和常见 5xx transient 状态，最多三次尝试；
 * schema、认证和其他 4xx 错误由 Provider 原样交给调用方处理。</p>
 */
public final class ModelHttpRetry {
    private static final int MAX_ATTEMPTS = 3;
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 429, 500, 502, 503, 504);

    private ModelHttpRetry() {
    }

    /**
     * 发送 JSON HTTP 请求，并对明确的 transient 错误执行有界退避重试。
     *
     * @param client 已配置 HTTP client
     * @param request 可重放的模型 HTTP request
     * @param cancellation 当前模型调用取消令牌
     * @return 最终 HTTP response；非 retryable 状态由 Provider 解释
     * @throws ModelException 请求多次 I/O 失败或线程被中断时抛出
     */
    public static HttpResponse<String> send(
            HttpClient client,
            HttpRequest request,
            CancellationToken cancellation
    ) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            cancellation.throwIfCancelled();
            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ModelException("model request was interrupted", error);
            } catch (IOException error) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new ModelException("model request failed after " + attempt + " attempts", error);
                }
                pauseBeforeRetry(attempt, cancellation, error);
                continue;
            }
            if (attempt == MAX_ATTEMPTS || !RETRYABLE_STATUS.contains(response.statusCode())) {
                return response;
            }
            pauseBeforeRetry(attempt, cancellation, null);
        }
        throw new IllegalStateException("unreachable retry loop");
    }

    /**
     * 按尝试次数使用短指数退避，并在等待后复核取消状态。
     *
     * @param attempt 当前已完成的尝试序号
     * @param cancellation 当前取消令牌
     * @param failure 最近的网络异常；有时用于诊断保留
     */
    private static void pauseBeforeRetry(int attempt, CancellationToken cancellation, IOException failure) {
        long delayMillis = Math.min(1_000, 100L << (attempt - 1));
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (failure != null) {
                interrupted.addSuppressed(failure);
            }
            throw new ModelException("interrupted while waiting to retry model request", interrupted);
        }
        cancellation.throwIfCancelled();
    }
}
