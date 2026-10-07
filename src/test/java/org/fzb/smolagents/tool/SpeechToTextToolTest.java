package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.model.ContentPart;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 loopback transcription endpoint 验证音频上传、凭据和错误映射。
 */
class SpeechToTextToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证 multipart 音频上传、语言提示、认证 header 和转录结果映射。
     *
     * @throws Exception 本地 HTTP server 或 JSON 参数处理失败时抛出
     */
    @Test
    void uploadsAudioAndReturnsTranscript() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (StubServer server = new StubServer(200, "{\"text\":\"recognized words\"}",
                authorization, contentType, body)) {
            SpeechToTextTool tool = tool(server.endpoint(), "local-test-key");
            ToolResult result = tool.execute(
                    arguments("audio/wav", Base64.getEncoder().encodeToString("RIFFaudio".getBytes(
                            StandardCharsets.UTF_8
                    )), "en"),
                    context("transcription")
            );

            assertEquals("recognized words", text(result));
            assertEquals("Bearer local-test-key", authorization.get());
            assertTrue(contentType.get().startsWith("multipart/form-data; boundary="));
            assertTrue(body.get().contains("name=\"model\""));
            assertTrue(body.get().contains("name=\"language\""));
            assertTrue(body.get().contains("name=\"file\"; filename=\"audio.wav\""));
            assertTrue(body.get().contains("whisper-1"));
            assertFalse(body.get().contains("recognized words"));
            assertTrue(body.get().contains("RIFFaudio"));
        }
    }

    /**
     * 验证无效音频参数和缺失 API key 会在请求前显式失败。
     *
     * @throws Exception 测试 JSON 参数处理失败时抛出
     */
    @Test
    void rejectsMalformedAudioAndMissingApiKey() throws Exception {
        try (StubServer server = new StubServer(200, "{\"text\":\"unused\"}", null, null, null)) {
            SpeechToTextTool tool = tool(server.endpoint(), "test-key");
            ToolExecutionException invalidAudio = assertThrows(
                    ToolExecutionException.class,
                    () -> tool.execute(arguments("audio/wav", "not-base64!", null), context("bad-audio"))
            );
            assertTrue(invalidAudio.getMessage().contains("base64"));
        }
        assertThrows(
                ConfigurationException.class,
                () -> tool(URI.create("http://127.0.0.1:1/transcriptions"), "")
        );
    }

    /**
     * 验证服务端错误状态和缺少转录文本不会返回成功形状结果。
     *
     * @throws Exception 本地 HTTP server 或 JSON 参数处理失败时抛出
     */
    @Test
    void surfacesHttpAndMalformedResponseErrors() throws Exception {
        String audio = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        try (StubServer server = new StubServer(401, "{\"error\":\"unauthorized\"}", null, null, null)) {
            ToolExecutionException failure = assertThrows(
                    ToolExecutionException.class,
                    () -> tool(server.endpoint(), "test-key")
                            .execute(arguments("audio/wav", audio, null), context("http-error"))
            );
            assertTrue(failure.getMessage().contains("HTTP 401"));
        }
        try (StubServer server = new StubServer(200, "{\"duration\":1.0}", null, null, null)) {
            ToolExecutionException malformed = assertThrows(
                    ToolExecutionException.class,
                    () -> tool(server.endpoint(), "test-key")
                            .execute(arguments("audio/wav", audio, null), context("missing-text"))
            );
            assertTrue(malformed.getMessage().contains("missing textual"));
        }
    }

    /**
     * 创建本地 API 配置的转录工具。
     *
     * @param endpoint loopback transcription endpoint
     * @param apiKey 测试凭据
     * @return 转录工具
     */
    private static SpeechToTextTool tool(URI endpoint, String apiKey) {
        return new SpeechToTextTool(endpoint, apiKey, "whisper-1", Duration.ofSeconds(2), 1024);
    }

    /**
     * 创建具有指定音频字段的 JSON 参数。
     *
     * @param mimeType 音频 MIME type
     * @param data base64 字节
     * @param language 可选语言代码
     * @return 工具参数对象
     */
    private static com.fasterxml.jackson.databind.JsonNode arguments(String mimeType, String data, String language) {
        var audio = MAPPER.createObjectNode()
                .put("data", data)
                .put("mimeType", mimeType);
        var arguments = MAPPER.createObjectNode();
        arguments.set("audio", audio);
        if (language != null) {
            arguments.put("language", language);
        }
        return arguments;
    }

    /**
     * 为单次工具调用创建独立上下文。
     *
     * @param callId 调用 ID
     * @return 工具调用上下文
     */
    private static ToolExecutionContext context(String callId) {
        return new ToolExecutionContext(UUID.randomUUID(), callId, new CancellationToken());
    }

    /**
     * 提取纯文本工具结果。
     *
     * @param result 工具结果
     * @return transcription 字符串
     */
    private static String text(ToolResult result) {
        return assertInstanceOf(ContentPart.Text.class, result.content().getFirst()).text();
    }

    /**
     * 提供固定状态码和 JSON 响应的本地 endpoint。
     */
    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;

        /**
         * 启动本机 transcription API stub。
         *
         * @param status 固定 HTTP 状态码
         * @param response 固定 JSON response
         * @param authorization 接收 Authorization header 的可选引用
         * @param contentType 接收 Content-Type header 的可选引用
         * @param body 接收 multipart body 的可选引用
         * @throws IOException 无法绑定 loopback 端口时抛出
         */
        private StubServer(
                int status,
                String response,
                AtomicReference<String> authorization,
                AtomicReference<String> contentType,
                AtomicReference<String> body
        ) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/transcriptions", exchange -> {
                try (exchange) {
                    if (authorization != null) {
                        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    }
                    if (contentType != null) {
                        contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                    }
                    if (body != null) {
                        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
                    } else {
                        exchange.getRequestBody().readAllBytes();
                    }
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }

        /**
         * 返回本地 transcription endpoint。
         *
         * @return loopback URI
         */
        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/transcriptions");
        }

        /**
         * 停止本地服务。
         */
        @Override
        public void close() {
            server.stop(0);
        }
    }
}
