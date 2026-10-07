package org.fzb.smolagents.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ToolExecutionException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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

/**
 * 使用 DuckDuckGo、Bing RSS 或 Exa 搜索网页，并返回有序 Markdown 摘要。
 *
 * <p>Exa 需要 {@code EXA_API_KEY}；搜索 endpoint 和凭据均可显式配置，便于在测试或
 * 受控网络中替换服务。该工具与 {@link ApiWebSearchTool} 是互斥的 web_search 实现。</p>
 */
public final class WebSearchTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private static final URI DEFAULT_DUCKDUCKGO =
            URI.create("https://lite.duckduckgo.com/lite/");
    private static final URI DEFAULT_BING = URI.create("https://www.bing.com/search");
    private static final URI DEFAULT_EXA = URI.create("https://api.exa.ai/search");

    private final SearchEngine engine;
    private final int maxResults;
    private final URI endpoint;
    private final String exaApiKey;
    private final Duration timeout;
    private final HttpClient client;
    private final ToolSpec spec = new ToolSpec(
            "web_search",
            "Search the web and return result titles, links, and descriptions.",
            createSchema(),
            true
    );

    /**
     * 创建使用 DuckDuckGo 和默认结果数的搜索工具。
     */
    public WebSearchTool() {
        this(10, SearchEngine.DUCKDUCKGO);
    }

    /**
     * 使用指定结果数和搜索引擎创建工具。
     *
     * @param maxResults 最多返回的搜索结果数
     * @param engine 搜索引擎
     */
    public WebSearchTool(int maxResults, SearchEngine engine) {
        this(
                maxResults,
                engine,
                endpointFor(engine),
                null,
                DEFAULT_TIMEOUT
        );
    }

    /**
     * 使用自定义 endpoint、Exa 凭据和超时创建搜索工具。
     *
     * @param maxResults 最多返回的搜索结果数
     * @param engine 搜索引擎
     * @param endpoint 当前引擎使用的 HTTP endpoint
     * @param exaApiKey Exa API key；未提供时在调用时读取 {@code EXA_API_KEY}
     * @param timeout 单次 HTTP 请求时限
     */
    public WebSearchTool(
            int maxResults,
            SearchEngine engine,
            URI endpoint,
            String exaApiKey,
            Duration timeout
    ) {
        if (maxResults < 1) {
            throw new ConfigurationException("maxResults must be positive");
        }
        this.engine = Objects.requireNonNull(engine, "engine");
        this.endpoint = requireHttpEndpoint(endpoint);
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new ConfigurationException("timeout must be positive");
        }
        this.maxResults = maxResults;
        this.exaApiKey = exaApiKey;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * 返回 web_search 工具的参数定义。
     *
     * @return 要求 query 字符串的工具 schema
     */
    @Override
    public ToolSpec spec() {
        return spec;
    }

    /**
     * 调用配置的搜索引擎并格式化结果。
     *
     * @param arguments 已通过 schema 校验的 query 参数
     * @param context 当前工具调用上下文
     * @return 搜索结果 Markdown
     * @throws ToolExecutionException 网络、协议或无结果错误时抛出
     */
    @Override
    public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
        Objects.requireNonNull(context, "context");
        CancellationToken cancellation = context.cancellation();
        cancellation.throwIfCancelled();
        String query = arguments.path("query").asText();
        List<SearchResult> results = switch (engine) {
            case DUCKDUCKGO -> parseDuckDuckGo(get(buildQueryUri(query), cancellation), endpoint);
            case BING -> parseBing(get(buildQueryUri(query, "format", "rss"), cancellation));
            case EXA -> parseExa(postExa(query, cancellation));
        };
        if (results.isEmpty()) {
            throw new ToolExecutionException("No results found! Try a less restrictive/shorter query.");
        }
        return ToolResult.text(formatResults(results.stream().limit(maxResults).toList()));
    }

    /**
     * 发送无密钥 GET 搜索请求并检查 HTTP 状态。
     *
     * @param uri 搜索 URI
     * @param cancellation 当前调用取消令牌
     * @return UTF-8 response body
     */
    private String get(URI uri, CancellationToken cancellation) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "text/html,application/rss+xml,application/xml")
                .GET()
                .build();
        return send(request, cancellation);
    }

    /**
     * 发送 Exa JSON 请求并要求有效 API key。
     *
     * @param query 搜索文本
     * @param cancellation 当前调用取消令牌
     * @return UTF-8 response body
     */
    private String postExa(String query, CancellationToken cancellation) {
        String apiKey = exaApiKey == null || exaApiKey.isBlank()
                ? System.getenv("EXA_API_KEY")
                : exaApiKey;
        if (apiKey == null || apiKey.isBlank()) {
            throw new ConfigurationException("Missing EXA_API_KEY for Exa web search");
        }
        ObjectNode body = MAPPER.createObjectNode()
                .put("query", query)
                .put("numResults", maxResults);
        body.set("contents", MAPPER.createObjectNode().put("highlights", true));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("x-exa-integration", "smolagents")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return send(request, cancellation);
    }

    /**
     * 执行 HTTP 请求，统一处理中断、传输错误和非成功状态。
     *
     * @param request 已构造请求
     * @param cancellation 当前调用取消令牌
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
                throw new ToolExecutionException("Web search request failed with HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("Web search request was interrupted", error);
        } catch (IOException error) {
            throw new ToolExecutionException("Web search request failed", error);
        }
    }

    /**
     * 构造 DuckDuckGo 或 Bing GET 请求 URI。
     *
     * @param query 搜索文本
     * @return query 参数已编码的 endpoint
     */
    private URI buildQueryUri(String query) {
        return buildQueryUri(query, null, null);
    }

    /**
     * 构造包含 query 和可选附加参数的 GET 请求 URI。
     *
     * @param query 搜索文本
     * @param extraName 附加参数名
     * @param extraValue 附加参数值
     * @return 编码后的 endpoint URI
     */
    private URI buildQueryUri(String query, String extraName, String extraValue) {
        String separator = endpoint.getRawQuery() == null || endpoint.getRawQuery().isEmpty() ? "?" : "&";
        StringBuilder uri = new StringBuilder(endpoint.toASCIIString())
                .append(separator)
                .append("q=").append(encode(query));
        if (extraName != null) {
            uri.append('&').append(encode(extraName)).append('=').append(encode(extraValue));
        }
        return URI.create(uri.toString());
    }

    /**
     * 解析 DuckDuckGo Lite 页面中的标题、链接和摘要。
     *
     * @param html 搜索结果 HTML
     * @param baseUri 搜索 endpoint，用于解析相对链接
     * @return 搜索结果列表
     */
    private static List<SearchResult> parseDuckDuckGo(String html, URI baseUri) {
        Document document = Jsoup.parse(html, baseUri.toString());
        List<SearchResult> results = new ArrayList<>();
        for (Element link : document.select("a.result-link")) {
            Element row = link.closest("tr");
            String description = "";
            String resultUrl = "";
            if (row != null) {
                Element sibling = row;
                while ((sibling = sibling.nextElementSibling()) != null
                        && sibling.selectFirst("a.result-link") == null) {
                    Element snippet = sibling.selectFirst(".result-snippet");
                    if (snippet != null) {
                        description = snippet.text();
                    }
                    Element linkText = sibling.selectFirst(".link-text");
                    if (linkText != null && !linkText.text().isBlank()) {
                        resultUrl = "https://" + linkText.text().strip();
                        break;
                    }
                }
            }
            if (resultUrl.isBlank()) {
                resultUrl = link.absUrl("href");
            }
            if (resultUrl.isBlank()) {
                continue;
            }
            // DuckDuckGo Lite groups the title, snippet, and display URL into separate table rows.
            // Match the Python parser's accumulated row behavior while retaining an href fallback.
            results.add(new SearchResult(link.text(), resultUrl, description));
        }
        return List.copyOf(results);
    }

    /**
     * 安全解析 Bing RSS response，不解析外部实体或 DTD。
     *
     * @param xml RSS XML 内容
     * @return 搜索结果列表
     */
    private static List<SearchResult> parseBing(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))
            );
            var items = document.getElementsByTagName("item");
            List<SearchResult> results = new ArrayList<>();
            for (int index = 0; index < items.getLength(); index++) {
                var item = (org.w3c.dom.Element) items.item(index);
                String title = childText(item, "title");
                String url = childText(item, "link");
                String description = childText(item, "description");
                if (!title.isBlank() && !url.isBlank()) {
                    results.add(new SearchResult(title, url, Jsoup.parse(description).text()));
                }
            }
            return List.copyOf(results);
        } catch (ParserConfigurationException | org.xml.sax.SAXException | IOException error) {
            throw new ToolExecutionException("Bing returned invalid RSS XML", error);
        }
    }

    /**
     * 读取 XML item 中指定的直接子元素文本。
     *
     * @param parent RSS item element
     * @param tag 子元素标签
     * @return 子元素文本；缺失时为空
     */
    private static String childText(org.w3c.dom.Element parent, String tag) {
        var nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    /**
     * 解析 Exa JSON 结果及可选 highlights。
     *
     * @param body Exa JSON response
     * @return 搜索结果列表
     */
    private static List<SearchResult> parseExa(String body) {
        JsonNode results;
        try {
            results = MAPPER.readTree(body).path("results");
        } catch (JsonProcessingException error) {
            throw new ToolExecutionException("Exa returned invalid JSON", error);
        }
        if (!results.isArray()) {
            throw new ToolExecutionException("Exa response is missing results array");
        }
        List<SearchResult> parsed = new ArrayList<>();
        for (JsonNode result : results) {
            String url = result.path("url").asText("");
            if (url.isBlank()) {
                throw new ToolExecutionException("Exa result is missing its URL");
            }
            JsonNode highlights = result.path("highlights");
            List<String> descriptions = new ArrayList<>();
            if (highlights.isArray()) {
                highlights.forEach(item -> {
                    if (item.isTextual() && !item.asText().isBlank()) {
                        descriptions.add(item.asText().strip());
                    }
                });
            }
            parsed.add(new SearchResult(
                    result.path("title").asText(""),
                    url,
                    String.join(" ", descriptions)
            ));
        }
        return List.copyOf(parsed);
    }

    /**
     * 格式化搜索结果列表。
     *
     * @param results 搜索结果
     * @return Markdown 内容
     */
    private static String formatResults(List<SearchResult> results) {
        StringBuilder markdown = new StringBuilder("## Search Results\n\n");
        for (int index = 0; index < results.size(); index++) {
            SearchResult result = results.get(index);
            if (index > 0) {
                markdown.append("\n\n");
            }
            markdown.append("[")
                    .append(result.title())
                    .append("](")
                    .append(result.url())
                    .append(")\n")
                    .append(result.description());
        }
        return markdown.toString();
    }

    /**
     * UTF-8 编码 query 参数。
     *
     * @param value 原始文本
     * @return 编码结果
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 返回对应搜索引擎的默认 endpoint。
     *
     * @param engine 搜索引擎
     * @return HTTP endpoint
     */
    private static URI endpointFor(SearchEngine engine) {
        Objects.requireNonNull(engine, "engine");
        return switch (engine) {
            case DUCKDUCKGO -> DEFAULT_DUCKDUCKGO;
            case BING -> DEFAULT_BING;
            case EXA -> DEFAULT_EXA;
        };
    }

    /**
     * 校验 endpoint 必须为绝对 HTTP(S) 地址。
     *
     * @param endpoint 搜索服务地址
     * @return 校验后的 endpoint
     */
    private static URI requireHttpEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null) {
            throw new ConfigurationException("endpoint must be an absolute HTTP(S) URI");
        }
        return endpoint;
    }

    /**
     * 创建仅接受 query 参数的工具 schema。
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
     * 支持的网页搜索后端。
     */
    public enum SearchEngine {
        /** DuckDuckGo Lite HTML 搜索。 */
        DUCKDUCKGO,
        /** Bing RSS 搜索结果。 */
        BING,
        /** Exa Search API。 */
        EXA
    }

    /**
     * 保存搜索结果摘要。
     *
     * @param title 页面标题
     * @param url 页面 URL
     * @param description 页面摘要
     */
    private record SearchResult(String title, String url, String description) {
    }
}
