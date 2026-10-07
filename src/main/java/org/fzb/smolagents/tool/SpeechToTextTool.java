package org.fzb.smolagents.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ToolExecutionException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * 将 base64 音频提交至 OpenAI-compatible transcription endpoint 并返回转录文本。
 *
 * <p>本工具是 Python Transformers 本地 Whisper pipeline 的可选远程 API 适配，不会在
 * 本机加载或运行 Whisper 模型。API key 默认从 {@code OPENAI_API_KEY} 读取；音频只通过
 * 请求 multipart body 发送，不写入 URL 或诊断消息。</p>
 */
public final class SpeechToTextTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final URI DEFAULT_ENDPOINT =
            URI.create("https://api.openai.com/v1/audio/transcriptions");
    private static final String DEFAULT_MODEL = "whisper-1";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
    private static final int DEFAULT_MAX_AUDIO_BYTES = 25 * 1024 * 1024;
    private static final int MAX_SUPPORTED_AUDIO_BYTES = 25 * 1024 * 1024;

    private final URI endpoint;
    private final String apiKey;
    private final String modelId;
    private final Duration timeout;
    private final int maxAudioBytes;
    private final HttpClient client;
    private final ToolSpec spec = new ToolSpec(
            "transcriber",
            "Transcribe an audio payload and return the recognized text.",
            createSchema(),
            true
    );

    /**
     * 使用 OpenAI audio transcription endpoint 和环境变量 API key 创建工具。
     */
    public SpeechToTextTool() {
        this(DEFAULT_ENDPOINT, null, DEFAULT_MODEL, DEFAULT_TIMEOUT, DEFAULT_MAX_AUDIO_BYTES);
    }

    /**
     * 使用默认 OpenAI endpoint 和显式 API key 创建工具。
     *
     * @param apiKey transcription API key
     */
    public SpeechToTextTool(String apiKey) {
        this(DEFAULT_ENDPOINT, apiKey, DEFAULT_MODEL, DEFAULT_TIMEOUT, DEFAULT_MAX_AUDIO_BYTES);
    }

    /**
     * 使用自定义 transcription endpoint 和请求设置创建工具。
     *
     * @param endpoint OpenAI-compatible audio transcription endpoint
     * @param apiKey API key；为 null 时读取 {@code OPENAI_API_KEY}
     * @param modelId transcription 模型名
     * @param timeout HTTP 请求超时
     * @param maxAudioBytes 接受的最大解码音频字节数
     */
    public SpeechToTextTool(
            URI endpoint,
            String apiKey,
            String modelId,
            Duration timeout,
            int maxAudioBytes
    ) {
        this.endpoint = requireEndpoint(endpoint);
        String resolvedKey = apiKey == null ? System.getenv("OPENAI_API_KEY") : apiKey;
        if (resolvedKey == null || resolvedKey.isBlank()) {
            throw new ConfigurationException("Missing OPENAI_API_KEY for speech transcription");
        }
        if (modelId == null || modelId.isBlank() || modelId.contains("\r") || modelId.contains("\n")) {
            throw new ConfigurationException("modelId must be non-empty and must not contain line breaks");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new ConfigurationException("timeout must be positive");
        }
        if (maxAudioBytes < 1 || maxAudioBytes > MAX_SUPPORTED_AUDIO_BYTES) {
            throw new ConfigurationException("maxAudioBytes must be between 1 and 25 MiB");
        }
        this.apiKey = resolvedKey;
        this.modelId = modelId;
        this.timeout = timeout;
        this.maxAudioBytes = maxAudioBytes;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * 返回 transcriber 工具参数定义。
     *
     * @return base64 音频与 MIME type schema
     */
    @Override
    public ToolSpec spec() {
        return spec;
    }

    /**
     * 校验输入音频、执行 multipart 上传并返回识别文本。
     *
     * @param arguments 包含 audio.data、audio.mimeType 及可选 language 的参数
     * @param context 当前工具调用上下文
     * @return transcription 文本
     * @throws ToolExecutionException 音频、HTTP 或响应格式无效时抛出
     */
    @Override
    public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
        Objects.requireNonNull(context, "context");
        CancellationToken cancellation = context.cancellation();
        cancellation.throwIfCancelled();
        JsonNode audio = arguments.path("audio");
        String encodedData = audio.path("data").asText("");
        String mimeType = audio.path("mimeType").asText("");
        if (encodedData.isBlank()) {
            throw new ToolExecutionException("audio.data must contain base64-encoded audio");
        }
        long maximumEncodedLength = ((long) maxAudioBytes + 2) / 3 * 4;
        if (encodedData.length() > maximumEncodedLength) {
            throw new ToolExecutionException("Encoded audio exceeds the configured maximum size");
        }
        if (!mimeType.matches("audio/[A-Za-z0-9.+-]+")) {
            throw new ToolExecutionException("audio.mimeType must be an audio MIME type");
        }
        byte[] bytes = decodeAudio(encodedData);
        if (bytes.length == 0 || bytes.length > maxAudioBytes) {
            throw new ToolExecutionException("Audio size must be between 1 and " + maxAudioBytes + " bytes");
        }
        String boundary = "smolagents-" + UUID.randomUUID();
        byte[] payload = createMultipartBody(boundary, mimeType, bytes, audioLanguage(arguments));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        try {
            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            cancellation.throwIfCancelled();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ToolExecutionException(
                        "Speech transcription request failed with HTTP " + response.statusCode()
                );
            }
            return ToolResult.text(parseTranscript(response.body()));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("Speech transcription request was interrupted", error);
        } catch (IOException error) {
            throw new ToolExecutionException("Speech transcription request failed", error);
        }
    }

    /**
     * 解码并校验 base64 音频，避免异常文本被误作音频文件。
     *
     * @param encodedData base64 文本
     * @return 音频字节
     */
    static byte[] decodeAudio(String encodedData) {
        try {
            return Base64.getDecoder().decode(encodedData);
        } catch (IllegalArgumentException error) {
            throw new ToolExecutionException("audio.data is not valid base64", error);
        }
    }

    /**
     * 生成包含模型名、文件数据及可选语言的 multipart body。
     *
     * @param boundary 唯一 multipart boundary
     * @param mimeType 音频 MIME type
     * @param audioBytes 音频数据
     * @param language 可选语言提示
     * @return UTF-8 multipart payload
     */
    private byte[] createMultipartBody(String boundary, String mimeType, byte[] audioBytes, String language) {
        String filename = "audio." + extensionFor(mimeType);
        StringBuilder prefix = new StringBuilder()
                .append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
                .append(modelId).append("\r\n");
        if (language != null) {
            prefix.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
                    .append(language).append("\r\n");
        }
        prefix.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(filename).append("\"\r\n")
                .append("Content-Type: ").append(mimeType).append("\r\n\r\n");
        byte[] header = prefix.toString().getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[header.length + audioBytes.length + suffix.length];
        System.arraycopy(header, 0, body, 0, header.length);
        System.arraycopy(audioBytes, 0, body, header.length, audioBytes.length);
        System.arraycopy(suffix, 0, body, header.length + audioBytes.length, suffix.length);
        return body;
    }

    /**
     * 读取可选 language 字段，并拒绝控制字符对 multipart 文本格式的注入。
     *
     * @param arguments 工具输入对象
     * @return 可选语言代码
     */
    static String audioLanguage(JsonNode arguments) {
        JsonNode language = arguments.get("language");
        if (language == null || language.isNull()) {
            return null;
        }
        if (!language.isTextual() || language.asText().isBlank()
                || language.asText().contains("\r") || language.asText().contains("\n")) {
            throw new ToolExecutionException("language must be a non-empty string without line breaks");
        }
        return language.asText();
    }

    /**
     * 为常见音频 MIME type 生成安全文件扩展名。
     *
     * @param mimeType 输入 MIME type
     * @return 文件扩展名
     */
    static String extensionFor(String mimeType) {
        return switch (mimeType.toLowerCase(java.util.Locale.ROOT)) {
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/mp4", "audio/m4a" -> "m4a";
            case "audio/webm" -> "webm";
            case "audio/ogg" -> "ogg";
            default -> "bin";
        };
    }

    /**
     * 解析 endpoint 返回的 JSON 文本字段。
     *
     * @param body transcription JSON response
     * @return 非空转录文本
     */
    static String parseTranscript(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            String transcript = root == null ? "" : root.path("text").asText("");
            if (transcript.isBlank()) {
                throw new ToolExecutionException("Speech transcription response is missing textual 'text'");
            }
            return transcript;
        } catch (JsonProcessingException error) {
            throw new ToolExecutionException("Speech transcription endpoint returned invalid JSON", error);
        }
    }

    /**
     * 校验 endpoint 必须为不含凭据的绝对 HTTP(S) 地址。
     *
     * @param endpoint transcription endpoint
     * @return 已校验 endpoint
     */
    private static URI requireEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) {
            throw new ConfigurationException("endpoint must be an absolute HTTP(S) URI without credentials or fragment");
        }
        return endpoint;
    }

    /**
     * 创建音频对象与可选语言输入 schema。
     *
     * @return JSON Schema
     */
    static JsonNode createSchema() {
        var audio = MAPPER.createObjectNode();
        audio.put("type", "object");
        var audioProperties = MAPPER.createObjectNode();
        audioProperties.set("data", MAPPER.createObjectNode()
                .put("type", "string")
                .put("description", "Base64-encoded audio bytes."));
        audioProperties.set("mimeType", MAPPER.createObjectNode()
                .put("type", "string")
                .put("description", "Audio MIME type, for example audio/wav."));
        audio.set("properties", audioProperties);
        audio.putArray("required").add("data").add("mimeType");
        audio.put("additionalProperties", false);

        var properties = MAPPER.createObjectNode();
        properties.set("audio", audio);
        properties.set("language", MAPPER.createObjectNode()
                .put("type", "string")
                .put("description", "Optional language code hint."));
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("audio");
        schema.put("additionalProperties", false);
        return schema;
    }
}
