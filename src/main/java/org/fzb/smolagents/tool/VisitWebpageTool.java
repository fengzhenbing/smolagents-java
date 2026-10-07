package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ToolExecutionException;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 获取网页并将 HTML 主体转换为 Markdown 风格文本。
 *
 * <p>默认仅允许访问公网 HTTP(S) 地址，不跟随重定向；可通过显式构造参数允许
 * private/loopback 网络以支持受控部署和本地测试。该地址校验是纵深防御措施，不是
 * 完整的 SSRF 隔离边界。</p>
 */
public final class VisitWebpageTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private static final int DEFAULT_MAX_OUTPUT_LENGTH = 40_000;
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final Pattern EXCESSIVE_NEWLINES = Pattern.compile("\\n{3,}");

    private final HttpClient client;
    private final Duration timeout;
    private final int maxOutputLength;
    private final boolean allowPrivateAddresses;
    private final ToolSpec spec = new ToolSpec(
            "visit_webpage",
            "Visit a public HTTP(S) webpage and return its content as Markdown.",
            createSchema(),
            true
    );

    /**
     * 创建默认公网网页读取工具。
     */
    public VisitWebpageTool() {
        this(DEFAULT_MAX_OUTPUT_LENGTH, DEFAULT_TIMEOUT, false);
    }

    /**
     * 使用输出长度和 HTTP 超时配置创建工具。
     *
     * @param maxOutputLength 返回 Markdown 的最大字符数
     * @param timeout 单次 HTTP 请求时限
     */
    public VisitWebpageTool(int maxOutputLength, Duration timeout) {
        this(maxOutputLength, timeout, false);
    }

    /**
     * 使用完整请求策略创建网页读取工具。
     *
     * @param maxOutputLength 返回 Markdown 的最大字符数
     * @param timeout 单次 HTTP 请求时限
     * @param allowPrivateAddresses 是否允许 private、loopback 和 link-local 地址
     */
    public VisitWebpageTool(int maxOutputLength, Duration timeout, boolean allowPrivateAddresses) {
        if (maxOutputLength < 1) {
            throw new ConfigurationException("maxOutputLength must be positive");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new ConfigurationException("timeout must be positive");
        }
        this.maxOutputLength = maxOutputLength;
        this.timeout = timeout;
        this.allowPrivateAddresses = allowPrivateAddresses;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 返回网页读取工具定义。
     *
     * @return 要求 url 字符串参数的工具 schema
     */
    @Override
    public ToolSpec spec() {
        return spec;
    }

    /**
     * 获取网页主体并返回长度受限的 Markdown 文本。
     *
     * @param arguments 已通过 schema 校验的 url 参数
     * @param context 当前工具调用上下文
     * @return 清理和截断后的页面内容
     * @throws ToolExecutionException 地址、网络、HTTP 或内容错误时抛出
     */
    @Override
    public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
        Objects.requireNonNull(context, "context");
        CancellationToken cancellation = context.cancellation();
        cancellation.throwIfCancelled();
        URI target = parseTarget(arguments.path("url").asText());
        validateAddress(target);

        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(timeout)
                .header("User-Agent", "smolagents-java/1.0")
                .header("Accept", "text/html,text/plain;q=0.9")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            try (InputStream body = response.body()) {
                cancellation.throwIfCancelled();
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new ToolExecutionException(
                            "Webpage request failed with HTTP " + response.statusCode()
                    );
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/html")
                        && !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/plain")) {
                    throw new ToolExecutionException("Unsupported webpage content type: " + contentType);
                }
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new ToolExecutionException(
                            "Webpage response exceeds the maximum size of " + MAX_RESPONSE_BYTES + " bytes"
                    );
                }
                String content = new String(bytes, StandardCharsets.UTF_8);
                String markdown = contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/plain")
                        ? content.strip()
                        : toMarkdown(content, target);
                return ToolResult.text(truncate(markdown, maxOutputLength));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("Webpage request was interrupted", error);
        } catch (IOException error) {
            throw new ToolExecutionException("Webpage request failed", error);
        }
    }

    /**
     * 解析目标 URL 并拒绝非 HTTP(S)、凭据或无主机地址。
     *
     * @param value 模型提供的 URL
     * @return 已校验的绝对 HTTP(S) URI
     */
    private static URI parseTarget(String value) {
        URI target;
        try {
            target = URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new ToolExecutionException("Webpage URL is invalid", error);
        }
        if (!("http".equalsIgnoreCase(target.getScheme()) || "https".equalsIgnoreCase(target.getScheme()))
                || target.getHost() == null
                || target.getUserInfo() != null) {
            throw new ToolExecutionException("Webpage URL must be an absolute HTTP(S) URL without credentials");
        }
        return target;
    }

    /**
     * 默认拒绝解析到本机、私有或链路本地地址的目标。
     *
     * @param target 已解析的网页 URL
     */
    private void validateAddress(URI target) {
        if (allowPrivateAddresses) {
            return;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(target.getHost())) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new ToolExecutionException(
                            "Webpage URL resolves to a non-public network address"
                    );
                }
            }
        } catch (IOException error) {
            throw new ToolExecutionException("Unable to resolve webpage host", error);
        }
    }

    /**
     * 使用 Jsoup DOM 将常见 HTML 结构转换为 Markdown。
     *
     * @param html HTML response body
     * @param baseUri 页面原始地址，用于解析相对链接
     * @return Markdown 风格正文
     */
    private static String toMarkdown(String html, URI baseUri) {
        Element body = Jsoup.parse(html, baseUri.toString()).body();
        body.select("script,style,noscript,template,svg,canvas").remove();
        StringBuilder markdown = new StringBuilder();
        body.childNodes().forEach(node -> appendMarkdown(node, markdown, false));
        String normalized = EXCESSIVE_NEWLINES.matcher(markdown.toString()).replaceAll("\n\n");
        return normalized.replaceAll("[ \\t]+\\n", "\n").strip();
    }

    /**
     * 递归转换 HTML 节点；代码片段保留空白，其它文本折叠空格。
     *
     * @param node 当前 DOM 节点
     * @param output Markdown 输出缓冲区
     * @param inPreformattedBlock 当前是否位于 pre 元素中
     */
    private static void appendMarkdown(Node node, StringBuilder output, boolean inPreformattedBlock) {
        if (node instanceof TextNode textNode) {
            String text = textNode.getWholeText();
            output.append(inPreformattedBlock ? text : text.replaceAll("\\s+", " "));
            return;
        }
        if (!(node instanceof Element element)) {
            node.childNodes().forEach(child -> appendMarkdown(child, output, inPreformattedBlock));
            return;
        }
        String tag = element.normalName();
        switch (tag) {
            case "br" -> output.append('\n');
            case "hr" -> output.append("\n\n---\n\n");
            case "img" -> appendImage(element, output);
            case "pre" -> appendPreformatted(element, output);
            case "table" -> appendTable(element, output);
            case "h1", "h2", "h3", "h4", "h5", "h6" ->
                    appendBlock(element, output, "#".repeat(tag.charAt(1) - '0') + " ");
            case "p", "div", "section", "article", "header", "footer", "main" ->
                    appendBlock(element, output, "");
            case "ul", "ol" -> appendList(element, output);
            case "li" -> appendListItem(element, output);
            case "blockquote" -> appendBlockquote(element, output);
            case "strong", "b" -> appendEmphasis(element, output, "**");
            case "em", "i" -> appendEmphasis(element, output, "*");
            case "code" -> appendInlineCode(element, output);
            case "a" -> appendLink(element, output);
            default -> element.childNodes().forEach(child -> appendMarkdown(child, output, inPreformattedBlock));
        }
    }

    /**
     * 输出段落或标题块。
     *
     * @param element HTML element
     * @param output Markdown 输出缓冲区
     * @param prefix 标题前缀；普通段落为空字符串
     */
    private static void appendBlock(Element element, StringBuilder output, String prefix) {
        output.append("\n\n").append(prefix);
        element.childNodes().forEach(child -> appendMarkdown(child, output, false));
        output.append("\n\n");
    }

    /**
     * 转换无序或有序列表。
     *
     * @param element 列表元素
     * @param output Markdown 输出缓冲区
     */
    private static void appendList(Element element, StringBuilder output) {
        output.append('\n');
        int index = 1;
        for (Element child : element.children()) {
            if (!"li".equals(child.normalName())) {
                appendMarkdown(child, output, false);
                continue;
            }
            output.append("ol".equals(element.normalName()) ? index++ + ". " : "- ");
            child.childNodes().forEach(node -> appendMarkdown(node, output, false));
            output.append('\n');
        }
        output.append('\n');
    }

    /**
     * 转换嵌套列表中的单项。
     *
     * @param element 列表项元素
     * @param output Markdown 输出缓冲区
     */
    private static void appendListItem(Element element, StringBuilder output) {
        output.append("\n- ");
        element.childNodes().forEach(child -> appendMarkdown(child, output, false));
        output.append('\n');
    }

    /**
     * 将块引用转换为 Markdown 引用行。
     *
     * @param element 引用元素
     * @param output Markdown 输出缓冲区
     */
    private static void appendBlockquote(Element element, StringBuilder output) {
        StringBuilder quote = new StringBuilder();
        element.childNodes().forEach(child -> appendMarkdown(child, quote, false));
        output.append("\n\n> ").append(quote.toString().strip().replace("\n", "\n> ")).append("\n\n");
    }

    /**
     * 转换强调文本。
     *
     * @param element 强调元素
     * @param output Markdown 输出缓冲区
     * @param marker Markdown 强调标记
     */
    private static void appendEmphasis(Element element, StringBuilder output, String marker) {
        output.append(marker);
        element.childNodes().forEach(child -> appendMarkdown(child, output, false));
        output.append(marker);
    }

    /**
     * 转换 inline code。
     *
     * @param element code 元素
     * @param output Markdown 输出缓冲区
     */
    private static void appendInlineCode(Element element, StringBuilder output) {
        output.append('`');
        element.childNodes().forEach(child -> appendMarkdown(child, output, true));
        output.append('`');
    }

    /**
     * 转换链接；仅为 HTTP(S) URL 生成可点击 Markdown。
     *
     * @param element 链接元素
     * @param output Markdown 输出缓冲区
     */
    private static void appendLink(Element element, StringBuilder output) {
        StringBuilder label = new StringBuilder();
        element.childNodes().forEach(child -> appendMarkdown(child, label, false));
        String href = element.absUrl("href");
        if (href.isBlank() || !(href.startsWith("http://") || href.startsWith("https://"))) {
            output.append(label);
            return;
        }
        output.append('[').append(label.toString().strip()).append("](").append(href).append(')');
    }

    /**
     * 将图片节点转换为描述与绝对 URL。
     *
     * @param element 图片元素
     * @param output Markdown 输出缓冲区
     */
    private static void appendImage(Element element, StringBuilder output) {
        String source = element.absUrl("src");
        if (!source.isBlank() && (source.startsWith("https://") || source.startsWith("http://"))) {
            output.append("![").append(element.attr("alt")).append("](").append(source).append(')');
        }
    }

    /**
     * 保留代码块正文并添加 fenced code delimiters。
     *
     * @param element pre 元素
     * @param output Markdown 输出缓冲区
     */
    private static void appendPreformatted(Element element, StringBuilder output) {
        output.append("\n\n```\n").append(element.wholeText().strip()).append("\n```\n\n");
    }

    /**
     * 将简单表格转换为 Markdown 表格行。
     *
     * @param table 表格元素
     * @param output Markdown 输出缓冲区
     */
    private static void appendTable(Element table, StringBuilder output) {
        output.append("\n\n");
        boolean headerWritten = false;
        for (Element row : table.select("tr")) {
            output.append("|");
            for (Element cell : row.select("> th, > td")) {
                output.append(' ').append(cell.text().replace("|", "\\|")).append(" |");
            }
            output.append('\n');
            if (!headerWritten && !row.select("> th").isEmpty()) {
                int columns = row.select("> th").size();
                output.append("|");
                for (int index = 0; index < columns; index++) {
                    output.append(" --- |");
                }
                output.append('\n');
                headerWritten = true;
            }
        }
        output.append('\n');
    }

    /**
     * 截断过长输出并标注被截断状态。
     *
     * @param content 完整 Markdown
     * @param limit 最大输出字符数
     * @return 未截断内容或带提示的前缀
     */
    private static String truncate(String content, int limit) {
        if (content.length() <= limit) {
            return content;
        }
        return content.substring(0, limit)
                + "\n..._This content has been truncated to stay below " + limit + " characters_...\n";
    }

    /**
     * 创建仅接受 url 参数的工具 schema。
     *
     * @return JSON Schema object
     */
    private static JsonNode createSchema() {
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", MAPPER.createObjectNode().set(
                "url",
                MAPPER.createObjectNode()
                        .put("type", "string")
                        .put("description", "The URL of the webpage to visit.")
        ));
        schema.putArray("required").add("url");
        schema.put("additionalProperties", false);
        return schema;
    }
}
