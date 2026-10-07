package org.fzb.smolagents.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.fzb.smolagents.agent.Agent;
import org.fzb.smolagents.agent.MemoryBackedAgent;
import org.fzb.smolagents.core.AgentInput;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.RunOptions;
import org.fzb.smolagents.event.AgentEvent;
import org.fzb.smolagents.memory.AgentMemory;
import org.fzb.smolagents.memory.AgentMemoryCodec;
import org.fzb.smolagents.memory.ActionStep;
import org.fzb.smolagents.memory.FinalAnswerStep;
import org.fzb.smolagents.memory.PlanningStep;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.ModelStreamEvent;
import org.fzb.smolagents.model.TokenUsage;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.net.URLDecoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 为预先配置的 Agent 提供 loopback JSON/SSE HTTP API 和有界会话历史。
 *
 * <p>服务仅允许绑定本机回环地址；可额外要求 bearer token。同步工作以运行事件 SSE
 * 输出，断开连接时取消订阅和当前 Agent 运行。此 HTTP adapter 不启动、不配置模型，也不
 * 改变传入 Agent 的代码执行安全边界。</p>
 */
public final class AgentHttpServer implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 单次输入请求 body 上限。 */
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    /** SSE 二进制输出片段的最大内嵌尺寸。 */
    private static final int MAX_MEDIA_BYTES = 2 * 1024 * 1024;
    /** 单个 SSE 事件的最大编码字节数。 */
    private static final int MAX_EVENT_BYTES = 4 * 1024 * 1024;
    /** Agent 事件 subscriber 完成标记。 */
    private static final Object STREAM_END = new Object();
    /** 单个服务实例可回放的最近历史事件数。 */
    private static final int MAX_HISTORY_EVENTS = 512;
    /** 每个会话历史的最大序列化大小；媒体 base64 在保存前会移除。 */
    private static final long MAX_HISTORY_BYTES = 4L * 1024 * 1024;
    /** 多会话 HTTP server 的最大 Agent 会话数。 */
    private static final int MAX_SESSIONS = 32;
    /** 单个持久会话状态文件的最大编码尺寸。 */
    private static final long MAX_PERSISTED_SESSION_BYTES = 22L * 1024 * 1024;
    private static final String DEFAULT_SESSION_ID = "default";
    private static final String UI_HTML = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>smolagents Java</title>
              <style>
                body { font: 16px system-ui, sans-serif; max-width: 900px; margin: 2rem auto; padding: 0 1rem; }
                label { display: block; margin: 1rem 0 .4rem; }
                input, textarea { box-sizing: border-box; width: 100%; padding: .7rem; }
                textarea { min-height: 8rem; }
                button { margin: .8rem .5rem .8rem 0; padding: .6rem 1rem; }
                #output { white-space: pre-wrap; min-height: 8rem; padding: 1rem; background: #f3f4f6; }
                #output img, #output audio { display: block; max-width: 100%; margin: .8rem 0; }
              </style>
              <script src="/app.js" defer></script>
            </head>
            <body>
              <h1>smolagents Java</h1>
              <p>仅用于已绑定 loopback 的预配置 Agent；代码执行安全边界由服务端 Agent 决定。</p>
              <form id="run-form">
                <label for="token">可选 API bearer token</label>
                <input id="token" type="password" autocomplete="off">
                <label for="task">任务</label>
                <textarea id="task" required></textarea>
                <button id="run" type="submit">运行</button>
                <button id="cancel" type="button" disabled>取消</button>
              </form>
              <div>
                <label for="session">会话</label>
                <select id="session"></select>
                <button id="new-session" type="button">新建会话</button>
                <button id="delete-session" type="button">删除会话</button>
                <button id="history" type="button">加载会话历史</button>
              </div>
              <h2>会话历史与运行事件</h2>
              <div id="output" aria-live="polite"></div>
            </body>
            </html>
            """;
    private static final String UI_SCRIPT = """
            const form = document.querySelector("#run-form");
            const task = document.querySelector("#task");
            const token = document.querySelector("#token");
            const output = document.querySelector("#output");
            const runButton = document.querySelector("#run");
            const cancelButton = document.querySelector("#cancel");
            const historyButton = document.querySelector("#history");
            const sessionSelect = document.querySelector("#session");
            const newSessionButton = document.querySelector("#new-session");
            const deleteSessionButton = document.querySelector("#delete-session");
            let activeController = null;
            let sessionId = null;
            let canCreateSessions = false;
            const authHeaders = () => token.value ? { Authorization: "Bearer " + token.value } : {};
            function appendText(value) {
              output.append(document.createTextNode(value));
            }
            function renderAgentValue(value) {
              if (!value || !Array.isArray(value.content)) {
                appendText(JSON.stringify(value));
                return;
              }
              for (const part of value.content) {
                if (part.type === "text") {
                  appendText(part.text || "");
                } else if (part.type === "json") {
                  appendText(JSON.stringify(part.value));
                } else if (part.type === "image" || part.type === "audio") {
                  const mime = part.mimeType || "";
                  const allowlistedMime = part.type === "image"
                    ? /^image\\/(png|jpeg|gif|webp)$/i.test(mime)
                    : /^audio\\/(wav|x-wav|mpeg|ogg|webm|mp4|flac)$/i.test(mime);
                  if (allowlistedMime && /^[A-Za-z0-9+/]+={0,2}$/.test(part.base64 || "")
                      && part.base64.length <= 3 * 1024 * 1024) {
                    const media = document.createElement(part.type === "image" ? "img" : "audio");
                    media.src = `data:${mime};base64,${part.base64}`;
                    if (part.type === "image") media.alt = "Agent image output";
                    else media.controls = true;
                    output.append(media);
                  } else {
                    appendText(part.binaryOmitted ? `\\n[${part.type} data omitted from history]`
                      : `\\n[${part.type} output is not embedded]`);
                  }
                  if (part.transcript) appendText(`\\n${part.transcript}`);
                }
              }
              if (value.structuredContent !== undefined && value.structuredContent !== null) {
                appendText(`\\n${JSON.stringify(value.structuredContent)}`);
              }
            }
            function renderEvent(eventName, value) {
              if (eventName === "user_task") appendText(`\\n\\nYou: ${value.task}\\n`);
              else if (eventName === "model_delta" && value.delta?.type === "text") appendText(value.delta.text);
              else if (eventName === "run_completed") {
                appendText(`\\n\\n[${value.status}] `);
                renderAgentValue(value.output);
              } else if (eventName === "step_completed" && Array.isArray(value.observations)) {
                for (const observation of value.observations) {
                  appendText(`\\n[${observation.toolName}] `);
                  renderAgentValue(observation.result);
                }
              } else if (eventName === "run_failed") appendText(`\\n\\n[failed] ${value.error}`);
              else if (eventName === "server_error") appendText(`\\n\\n[server error] ${value.error}`);
              else if (eventName === "tool_started") appendText(`\\n[tool] ${value.toolName}`);
              else if (eventName === "tool_failed") appendText(`\\n[tool error] ${value.error}`);
            }
            async function refreshSessions(selectedId) {
              const response = await fetch("/api/sessions", { headers: authHeaders() });
              if (!response.ok) throw new Error(`Session request failed: HTTP ${response.status}`);
              canCreateSessions = response.headers.get("X-Session-Creation-Enabled") === "true";
              const sessions = await response.json();
              sessionSelect.replaceChildren();
              for (const session of sessions) {
                const option = document.createElement("option");
                option.value = session.id;
                option.textContent = session.title || session.id;
                sessionSelect.append(option);
              }
              const selected = sessions.find(item => item.id === selectedId)
                || sessions.find(item => item.id === sessionId)
                || sessions[0];
              sessionId = selected?.id || null;
              if (sessionId) sessionSelect.value = sessionId;
              newSessionButton.disabled = !canCreateSessions || activeController !== null;
              deleteSessionButton.disabled = sessions.length <= 1;
              return sessions;
            }
            async function loadHistory() {
              if (!sessionId) return;
              const response = await fetch(
                "/api/history?sessionId=" + encodeURIComponent(sessionId),
                { headers: authHeaders() }
              );
              if (!response.ok) throw new Error(`History request failed: HTTP ${response.status}`);
              const items = await response.json();
              output.replaceChildren();
              for (const item of items) renderEvent(item.event, item.data);
            }
            historyButton.addEventListener("click", async () => {
              try { await loadHistory(); }
              catch (error) { output.textContent += `\\n[history error] ${error.message}`; }
            });
            sessionSelect.addEventListener("change", async () => {
              sessionId = sessionSelect.value;
              try { await loadHistory(); }
              catch (error) { output.textContent = `[history error] ${error.message}`; }
            });
            newSessionButton.addEventListener("click", async () => {
              if (activeController) return;
              try {
                const response = await fetch("/api/sessions", {
                  method: "POST",
                  headers: authHeaders()
                });
                if (!response.ok) throw new Error(`Session creation failed: HTTP ${response.status}`);
                const created = await response.json();
                await refreshSessions(created.id);
                output.replaceChildren();
                appendText("[new conversation]");
              } catch (error) {
                output.replaceChildren();
                appendText(`[session error] ${error.message}`);
              }
            });
            deleteSessionButton.addEventListener("click", async () => {
              if (activeController || !sessionId || !window.confirm("删除当前会话及其历史？")) return;
              try {
                const response = await fetch("/api/sessions/" + encodeURIComponent(sessionId), {
                  method: "DELETE",
                  headers: authHeaders()
                });
                if (!response.ok) throw new Error(`Session deletion failed: HTTP ${response.status}`);
                output.replaceChildren();
                await refreshSessions();
                await loadHistory();
              } catch (error) {
                output.replaceChildren();
                appendText(`[session error] ${error.message}`);
              }
            });
            token.addEventListener("change", () => {
              refreshSessions().then(loadHistory)
                .catch(error => {
                  output.replaceChildren();
                  appendText(`[session error] ${error.message}`);
                });
            });
            refreshSessions().then(loadHistory)
              .catch(error => {
                output.replaceChildren();
                appendText(`[session error] ${error.message}`);
              });
            form.addEventListener("submit", async (event) => {
              event.preventDefault();
              if (!sessionId) {
                output.replaceChildren();
                appendText("[session error] No active session");
                return;
              }
              activeController = new AbortController();
              runButton.disabled = true;
              cancelButton.disabled = false;
              sessionSelect.disabled = true;
              newSessionButton.disabled = true;
              deleteSessionButton.disabled = true;
              const headers = { "Content-Type": "application/json", ...authHeaders() };
              try {
                const response = await fetch("/api/run/stream", {
                  method: "POST",
                  headers,
                  body: JSON.stringify({ task: task.value, sessionId }),
                  signal: activeController.signal
                });
                if (!response.ok) throw new Error(`HTTP ${response.status}: ${await response.text()}`);
                const reader = response.body.getReader();
                const decoder = new TextDecoder();
                let buffer = "";
                while (true) {
                  const part = await reader.read();
                  buffer += decoder.decode(part.value || new Uint8Array(), { stream: !part.done });
                  const blocks = buffer.split(/\\r?\\n\\r?\\n/);
                  buffer = blocks.pop() || "";
                  for (const block of blocks) {
                    let eventName = "message";
                    let data = "";
                    for (const line of block.split(/\\r?\\n/)) {
                      if (line.startsWith("event:")) eventName = line.slice(6).trim();
                      else if (line.startsWith("data:")) data += line.slice(5).trim();
                    }
                    if (!data) continue;
                    const value = JSON.parse(data);
                    renderEvent(eventName, value);
                  }
                  if (part.done) break;
                }
              } catch (error) {
                if (error.name === "AbortError") appendText("\\n[cancelled]");
                else appendText(`\\n[error] ${error.message}`);
              } finally {
                activeController = null;
                runButton.disabled = false;
                cancelButton.disabled = true;
                sessionSelect.disabled = false;
                newSessionButton.disabled = !canCreateSessions;
                deleteSessionButton.disabled = sessionSelect.options.length <= 1;
              }
            });
            cancelButton.addEventListener("click", () => activeController?.abort());
            """;

    private final AgentSessionFactory sessionFactory;
    private final boolean multipleSessionsEnabled;
    private final Path sessionDirectory;
    private final String bearerToken;
    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object sessionsLock = new Object();
    private final Map<String, SessionState> sessions = new LinkedHashMap<>();

    /**
     * 创建只监听 loopback 的 Agent HTTP API。
     *
     * <p>远程界面或反向代理应连接本机端口，不可将此不含 TLS 的 server 直接绑定到公网。</p>
     * <p>此兼容构造器只暴露一个固定 Agent，会话 Agent 的生命周期仍归调用方所有。</p>
     *
     * @param address loopback 地址和端口；使用端口 0 可分配临时端口
     * @param agent 已配置的 Agent 实例
     * @param bearerToken 可选 API token；设置后所有 HTTP 路径必须使用 Bearer 认证
     * @throws IOException 无法绑定指定地址时抛出
     */
    public AgentHttpServer(InetSocketAddress address, Agent agent, String bearerToken) throws IOException {
        this(address, fixedAgentFactory(agent), bearerToken, false, null);
    }

    /**
     * 创建 loopback Web server，并为每个新会话创建独立 Agent。
     *
     * <p>工厂创建的 Agent 由 server 按会话删除/关闭生命周期释放。</p>
     *
     * @param address loopback 地址和端口；使用端口 0 可分配临时端口
     * @param sessionFactory 按新会话 ID 创建 Agent 的工厂
     * @param bearerToken 可选 API token；设置后所有 HTTP 路径必须使用 bearer token
     * @throws IOException 无法绑定指定地址时抛出
     */
    public AgentHttpServer(
            InetSocketAddress address,
            AgentSessionFactory sessionFactory,
            String bearerToken
    ) throws IOException {
        this(address, sessionFactory, bearerToken, true, null);
    }

    /**
     * 创建支持可选本地会话/记忆持久化的 loopback server。
     *
     * <p>目录包含任务、历史事件和 AgentMemory 快照，调用方必须选择本地可信且访问受限的位置。</p>
     *
     * @param address loopback 地址和端口
     * @param sessionFactory 为新旧会话创建独立 Agent 的工厂
     * @param bearerToken 可选 API bearer token
     * @param sessionDirectory 可选持久化目录；为空时使用纯内存会话
     * @throws IOException 目录初始化、历史恢复或端口绑定失败
     */
    public AgentHttpServer(
            InetSocketAddress address,
            AgentSessionFactory sessionFactory,
            String bearerToken,
            Path sessionDirectory
    ) throws IOException {
        this(address, sessionFactory, bearerToken, true, sessionDirectory);
    }

    /**
     * 构造 server 共用的监听、Agent 会话与 HTTP handler。
     *
     * @param address loopback 地址和端口
     * @param sessionFactory Agent 创建工厂
     * @param bearerToken 可选 bearer token
     * @param multipleSessionsEnabled 是否允许创建多个 Agent 会话
     * @param configuredSessionDirectory 可选的可信持久化目录
     * @throws IOException 无法绑定指定地址时抛出
     */
    private AgentHttpServer(
            InetSocketAddress address,
            AgentSessionFactory sessionFactory,
            String bearerToken,
            boolean multipleSessionsEnabled,
            Path configuredSessionDirectory
    ) throws IOException {
        validateLoopbackAddress(address);
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.multipleSessionsEnabled = multipleSessionsEnabled;
        this.sessionDirectory = prepareSessionDirectory(configuredSessionDirectory);
        this.bearerToken = bearerToken == null || bearerToken.isBlank() ? null : bearerToken;
        Agent defaultAgent = Objects.requireNonNull(
                sessionFactory.create(DEFAULT_SESSION_ID),
                "sessionFactory returned null Agent"
        );
        SessionState defaultSession = new SessionState(DEFAULT_SESSION_ID, "默认会话", defaultAgent);
        sessions.put(DEFAULT_SESSION_ID, defaultSession);
        try {
            if (sessionDirectory != null) {
                restorePersistedSession(defaultSession, sessionFile(DEFAULT_SESSION_ID));
                loadPersistedSessions();
            }
        } catch (IOException | RuntimeException error) {
            if (multipleSessionsEnabled) {
                closeCreatedSessionAgents(error);
            }
            throw error;
        }
        try {
            this.server = HttpServer.create(address, 0);
        } catch (IOException error) {
            if (multipleSessionsEnabled) {
                closeCreatedSessionAgents(error);
            }
            throw error;
        }
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server.setExecutor(executor);
        this.server.createContext("/health", this::handleHealth);
        this.server.createContext("/api/sessions", this::handleSessions);
        this.server.createContext("/api/history", this::handleHistory);
        this.server.createContext("/api/run/stream", this::handleStream);
        this.server.createContext("/app.js", this::handleUiScript);
        this.server.createContext("/", this::handleUi);
    }

    /**
     * 回滚构造失败前已创建的 factory-owned Agent 资源。
     *
     * @param failure 正在传播的主构造错误
     */
    private void closeCreatedSessionAgents(Throwable failure) {
        for (SessionState session : sessions.values()) {
            try {
                sessionFactory.closeSession(session.id, session.agent);
            } catch (RuntimeException closeError) {
                failure.addSuppressed(closeError);
            }
        }
        sessions.clear();
    }

    /**
     * 将单 Agent 构造入口适配为仅支持默认会话的 factory。
     *
     * @param agent 由调用方持有的固定 Agent 实例
     * @return 只接受默认会话 ID 的工厂
     */
    private static AgentSessionFactory fixedAgentFactory(Agent agent) {
        Agent fixed = Objects.requireNonNull(agent, "agent");
        return sessionId -> {
            if (!DEFAULT_SESSION_ID.equals(sessionId)) {
                throw new ConfigurationException("This AgentHttpServer constructor supports one session only");
            }
            return fixed;
        };
    }

    /**
     * 校验并创建显式配置的本地会话持久化目录。
     *
     * @param requested 调用方指定的目录；null 表示禁用磁盘持久化
     * @return 规范化目录
     * @throws IOException 无法建立目录或目录类型不正确
     */
    private static Path prepareSessionDirectory(Path requested) throws IOException {
        if (requested == null) {
            return null;
        }
        Path directory = requested.toAbsolutePath().normalize();
        if (directory.getRoot() == null || directory.equals(directory.getRoot())) {
            throw new ConfigurationException("sessionDirectory must not be a filesystem root");
        }
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new ConfigurationException("sessionDirectory must be a real directory, not a symlink");
        }
        return directory;
    }

    /**
     * 返回固定 session ID 对应的单文件持久化路径。
     *
     * @param sessionId 经过格式校验的会话 ID
     * @return 会话 JSON 文件路径
     */
    private Path sessionFile(String sessionId) {
        if (!isValidSessionId(sessionId)) {
            throw new IllegalArgumentException("invalid persisted session ID");
        }
        return sessionDirectory.resolve(sessionId + ".json");
    }

    /**
     * 从 JSON 文件安全恢复事件历史和可选的 AgentMemory。
     *
     * @param session 新创建的 Agent 会话
     * @param path 该会话专用状态文件
     * @throws IOException 文件损坏、字段无效或 Agent 类型不支持恢复时抛出
     */
    private void restorePersistedSession(SessionState session, Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > MAX_PERSISTED_SESSION_BYTES) {
            throw new IOException("Persisted Agent session file is invalid or oversized: " + path.getFileName());
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readAllBytes(path));
        } catch (JsonProcessingException error) {
            throw new IOException("Persisted Agent session file contains invalid JSON", error);
        }
        if (root == null || !root.isObject() || root.path("schema_version").asInt(-1) != 1
                || !session.id.equals(root.path("id").asText())) {
            throw new IOException("Persisted Agent session file has an unsupported schema or ID");
        }
        String title = root.path("title").asText("");
        String createdAt = root.path("created_at").asText("");
        String lastActivity = root.path("last_activity").asText("");
        JsonNode events = root.path("events");
        if (title.isBlank() || title.length() > 128
                || !events.isArray() || events.size() > MAX_HISTORY_EVENTS) {
            throw new IOException("Persisted Agent session metadata is invalid");
        }
        try {
            session.title = title;
            session.createdAt = Instant.parse(createdAt);
            session.lastActivity = Instant.parse(lastActivity);
        } catch (RuntimeException error) {
            throw new IOException("Persisted Agent session timestamps are invalid", error);
        }
        long totalBytes = 0;
        for (JsonNode node : events) {
            if (!node.isObject() || !node.path("event").isTextual() || !node.path("data").isObject()) {
                throw new IOException("Persisted Agent session contains an invalid history event");
            }
            ObjectNode event = ((ObjectNode) node).deepCopy();
            omitBinaryMedia(event);
            long eventBytes = event.toString().getBytes(StandardCharsets.UTF_8).length;
            totalBytes += eventBytes;
            if (totalBytes > MAX_HISTORY_BYTES) {
                throw new IOException("Persisted Agent session history exceeds its configured limit");
            }
            session.history.addLast(new SessionState.HistoryEntry(event, eventBytes));
        }
        session.historyBytes = totalBytes;
        JsonNode serializedMemory = root.get("memory");
        if (serializedMemory != null && !serializedMemory.isNull()) {
            if (!serializedMemory.isTextual() || !(session.agent instanceof MemoryBackedAgent memoryAgent)) {
                throw new IOException("Persisted Agent memory requires a MemoryBackedAgent");
            }
            try {
                AgentMemory memory = AgentMemoryCodec.loads(serializedMemory.asText());
                memoryAgent.restoreMemory(memory);
            } catch (RuntimeException error) {
                throw new IOException("Unable to restore persisted Agent memory", error);
            }
        }
    }

    /**
     * 扫描并恢复除默认会话外的所有安全文件名会话。
     *
     * @throws IOException 目录读取、文件解码或达到会话上限时抛出
     */
    private void loadPersistedSessions() throws IOException {
        List<Path> storedFiles = new ArrayList<>();
        try (var paths = Files.list(sessionDirectory)) {
            var candidates = paths.filter(path -> path.getFileName().toString().endsWith(".json")).iterator();
            while (candidates.hasNext()) {
                if (storedFiles.size() >= MAX_SESSIONS) {
                    throw new IOException("Persisted Agent session directory exceeds the session limit");
                }
                storedFiles.add(candidates.next());
            }
        }
        for (Path path : storedFiles) {
            String fileName = path.getFileName().toString();
            String sessionId = fileName.substring(0, fileName.length() - ".json".length());
            if (DEFAULT_SESSION_ID.equals(sessionId)) {
                continue;
            }
            if (!isValidSessionId(sessionId) || sessions.size() >= MAX_SESSIONS) {
                throw new IOException("Persisted Agent session directory contains an invalid or excess session");
            }
            Agent agent = Objects.requireNonNull(
                    sessionFactory.create(sessionId),
                    "sessionFactory returned null Agent"
            );
            if (sessions.values().stream().anyMatch(existing -> existing.agent == agent)) {
                throw new IOException("sessionFactory must create a distinct Agent instance per session");
            }
            SessionState session = new SessionState(
                    sessionId,
                    "会话 " + sessionId.substring(0, Math.min(8, sessionId.length())),
                    agent
            );
            try {
                restorePersistedSession(session, path);
            } catch (IOException | RuntimeException error) {
                try {
                    sessionFactory.closeSession(sessionId, agent);
                } catch (RuntimeException closeError) {
                    error.addSuppressed(closeError);
                }
                throw error;
            }
            sessions.put(sessionId, session);
        }
    }

    /**
     * 检查会话 ID 只能是固定 default 或规范 UUID。
     *
     * @param sessionId 待校验 ID
     * @return ID 可安全映射为文件名时为 true
     */
    private static boolean isValidSessionId(String sessionId) {
        if (DEFAULT_SESSION_ID.equals(sessionId)) {
            return true;
        }
        try {
            return UUID.fromString(sessionId).toString().equals(sessionId);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    /**
     * 开始监听本机 HTTP endpoint。
     *
     * @throws IllegalStateException server 已启动或已关闭时抛出
     */
    public synchronized void start() {
        if (closed.get() || !started.compareAndSet(false, true)) {
            throw new IllegalStateException("AgentHttpServer has already been started or closed");
        }
        server.start();
    }

    /**
     * 返回 server 实际绑定的地址和端口。
     *
     * @return loopback socket address
     */
    public InetSocketAddress address() {
        return server.getAddress();
    }

    /**
     * 处理带认证的本机健康检查请求。
     *
     * @param exchange 当前 HTTP 请求
     * @throws IOException 响应写入失败
     */
    private void handleHealth(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!authorize(exchange)) {
                sendError(exchange, 401, "authentication required");
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())
                    || !"/health".equals(exchange.getRequestURI().getPath())) {
                sendError(exchange, 404, "not found");
                return;
            }
            ObjectNode body = MAPPER.createObjectNode().put("status", "ok");
            sendJson(exchange, 200, body);
        }
    }

    /**
     * 提供与本机 SSE API 同源的轻量任务提交页面。
     *
     * @param exchange 当前 HTTP 请求
     * @throws IOException 响应写入失败
     */
    private void handleUi(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())
                    || !"/".equals(exchange.getRequestURI().getPath())) {
                sendError(exchange, 404, "not found");
                return;
            }
            exchange.getResponseHeaders().set(
                    "Content-Security-Policy",
                    "default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; connect-src 'self'; "
                            + "img-src data:; media-src data:; base-uri 'none'; form-action 'self'"
            );
            sendText(exchange, "text/html; charset=utf-8", UI_HTML);
        }
    }

    /**
     * 提供同源 SSE 消费脚本；脚本只通过 textContent 渲染模型内容。
     *
     * @param exchange 当前 HTTP 请求
     * @throws IOException 响应写入失败
     */
    private void handleUiScript(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())
                    || !"/app.js".equals(exchange.getRequestURI().getPath())) {
                sendError(exchange, 404, "not found");
                return;
            }
            sendText(exchange, "text/javascript; charset=utf-8", UI_SCRIPT);
        }
    }

    /**
     * 列出、创建或删除隔离的 Agent 会话。
     *
     * @param exchange 当前 HTTP 请求
     * @throws IOException 响应写入失败
     */
    private void handleSessions(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!authorize(exchange)) {
                sendError(exchange, 401, "authentication required");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && "/api/sessions".equals(path)) {
                ArrayNode result = MAPPER.createArrayNode();
                boolean canCreate;
                synchronized (sessionsLock) {
                    sessions.values().forEach(session -> result.add(sessionSummary(session)));
                    canCreate = multipleSessionsEnabled && sessions.size() < MAX_SESSIONS;
                }
                exchange.getResponseHeaders().set("X-Session-Creation-Enabled", Boolean.toString(canCreate));
                sendJson(exchange, 200, result);
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/api/sessions".equals(path)) {
                if (!isSameOriginBrowserRequest(exchange)) {
                    sendError(exchange, 403, "cross-origin browser requests are not allowed");
                    return;
                }
                if (!multipleSessionsEnabled) {
                    sendError(exchange, 409, "multi-session Agent factory is not configured");
                    return;
                }
                SessionState created;
                try {
                    created = createSession();
                } catch (SessionLimitException error) {
                    sendError(exchange, 409, error.getMessage());
                    return;
                } catch (RuntimeException error) {
                    sendError(exchange, 500, "Agent session creation failed");
                    return;
                }
                sendJson(exchange, 201, sessionSummary(created));
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod()) && path.startsWith("/api/sessions/")) {
                if (!isSameOriginBrowserRequest(exchange)) {
                    sendError(exchange, 403, "cross-origin browser requests are not allowed");
                    return;
                }
                String sessionId = path.substring("/api/sessions/".length());
                if (sessionId.isBlank() || sessionId.contains("/")) {
                    sendError(exchange, 404, "session not found");
                    return;
                }
                int status = deleteSession(sessionId);
                if (status == 204) {
                    exchange.sendResponseHeaders(204, -1);
                } else if (status == 404) {
                    sendError(exchange, 404, "session not found");
                } else if (status == 409) {
                    sendError(exchange, 409, "session is active or is the last remaining session");
                } else {
                    sendError(exchange, 500, "unable to close Agent session");
                }
                return;
            }
            sendError(exchange, 404, "not found");
        }
    }

    /**
     * 创建一个不超过进程会话上限的新 Agent 实例。
     *
     * @return 创建的独立会话
     */
    private SessionState createSession() {
        synchronized (sessionsLock) {
            if (sessions.size() >= MAX_SESSIONS) {
                throw new SessionLimitException("maximum number of Agent sessions reached");
            }
            String sessionId;
            do {
                sessionId = UUID.randomUUID().toString();
            } while (sessions.containsKey(sessionId));
            Agent agent = Objects.requireNonNull(sessionFactory.create(sessionId), "sessionFactory returned null Agent");
            if (sessions.values().stream().anyMatch(existing -> existing.agent == agent)) {
                throw new ConfigurationException("sessionFactory must create a distinct Agent instance per session");
            }
            SessionState created = new SessionState(
                    sessionId,
                    "会话 " + sessionId.substring(0, 8),
                    agent
            );
            sessions.put(sessionId, created);
            try {
                persistSession(created);
            } catch (IOException | RuntimeException error) {
                sessions.remove(sessionId);
                try {
                    sessionFactory.closeSession(sessionId, agent);
                } catch (RuntimeException closeError) {
                    error.addSuppressed(closeError);
                }
                throw new IllegalStateException("Unable to persist newly created Agent session", error);
            }
            return created;
        }
    }

    /**
     * 删除空闲会话，保留至少一个可用会话。
     *
     * @param sessionId 目标会话 ID
     * @return HTTP 状态码语义：204 删除成功、404 不存在、409 忙碌/最后一个会话、500 清理失败
     */
    private int deleteSession(String sessionId) {
        synchronized (sessionsLock) {
            SessionState session = sessions.get(sessionId);
            if (session == null) {
                return 404;
            }
            if (session.activeRun.get() || sessions.size() <= 1) {
                return 409;
            }
            try {
                deletePersistedSession(sessionId);
            } catch (IOException error) {
                return 500;
            }
            sessions.remove(sessionId);
            try {
                sessionFactory.closeSession(session.id, session.agent);
                return 204;
            } catch (RuntimeException error) {
                return 500;
            }
        }
    }

    /**
     * 原子保存历史及支持的 AgentMemory 快照；未配置目录时不执行磁盘 I/O。
     *
     * @param session 待保存会话
     * @throws IOException JSON 编码、大小限制或本地文件写入失败
     */
    private void persistSession(SessionState session) throws IOException {
        if (sessionDirectory == null) {
            return;
        }
        ObjectNode root = MAPPER.createObjectNode()
                .put("schema_version", 1)
                .put("id", session.id)
                .put("title", session.title)
                .put("created_at", session.createdAt.toString())
                .put("last_activity", session.lastActivity.toString());
        ArrayNode events = root.putArray("events");
        synchronized (session.historyLock) {
            session.history.forEach(entry -> events.add(entry.event().deepCopy()));
        }
        if (session.agent instanceof MemoryBackedAgent memoryAgent) {
            root.put("memory", AgentMemoryCodec.dumps(memoryAgent.memory()));
        } else {
            root.putNull("memory");
        }
        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(root);
        } catch (JsonProcessingException error) {
            throw new IOException("Unable to encode Agent session snapshot", error);
        }
        if (bytes.length > MAX_PERSISTED_SESSION_BYTES) {
            throw new IOException("Persisted Agent session exceeds the maximum file size");
        }
        Path destination = sessionFile(session.id);
        Path temporary = Files.createTempFile(sessionDirectory, session.id + "-", ".tmp");
        IOException failure = null;
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            failure = error;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                if (failure == null) {
                    failure = cleanupError;
                } else {
                    failure.addSuppressed(cleanupError);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 删除指定会话的持久化快照。
     *
     * @param sessionId 安全格式的会话 ID
     * @throws IOException 快照文件删除失败
     */
    private void deletePersistedSession(String sessionId) throws IOException {
        if (sessionDirectory != null) {
            Files.deleteIfExists(sessionFile(sessionId));
        }
    }

    /**
     * 构造不包含 Agent 内部对象的会话列表 JSON。
     *
     * @param session 会话状态
     * @return 会话 ID、标题和时间戳
     */
    private static ObjectNode sessionSummary(SessionState session) {
        return MAPPER.createObjectNode()
                .put("id", session.id)
                .put("title", session.title)
                .put("createdAt", session.createdAt.toString())
                .put("lastActivity", session.lastActivity.toString());
    }

    /**
     * 返回指定 Agent 会话的有限事件历史快照。
     *
     * @param exchange 当前 HTTP 请求
     * @throws IOException 响应写入失败
     */
    private void handleHistory(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!authorize(exchange)) {
                sendError(exchange, 401, "authentication required");
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())
                    || !"/api/history".equals(exchange.getRequestURI().getPath())) {
                sendError(exchange, 404, "not found");
                return;
            }
            String sessionId;
            try {
                sessionId = queryParameter(exchange.getRequestURI(), "sessionId");
            } catch (IllegalArgumentException error) {
                sendError(exchange, 400, "invalid sessionId query parameter");
                return;
            }
            SessionState session = findSession(sessionId == null ? DEFAULT_SESSION_ID : sessionId);
            if (session == null) {
                sendError(exchange, 404, "session not found");
                return;
            }
            ArrayNode snapshot = MAPPER.createArrayNode();
            synchronized (session.historyLock) {
                session.history.forEach(entry -> snapshot.add(entry.event().deepCopy()));
            }
            sendJson(exchange, 200, snapshot);
        }
    }

    /**
     * 校验请求并为 Agent 事件订阅建立 SSE 响应。
     *
     * @param exchange 当前 HTTP 请求
     * @throws IOException 请求读取或响应写入失败
     */
    private void handleStream(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            try (exchange) {
                sendError(exchange, 401, "authentication required");
            }
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())
                || !"/api/run/stream".equals(exchange.getRequestURI().getPath())) {
            try (exchange) {
                sendError(exchange, 404, "not found");
            }
            return;
        }
        if (!isJsonContentType(exchange)) {
            try (exchange) {
                sendError(exchange, 415, "Content-Type must be application/json");
            }
            return;
        }
        if (!isSameOriginBrowserRequest(exchange)) {
            try (exchange) {
                sendError(exchange, 403, "cross-origin browser requests are not allowed");
            }
            return;
        }
        RunSubmission submission;
        try {
            submission = parseInput(exchange);
        } catch (RequestTooLargeException error) {
            try (exchange) {
                sendError(exchange, 413, error.getMessage());
            }
            return;
        } catch (IllegalArgumentException error) {
            try (exchange) {
                sendError(exchange, 400, error.getMessage());
            }
            return;
        } catch (IOException error) {
            try (exchange) {
                sendError(exchange, 400, "unable to read request body");
            }
            return;
        }
        SessionState session;
        boolean alreadyActive;
        synchronized (sessionsLock) {
            session = sessions.get(submission.sessionId());
            alreadyActive = session != null && !session.activeRun.compareAndSet(false, true);
        }
        if (session == null) {
            try (exchange) {
                sendError(exchange, 404, "session not found");
            }
            return;
        }
        if (alreadyActive) {
            try (exchange) {
                sendError(exchange, 409, "another run is already active for this Agent session");
            }
            return;
        }
        try {
            if (submission.reset()) {
                clearHistory(session);
            }
            session.lastActivity = Instant.now();
            appendHistory(session, "user_task", MAPPER.createObjectNode().put("task", submission.input().task()));
            serveEventStream(exchange, session, submission.input(), submission.reset());
        } finally {
            session.activeRun.set(false);
        }
    }

    /**
     * 有界读取 JSON 请求，并转换为 Agent 输入记录。
     *
     * @param exchange 当前 HTTP 请求
     * @return 已校验的 Agent 输入和目标 session
     * @throws IOException 请求体读取失败
     * @throws IllegalArgumentException JSON 或字段格式无效
     */
    private RunSubmission parseInput(HttpExchange exchange) throws IOException {
        byte[] body;
        try (var requestBody = exchange.getRequestBody()) {
            body = requestBody.readNBytes(MAX_REQUEST_BYTES + 1);
        }
        if (body.length > MAX_REQUEST_BYTES) {
            throw new RequestTooLargeException("request body exceeds 1 MiB");
        }
        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("request body must be valid JSON", error);
        }
        if (json == null || !json.isObject()) {
            throw new IllegalArgumentException("request body must be a JSON object");
        }
        JsonNode task = json.get("task");
        if (task == null || !task.isTextual() || task.asText().isBlank()) {
            throw new IllegalArgumentException("task must be a non-empty string");
        }
        String sessionId = DEFAULT_SESSION_ID;
        JsonNode sessionNode = json.get("sessionId");
        if (sessionNode != null && !sessionNode.isNull()) {
            if (!sessionNode.isTextual() || sessionNode.asText().isBlank()
                    || sessionNode.asText().length() > 64) {
                throw new IllegalArgumentException("sessionId must be a non-empty string up to 64 characters");
            }
            sessionId = sessionNode.asText();
        }
        boolean reset = false;
        JsonNode resetNode = json.get("reset");
        if (resetNode != null && !resetNode.isNull()) {
            if (!resetNode.isBoolean()) {
                throw new IllegalArgumentException("reset must be a boolean");
            }
            reset = resetNode.asBoolean();
        }
        Map<String, JsonNode> additionalArguments = new LinkedHashMap<>();
        JsonNode additional = json.get("additionalArguments");
        if (additional != null && !additional.isNull()) {
            if (!additional.isObject()) {
                throw new IllegalArgumentException("additionalArguments must be a JSON object");
            }
            additional.fields().forEachRemaining(entry ->
                    additionalArguments.put(entry.getKey(), entry.getValue().deepCopy())
            );
        }
        return new RunSubmission(new AgentInput(task.asText(), additionalArguments), reset, sessionId);
    }

    /**
     * 以有界事件队列将 Agent 运行事件写成 SSE，并在客户端断连后取消上游运行。
     *
     * @param exchange 当前 HTTP 请求/响应
     * @param session 当前隔离 Agent 会话
     * @param input 已解析的 Agent 输入
     * @throws IOException SSE 响应无法写入或 Agent 流发生错误
     */
    private void serveEventStream(
            HttpExchange exchange,
            SessionState session,
            AgentInput input,
            boolean reset
    ) throws IOException {
        BlockingQueue<Object> events = new ArrayBlockingQueue<>(256);
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                writeSseFrame(
                        output,
                        "user_task",
                        MAPPER.createObjectNode().put("task", input.task())
                );
                session.agent.stream(input, new RunOptions(reset, null, false, null, new CancellationToken()))
                        .subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription value) {
                        if (!subscription.compareAndSet(null, value)) {
                            value.cancel();
                            return;
                        }
                        value.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(AgentEvent event) {
                        session.lastActivity = Instant.now();
                        appendHistory(session, eventName(event), serializeEvent(event));
                        if (!events.offer(event)) {
                            failure.set(new IOException("HTTP client did not consume Agent events fast enough"));
                            completed.set(true);
                            events.offer(STREAM_END);
                            Flow.Subscription activeSubscription = subscription.get();
                            if (activeSubscription != null) {
                                activeSubscription.cancel();
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        failure.set(error);
                        try {
                            persistSession(session);
                        } catch (IOException | RuntimeException persistenceFailure) {
                            error.addSuppressed(persistenceFailure);
                        }
                        completed.set(true);
                        events.offer(STREAM_END);
                    }

                    @Override
                    public void onComplete() {
                        try {
                            persistSession(session);
                        } catch (IOException | RuntimeException error) {
                            failure.set(error);
                        }
                        completed.set(true);
                        events.offer(STREAM_END);
                    }
                        });
                boolean streamEnded = false;
                while (!streamEnded || !events.isEmpty()) {
                    Object item = events.poll(100, TimeUnit.MILLISECONDS);
                    if (item == STREAM_END) {
                        streamEnded = true;
                    } else if (item instanceof AgentEvent event) {
                        writeSseEvent(output, event);
                    } else if (completed.get() && events.isEmpty()) {
                        streamEnded = true;
                    } else if (item == null && !streamEnded) {
                        output.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    }
                }
                Throwable streamFailure = failure.get();
                if (streamFailure != null) {
                    writeSseFrame(
                            output,
                            "server_error",
                            MAPPER.createObjectNode().put("error", safeMessage(streamFailure))
                    );
                    throw new IOException("Agent stream failed", streamFailure);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while serving Agent event stream", error);
        } finally {
            Flow.Subscription activeSubscription = subscription.get();
            if (activeSubscription != null && !completed.get()) {
                activeSubscription.cancel();
            }
        }
    }

    /**
     * 序列化一个 Agent 事件并写为 SSE event/data frame。
     *
     * @param output HTTP 响应流
     * @param event Agent 事件
     * @throws IOException JSON 编码或网络写入失败
     */
    private static void writeSseEvent(java.io.OutputStream output, AgentEvent event) throws IOException {
        writeSseFrame(output, eventName(event), serializeEvent(event));
    }

    /**
     * 写入带事件名的有限 JSON SSE frame。
     *
     * @param output HTTP response stream
     * @param name 稳定事件名称
     * @param data 已白名单化的事件内容
     * @throws IOException JSON 编码或网络写入失败
     */
    private static void writeSseFrame(
            java.io.OutputStream output,
            String name,
            ObjectNode data
    ) throws IOException {
        String payload;
        try {
            payload = MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException error) {
            throw new IOException("Unable to serialize Agent event", error);
        }
        String encoded = "event: " + name + "\ndata: " + payload + "\n\n";
        byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EVENT_BYTES) {
            throw new IOException("Agent event exceeded the HTTP SSE size limit");
        }
        output.write(bytes);
        output.flush();
    }

    /**
     * 保存经过有限字段映射的事件到本进程内有界的会话历史。
     *
     * @param eventName 稳定的 SSE event 名称
     * @param data 事件公开 JSON 字段
     */
    private void appendHistory(SessionState session, String eventName, ObjectNode data) {
        ObjectNode item = MAPPER.createObjectNode().put("event", eventName);
        ObjectNode safeData = data.deepCopy();
        omitBinaryMedia(safeData);
        item.set("data", safeData);
        long itemBytes = item.toString().getBytes(StandardCharsets.UTF_8).length;
        if (itemBytes > MAX_HISTORY_BYTES) {
            ObjectNode marker = MAPPER.createObjectNode().put("historyOmitted", true)
                    .put("originalBytes", itemBytes);
            item.set("data", marker);
            itemBytes = item.toString().getBytes(StandardCharsets.UTF_8).length;
        }
        synchronized (session.historyLock) {
            while (!session.history.isEmpty()
                    && (session.history.size() >= MAX_HISTORY_EVENTS
                    || session.historyBytes + itemBytes > MAX_HISTORY_BYTES)) {
                session.historyBytes -= session.history.removeFirst().bytes();
            }
            session.history.addLast(new SessionState.HistoryEntry(item, itemBytes));
            session.historyBytes += itemBytes;
        }
    }

    /**
     * 从历史副本中移除内嵌媒体数据，保留媒体类型与省略标记。
     *
     * @param node 待清理的 JSON 子树
     */
    private static void omitBinaryMedia(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            String type = object.path("type").asText("");
            if (("image".equals(type) || "audio".equals(type)) && object.has("base64")) {
                object.remove("base64");
                object.put("binaryOmitted", true);
            }
            object.elements().forEachRemaining(AgentHttpServer::omitBinaryMedia);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(AgentHttpServer::omitBinaryMedia);
        }
    }

    /**
     * 清除指定会话的有界事件历史。
     *
     * @param session 目标会话
     */
    private void clearHistory(SessionState session) {
        synchronized (session.historyLock) {
            session.history.clear();
            session.historyBytes = 0;
        }
    }

    /**
     * 在并发会话表中查找指定 ID。
     *
     * @param sessionId session identifier
     * @return 会话不存在时为 null
     */
    private SessionState findSession(String sessionId) {
        synchronized (sessionsLock) {
            return sessions.get(sessionId);
        }
    }

    /**
     * 从 URI query 解码唯一指定的参数。
     *
     * @param uri 请求 URI
     * @param name 参数名称
     * @return 参数值；未提供时为 null
     */
    private static String queryParameter(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        String result = null;
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            String key = URLDecoder.decode(separator < 0 ? pair : pair.substring(0, separator),
                    StandardCharsets.UTF_8);
            if (!name.equals(key)) {
                continue;
            }
            if (result != null) {
                throw new IllegalArgumentException("duplicate query parameter");
            }
            result = URLDecoder.decode(separator < 0 ? "" : pair.substring(separator + 1),
                    StandardCharsets.UTF_8);
        }
        return result;
    }

    /**
     * 已校验的单次 HTTP Agent 运行请求。
     *
     * @param input 当前任务和附加上下文
     * @param reset 是否开启新会话并清除前序 AgentMemory
     * @param sessionId 目标会话标识
     */
    private record RunSubmission(AgentInput input, boolean reset, String sessionId) {
    }

    /**
     * 拥有独立 Agent、运行互斥和事件历史的 Web 会话。
     */
    private static final class SessionState {
        private final String id;
        private String title;
        private final Agent agent;
        private final AtomicBoolean activeRun = new AtomicBoolean();
        private final Object historyLock = new Object();
        private final ArrayDeque<HistoryEntry> history = new ArrayDeque<>();
        private long historyBytes;
        private Instant createdAt = Instant.now();
        private volatile Instant lastActivity = createdAt;

        /**
         * 创建空历史的新会话状态。
         *
         * @param id 唯一会话 ID
         * @param title UI 展示名称
         * @param agent 专供该会话使用的 Agent
         */
        private SessionState(String id, String title, Agent agent) {
            this.id = id;
            this.title = title;
            this.agent = agent;
        }

        /**
         * 会话历史事件及其 UTF-8 编码大小。
         *
         * @param event 对外回放的安全 JSON 事件
         * @param bytes 编码字节数
         */
        private record HistoryEntry(ObjectNode event, long bytes) {
        }
    }

    /**
     * 表示 server 已达到安全配置的并发会话上限。
     */
    private static final class SessionLimitException extends IllegalStateException {
        /**
         * 创建会话上限诊断。
         *
         * @param message 错误说明
         */
        private SessionLimitException(String message) {
            super(message);
        }
    }

    /**
     * 将公开运行事件映射为不包含任意内部对象的 JSON 字段。
     *
     * @param event Agent 运行事件
     * @return 有限字段集合 JSON
     */
    private static ObjectNode serializeEvent(AgentEvent event) {
        ObjectNode json = MAPPER.createObjectNode().put("runId", event.runId().toString());
        if (event instanceof AgentEvent.RunStarted value) {
            json.put("startedAt", value.startedAt().toString());
        } else if (event instanceof AgentEvent.StepStarted value) {
            json.put("stepNumber", value.stepNumber()).put("startedAt", value.startedAt().toString());
        } else if (event instanceof AgentEvent.ModelDelta value) {
            json.set("delta", serializeDelta(value.delta()));
        } else if (event instanceof AgentEvent.ToolStarted value) {
            json.put("stepNumber", value.stepNumber()).put("callId", value.callId()).put("toolName", value.toolName());
        } else if (event instanceof AgentEvent.ToolCompleted value) {
            json.put("stepNumber", value.stepNumber()).put("callId", value.callId()).put("toolName", value.toolName());
        } else if (event instanceof AgentEvent.ToolFailed value) {
            json.put("stepNumber", value.stepNumber()).put("callId", value.callId())
                    .put("toolName", value.toolName()).put("error", value.error());
        } else if (event instanceof AgentEvent.StepCompleted value) {
            json.put("stepType", value.step().getClass().getSimpleName())
                    .put("stepNumber", value.step().stepNumber());
            if (value.step() instanceof PlanningStep plan) {
                json.put("plan", plan.plan());
            } else if (value.step() instanceof ActionStep action) {
                if (action.error() != null) {
                    json.put("error", action.error());
                }
                ArrayNode observations = MAPPER.createArrayNode();
                action.observations().forEach(observation -> observations.add(
                        MAPPER.createObjectNode()
                                .put("callId", observation.callId())
                                .put("toolName", observation.toolName())
                                .set("result", serializeValue(observation.result()))
                ));
                json.set("observations", observations);
            } else if (value.step() instanceof FinalAnswerStep answer) {
                json.set("answer", serializeValue(answer.answer()));
            }
        } else if (event instanceof AgentEvent.RunCompleted value) {
            json.put("status", value.result().status().name())
                    .put("durationMillis", value.result().duration().toMillis());
            json.set("tokenUsage", serializeUsage(value.result().tokenUsage()));
            json.set("output", serializeValue(value.output()));
        } else if (event instanceof AgentEvent.RunFailed value) {
            json.put("errorType", value.error().getClass().getSimpleName())
                    .put("error", safeMessage(value.error()));
        }
        return json;
    }

    /**
     * 将模型 delta 转换为 SSE 可传输的 JSON。
     *
     * @param delta 模型事件增量
     * @return delta JSON
     */
    private static ObjectNode serializeDelta(ModelStreamEvent delta) {
        ObjectNode json = MAPPER.createObjectNode();
        if (delta instanceof ModelStreamEvent.MessageDelta value) {
            json.put("type", "text").put("text", value.text());
        } else if (delta instanceof ModelStreamEvent.ToolCallDelta value) {
            json.put("type", "toolCall").put("index", value.index())
                    .put("id", value.id()).put("name", value.name())
                    .put("argumentsFragment", value.argumentsFragment());
        } else if (delta instanceof ModelStreamEvent.Usage value) {
            json.put("type", "usage").set("tokenUsage", serializeUsage(value.tokenUsage()));
        } else if (delta instanceof ModelStreamEvent.Completed value) {
            json.put("type", "completed").set("message", serializeMessageContent(value.message().content()));
        } else if (delta instanceof ModelStreamEvent.Failed value) {
            json.put("type", "failed").put("errorType", value.error().getClass().getSimpleName())
                    .put("error", safeMessage(value.error()));
        }
        return json;
    }

    /**
     * 序列化消息的文本、JSON 和有界媒体内容片段。
     *
     * @param content 有序内容列表
     * @return JSON 内容数组
     */
    private static ArrayNode serializeMessageContent(java.util.List<ContentPart> content) {
        ArrayNode values = MAPPER.createArrayNode();
        content.forEach(part -> values.add(serializeContentPart(part)));
        return values;
    }

    /**
     * 序列化最终 AgentValue，不动态加载或反射调用类型。
     *
     * @param value 最终输出值
     * @return 有限结构 JSON
     */
    private static ObjectNode serializeValue(AgentValue value) {
        ObjectNode json = MAPPER.createObjectNode();
        json.set("content", serializeMessageContent(value.content()));
        if (value.structuredContent() != null) {
            json.set("structuredContent", value.structuredContent());
        }
        return json;
    }

    /**
     * 将允许的内容类型映射到简单 JSON 记录。
     *
     * @param part 文本、媒体或 JSON 内容
     * @return 内容 JSON
     */
    private static ObjectNode serializeContentPart(ContentPart part) {
        ObjectNode json = MAPPER.createObjectNode();
        if (part instanceof ContentPart.Text text) {
            json.put("type", "text").put("text", text.text());
        } else if (part instanceof ContentPart.Json structured) {
            json.put("type", "json").set("value", structured.value());
        } else if (part instanceof ContentPart.Image image) {
            json.put("type", "image").put("mimeType", image.mimeType());
            if (image.uri() != null) {
                json.put("uri", image.uri().toString());
            }
            if (image.data() != null) {
                if (image.data().length > MAX_MEDIA_BYTES) {
                    json.put("binaryOmitted", true);
                } else {
                    json.put("base64", Base64.getEncoder().encodeToString(image.data()));
                }
            }
        } else if (part instanceof ContentPart.Audio audio) {
            json.put("type", "audio").put("mimeType", audio.mimeType());
            if (audio.uri() != null) {
                json.put("uri", audio.uri().toString());
            }
            if (audio.data() != null) {
                if (audio.data().length > MAX_MEDIA_BYTES) {
                    json.put("binaryOmitted", true);
                } else {
                    json.put("base64", Base64.getEncoder().encodeToString(audio.data()));
                }
            }
            if (audio.transcript() != null) {
                json.put("transcript", audio.transcript());
            }
        }
        return json;
    }

    /**
     * 序列化已知或未知的输入/输出 token 用量。
     *
     * @param usage 模型 token 统计
     * @return token usage JSON
     */
    private static ObjectNode serializeUsage(TokenUsage usage) {
        ObjectNode json = MAPPER.createObjectNode();
        if (usage.promptTokens().isPresent()) {
            json.put("inputTokens", usage.promptTokens().getAsLong());
        } else {
            json.putNull("inputTokens");
        }
        if (usage.completionTokens().isPresent()) {
            json.put("outputTokens", usage.completionTokens().getAsLong());
        } else {
            json.putNull("outputTokens");
        }
        return json;
    }

    /**
     * 返回稳定的 snake_case SSE event 名称。
     *
     * @param event Agent 事件
     * @return wire event 名称
     */
    private static String eventName(AgentEvent event) {
        if (event instanceof AgentEvent.RunStarted) {
            return "run_started";
        }
        if (event instanceof AgentEvent.StepStarted) {
            return "step_started";
        }
        if (event instanceof AgentEvent.ModelDelta) {
            return "model_delta";
        }
        if (event instanceof AgentEvent.ToolStarted) {
            return "tool_started";
        }
        if (event instanceof AgentEvent.ToolCompleted) {
            return "tool_completed";
        }
        if (event instanceof AgentEvent.ToolFailed) {
            return "tool_failed";
        }
        if (event instanceof AgentEvent.StepCompleted) {
            return "step_completed";
        }
        if (event instanceof AgentEvent.RunCompleted) {
            return "run_completed";
        }
        return "run_failed";
    }

    /**
     * 生成非空错误摘要，不将 stack trace 或任意字段写入 SSE。
     *
     * @param error 失败异常
     * @return 错误类型或消息
     */
    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /**
     * 对配置了 token 的请求执行 constant-time bearer token 比较。
     *
     * @param exchange 当前 HTTP 请求
     * @return 认证成功或未配置 token 时为 true
     */
    private boolean authorize(HttpExchange exchange) {
        if (bearerToken == null) {
            return true;
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] expected = bearerToken.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, supplied);
    }

    /**
     * 限制 SSE run endpoint 只接受 JSON body。
     *
     * @param exchange 当前 HTTP 请求
     * @return media type 是 application/json 时为 true
     */
    private static boolean isJsonContentType(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("Content-Type");
        if (value == null) {
            return false;
        }
        String mediaType = value.split(";", 2)[0].strip();
        return "application/json".equalsIgnoreCase(mediaType);
    }

    /**
     * 阻止 browser Origin 与 Host 不同的跨站请求伪造。
     *
     * <p>缺少 Origin 的 native HTTP client 可访问 loopback API；若提供 Fetch Metadata，
     * {@code cross-site} 请求仍会被拒绝。</p>
     *
     * @param exchange 当前 HTTP 请求
     * @return 浏览器请求同源或调用方未声明来源时为 true
     */
    private static boolean isSameOriginBrowserRequest(HttpExchange exchange) {
        String fetchSite = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if ("cross-site".equalsIgnoreCase(fetchSite)) {
            return false;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            URI originUri = URI.create(origin);
            URI hostUri = URI.create("http://" + host);
            return "http".equalsIgnoreCase(originUri.getScheme())
                    && originUri.getHost() != null
                    && originUri.getUserInfo() == null
                    && originUri.getRawQuery() == null
                    && originUri.getRawFragment() == null
                    && originUri.getHost().equalsIgnoreCase(hostUri.getHost())
                    && hostUri.getUserInfo() == null
                    && effectivePort(originUri) == effectivePort(hostUri);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    /**
     * 返回 URI 显式端口或 HTTP scheme 默认端口。
     *
     * @param uri 请求 origin
     * @return TCP 端口
     */
    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : 80;
    }

    /**
     * 写入 JSON 错误或健康检查响应。
     *
     * @param exchange 当前 HTTP 响应
     * @param status HTTP 状态码
     * @param body JSON body
     * @throws IOException 编码或网络写入失败
     */
    private static void sendJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /**
     * 写入固定或本地构造的文本内容。
     *
     * @param exchange 当前 HTTP 响应
     * @param contentType 响应 MIME type
     * @param text UTF-8 文本
     * @throws IOException 响应写入失败
     */
    private static void sendText(HttpExchange exchange, String contentType, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /**
     * 使用统一 JSON 格式返回错误。
     *
     * @param exchange 当前 HTTP 响应
     * @param status HTTP 状态码
     * @param message 可展示的错误文本
     * @throws IOException 响应写入失败
     */
    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, MAPPER.createObjectNode().put("error", message));
    }

    /**
     * 拒绝非 loopback 监听地址，避免把无 TLS 服务暴露到公网。
     *
     * @param address 配置的监听地址
     */
    private static void validateLoopbackAddress(InetSocketAddress address) {
        Objects.requireNonNull(address, "address");
        InetAddress host = address.getAddress();
        if (host == null || !host.isLoopbackAddress()) {
            throw new ConfigurationException("AgentHttpServer may only bind to a loopback address");
        }
    }

    /**
     * 停止 HTTP listener 和请求执行器，并关闭所有会话 Agent。
     */
    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(0);
        executor.shutdownNow();
        RuntimeException failure = null;
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                failure = new IllegalStateException("HTTP server request workers did not stop in time");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failure = new IllegalStateException("Interrupted while stopping HTTP server request workers", error);
        }
        List<SessionState> remaining;
        synchronized (sessionsLock) {
            remaining = new ArrayList<>(sessions.values());
            sessions.clear();
        }
        if (multipleSessionsEnabled) {
            for (SessionState session : remaining) {
                try {
                    persistSession(session);
                    sessionFactory.closeSession(session.id, session.agent);
                } catch (IOException | RuntimeException error) {
                    RuntimeException reported = error instanceof RuntimeException runtime
                            ? runtime
                            : new IllegalStateException("Failed to persist Agent session during shutdown", error);
                    if (failure == null) {
                        failure = reported;
                    } else {
                        failure.addSuppressed(reported);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 标记超过 HTTP run API 请求体上限的参数。
     */
    private static final class RequestTooLargeException extends IllegalArgumentException {
        /**
         * 创建请求大小错误。
         *
         * @param message 错误说明
         */
        private RequestTooLargeException(String message) {
            super(message);
        }
    }
}
