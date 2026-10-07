package org.fzb.smolagents.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ToolExecutionException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 使用 Brave Search API 或兼容端点查询网页并将结果格式化为 Markdown。
 *
 * <p>默认凭据从 {@code BRAVE_API_KEY} 环境变量读取。自定义 header、query 参数、
 * 速率限制和超时均可通过构造器配置；API key 只放入请求 header，不会进入 URL 或诊断消息。</p>
 */
public final class ApiWebSearchTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final URI DEFAULT_ENDPOINT =
            URI.create("https://api.search.brave.com/res/v1/web/search");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient httpClient;
    private final URI endpoint;
    private final Map<String, String> headers;
    private final Map<String, String> params;
    private final Duration timeout;
    private final Long minimumIntervalNanos;
    private final ToolSpec spec = new ToolSpec(
            "web_search",
            "Search the web and return result titles, URLs, and descriptions.",
            createSchema(),
            true
    );
    private long nextRequestNanos;

    /**
     * 使用 Brave Search API 默认端点和 {@code BRAVE_API_KEY} 环境变量创建工具。
     */
    public ApiWebSearchTool() {
        this(
                DEFAULT_ENDPOINT,
                System.getenv("BRAVE_API_KEY"),
                null,
                Map.of("count", "10"),
                1.0,
                DEFAULT_TIMEOUT
        );
    }

    /**
     * 使用自定义端点、认证信息和请求限制创建网页搜索工具。
     *
     * @param endpoint 搜索 API endpoint
     * @param apiKey API 密钥；当 headers 非空时可为 null，由调用方 header 提供认证
     * @param headers 自定义请求 header；为空时使用 Brave token header
     * @param params 除 query 外的请求参数，例如结果条数
     * @param rateLimit 每秒最大请求数；null 表示不限制
     * @param timeout 单次 HTTP 请求超时
     */
    public ApiWebSearchTool(
            URI endpoint,
            String apiKey,
            Map<String, String> headers,
            Map<String, String> params,
            Double rateLimit,
            Duration timeout
    ) {
        this.endpoint = requireHttpEndpoint(endpoint);
        this.timeout = requirePositive(timeout, "timeout");
        if (rateLimit != null && (!Double.isFinite(rateLimit) || rateLimit <= 0)) {
            throw new ConfigurationException("rateLimit must be finite and positive, or null");
        }
        if (headers == null || headers.isEmpty()) {
            if (apiKey == null || apiKey.isBlank()) {
                throw new ConfigurationException("Missing API key; set BRAVE_API_KEY or configure request headers");
            }
            this.headers = Map.of("X-Subscription-Token", apiKey);
        } else {
            this.headers = validatedCopy(headers, "headers");
        }
        this.params = validatedCopy(params == null ? Map.of() : params, "params");
        this.minimumIntervalNanos = rateLimit == null
                ? null
                : Math.max(1L, (long) (1_000_000_000.0 / rateLimit));
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    /**
     * 返回网页搜索工具定义。
     *
     * @return query 字符串参数及其描述
     */
    @Override
    public ToolSpec spec() {
        return spec;
    }

    /**
     * 发送搜索请求，并将网页结果转换为有序 Markdown。
     *
     * @param arguments 已通过 schema 校验的 query 参数
     * @param context 当前工具调用上下文
     * @return 搜索结果文本；无结果时返回明确提示
     * @throws ToolExecutionException 网络、HTTP 状态或响应格式错误时抛出
     */
    @Override
    public synchronized ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
        Objects.requireNonNull(context, "context");
        CancellationToken cancellation = context.cancellation();
        cancellation.throwIfCancelled();
        awaitRateLimit(cancellation);

        String query = arguments.path("query").asText();
        HttpRequest.Builder request = HttpRequest.newBuilder(buildRequestUri(query))
                .timeout(timeout)
                .GET();
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = httpClient.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            cancellation.throwIfCancelled();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ToolExecutionException("Web search request failed with HTTP " + response.statusCode());
            }
            return ToolResult.text(formatResults(parseResults(response.body())));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("Web search request was interrupted", error);
        } catch (IOException error) {
            throw new ToolExecutionException("Web search request failed", error);
        }
    }

    /**
     * 等待本地速率窗口并在等待期间响应取消请求。
     *
     * @param cancellation 当前工具调用的取消令牌
     */
    private void awaitRateLimit(CancellationToken cancellation) {
        if (minimumIntervalNanos == null) {
            return;
        }
        long remaining = nextRequestNanos - System.nanoTime();
        while (remaining > 0) {
            cancellation.throwIfCancelled();
            try {
                long sleepMillis = Math.max(1, Math.min(100, remaining / 1_000_000));
                Thread.sleep(sleepMillis);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ToolExecutionException("Interrupted while waiting for web search rate limit", error);
            }
            remaining = nextRequestNanos - System.nanoTime();
        }
        nextRequestNanos = System.nanoTime() + minimumIntervalNanos;
    }

    /**
     * 将 query 与静态请求参数合并为 endpoint URI。
     *
     * @param query 用户搜索文本
     * @return 已编码的请求 URI
     */
    private URI buildRequestUri(String query) {
        Map<String, String> requestParams = new LinkedHashMap<>(params);
        requestParams.put("q", query);
        StringBuilder uri = new StringBuilder(endpoint.toASCIIString());
        char separator = endpoint.getRawQuery() == null ? '?' : '&';
        for (Map.Entry<String, String> parameter : requestParams.entrySet()) {
            uri.append(separator)
                    .append(encode(parameter.getKey()))
                    .append('=')
                    .append(encode(parameter.getValue()));
            separator = '&';
        }
        return URI.create(uri.toString());
    }

    /**
     * 解析 Brave-compatible {@code web.results} 数组。
     *
     * @param body HTTP JSON response body
     * @return 标题、链接和描述组成的结果列表
     */
    private static java.util.List<SearchResult> parseResults(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JsonProcessingException error) {
            throw new ToolExecutionException("Web search returned invalid JSON", error);
        }
        JsonNode results = root.path("web").path("results");
        if (!results.isArray()) {
            throw new ToolExecutionException("Web search response is missing web.results");
        }
        java.util.List<SearchResult> parsed = new java.util.ArrayList<>();
        for (JsonNode result : results) {
            JsonNode title = result.get("title");
            JsonNode url = result.get("url");
            JsonNode description = result.get("description");
            if (title == null || !title.isTextual() || url == null || !url.isTextual()) {
                throw new ToolExecutionException("Web search result is missing a textual title or URL");
            }
            parsed.add(new SearchResult(
                    title.asText(),
                    url.asText(),
                    description != null && description.isTextual() ? description.asText() : ""
            ));
        }
        return java.util.List.copyOf(parsed);
    }

    /**
     * 将搜索结果渲染为模型可读的 Markdown。
     *
     * @param results 已解析结果
     * @return 结果列表或无结果提示
     */
    private static String formatResults(java.util.List<SearchResult> results) {
        if (results.isEmpty()) {
            return "No results found.";
        }
        StringBuilder output = new StringBuilder("## Search Results\n\n");
        for (int index = 0; index < results.size(); index++) {
            SearchResult result = results.get(index);
            if (index > 0) {
                output.append("\n\n");
            }
            output.append(index + 1)
                    .append(". [")
                    .append(result.title())
                    .append("](")
                    .append(result.url())
                    .append(")\n")
                    .append(result.description());
        }
        return output.toString();
    }

    /**
     * 对 URI query parameter 进行 UTF-8 表单编码。
     *
     * @param value 待编码文本
     * @return URI query component
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 校验并复制配置映射，拒绝空键和空值。
     *
     * @param source 输入映射
     * @param name 配置字段名
     * @return 不可变映射副本
     */
    private static Map<String, String> validatedCopy(Map<String, String> source, String name) {
        Objects.requireNonNull(source, name);
        Map<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                throw new ConfigurationException(name + " must not contain blank keys or null values");
            }
            copy.put(key, value);
        });
        return Map.copyOf(copy);
    }

    /**
     * 校验 HTTP endpoint scheme 和主机名。
     *
     * @param value 搜索服务 endpoint
     * @return 校验后的 endpoint
     */
    private static URI requireHttpEndpoint(URI value) {
        Objects.requireNonNull(value, "endpoint");
        if (!("http".equalsIgnoreCase(value.getScheme()) || "https".equalsIgnoreCase(value.getScheme()))
                || value.getHost() == null) {
            throw new ConfigurationException("endpoint must be an absolute HTTP(S) URI");
        }
        return value;
    }

    /**
     * 校验正时长配置。
     *
     * @param value 待校验时长
     * @param name 配置名称
     * @return 正时长
     */
    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new ConfigurationException(name + " must be positive");
        }
        return value;
    }

    /**
     * 创建仅接受 query 字符串的工具 schema。
     *
     * @return JSON Schema object
     */
    private static JsonNode createSchema() {
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", MAPPER.createObjectNode().set(
                "query",
                MAPPER.createObjectNode()
                        .put("type", "string")
                        .put("description", "The search query to perform.")
        ));
        schema.putArray("required").add("query");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 保存网页搜索的一条结构化结果。
     *
     * @param title 网页标题
     * @param url 网页 URL
     * @param description 网页摘要
     */
    private record SearchResult(String title, String url, String description) {
    }
}
