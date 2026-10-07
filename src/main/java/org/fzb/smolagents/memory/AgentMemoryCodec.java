package org.fzb.smolagents.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.SerializationException;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.MessageRole;
import org.fzb.smolagents.model.TokenUsage;
import org.fzb.smolagents.model.ToolCall;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.OptionalLong;

/**
 * 对 AgentMemory 提供版本化、安全白名单 JSON 持久化。
 *
 * <p>编解码器只构造已知的步骤、聊天内容和工具调用类型；不会按 JSON 中的 class name
 * 动态加载对象，也不接受 Java 原生序列化或 pickle 数据。</p>
 */
public final class AgentMemoryCodec {
    /** 当前持久化格式版本前缀。 */
    public static final String PREFIX = "smolagents-memory-v1:";
    private static final int MAX_SERIALIZED_LENGTH = 16 * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentMemoryCodec() {
    }

    /**
     * 将 AgentMemory 编码为带版本前缀的安全 JSON 字符串。
     *
     * @param memory 待持久化的会话记忆
     * @return 版本化 JSON 字符串
     */
    public static String dumps(AgentMemory memory) {
        if (memory == null) {
            throw new SerializationException("AgentMemory must not be null");
        }
        ObjectNode root = MAPPER.createObjectNode()
                .put("schema_version", 1)
                .put("system_prompt", memory.systemPrompt());
        ArrayNode steps = root.putArray("steps");
        memory.snapshot().forEach(step -> steps.add(serializeStep(step)));
        try {
            String serialized = PREFIX + MAPPER.writeValueAsString(root);
            if (serialized.length() > MAX_SERIALIZED_LENGTH) {
                throw new SerializationException("AgentMemory serialization exceeded the maximum size");
            }
            return serialized;
        } catch (JsonProcessingException error) {
            throw new SerializationException("Unable to serialize AgentMemory as JSON", error);
        }
    }

