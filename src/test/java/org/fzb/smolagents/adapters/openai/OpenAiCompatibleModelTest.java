package org.fzb.smolagents.adapters.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.GenerationOptions;
import org.fzb.smolagents.model.GenerationRequest;
import org.fzb.smolagents.model.MessageRole;
import org.fzb.smolagents.model.ModelCapabilities;
import org.fzb.smolagents.model.ModelStreamEvent;
import org.fzb.smolagents.model.TokenUsage;
import org.fzb.smolagents.tool.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用本地 HTTP 模拟服务验证 OpenAI 兼容请求及 SSE 响应映射。
 */
class OpenAiCompatibleModelTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
    private final AtomicBoolean blockSseResponse = new AtomicBoolean();
    private final CountDownLatch firstSseChunkSent = new CountDownLatch(1);
    private final CountDownLatch sendSseTail = new CountDownLatch(1);
    private final CountDownLatch clientDisconnected = new CountDownLatch(1);

    /**
     * 启动临时 HTTP 服务并捕获模型请求体。
     *
     * @throws IOException 本地端口无法监听时抛出
     */
    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                capturedRequest.set(MAPPER.readTree(exchange.getRequestBody()));
                if (blockSseResponse.get()) {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    OutputStream output = exchange.getResponseBody();
                    output.write("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                            .getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    firstSseChunkSent.countDown();
                    try {
                        sendSseTail.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    byte[] tail = ("data: {\"choices\":[{\"delta\":{\"content\":\""
                            + "x".repeat(4_096) + "\"}}]}\n\n").getBytes(StandardCharsets.UTF_8);
                    for (int index = 0; index < 500; index++) {
                        try {
                            output.write(tail);
                            output.flush();
                            Thread.sleep(10);
                        } catch (IOException error) {
                            clientDisconnected.countDown();
                            return;
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    return;
                }
                byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    /**
     * 释放本测试启动的本地 HTTP 服务。
     */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 验证工具调用、调用参数及 usage 均映射到核心数据类型。
     */
    @Test
    void mapsToolCallsAndUsageToCoreTypes() {
        responseBody.set("""
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call-1",
                        "type": "function",
                        "function": {"name": "echo", "arguments": "{\\"text\\":\\"hello\\"}"}
                      }]
                    }
                  }],
                  "usage": {"prompt_tokens": 7, "completion_tokens": 2}
                }
                """);
        OpenAiCompatibleModel model = model();

        ChatMessage response = model.generate(request());

        assertEquals(MessageRole.ASSISTANT, response.role());
        assertEquals("call-1", response.toolCalls().getFirst().id());
        assertEquals("hello", response.toolCalls().getFirst().arguments().get("text").asText());
        assertEquals(9, response.tokenUsage().totalTokens().orElseThrow());
        assertEquals("test-model", capturedRequest.get().path("model").asText());
        assertEquals("user", capturedRequest.get().path("messages").get(0).path("role").asText());
        assertEquals("echo", capturedRequest.get().path("tools").get(0).path("function").path("name").asText());
    }

    /**
     * 验证请求级停止序列映射到 OpenAI-compatible stop 参数。
     */
    @Test
    void mapsRequestStopSequences() {
        responseBody.set("""
                {"choices":[{"message":{"role":"assistant","content":"plan"}}]}
                """);
        GenerationRequest request = new GenerationRequest(
                List.of(ChatMessage.text(MessageRole.USER, "plan")),
                GenerationOptions.defaults(),
                List.of(),
                new CancellationToken(),
                List.of("<end_plan>")
        );

        model().generate(request);

        assertEquals("[\"<end_plan>\"]", capturedRequest.get().path("stop").toString());
    }

    /**
     * 验证 SSE 文本、工具调用和用量增量可聚合为完整消息。
     *
     * @throws InterruptedException 等待异步事件流时线程被中断
     */
    @Test
    void aggregatesSseMessageAndToolCallDeltas() throws InterruptedException {
        responseBody.set("""
                data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"hel"}}]}

                data: {"choices":[{"index":0,"delta":{"content":"lo"}}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-2","function":{"name":"echo","arguments":"{\\"text\\":\\"x\\"}"}}]}}]}

                data: {"choices":[],"usage":{"prompt_tokens":4,"completion_tokens":3}}

                data: [DONE]

                """);
        GenerationRequest request = new GenerationRequest(
                List.of(ChatMessage.text(MessageRole.USER, "test")),
                GenerationOptions.defaults(),
                List.of(),
                new CancellationToken()
        );
        List<ModelStreamEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        model().stream(request).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ModelStreamEvent event) {
                events.add(event);
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                completed.countDown();
            }

            @Override
            public void onComplete() {
                completed.countDown();
            }
        });

        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertNull(error.get());
        assertTrue(events.stream().anyMatch(ModelStreamEvent.MessageDelta.class::isInstance));
        ModelStreamEvent.Completed finalEvent = events.stream()
                .filter(ModelStreamEvent.Completed.class::isInstance)
                .map(ModelStreamEvent.Completed.class::cast)
                .findFirst()
                .orElseThrow();
        ContentPart.Text text = assertInstanceOf(ContentPart.Text.class, finalEvent.message().content().getFirst());
        assertEquals("hello", text.text());
        assertEquals("call-2", finalEvent.message().toolCalls().getFirst().id());
        assertEquals(7, finalEvent.message().tokenUsage().totalTokens().orElseThrow());
    }

    /**
     * 验证取消模型流订阅会关闭底层尚未结束的 HTTP SSE 响应。
     *
     * @throws InterruptedException 等待本机 SSE handler 时线程被中断
     */
    @Test
    void cancellationClosesAnActiveSseHttpResponse() throws InterruptedException {
        blockSseResponse.set(true);
        CancellationToken cancellation = new CancellationToken();
        GenerationRequest request = new GenerationRequest(
                List.of(ChatMessage.text(MessageRole.USER, "stream and cancel")),
                GenerationOptions.defaults(),
                List.of(),
                cancellation
        );
        AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();
        CountDownLatch subscriberCancelled = new CountDownLatch(1);
        model().stream(request).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriptionRef.set(subscription);
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ModelStreamEvent event) {
                if (event instanceof ModelStreamEvent.MessageDelta) {
                    subscriptionRef.get().cancel();
                    subscriberCancelled.countDown();
                    sendSseTail.countDown();
                }
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });

        assertTrue(firstSseChunkSent.await(5, TimeUnit.SECONDS));
        assertTrue(subscriberCancelled.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnected.await(5, TimeUnit.SECONDS));
        assertTrue(cancellation.isCancelled());
    }

    /**
     * 验证内嵌 WAV 音频映射到 OpenAI-compatible input_audio content。
     */
    @Test
    void mapsInlineAudioToInputAudioContent() {
        responseBody.set("""
                {"choices":[{"message":{"role":"assistant","content":"recognized"}}]}
                """);
        byte[] audioBytes = new byte[]{1, 2, 3, 4};
        GenerationRequest request = new GenerationRequest(
                List.of(new ChatMessage(
                        MessageRole.USER,
                        List.of(new ContentPart.Audio(null, "audio/wav", audioBytes, null)),
                        List.of(),
                        null,
                        null,
                        org.fzb.smolagents.model.TokenUsage.unknown()
                )),
                GenerationOptions.defaults(),
                List.of(),
                new CancellationToken()
        );

        ChatMessage response = model(true).generate(request);

        JsonNode audio = capturedRequest.get().path("messages").get(0).path("content").get(0);
        assertEquals("input_audio", audio.path("type").asText());
        assertEquals("AQIDBA==", audio.path("input_audio").path("data").asText());
        assertEquals("wav", audio.path("input_audio").path("format").asText());
        assertEquals("recognized", ((ContentPart.Text) response.content().getFirst()).text());
    }

    /**
     * 验证不支持的音频引用在发送请求前报告配置错误。
     */
    @Test
    void rejectsUriOnlyAudioForOpenAiCompatibleInput() {
        GenerationRequest request = new GenerationRequest(
                List.of(new ChatMessage(
                        MessageRole.USER,
                        List.of(new ContentPart.Audio(URI.create("https://example.com/audio.wav"), "audio/wav",
                                null, null)),
                        List.of(),
                        null,
                        null,
                        org.fzb.smolagents.model.TokenUsage.unknown()
                )),
                GenerationOptions.defaults(),
                List.of(),
                new CancellationToken()
        );

        ConfigurationException unsupported = assertThrows(
                ConfigurationException.class,
                () -> model().generate(request)
        );
        assertTrue(unsupported.getMessage().contains("not configured for audio"));

        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> model(true).generate(request)
        );
        assertTrue(failure.getMessage().contains("embedded audio bytes"));
    }

    /**
     * 验证模型未声明视觉能力时图像消息在发送前被拒绝。
     */
    @Test
    void rejectsImageWhenVisionCapabilityIsDisabled() {
        GenerationRequest request = new GenerationRequest(
                List.of(new ChatMessage(
                        MessageRole.USER,
                        List.of(new ContentPart.Image(
                                URI.create("https://example.com/image.png"),
                                "image/png",
                                null
                        )),
                        List.of(),
                        null,
                        null,
                        TokenUsage.unknown()
                )),
                GenerationOptions.defaults(),
                List.of(),
                new CancellationToken()
        );

        ConfigurationException failure = assertThrows(ConfigurationException.class, () -> model().generate(request));
        assertTrue(failure.getMessage().contains("image input"));
    }

    /**
     * 验证调用方可将核心消息角色转换为 Provider 特定角色名。
     */
    @Test
    void convertsConfiguredMessageRoles() {
        responseBody.set("""
                {"choices":[{"message":{"role":"assistant","content":"ok"}}]}
                """);
        OpenAiCompatibleModel model = new OpenAiCompatibleModel(
                "test-model",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                null,
                java.time.Duration.ofSeconds(5),
                new ModelCapabilities(true, false, false, true),
                Map.of(MessageRole.SYSTEM, "developer")
        );
        GenerationRequest request = new GenerationRequest(
                List.of(ChatMessage.text(MessageRole.SYSTEM, "system")),
                GenerationOptions.defaults(),
                List.of(),
                new CancellationToken()
        );

        model.generate(request);

        assertEquals("developer", capturedRequest.get().path("messages").get(0).path("role").asText());
    }

    /**
     * 创建指向当前测试 HTTP 服务的 OpenAI 兼容模型。
     *
     * @return 测试模型实例
     */
    private OpenAiCompatibleModel model() {
        return model(false);
    }

    /**
     * 创建指定音频能力的 OpenAI-compatible model。
     *
     * @param audio 是否声明支持内嵌音频输入
     * @return 本机测试模型
     */
    private OpenAiCompatibleModel model(boolean audio) {
        return model(audio, false);
    }

    /**
     * 创建带可选视觉/音频能力的 OpenAI-compatible model。
     *
     * @param audio 是否声明支持音频
     * @param vision 是否声明支持图像
     * @return 本机测试模型
     */
    private OpenAiCompatibleModel model(boolean audio, boolean vision) {
        return new OpenAiCompatibleModel(
                "test-model",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                null,
                java.time.Duration.ofSeconds(5),
                new ModelCapabilities(true, false, vision, true, audio)
        );
    }

    /**
     * 创建包含回显工具定义的基本生成请求。
     *
     * @return 测试生成请求
     */
    private static GenerationRequest request() {
        ToolSpec spec = new ToolSpec(
                "echo",
                "Return text.",
                MAPPER.createObjectNode().put("type", "object"),
                true
        );
        return new GenerationRequest(
                List.of(ChatMessage.text(MessageRole.USER, "echo hello")),
                GenerationOptions.defaults(),
                List.of(spec),
                new CancellationToken()
        );
    }
}
