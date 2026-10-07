package org.fzb.smolagents.executor.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ExecutorException;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.executor.CodeExecutor;
import org.fzb.smolagents.executor.ExecutorFailure;
import org.fzb.smolagents.executor.ExecutorRequest;
import org.fzb.smolagents.executor.ExecutorResult;
import org.fzb.smolagents.executor.PythonProcessCodeExecutor;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.ToolCall;
import org.fzb.smolagents.tool.Tool;
import org.fzb.smolagents.tool.ToolExecutionContext;
import org.fzb.smolagents.tool.ToolRegistry;
import org.fzb.smolagents.tool.ToolResult;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通过 JDK HTTP/WebSocket 客户端连接 Jupyter Kernel Gateway 的有界远程 Python 执行器。
 *
 * <p>Provider 沙箱须由显式生命周期 hook 创建，且其中运行标准 Jupyter Kernel Gateway。此实现不调用
 * shell、不安装包、不使用 pickle；输入变量与结果仅通过 JSON 安全值传递。</p>
 */
public class RemoteJupyterExecutor implements CodeExecutor {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL_PREFIX = "__SMOLAGENTS_TOOL__";
    private static final String RESULT_PREFIX = "__SMOLAGENTS_RESULT_";
    private static final String OUTPUT_TRUNCATION_MARKER = "\n[输出已截断]";

    private final String providerName;
    private final RemoteExecutorOptions options;
    private final JupyterKernelGatewayProvider sessionProvider;
    private final HttpClient httpClient;
    private RemoteKernelSession session;
    private boolean closed;

    /**
     * 创建使用默认边界的通用 Jupyter 执行器。
     *
     * @param providerName 日志/错误中使用的端点名称
     * @param sandboxProvider 沙箱创建与释放 hook
     */
    public RemoteJupyterExecutor(String providerName, JupyterSandboxProvider sandboxProvider) {
        this(providerName, sandboxProvider, RemoteExecutorOptions.defaults());
    }

    /**
     * 创建连接标准 Jupyter Kernel Gateway 的远程执行器。
     *
     * @param providerName provider 显示名称
     * @param sandboxProvider provider 沙箱创建/清理 hook
     * @param options 超时与响应边界
     */
    public RemoteJupyterExecutor(
            String providerName,
            JupyterSandboxProvider sandboxProvider,
            RemoteExecutorOptions options
    ) {
        this.providerName = Objects.requireNonNull(providerName, "providerName");
        if (providerName.isBlank()) {
            throw new IllegalArgumentException("providerName must not be blank");
        }
        this.options = Objects.requireNonNull(options, "options");
        this.httpClient = HttpClient.newBuilder().connectTimeout(options.setupTimeout()).build();
        this.sessionProvider = new JupyterKernelGatewayProvider(providerName, sandboxProvider, httpClient, options);
        this.session = sessionProvider.openSession();
    }

