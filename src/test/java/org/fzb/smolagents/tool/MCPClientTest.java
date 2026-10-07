package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.AgentRunException;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用本机 MCP stdio fixture 验证握手、工具发现、调用与资源生命周期。
 */
class MCPClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证 initialize、tools/list、输入 schema、本地工具调用和 structuredContent 映射。
     *
     * @throws Exception Python fixture 启动或 JSON 测试数据处理失败时抛出
     */
    @Test
    void discoversAndInvokesToolsWithStructuredOutput() throws Exception {
        try (MCPClient client = client(server("single"))) {
            Map<String, Tool> tools = client.getTools().stream()
                    .collect(Collectors.toMap(tool -> tool.spec().name(), tool -> tool));
            assertTrue(tools.containsKey("echo"));
            assertEquals("Echo text.", tools.get("echo").spec().description());
            var outputSchemas = client.getToolOutputSchemas();
            assertEquals(
                    MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"echoed\":{\"type\":\"string\"}}}"),
                    outputSchemas.get("echo")
            );
            assertEquals("object", tools.get("echo").spec().outputType());
            assertEquals(outputSchemas.get("echo"), tools.get("echo").spec().outputSchema());
            ((com.fasterxml.jackson.databind.node.ObjectNode) tools.get("echo").spec().outputSchema())
                    .put("type", "array");
            assertEquals("object", tools.get("echo").spec().outputSchema().path("type").asText());
            ((com.fasterxml.jackson.databind.node.ObjectNode) outputSchemas.get("echo")).put("type", "array");
            assertEquals("object", client.getToolOutputSchemas().get("echo").path("type").asText());

            ToolCall call = new ToolCall("mcp-echo", "echo", MAPPER.readTree("{\"text\":\"hello\"}"));
            ToolRegistry registry = new ToolRegistry();
            registry.register(tools.get("echo"));
            ToolResult result = registry.invoke(call, context("mcp-echo"));

            assertEquals("echo:hello", text(result));
            assertEquals(MAPPER.readTree("{\"echoed\":\"hello\"}"), result.structuredContent());
        }
    }

    /**
     * 验证启用结构化输出时可从单个 JSON 文本块恢复 JSON 结果。
     *
     * @throws Exception Python fixture 启动或 JSON 测试数据处理失败时抛出
     */
    @Test
    void parsesJsonTextAsStructuredFallback() throws Exception {
        try (MCPClient client = client(server("structured"))) {
            Tool jsonTool = client.getTools().stream()
                    .filter(tool -> tool.spec().name().equals("json_text"))
                    .findFirst()
                    .orElseThrow();
            ToolResult result = jsonTool.execute(
                    MAPPER.createObjectNode(),
                    context("mcp-json")
            );

            assertEquals("{\"answer\":42}", text(result));
            assertEquals(MAPPER.readTree("{\"answer\":42}"), result.structuredContent());
        }
    }

    /**
     * 验证远端 isError 被转换为执行异常，且预先取消会阻止请求。
     *
     * @throws Exception Python fixture 启动或 JSON 测试数据处理失败时抛出
     */
    @Test
    void surfacesRemoteErrorsAndHonorsCancellation() throws Exception {
        try (MCPClient client = client(server("errors"))) {
            Tool failing = client.getTools().stream()
                    .filter(tool -> tool.spec().name().equals("fail"))
                    .findFirst()
                    .orElseThrow();
            ToolExecutionException remoteError = assertThrows(
                    ToolExecutionException.class,
                    () -> failing.execute(MAPPER.createObjectNode(), context("mcp-fail"))
            );
            assertTrue(remoteError.getMessage().contains("remote execution failed"));

            CancellationToken cancelled = new CancellationToken();
            cancelled.cancel();
            assertThrows(
                    AgentRunException.class,
                    () -> client.getTools().getFirst().execute(
                            MAPPER.readTree("{\"text\":\"hello\"}"),
                            new ToolExecutionContext(UUID.randomUUID(), "cancelled-call", cancelled)
                    )
            );
        }
    }

    /**
     * 验证多服务器工具自动命名空间及断开后的工具失效。
     *
     * @throws Exception Python fixture 启动或 JSON 测试数据处理失败时抛出
     */
    @Test
    void namespacesMultipleServersAndInvalidatesToolsOnDisconnect() throws Exception {
        MCPClient client = new MCPClient(List.of(server("alpha"), server("beta")), true, false);
        Tool betaEcho = client.getTools().stream()
                .filter(tool -> tool.spec().name().equals("beta__echo"))
                .findFirst()
                .orElseThrow();

        assertEquals(
                "echo:from beta",
                text(betaEcho.execute(
                        MAPPER.readTree("{\"text\":\"from beta\"}"),
                        context("beta-call")
                ))
        );
        client.disconnect();
        assertThrows(IllegalStateException.class, client::getTools);
        assertThrows(
                ToolExecutionException.class,
                () -> betaEcho.execute(
                        MAPPER.readTree("{\"text\":\"after close\"}"),
                        context("closed-call")
                )
        );
        client.connect();
        assertTrue(client.getTools().stream().anyMatch(tool -> tool.spec().name().equals("beta__echo")));
        client.close();
    }

    /**
     * 验证未显式确认信任时客户端不会启动配置的服务器命令。
     *
     * @throws Exception fixture 路径解析失败时抛出
     */
    @Test
    void requiresExplicitServerTrust() throws Exception {
        assertThrows(
                ConfigurationException.class,
                () -> new MCPClient(server("untrusted"), false, false)
        );
    }

    /**
     * 验证 MCP ToolCollection 持有工具并可安全重复关闭。
     *
     * @throws Exception Python fixture 启动或资源处理失败时抛出
     */
    @Test
    void createsManagedToolCollectionFromMcp() throws Exception {
        ToolCollection collection = ToolCollection.fromMcp(server("collection"), true, false);
        assertTrue(collection.tools().stream().anyMatch(tool -> tool.spec().name().equals("echo")));
        collection.close();
        collection.close();
    }

    /**
     * 验证 prompt metadata 发现、参数传递和 JSON-RPC prompts/get 输出。
     *
     * @throws Exception Python fixture 或 JSON test data 失败时抛出
     */
    @Test
    void listsAndRendersPrompts() throws Exception {
        try (MCPClient client = client(server("prompt-server"))) {
            MCPClient.PromptDefinition definition = client.listPrompts().getFirst();

            assertEquals("greet", definition.exposedName());
            assertEquals("Create a greeting prompt.", definition.description());
            assertTrue(definition.arguments().getFirst().required());

            MCPClient.PromptResult prompt = client.getPrompt(
                    "prompt-server",
                    "greet",
                    Map.of("name", "Ada")
            );
            assertEquals("Rendered greeting.", prompt.description());
            assertEquals(
                    "Hello Ada",
                    prompt.messages().getFirst().path("content").path("text").asText()
            );
        }
    }

    /**
     * 验证 resources/list/read 映射为纯文本内容。
     *
     * @throws Exception Python fixture 或 JSON test data 失败时抛出
     */
    @Test
    void listsAndReadsResources() throws Exception {
        try (MCPClient client = client(server("resource-server"))) {
            MCPClient.ResourceDefinition resource = client.listResources().getFirst();
            assertEquals("test://fixture/readme", resource.uri());
            assertEquals("text/plain", resource.mimeType());

            MCPClient.ResourceResult result = client.readResource("resource-server", resource.uri());
            assertEquals("MCP resource body", text(new ToolResult(result.content(), null)));
        }
    }

    /**
     * 验证 ToolCollection 的 MCP 工厂同样要求显式信任确认。
     *
     * @throws Exception fixture 路径解析失败时抛出
     */
    @Test
    void requiresTrustWhenCreatingMcpToolCollection() throws Exception {
        assertThrows(
                ConfigurationException.class,
                () -> ToolCollection.fromMcp(server("untrusted-collection"), false, false)
        );
    }

    /**
     * 使用测试 fixture 创建标准输入/输出服务器参数。
     *
     * @param name 本地服务器名称
     * @return 启动 Python fixture 的配置
     * @throws URISyntaxException 测试资源 URL 无法转换为本地路径时抛出
     */
    private static MCPClient.ServerParameters server(String name) throws URISyntaxException {
        var resource = MCPClientTest.class.getResource("/mcp_stdio_server.py");
        if (resource == null) {
            throw new IllegalStateException("MCP test server resource is missing");
        }
        String python = System.getenv().getOrDefault("PYTHON", "python");
        return new MCPClient.ServerParameters(
                name,
                python,
                List.of("-u", Path.of(resource.toURI()).toString()),
                Map.of(),
                null,
                Duration.ofSeconds(5)
        );
    }

    /**
     * 启动单服务器测试客户端并启用 structuredContent。
     *
     * @param parameters stdio 服务启动参数
     * @return 已完成握手和工具发现的客户端
     */
    private static MCPClient client(MCPClient.ServerParameters parameters) {
        return new MCPClient(parameters, true, true);
    }

    /**
     * 创建本地工具调用上下文。
     *
     * @param callId 工具调用 ID
     * @return 独立上下文
     */
    private static ToolExecutionContext context(String callId) {
        return new ToolExecutionContext(UUID.randomUUID(), callId, new CancellationToken());
    }

    /**
     * 提取结果中的第一段文本。
     *
     * @param result 工具调用结果
     * @return 文本内容
     */
    private static String text(ToolResult result) {
        return assertInstanceOf(ContentPart.Text.class, result.content().getFirst()).text();
    }
}
