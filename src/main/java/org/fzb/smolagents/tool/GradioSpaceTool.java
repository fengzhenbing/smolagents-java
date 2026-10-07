package org.fzb.smolagents.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.model.ContentPart;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;

/**
 * 将显式配置的 Hugging Face Gradio Space API 端点适配为 Agent 工具。
 *
 * <p>本适配器可使用显式审核的参数定义，也可通过 Gradio {@code /gradio_api/info} 只读发现
 * named endpoint schema；不下载、编译或执行 Space 源码。支持 String、integer、number、
 * boolean 参数及需显式目录白名单的本地文件上传。服务端 binary transport 仍未实现。</p>
 */
public final class GradioSpaceTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_MEDIA_BYTES = 16 * 1024 * 1024;
    private static final int MAX_API_INFO_BYTES = 4 * 1024 * 1024;
    private static final Pattern API_NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    private final URI baseUrl;
    private final URI predictEndpoint;
    private final URI resultEndpoint;
    private final String apiKey;
    private final Duration timeout;
    private final List<Argument> arguments;
    private final FileUploadPolicy fileUploadPolicy;
    private final ToolSpec spec;
    private final HttpClient client;

    /**
     * 创建一个无需认证、仅接收显式提供 endpoint metadata 的 Gradio Space 工具。
     *
     * @param name 本地工具名
     * @param description 工具说明
     * @param baseUrl Space 公网 HTTPS origin
     * @param apiName Gradio 公开 API 名称，例如 {@code predict}
     * @param arguments 经 Space API schema 审核的有序参数定义
     * @param timeout 一轮 Gradio HTTP call/query 的超时时间
     */
    public GradioSpaceTool(
            String name,
            String description,
            URI baseUrl,
            String apiName,
            List<Argument> arguments,
            Duration timeout
    ) {
        this(name, description, baseUrl, apiName, arguments, null, timeout, false);
    }

    /**
     * 使用可选 token 和明文 loopback 配置创建工具。
     *
     * @param name 本地工具名
     * @param description 工具说明
     * @param baseUrl 公网 Space HTTPS origin；仅 loopback HTTP 可通过测试开关启用
     * @param apiName Gradio 公开 API 名称
     * @param arguments 经核验的有序参数定义
     * @param apiKey 可选的 Hugging Face/Space API key
     * @param timeout HTTP 请求超时时长
     * @param allowInsecureHttp 是否允许 HTTP；仅可用于测试或受控本机环境
     */
    public GradioSpaceTool(
            String name,
            String description,
            URI baseUrl,
            String apiName,
            List<Argument> arguments,
            String apiKey,
            Duration timeout,
            boolean allowInsecureHttp
    ) {
        this(name, description, baseUrl, apiName, arguments, apiKey, timeout, allowInsecureHttp, null);
    }

    /**
     * 创建 Gradio 工具并可选启用受限本地文件上传。
     *
     * @param name 本地工具名
     * @param description 工具说明
     * @param baseUrl Space origin
     * @param apiName Gradio API 名称
     * @param arguments 经核验的有序参数定义
     * @param apiKey 可选访问 key
     * @param timeout HTTP 请求超时
     * @param allowInsecureHttp 是否允许 loopback HTTP 测试 endpoint
     * @param fileUploadPolicy 本地文件上传白名单策略；含 FILE 参数时必须提供
     */
    public GradioSpaceTool(
            String name,
            String description,
            URI baseUrl,
            String apiName,
            List<Argument> arguments,
            String apiKey,
            Duration timeout,
            boolean allowInsecureHttp,
            FileUploadPolicy fileUploadPolicy
    ) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new ConfigurationException("Tool name must be a valid Java/Python identifier");
        }
        this.baseUrl = validateBaseUrl(baseUrl, allowInsecureHttp);
        String normalizedApiName = normalizeApiName(apiName);
        this.arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (this.arguments.stream().anyMatch(Objects::isNull)
                || this.arguments.stream().map(Argument::name).distinct().count() != this.arguments.size()) {
            throw new ConfigurationException("Gradio argument definitions must be non-null with unique names");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new ConfigurationException("timeout must be positive");
        }
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.fileUploadPolicy = fileUploadPolicy;
        if (this.arguments.stream().anyMatch(argument -> argument.type() == ValueType.FILE)) {
            if (fileUploadPolicy == null) {
                throw new ConfigurationException("Gradio FILE inputs require an explicit FileUploadPolicy");
            }
            if (!"https".equalsIgnoreCase(this.baseUrl.getScheme())
                    && !(allowInsecureHttp && isLoopbackHost(this.baseUrl.getHost()))) {
                throw new ConfigurationException("Gradio file uploads require an HTTPS endpoint");
            }
        }
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        String encodedName = URLEncoder.encode(normalizedApiName, StandardCharsets.UTF_8);
        this.predictEndpoint = resolve("/gradio_api/call/" + encodedName);
        this.resultEndpoint = resolve("/gradio_api/call/" + encodedName + "/");
        this.spec = new ToolSpec(name, Objects.requireNonNull(description, "description"),
                createSchema(this.arguments), true);
    }

    /**
     * 只读发现 Space 中可调用的 Gradio named endpoints。
     *
     * <p>此方法仅读取 {@code /gradio_api/info} JSON，不调用 endpoint，也不下载或执行 Space 代码。
     * 当前接受 string、integer、number、boolean 及基于显式上传策略的 file 参数；
     * 其它组件会显式报告为不支持。</p>
     *
     * @param baseUrl Space HTTPS origin；loopback HTTP 仅在显式测试模式下允许
     * @param apiKey 可选 Hugging Face/Space API key
     * @param timeout HTTP 请求超时时长
     * @param allowInsecureHttp 是否显式允许 loopback HTTP 测试 endpoint
     * @return Hub 返回顺序下的具名 API endpoint schema
     */
    public static List<ApiEndpoint> discoverApis(
            URI baseUrl,
            String apiKey,
            Duration timeout,
            boolean allowInsecureHttp
    ) {
        URI validatedBaseUrl = validateBaseUrl(baseUrl, allowInsecureHttp);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new ConfigurationException("timeout must be positive");
        }
        URI infoUrl = URI.create(validatedBaseUrl.toASCIIString().replaceAll("/+$", "")
                + "/gradio_api/info");
        HttpRequest.Builder request = HttpRequest.newBuilder(infoUrl)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "smolagents-java/1.0")
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        JsonNode info = fetchApiInfo(request.build(), timeout);
        JsonNode namedEndpoints = info.path("named_endpoints");
        if (!namedEndpoints.isObject()) {
            throw new ToolExecutionException("Gradio API info is missing named_endpoints");
        }
        List<ApiEndpoint> endpoints = new java.util.ArrayList<>();
        namedEndpoints.fields().forEachRemaining(entry -> endpoints.add(parseApiEndpoint(
                entry.getKey(),
                entry.getValue()
        )));
        return List.copyOf(endpoints);
    }

    /**
     * 读取 Space schema 并构造对应工具；有多个 API 时要求调用方指定名称。
     *
     * @param name 本地工具名
     * @param description 面向模型的工具说明
     * @param baseUrl Space HTTPS origin
     * @param apiName Gradio API 名称；仅一个 API 时可为 null
     * @param apiKey 可选 Hugging Face/Space API key
     * @param timeout HTTP 请求超时时长
     * @param allowInsecureHttp 是否显式允许 loopback HTTP 测试 endpoint
     * @return 使用发现到的有序参数 schema 创建的 Space 工具
     */
    public static GradioSpaceTool discover(
            String name,
            String description,
            URI baseUrl,
            String apiName,
            String apiKey,
            Duration timeout,
            boolean allowInsecureHttp
    ) {
        return discover(name, description, baseUrl, apiName, apiKey, timeout, allowInsecureHttp, null);
    }

    /**
     * 基于只读 API schema 发现具名 endpoint，并可选授权上传本地文件参数。
     *
     * @param name 本地工具名
     * @param description 面向模型的工具说明
     * @param baseUrl Space HTTPS origin
     * @param apiName API 名称；多个 endpoint 时必须指定
     * @param apiKey 可选 Hugging Face/Space API key
     * @param timeout HTTP 请求超时时长
     * @param allowInsecureHttp 是否显式允许 loopback HTTP 测试 endpoint
     * @param fileUploadPolicy 本地文件上传的显式目录及大小策略；未授权时 FILE schema 会失败
     * @return 按发现 schema 构造的 Space 工具
     */
    public static GradioSpaceTool discover(
            String name,
            String description,
            URI baseUrl,
            String apiName,
            String apiKey,
            Duration timeout,
            boolean allowInsecureHttp,
            FileUploadPolicy fileUploadPolicy
    ) {
        List<ApiEndpoint> endpoints = discoverApis(baseUrl, apiKey, timeout, allowInsecureHttp);
        if (endpoints.isEmpty()) {
            throw new ConfigurationException("Gradio Space exposes no named API endpoints");
        }
        ApiEndpoint selected;
        if (apiName == null || apiName.isBlank()) {
            if (endpoints.size() != 1) {
                throw new ConfigurationException("Specify apiName when a Gradio Space exposes multiple APIs");
            }
            selected = endpoints.getFirst();
        } else {
            String normalizedName = normalizeApiName(apiName);
            selected = endpoints.stream()
                    .filter(endpoint -> endpoint.apiName().equals(normalizedName))
                    .findFirst()
                    .orElseThrow(() -> new ConfigurationException("Gradio apiName was not found in Space metadata"));
        }
        return new GradioSpaceTool(
                name,
                description,
                baseUrl,
                selected.apiName(),
                selected.arguments(),
                apiKey,
                timeout,
                allowInsecureHttp,
                fileUploadPolicy
        );
    }

    /**
     * 返回构造器显式配置的工具 schema。
     *
     * @return Gradio Space 工具定义
     */
    @Override
    public ToolSpec spec() {
        return spec;
    }

    /**
     * 按 schema 参数顺序发起 Gradio POST call 和 SSE result query。
     *
     * @param arguments 已通过 JSON Schema validation 的调用参数
     * @param context 当前工具调用上下文
     * @return 文本、结构化 JSON、图片 URL 或音频 URL 结果
     */
    @Override
    public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
        Objects.requireNonNull(context, "context");
        CancellationToken cancellation = context.cancellation();
        cancellation.throwIfCancelled();
        ArrayNode values = MAPPER.createArrayNode();
        for (Argument argument : this.arguments) {
            JsonNode value = arguments.get(argument.name());
            if (value == null || value.isNull()) {
                values.addNull();
            } else if (argument.type() == ValueType.FILE) {
                if (!value.isTextual()) {
                    throw new ToolExecutionException("Gradio FILE input must be a local path string");
                }
                values.add(uploadFile(value.asText(), cancellation));
            } else {
                values.add(value.deepCopy());
            }
        }
        JsonNode created = postJson(predictEndpoint, MAPPER.createObjectNode().set("data", values), cancellation);
        String eventId = created.path("event_id").asText("");
        if (eventId.isBlank()) {
            throw new ToolExecutionException("Gradio call response is missing event_id");
        }
        URI resultUri = URI.create(resultEndpoint.toASCIIString() + URLEncoder.encode(
                eventId,
                StandardCharsets.UTF_8
        ));
        String eventStream = send(requestBuilder(resultUri)
                .header("Accept", "text/event-stream")
                .GET()
                .build(), cancellation);
        String data = parseCompletedEvent(eventStream);
        JsonNode results;
        try {
            results = MAPPER.readTree(data);
        } catch (JsonProcessingException error) {
            throw new ToolExecutionException("Gradio stream returned invalid JSON data", error);
        }
        if (!results.isArray() || results.isEmpty()) {
            throw new ToolExecutionException("Gradio stream returned no output value");
        }
        if (results.size() > 1 && results.get(1).isTextual()) {
            throw new ToolExecutionException("Gradio Space returned an error: " + results.get(1).asText());
        }
        return formatOutput(results.get(0), cancellation);
    }

    /**
     * 创建符合 Gradio API 参数顺序的工具 JSON Schema。
     *
     * @param arguments 参数定义
     * @return strict object schema
     */
    private static JsonNode createSchema(List<Argument> arguments) {
        var properties = MAPPER.createObjectNode();
        var required = MAPPER.createArrayNode();
        for (Argument argument : arguments) {
            var parameter = MAPPER.createObjectNode().put("type", argument.type().schemaType());
            if (argument.type() == ValueType.FILE) {
                parameter.put("type", "string");
                parameter.put("format", "filepath");
            }
            if (!argument.description().isBlank()) {
                parameter.put("description", argument.description());
            }
            if (argument.nullable()) {
                parameter.set("type", MAPPER.createArrayNode().add(argument.type().schemaType()).add("null"));
            }
            properties.set(argument.name(), parameter);
            if (argument.required()) {
                required.add(argument.name());
            }
        }
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 解析一个 Gradio endpoint 的输入参数和输出组件元数据。
     *
     * @param apiName Gradio endpoint key
     * @param metadata endpoint API 描述
     * @return 原语参数 schema 和输出组件名称
     */
    private static ApiEndpoint parseApiEndpoint(String apiName, JsonNode metadata) {
        String normalizedApiName = normalizeApiName(apiName);
        JsonNode parameters = metadata.path("parameters");
        JsonNode returns = metadata.path("returns");
        if (!parameters.isArray() || !returns.isArray() || returns.isEmpty()) {
            throw new ToolExecutionException("Gradio API endpoint has incomplete parameter or return metadata");
        }
        List<Argument> arguments = new java.util.ArrayList<>();
        for (JsonNode parameter : parameters) {
            String name = parameter.path("parameter_name").asText("");
            String typeName = parameter.path("type").path("type").asText("");
            String component = parameter.path("component").asText("");
            String pythonType = parameter.path("python_type").path("type").asText("");
            String description = parameter.path("python_type").path("description").asText("");
            boolean nullable = parameter.path("parameter_has_default").asBoolean(false);
            ValueType type = isGradioFileType(component, pythonType, typeName)
                    ? ValueType.FILE
                    : switch (typeName) {
                        case "string" -> ValueType.STRING;
                        case "integer" -> ValueType.INTEGER;
                        case "number" -> ValueType.NUMBER;
                        case "boolean" -> ValueType.BOOLEAN;
                        default -> throw new ToolExecutionException(
                                "Gradio API parameter type is unsupported: " + typeName
                        );
                    };
            try {
                arguments.add(new Argument(name, description, type, !nullable, nullable));
            } catch (ConfigurationException error) {
                throw new ToolExecutionException("Gradio API parameter metadata is invalid", error);
            }
        }
        List<String> outputComponents = new java.util.ArrayList<>();
        for (JsonNode output : returns) {
            String component = output.path("component").asText("");
            if (component.isBlank()) {
                throw new ToolExecutionException("Gradio API return metadata is missing its component name");
            }
            outputComponents.add(component);
        }
        return new ApiEndpoint(normalizedApiName, arguments, outputComponents);
    }

    /**
     * 根据 Gradio 组件和 Python 类型元数据识别需要以 FileData 上传的输入。
     *
     * @param component Gradio 组件名称
     * @param pythonType API metadata 中的 Python 类型
     * @param schemaType JSON Schema 简化类型
     * @return 组件为 file/image/audio/video 或显式 file type 时为 true
     */
    private static boolean isGradioFileType(String component, String pythonType, String schemaType) {
        return "file".equalsIgnoreCase(schemaType)
                || "uploadfile".equalsIgnoreCase(schemaType)
                || "filepath".equalsIgnoreCase(pythonType)
                || List.of("file", "image", "audio", "video").stream()
                .anyMatch(componentName -> componentName.equalsIgnoreCase(component));
    }

    /**
     * 通过有大小上限的只读 GET 请求读取 Gradio API info JSON。
     *
     * @param request 已设置认证和 endpoint 的 HTTP 请求
     * @param timeout 请求超时时长
     * @return API info JSON
     */
    private static JsonNode fetchApiInfo(HttpRequest request, Duration timeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("Gradio API info request was interrupted", error);
        } catch (IOException error) {
            throw new ToolExecutionException("Gradio API info request failed", error);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ToolExecutionException(
                        "Gradio API info request failed with HTTP " + response.statusCode()
                );
            }
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (contentLength > MAX_API_INFO_BYTES) {
                throw new ToolExecutionException("Gradio API info exceeded the maximum response size");
            }
            byte[] bytes = body.readNBytes(MAX_API_INFO_BYTES + 1);
            if (bytes.length > MAX_API_INFO_BYTES) {
                throw new ToolExecutionException("Gradio API info exceeded the maximum response size");
            }
            try {
                return MAPPER.readTree(bytes);
            } catch (JsonProcessingException error) {
                throw new ToolExecutionException("Gradio API info endpoint returned invalid JSON", error);
            }
        } catch (IOException error) {
            throw new ToolExecutionException("Unable to read Gradio API info response", error);
        }
    }

    /**
     * 去除 Gradio API 名称的可选前导斜线并验证名称字符。
     *
     * @param apiName Gradio API 名称
     * @return 可用于 Gradio call endpoint 的 API 名称
     */
    private static String normalizeApiName(String apiName) {
        if (apiName == null || apiName.isBlank()) {
            throw new ConfigurationException("Gradio apiName must not be blank");
        }
        String normalized = apiName.startsWith("/") ? apiName.substring(1) : apiName;
        if (!API_NAME.matcher(normalized).matches()) {
            throw new ConfigurationException("Gradio apiName must contain only letters, digits, '.', '_' or '-'");
        }
        return normalized;
    }

    /**
     * 把 Gradio 最终 output 映射到有限的内置 AgentValue 类型。
     *
     * @param value `data` 数组第一个输出项
     * @return tool result
     */
    private ToolResult formatOutput(JsonNode value, CancellationToken cancellation) {
        if (value.isNull()) {
            return ToolResult.text("");
        }
        if (value.isTextual()) {
            String text = value.asText();
            URI mediaUri = mediaUri(value);
            if (mediaUri != null) {
                String path = mediaUri.getPath().toLowerCase(java.util.Locale.ROOT);
                if (isImagePath(path)) {
                    byte[] image = fetchSameOriginMedia(mediaUri, imageMime(path), cancellation);
                    return new ToolResult(List.of(new ContentPart.Image(mediaUri, imageMime(path), image)), null);
                }
                if (isAudioPath(path)) {
                    byte[] audio = fetchSameOriginMedia(mediaUri, audioMime(path), cancellation);
                    return new ToolResult(List.of(new ContentPart.Audio(mediaUri, audioMime(path), audio, null)), null);
                }
            }
            return ToolResult.text(text);
        }
        if (value.isObject()) {
            JsonNode url = value.get("url");
            if (url != null && url.isTextual()) {
                URI mediaUri = mediaUri(url);
                if (mediaUri != null) {
                    String path = mediaUri.getPath().toLowerCase(java.util.Locale.ROOT);
                    if (isImagePath(path)) {
                        byte[] image = fetchSameOriginMedia(mediaUri, imageMime(path), cancellation);
                        return new ToolResult(List.of(new ContentPart.Image(mediaUri, imageMime(path), image)), value);
                    }
                    if (isAudioPath(path)) {
                        byte[] audio = fetchSameOriginMedia(mediaUri, audioMime(path), cancellation);
                        return new ToolResult(List.of(new ContentPart.Audio(mediaUri, audioMime(path), audio, null)), value);
                    }
                }
            }
        }
        return new ToolResult(List.of(new ContentPart.Json(value)), value);
    }

    /**
     * 下载仅限 Space 自身 origin 的媒体文件，并限制其 MIME 与最大二进制长度。
     *
     * @param mediaUri Gradio 返回的媒体 URL
     * @param expectedMime 从媒体文件扩展名推导出的 MIME type
     * @param cancellation 当前工具调用取消令牌
     * @return 同源媒体 bytes；外部 origin 返回 null 并保留 URI 引用
     */
    private byte[] fetchSameOriginMedia(
            URI mediaUri,
            String expectedMime,
            CancellationToken cancellation
    ) {
        if (!sameOrigin(baseUrl, mediaUri)) {
            return null;
        }
        cancellation.throwIfCancelled();
        HttpResponse<byte[]> response;
        try {
            response = client.send(
                    requestBuilder(mediaUri).header("Accept", expectedMime).GET().build(),
                    responseInfo -> new BoundedBodySubscriber(MAX_MEDIA_BYTES, cancellation)
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("Gradio media request was interrupted", error);
        } catch (IOException error) {
            if (causedBy(error, ResponseLimitException.class)) {
                throw new ToolExecutionException("Gradio media response exceeded the maximum size", error);
            }
            if (cancellation.isCancelled()) {
                throw new ToolExecutionException("Gradio media request was cancelled", error);
            }
            throw new ToolExecutionException("Unable to retrieve Gradio media output", error);
        }
        cancellation.throwIfCancelled();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ToolExecutionException("Gradio media request failed with HTTP " + response.statusCode());
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("")
                .toLowerCase(java.util.Locale.ROOT);
        if (!contentType.startsWith(expectedMime)
                && !contentType.startsWith("application/octet-stream")) {
            throw new ToolExecutionException("Gradio media response has an unexpected content type");
        }
        long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declaredLength > MAX_MEDIA_BYTES) {
            throw new ToolExecutionException("Gradio media response exceeded the maximum size");
        }
        return response.body();
    }

    /**
     * 解析 Gradio file result 中的绝对 URL，仅接受 HTTP(S) 媒体引用。
     *
     * @param value URL 文本 JSON node
     * @return 绝对媒体 URL；本地服务器路径或非法 URL 时返回 null
     */
    private static URI mediaUri(JsonNode value) {
        String raw = value.asText("");
        try {
            URI uri = URI.create(raw);
            if (uri.isAbsolute()
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // Non-URL output is returned as regular text instead of being treated as media.
        }
        return null;
    }

    /**
     * 校验本地文件白名单后上传到 Gradio，并生成标准 FileData 参数对象。
     *
     * <p>步骤：解析真实文件路径并检查授权根目录；有界读取文件字节；通过 multipart upload
     * endpoint 上传；把服务端返回路径包装为 Gradio {@code FileData} JSON。</p>
     *
     * @param rawPath 模型提供的本地文件路径
     * @param cancellation 当前工具调用取消令牌
     * @return 可放入 Gradio call data array 的文件对象
     */
    private JsonNode uploadFile(String rawPath, CancellationToken cancellation) {
        if (fileUploadPolicy == null) {
            throw new ToolExecutionException("Gradio file upload is not enabled for this tool");
        }
        if (rawPath == null || rawPath.isBlank()) {
            throw new ToolExecutionException("Gradio file input path must not be blank");
        }
        Path file;
        try {
            file = Path.of(rawPath).toRealPath();
        } catch (InvalidPathException | IOException error) {
            throw new ToolExecutionException("Gradio file input path is invalid or unavailable", error);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || fileUploadPolicy.allowedRoots().stream().noneMatch(file::startsWith)) {
            throw new ToolExecutionException("Gradio file input is outside the configured allowed roots");
        }
        byte[] content;
        try {
            long size = Files.size(file);
            if (size > fileUploadPolicy.maxFileBytes()) {
                throw new ToolExecutionException("Gradio file input exceeds the configured size limit");
            }
            try (InputStream input = Files.newInputStream(file)) {
                content = input.readNBytes((int) fileUploadPolicy.maxFileBytes() + 1);
            }
        } catch (IOException error) {
            throw new ToolExecutionException("Unable to read Gradio file input", error);
        }
        if (content.length > fileUploadPolicy.maxFileBytes()) {
            throw new ToolExecutionException("Gradio file input exceeds the configured size limit");
        }

        String fileName = safeMultipartFileName(file.getFileName().toString());
        String mimeType = mimeType(file);
        String boundary = "----smolagents-" + UUID.randomUUID();
        String opening = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + mimeType + "\r\n\r\n";
        String closing = "\r\n--" + boundary + "--\r\n";
        HttpRequest request = requestBuilder(resolve("/gradio_api/upload"))
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofString(opening, StandardCharsets.UTF_8),
                        HttpRequest.BodyPublishers.ofByteArray(content),
                        HttpRequest.BodyPublishers.ofString(closing, StandardCharsets.UTF_8)
                ))
                .build();
        String response = send(request, cancellation);
        JsonNode uploaded;
        try {
            uploaded = MAPPER.readTree(response);
        } catch (JsonProcessingException error) {
            throw new ToolExecutionException("Gradio upload endpoint returned invalid JSON", error);
        }
        if (!uploaded.isArray() || uploaded.isEmpty() || !uploaded.get(0).isTextual()
                || uploaded.get(0).asText().isBlank()) {
            throw new ToolExecutionException("Gradio upload endpoint returned no server file path");
        }
        ObjectNode fileData = MAPPER.createObjectNode()
                .put("path", uploaded.get(0).asText())
                .put("orig_name", fileName)
                .put("size", content.length)
                .put("mime_type", mimeType);
        fileData.set("meta", MAPPER.createObjectNode().put("_type", "gradio.FileData"));
        return fileData;
    }

    /**
     * 移除 multipart 文件名中的 header 控制字符和引号。
     *
     * @param fileName 本地文件名
     * @return 可用于 multipart header 的 ASCII 文件名
     */
    private static String safeMultipartFileName(String fileName) {
        StringBuilder safeName = new StringBuilder(fileName.length());
        for (int index = 0; index < fileName.length(); index++) {
            char value = fileName.charAt(index);
            safeName.append(value < 0x20 || value > 0x7e || value == '"' || value == '\\' ? '_' : value);
        }
        return safeName.isEmpty() ? "upload.bin" : safeName.toString();
    }

    /**
     * 从操作系统识别文件 MIME type；未识别或格式异常时使用二进制默认值。
     *
     * @param file 已授权的本地文件路径
     * @return 安全 MIME type
     */
    private static String mimeType(Path file) {
        try {
            String detected = Files.probeContentType(file);
            if (detected != null && detected.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) {
                return detected;
            }
        } catch (IOException ignored) {
            // MIME detection is optional; unknown types use the safe binary default.
        }
        return "application/octet-stream";
    }

    /**
     * 以 POST JSON 启动一次 Gradio queued event。
     *
     * @param endpoint Gradio API endpoint
     * @param payload JSON data array payload
     * @param cancellation 当前取消令牌
     * @return event id JSON response
     */
    private JsonNode postJson(URI endpoint, JsonNode payload, CancellationToken cancellation) {
        HttpRequest request = requestBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        String response = send(request, cancellation);
        try {
            return MAPPER.readTree(response);
        } catch (JsonProcessingException error) {
            throw new ToolExecutionException("Gradio call endpoint returned invalid JSON", error);
        }
    }

    /**
     * 等待 SSE complete/error event，并限制返回内容大小。
     *
     * @param responseBody HTTP response body
     * @return complete event 的 JSON data
     */
    private static String parseCompletedEvent(String responseBody) {
        if (responseBody.length() > MAX_RESPONSE_BYTES) {
            throw new ToolExecutionException("Gradio Space response exceeded the maximum size");
        }
        String currentEvent = "message";
        StringBuilder data = new StringBuilder();
        for (String line : responseBody.split("\\R", -1)) {
            if (line.isEmpty()) {
                String result = completeEventData(currentEvent, data);
                if (result != null) {
                    return result;
                }
                currentEvent = "message";
            } else if (line.startsWith("event:")) {
                currentEvent = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                String value = line.substring(5);
                data.append(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        String result = completeEventData(currentEvent, data);
        if (result != null) {
            return result;
        }
        throw new ToolExecutionException("Gradio SSE result stream ended without a complete event");
    }

    /**
     * 处理一个 SSE event，终止状态会显式转换为错误。
     *
     * @param event 当前 event 名称
     * @param data 当前 event data
     * @return complete data；非终止 event 返回 null
     */
    private static String completeEventData(String event, StringBuilder data) {
        if (data.isEmpty()) {
            return null;
        }
        String content = data.toString();
        data.setLength(0);
        if ("error".equalsIgnoreCase(event) || "server-error".equalsIgnoreCase(event)) {
            throw new ToolExecutionException("Gradio Space returned an error event: " + content);
        }
        if ("complete".equalsIgnoreCase(event)) {
            return content;
        }
        return null;
    }

    /**
     * 创建附加 bearer token 的 HTTP request builder。
     *
     * @param endpoint 当前 Gradio endpoint
     * @return 基础 request builder
     */
    private HttpRequest.Builder requestBuilder(URI endpoint) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("User-Agent", "smolagents-java/1.0");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder;
    }

    /**
     * 同步发送 endpoint 请求并将网络、超时和 HTTP 错误显式映射。
     *
     * @param request prepared HTTP request
     * @param cancellation request cancellation
     * @return 非错误 HTTP 响应的 UTF-8 正文
     */
    private String send(HttpRequest request, CancellationToken cancellation) {
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, info -> new BoundedBodySubscriber(MAX_RESPONSE_BYTES, cancellation));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("Gradio Space request was interrupted", error);
        } catch (IOException error) {
            if (causedBy(error, ResponseLimitException.class)) {
                throw new ToolExecutionException("Gradio Space response exceeded the maximum size", error);
            }
            if (cancellation.isCancelled()) {
                throw new ToolExecutionException("Gradio Space request was cancelled", error);
            }
            throw new ToolExecutionException("Gradio Space request failed", error);
        }
        cancellation.throwIfCancelled();
        String responseBody = new String(response.body(), StandardCharsets.UTF_8);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String details = responseBody.substring(0, Math.min(responseBody.length(), 1_024));
            throw new ToolExecutionException(
                    "Gradio Space request failed with HTTP " + response.statusCode()
                            + (details.isBlank() ? "" : ": " + details)
            );
        }
        return responseBody;
    }

    /**
     * 检查 Provider 包装异常链是否包含指定类型。
     *
     * @param error 原始异常或其包装异常
     * @param expectedType 要检查的原因类型
     * @return 异常链中包含指定原因时为 true
     */
    private static boolean causedBy(Throwable error, Class<? extends Throwable> expectedType) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (expectedType.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在 HTTP body 到达时限制实际读取字节，并让请求超时覆盖整个响应读取阶段。
     */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximumBytes;
        private final CancellationToken cancellation;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        /**
         * 创建单响应体字节上限 subscriber。
         *
         * @param maximumBytes 最大响应字节数
         * @param cancellation 请求取消令牌
         */
        private BoundedBodySubscriber(int maximumBytes, CancellationToken cancellation) {
            this.maximumBytes = maximumBytes;
            this.cancellation = cancellation;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> chunks) {
            if (result.isDone()) {
                return;
            }
            if (cancellation.isCancelled()) {
                subscription.cancel();
                result.completeExceptionally(new IOException("Gradio Space request was cancelled"));
                return;
            }
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > maximumBytes - body.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseLimitException());
                    return;
                }
                byte[] bytes = new byte[chunk.remaining()];
                chunk.get(bytes);
                body.write(bytes, 0, bytes.length);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(body.toByteArray());
        }
    }

    /**
     * 标识 Gradio 响应在进入内存缓冲前超过策略上限。
     */
    private static final class ResponseLimitException extends IOException {
        /**
         * 创建受限响应体超限错误。
         */
        private ResponseLimitException() {
            super("Gradio Space response exceeded the maximum size");
        }
    }

    /**
     * 依据 base URL 合并固定的 Gradio API path。
     *
     * @param path absolute endpoint path under the Space origin
     * @return endpoint URI
     */
    private URI resolve(String path) {
        String prefix = baseUrl.toASCIIString().replaceAll("/+$", "");
        return URI.create(prefix + path);
    }

    /**
     * 校验 Space base URL，默认拒绝私有/本机明文 HTTP endpoint。
     *
     * @param baseUrl Space origin
     * @param allowInsecureHttp 是否显式允许非公网测试 endpoint
     * @return normalized origin
     */
    private static URI validateBaseUrl(URI baseUrl, boolean allowInsecureHttp) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (!("http".equalsIgnoreCase(baseUrl.getScheme()) || "https".equalsIgnoreCase(baseUrl.getScheme()))
                || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null
                || baseUrl.getFragment() != null
                || baseUrl.getRawQuery() != null) {
            throw new ConfigurationException("Gradio baseUrl must be an HTTP(S) origin without query or credentials");
        }
        boolean https = "https".equalsIgnoreCase(baseUrl.getScheme());
        boolean loopbackHttpTest = allowInsecureHttp
                && !https
                && isLoopbackHost(baseUrl.getHost());
        if (!https && !loopbackHttpTest) {
            throw new ConfigurationException("Gradio Space baseUrl must use HTTPS; HTTP is limited to loopback tests");
        }
        if (!loopbackHttpTest) {
            validatePublicAddress(baseUrl.getHost());
        }
        return baseUrl;
    }

    /**
     * 拒绝解析到本机、私有、链路本地或组播网络的 Space 主机。
     *
     * @param host Space URL 主机名
     */
    private static void validatePublicAddress(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new ConfigurationException(
                            "Gradio Space URL resolves to a non-public network address"
                    );
                }
            }
        } catch (ConfigurationException error) {
            throw error;
        } catch (IOException error) {
            throw new ConfigurationException("Unable to resolve Gradio Space host", error);
        }
    }

    /**
     * 检查 URL host 是否为明确的 loopback 字面值。
     *
     * @param host URL 主机名
     * @return 本机 loopback 地址时为 true
     */
    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalizedHost = host.toLowerCase(java.util.Locale.ROOT);
        return normalizedHost.equals("localhost")
                || normalizedHost.equals("127.0.0.1")
                || normalizedHost.equals("[::1]")
                || normalizedHost.equals("::1");
    }

    /**
     * 比较两个 URI 的 scheme、host 和有效端口是否完全一致。
     *
     * @param left 第一个 origin
     * @param right 第二个 origin
     * @return 同源时为 true
     */
    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme() != null
                && right.getScheme() != null
                && left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost() != null
                && right.getHost() != null
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    /**
     * 返回显式 TCP 端口或 URI scheme 的默认端口。
     *
     * @param uri HTTP(S) URI
     * @return 有效端口值
     */
    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * 判断 Gradio URL path 是否对应常见图像扩展名。
     *
     * @param path lower-case path
     * @return 图像资源时为 true
     */
    private static boolean isImagePath(String path) {
        return List.of(".png", ".jpg", ".jpeg", ".gif", ".webp").stream().anyMatch(path::endsWith);
    }

    /**
     * 判断 Gradio URL path 是否对应常见音频扩展名。
     *
     * @param path lower-case path
     * @return 音频资源时为 true
     */
    private static boolean isAudioPath(String path) {
        return List.of(".mp3", ".wav", ".ogg", ".m4a", ".flac").stream().anyMatch(path::endsWith);
    }

    /**
     * 由常见图片文件扩展名推断 MIME type。
     *
     * @param path lower-case URL path
     * @return image MIME type
     */
    private static String imageMime(String path) {
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (path.endsWith(".gif")) {
            return "image/gif";
        }
        if (path.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    /**
     * 由常见音频文件扩展名推断 MIME type。
     *
     * @param path lower-case URL path
     * @return audio MIME type
     */
    private static String audioMime(String path) {
        if (path.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (path.endsWith(".ogg")) {
            return "audio/ogg";
        }
        if (path.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (path.endsWith(".flac")) {
            return "audio/flac";
        }
        return "audio/wav";
    }

    /**
     * Gradio API 中有限支持的输入参数类型。
     */
    public enum ValueType {
        /** 文本字符串。 */
        STRING("string"),
        /** Python integer。 */
        INTEGER("integer"),
        /** Python floating-point number。 */
        NUMBER("number"),
        /** Python boolean。 */
        BOOLEAN("boolean"),
        /** Gradio FileData 上传参数；调用方必须配置文件根目录和字节数上限。 */
        FILE("string");

        private final String schemaType;

        ValueType(String schemaType) {
            this.schemaType = schemaType;
        }

        private String schemaType() {
            return schemaType;
        }
    }

    /**
     * 描述 Gradio endpoint 的一个按位置传输的简单参数。
     *
     * @param name JSON 输入字段名
     * @param description 面向模型的说明
     * @param type 参数 schema 类型
     * @param required 参数是否必填
     * @param nullable Gradio endpoint 是否接受 JSON null
     */
    public record Argument(
            String name,
            String description,
            ValueType type,
            boolean required,
            boolean nullable
    ) {
        /**
         * 校验工具参数元数据。
         *
         * @param name JSON 输入字段名
         * @param description 参数说明
         * @param type 参数 primitive 类型
         * @param required 参数是否必填
         * @param nullable 参数是否可为空
         */
        public Argument {
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new ConfigurationException("Gradio argument name must be a valid identifier");
            }
            description = Objects.requireNonNull(description, "description");
            Objects.requireNonNull(type, "type");
            if (nullable && required) {
                throw new ConfigurationException("A required Gradio argument cannot be nullable");
            }
        }

        /**
         * 创建必需非空参数。
         *
         * @param name 字段名
         * @param description 说明
         * @param type primitive type
         */
        public Argument(String name, String description, ValueType type) {
            this(name, description, type, true, false);
        }
    }

    /**
     * 从 Gradio API info 发现的 endpoint 及其 primitive 输入、输出组件。
     *
     * @param apiName Gradio API 名称，不含可选前导斜线
     * @param arguments 按 Gradio 参数顺序排列的 schema
     * @param outputComponents 按 API 返回顺序排列的组件名称
     */
    public record ApiEndpoint(String apiName, List<Argument> arguments, List<String> outputComponents) {
        /**
         * 校验并复制发现到的 API endpoint 描述。
         *
         * @param apiName Gradio API 名称
         * @param arguments 参数 schema
         * @param outputComponents 输出组件名称
         */
        public ApiEndpoint {
            apiName = normalizeApiName(apiName);
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
            outputComponents = List.copyOf(Objects.requireNonNull(outputComponents, "outputComponents"));
        }
    }

    /**
     * 对 Gradio 本地文件上传的根目录和最大文件字节数作显式授权。
     *
     * @param allowedRoots 仅允许上传这些目录之下的普通文件
     * @param maxFileBytes 单个上传文件最大字节数
     */
    public record FileUploadPolicy(List<Path> allowedRoots, long maxFileBytes) {
        /**
         * 解析授权目录的真实路径并校验上传上限。
         *
         * @param allowedRoots 待授权目录
         * @param maxFileBytes 单文件最大字节数
         */
        public FileUploadPolicy {
            Objects.requireNonNull(allowedRoots, "allowedRoots");
            if (allowedRoots.isEmpty() || allowedRoots.size() > 32
                    || maxFileBytes < 1 || maxFileBytes > 64L * 1024 * 1024) {
                throw new ConfigurationException("FileUploadPolicy requires 1-32 roots and a 1-64MiB limit");
            }
            List<Path> roots = new ArrayList<>();
            for (Path root : allowedRoots) {
                Objects.requireNonNull(root, "allowed root");
                try {
                    Path realRoot = root.toRealPath();
                    if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
                        throw new ConfigurationException("Gradio file upload roots must be existing directories");
                    }
                    roots.add(realRoot);
                } catch (IOException error) {
                    throw new ConfigurationException("Unable to resolve a Gradio file upload root", error);
                }
            }
            allowedRoots = List.copyOf(roots);
        }
    }

}
