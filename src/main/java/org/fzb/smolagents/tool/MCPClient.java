package org.fzb.smolagents.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fzb.smolagents.core.AgentRunException;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.model.ContentPart;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通过 MCP stdio 或 Streamable HTTP JSON-RPC 连接一个或多个工具服务器。
 *
 * <p>当前实现覆盖 stdio、Streamable HTTP 和旧版 HTTP+SSE transport，以及服务器初始化、
 * 工具与 prompt/resource 发现/调用、文本/图片/音频内容及可选结构化输出。由于 stdio
 * 连接会启动本地命令，调用者必须显式设置 {@code trustRemoteCode=true}。</p>
 *
 * <p>服务进程 stdout 专用于 MCP JSON-RPC；服务诊断应写入 stderr。服务器进程由本对象
 * 管理，建议使用 try-with-resources 或显式调用 {@link #disconnect()}。</p>
 */
public final class MCPClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_PROTOCOL_LINE_LENGTH = 8 * 1024 * 1024;

    private final List<ServerConfiguration> serverParameters;
    private final boolean structuredOutput;
    private final boolean trustRemoteCode;
    private volatile List<Tool> tools;
    private volatile Map<String, JsonNode> toolOutputSchemas = Map.of();
    private List<McpConnection> connections = List.of();

    /**
     * 连接单个 MCP stdio 服务器并发现其工具。
     *
     * @param serverParameters 服务器命令、参数和环境配置
     * @param trustRemoteCode 必须显式设为 true，确认允许启动并调用此服务器
     * @param structuredOutput 是否保留 MCP structuredContent 和 JSON 文本回退
     */
    public MCPClient(ServerParameters serverParameters, boolean trustRemoteCode, boolean structuredOutput) {
        this(List.of(Objects.requireNonNull(serverParameters, "serverParameters")), trustRemoteCode, structuredOutput);
    }

    /**
     * 连接单个 MCP Streamable HTTP 服务器并发现其工具。
     *
     * @param serverParameters Streamable HTTP endpoint 与请求配置
     * @param trustRemoteCode 必须显式设为 true，确认允许调用远端服务器
     * @param structuredOutput 是否保留 MCP structuredContent 和 JSON 文本回退
     */
    public MCPClient(
            StreamableHttpServerParameters serverParameters,
            boolean trustRemoteCode,
            boolean structuredOutput
    ) {
        this(List.of(Objects.requireNonNull(serverParameters, "serverParameters")), trustRemoteCode, structuredOutput);
    }

    /**
     * 连接单个 legacy HTTP+SSE MCP 服务器并发现其工具。
     *
     * @param serverParameters SSE endpoint 和认证配置
     * @param trustRemoteCode 必须显式设为 true，确认允许调用远端服务器
     * @param structuredOutput 是否保留 structuredContent
     */
    public MCPClient(SseServerParameters serverParameters, boolean trustRemoteCode, boolean structuredOutput) {
        this(List.of(Objects.requireNonNull(serverParameters, "serverParameters")), trustRemoteCode, structuredOutput);
    }

    /**
     * 连接多个 MCP stdio/Streamable HTTP/SSE 服务器并发现全部工具。
     *
     * @param serverParameters 有序服务器配置；多服务器工具名以服务器别名作为前缀
     * @param trustRemoteCode 必须显式设为 true，确认允许启动/调用这些服务器
     * @param structuredOutput 是否保留 MCP structuredContent 和 JSON 文本回退
     */
    public MCPClient(
            List<? extends ServerConfiguration> serverParameters,
            boolean trustRemoteCode,
            boolean structuredOutput
    ) {
        if (!trustRemoteCode) {
            throw new ConfigurationException(
                    "MCP requires explicit trust acknowledgement before connecting to configured servers"
            );
        }
        if (serverParameters == null || serverParameters.isEmpty()) {
            throw new ConfigurationException("at least one MCP server must be configured");
        }
        this.serverParameters = List.copyOf(new ArrayList<>(serverParameters));
        this.structuredOutput = structuredOutput;
        this.trustRemoteCode = true;
        validateServerNames(this.serverParameters);
        connect();
    }

    /**
     * 返回当前连接上发现的 MCP 工具快照。
     *
     * @return 不可变工具列表
     * @throws IllegalStateException 客户端未连接或已断开时抛出
     */
    public List<Tool> getTools() {
        List<Tool> current = tools;
        if (current == null) {
            throw new IllegalStateException("MCP client is disconnected; call connect() before getTools()");
        }
        return current;
    }

    /**
     * 创建服务进程、执行 MCP 初始化并发现工具。
     *
     * <p>方法具有幂等性；发现期间任何服务器失败时，已启动的所有服务进程都会关闭，
     * 并将错误显式传播给调用者。</p>
     *
     * @throws ToolExecutionException 启动、初始化或工具发现失败时抛出
     */
    public synchronized void connect() {
        if (tools != null) {
            return;
        }
        if (!trustRemoteCode) {
            throw new ConfigurationException("MCP server process requires explicit trust acknowledgement");
        }
        List<McpConnection> opened = new ArrayList<>();
        List<Tool> discovered = new ArrayList<>();
        Map<String, JsonNode> outputSchemas = new LinkedHashMap<>();
        try {
            boolean multipleServers = serverParameters.size() > 1;
            for (ServerConfiguration parameters : serverParameters) {
                McpConnection connection = createConnection(parameters);
                opened.add(connection);
                connection.initialize();
                List<Tool> serverTools = connection.listTools();
                for (Tool tool : serverTools) {
                    ToolSpec original = tool.spec();
                    String name = multipleServers ? parameters.name() + "__" + original.name() : original.name();
                    JsonNode outputSchema = tool instanceof McpTool mcpTool ? mcpTool.outputSchema() : null;
                    discovered.add(new McpTool(connection, original, name, structuredOutput, outputSchema));
                    if (outputSchema != null) {
                        outputSchemas.put(name, outputSchema.deepCopy());
                    }
                }
            }
            ensureUniqueToolNames(discovered);
            connections = List.copyOf(opened);
            tools = List.copyOf(discovered);
            this.toolOutputSchemas = immutableJsonMap(outputSchemas);
        } catch (RuntimeException error) {
            closeConnections(opened, error);
            connections = List.of();
            tools = null;
            toolOutputSchemas = Map.of();
            throw error;
        }
    }

    /**
     * 关闭所有 MCP 服务进程并使已发现工具失效。
     */
    public synchronized void disconnect() {
        tools = null;
        toolOutputSchemas = Map.of();
        List<McpConnection> current = connections;
        connections = List.of();
        closeConnections(current, null);
    }

    /**
     * 返回远端 MCP tools/list 中声明的可选 outputSchema 快照。
     *
     * @return 按本地暴露工具名排序、且 JSON 树不可被修改的映射
     */
    public Map<String, JsonNode> getToolOutputSchemas() {
        return immutableJsonMap(toolOutputSchemas);
    }

    /**
     * 列出全部已连接 MCP server 可用的 prompt 定义。
     *
     * @return prompt 列表；多服务时每项含本地 server alias
     */
    public List<PromptDefinition> listPrompts() {
        List<McpConnection> activeConnections = requireConnections();
        List<PromptDefinition> prompts = new ArrayList<>();
        for (int index = 0; index < activeConnections.size(); index++) {
            ServerConfiguration server = serverParameters.get(index);
            JsonNode result = activeConnections.get(index).request("prompts/list", MAPPER.createObjectNode(), null);
            JsonNode remotePrompts = result.path("prompts");
            if (!remotePrompts.isArray()) {
                throw new ToolExecutionException("MCP prompts/list response is missing prompts array");
            }
            for (JsonNode prompt : remotePrompts) {
                String remoteName = requiredRemoteText(prompt, "name", "prompt");
                List<PromptArgument> arguments = parsePromptArguments(prompt.path("arguments"), remoteName);
                prompts.add(new PromptDefinition(
                        server.name(),
                        remoteName,
                        activeConnections.size() > 1 ? server.name() + "__" + remoteName : remoteName,
                        prompt.path("description").asText(""),
                        arguments
                ));
            }
        }
        return List.copyOf(prompts);
    }

    /**
     * 通过 MCP prompts/get 渲染指定 server 上的 prompt。
     *
     * @param serverName 配置的 MCP server alias
     * @param promptName server 中的 prompt 名称
     * @param arguments prompt 字符串参数
     * @return prompt 描述和消息序列
     */
    public PromptResult getPrompt(String serverName, String promptName, Map<String, String> arguments) {
        ServerBinding binding = requireBinding(serverName);
        if (promptName == null || promptName.isBlank()) {
            throw new IllegalArgumentException("promptName must not be blank");
        }
        ObjectNode params = MAPPER.createObjectNode().put("name", promptName);
        ObjectNode argumentValues = MAPPER.createObjectNode();
        Map<String, String> values = Map.copyOf(arguments == null ? Map.of() : arguments);
        values.forEach((name, value) -> {
            if (name == null || name.isBlank() || value == null) {
                throw new IllegalArgumentException("prompt argument names and values must not be null or blank");
            }
            argumentValues.put(name, value);
        });
        if (!argumentValues.isEmpty()) {
            params.set("arguments", argumentValues);
        }
        JsonNode result = binding.connection().request("prompts/get", params, null);
        JsonNode messages = result.path("messages");
        if (!messages.isArray()) {
            throw new ToolExecutionException("MCP prompts/get response is missing messages array");
        }
        return new PromptResult(
                serverName,
                promptName,
                result.path("description").asText(""),
                copyJsonArray(messages)
        );
    }

    /**
     * 枚举所有 MCP servers 暴露的 resources URI。
     *
     * @return resource metadata 快照
     */
    public List<ResourceDefinition> listResources() {
        List<McpConnection> activeConnections = requireConnections();
        List<ResourceDefinition> resources = new ArrayList<>();
        for (int index = 0; index < activeConnections.size(); index++) {
            String serverName = serverParameters.get(index).name();
            JsonNode result = activeConnections.get(index).request("resources/list", MAPPER.createObjectNode(), null);
            JsonNode remoteResources = result.path("resources");
            if (!remoteResources.isArray()) {
                throw new ToolExecutionException("MCP resources/list response is missing resources array");
            }
            for (JsonNode resource : remoteResources) {
                resources.add(new ResourceDefinition(
                        serverName,
                        requiredRemoteText(resource, "uri", "resource"),
                        resource.path("name").asText(""),
                        resource.path("title").asText(""),
                        resource.path("description").asText(""),
                        resource.path("mimeType").asText("")
                ));
            }
        }
        return List.copyOf(resources);
    }

    /**
     * 按 server alias 和 URI 读取远端 resource 文本或图片/音频数据。
     *
     * @param serverName MCP server alias
     * @param resourceUri 远端 resource URI
     * @return 读取后映射的安全内容片段
     */
    public ResourceResult readResource(String serverName, String resourceUri) {
        ServerBinding binding = requireBinding(serverName);
        if (resourceUri == null || resourceUri.isBlank()) {
            throw new IllegalArgumentException("resourceUri must not be blank");
        }
        ObjectNode params = MAPPER.createObjectNode().put("uri", resourceUri);
        JsonNode result = binding.connection().request("resources/read", params, null);
        JsonNode contents = result.path("contents");
        if (!contents.isArray()) {
            throw new ToolExecutionException("MCP resources/read response is missing contents array");
        }
        List<ContentPart> content = new ArrayList<>();
        for (JsonNode resource : contents) {
            String mimeType = resource.path("mimeType").asText("text/plain");
            JsonNode text = resource.get("text");
            if (text != null && text.isTextual()) {
                if ("application/json".equalsIgnoreCase(mimeType)) {
                    try {
                        content.add(new ContentPart.Json(MAPPER.readTree(text.asText())));
                    } catch (JsonProcessingException error) {
                        throw new ToolExecutionException("MCP resource contains invalid JSON text", error);
                    }
                } else {
                    content.add(new ContentPart.Text(text.asText()));
                }
                continue;
            }
            JsonNode blob = resource.get("blob");
            if (blob == null || !blob.isTextual()) {
                throw new ToolExecutionException("MCP resource content must contain text or base64 blob");
            }
            byte[] data = decodeResourceBlob(blob.asText());
            URI uri = parseOptionalUri(resource.path("uri").asText(null));
            if (mimeType.startsWith("image/")) {
                content.add(new ContentPart.Image(uri, mimeType, data));
            } else if (mimeType.startsWith("audio/")) {
                content.add(new ContentPart.Audio(uri, mimeType, data, null));
            } else {
                throw new ToolExecutionException("Unsupported MCP resource blob MIME type: " + mimeType);
            }
        }
        if (content.isEmpty()) {
            throw new ToolExecutionException("MCP resource returned no readable content");
        }
        return new ResourceResult(serverName, resourceUri, content);
    }

    /**
     * 作为资源关闭 MCP 连接。
     */
    @Override
    public void close() {
        disconnect();
    }

    /**
     * 校验服务器名称在多服务器模式下唯一且可安全用于工具命名空间。
     *
     * @param parameters MCP 服务器配置
     */
    private static void validateServerNames(List<ServerConfiguration> parameters) {
        Set<String> names = new HashSet<>();
        for (ServerConfiguration server : parameters) {
            if (!names.add(server.name())) {
                throw new ConfigurationException("duplicate MCP server name: " + server.name());
            }
        }
    }

    /**
     * 根据配置选择 stdio 或 Streamable HTTP transport。
     *
     * @param parameters MCP server 配置
     * @return 未初始化连接
     */
    private static McpConnection createConnection(ServerConfiguration parameters) {
        if (parameters instanceof ServerParameters stdio) {
            return new ServerConnection(stdio);
        }
        if (parameters instanceof StreamableHttpServerParameters http) {
            return new StreamableHttpConnection(http);
        }
        if (parameters instanceof SseServerParameters sse) {
            return new LegacySseConnection(sse);
        }
        throw new ConfigurationException("Unsupported MCP server transport configuration");
    }

    /**
     * 关闭全部连接并保留首个清理错误及后续异常。
     *
     * @param closing 待关闭连接
     * @param originalFailure 初始化过程中的主异常；为空时清理异常向上传播
     */
    private static void closeConnections(List<? extends McpConnection> closing, RuntimeException originalFailure) {
        RuntimeException closeFailure = null;
        for (McpConnection connection : closing) {
            try {
                connection.close();
            } catch (RuntimeException error) {
                if (originalFailure != null) {
                    originalFailure.addSuppressed(error);
                } else if (closeFailure == null) {
                    closeFailure = error;
                } else {
                    closeFailure.addSuppressed(error);
                }
            }
        }
        if (originalFailure == null && closeFailure != null) {
            throw closeFailure;
        }
    }

    /**
     * 复制 schema Map 及其 JSON 节点，防止调用方修改内部元数据。
     *
     * @param schemas schema source map
     * @return 不可变的深度复制映射
     */
    private static Map<String, JsonNode> immutableJsonMap(Map<String, JsonNode> schemas) {
        Map<String, JsonNode> copies = new LinkedHashMap<>();
        schemas.forEach((name, schema) -> copies.put(name, schema.deepCopy()));
        return java.util.Collections.unmodifiableMap(copies);
    }

    /**
     * 返回当前连接快照，未连接时用明确错误而不是空列表表示失败。
     *
     * @return 活跃 MCP transport 列表
     */
    private List<McpConnection> requireConnections() {
        if (tools == null) {
            throw new IllegalStateException("MCP client is disconnected; call connect() first");
        }
        return connections;
    }

    /**
     * 按配置别名定位单个 MCP server。
     *
     * @param name server alias
     * @return server 配置及 transport
     */
    private ServerBinding requireBinding(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("serverName must not be blank");
        }
        List<McpConnection> active = requireConnections();
        for (int index = 0; index < serverParameters.size(); index++) {
            ServerConfiguration parameters = serverParameters.get(index);
            if (parameters.name().equals(name)) {
                return new ServerBinding(parameters, active.get(index));
            }
        }
        throw new ConfigurationException("Unknown MCP server: " + name);
    }

    /**
     * 解析 MCP prompts/list 的 argument metadata。
     *
     * @param json remote arguments array
     * @param promptName prompt 名称
     * @return 不可变 prompt 参数列表
     */
    private static List<PromptArgument> parsePromptArguments(JsonNode json, String promptName) {
        if (json.isMissingNode() || json.isNull()) {
            return List.of();
        }
        if (!json.isArray()) {
            throw new ToolExecutionException("MCP prompt '" + promptName + "' arguments must be an array");
        }
        List<PromptArgument> arguments = new ArrayList<>();
        for (JsonNode argument : json) {
            arguments.add(new PromptArgument(
                    requiredRemoteText(argument, "name", "prompt argument"),
                    argument.path("description").asText(""),
                    argument.path("required").asBoolean(false)
            ));
        }
        return List.copyOf(arguments);
    }

    /**
     * 校验远端 metadata object 中的必填字符串字段。
     *
     * @param object 远端 JSON object
     * @param field 字段名
     * @param kind 对象类型诊断
     * @return 非空字段
     */
    private static String requiredRemoteText(JsonNode object, String field, String kind) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ToolExecutionException("MCP " + kind + " is missing textual '" + field + "'");
        }
        return value.asText();
    }

    /**
     * 深度复制 JSON array 中的所有节点。
     *
     * @param source source array
     * @return immutable JSON list
     */
    private static List<JsonNode> copyJsonArray(JsonNode source) {
        List<JsonNode> copies = new ArrayList<>(source.size());
        source.forEach(value -> copies.add(value.deepCopy()));
        return List.copyOf(copies);
    }

    /**
     * 将资源 blob 的 base64 字符串解码为字节数组。
     *
     * @param encoded base64 data
     * @return 内容 bytes
     */
    private static byte[] decodeResourceBlob(String encoded) {
        try {
            return java.util.Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            throw new ToolExecutionException("MCP resource contains invalid base64 blob data", error);
        }
    }

    /**
     * 解析可选资源 URI 字段。
     *
     * @param value URI 字符串或 null
     * @return URI 或 null
     */
    private static URI parseOptionalUri(String value) {
        if (value == null) {
            return null;
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new ToolExecutionException("MCP resource contains an invalid URI", error);
        }
    }

    /**
     * 绑定 MCP server 参数及其已建立连接。
     *
     * @param parameters 配置
     * @param connection transport
     */
    private record ServerBinding(ServerConfiguration parameters, McpConnection connection) {
    }

    /**
     * MCP server 的 prompt 参数元数据。
     *
     * @param name 参数名
     * @param description 参数说明
     * @param required 是否必需
     */
    public record PromptArgument(String name, String description, boolean required) {
    }

    /**
     * MCP prompt 的工具安全 metadata。
     *
     * @param serverName 本地 server alias
     * @param name 远端 prompt 名称
     * @param exposedName 客户端返回的名称，多个服务时带前缀
     * @param description prompt 描述
     * @param arguments 参数 metadata
     */
    public record PromptDefinition(
            String serverName,
            String name,
            String exposedName,
            String description,
            List<PromptArgument> arguments
    ) {
        /**
         * 复制 prompt 参数快照。
         *
         * @param serverName server alias
         * @param name 远端 prompt 名称
         * @param exposedName 暴露名称
         * @param description 描述
         * @param arguments 参数列表
         */
        public PromptDefinition {
            arguments = List.copyOf(arguments);
        }
    }

    /**
     * MCP prompts/get 返回的消息快照。
     *
     * @param serverName server alias
     * @param promptName prompt 名称
     * @param description 结果描述
     * @param messages 按顺序排列的 JSON message objects
     */
    public record PromptResult(String serverName, String promptName, String description, List<JsonNode> messages) {
        /**
         * 复制所有返回消息的 JSON 树。
         *
         * @param serverName server alias
         * @param promptName prompt 名称
         * @param description 结果描述
         * @param messages JSON message array
         */
        public PromptResult {
            messages = copyJsonArray(MAPPER.valueToTree(messages));
        }

        /**
         * 返回消息列表的防御性 JSON 副本。
         *
         * @return 深度复制的 message nodes
         */
        @Override
        public List<JsonNode> messages() {
            return copyJsonArray(MAPPER.valueToTree(messages));
        }
    }

    /**
     * MCP server 的 resource metadata。
     *
     * @param serverName server alias
     * @param uri 可读取资源 URI
     * @param name resource name
     * @param title 可选展示标题
     * @param description 可选说明
     * @param mimeType 可选 MIME type
     */
    public record ResourceDefinition(
            String serverName,
            String uri,
            String name,
            String title,
            String description,
            String mimeType
    ) {
    }

    /**
     * MCP resources/read 返回的内容片段。
     *
     * @param serverName server alias
     * @param uri 读取的 URI
     * @param content 文本或媒体结果
     */
    public record ResourceResult(String serverName, String uri, List<ContentPart> content) {
        /**
         * 创建不可变资源内容快照。
         *
         * @param serverName server alias
         * @param uri 资源 URI
         * @param content 内容片段
         */
        public ResourceResult {
            content = List.copyOf(content);
        }
    }

    /**
     * 检查从所有服务器发现的工具名称无冲突。
     *
     * @param discovered 已发现工具
     */
    private static void ensureUniqueToolNames(List<Tool> discovered) {
        Set<String> names = new HashSet<>();
        for (Tool tool : discovered) {
            if (!names.add(tool.spec().name())) {
                throw new ConfigurationException("duplicate MCP tool name: " + tool.spec().name());
            }
        }
    }

    /**
     * 提取 MCP tool 可选 outputSchema 并拒绝非 object schema。
     *
     * @param remoteTool tools/list response 中的 tool object
     * @param name 远端工具名
     * @return outputSchema 深度副本；未提供时为 null
     */
    private static JsonNode parseOutputSchema(JsonNode remoteTool, String name) {
        JsonNode schema = remoteTool.get("outputSchema");
        if (schema == null) {
            return null;
        }
        if (!schema.isObject()) {
            throw new ToolExecutionException("MCP tool '" + name + "' has a non-object outputSchema");
        }
        return schema.deepCopy();
    }

    /**
     * 标记支持的 MCP stdio 和 Streamable HTTP server 配置类型。
     */
    public sealed interface ServerConfiguration
            permits ServerParameters, StreamableHttpServerParameters, SseServerParameters {
        /**
         * 返回连接的本地别名。
         *
         * @return 本地 server 名称
         */
        String name();
    }

    /**
     * 定义启动 MCP stdio 服务所需的命令参数。
     *
     * @param name 本地服务器别名；多服务器模式用于生成工具命名空间
     * @param command 要直接启动的可执行文件，不经 shell 解释
     * @param arguments 命令行参数
     * @param environment 叠加到继承环境中的变量
     * @param workingDirectory 可选工作目录
     * @param timeout 初始化、发现及每次调用的超时
     */
    public record ServerParameters(
            String name,
            String command,
            List<String> arguments,
            Map<String, String> environment,
            Path workingDirectory,
            Duration timeout
    ) implements ServerConfiguration {
        /**
         * 校验并防御性复制服务器启动参数。
         *
         * @param name 本地服务器别名
         * @param command 可执行文件路径或命令
         * @param arguments 命令参数
         * @param environment 进程环境变量
         * @param workingDirectory 可选工作目录
         * @param timeout 服务请求超时
         */
        public ServerParameters {
            if (name == null || !name.matches("[A-Za-z0-9_.-]+")) {
                throw new ConfigurationException("MCP server name must contain only letters, digits, '.', '_' or '-'");
            }
            if (command == null || command.isBlank()) {
                throw new ConfigurationException("MCP server command must not be blank");
            }
            arguments = List.copyOf(arguments == null ? List.of() : arguments);
            environment = Map.copyOf(environment == null ? Map.of() : environment);
            timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
            if (timeout.isNegative() || timeout.isZero()) {
                throw new ConfigurationException("MCP server timeout must be positive");
            }
        }

        /**
         * 使用空环境增量、默认超时和可选目录创建 stdio 配置。
         *
         * @param name 本地服务器别名
         * @param command 可执行文件路径或命令
         * @param arguments 命令参数
         */
        public ServerParameters(String name, String command, List<String> arguments) {
            this(name, command, arguments, Map.of(), null, DEFAULT_TIMEOUT);
        }
    }

    /**
     * 定义 MCP Streamable HTTP server endpoint 与请求参数。
     *
     * @param name 本地服务器别名
     * @param endpoint Streamable HTTP MCP endpoint
     * @param headers 额外 HTTP 请求头，适用于 Authorization 等服务端认证配置
     * @param timeout 初始化、发现与调用请求超时
     */
    public record StreamableHttpServerParameters(
            String name,
            URI endpoint,
            Map<String, String> headers,
            Duration timeout
    ) implements ServerConfiguration {
        /**
         * 校验 server URL、认证头和超时时长。
         *
         * @param name 本地服务器别名
         * @param endpoint Streamable HTTP MCP endpoint
         * @param headers 附加请求头
         * @param timeout HTTP 请求超时
         */
        public StreamableHttpServerParameters {
            validateServerName(name);
            endpoint = validateHttpEndpoint(endpoint, "MCP HTTP endpoint");
            headers = validateCustomHeaders(headers);
            timeout = validateTimeout(timeout);
        }

        /**
         * 使用默认超时和无附加请求头创建 HTTP server 配置。
         *
         * @param name 本地服务器别名
         * @param endpoint Streamable HTTP MCP endpoint
         */
        public StreamableHttpServerParameters(String name, URI endpoint) {
            this(name, endpoint, Map.of(), DEFAULT_TIMEOUT);
        }
    }

    /**
     * 定义 legacy MCP HTTP+SSE 传输的事件流 endpoint。
     *
     * @param name 本地服务器别名
     * @param endpoint SSE GET endpoint；服务端需通过 {@code endpoint} event 提供 POST URL
     * @param headers GET/POST 都使用的认证请求头
     * @param timeout SSE 初始化及每次 JSON-RPC 请求超时
     */
    public record SseServerParameters(
            String name,
            URI endpoint,
            Map<String, String> headers,
            Duration timeout
    ) implements ServerConfiguration {
        /**
         * 校验名称、HTTP endpoint、header 与超时。
         *
         * @param name 本地服务器别名
         * @param endpoint SSE endpoint
         * @param headers GET/POST 共用请求头
         * @param timeout 连接与请求超时
         */
        public SseServerParameters {
            validateServerName(name);
            endpoint = validateHttpEndpoint(endpoint, "MCP SSE endpoint");
            headers = validateCustomHeaders(headers);
            timeout = validateTimeout(timeout);
        }

        /**
         * 使用默认超时和无附加请求头创建 SSE server 配置。
         *
         * @param name 本地服务器别名
         * @param endpoint SSE endpoint
         */
        public SseServerParameters(String name, URI endpoint) {
            this(name, endpoint, Map.of(), DEFAULT_TIMEOUT);
        }
    }

    /**
     * 校验服务器名称。
     *
     * @param name 本地服务器别名
     */
    private static void validateServerName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]+")) {
            throw new ConfigurationException("MCP server name must contain only letters, digits, '.', '_' or '-'");
        }
    }

    /**
     * 校验绝对 HTTP(S) endpoint。
     *
     * @param endpoint 待校验地址
     * @param description transport 地址类型说明
     * @return 已校验 URI
     */
    private static URI validateHttpEndpoint(URI endpoint, String description) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) {
            throw new ConfigurationException(
                    description + " must be an absolute HTTP(S) URI without credentials or fragment"
            );
        }
        return endpoint;
    }

    /**
     * 校验并复制服务认证 headers。
     *
     * @param headers 原始自定义 headers
     * @return 不可变 header map
     */
    private static Map<String, String> validateCustomHeaders(Map<String, String> headers) {
        Map<String, String> validatedHeaders = Map.copyOf(headers == null ? Map.of() : headers);
        for (Map.Entry<String, String> header : validatedHeaders.entrySet()) {
            if (header.getKey().isBlank()
                    || header.getKey().contains("\r")
                    || header.getKey().contains("\n")
                    || header.getValue().contains("\r")
                    || header.getValue().contains("\n")) {
                throw new ConfigurationException("MCP HTTP headers must use valid names and values");
            }
            String key = header.getKey().toLowerCase(java.util.Locale.ROOT);
            if (Set.of("accept", "content-type", "mcp-session-id", "mcp-protocol-version").contains(key)) {
                throw new ConfigurationException(
                        "MCP HTTP header is managed by the client and must not be overridden: " + header.getKey()
                );
            }
        }
        return validatedHeaders;
    }

    /**
     * 校验正的 MCP 请求超时。
     *
     * @param timeout 待校验时长；null 时使用默认值
     * @return 正超时
     */
    private static Duration validateTimeout(Duration timeout) {
        Duration result = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (result.isZero() || result.isNegative()) {
            throw new ConfigurationException("MCP server timeout must be positive");
        }
        return result;
    }

    /**
     * 定义 MCP transport 需要实现的连接、发现、调用和关闭契约。
     */
    private interface McpConnection extends AutoCloseable {
        /**
         * 发送 JSON-RPC 请求并等待结果。
         *
         * @param method MCP method
         * @param params JSON 参数
         * @param cancellation 可选取消令牌
         * @return JSON-RPC result
         */
        JsonNode request(String method, JsonNode params, CancellationToken cancellation);

        /**
         * 执行 MCP 初始化握手。
         */
        void initialize();

        /**
         * 获取远端工具定义快照。
         *
         * @return 本地代理工具
         */
        List<Tool> listTools();

        /**
         * 调用远端工具。
         *
         * @param remoteName 远端工具名
         * @param arguments 输入参数
         * @param cancellation 取消令牌
         * @return MCP 工具调用结果
         */
        JsonNode callTool(String remoteName, JsonNode arguments, CancellationToken cancellation);

        /**
         * 关闭 transport 连接及其受管资源。
         */
        @Override
        void close();
    }

    /**
     * 管理一个 MCP stdio 子进程及其 JSON-RPC 请求/响应。
     */
    private static final class ServerConnection implements McpConnection {
        private final ServerParameters parameters;
        private final AtomicLong nextRequestId = new AtomicLong();
        private final BlockingQueue<ReaderEvent> responses = new ArrayBlockingQueue<>(256);
        private Process process;
        private BufferedWriter writer;
        private Thread readerThread;

        /**
         * 创建尚未启动的服务器连接。
         *
         * @param parameters 服务器进程参数
         */
        private ServerConnection(ServerParameters parameters) {
            this.parameters = parameters;
        }

        /**
         * 启动服务器、初始化 MCP 会话并发送 initialized 通知。
         */
        @Override
        public void initialize() {
            startProcess();
            ObjectNode params = MAPPER.createObjectNode()
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .set("capabilities", MAPPER.createObjectNode());
            params.set("clientInfo", MAPPER.createObjectNode()
                    .put("name", "smolagents-java")
                    .put("version", "1.0"));
            JsonNode result = request("initialize", params, null);
            String negotiatedVersion = result.path("protocolVersion").asText("");
            if (!PROTOCOL_VERSION.equals(negotiatedVersion)) {
                throw new ToolExecutionException(
                        "MCP server negotiated unsupported protocol version: " + negotiatedVersion
                );
            }
            sendNotification("notifications/initialized");
        }

        /**
         * 启动子进程并建立 UTF-8 JSON-RPC 读写通道。
         */
        private void startProcess() {
            List<String> command = new ArrayList<>();
            command.add(parameters.command());
            command.addAll(parameters.arguments());
            ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
            builder.environment().putAll(parameters.environment());
            if (parameters.workingDirectory() != null) {
                builder.directory(parameters.workingDirectory().toFile());
            }
            try {
                process = builder.start();
            } catch (IOException error) {
                throw new ToolExecutionException(
                        "Failed to start MCP server '" + parameters.name() + "': " + error.getMessage(),
                        error
                );
            }
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            readerThread = Thread.ofPlatform()
                    .name("mcp-stdio-reader-" + parameters.name())
                    .daemon(true)
                    .start(this::readResponses);
        }

        /**
         * 在专用线程中读取 MCP stdout，避免同步请求阻塞且可应用请求超时。
         */
        private void readResponses() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > MAX_PROTOCOL_LINE_LENGTH) {
                        responses.offer(ReaderEvent.failure(
                                new ToolExecutionException("MCP server response exceeded the maximum line size")
                        ));
                        process.destroyForcibly();
                        return;
                    }
                    if (!responses.offer(ReaderEvent.line(line))) {
                        responses.clear();
                        responses.offer(ReaderEvent.failure(
                                new ToolExecutionException("MCP server produced too many unread protocol messages")
                        ));
                        process.destroyForcibly();
                        return;
                    }
                }
                responses.offer(ReaderEvent.eof());
            } catch (IOException error) {
                responses.offer(ReaderEvent.failure(
                        new ToolExecutionException("Failed to read MCP server output", error)
                ));
            }
        }

        /**
         * 请求服务端列出工具并构造工具适配器。
         *
         * @return 当前会话暴露的工具定义
         */
        @Override
        public List<Tool> listTools() {
            JsonNode result = request("tools/list", MAPPER.createObjectNode(), null);
            JsonNode remoteTools = result.path("tools");
            if (!remoteTools.isArray()) {
                throw new ToolExecutionException("MCP tools/list response is missing tools array");
            }
            List<Tool> tools = new ArrayList<>();
            for (JsonNode remoteTool : remoteTools) {
                String name = remoteTool.path("name").asText("");
                if (name.isBlank()) {
                    throw new ToolExecutionException("MCP tool definition is missing its name");
                }
                String description = remoteTool.path("description").asText("");
                JsonNode schema = remoteTool.get("inputSchema");
                if (schema == null || !schema.isObject()) {
                    throw new ToolExecutionException("MCP tool '" + name + "' is missing inputSchema");
                }
                ObjectNode inputSchema = schema.deepCopy();
                if (!inputSchema.has("type")) {
                    inputSchema.put("type", "object");
                }
                ToolSpec spec;
                try {
                    spec = new ToolSpec(name, description, inputSchema, true);
                } catch (IllegalArgumentException error) {
                    throw new ToolExecutionException("MCP tool '" + name + "' has invalid inputSchema", error);
                }
                tools.add(new McpTool(this, spec, name, false, parseOutputSchema(remoteTool, name)));
            }
            return List.copyOf(tools);
        }

        /**
         * 调用远端 MCP 工具。
         *
         * @param remoteName MCP server 原始工具名
         * @param arguments JSON 参数
         * @param cancellation 当前工具调用取消令牌
         * @return MCP tools/call result
         */
        @Override
        public JsonNode callTool(String remoteName, JsonNode arguments, CancellationToken cancellation) {
            ObjectNode params = MAPPER.createObjectNode()
                    .put("name", remoteName)
                    .set("arguments", arguments.deepCopy());
            return request("tools/call", params, cancellation);
        }

        /**
         * 发送 JSON-RPC 请求并匹配对应响应 ID。
         *
         * @param method MCP 方法名
         * @param params JSON-RPC 参数
         * @param cancellation 可选取消令牌
         * @return result JSON
         */
        @Override
        public synchronized JsonNode request(String method, JsonNode params, CancellationToken cancellation) {
            ensureRunning();
            if (cancellation != null) {
                cancellation.throwIfCancelled();
            }
            long requestId = nextRequestId.incrementAndGet();
            ObjectNode request = MAPPER.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("id", requestId)
                    .put("method", method)
                    .set("params", params);
            writeMessage(request);
            long deadline = System.nanoTime() + parameters.timeout().toNanos();
            try {
                while (true) {
                    ReaderEvent event = pollResponse(deadline, cancellation);
                    if (event.failure() != null) {
                        throw event.failure();
                    }
                    if (event.endOfStream()) {
                        throw new ToolExecutionException(
                                "MCP server '" + parameters.name() + "' closed its output stream"
                        );
                    }
                    JsonNode message;
                    try {
                        message = parseMessage(event.line());
                    } catch (ToolExecutionException error) {
                        close();
                        throw error;
                    }
                    if (message.has("method") && !message.has("id")) {
                        continue;
                    }
                    JsonNode id = message.get("id");
                    if (id == null || !id.canConvertToLong() || id.asLong() != requestId) {
                        close();
                        throw new ToolExecutionException(
                                "MCP server returned a response with an unexpected request id"
                        );
                    }
                    JsonNode error = message.get("error");
                    if (error != null && !error.isNull()) {
                        String detail = error.path("message").asText("MCP request failed");
                        throw new ToolExecutionException("MCP " + method + " failed: " + detail);
                    }
                    JsonNode result = message.get("result");
                    if (result == null || result.isNull()) {
                        throw new ToolExecutionException("MCP " + method + " response is missing result");
                    }
                    return result.deepCopy();
                }
            } catch (AgentRunException error) {
                close();
                throw error;
            }
        }

        /**
         * 发送不需要响应的 JSON-RPC 通知。
         *
         * @param method MCP 通知方法
         */
        private synchronized void sendNotification(String method) {
            ensureRunning();
            writeMessage(MAPPER.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("method", method)
                    .set("params", MAPPER.createObjectNode()));
        }

        /**
         * 将 JSON-RPC 消息写入子进程 stdin。
         *
         * @param message 已构造协议消息
         */
        private void writeMessage(JsonNode message) {
            try {
                writer.write(MAPPER.writeValueAsString(message));
                writer.newLine();
                writer.flush();
            } catch (IOException error) {
                throw new ToolExecutionException("Failed to write MCP request to server", error);
            }
        }

        /**
         * 等待下一条协议消息，定期允许调用者取消请求。
         *
         * @param deadline 请求绝对截止时间
         * @param cancellation 可选取消令牌
         * @return reader 事件
         */
        private ReaderEvent pollResponse(long deadline, CancellationToken cancellation) {
            while (true) {
                if (cancellation != null) {
                    cancellation.throwIfCancelled();
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    close();
                    throw new ToolExecutionException(
                            "MCP request timed out after " + parameters.timeout().toSeconds() + " seconds"
                    );
                }
                try {
                    ReaderEvent event = responses.poll(
                            Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                            TimeUnit.NANOSECONDS
                    );
                    if (event != null) {
                        return event;
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    close();
                    throw new ToolExecutionException("Interrupted while waiting for MCP response", error);
                }
            }
        }

        /**
         * 解析并验证一行 JSON-RPC 消息。
         *
         * @param line 服务端输出的一行 JSON
         * @return 解析后的 JSON 对象
         */
        private static JsonNode parseMessage(String line) {
            try {
                JsonNode message = MAPPER.readTree(line);
                if (message == null || !message.isObject() || !"2.0".equals(message.path("jsonrpc").asText())) {
                    throw new ToolExecutionException("MCP server emitted an invalid JSON-RPC 2.0 message");
                }
                return message;
            } catch (JsonProcessingException error) {
                throw new ToolExecutionException("MCP server emitted invalid JSON", error);
            }
        }

        /**
         * 确认子进程仍然连接且可以接收请求。
         */
        private void ensureRunning() {
            if (process == null || !process.isAlive() || writer == null) {
                throw new ToolExecutionException("MCP server '" + parameters.name() + "' is not running");
            }
        }

        /**
         * 关闭 stdin 并优雅停止子进程，超时后强制终止。
         */
        @Override
        public void close() {
            Process current = process;
            if (current == null) {
                return;
            }
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException ignored) {
                current.destroy();
            }
            current.destroy();
            try {
                if (!current.waitFor(500, TimeUnit.MILLISECONDS)) {
                    current.destroyForcibly();
                    current.waitFor(500, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException error) {
                current.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            if (readerThread != null) {
                readerThread.interrupt();
            }
            process = null;
            writer = null;
        }
    }

    /**
     * 管理 Streamable HTTP MCP 会话、协议版本和服务端返回的 session ID。
     */
    private static final class StreamableHttpConnection implements McpConnection {
        private final StreamableHttpServerParameters parameters;
        private final HttpClient client;
        private final AtomicLong nextRequestId = new AtomicLong();
        private String protocolVersion;
        private String sessionId;
        private boolean closed;

        /**
         * 创建尚未初始化的 Streamable HTTP transport。
         *
         * @param parameters HTTP server endpoint 配置
         */
        private StreamableHttpConnection(StreamableHttpServerParameters parameters) {
            this.parameters = parameters;
            this.client = HttpClient.newBuilder().connectTimeout(parameters.timeout()).build();
        }

        /**
         * 完成 HTTP MCP initialize/initialized 握手。
         */
        @Override
        public void initialize() {
            ObjectNode params = MAPPER.createObjectNode()
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .set("capabilities", MAPPER.createObjectNode());
            params.set("clientInfo", MAPPER.createObjectNode()
                    .put("name", "smolagents-java")
                    .put("version", "1.0"));
            JsonNode result = request("initialize", params, null);
            String negotiatedVersion = result.path("protocolVersion").asText("");
            if (!PROTOCOL_VERSION.equals(negotiatedVersion)) {
                throw new ToolExecutionException(
                        "MCP server negotiated unsupported protocol version: " + negotiatedVersion
                );
            }
            protocolVersion = negotiatedVersion;
            sendNotification("notifications/initialized");
        }

        /**
         * 请求 HTTP MCP server 列出工具。
         *
         * @return 当前 endpoint 的工具定义
         */
        @Override
        public List<Tool> listTools() {
            JsonNode result = request("tools/list", MAPPER.createObjectNode(), null);
            JsonNode remoteTools = result.path("tools");
            if (!remoteTools.isArray()) {
                throw new ToolExecutionException("MCP tools/list response is missing tools array");
            }
            List<Tool> tools = new ArrayList<>();
            for (JsonNode remoteTool : remoteTools) {
                String name = remoteTool.path("name").asText("");
                if (name.isBlank()) {
                    throw new ToolExecutionException("MCP tool definition is missing its name");
                }
                String description = remoteTool.path("description").asText("");
                JsonNode schema = remoteTool.get("inputSchema");
                if (schema == null || !schema.isObject()) {
                    throw new ToolExecutionException("MCP tool '" + name + "' is missing inputSchema");
                }
                ObjectNode inputSchema = schema.deepCopy();
                if (!inputSchema.has("type")) {
                    inputSchema.put("type", "object");
                }
                ToolSpec spec;
                try {
                    spec = new ToolSpec(name, description, inputSchema, true);
                } catch (IllegalArgumentException error) {
                    throw new ToolExecutionException("MCP tool '" + name + "' has invalid inputSchema", error);
                }
                tools.add(new McpTool(this, spec, name, false, parseOutputSchema(remoteTool, name)));
            }
            return List.copyOf(tools);
        }

        /**
         * 发送 HTTP tools/call 请求并响应取消信号。
         *
         * @param remoteName MCP server 原始工具名
         * @param arguments JSON 参数
         * @param cancellation 当前调用取消令牌
         * @return MCP result JSON
         */
        @Override
        public JsonNode callTool(String remoteName, JsonNode arguments, CancellationToken cancellation) {
            ObjectNode params = MAPPER.createObjectNode()
                    .put("name", remoteName)
                    .set("arguments", arguments.deepCopy());
            return request("tools/call", params, cancellation);
        }

        /**
         * 发送 JSON-RPC HTTP POST 并提取匹配 request ID 的响应。
         *
         * @param method MCP 方法
         * @param params MCP 参数对象
         * @param cancellation 可选取消令牌
         * @return MCP result
         */
        @Override
        public synchronized JsonNode request(String method, JsonNode params, CancellationToken cancellation) {
            ensureOpen();
            if (cancellation != null) {
                cancellation.throwIfCancelled();
            }
            long requestId = nextRequestId.incrementAndGet();
            ObjectNode request = MAPPER.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("id", requestId)
                    .put("method", method)
                    .set("params", params);
            HttpResponse<String> response = send(request, cancellation);
            List<JsonNode> messages = parseResponseMessages(response);
            for (JsonNode message : messages) {
                if (message.has("method") && !message.has("id")) {
                    continue;
                }
                JsonNode id = message.get("id");
                if (id == null || !id.canConvertToLong() || id.asLong() != requestId) {
                    throw new ToolExecutionException("MCP HTTP response has an unexpected request id");
                }
                JsonNode protocolError = message.get("error");
                if (protocolError != null && !protocolError.isNull()) {
                    throw new ToolExecutionException(
                            "MCP " + method + " failed: " + protocolError.path("message").asText("request failed")
                    );
                }
                JsonNode result = message.get("result");
                if (result == null || result.isNull()) {
                    throw new ToolExecutionException("MCP " + method + " response is missing result");
                }
                return result.deepCopy();
            }
            throw new ToolExecutionException("MCP HTTP response did not contain a response for request " + requestId);
        }

        /**
         * 发送无响应 JSON-RPC notification/initialized。
         *
         * @param method 通知方法
         */
        private synchronized void sendNotification(String method) {
            ensureOpen();
            ObjectNode notification = MAPPER.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("method", method)
                    .set("params", MAPPER.createObjectNode());
            HttpResponse<String> response = send(notification, null);
            if (response.statusCode() != 202 && response.statusCode() != 204
                    && !(response.statusCode() >= 200 && response.statusCode() < 300
                    && response.body().isBlank())) {
                throw new ToolExecutionException(
                        "MCP notification returned unexpected HTTP " + response.statusCode()
                );
            }
        }

        /**
         * 发送 MCP POST 请求，绑定 session、版本、认证头和超时。
         *
         * @param message JSON-RPC 请求或通知
         * @param cancellation 可选取消令牌
         * @return HTTP response
         */
        private HttpResponse<String> send(JsonNode message, CancellationToken cancellation) {
            ensureOpen();
            if (cancellation != null) {
                cancellation.throwIfCancelled();
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(parameters.endpoint())
                    .timeout(parameters.timeout())
                    .header("Accept", "application/json, text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(message.toString(), StandardCharsets.UTF_8));
            if (protocolVersion != null) {
                builder.header("MCP-Protocol-Version", protocolVersion);
            }
            if (sessionId != null) {
                builder.header("Mcp-Session-Id", sessionId);
            }
            parameters.headers().forEach(builder::header);
            CompletableFuture<HttpResponse<String>> pending = client.sendAsync(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            HttpResponse<String> response = awaitResponse(pending, cancellation);
            if (response.body().length() > MAX_PROTOCOL_LINE_LENGTH) {
                throw new ToolExecutionException("MCP HTTP response exceeded the maximum body size");
            }
            updateSession(response);
            String negotiatedVersion = response.headers().firstValue("MCP-Protocol-Version").orElse(null);
            if (negotiatedVersion != null && protocolVersion != null && !protocolVersion.equals(negotiatedVersion)) {
                throw new ToolExecutionException("MCP HTTP response changed the negotiated protocol version");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = response.body().substring(0, Math.min(response.body().length(), 2_048));
                throw new ToolExecutionException(
                        "MCP HTTP request failed with status " + response.statusCode()
                                + (detail.isBlank() ? "" : ": " + detail)
                );
            }
            if (response.statusCode() == 202 && message.has("id")) {
                throw new ToolExecutionException("MCP server accepted a request without returning its response");
            }
            return response;
        }

        /**
         * 定期检查请求完成和取消状态，取消时中断在途 HTTP 请求。
         *
         * @param pending 异步 HTTP 请求
         * @param cancellation 可选取消令牌
         * @return 完整 HTTP response
         */
        private HttpResponse<String> awaitResponse(
                CompletableFuture<HttpResponse<String>> pending,
                CancellationToken cancellation
        ) {
            while (true) {
                if (cancellation != null && cancellation.isCancelled()) {
                    pending.cancel(true);
                    cancellation.throwIfCancelled();
                }
                try {
                    return pending.get(50, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // 定期检查取消标志，最终超时由 HttpRequest.timeout 触发。
                } catch (InterruptedException error) {
                    pending.cancel(true);
                    Thread.currentThread().interrupt();
                    throw new ToolExecutionException("Interrupted while waiting for MCP HTTP response", error);
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    throw new ToolExecutionException(
                            "MCP HTTP request failed: " + Objects.toString(cause.getMessage(), cause.getClass().getName()),
                            cause
                    );
                }
            }
        }

        /**
         * 读取服务端 session header 并拒绝同一连接中的 session ID 变化。
         *
         * @param response HTTP response
         */
        private void updateSession(HttpResponse<String> response) {
            String receivedSession = response.headers().firstValue("Mcp-Session-Id").orElse(null);
            if (receivedSession == null) {
                return;
            }
            if (sessionId != null && !sessionId.equals(receivedSession)) {
                throw new ToolExecutionException("MCP server changed its session ID");
            }
            sessionId = receivedSession;
        }

        /**
         * 解析 JSON response 或 SSE data events。
         *
         * @param response HTTP response
         * @return 一条或多条 JSON-RPC 消息
         */
        private static List<JsonNode> parseResponseMessages(HttpResponse<String> response) {
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(java.util.Locale.ROOT);
            if (contentType.startsWith("application/json")) {
                return List.of(ServerConnection.parseMessage(response.body()));
            }
            if (contentType.startsWith("text/event-stream")) {
                return parseServerSentEvents(response.body());
            }
            throw new ToolExecutionException("MCP HTTP server returned unsupported content type: " + contentType);
        }

        /**
         * 解析 SSE data 字段，忽略注释、event 名称和空心跳。
         *
         * @param body text/event-stream 响应内容
         * @return 解析后的 JSON-RPC 消息
         */
        private static List<JsonNode> parseServerSentEvents(String body) {
            List<JsonNode> messages = new ArrayList<>();
            StringBuilder data = new StringBuilder();
            for (String line : body.split("\\R", -1)) {
                if (line.isEmpty()) {
                    dispatchSseData(data, messages);
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    String value = line.substring(5);
                    data.append(value.startsWith(" ") ? value.substring(1) : value);
                }
            }
            dispatchSseData(data, messages);
            if (messages.isEmpty()) {
                throw new ToolExecutionException("MCP SSE response contained no JSON-RPC messages");
            }
            return List.copyOf(messages);
        }

        /**
         * 将一个 SSE data event 转换成 MCP 消息并清空当前 event 缓冲区。
         *
         * @param data 已累计 data 内容
         * @param messages 输出消息列表
         */
        private static void dispatchSseData(StringBuilder data, List<JsonNode> messages) {
            if (data.isEmpty()) {
                return;
            }
            String value = data.toString();
            data.setLength(0);
            if (!"[DONE]".equals(value)) {
                messages.add(ServerConnection.parseMessage(value));
            }
        }

        /**
         * 确认 HTTP MCP session 仍可使用。
         */
        private void ensureOpen() {
            if (closed) {
                throw new ToolExecutionException("MCP HTTP server '" + parameters.name() + "' is disconnected");
            }
        }

        /**
         * 关闭会话；有 session ID 时按 MCP 规范发送 DELETE。
         */
        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            String currentSession = sessionId;
            closed = true;
            if (currentSession == null) {
                return;
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(parameters.endpoint())
                    .timeout(parameters.timeout())
                    .header("Mcp-Session-Id", currentSession)
                    .method("DELETE", HttpRequest.BodyPublishers.noBody());
            if (protocolVersion != null) {
                builder.header("MCP-Protocol-Version", protocolVersion);
            }
            parameters.headers().forEach(builder::header);
            try {
                HttpResponse<String> response = client.send(
                        builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (!(response.statusCode() == 405
                        || (response.statusCode() >= 200 && response.statusCode() < 300))) {
                    throw new ToolExecutionException(
                            "MCP session termination failed with HTTP " + response.statusCode()
                    );
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ToolExecutionException("Interrupted while terminating MCP HTTP session", error);
            } catch (IOException error) {
                throw new ToolExecutionException("Failed to terminate MCP HTTP session", error);
            }
        }
    }

    /**
     * 管理 legacy HTTP+SSE MCP server 提供的 SSE event stream 与独立 POST message endpoint。
     */
    private static final class LegacySseConnection implements McpConnection {
        private final SseServerParameters parameters;
        private final HttpClient client;
        private final AtomicLong nextRequestId = new AtomicLong();
        private final BlockingQueue<ServerSentEvent> events = new ArrayBlockingQueue<>(256);
        private CompletableFuture<HttpResponse<InputStream>> streamFuture;
        private InputStream streamBody;
        private Thread readerThread;
        private URI messageEndpoint;
        private boolean closed;

        /**
         * 创建尚未打开 SSE stream 的 legacy transport。
         *
         * @param parameters SSE server 配置
         */
        private LegacySseConnection(SseServerParameters parameters) {
            this.parameters = parameters;
            this.client = HttpClient.newBuilder().connectTimeout(parameters.timeout()).build();
        }

        /**
         * 打开 SSE event stream、解析 POST endpoint 并执行 initialize 握手。
         */
        @Override
        public void initialize() {
            openEventStream();
            messageEndpoint = awaitMessageEndpoint();
            ObjectNode params = MAPPER.createObjectNode()
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .set("capabilities", MAPPER.createObjectNode());
            params.set("clientInfo", MAPPER.createObjectNode()
                    .put("name", "smolagents-java")
                    .put("version", "1.0"));
            JsonNode result = request("initialize", params, null);
            String version = result.path("protocolVersion").asText("");
            if (!PROTOCOL_VERSION.equals(version)) {
                throw new ToolExecutionException("MCP SSE server negotiated unsupported protocol version: " + version);
            }
            sendNotification("notifications/initialized");
        }

        /**
         * 请求 server 列出可用工具。
         *
         * @return 已发现的 MCP 工具代理
         */
        @Override
        public List<Tool> listTools() {
            JsonNode result = request("tools/list", MAPPER.createObjectNode(), null);
            JsonNode remoteTools = result.path("tools");
            if (!remoteTools.isArray()) {
                throw new ToolExecutionException("MCP tools/list response is missing tools array");
            }
            List<Tool> tools = new ArrayList<>();
            for (JsonNode remoteTool : remoteTools) {
                String name = remoteTool.path("name").asText("");
                if (name.isBlank()) {
                    throw new ToolExecutionException("MCP tool definition is missing its name");
                }
                JsonNode schema = remoteTool.get("inputSchema");
                if (schema == null || !schema.isObject()) {
                    throw new ToolExecutionException("MCP tool '" + name + "' is missing inputSchema");
                }
                ObjectNode inputSchema = schema.deepCopy();
                if (!inputSchema.has("type")) {
                    inputSchema.put("type", "object");
                }
                ToolSpec spec = new ToolSpec(
                        name,
                        remoteTool.path("description").asText(""),
                        inputSchema,
                        true
                );
                tools.add(new McpTool(this, spec, name, false, parseOutputSchema(remoteTool, name)));
            }
            return List.copyOf(tools);
        }

        /**
         * 通过消息 POST endpoint 调用远端 MCP 工具。
         *
         * @param remoteName 原始 MCP 工具名
         * @param arguments 已验证参数
         * @param cancellation 工具运行取消令牌
         * @return SSE 返回的 JSON-RPC result
         */
        @Override
        public JsonNode callTool(String remoteName, JsonNode arguments, CancellationToken cancellation) {
            ObjectNode params = MAPPER.createObjectNode()
                    .put("name", remoteName)
                    .set("arguments", arguments.deepCopy());
            return request("tools/call", params, cancellation);
        }

        /**
         * 开始 GET event stream，并在独立守护线程中读取 SSE。
         */
        private void openEventStream() {
            HttpRequest.Builder builder = HttpRequest.newBuilder(parameters.endpoint())
                    .timeout(parameters.timeout())
                    .header("Accept", "text/event-stream")
                    .GET();
            parameters.headers().forEach(builder::header);
            streamFuture = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response;
            try {
                response = streamFuture.get(parameters.timeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                streamFuture.cancel(true);
                throw new ToolExecutionException("Timed out while connecting to MCP SSE endpoint", error);
            } catch (InterruptedException error) {
                streamFuture.cancel(true);
                Thread.currentThread().interrupt();
                throw new ToolExecutionException("Interrupted while connecting to MCP SSE endpoint", error);
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                throw new ToolExecutionException(
                        "Unable to connect to MCP SSE endpoint: "
                                + Objects.toString(cause.getMessage(), cause.getClass().getName()),
                        cause
                );
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeStreamBody(response.body());
                throw new ToolExecutionException(
                        "MCP SSE endpoint returned HTTP " + response.statusCode()
                );
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(java.util.Locale.ROOT);
            if (!contentType.startsWith("text/event-stream")) {
                closeStreamBody(response.body());
                throw new ToolExecutionException("MCP SSE endpoint returned unsupported content type: " + contentType);
            }
            streamBody = response.body();
            readerThread = Thread.ofPlatform()
                    .name("mcp-sse-reader-" + parameters.name())
                    .daemon(true)
                    .start(this::readEvents);
        }

        /**
         * 读取 SSE event/data 字段，忽略注释与非 data 控制字段。
         */
        private void readEvents() {
            String eventName = "message";
            StringBuilder data = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(streamBody, StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > MAX_PROTOCOL_LINE_LENGTH) {
                        failEventReader("MCP SSE event exceeded the maximum line size");
                        return;
                    }
                    if (line.isEmpty()) {
                        if (!publishEvent(eventName, data)) {
                            return;
                        }
                        eventName = "message";
                    } else if (line.startsWith(":")) {
                        continue;
                    } else if (line.startsWith("event:")) {
                        String value = line.substring(6);
                        eventName = value.startsWith(" ") ? value.substring(1) : value;
                    } else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) {
                            data.append('\n');
                        }
                        String value = line.substring(5);
                        data.append(value.startsWith(" ") ? value.substring(1) : value);
                    }
                }
                publishEvent(eventName, data);
                events.offer(ServerSentEvent.eof());
            } catch (IOException error) {
                events.offer(ServerSentEvent.failure(
                        new ToolExecutionException("Failed to read MCP SSE event stream", error)
                ));
            }
        }

        /**
         * 将累计的 SSE event 加入响应队列，并清空缓冲区。
         *
         * @param eventName SSE event name
         * @param data SSE data fields
         * @return 缓冲区成功加入事件队列时为 true
         */
        private boolean publishEvent(String eventName, StringBuilder data) {
            if (data.isEmpty()) {
                return true;
            }
            ServerSentEvent event = ServerSentEvent.message(eventName, data.toString());
            data.setLength(0);
            if (events.offer(event)) {
                return true;
            }
            failEventReader("MCP SSE server produced too many unread events");
            return false;
        }

        /**
         * 记录 SSE reader 错误并关闭流以释放资源。
         *
         * @param message 错误描述
         */
        private void failEventReader(String message) {
            events.clear();
            events.offer(ServerSentEvent.failure(new ToolExecutionException(message)));
            closeStreamBody(streamBody);
        }

        /**
         * 等待并验证 SSE server 通过 endpoint event 发布的 POST URL。
         *
         * @return 与 event stream 同源的消息 URI
         */
        private URI awaitMessageEndpoint() {
            long deadline = System.nanoTime() + parameters.timeout().toNanos();
            while (true) {
                ServerSentEvent event = pollEvent(deadline, null);
                if (event.failure() != null) {
                    throw event.failure();
                }
                if (event.endOfStream()) {
                    throw new ToolExecutionException("MCP SSE stream closed before sending its endpoint event");
                }
                if (!"endpoint".equals(event.eventName())) {
                    continue;
                }
                URI endpoint;
                try {
                    endpoint = parameters.endpoint().resolve(event.data().strip());
                } catch (IllegalArgumentException error) {
                    throw new ToolExecutionException("MCP SSE endpoint event contains an invalid URL", error);
                }
                if (!sameOrigin(parameters.endpoint(), endpoint)
                        || endpoint.getUserInfo() != null
                        || endpoint.getFragment() != null) {
                    throw new ToolExecutionException(
                            "MCP SSE message endpoint must be same-origin and must not contain credentials or fragment"
                    );
                }
                return endpoint;
            }
        }

        /**
         * POST JSON-RPC，随后等待 SSE 消息流中匹配的响应 ID。
         *
         * @param method MCP method
         * @param params JSON-RPC params
         * @param cancellation 可选取消令牌
         * @return result object
         */
        @Override
        public synchronized JsonNode request(String method, JsonNode params, CancellationToken cancellation) {
            ensureOpen();
            if (cancellation != null) {
                cancellation.throwIfCancelled();
            }
            long requestId = nextRequestId.incrementAndGet();
            ObjectNode request = MAPPER.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("id", requestId)
                    .put("method", method)
                    .set("params", params);
            postMessage(request);
            long deadline = System.nanoTime() + parameters.timeout().toNanos();
            try {
                while (true) {
                    if (cancellation != null) {
                        cancellation.throwIfCancelled();
                    }
                    ServerSentEvent event = pollEvent(deadline, cancellation);
                    if (event.failure() != null) {
                        throw event.failure();
                    }
                    if (event.endOfStream()) {
                        throw new ToolExecutionException("MCP SSE stream closed while awaiting a response");
                    }
                    if ("endpoint".equals(event.eventName()) || "[DONE]".equals(event.data())) {
                        continue;
                    }
                    JsonNode message = ServerConnection.parseMessage(event.data());
                    if (message.has("method") && !message.has("id")) {
                        continue;
                    }
                    JsonNode id = message.get("id");
                    if (id == null || !id.canConvertToLong() || id.asLong() != requestId) {
                        close();
                        throw new ToolExecutionException("MCP SSE response has an unexpected request id");
                    }
                    JsonNode protocolError = message.get("error");
                    if (protocolError != null && !protocolError.isNull()) {
                        throw new ToolExecutionException(
                                "MCP " + method + " failed: " + protocolError.path("message").asText("request failed")
                        );
                    }
                    JsonNode result = message.get("result");
                    if (result == null || result.isNull()) {
                        throw new ToolExecutionException("MCP " + method + " response is missing result");
                    }
                    return result.deepCopy();
                }
            } catch (AgentRunException error) {
                close();
                throw error;
            }
        }

        /**
         * 发送无需响应的 JSON-RPC initialized notification。
         *
         * @param method 通知方法
         */
        private synchronized void sendNotification(String method) {
            ensureOpen();
            ObjectNode notification = MAPPER.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("method", method)
                    .set("params", MAPPER.createObjectNode());
            postMessage(notification);
        }

        /**
         * 将 JSON-RPC 内容 POST 到 endpoint event 提供的同源 URL。
         *
         * @param message JSON-RPC request 或 notification
         */
        private void postMessage(JsonNode message) {
            ensureOpen();
            HttpRequest.Builder builder = HttpRequest.newBuilder(messageEndpoint)
                    .timeout(parameters.timeout())
                    .header("Accept", "application/json, text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(message.toString(), StandardCharsets.UTF_8));
            parameters.headers().forEach(builder::header);
            HttpResponse<String> response;
            try {
                response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ToolExecutionException("Interrupted while posting MCP SSE message", error);
            } catch (IOException error) {
                throw new ToolExecutionException("Failed to post MCP SSE message", error);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ToolExecutionException("MCP SSE message endpoint returned HTTP " + response.statusCode());
            }
            if (response.body().length() > MAX_PROTOCOL_LINE_LENGTH) {
                throw new ToolExecutionException("MCP SSE POST response exceeded the maximum body size");
            }
        }

        /**
         * 从响应队列读取一条 SSE event，并检查超时或取消。
         *
         * @param deadline 请求 deadline
         * @param cancellation 可选取消令牌
         * @return 收到的 SSE event
         */
        private ServerSentEvent pollEvent(long deadline, CancellationToken cancellation) {
            while (true) {
                if (cancellation != null) {
                    cancellation.throwIfCancelled();
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    close();
                    throw new ToolExecutionException(
                            "Timed out after " + parameters.timeout().toSeconds() + " seconds waiting for MCP SSE event"
                    );
                }
                try {
                    ServerSentEvent event = events.poll(
                            Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                            TimeUnit.NANOSECONDS
                    );
                    if (event != null) {
                        return event;
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    close();
                    throw new ToolExecutionException("Interrupted while waiting for MCP SSE event", error);
                }
            }
        }

        /**
         * 确认 SSE stream 与 message endpoint 同源。
         *
         * @param first 事件流 URI
         * @param second 服务端提供的消息 URI
         * @return scheme、host、effective port 一致时为 true
         */
        private static boolean sameOrigin(URI first, URI second) {
            return first.getScheme().equalsIgnoreCase(second.getScheme())
                    && first.getHost().equalsIgnoreCase(second.getHost())
                    && effectivePort(first) == effectivePort(second);
        }

        /**
         * 计算 HTTP 或 HTTPS 默认端口。
         *
         * @param uri 待处理 URI
         * @return 显式或协议默认端口
         */
        private static int effectivePort(URI uri) {
            if (uri.getPort() >= 0) {
                return uri.getPort();
            }
            return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }

        /**
         * 确认 SSE transport 尚未断开。
         */
        private void ensureOpen() {
            if (closed || messageEndpoint == null) {
                throw new ToolExecutionException("MCP SSE server '" + parameters.name() + "' is disconnected");
            }
        }

        /**
         * 关闭 SSE input stream 与其异步 HTTP 请求。
         */
        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (streamFuture != null) {
                streamFuture.cancel(true);
            }
            closeStreamBody(streamBody);
            if (readerThread != null) {
                readerThread.interrupt();
            }
        }

        /**
         * 尽力关闭 HTTP SSE response body。
         *
         * @param body 输入流；可为空
         */
        private static void closeStreamBody(InputStream body) {
            if (body == null) {
                return;
            }
            try {
                body.close();
            } catch (IOException ignored) {
                // The transport is already terminating; there is no further recovery action.
            }
        }
    }

    /**
     * 保存单个已解析的 SSE event。
     *
     * @param eventName SSE event name
     * @param data event 数据
     * @param endOfStream 是否已到达 EOF
     * @param failure reader 错误
     */
    private record ServerSentEvent(
            String eventName,
            String data,
            boolean endOfStream,
            ToolExecutionException failure
    ) {
        /**
         * 创建普通 SSE data event。
         *
         * @param eventName event 名称
         * @param data event data
         * @return 普通 event
         */
        private static ServerSentEvent message(String eventName, String data) {
            return new ServerSentEvent(eventName, data, false, null);
        }

        /**
         * 创建流结束 event。
         *
         * @return EOF event
         */
        private static ServerSentEvent eof() {
            return new ServerSentEvent(null, null, true, null);
        }

        /**
         * 创建 SSE reader failure event。
         *
         * @param failure 传输错误
         * @return failure event
         */
        private static ServerSentEvent failure(ToolExecutionException failure) {
            return new ServerSentEvent(null, null, false, failure);
        }
    }

    /**
     * 将远端 MCP 工具定义转换为本地 Tool 契约。
     */
    private static final class McpTool implements Tool {
        private final McpConnection connection;
        private final String remoteName;
        private final boolean structuredOutput;
        private final JsonNode outputSchema;
        private final ToolSpec spec;

        /**
         * 创建指向指定 MCP server 工具的本地代理。
         *
         * @param connection MCP server 会话
         * @param remoteSpec 原始工具定义
         * @param exposedName 本地工具注册名称
         * @param structuredOutput 是否处理结构化结果
         * @param outputSchema MCP 服务器声明的输出 schema
         */
        private McpTool(
                McpConnection connection,
                ToolSpec remoteSpec,
                String exposedName,
                boolean structuredOutput,
                JsonNode outputSchema
        ) {
            this.connection = connection;
            this.remoteName = remoteSpec.name();
            this.structuredOutput = structuredOutput;
            this.outputSchema = outputSchema == null ? null : outputSchema.deepCopy();
            this.spec = new ToolSpec(
                    exposedName,
                    remoteSpec.description(),
                    remoteSpec.inputSchema(),
                    remoteSpec.enabled(),
                    outputSchema == null ? remoteSpec.outputType() : "object",
                    outputSchema
            );
        }

        /**
         * 返回 MCP 服务器声明的输出 schema 深拷贝。
         *
         * @return output schema；未声明时返回 null
         */
        private JsonNode outputSchema() {
            return outputSchema == null ? null : outputSchema.deepCopy();
        }

        /**
         * 返回适配后的工具名称与输入 schema。
         *
         * @return 本地 MCP 工具定义
         */
        @Override
        public ToolSpec spec() {
            return spec;
        }

        /**
         * 调用 MCP server 并映射多模态及可选结构化结果。
         *
         * @param arguments 已通过本地 JSON Schema 验证的参数
         * @param context 当前工具执行上下文
         * @return MCP 内容块和结构化输出
         */
        @Override
        public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
            Objects.requireNonNull(context, "context");
            JsonNode response = connection.callTool(remoteName, arguments, context.cancellation());
            if (response.path("isError").asBoolean(false)) {
                throw new ToolExecutionException("MCP tool '" + remoteName + "' reported an execution error: "
                        + textFromContent(response.path("content")));
            }
            List<ContentPart> content = parseContent(response.path("content"));
            JsonNode structured = structuredOutput ? response.get("structuredContent") : null;
            if (structuredOutput && structured == null) {
                structured = parseJsonText(content);
            }
            if (content.isEmpty() && structured == null) {
                throw new ToolExecutionException("MCP tool '" + remoteName + "' returned no content");
            }
            return new ToolResult(content, structured);
        }

        /**
         * 将 MCP Text/Image/Audio/embedded resource 内容块映射为 Java 内容片段。
         *
         * @param blocks MCP content 数组
         * @return 已转换的内容列表
         */
        private static List<ContentPart> parseContent(JsonNode blocks) {
            if (!blocks.isArray()) {
                return List.of();
            }
            List<ContentPart> content = new ArrayList<>();
            for (JsonNode block : blocks) {
                String type = block.path("type").asText("");
                switch (type) {
                    case "text" -> content.add(new ContentPart.Text(block.path("text").asText("")));
                    case "image" -> content.add(new ContentPart.Image(
                            null,
                            requiredMimeType(block, "image"),
                            decodeData(block, "image")
                    ));
                    case "audio" -> content.add(new ContentPart.Audio(
                            null,
                            requiredMimeType(block, "audio"),
                            decodeData(block, "audio"),
                            null
                    ));
                    case "resource" -> addEmbeddedResource(content, block.path("resource"));
                    default -> throw new ToolExecutionException("Unsupported MCP content block type: " + type);
                }
            }
            return List.copyOf(content);
        }

        /**
         * 映射 MCP embedded resource 的可呈现文本内容。
         *
         * @param content 输出内容列表
         * @param resource MCP embedded resource
         */
        private static void addEmbeddedResource(List<ContentPart> content, JsonNode resource) {
            JsonNode text = resource.get("text");
            if (text != null && text.isTextual()) {
                content.add(new ContentPart.Text(text.asText()));
                return;
            }
            String mimeType = resource.path("mimeType").asText("");
            if (mimeType.startsWith("image/") || mimeType.startsWith("audio/")) {
                JsonNode blob = resource.get("blob");
                if (blob == null || !blob.isTextual()) {
                    throw new ToolExecutionException("MCP embedded media resource is missing base64 blob data");
                }
                byte[] data = decodeBase64(blob.asText(), "embedded resource");
                if (mimeType.startsWith("image/")) {
                    content.add(new ContentPart.Image(null, mimeType, data));
                } else {
                    content.add(new ContentPart.Audio(null, mimeType, data, null));
                }
                return;
            }
            throw new ToolExecutionException("Unsupported MCP embedded resource content");
        }

        /**
         * 读取并校验 base64 媒体字段。
         *
         * @param block MCP 媒体块
         * @param kind 内容类型描述
         * @return 解码后的媒体字节
         */
        private static byte[] decodeData(JsonNode block, String kind) {
            JsonNode data = block.get("data");
            if (data == null || !data.isTextual()) {
                throw new ToolExecutionException("MCP " + kind + " block is missing base64 data");
            }
            return decodeBase64(data.asText(), kind);
        }

        /**
         * 解码标准 base64 字符串并保留明确的格式错误。
         *
         * @param value base64 字符串
         * @param kind 数据类型说明
         * @return 解码后的 bytes
         */
        private static byte[] decodeBase64(String value, String kind) {
            try {
                return java.util.Base64.getDecoder().decode(value);
            } catch (IllegalArgumentException error) {
                throw new ToolExecutionException("MCP " + kind + " block contains invalid base64 data", error);
            }
        }

        /**
         * 读取媒体块必需的 MIME 类型。
         *
         * @param block MCP 媒体块
         * @param kind 类型描述
         * @return MIME 类型
         */
        private static String requiredMimeType(JsonNode block, String kind) {
            String mimeType = block.path("mimeType").asText("");
            if (mimeType.isBlank()) {
                throw new ToolExecutionException("MCP " + kind + " block is missing mimeType");
            }
            return mimeType;
        }

        /**
         * 在 structuredContent 缺失时尝试从单一 JSON 文本块恢复结构化结果。
         *
         * @param content 已解析内容
         * @return JSON 结果；无有效 JSON 文本时返回 null
         */
        private static JsonNode parseJsonText(List<ContentPart> content) {
            if (content.size() != 1 || !(content.getFirst() instanceof ContentPart.Text text)) {
                return null;
            }
            try {
                return MAPPER.readTree(text.text());
            } catch (JsonProcessingException ignored) {
                return null;
            }
        }

        /**
         * 汇总 MCP 错误响应的文本块，用于错误诊断。
         *
         * @param blocks MCP content 数组
         * @return 合并的错误消息
         */
        private static String textFromContent(JsonNode blocks) {
            if (!blocks.isArray()) {
                return "server returned isError=true";
            }
            List<String> messages = new ArrayList<>();
            for (JsonNode block : blocks) {
                if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) {
                    messages.add(block.path("text").asText());
                }
            }
            return messages.isEmpty() ? "server returned isError=true" : String.join("\n", messages);
        }
    }

    /**
     * 表示 stdio reader 捕获的消息、EOF 或协议读取错误。
     *
     * @param line JSON-RPC 消息行
     * @param endOfStream 是否已到达 EOF
     * @param failure reader 发生的错误
     */
    private record ReaderEvent(String line, boolean endOfStream, ToolExecutionException failure) {
        /**
         * 创建普通协议行事件。
         *
         * @param line JSON-RPC 字符串行
         * @return reader event
         */
        private static ReaderEvent line(String line) {
            return new ReaderEvent(line, false, null);
        }

        /**
         * 创建子进程输出结束事件。
         *
         * @return EOF event
         */
        private static ReaderEvent eof() {
            return new ReaderEvent(null, true, null);
        }

        /**
         * 创建 reader 错误事件。
         *
         * @param failure reader 错误
         * @return failed event
         */
        private static ReaderEvent failure(ToolExecutionException failure) {
            return new ReaderEvent(null, false, failure);
        }
    }
}