    /**
     * 将记忆写入指定本地文件，并在支持时通过同目录原子替换防止半写入状态。
     *
     * <p>记忆可能包含系统提示词、任务、消息和工具 observation；调用方负责选择可信且
     * 访问受控的本地路径。此方法不会上传或加密内容。</p>
     *
     * @param memory 待持久化会话记忆
     * @param path 目标 JSON 文件
     */
    public static void save(AgentMemory memory, Path path) {
        if (path == null) {
            throw new SerializationException("AgentMemory path must not be null");
        }
        Path absolutePath = path.toAbsolutePath().normalize();
        Path parent = absolutePath.getParent();
        if (parent == null || absolutePath.getFileName() == null) {
            throw new SerializationException("AgentMemory path must include a file name and parent directory");
        }
        Path temporary = null;
        SerializationException failure = null;
        try {
            Files.createDirectories(parent);
            String temporaryPrefix = absolutePath.getFileName().toString();
            if (temporaryPrefix.length() < 3) {
                temporaryPrefix = (temporaryPrefix + "___").substring(0, 3);
            }
            temporary = Files.createTempFile(parent, temporaryPrefix, ".tmp");
            Files.writeString(temporary, dumps(memory), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        absolutePath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, absolutePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException error) {
            failure = new SerializationException("Unable to save AgentMemory to " + absolutePath, error);
        } catch (SerializationException error) {
            failure = error;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (java.io.IOException cleanupFailure) {
                    if (failure == null) {
                        failure = new SerializationException(
                                "Unable to remove temporary AgentMemory file " + temporary,
                                cleanupFailure
                        );
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 从版本化 JSON 字符串恢复 AgentMemory。
     *
     * @param serialized 持久化记忆字符串
     * @return 使用显式允许的步骤类型构建的记忆
     * @throws SerializationException 前缀、版本、字段或类型不受支持时抛出
     */
    public static AgentMemory loads(String serialized) {
        if (serialized == null || !serialized.startsWith(PREFIX)) {
            throw new SerializationException("AgentMemory requires the supported versioned JSON prefix");
        }
        if (serialized.length() > MAX_SERIALIZED_LENGTH) {
            throw new SerializationException("AgentMemory payload exceeded the maximum size");
        }
        try {
            JsonNode root = MAPPER.readTree(serialized.substring(PREFIX.length()));
            if (root == null || !root.isObject()) {
                throw new SerializationException("AgentMemory payload must contain a JSON object");
            }
            if (requiredInt(root, "schema_version") != 1) {
                throw new SerializationException("Unsupported AgentMemory schema version");
            }
            String systemPrompt = requiredText(root, "system_prompt");
            JsonNode stepsNode = requiredArray(root, "steps");
            List<MemoryStep> steps = new ArrayList<>(stepsNode.size());
            for (JsonNode step : stepsNode) {
                steps.add(deserializeStep(step));
            }
            return new AgentMemory(systemPrompt, steps);
        } catch (JsonProcessingException error) {
            throw new SerializationException("AgentMemory payload contains invalid JSON", error);
        } catch (SerializationException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw new SerializationException("AgentMemory payload contains invalid values", error);
        }
    }

    /**
     * 从本地版本化 JSON 文件恢复 AgentMemory。
     *
     * @param path 记忆文件路径
     * @return 使用 allowlist 类型构造的会话记忆
     */
    public static AgentMemory load(Path path) {
        if (path == null) {
            throw new SerializationException("AgentMemory path must not be null");
        }
        Path absolutePath = path.toAbsolutePath().normalize();
        try {
            if (Files.size(absolutePath) > MAX_SERIALIZED_LENGTH) {
                throw new SerializationException("AgentMemory file exceeded the maximum size");
            }
            return loads(Files.readString(absolutePath, StandardCharsets.UTF_8));
        } catch (java.io.IOException error) {
            throw new SerializationException("Unable to load AgentMemory from " + absolutePath, error);
        }
    }

    /**
     * 将允许的记忆步骤转换为结构化 JSON object。
     *
     * @param step 记忆步骤
     * @return 带 step_type 的 JSON object
     */
    private static ObjectNode serializeStep(MemoryStep step) {
        ObjectNode json = MAPPER.createObjectNode()
                .put("step_number", step.stepNumber())
                .put("started_at", step.startedAt().toString());
        if (step instanceof TaskStep task) {
            json.put("step_type", "task").put("task", task.task());
        } else if (step instanceof PlanningStep planning) {
            json.put("step_type", "planning").put("plan", planning.plan());
            json.set("token_usage", serializeTokenUsage(planning.tokenUsage()));
            json.set("model_input_messages", serializeMessages(planning.modelInputMessages()));
            json.set("model_output_message", serializeMessage(planning.modelOutputMessage()));
        } else if (step instanceof ActionStep action) {
            json.put("step_type", "action");
            json.set("duration", serializeDuration(action.duration()));
            json.set("model_output", serializeMessage(action.modelOutput()));
            json.set("observations", serializeObservations(action.observations()));
            json.set("token_usage", serializeTokenUsage(action.tokenUsage()));
            putNullableText(json, "error", action.error());
        } else if (step instanceof FinalAnswerStep answer) {
            json.put("step_type", "final_answer");
            json.set("duration", serializeDuration(answer.duration()));
            json.set("answer", serializeAgentValue(answer.answer()));
            json.set("token_usage", serializeTokenUsage(answer.tokenUsage()));
        } else {
            throw new SerializationException("Unsupported AgentMemory step: " + step.getClass().getName());
        }
        return json;
    }

    /**
     * 按显式 step_type 恢复内置 MemoryStep 记录。
     *
     * @param json 持久化步骤 JSON
     * @return 受支持的步骤对象
     */
    private static MemoryStep deserializeStep(JsonNode json) {
        if (!json.isObject()) {
            throw new SerializationException("Memory step must be a JSON object");
        }
        int stepNumber = requiredInt(json, "step_number");
        Instant startedAt = parseInstant(requiredText(json, "started_at"));
        return switch (requiredText(json, "step_type")) {
            case "task" -> new TaskStep(stepNumber, startedAt, requiredText(json, "task"));
            case "planning" -> new PlanningStep(
                    stepNumber,
                    startedAt,
                    requiredText(json, "plan"),
                    deserializeTokenUsage(requiredObject(json, "token_usage")),
                    json.has("model_input_messages")
                            ? deserializeMessages(requiredArray(json, "model_input_messages"))
                            : List.of(),
                    json.has("model_output_message")
                            ? deserializeMessage(requiredObject(json, "model_output_message"))
                            : ChatMessage.text(MessageRole.ASSISTANT, requiredText(json, "plan"))
            );
            case "action" -> new ActionStep(
                    stepNumber,
                    startedAt,
                    deserializeDuration(requiredObject(json, "duration")),
                    deserializeMessage(requiredObject(json, "model_output")),
                    deserializeObservations(requiredArray(json, "observations")),
                    deserializeTokenUsage(requiredObject(json, "token_usage")),
                    nullableText(json, "error")
            );
            case "final_answer" -> new FinalAnswerStep(
                    stepNumber,
                    startedAt,
                    deserializeDuration(requiredObject(json, "duration")),
                    deserializeAgentValue(requiredObject(json, "answer")),
                    deserializeTokenUsage(requiredObject(json, "token_usage"))
            );
            default -> throw new SerializationException(
                    "Unsupported AgentMemory step_type: " + json.path("step_type").asText()
            );
        };
    }

    /**
     * 序列化 token 用量，分别保留未知的输入或输出计数。
     *
     * @param usage 模型 token 用量
     * @return token_usage JSON object
     */
    private static ObjectNode serializeTokenUsage(TokenUsage usage) {
        ObjectNode json = MAPPER.createObjectNode();
        if (usage.promptTokens().isPresent()) {
            json.put("input_tokens", usage.promptTokens().getAsLong());
        } else {
            json.putNull("input_tokens");
        }
        if (usage.completionTokens().isPresent()) {
            json.put("output_tokens", usage.completionTokens().getAsLong());
        } else {
            json.putNull("output_tokens");
        }
        return json;
    }

    /**
     * 恢复可能具有未知部分的 token 用量。
     *
     * @param json token_usage object
     * @return 逐项保留已知/未知状态的计数
     */
    private static TokenUsage deserializeTokenUsage(JsonNode json) {
        return new TokenUsage(optionalLong(json.get("input_tokens")), optionalLong(json.get("output_tokens")));
    }

    /**
     * 序列化聊天消息及其文本/媒体内容和工具调用。
     *
     * @param message 聊天消息
     * @return JSON message object
     */
    private static ObjectNode serializeMessage(ChatMessage message) {
        ObjectNode json = MAPPER.createObjectNode()
                .put("role", message.role().name())
                .set("content", serializeContent(message.content()));
        json.set("tool_calls", serializeToolCalls(message.toolCalls()));
        putNullableText(json, "name", message.name());
        putNullableText(json, "tool_call_id", message.toolCallId());
        json.set("token_usage", serializeTokenUsage(message.tokenUsage()));
        return json;
    }

    /**
     * 序列化模型输入消息列表。
     *
     * @param messages 聊天消息列表
     * @return JSON message array
     */
    private static ArrayNode serializeMessages(List<ChatMessage> messages) {
        ArrayNode json = MAPPER.createArrayNode();
        messages.forEach(message -> json.add(serializeMessage(message)));
        return json;
    }

    /**
     * 恢复显式列出的聊天消息列表。
     *
     * @param json 持久化消息数组
     * @return 不可变消息列表
     */
    private static List<ChatMessage> deserializeMessages(JsonNode json) {
        if (!json.isArray()) {
            throw new SerializationException("Persisted model input messages must be an array");
        }
        List<ChatMessage> messages = new ArrayList<>();
        json.forEach(message -> messages.add(deserializeMessage(message)));
        return List.copyOf(messages);
    }

    /**
     * 恢复 ChatMessage 和其 tool-call contract。
     *
     * @param json 持久化 message object
     * @return 恢复的聊天消息
     */
    private static ChatMessage deserializeMessage(JsonNode json) {
        List<ContentPart> content = deserializeContent(requiredArray(json, "content"));
        List<ToolCall> calls = deserializeToolCalls(requiredArray(json, "tool_calls"));
        return new ChatMessage(
                parseEnum(MessageRole.class, requiredText(json, "role"), "message role"),
                content,
                calls,
                nullableText(json, "name"),
                nullableText(json, "tool_call_id"),
                deserializeTokenUsage(requiredObject(json, "token_usage"))
        );
    }

    /**
     * 序列化内容片段并保留媒体字节与 MIME type。
     *
     * @param content 消息内容
     * @return content JSON array
     */
    private static ArrayNode serializeContent(List<ContentPart> content) {
        ArrayNode result = MAPPER.createArrayNode();
        for (ContentPart part : content) {
            ObjectNode json = MAPPER.createObjectNode();
            if (part instanceof ContentPart.Text text) {
                json.put("type", "text").put("text", text.text());
            } else if (part instanceof ContentPart.Json structured) {
                json.put("type", "json").set("value", structured.value());
            } else if (part instanceof ContentPart.Image image) {
                json.put("type", "image")
                        .put("mime_type", image.mimeType())
                        .put("uri", image.uri() == null ? null : image.uri().toString())
                        .put("data", base64(image.data()));
            } else if (part instanceof ContentPart.Audio audio) {
                json.put("type", "audio")
                        .put("mime_type", audio.mimeType())
                        .put("uri", audio.uri() == null ? null : audio.uri().toString())
                        .put("data", base64(audio.data()));
                putNullableText(json, "transcript", audio.transcript());
            } else {
                throw new SerializationException("Unsupported content part: " + part.getClass().getName());
            }
            result.add(json);
        }
        return result;
    }

    /**
     * 恢复纯文本、JSON、图片和音频内容片段。
     *
     * @param json 持久化内容数组
     * @return 顺序保持的内容列表
     */
    private static List<ContentPart> deserializeContent(JsonNode json) {
        List<ContentPart> result = new ArrayList<>(json.size());
        for (JsonNode part : json) {
            String type = requiredText(part, "type");
            switch (type) {
                case "text" -> result.add(new ContentPart.Text(requiredText(part, "text")));
                case "json" -> result.add(new ContentPart.Json(requiredNode(part, "value")));
                case "image" -> result.add(new ContentPart.Image(
                        parseUri(nullableText(part, "uri")),
                        requiredText(part, "mime_type"),
                        decodeBase64(nullableText(part, "data"), "image")
                ));
                case "audio" -> result.add(new ContentPart.Audio(
                        parseUri(nullableText(part, "uri")),
                        requiredText(part, "mime_type"),
                        decodeBase64(nullableText(part, "data"), "audio"),
                        nullableText(part, "transcript")
                ));
                default -> throw new SerializationException("Unsupported content part type: " + type);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 序列化模型工具调用 ID、名称与 JSON 参数。
     *
     * @param calls tool call 列表
     * @return tool_calls JSON array
     */
    private static ArrayNode serializeToolCalls(List<ToolCall> calls) {
        ArrayNode result = MAPPER.createArrayNode();
        for (ToolCall call : calls) {
            ObjectNode json = MAPPER.createObjectNode()
                    .put("id", call.id())
                    .put("name", call.name())
                    .set("arguments", call.arguments());
            result.add(json);
        }
        return result;
    }

    /**
     * 恢复具名工具调用和 object arguments。
     *
     * @param json 持久化工具调用数组
     * @return 原顺序工具调用
     */
    private static List<ToolCall> deserializeToolCalls(JsonNode json) {
        List<ToolCall> result = new ArrayList<>(json.size());
        for (JsonNode call : json) {
            JsonNode arguments = requiredObject(call, "arguments");
            result.add(new ToolCall(requiredText(call, "id"), requiredText(call, "name"), arguments));
        }
        return List.copyOf(result);
    }

    /**
     * 序列化工具观察及其 AgentValue。
     *
     * @param observations 工具观察列表
     * @return observations JSON array
     */
    private static ArrayNode serializeObservations(List<ToolObservation> observations) {
        ArrayNode result = MAPPER.createArrayNode();
        for (ToolObservation observation : observations) {
            result.add(MAPPER.createObjectNode()
                    .put("call_id", observation.callId())
                    .put("tool_name", observation.toolName())
                    .set("result", serializeAgentValue(observation.result())));
        }
        return result;
    }

    /**
     * 恢复工具观察与结果值。
     *
     * @param json 持久化 observations array
     * @return 工具观察列表
     */
    private static List<ToolObservation> deserializeObservations(JsonNode json) {
        List<ToolObservation> result = new ArrayList<>(json.size());
        for (JsonNode observation : json) {
            result.add(new ToolObservation(
                    requiredText(observation, "call_id"),
                    requiredText(observation, "tool_name"),
                    deserializeAgentValue(requiredObject(observation, "result"))
            ));
        }
        return List.copyOf(result);
    }

    /**
     * 序列化 AgentValue 中的内容及可选 structuredContent。
     *
     * @param value 工具或 Agent 输出值
     * @return agent value JSON object
     */
    private static ObjectNode serializeAgentValue(AgentValue value) {
        ObjectNode json = MAPPER.createObjectNode().set("content", serializeContent(value.content()));
        JsonNode structured = value.structuredContent();
        if (structured == null) {
            json.putNull("structured_content");
        } else {
            json.set("structured_content", structured);
        }
        return json;
    }

    /**
     * 恢复 AgentValue 的多模态内容和可选结构化 JSON。
     *
     * @param json 持久化 AgentValue object
     * @return AgentValue
     */
    private static AgentValue deserializeAgentValue(JsonNode json) {
        JsonNode structured = json.get("structured_content");
        return new AgentValue(
                deserializeContent(requiredArray(json, "content")),
                structured == null || structured.isNull() ? null : structured
        );
    }

    /**
     * 序列化持续时间的 seconds/nanos 精确表示。
     *
     * @param duration 持续时间
     * @return duration object
     */
    private static ObjectNode serializeDuration(Duration duration) {
        return MAPPER.createObjectNode()
                .put("seconds", duration.getSeconds())
                .put("nanos", duration.getNano());
    }

    /**
     * 恢复精确的持续时间表示。
     *
     * @param json seconds/nanos object
     * @return duration
     */
    private static Duration deserializeDuration(JsonNode json) {
        int nanos = requiredInt(json, "nanos");
        if (nanos < 0 || nanos >= 1_000_000_000) {
            throw new SerializationException("duration nanos field is out of range");
        }
        return Duration.ofSeconds(requiredLong(json, "seconds"), nanos);
    }

    /**
     * 以 base64 编码可选的媒体字节数组。
     *
     * @param bytes 媒体数据
     * @return base64 字符串；未内嵌数据时为 JSON null
     */
    private static String base64(byte[] bytes) {
        return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 解码可选的媒体 base64 字段。
     *
     * @param value base64 文本
     * @param mediaType 媒体类型标签
     * @return 字节数组；字段为空时为 null
     */
    private static byte[] decodeBase64(String value, String mediaType) {
        if (value == null) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new SerializationException("Invalid base64 data for " + mediaType, error);
        }
    }

    /**
     * 将可选 URI 文本解析为 URI。
     *
     * @param value URI 字符串
     * @return URI；空值时为 null
     */
    private static URI parseUri(String value) {
        if (value == null) {
            return null;
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new SerializationException("Invalid media URI", error);
        }
    }

    /**
     * 解析 ISO-8601 Instant 字段。
     *
     * @param value 时间文本
     * @return Instant
     */
    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw new SerializationException("Invalid memory step timestamp", error);
        }
    }

    /**
     * 解析给定枚举的名称，拒绝未知值。
     *
     * @param enumType 目标 enum 类型
     * @param value 持久化 enum 字符串
     * @param field 字段名
     * @return 枚举项
     */
    private static <E extends Enum<E>> E parseEnum(Class<E> enumType, String value, String field) {
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException error) {
            throw new SerializationException("Unsupported " + field + ": " + value, error);
        }
    }

    /**
     * 添加可选字符串字段或 JSON null。
     *
     * @param object 目标 JSON 对象
     * @param name 字段名
     * @param value 字段值
     */
    private static void putNullableText(ObjectNode object, String name, String value) {
        if (value == null) {
            object.putNull(name);
        } else {
            object.put(name, value);
        }
    }

    /**
     * 读取必填非空文本字段。
     *
     * @param object 所属 JSON object
     * @param name 字段名
     * @return 字段文本
     */
    private static String requiredText(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isTextual()) {
            throw new SerializationException("Missing textual memory field: " + name);
        }
        return value.asText();
    }

    /**
     * 读取可选文本字段。
     *
     * @param object 所属 JSON object
     * @param name 字段名
     * @return 文本或 null
     */
    private static String nullableText(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new SerializationException("Memory field must be text or null: " + name);
        }
        return value.asText();
    }

    /**
     * 读取必填整数。
     *
     * @param object 所属 JSON object
     * @param name 字段名
     * @return int 值
     */
    private static int requiredInt(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new SerializationException("Memory field must be an integer: " + name);
        }
        return value.intValue();
    }

    /**
     * 读取必填 long。
     *
     * @param object 所属 JSON object
     * @param name 字段名
     * @return long 值
     */
    private static long requiredLong(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new SerializationException("Memory field must be a long integer: " + name);
        }
        return value.longValue();
    }

    /**
     * 读取可选 long token 字段。
     *
     * @param value 可选 token 数节点
     * @return OptionalLong
     */
    private static OptionalLong optionalLong(JsonNode value) {
        if (value == null || value.isNull()) {
            return OptionalLong.empty();
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new SerializationException("Token usage values must be non-negative integers or null");
        }
        return OptionalLong.of(value.longValue());
    }

    /**
     * 读取必需 JSON object。
     *
     * @param object 所属 JSON object
     * @param name 字段名
     * @return JSON object 节点
     */
    private static JsonNode requiredObject(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isObject()) {
            throw new SerializationException("Memory field must be an object: " + name);
        }
        return value;
    }

    /**
     * 读取必需 JSON array。
     *
     * @param object 所属 JSON object
     * @param name 字段名
     * @return JSON array 节点
     */
    private static JsonNode requiredArray(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isArray()) {
            throw new SerializationException("Memory field must be an array: " + name);
        }
        return value;
    }

    /**
     * 读取允许为任意 JSON 类型的必需字段。
     *
     * @param object 所属 JSON object
     * @param name 字段名
     * @return JSON 节点防御性副本
     */
    private static JsonNode requiredNode(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            throw new SerializationException("Missing JSON value: " + name);
        }
        return value.deepCopy();
    }
}
