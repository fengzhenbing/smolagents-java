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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 通过 SerpApi 或 Serper 查询 Google 自然搜索结果。
 *
 * <p>默认使用 SerpApi 与 {@code SERPAPI_API_KEY}；Serper 使用 {@code SERPER_API_KEY}。
 * 可显式传入密钥和 endpoint，便于安全装配与本地 stub 测试。</p>
 */
public final class GoogleSearchTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final URI SERPAPI_ENDPOINT = URI.create("https://serpapi.com/search.json");
    private static final URI SERPER_ENDPOINT = URI.create("https://google.serper.dev/search");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private final Provider provider;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient client;
    private final ToolSpec spec = new ToolSpec(
            "web_search",
            "Search Google for a query, optionally restricted to a publication year.",
            createSchema(),
            true
    );

    /**
     * 使用 SerpApi 和 {@code SERPAPI_API_KEY} 环境变量创建工具。
     */
    public GoogleSearchTool() {
        this(Provider.SERPAPI, null, null, DEFAULT_TIMEOUT);
    }

    /**
     * 使用指定搜索提供方和环境变量凭据创建工具。
     *
     * @param provider 搜索 API 提供方
     */
    public GoogleSearchTool(Provider provider) {
        this(provider, null, null, DEFAULT_TIMEOUT);
    }

    /**
     * 使用显式密钥、endpoint 和超时创建工具。
     *
     * @param provider 搜索 API 提供方
     * @param apiKey API 密钥；为空时读取该 provider 对应的环境变量
     * @param endpoint 搜索 API endpoint；为空时使用官方默认 endpoint
     * @param timeout HTTP 请求超时时长
     */
    public GoogleSearchTool(Provider provider, String apiKey, URI endpoint, Duration timeout) {
        this.provider = Objects.requireNonNull(provider, "provider");
        String resolvedKey = apiKey == null || apiKey.isBlank() ? System.getenv(provider.apiKeyEnvironment()) : apiKey;
        if (resolvedKey == null || resolvedKey.isBlank()) {
            throw new ConfigurationException(
                    "Missing API key. Set " + provider.apiKeyEnvironment() + " or provide apiKey."
            );
        }
        this.apiKey = resolvedKey;
        this.endpoint = requireHttpEndpoint(endpoint == null ? provider.defaultEndpoint() : endpoint);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new ConfigurationException("timeout must be positive");
        }
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * 返回 Google 搜索工具的 query 与可选年份过滤参数定义。
     *
     * @return JSON Schema 工具定义
     */
    @Override
    public ToolSpec spec() {
        return spec;
    }

    /**
     * 发起 Google 搜索并返回 Markdown 结果。
     *
     * @param arguments 已通过 schema 校验的 query 和 filter_year
     * @param context 当前工具调用上下文
     * @return 格式化搜索结果；合法但无结果时返回空结果提示
     * @throws ToolExecutionException 服务调用失败或响应结构无效时抛出
     */
    @Override
    public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
        Objects.requireNonNull(context, "context");
        CancellationToken cancellation = context.cancellation();
        cancellation.throwIfCancelled();
        String query = arguments.path("query").asText();
        Integer filterYear = parseFilterYear(arguments.get("filter_year"));
        HttpRequest request = HttpRequest.newBuilder(buildRequestUri(query, filterYear))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "smolagents-java/1.0")
                .GET()
                .build();
        String body = send(request, cancellation);
        JsonNode root = parseResponse(body);
        JsonNode results = root.get(provider.resultsField());
        if (results == null || !results.isArray()) {
            throw missingResults(query, filterYear);
        }
        if (results.isEmpty()) {
            String yearFilterMessage = filterYear == null ? "" : " with filter year=" + filterYear;
            return ToolResult.text(
                    "No results found for '" + query + "'" + yearFilterMessage
                            + ". Try with a more general query, or remove the year filter."
            );
        }
        return ToolResult.text(formatResults(parseResults(results)));
    }

    /**
     * 构造 provider 所需的 GET query 参数。
     *
     * @param query 搜索文本
     * @param filterYear 可选年份限制
     * @return 带 URL 编码查询参数的 endpoint
     */
    private URI buildRequestUri(String query, Integer filterYear) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", query);
        if (provider == Provider.SERPAPI) {
            params.put("api_key", apiKey);
            params.put("engine", "google");
            params.put("google_domain", "google.com");
        } else {
            params.put("api_key", apiKey);
        }
        if (filterYear != null) {
            params.put("tbs", "cdr:1,cd_min:01/01/" + filterYear + ",cd_max:12/31/" + filterYear);
        }
        StringBuilder url = new StringBuilder(endpoint.toASCIIString());
        boolean hasQuery = endpoint.getRawQuery() != null && !endpoint.getRawQuery().isEmpty();
        for (Map.Entry<String, String> param : params.entrySet()) {
            url.append(hasQuery ? '&' : '?')
                    .append(encode(param.getKey()))
                    .append('=')
                    .append(encode(param.getValue()));
            hasQuery = true;
        }
        return URI.create(url.toString());
    }

    /**
     * 发送 HTTP 请求并检查响应状态。
     *
     * @param request 已配置请求
     * @param cancellation 当前工具调用取消令牌
     * @return response body
     */
    private String send(HttpRequest request, CancellationToken cancellation) {
        try {
            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            cancellation.throwIfCancelled();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ToolExecutionException("Google search request failed with HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("Google search request was interrupted", error);
        } catch (IOException error) {
            throw new ToolExecutionException("Google search request failed", error);
        }
    }

    /**
     * 解析 provider 返回的 JSON object。
     *
     * @param body HTTP response body
     * @return JSON object
     */
    private JsonNode parseResponse(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                throw new ToolExecutionException("Google search response must be a JSON object");
            }
            return root;
        } catch (JsonProcessingException error) {
            throw new ToolExecutionException("Google search returned invalid JSON", error);
        }
    }

    /**
     * 提取每条自然搜索结果并校验必要字段。
     *
     * @param results provider 返回的结果数组
     * @return 有序结果列表
     */
    private static List<SearchResult> parseResults(JsonNode results) {
        List<SearchResult> parsed = new ArrayList<>();
        for (JsonNode page : results) {
            String title = page.path("title").asText("");
            String link = page.path("link").asText("");
            if (title.isBlank() || link.isBlank()) {
                throw new ToolExecutionException("Google search result is missing title or link");
            }
            parsed.add(new SearchResult(
                    title,
                    link,
                    optionalText(page, "date"),
                    optionalText(page, "source"),
                    optionalText(page, "snippet")
            ));
        }
        return List.copyOf(parsed);
    }

    /**
     * 读取可选文本字段。
     *
     * @param object 搜索结果对象
     * @param field 字段名
     * @return 字段文本；不存在或非文本时为空字符串
     */
    private static String optionalText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    /**
     * 格式化 SerpApi/Serper 结果，保留时间、来源和摘要字段。
     *
     * @param results 已校验搜索结果
     * @return Markdown 搜索结果文本
     */
    private static String formatResults(List<SearchResult> results) {
        StringBuilder output = new StringBuilder("## Search Results\n");
        for (int index = 0; index < results.size(); index++) {
            if (index > 0) {
                output.append('\n');
            }
            SearchResult result = results.get(index);
            output.append(index)
                    .append(". [")
                    .append(result.title())
                    .append("](")
                    .append(result.link())
                    .append(')');
            if (!result.date().isBlank()) {
                output.append("\nDate published: ").append(result.date());
            }
            if (!result.source().isBlank()) {
                output.append("\nSource: ").append(result.source());
            }
            output.append('\n').append(result.snippet());
        }
        return output.toString();
    }

    /**
     * 生成字段缺失时区分带年份过滤和普通查询的诊断错误。
     *
     * @param query 用户搜索词
     * @param filterYear 可选年份限制
     * @return 明确的无结果工具异常
     */
    private static ToolExecutionException missingResults(String query, Integer filterYear) {
        String qualifier = filterYear == null ? "" : " with filtering on year=" + filterYear;
        String suggestion = filterYear == null
                ? "Use a less restrictive query."
                : "Use a less restrictive query or do not filter on year.";
        return new ToolExecutionException(
                "No results found for query: '" + query + "'" + qualifier + ". " + suggestion
        );
    }

    /**
     * 校验可选年份参数必须为正整数。
     *
     * @param yearNode JSON year value
     * @return 年份；未指定或显式 null 时返回 null
     */
    private static Integer parseFilterYear(JsonNode yearNode) {
        if (yearNode == null || yearNode.isNull()) {
            return null;
        }
        if (!yearNode.isIntegralNumber() || !yearNode.canConvertToInt() || yearNode.asInt() < 1) {
            throw new ToolExecutionException("filter_year must be a positive integer or null");
        }
        return yearNode.asInt();
    }

    /**
     * 对 query 参数执行 UTF-8 URL 编码。
     *
     * @param value 参数值
     * @return 编码后的 query component
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 校验 endpoint 为 HTTP(S) 绝对 URI，且不包含嵌入凭据或片段。
     *
     * @param endpoint 搜索服务地址
     * @return 校验后的 endpoint
     */
    private static URI requireHttpEndpoint(URI endpoint) {
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
     * 创建必填 query 和可选 nullable filter_year schema。
     *
     * @return JSON Schema object
     */
    private static JsonNode createSchema() {
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        var properties = MAPPER.createObjectNode();
        properties.set("query", MAPPER.createObjectNode()
                .put("type", "string")
                .put("description", "The Google search query."));
        var yearSchema = MAPPER.createObjectNode();
        yearSchema.set("type", MAPPER.createArrayNode().add("integer").add("null"));
        yearSchema.put("description", "Optionally restrict results to a year.");
        properties.set("filter_year", yearSchema);
        schema.set("properties", properties);
        schema.putArray("required").add("query");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * SerpApi 和 Serper 查询返回的可选详情。
     *
     * @param title 页面标题
     * @param link 页面 URL
     * @param date 页面发布日期
     * @param source 页面来源
     * @param snippet 页面摘要
     */
    private record SearchResult(String title, String link, String date, String source, String snippet) {
    }

    /**
     * 支持的 Google 搜索 API 服务。
     */
    public enum Provider {
        /** SerpApi 的 Google organic results API。 */
        SERPAPI(SERPAPI_ENDPOINT, "SERPAPI_API_KEY", "organic_results"),
        /** Serper 的 Google search API。 */
        SERPER(SERPER_ENDPOINT, "SERPER_API_KEY", "organic");

        private final URI defaultEndpoint;
        private final String apiKeyEnvironment;
        private final String resultsField;

        Provider(URI defaultEndpoint, String apiKeyEnvironment, String resultsField) {
            this.defaultEndpoint = defaultEndpoint;
            this.apiKeyEnvironment = apiKeyEnvironment;
            this.resultsField = resultsField;
        }

        private URI defaultEndpoint() {
            return defaultEndpoint;
        }

        private String apiKeyEnvironment() {
            return apiKeyEnvironment;
        }

        private String resultsField() {
            return resultsField;
        }
    }
}
