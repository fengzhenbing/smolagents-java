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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 使用本机 Chrome DevTools Protocol 执行视觉网页浏览动作并返回页面截图。
 *
 * <p>默认要求 Chrome executable 显式配置，headless 默认关闭；页面仅支持受限的
 * HTTP(S) 公网导航，不暴露任意 JavaScript 执行接口。CDP websocket 只连接本机临时
 * debugging port。该浏览器仍以当前操作系统用户运行，不是操作系统安全沙箱。</p>
 */
public final class VisualBrowserTool implements Tool, AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_COMMAND_LINE_CHARS = 16_384;
    private static final int MAX_SCREENSHOT_BYTES = 12 * 1024 * 1024;
    private static final int MAX_PAGE_TEXT_CHARS = 16_000;
    private static final Pattern CSS_SELECTOR_CONTROL = Pattern.compile("[\\p{Cntrl}]");
    private static final Map<String, Integer> KEY_CODES = Map.ofEntries(
            Map.entry("Enter", 13),
            Map.entry("Tab", 9),
            Map.entry("Escape", 27),
            Map.entry("Backspace", 8),
            Map.entry("Delete", 46),
            Map.entry("ArrowDown", 40),
            Map.entry("ArrowUp", 38),
            Map.entry("ArrowLeft", 37),
            Map.entry("ArrowRight", 39),
            Map.entry("Home", 36),
            Map.entry("End", 35),
            Map.entry("PageDown", 34),
            Map.entry("PageUp", 33),
            Map.entry("Space", 32)
    );

    private final Duration timeout;
    private final boolean headless;
    private final boolean allowPrivateAddresses;
    private final ToolSpec spec = new ToolSpec(
            "visual_browser",
            "Navigate a public webpage, perform a visible browser action, and return the current screenshot.",
            createSchema(),
            true
    );
    private final BrowserBackend backend;

    /**
     * 创建一个使用显式 Chrome binary、可见窗口和公网 URL 限制的本机浏览器工具。
     *
     * @param chromeExecutable 本机 Chrome/Chromium executable 完整路径
     */
    public VisualBrowserTool(Path chromeExecutable) {
        this(chromeExecutable, Duration.ofSeconds(30), false, false);
    }

    /**
     * 创建一个以 Chrome DevTools Protocol 驱动的本机浏览器工具。
     *
     * @param chromeExecutable 本机 Chrome/Chromium executable 完整路径
     * @param timeout 浏览器动作最长时间
     * @param headless 是否无窗口启动；false 用于实际观察浏览器
     * @param allowPrivateAddresses 是否显式允许 private/loopback/link-local 目标
     */
    public VisualBrowserTool(
            Path chromeExecutable,
            Duration timeout,
            boolean headless,
            boolean allowPrivateAddresses
    ) {
        if (chromeExecutable == null || !Files.isRegularFile(chromeExecutable)) {
            throw new ConfigurationException("A valid Chrome/Chromium executable path is required");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new ConfigurationException("Browser timeout must be positive");
        }
        this.timeout = timeout;
        this.headless = headless;
        this.allowPrivateAddresses = allowPrivateAddresses;
        this.backend = new CdpBrowserBackend(
                chromeExecutable.toAbsolutePath().normalize(),
                timeout,
                headless,
                allowPrivateAddresses
        );
    }

    /**
     * 使用替代 backend 构造工具，供不启动外部浏览器的确定性测试使用。
     *
     * @param timeout 单次浏览器动作时限
     * @param allowPrivateAddresses 是否允许 private 地址
     * @param backend 可注入浏览器动作实现
     */
    VisualBrowserTool(
            Duration timeout,
            boolean allowPrivateAddresses,
            BrowserBackend backend
    ) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new ConfigurationException("Browser timeout must be positive");
        }
        this.timeout = timeout;
        this.headless = true;
        this.allowPrivateAddresses = allowPrivateAddresses;
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /**
     * 返回受限浏览器动作的模型可见 schema。
     *
     * @return browser action 工具定义
     */
    @Override
    public ToolSpec spec() {
        return spec;
    }

    /**
     * 执行一个固定浏览器动作并返回当前页面状态。
     *
     * @param arguments action и его валидированные параметры
     * @param context текущий run/cancellation context
     * @return информация о странице и screenshot image part
     */
    @Override
    public synchronized ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
        Objects.requireNonNull(context, "context");
        context.cancellation().throwIfCancelled();
        if (context.cancellation().isCancelled()) {
            throw new ToolExecutionException("Browser action was cancelled");
        }
        String actionName = arguments.path("action").asText("");
        BrowserAction action = parseAction(arguments);
        if ("navigate".equals(actionName)) {
            validateTarget(action.url(), context.cancellation());
        }
        BrowserResult result;
        try {
            result = backend.execute(action, timeout, context.cancellation());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("Browser action was interrupted", error);
        } catch (IOException error) {
            throw new ToolExecutionException("Chrome DevTools request failed", error);
        }
        String summary = "Browser action: " + actionName
                + "\nURL: " + result.url()
                + (result.title().isBlank() ? "" : "\nTitle: " + result.title())
                + (result.pageText().isBlank() ? "" : "\nVisible text:\n" + result.pageText());
        if (result.screenshot() == null) {
            return ToolResult.text(summary);
        }
        return new ToolResult(List.of(
                new ContentPart.Text(summary),
                new ContentPart.Image(null, "image/png", result.screenshot())
        ), null);
    }

    /**
     * 关闭 Chromium 进程、CDP websocket 和本工具创建的临时 profile。
     */
    @Override
    public synchronized void close() {
        backend.close();
    }

    /**
     * 构造单动作、额外参数拒绝的工具输入 schema。
     *
     * @return browser action object schema
     */
    private static JsonNode createSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        ArrayNode actions = MAPPER.createArrayNode();
        List.of("navigate", "click", "type", "press", "scroll", "back", "screenshot", "read_text",
                "search_text", "wait", "close_popups").forEach(actions::add);
        properties.set("action", MAPPER.createObjectNode().put("type", "string")
                .put("description", "Single browser action. Each call returns a screenshot when available.")
                .set("enum", actions));
        properties.set("url", MAPPER.createObjectNode().put("type", "string")
                .put("description", "Absolute public HTTP(S) URL required by navigate."));
        properties.set("selector", MAPPER.createObjectNode().put("type", "string")
                .put("description", "CSS selector of an element to click or type into."));
        properties.set("text", MAPPER.createObjectNode().put("type", "string")
                .put("description", "Text to enter into the selected input."));
        properties.set("key", MAPPER.createObjectNode().put("type", "string")
                .put("description", "Supported key: Enter, Tab, Escape, Backspace, Delete, arrows, Home, End, PageUp, PageDown, Space."));
        properties.set("scrollY", MAPPER.createObjectNode().put("type", "integer")
                .put("description", "Vertical scroll distance; clamped to +/- 5000 pixels."));
        properties.set("milliseconds", MAPPER.createObjectNode().put("type", "integer")
                .put("description", "Wait duration for wait action; clamped to 0-5000 milliseconds."));
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("action");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 校验浏览器动作名、字符串输入和有界数值参数。
     *
     * @param arguments 模型或调用方提供的参数对象
     * @return 已归一化的动作记录
     */
    private static BrowserAction parseAction(JsonNode arguments) {
        String name = arguments.path("action").asText("");
        if (!List.of("navigate", "click", "type", "press", "scroll", "back", "screenshot",
                "read_text", "search_text", "wait", "close_popups").contains(name)) {
            throw new ToolExecutionException("Unknown browser action");
        }
        String url = readBoundedText(arguments, "url");
        String selector = readBoundedText(arguments, "selector");
        String text = readBoundedText(arguments, "text");
        String key = readBoundedText(arguments, "key");
        int scrollY = arguments.path("scrollY").canConvertToInt()
                ? Math.max(-5_000, Math.min(5_000, arguments.path("scrollY").asInt()))
                : 0;
        int waitMillis = arguments.path("milliseconds").canConvertToInt()
                ? Math.max(0, Math.min(5_000, arguments.path("milliseconds").asInt()))
                : 0;
        if (("click".equals(name) || "type".equals(name)) && selector.isBlank()) {
            throw new ToolExecutionException("Browser click/type actions require a CSS selector");
        }
        if ("type".equals(name) && text.isEmpty()) {
            throw new ToolExecutionException("Browser type action requires non-empty text");
        }
        if ("navigate".equals(name) && url.isBlank()) {
            throw new ToolExecutionException("Browser navigate action requires a URL");
        }
        if ("press".equals(name) && !KEY_CODES.containsKey(key)) {
            throw new ToolExecutionException("Browser key is not in the supported keyboard allowlist");
        }
        if ("search_text".equals(name) && text.isBlank()) {
            throw new ToolExecutionException("Browser search_text action requires non-empty text");
        }
        if ("scroll".equals(name) && scrollY == 0) {
            throw new ToolExecutionException("Browser scroll action requires a non-zero scrollY");
        }
        if (!selector.isBlank() && CSS_SELECTOR_CONTROL.matcher(selector).find()) {
            throw new ToolExecutionException("Browser selector contains control characters");
        }
        return new BrowserAction(name, url, selector, text, key, scrollY, waitMillis);
    }

    /**
     * 读取可选的有界字符串字段。
     *
     * @param arguments 输入参数对象
     * @param field 字段名
     * @return 空字段返回空字符串
     */
    private static String readBoundedText(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        if (!value.isTextual() || value.asText().length() > MAX_COMMAND_LINE_CHARS) {
            throw new ToolExecutionException("Browser " + field + " must be a string up to 16384 characters");
        }
        return value.asText();
    }

    /**
     * 校验初始导航地址的协议、凭据和 DNS 解析地址。
     *
     * @param rawUrl 模型请求的绝对 URL
     * @param cancellation 当前运行的取消信号
     */
    private void validateTarget(String rawUrl, CancellationToken cancellation) {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException error) {
            throw new ToolExecutionException("Browser URL is invalid", error);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new ToolExecutionException("Browser URL must be an absolute HTTP(S) URL without credentials");
        }
        if (allowPrivateAddresses) {
            return;
        }
        cancellation.throwIfCancelled();
        if (!isPublicHost(uri.getHost())) {
            throw new ToolExecutionException("Browser URL resolves to a non-public address or cannot be resolved");
        }
    }

    /**
     * 判断域名解析的所有地址均为公网单播地址。
     *
     * @param host URL 中的主机名
     * @return 所有解析地址都可公开路由时为 true
     */
    private static boolean isPublicHost(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    return false;
                }
            }
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    /**
     * 一个不暴露任意 JavaScript 执行能力的有限 browser action。
     *
     * @param name 动作名称
     * @param url 可选导航 URL
     * @param selector 可选 CSS selector
     * @param text 可选输入文本
     * @param key 可选 allowlisted key
     * @param scrollY 有符号滚动像素
     * @param waitMillis wait action 延迟
     */
    record BrowserAction(
            String name,
            String url,
            String selector,
            String text,
            String key,
            int scrollY,
            int waitMillis
    ) {
    }

    /**
     * 页面状态和可选的可视化 PNG。
     *
     * @param url 当前浏览器地址
     * @param title 页面标题
     * @param pageText 有界页面可见文本
     * @param screenshot 有界 PNG 字节
     */
    record BrowserResult(String url, String title, String pageText, byte[] screenshot) {
        BrowserResult {
            url = url == null ? "" : url;
            title = title == null ? "" : title;
            pageText = pageText == null ? "" : pageText;
            if (screenshot != null && screenshot.length > MAX_SCREENSHOT_BYTES) {
                throw new ToolExecutionException("Browser screenshot exceeds the maximum byte size");
            }
            screenshot = screenshot == null ? null : screenshot.clone();
        }

        /**
         * 返回防御性复制的截图字节，避免调用方修改记录内部状态。
         *
         * @return PNG bytes；无截图时为 null
         */
        @Override
        public byte[] screenshot() {
            return screenshot == null ? null : screenshot.clone();
        }
    }

    /**
     * Chrome 或确定性测试替身的浏览器动作接口。
     */
    interface BrowserBackend extends AutoCloseable {
        /**
         * 运行一个浏览器动作并返回当前页面状态。
         *
         * @param action 已校验浏览器动作
         * @param timeout 当前动作 timeout
         * @param cancellation 取消令牌
         * @return 当前页面及可选截图
         * @throws IOException CDP/浏览器调用失败
         * @throws InterruptedException 调用线程被中断
         */
        BrowserResult execute(BrowserAction action, Duration timeout, CancellationToken cancellation)
                throws IOException, InterruptedException;

        /**
         * 关闭 backend 持有的浏览器及相关资源。
         */
        @Override
        void close();
    }

    private static final class CdpBrowserBackend implements BrowserBackend {
        private static final ObjectMapper CDP_MAPPER = new ObjectMapper();
        private final Path chromeExecutable;
        private final Duration startupTimeout;
        private final boolean headless;
        private final boolean allowPrivateAddresses;
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final AtomicLong requestIds = new AtomicLong();
        private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
        private final StringBuilder websocketMessage = new StringBuilder();
        private final Object websocketSendLock = new Object();
        private volatile String lastLoadedLoaderId = "";
        private Process chromeProcess;
        private Path userDataDir;
        private WebSocket websocket;
        private URI devToolsHttpBase;

        /**
         * 保存浏览器启动参数，不在工具构造时启动外部进程。
         *
         * @param chromeExecutable Chrome/Chromium executable
         * @param startupTimeout 建立 DevTools 会话的超时
         * @param headless 是否 headless 启动
         * @param allowPrivateAddresses 是否允许导航至 private 地址
         */
        private CdpBrowserBackend(
                Path chromeExecutable,
                Duration startupTimeout,
                boolean headless,
                boolean allowPrivateAddresses
        ) {
            this.chromeExecutable = chromeExecutable;
            this.startupTimeout = startupTimeout;
            this.headless = headless;
            this.allowPrivateAddresses = allowPrivateAddresses;
        }

        /**
         * 启动或复用 CDP 会话并执行一个已校验动作。
         *
         * @param action 单个浏览器动作
         * @param timeout 动作超时
         * @param cancellation 运行取消令牌
         * @return 当前页面文本和 PNG 截图
         * @throws IOException CDP/HTTP 通信失败
         * @throws InterruptedException 线程被中断
         */
        @Override
        public synchronized BrowserResult execute(
                BrowserAction action,
                Duration timeout,
                CancellationToken cancellation
        ) throws IOException, InterruptedException {
            cancellation.throwIfCancelled();
            ensureStarted(timeout);
            if ("navigate".equals(action.name())) {
                JsonNode navigation = call(
                        "Page.navigate",
                        MAPPER.createObjectNode().put("url", action.url()),
                        timeout
                );
                String navigationError = navigation.path("errorText").asText("");
                if (!navigationError.isBlank()) {
                    throw new ToolExecutionException("Browser navigation failed: " + navigationError);
                }
                awaitPageLoad(timeout, cancellation, navigation.path("loaderId").asText(""));
            } else if ("click".equals(action.name())) {
                ObjectNode params = MAPPER.createObjectNode().put("expression",
                        "(function(){const e=document.querySelector(" + jsString(action.selector())
                                + ");if(!e)throw new Error('selector not found');e.scrollIntoView({block:'center'});"
                                + "const r=e.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2};})()")
                        .put("returnByValue", true);
                JsonNode point = call("Runtime.evaluate", params, timeout)
                        .path("result").path("value");
                double x = point.path("x").asDouble(Double.NaN);
                double y = point.path("y").asDouble(Double.NaN);
                if (!Double.isFinite(x) || !Double.isFinite(y)) {
                    throw new ToolExecutionException("Browser click selector did not resolve to an element");
                }
                clickAt(x, y, timeout);
            } else if ("type".equals(action.name())) {
                call("Runtime.evaluate", MAPPER.createObjectNode().put("expression",
                                "(function(){const e=document.querySelector(" + jsString(action.selector())
                                        + ");if(!e)throw new Error('selector not found');e.focus();"
                                        + "if('value' in e){e.value='';e.dispatchEvent(new Event('input',{bubbles:true}));}"
                                        + "})()").put("returnByValue", true),
                        timeout);
                call("Input.insertText", MAPPER.createObjectNode().put("text", action.text()), timeout);
            } else if ("press".equals(action.name())) {
                dispatchKey(action.key(), timeout);
            } else if ("scroll".equals(action.name())) {
                call("Runtime.evaluate", MAPPER.createObjectNode()
                        .put("expression", "window.scrollBy(0," + action.scrollY() + ")"), timeout);
            } else if ("back".equals(action.name())) {
                call("Runtime.evaluate", MAPPER.createObjectNode().put("expression", "history.back()"), timeout);
                awaitPageLoad(timeout, cancellation);
            } else if ("close_popups".equals(action.name())) {
                dispatchKey("Escape", timeout);
            } else if ("wait".equals(action.name())) {
                waitForAction(action.waitMillis(), timeout, cancellation);
            } else if ("search_text".equals(action.name())) {
                JsonNode found = call("Runtime.evaluate", MAPPER.createObjectNode()
                                .put("expression", searchTextExpression(action.text()))
                                .put("returnByValue", true),
                                timeout).path("result").path("value");
                pageSearchSummary = "Text search matches: " + found.path("count").asInt();
            }
            cancellation.throwIfCancelled();
            BrowserResult current = currentPage(timeout, cancellation);
            if (!pageSearchSummary.isBlank()) {
                current = new BrowserResult(current.url(), current.title(),
                        pageSearchSummary + "\n" + current.pageText(), current.screenshot());
                pageSearchSummary = "";
            }
            return current;
        }

        private String pageSearchSummary = "";

        /**
         * 以短间隔检查取消令牌的方式等待指定时长。
         *
         * @param waitMillis 等待毫秒数
         * @param timeout 当前动作的最大时长
         * @param cancellation 运行取消令牌
         * @throws InterruptedException 当前线程被中断
         */
        private void waitForAction(int waitMillis, Duration timeout, CancellationToken cancellation)
                throws InterruptedException {
            long deadline = System.nanoTime() + Math.min(
                    TimeUnit.MILLISECONDS.toNanos(waitMillis),
                    timeout.toNanos()
            );
            while (System.nanoTime() < deadline) {
                cancellation.throwIfCancelled();
                long remaining = deadline - System.nanoTime();
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
            }
        }

        /**
         * 创建只检查页面文本节点、不会拼接成可执行脚本的搜索表达式。
         *
         * @param text 要搜索的可见文本片段
         * @return 将查询文本 JSON 编码后的有限 JavaScript 表达式
         */
        private static String searchTextExpression(String text) {
            String encodedText = jsString(text);
            return "(function(){const q=" + encodedText
                    + ";const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);let n,count=0,first=null;"
                    + "while(n=walker.nextNode()){if(n.nodeValue.includes(q)){count++;if(!first)first=n.parentElement;}}"
                    + "if(first)first.scrollIntoView({block:'center'});return {count};})()";
        }

        /**
         * 创建独占临时 profile 的 Chrome 进程并初始化 CDP 页面域。
         *
         * @param timeout 当前动作等待预算
         * @throws IOException Chrome 启动或 DevTools 握手失败
         * @throws InterruptedException 启动等待被中断
         */
        private void ensureStarted(Duration timeout) throws IOException, InterruptedException {
            if (websocket != null && chromeProcess != null && chromeProcess.isAlive()) {
                return;
            }
            stopBrowser();
            int port;
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
                port = socket.getLocalPort();
            }
            userDataDir = Files.createTempDirectory("smolagents-chrome-profile-");
            List<String> command = new java.util.ArrayList<>(List.of(
                    chromeExecutable.toString(),
                    "--remote-debugging-address=127.0.0.1",
                    "--remote-debugging-port=" + port,
                    "--user-data-dir=" + userDataDir,
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--disable-background-networking",
                    "--disable-background-mode",
                    "--disable-component-update",
                    "--disable-sync",
                    "--disable-breakpad",
                    "--disable-extensions",
                    "--window-size=1000,900",
                    "--window-position=0,0"
            ));
            if (headless) {
                command.add("--headless=new");
            }
            command.add("about:blank");
            chromeProcess = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            devToolsHttpBase = URI.create("http://127.0.0.1:" + port);
            URI jsonVersion = devToolsHttpBase.resolve("/json/version");
            Duration allowedStartup = timeout.compareTo(startupTimeout) < 0 ? timeout : startupTimeout;
            long deadline = System.nanoTime() + allowedStartup.toNanos();
            boolean debuggerReady = false;
            while (System.nanoTime() < deadline) {
                if (!chromeProcess.isAlive()) {
                    throw new IOException("Chrome exited during startup");
                }
                try {
                    HttpResponse<String> response = httpClient.send(
                            HttpRequest.newBuilder(jsonVersion).timeout(Duration.ofSeconds(2)).GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                    );
                    if (response.statusCode() == 200) {
                        JsonNode version = CDP_MAPPER.readTree(response.body());
                        String browserSocket = version.path("webSocketDebuggerUrl").asText("");
                        if (browserSocket.isBlank()) {
                            throw new IOException("Chrome version response is missing its DevTools WebSocket URL");
                        }
                        debuggerReady = true;
                        break;
                    }
                } catch (IOException ignored) {
                    // Chrome takes a short time to open its loopback DevTools endpoint.
                }
                Thread.sleep(100);
            }
            if (!debuggerReady) {
                stopBrowser();
                throw new IOException("Chrome DevTools endpoint did not become ready before timeout");
            }
            JsonNode targets = sendHttp("/json/list", timeout);
            String pageSocket = null;
            for (JsonNode target : targets) {
                if ("page".equals(target.path("type").asText())) {
                    pageSocket = target.path("webSocketDebuggerUrl").asText(null);
                    break;
                }
            }
            if (pageSocket == null) {
                throw new IOException("Chrome did not expose an initial page target");
            }
            websocket = connectWebSocket(URI.create(pageSocket), timeout);
            call("Page.enable", MAPPER.createObjectNode(), timeout);
            call("Page.setLifecycleEventsEnabled", MAPPER.createObjectNode().put("enabled", true), timeout);
            call("Runtime.enable", MAPPER.createObjectNode(), timeout);
            call("Fetch.enable", MAPPER.createObjectNode()
                    .set("patterns", MAPPER.createArrayNode().add(
                            MAPPER.createObjectNode().put("urlPattern", "*")
                    )), timeout);
        }

        /**
         * 使用 JDK WebSocket client 建立本机 DevTools 连接。
         *
         * @param endpoint Chrome 提供的本机 CDP WebSocket URI
         * @param timeout 最长握手时间
         * @return 已连接的 WebSocket
         * @throws IOException 握手失败或超时
         * @throws InterruptedException 连接等待被中断
         */
        private WebSocket connectWebSocket(URI endpoint, Duration timeout)
                throws IOException, InterruptedException {
            try {
                return httpClient.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(endpoint, new CdpListener())
                        .get(Math.min(timeout.toMillis(), 15_000), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException error) {
                throw new IOException("Unable to connect to Chrome DevTools WebSocket", error);
            }
        }

        /**
         * 读取本机 DevTools HTTP endpoint 的有界 JSON 响应。
         *
         * @param path endpoint 相对路径
         * @param timeout HTTP 请求时限
         * @return 解析后的 JSON
         * @throws IOException HTTP 或 JSON 解码失败
         * @throws InterruptedException 请求等待被中断
         */
        private JsonNode sendHttp(String path, Duration timeout) throws IOException, InterruptedException {
            URI listUri = devToolsHttpBase.resolve(path);
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(listUri).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Chrome DevTools endpoint returned HTTP " + response.statusCode());
            }
            try {
                return CDP_MAPPER.readTree(response.body());
            } catch (JsonProcessingException error) {
                throw new IOException("Chrome DevTools returned invalid JSON", error);
            }
        }

        /**
         * 发送 CDP 命令并等待具有相同 request ID 的响应。
         *
         * @param method CDP 方法名
         * @param params 命令参数
         * @param timeout 发送及响应等待时限
         * @return 命令 result 对象
         * @throws IOException CDP 连接或命令失败
         * @throws InterruptedException 等待被中断
         */
        private JsonNode call(String method, ObjectNode params, Duration timeout)
                throws IOException, InterruptedException {
            Throwable priorFailure = asynchronousFailure.get();
            if (priorFailure != null) {
                throw new IOException("Chrome DevTools asynchronous request interception failed", priorFailure);
            }
            WebSocket socket = websocket;
            if (socket == null) {
                throw new IOException("Chrome DevTools WebSocket is not connected");
            }
            long id = requestIds.incrementAndGet();
            CompletableFuture<JsonNode> response = new CompletableFuture<>();
            pending.put(id, response);
            String request = MAPPER.createObjectNode().put("id", id)
                    .put("method", method).set("params", params).toString();
            try {
                sendText(socket, request, timeout);
                JsonNode body = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (body.has("error")) {
                    throw new ToolExecutionException(
                            "Chrome DevTools " + method + " failed: " + body.path("error").path("message").asText()
                    );
                }
                return body.path("result");
            } catch (ExecutionException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                throw new IOException("Chrome DevTools command failed", cause);
            } catch (TimeoutException error) {
                pending.remove(id);
                throw new IOException("Chrome DevTools command timed out: " + method, error);
            } finally {
                pending.remove(id);
            }
        }

        /**
         * 串行发送 CDP WebSocket 消息；JDK WebSocket 不允许前一次 send 未完成时并发发送。
         *
         * @param socket 当前 Chrome DevTools WebSocket
         * @param message 单个完整 JSON frame
         * @param timeout 最长发送等待时间
         * @throws IOException WebSocket send 失败或超时
         * @throws InterruptedException 当前线程被中断
         */
        private void sendText(WebSocket socket, String message, Duration timeout)
                throws IOException, InterruptedException {
            synchronized (websocketSendLock) {
                try {
                    socket.sendText(message, true).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (ExecutionException | TimeoutException error) {
                    throw new IOException("Unable to send Chrome DevTools WebSocket message", error);
                }
            }
        }

        /**
         * 等待指定 CDP loader 的 load 生命周期事件，避免过早读取旧页面。
         *
         * @param timeout 页面 load 等待时限
         * @param cancellation 运行取消令牌
         * @param loaderId Page.navigate 返回的 loader 标识；空值时使用 readyState 兜底
         * @throws IOException CDP 就绪状态查询失败
         * @throws InterruptedException 当前线程被中断
         */
        private void awaitPageLoad(Duration timeout, CancellationToken cancellation, String loaderId)
                throws IOException, InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            if (!loaderId.isBlank()) {
                while (System.nanoTime() < deadline) {
                    cancellation.throwIfCancelled();
                    if (loaderId.equals(lastLoadedLoaderId)) {
                        return;
                    }
                    Thread.sleep(25);
                }
                throw new ToolExecutionException("Browser page did not finish loading before timeout");
            }
            while (System.nanoTime() < deadline) {
                cancellation.throwIfCancelled();
                JsonNode ready = call("Runtime.evaluate", MAPPER.createObjectNode()
                        .put("expression", "({state:document.readyState,url:location.href})")
                        .put("returnByValue", true), Duration.ofSeconds(2))
                        .path("result").path("value");
                if ("complete".equals(ready.path("state").asText())
                        && !"about:blank".equals(ready.path("url").asText())) {
                    return;
                }
                Thread.sleep(100);
            }
            throw new ToolExecutionException("Browser page did not finish loading before timeout");
        }

        /**
         * 对不产生 loader ID 的历史返回操作使用文档就绪状态兜底等待。
         *
         * @param timeout 操作等待时限
         * @param cancellation 运行取消令牌
         * @throws IOException Runtime.evaluate 通信失败
         * @throws InterruptedException 当前线程被中断
         */
        private void awaitPageLoad(Duration timeout, CancellationToken cancellation)
                throws IOException, InterruptedException {
            awaitPageLoad(timeout, cancellation, "");
        }

        /**
         * 按 CSS selector 的视口中心坐标发送鼠标按下和释放事件。
         *
         * @param x 视口水平坐标
         * @param y 视口垂直坐标
         * @param timeout CDP 命令时限
         * @throws IOException CDP 命令失败
         * @throws InterruptedException 命令等待被中断
         */
        private void clickAt(double x, double y, Duration timeout) throws IOException, InterruptedException {
            call("Input.dispatchMouseEvent", MAPPER.createObjectNode()
                    .put("type", "mousePressed").put("x", x).put("y", y).put("button", "left").put("clickCount", 1),
                    timeout);
            call("Input.dispatchMouseEvent", MAPPER.createObjectNode()
                    .put("type", "mouseReleased").put("x", x).put("y", y).put("button", "left").put("clickCount", 1),
                    timeout);
        }

        /**
         * 发送 allowlist 中指定按键的 keyDown/keyUp 事件。
         *
         * @param key 支持的按键名
         * @param timeout CDP 命令时限
         * @throws IOException CDP 命令失败
         * @throws InterruptedException 命令等待被中断
         */
        private void dispatchKey(String key, Duration timeout) throws IOException, InterruptedException {
            int code = KEY_CODES.get(key);
            call("Input.dispatchKeyEvent", MAPPER.createObjectNode()
                    .put("type", "keyDown").put("key", " ".equals(key) ? " " : key).put("windowsVirtualKeyCode", code),
                    timeout);
            call("Input.dispatchKeyEvent", MAPPER.createObjectNode()
                    .put("type", "keyUp").put("key", " ".equals(key) ? " " : key).put("windowsVirtualKeyCode", code),
                    timeout);
        }

        /**
         * 读取当前页面的 URL、标题、有限可见文本和 PNG 截图。
         *
         * @param timeout CDP 查询时限
         * @param cancellation 运行取消令牌
         * @return 当前页面 snapshot
         * @throws IOException CDP 查询或截图解码失败
         * @throws InterruptedException 当前线程被中断
         */
        private BrowserResult currentPage(Duration timeout, CancellationToken cancellation)
                throws IOException, InterruptedException {
            cancellation.throwIfCancelled();
            JsonNode page = call("Runtime.evaluate", MAPPER.createObjectNode()
                            .put("expression", "({url:location.href,title:document.title,text:(document.body?.innerText||'').slice(0,"
                                    + MAX_PAGE_TEXT_CHARS + ")})")
                            .put("returnByValue", true),
                    timeout).path("result").path("value");
            String currentUrl = page.path("url").asText("");
            String title = page.path("title").asText("");
            String text = page.path("text").asText("");
            JsonNode screenshot = call("Page.captureScreenshot", MAPPER.createObjectNode()
                    .put("format", "png")
                    .put("captureBeyondViewport", false), timeout);
            String encoded = screenshot.path("data").asText("");
            byte[] png;
            try {
                png = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException error) {
                throw new IOException("Chrome returned invalid screenshot bytes", error);
            }
            if (png.length > MAX_SCREENSHOT_BYTES) {
                throw new ToolExecutionException("Browser screenshot exceeds the maximum byte size");
            }
            return new BrowserResult(currentUrl, title, text, png);
        }

        /**
         * 将字符串序列化为可安全嵌入表达式的 JavaScript 字符串字面量。
         *
         * @param value 原始文本
         * @return JSON 编码字面量
         */
        private static String jsString(String value) {
            try {
                return MAPPER.writeValueAsString(value);
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Unable to encode browser selector", error);
            }
        }

        /**
         * 按需关闭 WebSocket、Chrome 进程树并删除临时用户目录。
         */
        @Override
        public synchronized void close() {
            stopBrowser();
        }

        /**
         * 释放本工具拥有的浏览器进程、连接和磁盘资源。
         */
        private void stopBrowser() {
            if (websocket != null) {
                websocket.sendClose(WebSocket.NORMAL_CLOSURE, "close").orTimeout(1, TimeUnit.SECONDS)
                        .exceptionally(error -> null);
                websocket = null;
            }
            pending.values().forEach(future -> future.cancel(true));
            pending.clear();
            if (chromeProcess != null) {
                List<ProcessHandle> chromeChildren = chromeProcess.toHandle().descendants().toList();
                chromeChildren.forEach(ProcessHandle::destroyForcibly);
                chromeProcess.destroy();
                try {
                    if (!chromeProcess.waitFor(2, TimeUnit.SECONDS)) {
                        chromeProcess.destroyForcibly();
                        chromeProcess.waitFor(2, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    chromeProcess.destroyForcibly();
                } finally {
                    chromeChildren.forEach(ProcessHandle::destroyForcibly);
                }
                chromeProcess = null;
            }
            if (userDataDir != null) {
                Path profile = userDataDir;
                try {
                    deleteProfileWithRetry(profile);
                } catch (IOException error) {
                    throw new ToolExecutionException("Unable to clean temporary Chrome profile", error);
                } finally {
                    userDataDir = null;
                }
            }
        }

        /**
         * 删除 Chrome 用户目录；Windows 文件索引/数据库句柄关闭可能稍晚于进程退出。
         *
         * @param profile 临时浏览器用户目录
         * @throws IOException 重试后仍无法删除目录或当前线程被中断
         */
        private static void deleteProfileWithRetry(Path profile) throws IOException {
            IOException lastError = null;
            for (int attempt = 0; attempt < 8; attempt++) {
                try (Stream<Path> paths = Files.walk(profile)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                    return;
                } catch (IOException error) {
                    lastError = error;
                    if (attempt == 7) {
                        break;
                    }
                    try {
                        Thread.sleep(100L * (attempt + 1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while cleaning temporary Chrome profile", interrupted);
                    }
                }
            }
            throw lastError;
        }

        private final class CdpListener implements WebSocket.Listener {
            /**
             * 请求接收第一条 CDP WebSocket frame。
             *
             * @param webSocket CDP WebSocket
             */
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            /**
             * 路由 CDP 响应、页面 load 事件及 Fetch 子请求拦截事件。
             *
             * @param webSocket CDP WebSocket
             * @param data 当前 frame 数据
             * @param last 当前 message 是否完整
             * @return 处理完成后的回调阶段
             */
            @Override
            public synchronized java.util.concurrent.CompletionStage<?> onText(
                    WebSocket webSocket,
                    CharSequence data,
                    boolean last
            ) {
                websocketMessage.append(data);
                if (last) {
                    try {
                        JsonNode response = MAPPER.readTree(websocketMessage.toString());
                        if ("Fetch.requestPaused".equals(response.path("method").asText())) {
                            dispatchPausedRequest(webSocket, response.path("params"));
                            return requestNextFrame(webSocket);
                        }
                        if ("Page.lifecycleEvent".equals(response.path("method").asText())) {
                            JsonNode params = response.path("params");
                            if ("load".equals(params.path("name").asText())) {
                                lastLoadedLoaderId = params.path("loaderId").asText("");
                            }
                        }
                        JsonNode id = response.get("id");
                        if (id != null && id.canConvertToLong()) {
                            CompletableFuture<JsonNode> future = pending.get(id.longValue());
                            if (future != null) {
                                future.complete(response);
                            }
                        }
                    } catch (JsonProcessingException error) {
                        pending.values().forEach(future -> future.completeExceptionally(error));
                    } finally {
                        websocketMessage.setLength(0);
                    }
                }
                return requestNextFrame(webSocket);
            }

            /**
             * 请求读取下一条 WebSocket frame。
             *
             * @param webSocket CDP WebSocket
             * @return 已完成的监听阶段
             */
            private CompletableFuture<?> requestNextFrame(WebSocket webSocket) {
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }

            /**
             * 拦截并允许/拒绝一个页面发起的网络请求。
             *
             * @param webSocket CDP WebSocket
             * @param params Fetch.requestPaused 事件参数
             */
            private void dispatchPausedRequest(WebSocket webSocket, JsonNode params) {
                String requestId = params.path("requestId").asText("");
                String rawUrl = params.path("request").path("url").asText("");
                if (requestId.isBlank()) {
                    asynchronousFailure.compareAndSet(
                            null,
                            new IllegalStateException("Chrome Fetch interception event omitted requestId")
                    );
                    webSocket.abort();
                    return;
                }
                Thread.ofVirtual().start(() -> {
                    boolean allowed = isAllowedNetworkRequest(rawUrl);
                    ObjectNode commandParams = MAPPER.createObjectNode().put("requestId", requestId);
                    String method;
                    if (allowed) {
                        method = "Fetch.continueRequest";
                    } else {
                        method = "Fetch.failRequest";
                        commandParams.put("errorReason", "BlockedByClient");
                    }
                    ObjectNode command = MAPPER.createObjectNode()
                            .put("id", requestIds.incrementAndGet())
                            .put("method", method)
                            .set("params", commandParams);
                    try {
                        sendText(webSocket, command.toString(), Duration.ofSeconds(10));
                    } catch (IOException | InterruptedException error) {
                        if (error instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        asynchronousFailure.compareAndSet(null, error);
                        webSocket.abort();
                    }
                });
            }

            /**
             * 校验资源请求协议和地址，防止页面加载内网/本机资源。
             *
             * @param rawUrl 页面请求 URL
             * @return 允许继续访问时为 true
             */
            private boolean isAllowedNetworkRequest(String rawUrl) {
                try {
                    URI uri = URI.create(rawUrl);
                    String scheme = uri.getScheme();
                    if ("data".equalsIgnoreCase(scheme) || "blob".equalsIgnoreCase(scheme)) {
                        return true;
                    }
                    if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                            || uri.getHost() == null || uri.getUserInfo() != null) {
                        return false;
                    }
                    return allowPrivateAddresses || isPublicHost(uri.getHost());
                } catch (IllegalArgumentException error) {
                    return false;
                }
            }

            /**
             * 使等待中的 CDP 命令观察到 WebSocket 异步错误。
             *
             * @param webSocket 已失效连接
             * @param error WebSocket 错误
             */
            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                pending.values().forEach(future -> future.completeExceptionally(error));
            }
        }
    }
}
