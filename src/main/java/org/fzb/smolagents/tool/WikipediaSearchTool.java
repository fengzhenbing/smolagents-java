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
 * 通过 MediaWiki Action API 读取指定 Wikipedia 页面的摘要或全文。
 *
 * <p>请求使用显式 User-Agent，默认不需要 API key。语言代码、响应内容类型和提取格式
 * 均在构造时校验；外部网络或协议错误会作为工具执行异常抛出。</p>
 */
public final class WikipediaSearchTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private static final String DEFAULT_USER_AGENT =
            "smolagents-java/1.0 (https://github.com/fengzhenbing/smolagents)";

    private final HttpClient client;
    private final URI endpoint;
    private final String language;
    private final ContentType contentType;
    private final ExtractFormat extractFormat;
    private final Duration timeout;
    private final ToolSpec spec = new ToolSpec(
            "wikipedia_search",
            "Read the summary or full text of a Wikipedia topic and return its page URL.",
            createSchema(),
            true
    );

    /**
     * 使用英文 Wikipedia、全文和 WIKI 提取格式创建工具。
     */
    public WikipediaSearchTool() {
        this(DEFAULT_USER_AGENT, "en", ContentType.TEXT, ExtractFormat.WIKI);
    }

    /**
     * 使用指定语言、内容长度和提取格式创建 Wikipedia 工具。
     *
     * @param userAgent 符合 Wikimedia 政策的客户端标识
     * @param language Wikipedia 语言代码，例如 {@code en} 或 {@code es}
     * @param contentType 返回摘要或全文
     * @param extractFormat 返回 WIKI 文本或 HTML
     */
    public WikipediaSearchTool(
            String userAgent,
            String language,
            ContentType contentType,
            ExtractFormat extractFormat
    ) {
        this(
                userAgent,
                language,
                contentType,
                extractFormat,
                URI.create("https://" + language + ".wikipedia.org/w/api.php"),
                DEFAULT_TIMEOUT
        );
    }

    /**
     * 使用自定义 MediaWiki API endpoint 和请求时限创建工具。
     *
     * @param userAgent 符合 Wikimedia 政策的客户端标识
     * @param language Wikipedia 语言代码
     * @param contentType 返回摘要或全文
     * @param extractFormat 返回 WIKI 文本或 HTML
     * @param endpoint MediaWiki Action API endpoint
     * @param timeout 单次 HTTP 请求超时
     */
    public WikipediaSearchTool(
            String userAgent,
            String language,
            ContentType contentType,
            ExtractFormat extractFormat,
            URI endpoint,
            Duration timeout
    ) {
        if (userAgent == null || userAgent.isBlank()
                || userAgent.chars().anyMatch(Character::isISOControl)) {
            throw new ConfigurationException("userAgent must be non-blank and contain no control characters");
        }
        if (language == null || !language.matches("[a-z]{2,3}(?:-[a-z0-9]+)?")) {
            throw new ConfigurationException("language must be a valid Wikipedia language code");
        }
        this.endpoint = requireHttpEndpoint(endpoint);
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new ConfigurationException("timeout must be positive");
        }
        this.language = language;
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.extractFormat = Objects.requireNonNull(extractFormat, "extractFormat");
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.userAgent = userAgent;
    }

    private final String userAgent;

    /**
     * 返回 Wikipedia 查询工具定义。
     *
     * @return 要求 query 文本参数的工具 schema
     */
    @Override
    public ToolSpec spec() {
        return spec;
    }

    /**
     * 请求 Wikipedia 页面并返回标题、正文及规范页面 URL。
     *
     * @param arguments 已通过 schema 校验的 query 参数
     * @param context 当前工具调用上下文
     * @return Wikipedia 页面或未找到页面提示
     * @throws ToolExecutionException HTTP 或响应协议错误时抛出
     */
    @Override
    public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
        Objects.requireNonNull(context, "context");
        CancellationToken cancellation = context.cancellation();
        cancellation.throwIfCancelled();
        HttpRequest request = HttpRequest.newBuilder(buildRequestUri(arguments.path("query").asText()))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            cancellation.throwIfCancelled();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ToolExecutionException(
                        "Wikipedia API returned HTTP " + response.statusCode() + " for language " + language
                );
            }
            return ToolResult.text(formatPage(arguments.path("query").asText(), parsePage(response.body())));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("Wikipedia request was interrupted", error);
        } catch (IOException error) {
            throw new ToolExecutionException("Wikipedia request failed", error);
        }
    }

    /**
     * 构造获取 page extract 与规范 URL 的 MediaWiki 请求。
     *
     * @param title 用户提供的页面标题
     * @return URL 编码后的 Action API URI
     */
    private URI buildRequestUri(String title) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("action", "query");
        params.put("prop", "extracts|info");
        params.put("inprop", "url");
        params.put("explaintext", extractFormat == ExtractFormat.WIKI ? "1" : "0");
        if (contentType == ContentType.SUMMARY) {
            params.put("exintro", "1");
        }
        params.put("titles", title);
        params.put("redirects", "1");
        params.put("format", "json");
        params.put("formatversion", "2");
        StringBuilder query = new StringBuilder(endpoint.toASCIIString());
        query.append(endpoint.getRawQuery() == null ? '?' : '&');
        params.forEach((key, value) -> query.append(encode(key))
                .append('=')
                .append(encode(value))
                .append('&'));
        query.setLength(query.length() - 1);
        return URI.create(query.toString());
    }

    /**
     * 从 MediaWiki response 中读取首个页面记录。
     *
     * @param body JSON 响应
     * @return 页面标题、正文和 URL；页面不存在时返回 null
     */
    private static Page parsePage(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JsonProcessingException error) {
            throw new ToolExecutionException("Wikipedia API returned invalid JSON", error);
        }
        JsonNode pages = root.path("query").path("pages");
        if (!pages.isArray()) {
            throw new ToolExecutionException("Wikipedia response is missing query.pages");
        }
        if (pages.isEmpty() || pages.get(0).has("missing")) {
            return null;
        }
        JsonNode page = pages.get(0);
        JsonNode title = page.get("title");
        JsonNode url = page.get("fullurl");
        JsonNode extract = page.get("extract");
        if (title == null || !title.isTextual()
                || url == null || !url.isTextual()
                || extract == null || !extract.isTextual()) {
            throw new ToolExecutionException("Wikipedia page response is missing title, fullurl, or extract");
        }
        return new Page(title.asText(), url.asText(), extract.asText());
    }

    /**
     * 生成与 Python 工具相近的页面结果格式。
     *
     * @param query 原始查询文本
     * @param page 解析出的页面；null 表示未找到
     * @return Markdown 页面描述或未找到提示
     */
    private static String formatPage(String query, Page page) {
        if (page == null) {
            return "No Wikipedia page found for '" + query + "'. Try a different query.";
        }
        return "✅ **Wikipedia Page:** " + page.title()
                + "\n\n**Content:** " + page.extract()
                + "\n\n🔗 **Read more:** " + page.url();
    }

    /**
     * 编码 URI query 参数。
     *
     * @param value 待编码内容
     * @return UTF-8 query component
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 校验 MediaWiki API 必须使用 HTTP 或 HTTPS。
     *
     * @param value API endpoint
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
     * 构造只接受 query 参数的工具 schema。
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
                        .put("description", "The topic to search on Wikipedia.")
        ));
        schema.putArray("required").add("query");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 控制提取摘要或完整页面内容。
     */
    public enum ContentType {
        /** 返回页面简介。 */
        SUMMARY,
        /** 返回页面全文。 */
        TEXT
    }

    /**
     * 控制 MediaWiki 提取正文的表示形式。
     */
    public enum ExtractFormat {
        /** 返回纯文本 Wiki 内容。 */
        WIKI,
        /** 保留 MediaWiki 返回的 HTML。 */
        HTML
    }

    /**
     * 保存 Wikipedia 页面的展示字段。
     *
     * @param title 页面标题
     * @param url 页面规范 URL
     * @param extract 摘要或正文
     */
    private record Page(String title, String url, String extract) {
    }
}
