package org.fzb.smolagents.adapters.ollama;

import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.AgentInput;
import org.fzb.smolagents.core.RunStatus;
import org.fzb.smolagents.agent.ToolCallingAgent;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.GenerationOptions;
import org.fzb.smolagents.model.GenerationRequest;
import org.fzb.smolagents.model.MessageRole;
import org.fzb.smolagents.tool.Tool;
import org.fzb.smolagents.tool.ToolExecutionContext;
import org.fzb.smolagents.tool.ToolResult;
import org.fzb.smolagents.tool.ToolSpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通过显式 opt-in 环境变量运行本机 Ollama 和 Gemma 集成验证。
 */
class OllamaModelIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证本机 Gemma 能经 Ollama 原生 API 返回文本。
     */
    @Test
    void gemmaSmokeRespondsThroughLocalOllama() {
        Assumptions.assumeTrue("1".equals(System.getenv("SMOLAGENTS_OLLAMA_TESTS")));
        OllamaModel model = configuredModel();
        var response = model.generate(new GenerationRequest(
                List.of(ChatMessage.text(MessageRole.USER, "Reply with only the word OK.")),
                new GenerationOptions(0.0, 16, configuredTimeout(), null),
                List.of(),
                new CancellationToken()
        ));

        assertEqualsRoleAndContent(response);
    }

    /**
     * 验证 Gemma 能按声明请求执行 echo 工具。
     */
    @Test
    void gemmaCanRequestADeclaredTool() {
        Assumptions.assumeTrue("1".equals(System.getenv("SMOLAGENTS_OLLAMA_TESTS")));
        OllamaModel model = configuredModel();
        ToolSpec echo = new ToolSpec(
                "echo",
                "Echo the supplied text.",
                MAPPER.createObjectNode()
                        .put("type", "object")
                        .set("properties", MAPPER.createObjectNode()
                                .set("text", MAPPER.createObjectNode().put("type", "string"))),
                true
        );
        var response = model.generate(new GenerationRequest(
                List.of(ChatMessage.text(
                        MessageRole.USER,
                        "You must call the echo tool with text set to fzb. Do not answer directly."
                )),
                new GenerationOptions(0.0, 64, configuredTimeout(), null),
                List.of(echo),
                new CancellationToken()
        ));

        assertFalse(response.toolCalls().isEmpty(),
                "Gemma must return a tool call to pass the configured Ollama tool-calling check");
        assertTrue(response.toolCalls().stream().anyMatch(call -> call.name().equals("echo")));
    }

    /**
     * 验证 Gemma 能通过 ToolCallingAgent 调用加法工具并给出答案。
     */
    @Test
    void gemmaCompletesToolCallingAgentTask() {
        Assumptions.assumeTrue("1".equals(System.getenv("SMOLAGENTS_OLLAMA_TESTS")));
        AddTool addTool = new AddTool();
        ToolCallingAgent agent = new ToolCallingAgent(
                configuredModel(),
                List.of(addTool),
                4,
                "You are an assistant. Use tools to solve tasks and call final_answer with the result."
        );

        var result = agent.runFull(
                new AgentInput("Use the add tool to calculate 4 + 6. Return the result.", Map.of()),
                new org.fzb.smolagents.core.RunOptions(
                        true, 4, true, configuredTimeout(), new CancellationToken()
                )
        );

        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals(1, addTool.invocations);
        assertTrue(result.output().plainText()
                        .map(text -> text.contains("10"))
                        .orElseGet(() -> result.output().structuredContent() != null
                                && "10".equals(result.output().structuredContent().asText())),
                "Gemma final answer must contain the calculated value 10");
        assertTrue(agent.memory().snapshot().stream()
                .filter(org.fzb.smolagents.memory.ActionStep.class::isInstance)
                .map(org.fzb.smolagents.memory.ActionStep.class::cast)
                .flatMap(step -> step.observations().stream())
                .anyMatch(observation -> "10".equals(observation.result().plainText().orElse(""))),
                "Agent memory must record the add tool result");
    }

    /**
     * 根据环境变量构造本机 Ollama 模型及推理参数。
     *
     * @return 集成测试用 Ollama 客户端
     */
    private static OllamaModel configuredModel() {
        String modelId = System.getenv().getOrDefault("OLLAMA_MODEL", "gemma4:26b");
        URI baseUri = URI.create(System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434/v1"));
        Map<String, JsonNode> options = new HashMap<>();
        String numGpu = System.getenv().getOrDefault("OLLAMA_NUM_GPU", "0");
        if (!numGpu.isBlank()) {
            options.put("num_gpu", MAPPER.getNodeFactory().numberNode(Integer.parseInt(numGpu)));
        }
        boolean think = Boolean.parseBoolean(System.getenv().getOrDefault("OLLAMA_THINK", "false"));
        return new OllamaModel(modelId, baseUri, configuredTimeout(), options, think);
    }

    /**
     * 读取集成测试请求超时时间。
     *
     * @return 配置超时时长
     */
    private static Duration configuredTimeout() {
        String timeout = System.getenv().getOrDefault("OLLAMA_TEST_TIMEOUT_SECONDS", "240");
        return Duration.ofSeconds(Long.parseLong(timeout));
    }

    /**
     * 断言模型返回 assistant 角色和非空内容。
     *
     * @param response 模型响应
     */
    private static void assertEqualsRoleAndContent(org.fzb.smolagents.model.ChatMessage response) {
        assertEquals(MessageRole.ASSISTANT, response.role());
        assertFalse(response.content().isEmpty(), "Gemma smoke response must contain content");
    }

    /**
     * 用于端到端测试并统计调用次数的整数加法工具。
     */
    private static final class AddTool implements Tool {
        private final ToolSpec spec = new ToolSpec(
                "add",
                "Add two integers and return their sum.",
                createSchema(),
                true
        );
        private int invocations;

        /**
         * 返回加法工具的模型可见定义。
         *
         * @return 工具定义
         */
        @Override
        public ToolSpec spec() {
            return spec;
        }

        /**
         * 计算参数 a 与 b 的整数和。
         *
         * @param arguments 已校验的调用参数
         * @param context 工具执行上下文
         * @return 和的文本结果
         */
        @Override
        public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
            invocations++;
            int sum = arguments.path("a").asInt() + arguments.path("b").asInt();
            return ToolResult.text(Integer.toString(sum));
        }

        /**
         * 创建要求提供两个整数参数的 JSON Schema。
         *
         * @return 加法工具 schema
         */
        private static JsonNode createSchema() {
            var schema = MAPPER.createObjectNode();
            var properties = MAPPER.createObjectNode();
            properties.set("a", MAPPER.createObjectNode().put("type", "integer"));
            properties.set("b", MAPPER.createObjectNode().put("type", "integer"));
            schema.put("type", "object");
            schema.set("properties", properties);
            schema.putArray("required").add("a").add("b");
            return schema;
        }
    }
}
