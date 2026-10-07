package org.fzb.smolagents.adapters;

import com.sun.net.httpserver.HttpServer;
import org.fzb.smolagents.core.CancellationToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证模型 HTTP 传输层仅对 transient 错误执行有界重试。
 */
class ModelHttpRetryTest {
    /**
     * 验证第一次 503 后重试并返回成功响应。
     *
     * @throws Exception loopback service 或 HTTP 请求失败时抛出
     */
    @Test
    void retriesTransientStatusAndReturnsSuccess() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (StubServer server = new StubServer(() -> calls.incrementAndGet() == 1 ? 503 : 200)) {
            var response = send(server.endpoint());

            assertEquals(200, response.statusCode());
            assertEquals(2, calls.get());
        }
    }

    /**
     * 验证认证错误不触发无意义重试。
     *
     * @throws Exception loopback service 或 HTTP 请求失败时抛出
     */
    @Test
    void doesNotRetryNonTransientClientErrors() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (StubServer server = new StubServer(() -> {
            calls.incrementAndGet();
            return 401;
        })) {
            var response = send(server.endpoint());

            assertEquals(401, response.statusCode());
            assertEquals(1, calls.get());
        }
    }

    /**
     * 验证连续 transient 错误达到最多三次后停止并返回最后一个状态。
     *
     * @throws Exception loopback service 或 HTTP 请求失败时抛出
     */
    @Test
    void stopsAfterConfiguredMaximumAttempts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (StubServer server = new StubServer(() -> {
            calls.incrementAndGet();
            return 503;
        })) {
            var response = send(server.endpoint());

            assertEquals(503, response.statusCode());
            assertEquals(3, calls.get());
        }
    }

    /**
     * 发送可重放的 GET 请求到指定 loopback endpoint。
     *
     * @param endpoint server URL
     * @return provider response
     */
    private static java.net.http.HttpResponse<String> send(URI endpoint) {
        HttpRequest request = HttpRequest.newBuilder(endpoint).GET().build();
        return ModelHttpRetry.send(HttpClient.newHttpClient(), request, new CancellationToken());
    }

    /**
     * 提供可配置 HTTP 状态码的本机 endpoint。
     */
    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;

        /**
         * 启动 loopback HTTP server。
         *
         * @param statusCode 当前请求应返回的状态码
         * @throws IOException 无法监听 loopback 端口时抛出
         */
        private StubServer(java.util.function.IntSupplier statusCode) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    byte[] body = "response".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(statusCode.getAsInt(), body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
        }

        /**
         * 返回本机 endpoint。
         *
         * @return URI
         */
        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        /**
         * 停止本地 HTTP 服务。
         */
        @Override
        public void close() {
            server.stop(0);
        }
    }
}
