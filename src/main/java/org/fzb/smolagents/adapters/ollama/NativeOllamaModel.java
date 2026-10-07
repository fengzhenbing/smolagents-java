package org.fzb.smolagents.adapters.ollama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fzb.smolagents.adapters.ModelHttpRetry;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ModelException;
import org.fzb.smolagents.core.ModelOutputException;
import org.fzb.smolagents.core.SmolagentsException;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.GenerationOptions;
import org.fzb.smolagents.model.GenerationRequest;
import org.fzb.smolagents.model.MessageRole;
import org.fzb.smolagents.model.Model;
import org.fzb.smolagents.model.ModelCapabilities;
import org.fzb.smolagents.model.ModelStreamEvent;
import org.fzb.smolagents.model.TokenUsage;
import org.fzb.smolagents.model.ToolCall;
import org.fzb.smolagents.tool.ToolSpec;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 通过 Ollama 原生 {@code /api/chat} 接口执行模型生成和 NDJSON 流式生成。
 */
final class NativeOllamaModel implements Model {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ModelCapabilities DEFAULT_CAPABILITIES = new ModelCapabilities(true, false, false, true);
    private static final int MAX_ERROR_BODY_LENGTH = 4_096;

    private final String modelId;
    private final URI endpoint;
    private final Duration defaultTimeout;
    private final Map<String, JsonNode> options;
    private final Boolean think;
    private final ModelCapabilities capabilities;
    private final HttpClient client;

    /**
     * 创建 Ollama 原生 API 适配器。
     *
     * @param modelId Ollama 模型标识
     * @param baseUri Ollama 服务地址或 API 路径
     * @param defaultTimeout 默认请求超时
     * @param options Ollama 运行选项，例如 GPU 数量
     * @param think 是否启用模型思考模式；null 时使用服务端默认值
     */
    NativeOllamaModel(
            String modelId,
            URI baseUri,
            Duration defaultTimeout,
            Map<String, JsonNode> options,
            Boolean think
    ) {
        this(modelId, baseUri, defaultTimeout, options, think, DEFAULT_CAPABILITIES);
    }

