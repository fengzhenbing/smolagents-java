package org.fzb.smolagents.executor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ExecutorException;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.ToolCall;
import org.fzb.smolagents.tool.Tool;
import org.fzb.smolagents.tool.ToolExecutionContext;
import org.fzb.smolagents.tool.ToolRegistry;
import org.fzb.smolagents.tool.ToolResult;
import org.fzb.smolagents.tool.ToolSpec;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Pattern;

/**
 * 通过持久化 CPython 子进程执行代码，并以 JSON Lines 协议桥接 Java 工具。
 *
 * <p>此实现仅用于本地兼容执行，不是安全沙箱。执行的 Python 代码以当前操作系统用户权限运行；
 * 对不可信代码应使用隔离容器或受控远程执行器。</p>
 */
public final class PythonProcessCodeExecutor implements CodeExecutor, ImportPolicyProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WORKER_RESOURCE =
            "/org/fzb/smolagents/executor/python_worker.py";
    private static final String EOF_MARKER = "\u0000<python-worker-eof>";
    private static final Pattern PYTHON_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> PYTHON_KEYWORDS = Set.of(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
            "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
            "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
            "return", "try", "while", "with", "yield"
    );
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(10);
    private static final List<String> DEFAULT_AUTHORIZED_IMPORTS = List.of(
            "collections",
            "datetime",
            "itertools",
            "math",
            "queue",
            "random",
            "re",
            "stat",
            "statistics",
            "time",
            "unicodedata"
    );

    private final String pythonExecutable;
    private final Duration defaultTimeout;
    private final int maxOutputChars;
    private final List<String> authorizedImports;
    private final BiFunction<String, Path, List<String>> processCommandFactory;
    private final Runnable processStopAction;
    private BlockingQueue<String> protocolLines;
    private ConcurrentLinkedDeque<String> errorLines;
    private Process process;
    private BufferedWriter workerInput;
    private Path workerFile;
    private boolean closed;

    /**
     * 使用系统默认 Python 命令和标准执行限制创建执行器。
     */
    public PythonProcessCodeExecutor() {
        this(defaultPythonExecutable(), Duration.ofSeconds(30), 50_000, DEFAULT_AUTHORIZED_IMPORTS);
    }

    /**
     * 使用指定的 Python 命令和标准执行限制创建执行器。
     *
     * @param pythonExecutable Python 可执行文件路径或命令名，不包含额外命令行参数
     */
    public PythonProcessCodeExecutor(String pythonExecutable) {
        this(pythonExecutable, Duration.ofSeconds(30), 50_000, DEFAULT_AUTHORIZED_IMPORTS);
    }

    /**
     * 使用系统 Python、默认限制及指定额外 import 创建执行器。
     *
     * <p>额外列表会与默认白名单合并。显式传入 {@code *} 会取消模块限制，允许任意 Python
     * import；本执行器仍以当前 OS 用户权限执行，不能作为不可信代码的安全边界。</p>
     *
     * @param additionalAuthorizedImports 在默认白名单之上增加的模块名
     */
    public PythonProcessCodeExecutor(List<String> additionalAuthorizedImports) {
        this(
                defaultPythonExecutable(),
                Duration.ofSeconds(30),
                50_000,
                mergeAdditionalImports(additionalAuthorizedImports)
        );
    }

    /**
     * 使用自定义 Python 命令、超时、输出长度及导入白名单创建执行器。
     *
     * @param pythonExecutable Python 可执行文件路径或命令名
     * @param defaultTimeout 未指定单次超时时采用的时限
     * @param maxOutputChars 标准输出最大字符数
     * @param authorizedImports 允许 Python 代码导入的模块名；使用 {@code *} 表示允许任意导入
     */
    public PythonProcessCodeExecutor(
            String pythonExecutable,
            Duration defaultTimeout,
            int maxOutputChars,
            List<String> authorizedImports
    ) {
        this(
                pythonExecutable,
                defaultTimeout,
                maxOutputChars,
                authorizedImports,
                (executable, script) -> List.of(executable, "-u", script.toString()),
                () -> { }
        );
    }

    /**
     * 使用自定义进程启动命令创建执行器，供隔离后端复用 JSON Lines 协议实现。
     *
     * @param pythonExecutable 执行器启动配置标识
     * @param defaultTimeout 未指定单次超时时采用的时限
     * @param maxOutputChars 标准输出最大字符数
     * @param authorizedImports 允许导入的模块名
     * @param processCommandFactory 根据 worker 路径生成完整进程参数
     */
    PythonProcessCodeExecutor(
            String pythonExecutable,
            Duration defaultTimeout,
            int maxOutputChars,
            List<String> authorizedImports,
            BiFunction<String, Path, List<String>> processCommandFactory
    ) {
        this(
                pythonExecutable,
                defaultTimeout,
                maxOutputChars,
                authorizedImports,
                processCommandFactory,
                () -> { }
        );
    }

    /**
     * 创建允许定制 worker 进程启动和额外清理动作的执行器。
     *
     * @param pythonExecutable 启动器标识
     * @param defaultTimeout 未指定单次超时时采用的时限
     * @param maxOutputChars 标准输出最大字符数
     * @param authorizedImports 允许导入的模块名
     * @param processCommandFactory 根据 worker 路径生成完整进程参数
     * @param processStopAction worker 进程停止后执行的资源清理动作
     */
    PythonProcessCodeExecutor(
            String pythonExecutable,
            Duration defaultTimeout,
            int maxOutputChars,
            List<String> authorizedImports,
            BiFunction<String, Path, List<String>> processCommandFactory,
            Runnable processStopAction
    ) {
        if (pythonExecutable == null || pythonExecutable.isBlank()) {
            throw new IllegalArgumentException("pythonExecutable must not be blank");
        }
        if (defaultTimeout == null || defaultTimeout.isZero() || defaultTimeout.isNegative()) {
            throw new IllegalArgumentException("defaultTimeout must be positive");
        }
        if (maxOutputChars < 1) {
            throw new IllegalArgumentException("maxOutputChars must be positive");
        }
        this.pythonExecutable = pythonExecutable;
        this.defaultTimeout = defaultTimeout;
        this.maxOutputChars = maxOutputChars;
        this.authorizedImports = List.copyOf(Objects.requireNonNull(authorizedImports, "authorizedImports"));
        this.processCommandFactory = Objects.requireNonNull(processCommandFactory, "processCommandFactory");
        this.processStopAction = Objects.requireNonNull(processStopAction, "processStopAction");
        if (this.authorizedImports.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("authorizedImports must not contain blank values");
        }
    }

    /**
     * 返回实际传给 CPython worker 的导入白名单。
     *
     * @return 不可变模块名列表
     */
    @Override
    public List<String> authorizedImports() {
        return authorizedImports;
    }

    /**
     * 合并默认模块和调用方附加模块，并保留首次出现顺序。
     *
     * @param additional 附加模块列表
     * @return 不重复的完整白名单
     */
    private static List<String> mergeAdditionalImports(List<String> additional) {
        Objects.requireNonNull(additional, "additionalAuthorizedImports");
        LinkedHashSet<String> imports = new LinkedHashSet<>(DEFAULT_AUTHORIZED_IMPORTS);
        for (String name : additional) {
            if (name == null || name.isBlank()) {
                throw new ConfigurationException("additionalAuthorizedImports must not contain blank values");
            }
            imports.add(name);
        }
        return List.copyOf(imports);
    }

    /**
     * 在持久化 Python 命名空间中执行代码，并处理执行期间发起的 Java 工具调用。
     *
     * @param request 代码、变量、工具和运行限制
     * @return 标准输出、最终表达式结果、变量快照或执行失败
     */
    @Override
    public synchronized ExecutorResult execute(ExecutorRequest request) {
        Objects.requireNonNull(request, "request");
        request.cancellation().throwIfCancelled();
        validateVariableNames(request.variables(), request.tools().keySet());
        ToolRegistry registry = createToolRegistry(request.tools());
        try {
            ensureStarted();
            ObjectNode command = MAPPER.createObjectNode()
                    .put("type", "execute")
                    .put("code", request.code())
                    .put("maxOutputChars", maxOutputChars);
            command.set("variables", toPythonVariables(request.variables()));
            command.set("toolSchemas", toToolSchemas(registry));
            ArrayNode imports = command.putArray("authorizedImports");
            authorizedImports.forEach(imports::add);

            writeMessage(command);
            Duration timeout = request.timeout() == null ? defaultTimeout : request.timeout();
            JsonNode response = awaitResponse(timeout, request.cancellation(), registry, request.runId());
            if (!"result".equals(response.path("type").asText())) {
                throw protocolFailure("Python worker returned an unexpected response", response);
            }
            return parseResult(response);
        } catch (ExecutorException error) {
            throw error;
        } catch (IOException error) {
            String details = diagnostic();
            throw terminateWorker("Python worker communication failed: " + details, error);
        }
    }

    /**
     * 清空解释器命名空间；下次执行仍会使用同一个 Python 进程。
     */
    @Override
    public synchronized void reset() {
        if (closed) {
            throw new ExecutorException("Python executor is already closed");
        }
        if (process == null || !process.isAlive()) {
            stopWorker();
            return;
        }
        try {
            writeMessage(MAPPER.createObjectNode().put("type", "reset"));
            JsonNode response = awaitResponse(
                    defaultTimeout,
                    new CancellationToken(),
                    new ToolRegistry(),
                    UUID.randomUUID()
            );
            if (!"reset".equals(response.path("type").asText())) {
                throw protocolFailure("Python worker did not confirm reset", response);
            }
        } catch (IOException error) {
            throw terminateWorker("Failed to reset Python worker: " + diagnostic(), error);
        }
    }

    /**
     * 关闭 Python 子进程并删除临时 worker 脚本。
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        IOException cleanupError = null;
        try {
            if (process != null && process.isAlive()) {
                writeMessage(MAPPER.createObjectNode().put("type", "shutdown"));
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (IOException error) {
            cleanupError = error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            cleanupError = new IOException("Interrupted while closing Python worker", error);
        } finally {
            try {
                stopWorker();
            } catch (RuntimeException error) {
                if (cleanupError == null) {
                    cleanupError = new IOException("Failed to stop Python worker resources", error);
                } else {
                    cleanupError.addSuppressed(error);
                }
            }
            if (workerFile != null) {
                try {
                    Files.deleteIfExists(workerFile);
                    workerFile = null;
                } catch (IOException error) {
                    if (cleanupError == null) {
                        cleanupError = error;
                    } else {
                        cleanupError.addSuppressed(error);
                    }
                }
            }
        }
        if (cleanupError != null) {
            throw new ExecutorException("Failed to close Python executor", cleanupError);
        }
    }

    /**
     * 返回本执行器使用的 Python 命令。
     *
     * @return Python 可执行文件路径或命令名
     */
    public String pythonExecutable() {
        return pythonExecutable;
    }

    /**
     * 启动 Python worker 并等待其就绪确认。
     *
     * @throws IOException worker 资源读取或进程启动失败时抛出
     */
    private void ensureStarted() throws IOException {
        if (closed) {
            throw new ExecutorException("Python executor is already closed");
        }
        if (process != null && process.isAlive()) {
            return;
        }
        stopWorker();
        workerFile = copyWorkerScript();
        try {
            List<String> command = List.copyOf(processCommandFactory.apply(pythonExecutable, workerFile));
            if (command.isEmpty() || command.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new ConfigurationException("executor process command must contain non-blank arguments");
            }
            process = new ProcessBuilder(command).start();
        } catch (ConfigurationException | ExecutorException error) {
            try {
                cleanupWorkerFile();
            } catch (ExecutorException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        } catch (IOException error) {
            cleanupWorkerFile();
            throw new ExecutorException(
                    "Unable to start Python executable '" + pythonExecutable + "': " + error.getMessage(),
                    error
            );
        }
        protocolLines = new LinkedBlockingQueue<>();
        errorLines = new ConcurrentLinkedDeque<>();
        workerInput = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startReader(process.getInputStream(), protocolLines, "smolagents-python-stdout");
        startErrorReader(process.getErrorStream(), errorLines);
        JsonNode ready = awaitResponse(
                STARTUP_TIMEOUT,
                new CancellationToken(),
                new ToolRegistry(),
                UUID.randomUUID()
        );
        if (!"ready".equals(ready.path("type").asText())) {
            stopWorker();
            throw protocolFailure("Python worker failed during startup", ready);
        }
    }

    /**
     * 将打包在应用资源中的 worker 脚本复制到临时文件。
     *
     * @return 临时脚本路径
     * @throws IOException 资源不存在或无法写入临时文件时抛出
     */
    private Path copyWorkerScript() throws IOException {
        Path temporaryFile = Files.createTempFile("smolagents-python-worker-", ".py");
        try (InputStream source = PythonProcessCodeExecutor.class.getResourceAsStream(WORKER_RESOURCE)) {
            if (source == null) {
                Files.deleteIfExists(temporaryFile);
                throw new IOException("Missing packaged Python worker resource: " + WORKER_RESOURCE);
            }
            Files.copy(source, temporaryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return temporaryFile;
        } catch (IOException error) {
            Files.deleteIfExists(temporaryFile);
            throw error;
        }
    }

    /**
     * 为 worker 标准输出启动协议读取线程。
     *
     * @param stream 子进程输出流
     * @param destination 接收逐行输出的队列
     * @param threadName 读取线程名称
     */
    private static void startReader(InputStream stream, BlockingQueue<String> destination, String threadName) {
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    destination.offer(line);
                }
            } catch (IOException error) {
                destination.offer("{\"type\":\"reader_error\",\"error\":"
                        + quote(error.getMessage()) + "}");
            } finally {
                destination.offer(EOF_MARKER);
            }
        }, threadName);
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * 持续读取并保留有限数量的 Python 错误输出行。
     *
     * @param stream 子进程错误流
     */
    private void startErrorReader(InputStream stream, ConcurrentLinkedDeque<String> destination) {
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    destination.addLast(line);
                    while (destination.size() > 40) {
                        destination.pollFirst();
                    }
                }
            } catch (IOException error) {
                destination.addLast("stderr reader failed: " + error.getMessage());
            }
        }, "smolagents-python-stderr");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * 等待 worker 响应，同时处理中间发生的 Java 工具调用。
     *
     * @param timeout 本次协议交互的时限
     * @param cancellation 取消令牌
     * @param tools 已注册工具
     * @return worker JSON 响应
     */
    private JsonNode awaitResponse(
            Duration timeout,
            CancellationToken cancellation,
            ToolRegistry tools,
            UUID runId
    ) {
        long deadline = System.nanoTime() + timeout.toNanos();
        BlockingQueue<String> responseLines = protocolLines;
        if (responseLines == null) {
            throw new ExecutorException("Python worker is not running");
        }
        while (true) {
            if (cancellation.isCancelled()) {
                throw terminateWorker("Python execution was cancelled", null);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw terminateWorker("Python execution exceeded timeout " + timeout, null);
            }
            String line;
            try {
                line = responseLines.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                        TimeUnit.NANOSECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw terminateWorker("Interrupted while waiting for Python worker", error);
            }
            if (line == null) {
                if (process == null || !process.isAlive()) {
                    throw terminateWorker("Python worker exited unexpectedly: " + diagnostic(), null);
                }
                continue;
            }
            if (EOF_MARKER.equals(line)) {
                throw terminateWorker("Python worker closed its output: " + diagnostic(), null);
            }
            JsonNode response;
            try {
                response = MAPPER.readTree(line);
            } catch (JsonProcessingException error) {
                throw terminateWorker("Python worker emitted invalid protocol JSON", error);
            }
            if ("tool_call".equals(response.path("type").asText())) {
                handleToolCall(response, tools, cancellation, runId);
                continue;
            }
            if ("protocol_error".equals(response.path("type").asText())
                    || "reader_error".equals(response.path("type").asText())) {
                throw terminateWorker("Python worker protocol error: "
                        + response.path("error").asText("unknown error"), null);
            }
            return response;
        }
    }

    /**
     * 执行 worker 发起的工具调用并通过协议返回 JSON 结果或明确错误。
     *
     * @param request Python worker 的工具调用请求
     * @param tools 工具注册表
     */
    private void handleToolCall(
            JsonNode request,
            ToolRegistry tools,
            CancellationToken cancellation,
            UUID runId
    ) {
        String callId = request.path("callId").asText("");
        String name = request.path("name").asText("");
        ObjectNode response = MAPPER.createObjectNode().put("type", "tool_response").put("callId", callId);
        try {
            JsonNode arguments = request.path("arguments");
            ToolResult result = tools.invoke(
                    new ToolCall(callId, name, arguments),
                    new ToolExecutionContext(runId, callId, cancellation)
            );
            response.put("ok", true);
            response.set("value", toolResultValue(result));
        } catch (RuntimeException error) {
            response.put("ok", false);
            response.put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
        try {
            writeMessage(response);
        } catch (IOException error) {
            throw terminateWorker("Failed to return a Java tool result to Python", error);
        }
    }

    /**
     * 构造执行请求对应的工具注册表。
     *
     * @param tools 请求中的工具映射
     * @return 参数校验启用的工具注册表
     */
    private static ToolRegistry createToolRegistry(Map<String, Tool> tools) {
        ToolRegistry registry = new ToolRegistry();
        for (Map.Entry<String, Tool> entry : tools.entrySet()) {
            ToolSpec spec = Objects.requireNonNull(entry.getValue().spec(), "tool spec");
            if (!entry.getKey().equals(spec.name())) {
                throw new ConfigurationException("Tool map key must match its declared name: " + entry.getKey());
            }
            if (!isPythonIdentifier(spec.name())
                    || (!"final_answer".equals(spec.name()) && !isPythonToolName(spec.name()))) {
                throw new ConfigurationException("Python tool name must be a valid identifier: " + spec.name());
            }
            if (spec.enabled() && !"final_answer".equals(spec.name())) {
                registry.register(entry.getValue());
            }
        }
        return registry;
    }

    /**
     * 将启用工具的 JSON Schema 发送给 Python 包装函数以支持位置参数。
     *
     * @param registry 工具注册表
     * @return 工具名称到 JSON Schema 的映射
     */
    private static ObjectNode toToolSchemas(ToolRegistry registry) {
        ObjectNode schemas = MAPPER.createObjectNode();
        for (ToolSpec spec : registry.specs()) {
            schemas.set(spec.name(), spec.inputSchema());
        }
        return schemas;
    }

    /**
     * 将 Java AgentValue 参数转换为 Python worker 可接收的 JSON 值。
     *
     * @param variables Java 侧变量
     * @return Python 变量 JSON 对象
     */
    private static ObjectNode toPythonVariables(Map<String, AgentValue> variables) {
        ObjectNode json = MAPPER.createObjectNode();
        variables.forEach((name, value) -> {
            JsonNode structured = value.structuredContent();
            if (structured != null) {
                json.set(name, structured);
            } else {
                String text = value.plainText().orElseThrow(
                        () -> new ConfigurationException("Python variable is not JSON-compatible: " + name)
                );
                json.put(name, text);
            }
        });
        return json;
    }

    /**
     * 将 Python 执行响应转换为执行器领域类型。
     *
     * @param response worker 结果消息
     * @return 执行结果
     */
    private static ExecutorResult parseResult(JsonNode response) {
        String stdout = response.path("stdout").asText("");
        Map<String, AgentValue> variables = parseVariables(response.path("variables"));
        if (!response.path("ok").asBoolean(false)) {
            JsonNode line = response.get("lineNumber");
            Integer lineNumber = line != null && line.canConvertToInt() ? line.asInt() : null;
            return new ExecutorResult(
                    stdout,
                    null,
                    variables,
                    new ExecutorFailure(
                            response.path("errorType").asText("PythonError"),
                            response.path("error").asText("Python execution failed"),
                            lineNumber
                    )
            );
        }
        JsonNode value = response.get("value");
        AgentValue resultValue;
        if (!response.path("valueIsJson").asBoolean(true)) {
            resultValue = AgentValue.text(value == null ? "None" : value.asText());
        } else {
            JsonNode jsonValue = value == null ? MAPPER.nullNode() : value;
            resultValue = jsonToAgentValue(jsonValue);
        }
        return new ExecutorResult(
                stdout,
                resultValue,
                variables,
                null,
                response.path("isFinalAnswer").asBoolean(false)
        );
    }

    /**
     * 将 Python JSON 变量快照映射为 AgentValue。
     *
     * @param variables worker 返回的变量对象
     * @return 变量名到值的不可变映射
     */
    private static Map<String, AgentValue> parseVariables(JsonNode variables) {
        if (!variables.isObject()) {
            return Map.of();
        }
        var result = new java.util.LinkedHashMap<String, AgentValue>();
        variables.fields().forEachRemaining(entry -> result.put(entry.getKey(), jsonToAgentValue(entry.getValue())));
        return Map.copyOf(result);
    }

    /**
     * 将 JSON 标量或结构化值映射为 AgentValue。
     *
     * @param value JSON 值
     * @return 可在记忆和下一步执行中使用的 AgentValue
     */
    private static AgentValue jsonToAgentValue(JsonNode value) {
        if (value.isTextual()) {
            return AgentValue.text(value.asText());
        }
        return new AgentValue(List.of(new ContentPart.Json(value)), value);
    }

    /**
     * 将工具结果转换为 worker 支持的 JSON 值，拒绝静默丢弃多模态内容。
     *
     * @param result Java 工具执行结果
     * @return JSON 兼容工具值
     */
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
                        "Python code cannot receive non-text media from tool: " + part.getClass().getSimpleName()
                );
            }
        }
        return MAPPER.getNodeFactory().textNode(text.toString());
    }

    /**
     * 校验初始变量名为 Python 标识符且不会遮蔽工具或内置函数。
     *
     * @param variables 初始变量映射
     * @param toolNames 注册工具名称
     */
    private static void validateVariableNames(Map<String, AgentValue> variables, java.util.Set<String> toolNames) {
        for (String name : variables.keySet()) {
            if (!isPythonIdentifier(name) || name.startsWith("__smolagents_")) {
                throw new ConfigurationException("Invalid Python variable name: " + name);
            }
            if ("final_answer".equals(name) || "__builtins__".equals(name) || toolNames.contains(name)) {
                throw new ConfigurationException("Python variable name is reserved: " + name);
            }
        }
    }

    /**
     * 判断名称是否符合当前 Python 标识符和关键字规则。
     *
     * @param name 待检查名称
     * @return 是可用标识符时为 true
     */
    public static boolean isPythonIdentifier(String name) {
        return name != null && PYTHON_IDENTIFIER.matcher(name).matches() && !PYTHON_KEYWORDS.contains(name);
    }

    /**
     * 判断名称是否可安全地作为 Python 代码中的工具函数名。
     *
     * @param name 待检查名称
     * @return 是非保留的 Python 工具标识符时为 true
     */
    public static boolean isPythonToolName(String name) {
        return isPythonIdentifier(name) && !name.startsWith("__") && !"final_answer".equals(name);
    }

    /**
     * 将 JSON 对象编码后写入 worker 标准输入。
     *
     * @param message 协议消息
     * @throws IOException 写入进程管道失败时抛出
     */
    private void writeMessage(ObjectNode message) throws IOException {
        if (workerInput == null) {
            throw new IOException("Python worker is not running");
        }
        workerInput.write(MAPPER.writeValueAsString(message));
        workerInput.newLine();
        workerInput.flush();
    }

    /**
     * 根据进程错误输出构造诊断文本。
     *
     * @return 最近的 worker 标准错误行
     */
    private String diagnostic() {
        ConcurrentLinkedDeque<String> currentErrorLines = errorLines;
        List<String> lines = currentErrorLines == null ? List.of() : new ArrayList<>(currentErrorLines);
        return lines.isEmpty() ? "no stderr details" : String.join(" | ", lines);
    }

    /**
     * 将协议错误节点转换为带有可读诊断信息的执行器异常。
     *
     * @param message 上下文说明
     * @param response worker 响应节点
     * @return 协议失败异常
     */
    private ExecutorException protocolFailure(String message, JsonNode response) {
        ExecutorException failure = new ExecutorException(message + ": " + response);
        try {
            stopWorker();
        } catch (RuntimeException cleanupError) {
            failure.addSuppressed(cleanupError);
        }
        return failure;
    }

    /**
     * 终止失效 worker，并确保清理错误不会覆盖原始执行失败。
     *
     * @param message 原始失败说明
     * @param cause 可选底层异常
     * @return 保留执行失败及清理失败的异常
     */
    private ExecutorException terminateWorker(String message, Throwable cause) {
        ExecutorException failure = cause == null
                ? new ExecutorException(message)
                : new ExecutorException(message, cause);
        try {
            stopWorker();
        } catch (RuntimeException cleanupError) {
            failure.addSuppressed(cleanupError);
        }
        return failure;
    }

    /**
     * 停止当前 worker 并释放进程管道及临时文件。
     */
    private void stopWorker() {
        Process current = process;
        process = null;
        BlockingQueue<String> currentProtocolLines = protocolLines;
        protocolLines = null;
        ConcurrentLinkedDeque<String> currentErrorLines = errorLines;
        errorLines = null;
        if (workerInput != null) {
            try {
                workerInput.close();
            } catch (IOException ignored) {
                // 进程终止时关闭管道失败不会改变停止结果。
            }
            workerInput = null;
        }
        if (current != null && current.isAlive()) {
            current.destroy();
            try {
                if (!current.waitFor(250, TimeUnit.MILLISECONDS)) {
                    current.destroyForcibly();
                    current.waitFor(1, TimeUnit.SECONDS);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
        if (currentProtocolLines != null) {
            currentProtocolLines.clear();
        }
        if (currentErrorLines != null) {
            currentErrorLines.clear();
        }
        RuntimeException cleanupFailure = null;
        if (current != null) {
            try {
                processStopAction.run();
            } catch (RuntimeException error) {
                cleanupFailure = error;
            }
        }
        try {
            cleanupWorkerFile();
        } catch (RuntimeException error) {
            if (cleanupFailure == null) {
                cleanupFailure = error;
            } else {
                cleanupFailure.addSuppressed(error);
            }
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    /**
     * 删除 worker 临时脚本；清理失败通过执行器异常上报。
     */
    private void cleanupWorkerFile() {
        if (workerFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(workerFile);
            workerFile = null;
        } catch (IOException error) {
            throw new ExecutorException("Failed to delete temporary Python worker script", error);
        }
    }

    /**
     * 选择适用于当前操作系统的默认 Python 命令。
     *
     * @return {@code smolagents.python} 系统属性或平台默认命令
     */
    private static String defaultPythonExecutable() {
        String configured = System.getProperty("smolagents.python");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "python"
                : "python3";
    }

    /**
     * 对 JSON 字符串进行转义，供读取线程异常构造协议消息。
     *
     * @param value 待转义文本
     * @return JSON 字符串字面量
     */
    private static String quote(String value) {
        try {
            return MAPPER.writeValueAsString(value == null ? "" : value);
        } catch (JsonProcessingException error) {
            return "\"reader error\"";
        }
    }
}
