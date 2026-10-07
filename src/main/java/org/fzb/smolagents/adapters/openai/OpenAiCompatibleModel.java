package org.fzb.smolagents.adapters.openai;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 适配 OpenAI Chat Completions API 及兼容服务的模型实现。
 */
public class OpenAiCompatibleModel implements Model {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ERROR_BODY_LENGTH = 4_096;

    private final String modelId;
    private final URI endpoint;
    private final String apiKey;
    private final Duration defaultTimeout;
    private final ModelCapabilities capabilities;
    private final Map<MessageRole, String> roleConversions;
    private final HttpClient client;

    /**
     * 创建 OpenAI 兼容模型客户端。
     *
     * @param modelId 服务端使用的模型名称
     * @param baseUri 服务基础 URI，可包含版本或 chat completions 路径
     * @param apiKey 可选 API 密钥
     * @param defaultTimeout 默认请求超时
     * @param capabilities 模型支持能力声明
     */
    public OpenAiCompatibleModel(
            String modelId,
            URI baseUri,
            String apiKey,
            Duration defaultTimeout,
            ModelCapabilities capabilities
    ) {
        this(modelId, baseUri, apiKey, defaultTimeout, capabilities, Map.of());
    }

    /**
     * 创建 OpenAI 兼容模型客户端并可覆盖请求消息角色名。
     *
     * @param modelId 服务端使用的模型名称
     * @param baseUri 服务基础 URI，可包含版本或 chat completions 路径
     * @param apiKey 可选 API 密钥
     * @param defaultTimeout 默认请求超时
     * @param capabilities 模型支持能力声明
     * @param roleConversions 核心消息角色到 endpoint role 字符串的转换表
     */
    public OpenAiCompatibleModel(
            String modelId,
            URI baseUri,
            String apiKey,
            Duration defaultTimeout,
            ModelCapabilities capabilities,
            Map<MessageRole, String> roleConversions
    ) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        this.modelId = modelId;
        this.endpoint = chatCompletionsEndpoint(Objects.requireNonNull(baseUri, "baseUri"));
        this.apiKey = apiKey;
        if (defaultTimeout == null || defaultTimeout.isNegative() || defaultTimeout.isZero()) {
            throw new IllegalArgumentException("defaultTimeout must be positive");
        }
        this.defaultTimeout = defaultTimeout;
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.roleConversions = validateRoleConversions(roleConversions);
        this.client = HttpClient.newBuilder().connectTimeout(defaultTimeout).build();
    }

    /**
     * 返回服务端模型名称。
     *
     * @return 模型名称
     */
    public String modelId() {
        return modelId;
    }

    /**
     * 发送一次非流式聊天补全请求并解析模型响应。
     *
     * @param request 消息、生成参数、工具和取消令牌
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
            return parseResponse(MAPPER.readTree(response.body()));
        } catch (JsonProcessingException error) {
            throw new ModelOutputException("model returned invalid JSON", error);
        }
    }

    /**
     * 创建单订阅者模型事件流，并异步读取服务端 SSE 响应。
     *
     * @param request 消息、生成参数、工具和取消令牌
     * @return 增量模型响应事件流
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
                     * 返回构造客户端时声明的模型能力。
                     *
                     * @return 模型能力
                     */
                    @Override
                    public void cancel() {
                    }
                });
                subscriber.onError(new IllegalStateException("model stream supports one subscriber"));
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
     * 检查请求是否符合已声明能力，并构造带鉴权头的 HTTP 请求。
     *
     * @param request 生成请求
     * @param streaming 是否使用流式响应
     * @return 已序列化的 HTTP 请求
     */
    private HttpRequest createRequest(GenerationRequest request, boolean streaming) {
        Objects.requireNonNull(request, "request");
        if (!capabilities.toolCalling() && !request.tools().isEmpty()) {
            throw new ConfigurationException("model is not configured for tool calling");
        }
        if (!capabilities.structuredOutput() && request.options().responseSchema() != null) {
            throw new ConfigurationException("model is not configured for structured output");
        }
        validateMediaCapabilities(request);
        request.cancellation().throwIfCancelled();
        Duration timeout = request.options().timeout() == null
                ? defaultTimeout
                : request.options().timeout();
        ObjectNode body = createPayload(request, streaming);
        String encoded;
        try {
            encoded = MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException error) {
            throw new ModelException("failed to serialize model request", error);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encoded));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    /**
     * 拒绝请求中超出模型能力声明的图像或音频输入。
     *
     * @param request 完整生成请求
     */
    private void validateMediaCapabilities(GenerationRequest request) {
        for (ChatMessage message : request.messages()) {
            for (ContentPart part : message.content()) {
                if (part instanceof ContentPart.Image && !capabilities.vision()) {
                    throw new ConfigurationException("model is not configured for image input");
                }
                if (part instanceof ContentPart.Audio && !capabilities.audio()) {
                    throw new ConfigurationException("model is not configured for audio input");
                }
            }
        }
    }

    /**
     * 将核心消息和生成选项映射为 Chat Completions 请求体。
     *
     * @param request 生成请求
     * @param streaming 是否请求 SSE 流
     * @return OpenAI 格式请求 JSON
     */
    private ObjectNode createPayload(GenerationRequest request, boolean streaming) {
        ObjectNode body = MAPPER.createObjectNode()
                .put("model", modelId)
                .put("stream", streaming);
        ArrayNode messages = body.putArray("messages");
        request.messages().forEach(message -> messages.add(toOpenAiMessage(message)));

        GenerationOptions options = request.options();
        if (options.temperature() != null) {
            body.put("temperature", options.temperature());
        }
        if (options.maxTokens() != null) {
            body.put("max_tokens", options.maxTokens());
        }
        if (!request.stopSequences().isEmpty()) {
            body.set("stop", MAPPER.valueToTree(request.stopSequences()));
        }
        if (options.responseSchema() != null) {
            body.set("response_format", MAPPER.createObjectNode()
                    .put("type", "json_schema")
                    .set("json_schema", MAPPER.createObjectNode()
                            .put("name", "response")
                            .set("schema", options.responseSchema())));
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            request.tools().stream().filter(ToolSpec::enabled).forEach(spec -> tools.add(toOpenAiTool(spec)));
            body.put("tool_choice", "auto");
        }
        return body;
    }

    /**
     * 将一条核心聊天消息映射为 OpenAI 消息格式。
     *
     * @param message 核心聊天消息
     * @return OpenAI 消息 JSON
     */
    private ObjectNode toOpenAiMessage(ChatMessage message) {
        ObjectNode json = MAPPER.createObjectNode()
                .put("role", roleConversions.getOrDefault(
                        message.role(),
                        message.role().name().toLowerCase(java.util.Locale.ROOT)
                ));
        if (message.role() == MessageRole.TOOL) {
            json.put("tool_call_id", message.toolCallId());
            if (message.name() != null) {
                json.put("name", message.name());
            }
        }
        json.set("content", toOpenAiContent(message.content()));
        if (!message.toolCalls().isEmpty()) {
            ArrayNode toolCalls = json.putArray("tool_calls");
            message.toolCalls().forEach(call -> {
                ObjectNode function = MAPPER.createObjectNode()
                        .put("name", call.name())
                        .put("arguments", call.arguments().toString());
                toolCalls.add(MAPPER.createObjectNode()
                        .put("id", call.id())
                        .put("type", "function")
                        .set("function", function));
            });
        }
        return json;
    }

    /**
     * 将文本、多模态内容片段转换为 OpenAI content 字段格式。
     *
     * @param parts 消息内容片段
     * @return 文本或多模态 JSON 内容
     */
    private JsonNode toOpenAiContent(List<ContentPart> parts) {
        if (parts.isEmpty()) {
            return MAPPER.nullNode();
        }
        if (parts.size() == 1 && parts.getFirst() instanceof ContentPart.Text text) {
            return MAPPER.getNodeFactory().textNode(text.text());
        }
        ArrayNode content = MAPPER.createArrayNode();
        for (ContentPart part : parts) {
            if (part instanceof ContentPart.Text text) {
                content.add(MAPPER.createObjectNode().put("type", "text").put("text", text.text()));
            } else if (part instanceof ContentPart.Json json) {
                content.add(MAPPER.createObjectNode().put("type", "text").put("text", json.value().toString()));
            } else if (part instanceof ContentPart.Image image) {
                String url = image.uri() != null
                        ? image.uri().toString()
                        : "data:" + image.mimeType() + ";base64," + Base64.getEncoder().encodeToString(image.data());
                content.add(MAPPER.createObjectNode()
                        .put("type", "image_url")
                        .set("image_url", MAPPER.createObjectNode().put("url", url)));
            } else if (part instanceof ContentPart.Audio audio) {
                byte[] data = audio.data();
                if (data == null) {
                    throw new ConfigurationException(
                            "OpenAI-compatible audio input requires embedded audio bytes; URI-only audio is unsupported"
                    );
                }
                String format = audioFormat(audio.mimeType());
                content.add(MAPPER.createObjectNode()
                        .put("type", "input_audio")
                        .set("input_audio", MAPPER.createObjectNode()
                                .put("data", Base64.getEncoder().encodeToString(data))
                                .put("format", format)));
            } else {
                throw new ConfigurationException("unsupported content part for the OpenAI-compatible adapter");
            }
        }
        return content;
    }

    /**
     * 将音频 MIME type 映射为 Chat Completions input_audio 格式名。
     *
     * @param mimeType audio content MIME type
     * @return provider 格式标识
     */
    private static String audioFormat(String mimeType) {
        return switch (mimeType.toLowerCase(java.util.Locale.ROOT)) {
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/mp4", "audio/m4a" -> "mp4";
            case "audio/webm" -> "webm";
            default -> throw new ConfigurationException(
                    "Unsupported OpenAI-compatible audio MIME type: " + mimeType
            );
        };
    }

    /**
     * 将工具定义映射为 OpenAI function tool 格式。
     *
     * @param spec 工具定义
     * @return OpenAI tool JSON
     */
    private ObjectNode toOpenAiTool(ToolSpec spec) {
        ObjectNode function = MAPPER.createObjectNode()
                .put("name", spec.name())
                .put("description", spec.description())
                .set("parameters", spec.inputSchema());
        return MAPPER.createObjectNode().put("type", "function").set("function", function);
    }

    /**
     * 解析 choices[0] 中的响应消息、工具调用及 token 用量。
     *
     * @param response 服务端响应 JSON
     * @return 核心模型消息
     */
    private ChatMessage parseResponse(JsonNode response) {
        JsonNode message = response.path("choices").path(0).path("message");
        if (message.isMissingNode() || !message.isObject()) {
            throw new ModelOutputException("model response is missing choices[0].message");
        }
        MessageRole role = parseRole(message.path("role").asText(null));
        List<ContentPart> content = parseContent(message.get("content"));
        List<ToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));
        return new ChatMessage(role, content, toolCalls, null, null, parseUsage(response.path("usage")));
    }

    /**
     * 解析并校验响应中的工具调用。
     *
     * @param calls 服务端 tool_calls 字段
     * @return 核心工具调用列表
     */
    private List<ToolCall> parseToolCalls(JsonNode calls) {
        if (calls == null || !calls.isArray()) {
            return List.of();
        }
        List<ToolCall> result = new ArrayList<>();
        for (JsonNode call : calls) {
            String id = call.path("id").asText(null);
            String name = call.path("function").path("name").asText(null);
            JsonNode arguments = call.path("function").get("arguments");
            if (arguments == null) {
                throw new ModelOutputException("tool call is missing function.arguments");
            }
            JsonNode parsedArguments = arguments;
            if (arguments.isTextual()) {
                try {
                    parsedArguments = MAPPER.readTree(arguments.asText());
                } catch (JsonProcessingException error) {
                    throw new ModelOutputException("tool call arguments are invalid JSON", error);
                }
            }
            if (id == null || name == null || !parsedArguments.isObject()) {
                throw new ModelOutputException("tool call requires an id, function name, and JSON object arguments");
            }
            result.add(new ToolCall(id, name, parsedArguments));
        }
        return List.copyOf(result);
    }

    /**
     * 解析模型响应中的文本内容片段。
     *
     * @param content 服务端 content 字段
     * @return 核心内容片段
     */
    private List<ContentPart> parseContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return List.of();
        }
        if (content.isTextual()) {
            return content.asText().isEmpty() ? List.of() : List.of(new ContentPart.Text(content.asText()));
        }
        if (!content.isArray()) {
            throw new ModelOutputException("message content must be text, an array, or null");
        }
        List<ContentPart> result = new ArrayList<>();
        for (JsonNode item : content) {
            if ("text".equals(item.path("type").asText())) {
                result.add(new ContentPart.Text(item.path("text").asText()));
            } else {
                throw new ModelOutputException("unsupported response content type: " + item.path("type").asText());
            }
        }
        return List.copyOf(result);
    }

    /**
     * 将服务端角色名转换为核心消息角色。
     *
     * @param role 服务端角色名
     * @return 核心消息角色
     */
    private static MessageRole parseRole(String role) {
        if (role == null) {
            throw new ModelOutputException("model response is missing message.role");
        }
        return switch (role.toLowerCase(java.util.Locale.ROOT)) {
            case "assistant" -> MessageRole.ASSISTANT;
            case "user" -> MessageRole.USER;
            case "system" -> MessageRole.SYSTEM;
            case "tool" -> MessageRole.TOOL;
            default -> throw new ModelOutputException("unsupported message role: " + role);
        };
    }

    /**
     * 读取 usage 对象；字段不完整时返回未知用量。
     *
     * @param usage 服务端用量 JSON
     * @return token 用量
     */
    private static TokenUsage parseUsage(JsonNode usage) {
        JsonNode prompt = usage.get("prompt_tokens");
        JsonNode completion = usage.get("completion_tokens");
        if (prompt == null || completion == null || !prompt.canConvertToLong() || !completion.canConvertToLong()) {
            return TokenUsage.unknown();
        }
        return TokenUsage.of(prompt.asLong(), completion.asLong());
    }

    /**
     * 执行 HTTP SSE 请求、逐行解析数据并发布完成或失败事件。
     *
     * @param request 生成请求
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
                throw new ModelException("model stream was cancelled", cause);
            }
            throw new ModelException("model stream request failed", cause);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelException("model stream was interrupted", error);
        } catch (java.util.concurrent.CancellationException error) {
            throw new ModelException("model stream was cancelled", error);
        }
        Stream<String> lines = response.body();
        activeLines.set(lines);
        if (request.cancellation().isCancelled()) {
            lines.close();
            activeLines.compareAndSet(lines, null);
            activeRequest.compareAndSet(responseFuture, null);
            throw new ModelException("model stream was cancelled");
        }
        try (lines) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw httpError(response.statusCode(), lines.limit(50).reduce("", (a, b) -> a + b + "\n"));
            }
            // 步骤一：过滤 SSE 数据行和结束标记，并将每个 JSON 块交给累积器。
            StreamAccumulator accumulator = new StreamAccumulator();
            for (String line : (Iterable<String>) lines::iterator) {
                request.cancellation().throwIfCancelled();
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode chunk;
                try {
                    chunk = MAPPER.readTree(data);
                } catch (JsonProcessingException error) {
                    throw new ModelOutputException("model stream returned invalid JSON", error);
                }
                accumulator.accept(chunk, publisher);
            }
            // 步骤二：流结束后发布完整消息；过程增量已在消费时同步发布。
            publisher.submit(new ModelStreamEvent.Completed(accumulator.message()));
        } finally {
            activeLines.compareAndSet(lines, null);
            activeRequest.compareAndSet(responseFuture, null);
        }
    }

    /**
     * 取消 HTTP exchange 并关闭已创建的 SSE line stream。
     *
     * @param activeRequest 当前 HTTP request future
     * @param activeLines 当前 SSE line stream
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
     * 将 HTTP 错误状态和受限长度响应体转换为模型异常。
     *
     * @param statusCode HTTP 状态码
     * @param body 响应正文
     * @return 可抛出的模型异常
     */
    private ModelException httpError(int statusCode, String body) {
        String detail = body == null ? "" : body.substring(0, Math.min(body.length(), MAX_ERROR_BODY_LENGTH));
        return new ModelException("model endpoint returned HTTP " + statusCode + ": " + detail);
    }

    private static URI chatCompletionsEndpoint(URI baseUri) {
        String value = baseUri.toString().replaceAll("/+$", "");
        if (value.endsWith("/chat/completions")) {
            return URI.create(value);
        }
        if (value.endsWith("/v1")) {
            return URI.create(value + "/chat/completions");
        }
        return URI.create(value + "/v1/chat/completions");
    }

    /**
     * 校验并复制 role 转换，避免模型请求中出现空角色。
     *
     * @param conversions 调用方角色映射
     * @return immutable role map
     */
    private static Map<MessageRole, String> validateRoleConversions(Map<MessageRole, String> conversions) {
        Objects.requireNonNull(conversions, "roleConversions");
        Map<MessageRole, String> result = new java.util.EnumMap<>(MessageRole.class);
        conversions.forEach((role, converted) -> {
            if (role == null || converted == null || converted.isBlank()) {
                throw new ConfigurationException("roleConversions must not contain null or blank values");
            }
            result.put(role, converted);
        });
        return Map.copyOf(result);
    }

    /**
     * 将多个 SSE delta 聚合为完整消息，并同时转发增量事件。
     */
    private static final class StreamAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, ToolCallAccumulator> toolCalls = new TreeMap<>();
        private TokenUsage tokenUsage = TokenUsage.unknown();

        /**
         * 消费一个 SSE JSON 块并更新消息内容、工具调用和用量。
         *
         * @param chunk 当前响应块
         * @param publisher 模型事件发布器
         */
        private void accept(JsonNode chunk, SubmissionPublisher<ModelStreamEvent> publisher) {
            JsonNode usage = chunk.get("usage");
            if (usage != null && usage.isObject()) {
                tokenUsage = parseUsage(usage);
                publisher.submit(new ModelStreamEvent.Usage(tokenUsage));
            }
            JsonNode choices = chunk.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return;
            }
            JsonNode delta = choices.get(0).path("delta");
            JsonNode text = delta.get("content");
            if (text != null && text.isTextual() && !text.asText().isEmpty()) {
                content.append(text.asText());
                publisher.submit(new ModelStreamEvent.MessageDelta(text.asText()));
            }
            JsonNode calls = delta.path("tool_calls");
            if (calls.isArray()) {
                for (JsonNode call : calls) {
                    int index = call.path("index").asInt(-1);
                    if (index < 0) {
                        throw new ModelOutputException("stream tool call is missing an index");
                    }
                    ToolCallAccumulator accumulator = toolCalls.computeIfAbsent(index, ignored -> new ToolCallAccumulator());
                    JsonNode function = call.path("function");
                    String id = textOrNull(call.get("id"));
                    String name = textOrNull(function.get("name"));
                    String arguments = textOrEmpty(function.get("arguments"));
                    accumulator.append(id, name, arguments);
                    publisher.submit(new ModelStreamEvent.ToolCallDelta(index, id, name, arguments));
                }
            }
        }

        /**
         * 构建当前已聚合的完整 assistant 消息。
         *
         * @return 聚合后的消息
         */
        private ChatMessage message() {
            List<ToolCall> calls = toolCalls.values().stream().map(ToolCallAccumulator::toToolCall).toList();
            List<ContentPart> parts = content.isEmpty()
                    ? List.of()
                    : List.of(new ContentPart.Text(content.toString()));
            return new ChatMessage(MessageRole.ASSISTANT, parts, calls, null, null, tokenUsage);
        }
    }

    /**
     * 在多个 SSE 块之间增量收集单个工具调用字段。
     */
    private static final class ToolCallAccumulator {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        /**
         * 合并工具调用的标识、名称和参数片段。
         *
         * @param idDelta 工具调用 ID 增量
         * @param nameDelta 工具名称增量
         * @param argumentsDelta JSON 参数增量
         */
        private void append(String idDelta, String nameDelta, String argumentsDelta) {
            if (idDelta != null) {
                id = idDelta;
            }
            if (nameDelta != null) {
                name = nameDelta;
            }
            arguments.append(argumentsDelta);
        }

        /**
         * 校验收集结果并转换为核心工具调用。
         *
         * @return 完整工具调用
         */
        private ToolCall toToolCall() {
            if (id == null || name == null) {
                throw new ModelOutputException("streamed tool call is missing its id or name");
            }
            try {
                JsonNode parsed = arguments.isEmpty()
                        ? MAPPER.createObjectNode()
                        : MAPPER.readTree(arguments.toString());
                if (parsed == null || !parsed.isObject()) {
                    throw new ModelOutputException("streamed tool call arguments must be a JSON object");
                }
                return new ToolCall(id, name, parsed);
            } catch (JsonProcessingException error) {
                throw new ModelOutputException("streamed tool call arguments are invalid JSON", error);
            }
        }
    }

    /**
     * 仅从 textual JSON 字段读取字符串，否则返回 null。
     *
     * @param value 待读取 JSON 节点
     * @return 文本值或 null
     */
    private static String textOrNull(JsonNode value) {
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /**
     * 仅从 textual JSON 字段读取字符串，否则返回空串。
     *
     * @param value 待读取 JSON 节点
     * @return 文本值或空串
     */
    private static String textOrEmpty(JsonNode value) {
        return value != null && value.isTextual() ? value.asText() : "";
    }
}