    /**
     * 创建 Ollama 原生适配器并显式声明所选模型能力。
     *
     * @param modelId Ollama 模型标识
     * @param baseUri Ollama 服务地址或 API 路径
     * @param defaultTimeout 默认请求超时
     * @param options Ollama 运行选项
     * @param think 是否启用思考模式
     * @param capabilities 本模型声明的协议能力
     */
    NativeOllamaModel(
            String modelId,
            URI baseUri,
            Duration defaultTimeout,
            Map<String, JsonNode> options,
            Boolean think,
            ModelCapabilities capabilities
    ) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        this.modelId = modelId;
        this.endpoint = chatEndpoint(Objects.requireNonNull(baseUri, "baseUri"));
        if (defaultTimeout == null || defaultTimeout.isNegative() || defaultTimeout.isZero()) {
            throw new IllegalArgumentException("defaultTimeout must be positive");
        }
        this.defaultTimeout = defaultTimeout;
        Objects.requireNonNull(options, "options");
        this.options = options.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> Objects.requireNonNull(entry.getValue(), "option value").deepCopy()
        ));
        this.think = think;
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.audio()) {
            throw new ConfigurationException("Native Ollama adapter does not support audio input");
        }
        this.client = HttpClient.newBuilder().connectTimeout(defaultTimeout).build();
    }

    /**
     * 发送一次非流式原生聊天请求并解析 assistant 消息。
     *
     * @param request 核心生成请求
     * @return assistant 模型响应
     */
    @Override
    public ChatMessage generate(GenerationRequest request) {
        HttpRequest httpRequest = createRequest(request, false);
        HttpResponse<String> response = ModelHttpRetry.send(client, httpRequest, request.cancellation());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw httpError(response.statusCode(), response.body());
        }
        try {
            JsonNode body = MAPPER.readTree(response.body());
            if (body == null || !body.path("message").isObject()) {
                throw new ModelOutputException("Ollama response is missing message");
            }
            return parseMessage(body.path("message"), parseUsage(body));
        } catch (JsonProcessingException error) {
            throw new ModelOutputException("Ollama returned invalid JSON", error);
        }
    }

    /**
     * 创建仅支持一个订阅者的异步 NDJSON 模型事件流。
     *
     * @param request 核心生成请求
     * @return 模型响应增量事件流
     */
    @Override
    public Flow.Publisher<ModelStreamEvent> stream(GenerationRequest request) {
        Objects.requireNonNull(request, "request");
        AtomicBoolean subscribed = new AtomicBoolean();
        AtomicReference<CompletableFuture<?>> activeRequest = new AtomicReference<>();
        AtomicReference<Stream<String>> activeLines = new AtomicReference<>();
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            if (!subscribed.compareAndSet(false, true)) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                    }

                    /**
                     * 返回 Ollama 适配器声明的模型能力。
                     *
                     * @return 模型能力
                     */
                    @Override
                    public void cancel() {
                    }
                });
                subscriber.onError(new IllegalStateException("Ollama stream supports one subscriber"));
                return;
            }
            SubmissionPublisher<ModelStreamEvent> publisher = new SubmissionPublisher<>();
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(long n) {
                            subscription.request(n);
                        }

                        @Override
                        public void cancel() {
                            request.cancellation().cancel();
                            cancelStreamingRequest(activeRequest, activeLines);
                            subscription.cancel();
                        }
                    });
                }

                @Override
                public void onNext(ModelStreamEvent item) {
                    subscriber.onNext(item);
                }

                @Override
                public void onError(Throwable throwable) {
                    subscriber.onError(throwable);
                }

                @Override
                public void onComplete() {
                    subscriber.onComplete();
                }
            });
            CompletableFuture.runAsync(() -> {
                try {
                    streamResponse(request, publisher, activeRequest, activeLines);
                } catch (SmolagentsException error) {
                    publisher.submit(new ModelStreamEvent.Failed(error));
                } finally {
                    publisher.close();
                }
            });
        };
    }

    @Override
    public ModelCapabilities capabilities() {
        return capabilities;
    }

    /**
     * 构造含本次模型参数的 HTTP POST 请求。
     *
     * @param request 核心生成请求
     * @param streaming 是否请求流式响应
     * @return Ollama HTTP 请求
     */
    private HttpRequest createRequest(GenerationRequest request, boolean streaming) {
        Objects.requireNonNull(request, "request");
        request.cancellation().throwIfCancelled();
        ObjectNode body = createPayload(request, streaming);
        String encoded;
        try {
            encoded = MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException error) {
            throw new ModelException("failed to serialize Ollama request", error);
        }
        Duration timeout = request.options().timeout() == null ? defaultTimeout : request.options().timeout();
        return HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encoded))
                .build();
    }

    /**
     * 将核心消息、工具及参数转换为 Ollama 原生请求体。
     *
     * @param request 核心生成请求
     * @param streaming 是否启用 NDJSON 流式响应
     * @return Ollama 请求 JSON
     */
    private ObjectNode createPayload(GenerationRequest request, boolean streaming) {
        ObjectNode body = MAPPER.createObjectNode()
                .put("model", modelId)
                .put("stream", streaming);
        ArrayNode messages = body.putArray("messages");
        request.messages().forEach(message -> messages.add(toOllamaMessage(message)));
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            request.tools().stream().filter(ToolSpec::enabled).forEach(spec -> tools.add(toOllamaTool(spec)));
        }
        if (!options.isEmpty()) {
            ObjectNode requestOptions = body.putObject("options");
            options.forEach((key, value) -> requestOptions.set(key, value.deepCopy()));
        }
        GenerationOptions generationOptions = request.options();
        if (generationOptions.temperature() != null || generationOptions.maxTokens() != null) {
            ObjectNode requestOptions = (ObjectNode) body.with("options");
            if (generationOptions.temperature() != null) {
                requestOptions.put("temperature", generationOptions.temperature());
            }
            if (generationOptions.maxTokens() != null) {
                requestOptions.put("num_predict", generationOptions.maxTokens());
            }
        }
        if (!request.stopSequences().isEmpty()) {
            ArrayNode stop = MAPPER.createArrayNode();
            request.stopSequences().forEach(stop::add);
            ((ObjectNode) body.with("options")).set("stop", stop);
        }
        if (generationOptions.responseSchema() != null) {
            body.set("format", generationOptions.responseSchema());
        }
        if (think != null) {
            body.put("think", think);
        }
        return body;
    }

    /**
     * 将核心消息映射为 Ollama 原生消息格式。
     *
     * @param message 核心聊天消息
     * @return Ollama 消息 JSON
     */
    private ObjectNode toOllamaMessage(ChatMessage message) {
        ObjectNode json = MAPPER.createObjectNode()
                .put("role", message.role().name().toLowerCase(java.util.Locale.ROOT))
                .put("content", textContent(message.content()));
        if (message.name() != null) {
            json.put("name", message.name());
        }
        ArrayNode images = MAPPER.createArrayNode();
        for (ContentPart part : message.content()) {
            if (part instanceof ContentPart.Image image) {
                if (!capabilities.vision()) {
                    throw new ConfigurationException("Ollama model is not configured for image input");
                }
                if (image.data() == null) {
                    throw new ConfigurationException("Ollama image input requires binary image data");
                }
                images.add(Base64.getEncoder().encodeToString(image.data()));
            } else if (part instanceof ContentPart.Audio) {
                throw new ConfigurationException("Ollama chat adapter does not support audio content");
            }
        }
        if (!images.isEmpty()) {
            json.set("images", images);
        }
        if (!message.toolCalls().isEmpty()) {
            ArrayNode calls = json.putArray("tool_calls");
            message.toolCalls().forEach(call -> calls.add(MAPPER.createObjectNode()
                    .put("type", "function")
                    .set("function", MAPPER.createObjectNode()
                            .put("name", call.name())
                            .set("arguments", call.arguments()))));
        }
        return json;
    }

    /**
     * 合并消息中的文本和 JSON 内容为 Ollama content 文本字段。
     *
     * @param content 消息内容片段
     * @return 合并后的文本
     */
    private static String textContent(List<ContentPart> content) {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : content) {
            if (part instanceof ContentPart.Text value) {
                text.append(value.text());
            } else if (part instanceof ContentPart.Json value) {
                text.append(value.value());
            }
        }
        return text.toString();
    }

    /**
     * 将工具定义映射为 Ollama function tool 格式。
     *
     * @param spec 工具定义
     * @return Ollama 工具 JSON
     */
    private ObjectNode toOllamaTool(ToolSpec spec) {
        return MAPPER.createObjectNode()
                .put("type", "function")
                .set("function", MAPPER.createObjectNode()
                        .put("name", spec.name())
                        .put("description", spec.description())
                        .set("parameters", spec.inputSchema()));
    }

    /**
     * 解析 assistant 消息内容、工具调用及 token 用量。
     *
     * @param message Ollama message JSON
     * @param tokenUsage 响应用量
     * @return 核心 assistant 消息
     */
    private ChatMessage parseMessage(JsonNode message, TokenUsage tokenUsage) {
        String role = message.path("role").asText("assistant");
        if (!"assistant".equalsIgnoreCase(role)) {
            throw new ModelOutputException("Ollama response role must be assistant");
        }
        JsonNode contentNode = message.get("content");
        List<ContentPart> content = contentNode != null && contentNode.isTextual() && !contentNode.asText().isEmpty()
                ? List.of(new ContentPart.Text(contentNode.asText()))
                : List.of();
        List<ToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));
        return new ChatMessage(MessageRole.ASSISTANT, content, toolCalls, null, null, tokenUsage);
    }

    /**
     * 解析工具调用并为缺少 ID 的原生调用生成本地唯一标识。
     *
     * @param calls Ollama tool_calls 数组
     * @return 核心工具调用列表
     */
    private static List<ToolCall> parseToolCalls(JsonNode calls) {
        if (!calls.isArray()) {
            return List.of();
        }
        List<ToolCall> result = new ArrayList<>();
        for (JsonNode call : calls) {
            JsonNode function = call.path("function");
            String name = function.path("name").asText(null);
            JsonNode arguments = function.get("arguments");
            if (name == null || arguments == null) {
                throw new ModelOutputException("Ollama tool call is missing its function name or arguments");
            }
            JsonNode parsedArguments = arguments;
            if (arguments.isTextual()) {
                try {
                    parsedArguments = MAPPER.readTree(arguments.asText());
                } catch (JsonProcessingException error) {
                    throw new ModelOutputException("Ollama tool call arguments are invalid JSON", error);
                }
            }
            if (parsedArguments == null || !parsedArguments.isObject()) {
                throw new ModelOutputException("Ollama tool call arguments must be a JSON object");
            }
            String id = call.path("id").asText("ollama-" + UUID.randomUUID());
            result.add(new ToolCall(id, name, parsedArguments));
        }
        return List.copyOf(result);
    }

    /**
     * 执行 NDJSON 请求，逐行发布增量并最终发布完整消息。
     *
     * @param request 核心生成请求
     * @param publisher 模型事件发布器
     */
    private void streamResponse(
            GenerationRequest request,
            SubmissionPublisher<ModelStreamEvent> publisher,
            AtomicReference<CompletableFuture<?>> activeRequest,
            AtomicReference<Stream<String>> activeLines
    ) {
        CompletableFuture<HttpResponse<Stream<String>>> responseFuture = client.sendAsync(
                createRequest(request, true),
                HttpResponse.BodyHandlers.ofLines()
        );
        activeRequest.set(responseFuture);
        if (request.cancellation().isCancelled()) {
            responseFuture.cancel(true);
        }
        HttpResponse<Stream<String>> response;
        try {
            response = responseFuture.get();
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (request.cancellation().isCancelled()) {
                throw new ModelException("Ollama stream was cancelled", cause);
            }
            throw new ModelException("Ollama stream request failed", cause);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelException("Ollama stream was interrupted", error);
        } catch (java.util.concurrent.CancellationException error) {
            throw new ModelException("Ollama stream was cancelled", error);
        }
        Stream<String> lines = response.body();
        activeLines.set(lines);
        if (request.cancellation().isCancelled()) {
            lines.close();
            activeLines.compareAndSet(lines, null);
            activeRequest.compareAndSet(responseFuture, null);
            throw new ModelException("Ollama stream was cancelled");
        }
        try (lines) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = lines.limit(50).reduce("", (a, b) -> a + b + "\n");
                throw httpError(response.statusCode(), body);
            }
            // 步骤一：逐行解析 JSON 块，响应取消信号并累积消息片段。
            StreamAccumulator accumulator = new StreamAccumulator();
            for (String line : (Iterable<String>) lines::iterator) {
                if (request.cancellation().isCancelled()) {
                    throw new ModelException("Ollama stream was cancelled");
                }
                JsonNode chunk;
                try {
                    chunk = MAPPER.readTree(line);
                } catch (JsonProcessingException error) {
                    throw new ModelOutputException("Ollama stream returned invalid JSON", error);
                }
                accumulator.accept(chunk, publisher);
                if (chunk.path("done").asBoolean(false)) {
                    break;
                }
            }
            // 步骤二：流正常结束后发布合并后的完整 assistant 消息。
            publisher.submit(new ModelStreamEvent.Completed(accumulator.message()));
        } finally {
            activeLines.compareAndSet(lines, null);
            activeRequest.compareAndSet(responseFuture, null);
        }
    }

    /**
     * 取消 HTTP exchange 并关闭已创建的 NDJSON line stream。
     *
     * @param activeRequest 当前 HTTP request future
     * @param activeLines 当前 NDJSON line stream
     */
    private static void cancelStreamingRequest(
            AtomicReference<CompletableFuture<?>> activeRequest,
            AtomicReference<Stream<String>> activeLines
    ) {
        CompletableFuture<?> request = activeRequest.get();
        if (request != null) {
            request.cancel(true);
        }
        Stream<String> lines = activeLines.get();
        if (lines != null) {
            lines.close();
        }
    }

    /**
     * 从原生响应中提取 prompt 和 completion token 用量。
     *
     * @param response Ollama 响应 JSON
     * @return 已知或未知的 token 用量
     */
    private static TokenUsage parseUsage(JsonNode response) {
        JsonNode prompt = response.get("prompt_eval_count");
        JsonNode completion = response.get("eval_count");
        if (prompt == null || completion == null || !prompt.canConvertToLong() || !completion.canConvertToLong()) {
            return TokenUsage.unknown();
        }
        return TokenUsage.of(prompt.asLong(), completion.asLong());
    }

    /**
     * 将基础地址规范化为 Ollama 原生聊天接口 URI。
     *
     * @param baseUri 基础 URI
     * @return {@code /api/chat} 端点
     */
    private static URI chatEndpoint(URI baseUri) {
        String value = baseUri.toString().replaceAll("/+$", "");
        if (value.endsWith("/api/chat")) {
            return URI.create(value);
        }
        if (value.endsWith("/api")) {
            return URI.create(value + "/chat");
        }
        if (value.endsWith("/v1")) {
            value = value.substring(0, value.length() - 3);
        }
        return URI.create(value + "/api/chat");
    }

    /**
     * 将 HTTP 错误状态及受限长度响应体转换为模型异常。
     *
     * @param statusCode HTTP 状态码
     * @param body 响应体
     * @return 模型异常
     */
    private static ModelException httpError(int statusCode, String body) {
        String detail = body == null ? "" : body.substring(0, Math.min(body.length(), MAX_ERROR_BODY_LENGTH));
        return new ModelException("Ollama endpoint returned HTTP " + statusCode + ": " + detail);
    }

    /**
     * 聚合 Ollama 流中的文本、工具调用及最终 token 用量。
     */
    private static final class StreamAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, ToolCall> calls = new TreeMap<>();
        private TokenUsage tokenUsage = TokenUsage.unknown();

        /**
         * 消费一个 NDJSON 块，更新聚合消息并转发增量事件。
         *
         * @param chunk 当前 NDJSON JSON 对象
         * @param publisher 模型事件发布器
         */
        private void accept(JsonNode chunk, SubmissionPublisher<ModelStreamEvent> publisher) {
            JsonNode message = chunk.path("message");
            JsonNode text = message.get("content");
            if (text != null && text.isTextual() && !text.asText().isEmpty()) {
                content.append(text.asText());
                publisher.submit(new ModelStreamEvent.MessageDelta(text.asText()));
            }
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray()) {
                int index = 0;
                for (JsonNode call : toolCalls) {
                    List<ToolCall> parsed = parseToolCalls(MAPPER.createArrayNode().add(call));
                    ToolCall toolCall = parsed.getFirst();
                    calls.put(index, toolCall);
                    publisher.submit(new ModelStreamEvent.ToolCallDelta(
                            index,
                            toolCall.id(),
                            toolCall.name(),
                            toolCall.arguments().toString()
                    ));
                    index++;
                }
            }
            if (chunk.path("done").asBoolean(false)) {
                tokenUsage = parseUsage(chunk);
                publisher.submit(new ModelStreamEvent.Usage(tokenUsage));
            }
        }

        /**
         * 生成当前累积状态对应的 assistant 消息。
         *
         * @return 完整的核心聊天消息
         */
        private ChatMessage message() {
            List<ContentPart> parts = content.isEmpty()
                    ? List.of()
                    : List.of(new ContentPart.Text(content.toString()));
            return new ChatMessage(MessageRole.ASSISTANT, parts, List.copyOf(calls.values()),
                    null, null, tokenUsage);
        }
    }
}
