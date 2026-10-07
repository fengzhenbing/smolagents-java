package org.fzb.smolagents.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fzb.smolagents.tool.FinalAnswerTool;
import org.fzb.smolagents.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证规划模板生成的工具提示词与 Python Tool prompt 的可观察格式一致。
 */
class PlanningPromptTemplatesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证工具调用规划文本保留 Python 输入字典和实际输出类型。
     */
    @Test
    void rendersPythonToolCallingPromptMetadata() {
        ToolSpec tool = tool("echo", "Return the input text.", "text", "Text to echo", "string", null);
        String prompt = PlanningPromptTemplates.toolCallingDefaults()
                .renderInitialPlan("find a value", List.of(tool));

        assertTrue(prompt.contains(
                "- echo: Return the input text.\n"
                        + "    Takes inputs: {'text': {'type': 'string', 'description': 'Text to echo'}}\n"
                        + "    Returns an output of type: string"
        ));
    }

    /**
     * 验证 CodeAgent 工具 stub 使用 Python 的 schema 类型、输出类型和结构化返回说明。
     */
    @Test
    void rendersPythonCodePromptWithStructuredOutputSchema() {
        ObjectNode outputSchema = MAPPER.createObjectNode()
                .put("type", "object");
        outputSchema.set("properties", MAPPER.createObjectNode()
                .set("value", MAPPER.createObjectNode().put("type", "string")));
        ToolSpec tool = tool("lookup", "Look up a value.", "query", "Search query", "string", outputSchema);
        String prompt = PlanningPromptTemplates.codeAgentDefaults()
                .renderInitialPlan("find a value", List.of(tool));
        String renderedToolPrompt = prompt.substring(
                prompt.indexOf("def lookup"),
                prompt.indexOf("\n```", prompt.indexOf("def lookup"))
        );
        if (renderedToolPrompt.endsWith("\n")) {
            renderedToolPrompt = renderedToolPrompt.substring(0, renderedToolPrompt.length() - 1);
        }

        assertEquals(
                "def lookup(query: string) -> dict:\n"
                        + "    \"\"\"Look up a value.\n"
                        + "\n"
                        + "    Important: This tool returns structured output! Use the JSON schema below to directly "
                        + "access fields like result['field_name']. NO print() statements needed to inspect the output!\n"
                        + "\n"
                        + "    Args:\n"
                        + "        query: Search query\n"
                        + "\n"
                        + "    Returns:\n"
                        + "        dict (structured output): This tool ALWAYS returns a dictionary that strictly "
                        + "adheres to the following JSON schema:\n"
                        + "            {\n"
                        + "                \"type\": \"object\",\n"
                        + "                \"properties\": {\n"
                        + "                    \"value\": {\n"
                        + "                        \"type\": \"string\"\n"
                        + "                    }\n"
                        + "                }\n"
                        + "            }\n"
                        + "    \"\"\"",
                renderedToolPrompt
        );
    }

    /**
     * 验证 ToolSpec 默认输出类型和结构化输出 schema 均不可被调用方修改。
     */
    @Test
    void preservesToolOutputMetadataDefensively() {
        ObjectNode inputSchema = MAPPER.createObjectNode().put("type", "object");
        ObjectNode outputSchema = MAPPER.createObjectNode().put("type", "object");
        ToolSpec defaultTool = new ToolSpec("echo", "Echoes text.", inputSchema, true);
        ToolSpec finalAnswer = new ToolSpec("final_answer", "Returns an answer.", inputSchema, true);
        ToolSpec structuredTool = new ToolSpec(
                "lookup", "Looks up a value.", inputSchema, true, "string", outputSchema
        );

        outputSchema.put("type", "string");

        assertEquals("string", defaultTool.outputType());
        assertEquals("any", finalAnswer.outputType());
        assertEquals("object", structuredTool.outputSchema().path("type").asText());
        ((ObjectNode) structuredTool.outputSchema()).put("type", "array");
        assertEquals("object", structuredTool.outputSchema().path("type").asText());
    }

    /**
     * 使用 Python YAML/Jinja probe 的 SHA-256 锁定两种 Agent 的完整规划模板文本。
     * Python 源 YAML 的单花括号剩余步数占位符会原样保留；Java 仍填入真实预算，只在静态模板哈希中归一化该处。
     *
     * @throws NoSuchAlgorithmException 当前 JDK 缺少 SHA-256 实现时抛出
     */
    @Test
    void matchesPythonPlanningYamlRenderHashes() throws NoSuchAlgorithmException {
        List<ToolSpec> tools = List.of(
                tool("echo", "Return the input text.", "text", "Text to echo", "string", null),
                new FinalAnswerTool().spec()
        );
        PlanningPromptTemplates code = PlanningPromptTemplates.codeAgentDefaults();
        PlanningPromptTemplates toolCalling = PlanningPromptTemplates.toolCallingDefaults();

        assertEquals(
                "3a39f6e87d0b1e81d9ffd70afeab78cb847de7a67780bb069585e0c777718ef9",
                sha256(code.renderInitialPlan("find a value", tools)),
                code.renderInitialPlan("find a value", tools)
        );
        String codeUpdate = code.renderUpdatePlanUser("find a value", 2, tools);
        // Python 两份 YAML 都写成单花括号，Jinja 不会替换；Java 保留有用的预算插值，只归一化静态文案比较。
        String pythonCodeUpdate = codeUpdate.replace(
                "Beware that you have 2 steps remaining.",
                "Beware that you have {remaining_steps} steps remaining."
        );
        assertEquals(
                "3266df0db429b737a31a4bb801d6a14f70636e988e8f9e389b7baaa8c578b248",
                sha256(pythonCodeUpdate),
                pythonCodeUpdate
        );
        assertEquals(
                "da551e38d4dd492d5ddb84f91c80be8dd94bab79c6edbda4d3e16acb512a353a",
                sha256(code.renderUpdatePlanSystem("find a value")),
                code.renderUpdatePlanSystem("find a value")
        );
        assertEquals(
                "de644d6ea445eadcbefbf5f27cb277cb4f9a5706d9bee4cbc67ad1da68dd8324",
                sha256(toolCalling.renderInitialPlan("find a value", tools)),
                toolCalling.renderInitialPlan("find a value", tools)
        );
        String toolCallingUpdate = toolCalling.renderUpdatePlanUser("find a value", 2, tools);
        String pythonToolCallingUpdate = toolCallingUpdate.replace(
                "Beware that you have 2 steps remaining.",
                "Beware that you have {remaining_steps} steps remaining."
        );
        assertEquals(
                "0ce0eb6da0948b0413a5f6cd8779de5e4d6c9a8b77fd38917df38c9c77cffd7e",
                sha256(pythonToolCallingUpdate),
                pythonToolCallingUpdate
        );
        assertEquals(
                "da551e38d4dd492d5ddb84f91c80be8dd94bab79c6edbda4d3e16acb512a353a",
                sha256(toolCalling.renderUpdatePlanSystem("find a value")),
                toolCalling.renderUpdatePlanSystem("find a value")
        );
    }

    /**
     * 验证 managed-agent 条件块以 Python YAML/Jinja 默认格式渲染。
     *
     * @throws NoSuchAlgorithmException 当前 JDK 缺少 SHA-256 实现时抛出
     */
    @Test
    void matchesPythonManagedAgentPlanningPromptHashes() throws NoSuchAlgorithmException {
        List<ToolSpec> tools = List.of(
                tool("echo", "Return the input text.", "text", "Text to echo", "string", null),
                new FinalAnswerTool().spec()
        );
        List<ToolSpec> managedAgents = List.of(managedAgent("researcher", "Research facts"));
        PlanningPromptTemplates code = PlanningPromptTemplates.codeAgentDefaults();
        PlanningPromptTemplates toolCalling = PlanningPromptTemplates.toolCallingDefaults();

        assertEquals(
                "1fcdfebbfdc05c2433a91b2bc756fb7e6fda953fbfff149c09edb7f77edecec1",
                sha256(code.renderInitialPlan("find a value", tools, managedAgents))
        );
        String codeUpdate = code.renderUpdatePlanUser("find a value", 2, tools, managedAgents)
                .replace(
                        "Beware that you have 2 steps remaining.",
                        "Beware that you have {remaining_steps} steps remaining."
                );
        assertEquals(
                "2cb7630831cd96657aa3f26535109def9e6d3d9b9732be99eb7216465071216d",
                sha256(codeUpdate),
                codeUpdate
        );
        assertEquals(
                "25028cfe0be808bbdb04ced876fd15dadea3e651d72617774926c47821b434e4",
                sha256(toolCalling.renderInitialPlan("find a value", tools, managedAgents))
        );
        String toolCallingUpdate = toolCalling.renderUpdatePlanUser("find a value", 2, tools, managedAgents)
                .replace(
                        "Beware that you have 2 steps remaining.",
                        "Beware that you have {remaining_steps} steps remaining."
                );
        assertEquals(
                "3e8f23684517a64774ab16f02796e52a08fa40b4a6df50534e195ac00aff8e99",
                sha256(toolCallingUpdate),
                toolCallingUpdate
        );
    }

    private static String sha256(String value) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static ToolSpec tool(
            String name,
            String description,
            String argumentName,
            String argumentDescription,
            String outputType,
            ObjectNode outputSchema
    ) {
        ObjectNode inputSchema = MAPPER.createObjectNode().put("type", "object");
        inputSchema.set("properties", MAPPER.createObjectNode()
                .set(argumentName, MAPPER.createObjectNode()
                        .put("type", "string")
                        .put("description", argumentDescription)));
        return new ToolSpec(name, description, inputSchema, true, outputType, outputSchema);
    }

    private static ToolSpec managedAgent(String name, String description) {
        ObjectNode inputSchema = MAPPER.createObjectNode().put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("task", MAPPER.createObjectNode()
                .put("type", "string")
                .put("description", "Long detailed description of the task."));
        ObjectNode additionalArgs = MAPPER.createObjectNode();
        additionalArgs.set("type", MAPPER.createArrayNode().add("object").add("null"));
        additionalArgs.put(
                "description",
                "Dictionary of extra inputs to pass to the managed agent, e.g. images, dataframes, "
                        + "or any other contextual data it may need."
        );
        properties.set("additional_args", additionalArgs);
        inputSchema.set("properties", properties);
        inputSchema.putArray("required").add("task");
        inputSchema.put("additionalProperties", false);
        return new ToolSpec(name, description, inputSchema, true, "string");
    }
}
