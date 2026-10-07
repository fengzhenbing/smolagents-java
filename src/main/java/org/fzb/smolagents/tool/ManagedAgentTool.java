package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fzb.smolagents.agent.Agent;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.AgentInput;
import org.fzb.smolagents.core.AgentRunException;
import org.fzb.smolagents.core.RunOptions;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.model.ContentPart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将子 Agent 包装为可由父 Agent 调用的普通工具。
 */
public final class ManagedAgentTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TASK_TEMPLATE = """
            You're a helpful agent named '{{name}}'.
            You have been submitted this task by your manager.
            ---
            Task:
            {{task}}
            ---
            You're helping your manager solve a wider task: so make sure to not provide a one-line answer, but give as much information as possible to give them a clear understanding of the answer.

            Your final_answer WILL HAVE to contain these parts:
            ### 1. Task outcome (short version):
            ### 2. Task outcome (extremely detailed version):
            ### 3. Additional context (if relevant):

            Put all these in your final_answer tool, everything that you do not pass as an argument to final_answer will be lost.
            And even if your task resolution is not successful, please return as much context as possible, so that your manager can act upon this feedback.""";
    private static final String REPORT_TEMPLATE =
            "Here is the final answer from your managed agent '{{name}}':\n{{final_answer}}";
    private final ToolSpec spec;
    private final Agent agent;
    private final boolean includeRunSummary;

    /**
     * 创建只向父 Agent 返回子 Agent 答案的工具。
     *
     * @param name 子 Agent 在父 Agent 工具集中的名称
     * @param description 子 Agent 的职责描述
     * @param agent 被委托执行任务的 Agent
     */
    public ManagedAgentTool(String name, String description, Agent agent) {
        this(name, description, agent, false);
    }

    /**
     * 创建可选附加运行摘要的子 Agent 工具。
     *
     * @param name 子 Agent 在父 Agent 工具集中的名称
     * @param description 子 Agent 的职责描述
     * @param agent 被委托执行任务的 Agent
     * @param includeRunSummary 是否返回子 Agent 的终止状态和步骤数
     */
    public ManagedAgentTool(String name, String description, Agent agent, boolean includeRunSummary) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.includeRunSummary = includeRunSummary;
        this.spec = new ToolSpec(name, description, taskSchema(), true, "string");
    }

    /**
     * 返回委托工具的模型可见定义。
     *
     * @return 工具名称、说明及任务参数 schema
     */
    @Override
    public ToolSpec spec() {
        return spec;
    }

    /**
     * 将调用任务交给子 Agent，并把结果映射为工具输出。
     *
     * @param arguments 已校验的 task 参数
     * @param context 父 Agent 的工具执行上下文
     * @return 子 Agent 答案及可选运行摘要
     * @throws AgentRunException 子 Agent 运行失败时传播其异常
     */
    @Override
    public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
        context.cancellation().throwIfCancelled();
        var result = agent.runFull(
                new AgentInput(
                        renderTask(arguments.path("task").asText()),
                        additionalArguments(arguments)
                ),
                new RunOptions(true, null, true, null, context.cancellation())
        );
        List<ContentPart> reportContent = reportContent(result.output());
        if (!includeRunSummary) {
            return new ToolResult(reportContent, result.output().structuredContent());
        }
        ObjectNode summary = MAPPER.createObjectNode();
        result.output().plainText().ifPresent(text -> summary.put("answer", text));
        JsonNode structuredAnswer = result.output().structuredContent();
        if (structuredAnswer != null) {
            summary.set("answer", structuredAnswer);
        }
        summary.put("status", result.status().name());
        summary.put("step_count", result.steps().size());
        summary.put("duration_millis", result.duration().toMillis());
        return new ToolResult(reportContent, summary);
    }

    /**
     * 创建与 Python ManagedAgent inputs 相符的 task 和可选附加参数 schema。
     *
     * @return 包含 task 与 nullable additional_args 的 JSON Schema
     */
    private static JsonNode taskSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        ObjectNode task = MAPPER.createObjectNode();
        task.put("type", "string");
        task.put("description", "Long detailed description of the task.");
        task.put("minLength", 1);
        properties.set("task", task);
        ObjectNode additionalArgs = MAPPER.createObjectNode();
        additionalArgs.set("type", MAPPER.createArrayNode().add("object").add("null"));
        additionalArgs.put(
                "description",
                "Dictionary of extra inputs to pass to the managed agent, e.g. images, dataframes, "
                        + "or any other contextual data it may need."
        );
        properties.set("additional_args", additionalArgs);
        schema.set("properties", properties);
        schema.putArray("required").add("task");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 将可选 additional_args 对象转换为子 Agent 的 JSON 变量映射。
     *
     * @param arguments 已校验的调用参数
     * @return 子 Agent 的附加 JSON 参数
     * @throws ToolExecutionException 参数直接调用时不是对象或 null
     */
    private static Map<String, JsonNode> additionalArguments(JsonNode arguments) {
        JsonNode additionalArgs = arguments.get("additional_args");
        if (additionalArgs == null || additionalArgs.isNull()) {
            return Map.of();
        }
        if (!additionalArgs.isObject()) {
            throw new ToolExecutionException("managed-agent additional_args must be an object or null");
        }
        Map<String, JsonNode> values = new LinkedHashMap<>();
        additionalArgs.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue()));
        return values;
    }

    /**
     * 为子 Agent 任务应用 Python ManagedAgent 的任务提示模板。
     *
     * @param task 父 Agent 委托的任务文本
     * @return 包含角色说明及报告要求的子任务
     */
    private String renderTask(String task) {
        return TASK_TEMPLATE
                .replace("{{name}}", spec.name())
                .replace("{{task}}", task);
    }

    /**
     * 为子 Agent 答案加入 Python ManagedAgent 的报告前缀，同时保留多模态/结构化内容。
     *
     * @param output 子 Agent 原始输出
     * @return 带 managed-agent 报告文本的工具内容
     */
    private List<ContentPart> reportContent(AgentValue output) {
        String prefix = "Here is the final answer from your managed agent '" + spec.name() + "':\n";
        var plainText = output.plainText();
        if (plainText.isPresent()) {
            return List.of(new ContentPart.Text(
                    REPORT_TEMPLATE.replace("{{name}}", spec.name())
                            .replace("{{final_answer}}", plainText.orElseThrow())
            ));
        }
        ArrayList<ContentPart> content = new ArrayList<>();
        content.add(new ContentPart.Text(prefix));
        content.addAll(output.content());
        return List.copyOf(content);
    }
}
