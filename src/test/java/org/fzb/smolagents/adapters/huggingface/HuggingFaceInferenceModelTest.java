package org.fzb.smolagents.adapters.huggingface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.GenerationOptions;
import org.fzb.smolagents.model.GenerationRequest;
import org.fzb.smolagents.model.MessageRole;
import org.fzb.smolagents.model.ModelCapabilities;
import org.fzb.smolagents.model.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 使用 loopback HTTP endpoint 验证 Hugging Face Inference Router adapter。
 */
class HuggingFaceInferenceModelTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    /**
     * 启动本机 Chat Completions endpoint stub。
     *
     * @throws IOException 无法绑定 loopback 端口时抛出
     */
    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                capturedRequest.set(MAPPER.readTree(exchange.getRequestBody()));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] response = """
                        {"choices":[{"message":{"role":"assistant","content":"hello from HF"}}],
                         "usage":{"prompt_tokens":5,"completion_tokens":3}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
    }

    /**
     * 停止本地 endpoint stub。
     */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 验证模型路由后缀、Bearer token、输入消息和返回 token 用量。
     */
    @Test
    void sendsProviderQualifiedChatRequestAndParsesCompletion() {
        HuggingFaceInferenceModel model = model(
                "org/example-model",
                "test-provider",
                "hf_test_token"
        );

        ChatMessage response = model.generate(request());

        assertEquals("org/example-model:test-provider", model.modelId());
        assertEquals("org/example-model:test-provider", capturedRequest.get().path("model").asText());
        assertEquals("user", capturedRequest.get().path("messages").get(0).path("role").asText());
        assertEquals("question", capturedRequest.get().path("messages").get(0).path("content").asText());
        assertEquals("Bearer hf_test_token", authorization.get());
        ContentPart.Text text = assertInstanceOf(ContentPart.Text.class, response.content().getFirst());
        assertEquals("hello from HF", text.text());
        assertEquals(8, response.tokenUsage().totalTokens().orElseThrow());
    }

    /**
     * 验证 token 是可选配置，且不传 provider 时模型 ID 保持原样。
     */
    @Test
    void allowsAnonymousCustomEndpointAndPreservesModelId() {
        HuggingFaceInferenceModel model = model("org/model", null, "");

        model.generate(request());

        assertEquals("org/model", model.modelId());
        assertNull(authorization.get());
    }

    /**
     * 验证模型能力声明由调用方配置并原样公开。
     */
    @Test
    void exposesConfiguredCapabilities() {
        ModelCapabilities capabilities = new ModelCapabilities(true, true, true, true);
        HuggingFaceInferenceModel model = new HuggingFaceInferenceModel(
                "org/model",
                null,
                "test-token",
                baseUrl(),
                Duration.ofSeconds(2),
                capabilities
        );

        assertEquals(capabilities, model.capabilities());
    }

    /**
     * 验证 Hugging Face adapter 可覆盖 endpoint 对 system role 的命名。
     */
    @Test
    void mapsSystemRoleForProviderSpecificEndpoints() {
        HuggingFaceInferenceModel model = new HuggingFaceInferenceModel(
                "org/model",
                null,
                "test-token",
                baseUrl(),
                Duration.ofSeconds(2),
                new ModelCapabilities(true, false, false, true),
                Map.of(MessageRole.SYSTEM, "developer")
        );
        GenerationRequest request = new GenerationRequest(
                List.of(ChatMessage.text(MessageRole.SYSTEM, "system prompt")),
                GenerationOptions.defaults(),
                List.of(),
                new CancellationToken()
        );

        model.generate(request);

        assertEquals("developer", capturedRequest.get().path("messages").get(0).path("role").asText());
    }

    /**
     * 创建测试模型实例。
     *
     * @param modelId 基础模型标识
     * @param provider 可选 provider 标识
     * @param token 可选 token
     * @return 指向本机服务的模型
     */
    private HuggingFaceInferenceModel model(String modelId, String provider, String token) {
        return new HuggingFaceInferenceModel(
                modelId,
                provider,
                token,
                baseUrl(),
                Duration.ofSeconds(2),
                new ModelCapabilities(true, false, false, true)
        );
    }

    /**
     * 创建指向本地服务的 OpenAI-compatible base URL。
     *
     * @return 本机服务地址
     */
    private URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    /**
     * 创建一个文本问答生成请求。
     *
     * @return 测试生成请求
     */
    private static GenerationRequest request() {
        return new GenerationRequest(
                List.of(ChatMessage.text(MessageRole.USER, "question")),
                GenerationOptions.defaults(),
                List.of(),
                new CancellationToken()
        );
    }
}