    /**
     * 创建代码执行请求，并在 Jupyter 内核中运行持久化 Python 命名空间。
     *
     * @param request 一次代码动作、变量、工具及超时信息
     * @return 标准输出、JSON 结果、变量快照或失败详情
     */
    @Override
    public synchronized ExecutorResult execute(ExecutorRequest request) {
        Objects.requireNonNull(request, "request");
        request.cancellation().throwIfCancelled();
        ensureOpen();
        ToolRegistry registry = createToolRegistry(request.tools());
        Duration timeout = request.timeout() == null ? options.defaultExecutionTimeout() : request.timeout();
        String marker = RESULT_PREFIX + UUID.randomUUID().toString().replace("-", "") + "__:";
        String pythonCode = buildPythonRequest(request, marker);
        if (pythonCode.length() > options.maxMessageChars()) {
            throw new ExecutorException("Remote Python request exceeded configured message size limit");
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        WebSocket socket = null;
        boolean requestSent = false;
        boolean executionComplete = false;
        MessageListener listener = new MessageListener(options.maxMessageChars());
        try {
            WebSocket.Builder builder = httpClient.newWebSocketBuilder().connectTimeout(options.setupTimeout());
            session.headers().forEach(builder::header);
            socket = builder.buildAsync(session.channelsUri(), listener)
                    .get(remainingNanos(deadline), TimeUnit.NANOSECONDS);

            String messageId = UUID.randomUUID().toString();
            ObjectNode execute = createExecuteMessage(messageId, pythonCode);
            requestSent = true;
            send(socket, MAPPER.writeValueAsString(execute), deadline, request.cancellation());
            StreamCollector streams = new StreamCollector(marker, options.maxOutputChars(), options.maxMessageChars());
            KernelFailure kernelFailure = null;
            boolean idle = false;
            Map<String, Tool> tools = toolsByName(request.tools());
            while (!idle) {
                request.cancellation().throwIfCancelled();
                JsonNode message = receive(listener, deadline, request.cancellation());
                String parentId = message.path("parent_header").path("msg_id").asText("");
                if (!messageId.equals(parentId)) {
                    continue;
                }
                String type = message.path("msg_type").asText(message.path("header").path("msg_type").asText(""));
                JsonNode content = message.path("content");
                if ("stream".equals(type)) {
                    streams.accept(content.path("text").asText(""));
                } else if ("input_request".equals(type)) {
                    String reply = handleInputRequest(content.path("prompt").asText(""), registry, request);
                    send(socket, createInputReply(message, reply), deadline, request.cancellation());
                } else if ("error".equals(type)) {
                    kernelFailure = kernelFailure(content);
                } else if ("status".equals(type)
                        && "idle".equals(content.path("execution_state").asText())) {
                    idle = true;
                }
            }
            executionComplete = true;
            return result(streams, kernelFailure);
        } catch (TimeoutException error) {
            return failure("RemoteExecutionTimeout", providerName + " execution exceeded " + timeout, Map.of());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ExecutorException(providerName + " execution was interrupted", error);
        } catch (java.util.concurrent.ExecutionException error) {
            throw new ExecutorException(providerName + " Jupyter WebSocket communication failed", error.getCause());
        } catch (java.io.IOException error) {
            throw new ExecutorException(providerName + " Jupyter WebSocket communication failed", error);
        } finally {
            if (requestSent && !executionComplete) {
                try {
                    sessionProvider.interruptSession(session);
                } catch (RuntimeException ignored) {
                }
            }
            if (socket != null) {
                closeSocket(socket);
            }
        }
    }

    /**
     * 删除当前内核并在同一 provider 沙箱中创建一个干净内核。
     */
    @Override
    public synchronized void reset() {
        ensureOpen();
        RemoteKernelSession old = session;
        session = null;
        try {
            session = sessionProvider.resetSession(old);
        } catch (RuntimeException error) {
            closed = true;
            throw error;
        }
    }

    /**
     * 释放 Jupyter 内核及其 provider 沙箱；重复调用不会重复清理。
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        RemoteKernelSession old = session;
        session = null;
        sessionProvider.closeSession(old);
    }

    private ObjectNode createExecuteMessage(String messageId, String code) {
        ObjectNode header = MAPPER.createObjectNode()
                .put("msg_id", messageId)
                .put("username", "smolagents-java")
                .put("session", UUID.randomUUID().toString())
                .put("msg_type", "execute_request")
                .put("version", "5.0");
        ObjectNode content = MAPPER.createObjectNode()
                .put("code", code)
                .put("silent", false)
                .put("store_history", true)
                .put("allow_stdin", true);
        content.set("user_expressions", MAPPER.createObjectNode());
        ObjectNode message = MAPPER.createObjectNode();
        message.set("header", header);
        message.set("parent_header", MAPPER.createObjectNode());
        message.set("metadata", MAPPER.createObjectNode());
        message.set("content", content);
        return message;
    }

    private String buildPythonRequest(ExecutorRequest request, String marker) {
        ObjectNode variables = MAPPER.createObjectNode();
        Map<String, Tool> tools = toolsByName(request.tools());
        request.variables().forEach((name, value) -> {
            if (!PythonProcessCodeExecutor.isPythonIdentifier(name) || name.startsWith("__smolagents_")) {
                throw new ExecutorException("Invalid Python variable name: " + name);
            }
            if ("final_answer".equals(name) || "__builtins__".equals(name) || tools.containsKey(name)) {
                throw new ExecutorException("Python variable name is reserved: " + name);
            }
            JsonNode jsonValue = value.structuredContent();
            if (jsonValue == null) {
                String text = value.plainText().orElseThrow(() ->
                        new ExecutorException("Remote Python variables must be text or structured JSON"));
                jsonValue = MAPPER.getNodeFactory().textNode(text);
            }
            variables.set(name, jsonValue);
        });

        ObjectNode toolSchemas = MAPPER.createObjectNode();
        for (Map.Entry<String, Tool> entry : tools.entrySet()) {
            String name = entry.getKey();
            if (!"final_answer".equals(name) && !PythonProcessCodeExecutor.isPythonToolName(name)) {
                throw new ExecutorException("Invalid Python tool name: " + name);
            }
            toolSchemas.set(name, entry.getValue().spec().inputSchema());
        }
        return pythonBootstrap(
                base64Json(variables),
                base64Json(toolSchemas),
                base64Text(request.code()),
                marker,
                Math.max(1, options.maxMessageChars() - marker.length() - 256)
        );
    }

    private String pythonBootstrap(String variables, String tools, String code, String marker, int maxResultChars) {
        return """
                import ast as __sa_ast
                import base64 as __sa_base64
                import builtins as __sa_builtins
                import json as __sa_json
                import keyword as __sa_keyword
                import traceback as __sa_traceback
                __sa_vars = __sa_json.loads(__sa_base64.b64decode("%s").decode("utf-8"))
                __sa_tools = __sa_json.loads(__sa_base64.b64decode("%s").decode("utf-8"))
                __sa_code = __sa_base64.b64decode("%s").decode("utf-8")
                __sa_marker = "%s"
                __sa_max_result_chars = %d
                __sa_tool_prefix = "%s"
                class __sa_FinalAnswer(BaseException):
                    def __init__(self, value):
                        self.value = value
                def __sa_call_tool(name, arguments):
                    prompt = __sa_tool_prefix + __sa_json.dumps({"name": name, "arguments": arguments}, ensure_ascii=False, separators=(",", ":"))
                    response = __sa_json.loads(__sa_builtins.input(prompt))
                    if not response.get("ok"):
                        raise RuntimeError(response.get("error", "Java tool execution failed"))
                    return response.get("value")
                def __sa_make_tool(name, schema):
                    property_names = list(schema.get("properties", {}).keys())
                    def invoke(*args, **kwargs):
                        if len(args) == 1 and isinstance(args[0], dict) and not kwargs:
                            arguments = args[0]
                        else:
                            if len(args) > len(property_names):
                                raise TypeError(name + " received too many positional arguments")
                            arguments = dict(zip(property_names, args))
                            for key, value in kwargs.items():
                                if key in arguments:
                                    raise TypeError(name + " received multiple values for " + key)
                                arguments[key] = value
                        return __sa_call_tool(name, arguments)
                    return invoke
                def final_answer(*args, **kwargs):
                    if len(args) > 1 or (args and "answer" in kwargs):
                        raise TypeError("final_answer received invalid arguments")
                    if args:
                        answer = args[0]
                    elif "answer" in kwargs:
                        answer = kwargs["answer"]
                    else:
                        raise TypeError("final_answer requires answer")
                    raise __sa_FinalAnswer(__sa_call_tool("final_answer", {"answer": answer}))
                for __sa_tool_name, __sa_schema in __sa_tools.items():
                    if __sa_tool_name != "final_answer":
                        globals()[__sa_tool_name] = __sa_make_tool(__sa_tool_name, __sa_schema)
                for __sa_name in __sa_vars:
                    if (not __sa_name.isidentifier() or __sa_keyword.iskeyword(__sa_name)
                            or __sa_name.startswith("__smolagents_") or __sa_name.startswith("__sa_")
                            or __sa_name in __sa_tools or __sa_name in ("final_answer", "__builtins__")):
                        raise ValueError("Invalid or reserved Python variable name: " + __sa_name)
                globals().update(__sa_vars)
                __sa_record = {"ok": True, "value": None, "valueIsJson": True, "isFinalAnswer": False, "variables": {}}
                try:
                    __sa_tree = __sa_ast.parse(__sa_code, filename="<smolagents-code>", mode="exec")
                    if __sa_tree.body and isinstance(__sa_tree.body[-1], __sa_ast.Expr):
                        __sa_expr = __sa_tree.body[-1]
                        __sa_tree.body[-1] = __sa_ast.Assign(
                            targets=[__sa_ast.Name(id="__sa_result", ctx=__sa_ast.Store())], value=__sa_expr.value)
                        __sa_ast.copy_location(__sa_tree.body[-1], __sa_expr)
                        __sa_ast.fix_missing_locations(__sa_tree)
                    exec(compile(__sa_tree, "<smolagents-code>", "exec"), globals(), globals())
                    __sa_value = globals().pop("__sa_result", None)
                except __sa_FinalAnswer as __sa_final:
                    __sa_record["value"] = __sa_final.value
                    __sa_record["isFinalAnswer"] = True
                except BaseException as __sa_error:
                    __sa_record["ok"] = False
                    __sa_record["errorType"] = type(__sa_error).__name__
                    __sa_record["error"] = str(__sa_error) or type(__sa_error).__name__
                    __sa_trace = __sa_traceback.extract_tb(__sa_error.__traceback__)
                    __sa_source_lines = [frame.lineno for frame in __sa_trace if frame.filename == "<smolagents-code>"]
                    __sa_record["lineNumber"] = __sa_source_lines[-1] if __sa_source_lines else None
                else:
                    __sa_record["value"] = __sa_value
                if __sa_record["ok"]:
                    try:
                        __sa_json.dumps(__sa_record["value"], ensure_ascii=False, allow_nan=False)
                    except (TypeError, ValueError, OverflowError):
                        __sa_record["value"] = repr(__sa_record["value"])
                        __sa_record["valueIsJson"] = False
                for __sa_name, __sa_value in list(globals().items()):
                    if __sa_name.startswith("_") or __sa_name in ("final_answer", "__builtins__") or __sa_name in __sa_tools:
                        continue
                    try:
                        __sa_json.dumps(__sa_value, ensure_ascii=False, allow_nan=False)
                        __sa_record["variables"][__sa_name] = __sa_value
                    except (TypeError, ValueError, OverflowError, RecursionError):
                        pass
                __sa_serialized = __sa_json.dumps(__sa_record, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
                if len(__sa_serialized) > __sa_max_result_chars:
                    __sa_serialized = __sa_json.dumps({"ok": False, "errorType": "RemoteResponseTooLarge",
                        "error": "Remote execution result exceeded configured size limit", "variables": {}}, separators=(",", ":"))
                print(__sa_marker + __sa_serialized, flush=True)
                """.formatted(variables, tools, code, marker, maxResultChars, TOOL_PREFIX);
    }

    private String handleInputRequest(String prompt, ToolRegistry registry, ExecutorRequest request) {
        ObjectNode reply = MAPPER.createObjectNode();
        if (!prompt.startsWith(TOOL_PREFIX)) {
            reply.put("ok", false).put("error", "Interactive Python input is not supported by this executor");
            return jsonText(reply);
        }
        try {
            JsonNode call = MAPPER.readTree(prompt.substring(TOOL_PREFIX.length()));
            String name = call.path("name").asText("");
            JsonNode arguments = call.path("arguments");
            if (!arguments.isObject()) {
                throw new ToolExecutionException("Remote Python tool arguments must be a JSON object");
            }
            request.cancellation().throwIfCancelled();
            String callId = "remote-" + UUID.randomUUID();
            ToolResult result = registry.invoke(
                    new ToolCall(callId, name, arguments),
                    new ToolExecutionContext(request.runId(), callId, request.cancellation())
            );
            reply.put("ok", true);
            reply.set("value", toolResultValue(result));
        } catch (Exception error) {
            reply.put("ok", false).put("error", clipped(error.getMessage(), options.maxMessageChars()));
        }
        String encoded = jsonText(reply);
        if (encoded.length() <= options.maxMessageChars()) {
            return encoded;
        }
        return jsonText(MAPPER.createObjectNode()
                .put("ok", false)
                .put("error", "Remote tool result exceeded configured message size limit"));
    }

    private String createInputReply(JsonNode inputRequest, String value) throws java.io.IOException {
        String sessionId = inputRequest.path("parent_header").path("session").asText(UUID.randomUUID().toString());
        ObjectNode header = MAPPER.createObjectNode()
                .put("msg_id", UUID.randomUUID().toString())
                .put("username", "smolagents-java")
                .put("session", sessionId)
                .put("msg_type", "input_reply")
                .put("version", "5.0");
        ObjectNode reply = MAPPER.createObjectNode();
        reply.set("header", header);
        reply.set("parent_header", inputRequest.path("header").deepCopy());
        reply.set("metadata", MAPPER.createObjectNode());
        reply.set("content", MAPPER.createObjectNode().put("value", value));
        return MAPPER.writeValueAsString(reply);
    }

    private static JsonNode toolResultValue(ToolResult result) {
        JsonNode structured = result.structuredContent();
        if (structured != null) {
            return structured;
        }
        StringBuilder text = new StringBuilder();
        for (ContentPart part : result.content()) {
            if (part instanceof ContentPart.Text content) {
                text.append(content.text());
            } else if (part instanceof ContentPart.Json json) {
                return json.value();
            } else {
                throw new ToolExecutionException(
                        "Remote Python cannot receive non-text media from tool: " + part.getClass().getSimpleName()
                );
            }
        }
        return MAPPER.getNodeFactory().textNode(text.toString());
    }

    private static Map<String, Tool> toolsByName(Map<String, Tool> requestTools) {
        Map<String, Tool> result = new LinkedHashMap<>();
        requestTools.values().forEach(tool -> {
            String name = tool.spec().name();
            if (result.putIfAbsent(name, tool) != null) {
                throw new ExecutorException("Duplicate remote Python tool name: " + name);
            }
        });
        return Map.copyOf(result);
    }

    private static ToolRegistry createToolRegistry(Map<String, Tool> requestTools) {
        ToolRegistry registry = new ToolRegistry();
        requestTools.forEach((name, tool) -> {
            if (!name.equals(tool.spec().name())) {
                throw new ExecutorException("Tool map key must match tool name: " + name);
            }
            registry.register(tool);
        });
        return registry;
    }

    private ExecutorResult result(StreamCollector streams, KernelFailure kernelFailure) {
        String stdout = streams.stdout();
        if (kernelFailure != null) {
            return new ExecutorResult(stdout, null, Map.of(),
                    new ExecutorFailure(kernelFailure.type(), kernelFailure.message(), null));
        }
        JsonNode record = streams.result();
        if (record == null) {
            return failure("RemoteProtocolError", "Jupyter kernel finished without a result message", Map.of(), stdout);
        }
        Map<String, AgentValue> variables = parseVariables(record.path("variables"));
        if (!record.path("ok").asBoolean(false)) {
            JsonNode line = record.get("lineNumber");
            Integer lineNumber = line != null && line.canConvertToInt() ? line.asInt() : null;
            return new ExecutorResult(stdout, null, variables, new ExecutorFailure(
                    record.path("errorType").asText("PythonError"),
                    record.path("error").asText("Remote Python execution failed"),
                    lineNumber
            ));
        }
        JsonNode value = record.get("value");
        AgentValue agentValue;
        if (!record.path("valueIsJson").asBoolean(true)) {
            agentValue = AgentValue.text(value == null ? "None" : value.asText());
        } else {
            JsonNode structured = value == null ? MAPPER.nullNode() : value;
            agentValue = structured.isTextual()
                    ? AgentValue.text(structured.asText())
                    : new AgentValue(List.of(new ContentPart.Json(structured)), structured);
        }
        return new ExecutorResult(stdout, agentValue, variables, null, record.path("isFinalAnswer").asBoolean(false));
    }

    private static Map<String, AgentValue> parseVariables(JsonNode variables) {
        if (!variables.isObject()) {
            return Map.of();
        }
        Map<String, AgentValue> result = new LinkedHashMap<>();
        variables.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            AgentValue agentValue = value.isTextual()
                    ? AgentValue.text(value.asText())
                    : new AgentValue(List.of(new ContentPart.Json(value)), value);
            result.put(entry.getKey(), agentValue);
        });
        return Map.copyOf(result);
    }

    private static ExecutorResult failure(String type, String message, Map<String, AgentValue> variables) {
        return failure(type, message, variables, "");
    }

    private static ExecutorResult failure(String type, String message, Map<String, AgentValue> variables, String stdout) {
        return new ExecutorResult(stdout, null, variables, new ExecutorFailure(type, message, null));
    }

    private static KernelFailure kernelFailure(JsonNode content) {
        String type = content.path("ename").asText("RemoteKernelError");
        String message = content.path("evalue").asText(type);
        JsonNode traceback = content.path("traceback");
        if (traceback.isArray()) {
            List<String> lines = new ArrayList<>();
            traceback.forEach(line -> lines.add(line.asText()));
            message = String.join("\n", lines);
        }
        return new KernelFailure(type, stripAnsi(message));
    }

    private static String stripAnsi(String text) {
        return text.replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", "");
    }

    private static void send(WebSocket socket, String text, long deadline, CancellationToken cancellation)
            throws InterruptedException, java.util.concurrent.ExecutionException, TimeoutException {
        cancellation.throwIfCancelled();
        socket.sendText(text, true).get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
    }

    private JsonNode receive(MessageListener listener, long deadline, CancellationToken cancellation)
            throws InterruptedException, TimeoutException, java.io.IOException {
        while (true) {
            cancellation.throwIfCancelled();
            long remaining = remainingNanos(deadline);
            InboundEvent event = listener.events().poll(
                    Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
            if (event instanceof InboundMessage message) {
                listener.messageConsumed(message.text().length());
                try {
                    return MAPPER.readTree(message.text());
                } catch (java.io.IOException error) {
                    throw new java.io.IOException("Jupyter server sent malformed JSON", error);
                }
            }
            if (event instanceof InboundFailure failure) {
                throw new java.io.IOException("Jupyter WebSocket failed", failure.error());
            }
            Throwable asynchronousError = listener.asynchronousError();
            if (asynchronousError != null) {
                throw new java.io.IOException("Jupyter WebSocket failed", asynchronousError);
            }
            if (event instanceof InboundClosed) {
                throw new java.io.IOException("Jupyter WebSocket closed before execution completed");
            }
        }
    }

    private static long remainingNanos(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("Remote execution timeout");
        }
        return remaining;
    }

    private static String base64Json(JsonNode value) {
        try {
            return Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(value));
        } catch (java.io.IOException error) {
            throw new ExecutorException("Could not serialize remote Python request as JSON", error);
        }
    }

    private static String base64Text(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String jsonText(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (java.io.IOException error) {
            throw new ExecutorException("Could not encode Jupyter input reply", error);
        }
    }

    private static String clipped(String value, int maximum) {
        if (value == null) {
            return "Remote tool execution failed";
        }
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private void ensureOpen() {
        if (closed || session == null) {
            throw new ExecutorException(providerName + " remote executor is closed");
        }
    }

    private static void closeSocket(WebSocket socket) {
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "execution complete").get(100, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            socket.abort();
        }
    }

    private record KernelFailure(String type, String message) {
    }

    private sealed interface InboundEvent permits InboundMessage, InboundFailure, InboundClosed {
    }

    private record InboundMessage(String text) implements InboundEvent {
    }

    private record InboundFailure(Throwable error) implements InboundEvent {
    }

    private record InboundClosed() implements InboundEvent {
    }

    private static final class MessageListener implements WebSocket.Listener {
        private final int maximumMessageChars;
        private final BlockingQueue<InboundEvent> events = new LinkedBlockingQueue<>(16);
        private final StringBuilder fragments = new StringBuilder();
        private final AtomicReference<Throwable> asynchronousError = new AtomicReference<>();
        private int queuedChars;

        private MessageListener(int maximumMessageChars) {
            this.maximumMessageChars = maximumMessageChars;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public synchronized CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (fragments.length() + data.length() > maximumMessageChars) {
                fail(new IllegalStateException("Jupyter WebSocket message exceeded configured size limit"));
                webSocket.abort();
                return CompletableFuture.completedFuture(null);
            }
            fragments.append(data);
            if (last) {
                String text = fragments.toString();
                fragments.setLength(0);
                if (queuedChars + text.length() > maximumMessageChars * 2L || !events.offer(new InboundMessage(text))) {
                    fail(new IllegalStateException("Jupyter WebSocket response buffer exceeded configured size limit"));
                    webSocket.abort();
                } else {
                    queuedChars += text.length();
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            events.offer(new InboundClosed());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(error);
        }

        private BlockingQueue<InboundEvent> events() {
            return events;
        }

        private Throwable asynchronousError() {
            return asynchronousError.get();
        }

        private synchronized void messageConsumed(int length) {
            queuedChars = Math.max(0, queuedChars - length);
        }

        private void fail(Throwable error) {
            asynchronousError.compareAndSet(null, error);
            events.offer(new InboundFailure(error));
        }
    }

    private static final class StreamCollector {
        private final String marker;
        private final int maxOutputChars;
        private final int maxMessageChars;
        private final StringBuilder pending = new StringBuilder();
        private final StringBuilder output = new StringBuilder();
        private final StringBuilder payload = new StringBuilder();
        private JsonNode result;
        private boolean markerSeen;
        private boolean truncated;

        private StreamCollector(String marker, int maxOutputChars, int maxMessageChars) {
            this.marker = marker;
            this.maxOutputChars = maxOutputChars;
            this.maxMessageChars = maxMessageChars;
        }

        private void accept(String text) throws java.io.IOException {
            if (markerSeen) {
                if (result == null) {
                    readPayload(text);
                }
                return;
            }
            pending.append(text);
            int markerAt = pending.indexOf(marker);
            if (markerAt >= 0) {
                appendOutput(pending.substring(0, markerAt));
                markerSeen = true;
                String afterMarker = pending.substring(markerAt + marker.length());
                pending.setLength(0);
                readPayload(afterMarker);
                return;
            }
            int keep = Math.min(marker.length() - 1, pending.length());
            int flush = pending.length() - keep;
            if (flush > 0) {
                appendOutput(pending.substring(0, flush));
                pending.delete(0, flush);
            }
        }

        private void readPayload(String text) throws java.io.IOException {
            int newline = text.indexOf('\n');
            String piece = newline < 0 ? text : text.substring(0, newline);
            if (payload.length() + piece.length() > maxMessageChars) {
                throw new java.io.IOException("Jupyter result exceeded configured size limit");
            }
            payload.append(piece);
            if (newline >= 0) {
                String json = payload.toString();
                if (json.endsWith("\r")) {
                    json = json.substring(0, json.length() - 1);
                }
                result = MAPPER.readTree(json);
            }
        }

        private void appendOutput(String text) {
            int remaining = maxOutputChars - output.length();
            if (text.length() <= remaining) {
                output.append(text);
            } else {
                if (remaining > 0) {
                    output.append(text, 0, remaining);
                }
                truncated = true;
            }
        }

        private JsonNode result() {
            return result;
        }

        private String stdout() {
            if (!markerSeen) {
                appendOutput(pending.toString());
                pending.setLength(0);
            }
            if (!truncated) {
                return output.toString();
            }
            if (maxOutputChars <= OUTPUT_TRUNCATION_MARKER.length()) {
                return OUTPUT_TRUNCATION_MARKER.substring(0, maxOutputChars);
            }
            return output.substring(0, maxOutputChars - OUTPUT_TRUNCATION_MARKER.length())
                    + OUTPUT_TRUNCATION_MARKER;
        }
    }
}
