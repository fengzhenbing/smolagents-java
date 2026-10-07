package org.fzb.smolagents.adapters.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.GenerationOptions;
import org.fzb.smolagents.model.GenerationRequest;
import org.fzb.smolagents.model.MessageRole;
import org.fzb.smolagents.model.ModelCapabilities;
import org.fzb.smolagents.model.ModelStreamEvent;
import org.fzb.smolagents.tool.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * 使用本地 HTTP 模拟服务验证 Ollama 原生聊天请求和 NDJSON 流。
 */
class OllamaModelTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
    private final AtomicBoolean blockStreamResponse = new AtomicBoolean();
    private final CountDownLatch firstStreamChunkSent = new CountDownLatch(1);
    private final CountDownLatch sendStreamTail = new CountDownLatch(1);
    private final CountDownLatch clientDisconnected = new CountDownLatch(1);
    private HttpServer server;

    /**
     * 启动临时 Ollama API 模拟服务并捕获请求体。
     *
     * @throws IOException 本地端口无法监听时抛出
     */
    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            try (exchange) {
                capturedRequest.set(MAPPER.readTree(exchange.getRequestBody()));
                if (blockStreamResponse.get()) {
                    exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
                    exchange.sendResponseHeaders(200, 0);
                    var output = exchange.getResponseBody();
                    output.write("""
                            {"message":{"role":"assistant","content":"partial"},"done":false}

                            """.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    firstStreamChunkSent.countDown();
                    try {
                        sendStreamTail.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    byte[] tail = """
                            {"message":{"role":"assistant","content":"tail"},"done":false}

                            """.getBytes(StandardCharsets.UTF_8);
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
                exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    /**
     * 停止当前测试启动的本地服务。
     */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 验证工具调用、token 用量及 Ollama 运行选项映射。
     */
    @Test
    void mapsNativeOllamaToolCallsUsageAndRuntimeOptions() {
        responseBody.set("""
                {"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"echo","arguments":{"text":"ping"}}}]},"prompt_eval_count":5,"eval_count":2,"done":true}
                """);
        OllamaModel model = model();

        ChatMessage response = model.generate(request(true));

        assertEquals("echo", response.toolCalls().getFirst().name());
        assertEquals("ping", response.toolCalls().getFirst().arguments().get("text").asText());
        assertTrue(response.toolCalls().getFirst().id().startsWith("ollama-"));
        assertEquals(7, response.tokenUsage().totalTokens().orElseThrow());
        assertEquals("gemma4:26b", capturedRequest.get().path("model").asText());
        assertEquals(false, capturedRequest.get().path("think").asBoolean());
        assertEquals(0, capturedRequest.get().path("options").path("num_gpu").asInt());
        assertEquals("echo", capturedRequest.get().path("tools").get(0).path("function").path("name").asText());
    }

    /**
     * 验证请求级停止序列映射到 Ollama 原生 options.stop。
     */
    @Test
    void mapsRequestStopSequencesToNativeOptions() {
        responseBody.set("""
                {"message":{"role":"assistant","content":"plan"},"done":true}
                """);
        GenerationRequest request = new GenerationRequest(
                List.of(ChatMessage.text(MessageRole.USER, "plan")),
                GenerationOptions.defaults(),
                List.of(),
                new CancellationToken(),
                List.of("<end_plan>")
        );

        model().generate(request);

        assertEquals("[\"<end_plan>\"]", capturedRequest.get().path("options").path("stop").toString());
    }

    /**
     * 验证 NDJSON 文本片段和 token 用量可生成完成事件。
     *
     * @throws InterruptedException 等待异步事件流时线程被中断
     */
    @Test
    void streamsNativeNdjsonContentAndUsage() throws InterruptedException {
        responseBody.set("""
                {"message":{"role":"assistant","content":"OK"},"done":false}
                {"message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":3,"eval_count":2}
                """);
        List<ModelStreamEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        model().stream(request(false)).subscribe(new Flow.Subscriber<>() {
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
        ModelStreamEvent.Completed finalEvent = events.stream()
                .filter(ModelStreamEvent.Completed.class::isInstance)
                .map(ModelStreamEvent.Completed.class::cast)
                .findFirst()
                .orElseThrow();
        ContentPart.Text text = assertInstanceOf(ContentPart.Text.class, finalEvent.message().content().getFirst());
        assertEquals("OK", text.text());
        assertEquals(5, finalEvent.message().tokenUsage().totalTokens().orElseThrow());
    }

    /**
     * 验证取消模型订阅会关闭活动的 Ollama NDJSON HTTP 响应。
     *
     * @throws InterruptedException 等待模型增量和本地 server 断连时线程被中断
     */
    @Test
    void cancellationClosesAnActiveNativeHttpStream() throws InterruptedException {
        blockStreamResponse.set(true);
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
                    sendStreamTail.countDown();
                }
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });

        assertTrue(firstStreamChunkSent.await(5, TimeUnit.SECONDS));
        assertTrue(subscriberCancelled.await(5, TimeUnit.SECONDS));
        assertTrue(clientDisconnected.await(5, TimeUnit.SECONDS));
        assertTrue(cancellation.isCancelled());
    }

    /**
     * 验证 Ollama 图像输入需显式声明 vision 能力，并映射为 base64 images 数组。
     */
    @Test
    void gatesImageInputOnConfiguredModelVisionCapability() {
        responseBody.set("""
                {"message":{"role":"assistant","content":"image received"},"done":true}
                """);
        GenerationRequest request = new GenerationRequest(
                List.of(new ChatMessage(
                        MessageRole.USER,
                        List.of(new ContentPart.Image(null, "image/png", new byte[]{1, 2, 3})),
                        List.of(),
                        null,
                        null
                )),
                GenerationOptions.defaults(),
                List.of(),
                new CancellationToken()
        );

        assertThrows(org.fzb.smolagents.core.ConfigurationException.class, () -> model().generate(request));
        ChatMessage response = model(true).generate(request);

        assertEquals("AQID", capturedRequest.get().path("messages").get(0).path("images").get(0).asText());
        assertEquals("image received", ((ContentPart.Text) response.content().getFirst()).text());
    }

    /**
     * 创建连接到当前本地模拟服务的 Ollama 模型。
     *
     * @return 测试用 Ollama 模型
     */
    private OllamaModel model() {
        return model(false);
    }

    /**
     * 创建显式配置 vision 能力的 Ollama 测试模型。
     *
     * @param vision 是否声明图像输入支持
     * @return 本机测试模型
     */
    private OllamaModel model(boolean vision) {
        return new OllamaModel(
                "gemma4:26b",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(5),
                Map.of("num_gpu", MAPPER.getNodeFactory().numberNode(0)),
                false,
                new ModelCapabilities(true, false, vision, true)
        );
    }

    /**
     * 创建可选是否带工具定义的测试生成请求。
     *
     * @param includeTool 是否包含 echo 工具
     * @return 测试生成请求
     */
    private static GenerationRequest request(boolean includeTool) {
        List<ToolSpec> tools = includeTool
                ? List.of(new ToolSpec(
                        "echo",
                        "Echo text.",
                        MAPPER.createObjectNode().put("type", "object"),
                        true
                ))
                : List.of();
        return new GenerationRequest(
                List.of(ChatMessage.text(MessageRole.USER, "Say ping.")),
                GenerationOptions.defaults(),
                tools,
                new CancellationToken()
        );
    }
}
