package org.fzb.smolagents.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.tool.ToolSpec;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 可定制的规划阶段提示词模板。
 *
 * <p>模板只支持 {@code {{task}}}、{@code {{tools}}}、{@code {{managed_agents}}} 和
 * {@code {{remaining_steps}}} 四种纯文本变量，不执行表达式、脚本或远端模板代码。</p>
 *
 * @param initialPlan 首次规划的 user prompt 模板
 * @param updatePlanSystem 后续规划的 system prompt 模板
 * @param updatePlanUser 后续规划的 user prompt 模板
 * @param toolPromptStyle Python 兼容工具呈现方式
 */
public record PlanningPromptTemplates(
        String initialPlan,
        String updatePlanSystem,
        String updatePlanUser,
        ToolPromptStyle toolPromptStyle
) {
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([a-z_]+)}}");
    private static final ObjectWriter PYTHON_JSON_WRITER = new ObjectMapper().writer(
            new DefaultPrettyPrinter()
                    .withObjectIndenter(new DefaultIndenter("    ", "\n"))
                    .withArrayIndenter(new DefaultIndenter("    ", "\n"))
                    .withSeparators(Separators.createDefaultInstance()
                            .withObjectFieldValueSpacing(Separators.Spacing.AFTER))
    );

    /**
     * 保留原有三模板构造方式，并使用结构化 schema 摘要呈现工具。
     *
     * @param initialPlan 首次规划模板
     * @param updatePlanSystem 更新规划 system 模板
     * @param updatePlanUser 更新规划 user 模板
     */
    public PlanningPromptTemplates(String initialPlan, String updatePlanSystem, String updatePlanUser) {
        this(initialPlan, updatePlanSystem, updatePlanUser, ToolPromptStyle.SCHEMA_SUMMARY);
    }

    /**
     * 校验模板文本不为 null，并保留调用方提供的精确内容。
     *
     * @param initialPlan 首次规划模板
     * @param updatePlanSystem 更新规划 system 模板
     * @param updatePlanUser 更新规划 user 模板
     * @param toolPromptStyle Python 兼容工具呈现方式
     */
    public PlanningPromptTemplates {
        Objects.requireNonNull(initialPlan, "initialPlan");
        Objects.requireNonNull(updatePlanSystem, "updatePlanSystem");
        Objects.requireNonNull(updatePlanUser, "updatePlanUser");
        Objects.requireNonNull(toolPromptStyle, "toolPromptStyle");
    }

    /**
     * 返回默认规划模板。
     *
     * @return 带事实调查、工具清单与结束 token 的默认模板
     */
    public static PlanningPromptTemplates defaults() {
        return new PlanningPromptTemplates(
                """
                        You are an expert task planner. First survey the facts given, facts to look up, and facts to derive. \
                        Then write a concise, ordered high-level plan using the available tools. Do not assume facts or \
                        claim unfinished work is complete. End the plan with <end_plan>.

                        Available tools:
                        {{tools}}
                        Task:
                        ```
                        {{task}}
                        ```
                        Write the facts survey first, then the plan.""",
                """
                        You are an expert task planner. Update the facts survey and plan for this task:
                        ```
                        {{task}}
                        ```

                        The following messages contain a summary of attempts made so far. Build on successful results, \
                        but choose a new approach if progress has stalled.""",
                """
                        Now write an updated facts survey, followed by a step-by-step high-level plan. Include facts \
                        given, facts learned, facts still to look up, and facts still to derive. Do not skip necessary \
                        steps or add superfluous work. There are {{remaining_steps}} action steps remaining. End with \
                        <end_plan>.

                        Available tools:
                        {{tools}}
                        Current task:
                        ```
                        {{task}}
                        ```"""
        );
    }

    /**
     * 返回按 Python tool-calling Agent 结构编写的默认规划模板。
     *
     * <p>托管 Agent 列表由安全的固定格式渲染；不执行任意 Jinja 表达式或自定义指令代码。</p>
     *
     * @return facts-survey 与工具调用任务步骤模板
     */
    public static PlanningPromptTemplates toolCallingDefaults() {
        return new PlanningPromptTemplates(
                """
                        You are a world expert at analyzing a situation to derive facts, and plan accordingly towards solving a task.
                        Below I will present you a task. You will need to 1. build a survey of facts known or needed to solve the task, then 2. make a plan of action to solve the task.

                        ## 1. Facts survey
                        You will build a comprehensive preparatory survey of which facts we have at our disposal and which ones we still need.
                        These "facts" will typically be specific names, dates, values, etc. Your answer should use the below headings:
                        ### 1.1. Facts given in the task
                        List here the specific facts given in the task that could help you (there might be nothing here).

                        ### 1.2. Facts to look up
                        List here any facts that we may need to look up.
                        Also list where to find each of these, for instance a website, a file... - maybe the task contains some sources that you should re-use here.

                        ### 1.3. Facts to derive
                        List here anything that we want to derive from the above by logical reasoning, for instance computation or simulation.

                        Don't make any assumptions. For each item, provide a thorough reasoning. Do not add anything else on top of three headings above.

                        ## 2. Plan
                        Then for the given task, develop a step-by-step high-level plan taking into account the above inputs and list of facts.
                        This plan should involve individual tasks based on the available tools, that if executed correctly will yield the correct answer.
                        Do not skip steps, do not add any superfluous steps. Only write the high-level plan, DO NOT DETAIL INDIVIDUAL TOOL CALLS.
                        After writing the final step of the plan, write the '<end_plan>' tag and stop there.

                        You can leverage these tools:
                        {{tools}}
                        {{managed_agents}}
                        ---
                        Now begin! Here is your task:
                        ```
                        {{task}}
                        ```
                        First in part 1, write the facts survey, then in part 2, write your plan.""",
                """
                        You are a world expert at analyzing a situation, and plan accordingly towards solving a task.
                        You have been given the following task:
                        ```
                        {{task}}
                        ```

                        Below you will find a history of attempts made to solve this task.
                        You will first have to produce a survey of known and unknown facts, then propose a step-by-step high-level plan to solve the task.
                        If the previous tries so far have met some success, your updated plan can build on these results.
                        If you are stalled, you can make a completely new plan starting from scratch.

                        Find the task and history below:""",
                """
                        Now write your updated facts below, taking into account the above history:
                        ## 1. Updated facts survey
                        ### 1.1. Facts given in the task
                        ### 1.2. Facts that we have learned
                        ### 1.3. Facts still to look up
                        ### 1.4. Facts still to derive

                        Then write a step-by-step high-level plan to solve the task above.
                        ## 2. Plan
                        ### 2. 1. ...
                        Etc.
                        This plan should involve individual tasks based on the available tools, that if executed correctly will yield the correct answer.
                        Beware that you have {{remaining_steps}} steps remaining.
                        Do not skip steps, do not add any superfluous steps. Only write the high-level plan, DO NOT DETAIL INDIVIDUAL TOOL CALLS.
                        After writing the final step of the plan, write the '<end_plan>' tag and stop there.

                        You can leverage these tools:
                        {{tools}}
                        {{managed_agents}}
                        Now write your new plan below.""",
                ToolPromptStyle.TOOL_CALLING
        );
    }

    /**
     * 返回按 Python code Agent 结构编写的默认规划模板。
     *
     * @return facts-survey、Python tool stubs 和任务步骤模板
     */
    public static PlanningPromptTemplates codeAgentDefaults() {
        return new PlanningPromptTemplates(
                """
                        You are a world expert at analyzing a situation to derive facts, and plan accordingly towards solving a task.
                        Below I will present you a task. You will need to 1. build a survey of facts known or needed to solve the task, then 2. make a plan of action to solve the task.

                        ## 1. Facts survey
                        You will build a comprehensive preparatory survey of which facts we have at our disposal and which ones we still need.
                        These "facts" will typically be specific names, dates, values, etc. Your answer should use the below headings:
                        ### 1.1. Facts given in the task
                        List here the specific facts given in the task that could help you (there might be nothing here).

                        ### 1.2. Facts to look up
                        List here any facts that we may need to look up.
                        Also list where to find each of these, for instance a website, a file... - maybe the task contains some sources that you should re-use here.

                        ### 1.3. Facts to derive
                        List here anything that we want to derive from the above by logical reasoning, for instance computation or simulation.

                        Don't make any assumptions. For each item, provide a thorough reasoning. Do not add anything else on top of three headings above.

                        ## 2. Plan
                        Then for the given task, develop a step-by-step high-level plan taking into account the above inputs and list of facts.
                        This plan should involve individual tasks based on the available tools, that if executed correctly will yield the correct answer.
                        Do not skip steps, do not add any superfluous steps. Only write the high-level plan, DO NOT DETAIL INDIVIDUAL TOOL CALLS.
                        After writing the final step of the plan, write the '<end_plan>' tag and stop there.

                        You can leverage these tools, behaving like regular python functions:
                        ```python
                        {{tools}}
                        ```
                        {{managed_agents}}
                        ---
                        Now begin! Here is your task:
                        ```
                        {{task}}
                        ```
                        First in part 1, write the facts survey, then in part 2, write your plan.""",
                """
                        You are a world expert at analyzing a situation, and plan accordingly towards solving a task.
                        You have been given the following task:
                        ```
                        {{task}}
                        ```

                        Below you will find a history of attempts made to solve this task.
                        You will first have to produce a survey of known and unknown facts, then propose a step-by-step high-level plan to solve the task.
                        If the previous tries so far have met some success, your updated plan can build on these results.
                        If you are stalled, you can make a completely new plan starting from scratch.

                        Find the task and history below:""",
                """
                        Now write your updated facts below, taking into account the above history:
                        ## 1. Updated facts survey
                        ### 1.1. Facts given in the task
                        ### 1.2. Facts that we have learned
                        ### 1.3. Facts still to look up
                        ### 1.4. Facts still to derive

                        Then write a step-by-step high-level plan to solve the task above.
                        ## 2. Plan
                        ### 2. 1. ...
                        Etc.
                        This plan should involve individual tasks based on the available tools, that if executed correctly will yield the correct answer.
                        Beware that you have {{remaining_steps}} steps remaining.
                        Do not skip steps, do not add any superfluous steps. Only write the high-level plan, DO NOT DETAIL INDIVIDUAL TOOL CALLS.
                        After writing the final step of the plan, write the '<end_plan>' tag and stop there.

                        You can leverage these tools, behaving like regular python functions:
                        ```python
                        {{tools}}
                        ```
                        {{managed_agents}}
                        Now write your updated facts survey below, then your new plan.""",
                ToolPromptStyle.PYTHON_CODE
        );
    }

    /**
     * 渲染首次规划提示词。
     *
     * @param task 当前任务
     * @param tools 当前可用工具 schema
     * @return user prompt 文本
     */
    public String renderInitialPlan(String task, List<ToolSpec> tools) {
        return renderInitialPlan(task, tools, List.of());
    }

    /**
     * 渲染首次规划提示词，并把托管 Agent 单独呈现为 team members。
     *
     * @param task 当前任务
     * @param tools 当前普通工具 schema
     * @param managedAgents 当前托管 Agent schema
     * @return user prompt 文本
     */
    public String renderInitialPlan(String task, List<ToolSpec> tools, List<ToolSpec> managedAgents) {
        return render(initialPlan, Map.of(
                "task", Objects.requireNonNull(task, "task"),
                "tools", formatTools(tools, toolPromptStyle),
                "managed_agents", formatManagedAgents(managedAgents, toolPromptStyle)
        ));
    }

    /**
     * 渲染更新规划的 system prompt。
     *
     * @param task 当前任务
     * @return system prompt 文本
     */
    public String renderUpdatePlanSystem(String task) {
        return render(updatePlanSystem, Map.of("task", Objects.requireNonNull(task, "task")));
    }

    /**
     * 渲染更新规划的 user prompt。
     *
     * @param task 当前任务
     * @param remainingSteps 当前动作之后的剩余动作数
     * @param tools 当前可用工具 schema
     * @return user prompt 文本
     */
    public String renderUpdatePlanUser(String task, int remainingSteps, List<ToolSpec> tools) {
        return renderUpdatePlanUser(task, remainingSteps, tools, List.of());
    }

    /**
     * 渲染更新规划的 user prompt，并把托管 Agent 单独呈现为 team members。
     *
     * @param task 当前任务
     * @param remainingSteps 当前动作之后的剩余动作数
     * @param tools 当前普通工具 schema
     * @param managedAgents 当前托管 Agent schema
     * @return user prompt 文本
     */
    public String renderUpdatePlanUser(
            String task,
            int remainingSteps,
            List<ToolSpec> tools,
            List<ToolSpec> managedAgents
    ) {
        if (remainingSteps < 0) {
            throw new ConfigurationException("remaining planning steps must not be negative");
        }
        return render(updatePlanUser, Map.of(
                "task", Objects.requireNonNull(task, "task"),
                "remaining_steps", Integer.toString(remainingSteps),
                "tools", formatTools(tools, toolPromptStyle),
                "managed_agents", formatManagedAgents(managedAgents, toolPromptStyle)
        ));
    }

    /**
     * 将有限允许的占位符替换为纯文本变量，不解释替换值中的模板语法。
     *
     * @param template 原始模板
     * @param variables 该模板允许的变量
     * @return 渲染后的纯文本
     */
    private static String render(String template, Map<String, String> variables) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length());
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = variables.get(name);
            if (value == null) {
                throw new ConfigurationException("Unsupported planning prompt variable: " + name);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        if (rendered.toString().matches("(?s).*\\{\\{.*?}}.*")) {
            throw new ConfigurationException("Planning prompt contains a malformed variable placeholder");
        }
        return rendered.toString();
    }

    /**
     * 按稳定顺序格式化模型可见的工具名和描述。
     *
     * @param tools 当前工具 schema
     * @return 一行一个工具的 prompt 文本
     */
    private static String formatTools(List<ToolSpec> tools, ToolPromptStyle promptStyle) {
        Objects.requireNonNull(tools, "tools");
        if (tools.isEmpty()) {
            return "- No tools are available.";
        }
        String rendered = tools.stream().map(tool -> switch (promptStyle) {
            case TOOL_CALLING -> formatToolCallingTool(tool);
            case PYTHON_CODE -> formatPythonTool(tool);
            case SCHEMA_SUMMARY -> "- " + tool.name() + ": " + tool.description()
                    + " Schema: " + tool.inputSchema();
        }).collect(Collectors.joining(promptStyle == ToolPromptStyle.PYTHON_CODE ? "\n\n" : "\n"));
        return promptStyle == ToolPromptStyle.PYTHON_CODE ? rendered + "\n" : rendered;
    }

    /**
     * 按当前 Agent 提示词模式格式化托管 Agent 列表。
     *
     * @param managedAgents 托管 Agent 工具规格
     * @param promptStyle 普通工具呈现方式
     * @return Python YAML managed-agent 区域或空字符串
     */
    private static String formatManagedAgents(List<ToolSpec> managedAgents, ToolPromptStyle promptStyle) {
        Objects.requireNonNull(managedAgents, "managedAgents");
        if (managedAgents.isEmpty()) {
            return "";
        }
        return switch (promptStyle) {
            case TOOL_CALLING -> formatToolCallingManagedAgents(managedAgents);
            case PYTHON_CODE -> formatCodeManagedAgents(managedAgents);
            case SCHEMA_SUMMARY -> managedAgents.stream()
                    .map(agent -> "- " + agent.name() + ": " + agent.description())
                    .collect(Collectors.joining("\n"));
        };
    }

    /**
     * 格式化 tool-calling planner 的 team member 列表。
     *
     * @param managedAgents 托管 Agent 工具规格
     * @return Python Jinja 默认 block 对应文本
     */
    private static String formatToolCallingManagedAgents(List<ToolSpec> managedAgents) {
        String members = managedAgents.stream()
                .map(agent -> "- " + agent.name() + ": " + agent.description()
                        + "\n  - Takes inputs: " + managedAgentInputs(agent)
                        + "\n  - Returns an output of type: " + agent.outputType())
                .collect(Collectors.joining("\n"));
        return "You can also give tasks to team members.\n"
                + "Calling a team member works similarly to calling a tool: provide the task description as the "
                + "'task' argument. Since this team member is a real human, be as detailed and verbose as necessary "
                + "in your task description.\n"
                + "You can also include any relevant variables or context using the 'additional_args' argument.\n"
                + "Here is a list of the team members that you can call:\n"
                + members
                + "\n";
    }

    /**
     * 格式化 CodeAgent planner 的 managed-agent Python function stubs。
     *
     * @param managedAgents 托管 Agent 工具规格
     * @return Python Jinja code block 对应文本
     */
    private static String formatCodeManagedAgents(List<ToolSpec> managedAgents) {
        String functions = managedAgents.stream()
                .map(agent -> "def " + agent.name() + "(task: str, additional_args: dict[str, Any]) -> str:\n"
                        + "    \"\"\"" + agent.description() + "\n\n"
                        + "    Args:\n"
                        + "        task: Long detailed description of the task.\n"
                        + "        additional_args: Dictionary of extra inputs to pass to the managed agent, e.g. "
                        + "images, dataframes, or any other contextual data it may need.\n"
                        + "    \"\"\"")
                .collect(Collectors.joining("\n\n"));
        return "You can also give tasks to team members.\n"
                + "Calling a team member works similarly to calling a tool: provide the task description as the "
                + "'task' argument. Since this team member is a real human, be as detailed and verbose as necessary "
                + "in your task description.\n"
                + "You can also include any relevant variables or context using the 'additional_args' argument.\n"
                + "Here is a list of the team members that you can call:\n"
                + "```python\n"
                + functions
                + "\n\n```\n";
    }

    /**
     * 呈现 Python ManagedAgent 的输入字典，不把 JSON Schema 的 union 写法泄露到 prompt。
     *
     * @param agent 托管 Agent 工具规格
     * @return Python repr 形状的 inputs 字典
     */
    private static String managedAgentInputs(ToolSpec agent) {
        JsonNode properties = agent.inputSchema().path("properties");
        String taskDescription = pythonString(properties.path("task")
                .path("description").asText("Long detailed description of the task."));
        String additionalDescription = pythonString(properties.path("additional_args")
                .path("description").asText("Dictionary of extra inputs to pass to the managed agent."));
        return "{'task': {'type': 'string', 'description': '" + taskDescription
                + "'}, 'additional_args': {'type': 'object', 'description': '" + additionalDescription
                + "', 'nullable': True}}";
    }

    /**
     * 按 Python smolagents 的 tool-calling prompt 形状渲染工具说明。
     *
     * @param tool 工具定义
     * @return 名称、描述、输入及结果类型说明
     */
    private static String formatToolCallingTool(ToolSpec tool) {
        JsonNode properties = tool.inputSchema().path("properties");
        Map<String, String> inputs = new java.util.LinkedHashMap<>();
        properties.fields().forEachRemaining(entry -> {
            JsonNode schema = entry.getValue();
            String type = pythonValue(schema.path("type"));
            String description = entry.getValue().path("description").asText("");
            StringBuilder input = new StringBuilder("{'type': ").append(type)
                    .append(", 'description': '").append(pythonString(description)).append("'");
            if (schema.has("nullable") && schema.get("nullable").isBoolean()) {
                input.append(", 'nullable': ").append(schema.get("nullable").asBoolean() ? "True" : "False");
            }
            inputs.put(entry.getKey(), input.append("}").toString());
        });
        String inputMap = inputs.entrySet().stream()
                .map(entry -> "'" + pythonString(entry.getKey()) + "': " + entry.getValue())
                .collect(Collectors.joining(", ", "{", "}"));
        return "- " + tool.name() + ": " + tool.description()
                + "\n    Takes inputs: " + inputMap
                + "\n    Returns an output of type: " + tool.outputType();
    }

    /**
     * 按 Python to_code_prompt 形状生成安全的 Python 工具 stub。
     *
     * @param tool 工具定义
     * @return 函数签名和参数说明 docstring
     */
    private static String formatPythonTool(ToolSpec tool) {
        JsonNode properties = tool.inputSchema().path("properties");
        List<String> arguments = new java.util.ArrayList<>();
        List<String> descriptions = new java.util.ArrayList<>();
        properties.fields().forEachRemaining(entry -> {
            arguments.add(entry.getKey() + ": " + pythonTypeText(entry.getValue().path("type")));
            String description = entry.getValue().path("description").asText("");
            if (!description.isBlank()) {
                descriptions.add(entry.getKey() + ": " + description);
            }
        });
        JsonNode outputSchema = tool.outputSchema();
        String outputType = outputSchema == null ? tool.outputType() : "dict";
        StringBuilder documentation = new StringBuilder(tool.description());
        if (outputSchema != null) {
            documentation.append("\n\nImportant: This tool returns structured output! Use the JSON schema below "
                    + "to directly access fields like result['field_name']. NO print() statements needed to inspect "
                    + "the output!");
        }
        if (!descriptions.isEmpty()) {
            documentation.append("\n\nArgs:\n    ").append(String.join("\n    ", descriptions));
        }
        if (outputSchema != null) {
            documentation.append("\n\nReturns:\n    dict (structured output): This tool ALWAYS returns a dictionary "
                    + "that strictly adheres to the following JSON schema:\n")
                    .append(indent(formatPythonJson(outputSchema), "        "));
        }
        String docstring = "\"\"\"" + documentation + "\n\"\"\"";
        return "def " + tool.name() + "(" + String.join(", ", arguments) + ") -> " + outputType + ":\n"
                + indentNonBlankLines(docstring, "    ");
    }

    /**
     * 以 Python 工具 prompt 的字面量形式呈现输入类型。
     *
     * @param value JSON Schema type 字段
     * @return Python 字符串或字符串列表字面量
     */
    private static String pythonValue(JsonNode value) {
        if (value.isArray()) {
            return java.util.stream.StreamSupport.stream(value.spliterator(), false)
                    .map(item -> "'" + pythonString(item.asText()) + "'")
                    .collect(Collectors.joining(", ", "[", "]"));
        }
        return "'" + pythonString(value.asText("any")) + "'";
    }

    /**
     * 返回 {@code Tool.to_code_prompt} 中直接插入签名的类型文本。
     *
     * @param value JSON Schema type 字段
     * @return 原始 Python 工具类型，联合类型为 Python 列表字面量
     */
    private static String pythonTypeText(JsonNode value) {
        return value.isArray() ? pythonValue(value) : value.asText("any");
    }

    /**
     * 按四空格缩进格式化结构化输出 schema。
     *
     * @param schema 结构化输出 schema
     * @return Python JSON 文档格式
     */
    private static String formatPythonJson(JsonNode schema) {
        try {
            return PYTHON_JSON_WRITER.writeValueAsString(schema);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to render tool output schema", exception);
        }
    }

    /**
     * 缩进非空行，与 Python {@code textwrap.indent} 默认行为一致。
     *
     * @param value 原始多行文本
     * @param prefix 行首缩进
     * @return 缩进后的文本
     */
    private static String indentNonBlankLines(String value, String prefix) {
        return value.lines()
                .map(line -> line.isBlank() ? line : prefix + line)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 为 JSON 格式化输出中的每一行添加缩进。
     *
     * @param value 多行 JSON 文本
     * @param prefix 行首缩进
     * @return 缩进后的 JSON
     */
    private static String indent(String value, String prefix) {
        return value.lines().map(line -> prefix + line).collect(Collectors.joining("\n"));
    }

    /**
     * 转义 Python 单引号字面量中不可直接出现的反斜线和单引号。
     *
     * @param value 任意工具 schema 文本
     * @return 可嵌入单引号字符串的内容
     */
    private static String pythonString(String value) {
        return value.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    /**
     * 选择 Python tool schema 的呈现方式。
     */
    public enum ToolPromptStyle {
        /** 结构化 JSON Schema 摘要，供自定义模板使用。 */
        SCHEMA_SUMMARY,
        /** Python 函数 stub，供 CodeAgent 使用。 */
        PYTHON_CODE,
        /** Python tool-calling 概要，供 ToolCallingAgent 使用。 */
        TOOL_CALLING
    }
}
