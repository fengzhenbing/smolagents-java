package org.fzb.smolagents.agent;

import org.fzb.smolagents.core.AgentInput;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.RunOptions;
import org.fzb.smolagents.core.RunResult;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.event.AgentEvent;
import org.fzb.smolagents.executor.CodeExecutor;
import org.fzb.smolagents.executor.ImportPolicyProvider;
import org.fzb.smolagents.executor.PythonProcessCodeExecutor;
import org.fzb.smolagents.memory.AgentMemory;
import org.fzb.smolagents.memory.MemoryStep;
import org.fzb.smolagents.model.GenerationOptions;
import org.fzb.smolagents.model.Model;
import org.fzb.smolagents.tool.FinalAnswerTool;
import org.fzb.smolagents.tool.Tool;
import org.fzb.smolagents.tool.ToolRegistry;
import org.fzb.smolagents.tool.ToolSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * 通过模型生成 Python 代码动作并委托 CodeExecutor 执行的多步 Agent。
 *
 * <p>默认执行器是在本机权限下运行的 CPython 子进程，不是安全沙箱；不得将其用于不可信代码。</p>
 */
public final class CodeAgent implements MemoryBackedAgent, AutoCloseable {
    private static final int DEFAULT_MAX_STEPS = 6;
    private final AgentRuntime runtime;
    private final CodeExecutor executor;

    /**
     * 使用默认 CPython 进程执行器、提示词和步数上限创建 CodeAgent。
     *
     * @param model 用于生成 Python 代码的模型
     * @param tools 可供 Python 代码调用的工具
     */
    public CodeAgent(Model model, List<? extends Tool> tools) {
        this(model, tools, new PythonProcessCodeExecutor(), DEFAULT_MAX_STEPS, null);
    }

    /**
     * 使用默认执行器并额外授权指定的 Python import 创建 CodeAgent。
     *
     * <p>附加模块会并入执行器默认 allowlist。包含 {@code *} 时会允许任意 import，
     * 仅适用于可信代码；本机进程执行器仍不是安全沙箱。</p>
     *
     * @param model 用于生成 Python 代码的模型
     * @param tools 可供 Python 代码调用的工具
     * @param additionalAuthorizedImports 除默认白名单外额外允许的模块名
     */
    public CodeAgent(Model model, List<? extends Tool> tools, List<String> additionalAuthorizedImports) {
        this(model, tools, new PythonProcessCodeExecutor(additionalAuthorizedImports), DEFAULT_MAX_STEPS, null);
    }

    /**
     * 使用默认 CPython 进程执行器和指定 Agent 参数创建 CodeAgent。
     *
     * @param model 用于生成 Python 代码的模型
     * @param tools 可供 Python 代码调用的工具
     * @param maxSteps 最大动作步数
     * @param systemPrompt 自定义系统提示词；为空时使用内置提示词
     */
    public CodeAgent(Model model, List<? extends Tool> tools, int maxSteps, String systemPrompt) {
        this(model, tools, new PythonProcessCodeExecutor(), maxSteps, systemPrompt);
    }

    /**
     * 使用默认 CPython 执行器和规划间隔创建 CodeAgent。
     *
     * @param model 用于生成 Python 代码和计划的模型
     * @param tools 可供 Python 代码调用的工具
     * @param maxSteps 最大动作步数
     * @param systemPrompt 自定义系统提示词；为空时使用内置提示词
     * @param planningInterval 规划间隔；为空表示不启用规划
     */
    public CodeAgent(
            Model model,
            List<? extends Tool> tools,
            int maxSteps,
            String systemPrompt,
            Integer planningInterval
    ) {
        this(model, tools, new PythonProcessCodeExecutor(), maxSteps, systemPrompt, planningInterval, List.of());
    }

    /**
     * 使用默认 CPython 执行器、规划间隔及最终答案校验器创建 CodeAgent。
     *
     * @param model 用于生成 Python 代码和计划的模型
     * @param tools 可供 Python 代码调用的工具
     * @param maxSteps 最大动作步数
     * @param systemPrompt 自定义系统提示词；为空时使用内置提示词
     * @param planningInterval 规划间隔；为空表示不启用规划
     * @param finalAnswerChecks 最终答案校验器
     */
    public CodeAgent(
            Model model,
            List<? extends Tool> tools,
            int maxSteps,
            String systemPrompt,
            Integer planningInterval,
            List<? extends FinalAnswerCheck> finalAnswerChecks
    ) {
        this(
                model,
                tools,
                new PythonProcessCodeExecutor(),
                maxSteps,
                systemPrompt,
                planningInterval,
                finalAnswerChecks
        );
    }

    /**
     * 使用调用方提供的执行器创建 CodeAgent。
     *
     * @param model 用于生成 Python 代码的模型
     * @param tools 可供 Python 代码调用的工具
     * @param executor Python 代码执行器
     */
    public CodeAgent(Model model, List<? extends Tool> tools, CodeExecutor executor) {
        this(model, tools, executor, DEFAULT_MAX_STEPS, null);
    }

    /**
     * 使用指定执行器、步数上限及系统提示词创建 CodeAgent。
     *
     * @param model 用于生成 Python 代码的模型
     * @param tools 可供 Python 代码调用的工具
     * @param executor Python 代码执行器
     * @param maxSteps 最大动作步数
     * @param systemPrompt 自定义系统提示词；为空时使用内置提示词
     */
    public CodeAgent(
            Model model,
            List<? extends Tool> tools,
            CodeExecutor executor,
            int maxSteps,
            String systemPrompt
    ) {
        this(model, tools, executor, maxSteps, systemPrompt, null);
    }

    /**
     * 使用指定执行器、步数上限、系统提示词及规划间隔创建 CodeAgent。
     *
     * @param model 用于生成 Python 代码和计划的模型
     * @param tools 可供 Python 代码调用的工具
     * @param executor Python 代码执行器
     * @param maxSteps 最大动作步数
     * @param systemPrompt 自定义系统提示词；为空时使用内置提示词
     * @param planningInterval 规划间隔；为空表示不启用规划
     */
    public CodeAgent(
            Model model,
            List<? extends Tool> tools,
            CodeExecutor executor,
            int maxSteps,
            String systemPrompt,
            Integer planningInterval
    ) {
        this(model, tools, executor, maxSteps, systemPrompt, planningInterval, List.of());
    }

    /**
     * 使用指定执行器、规划间隔及最终答案校验器创建 CodeAgent。
     *
     * @param model 用于生成 Python 代码和计划的模型
     * @param tools 可供 Python 代码调用的工具
     * @param executor Python 代码执行器
     * @param maxSteps 最大动作步数
     * @param systemPrompt 自定义系统提示词；为空时使用内置提示词
     * @param planningInterval 规划间隔；为空表示不启用规划
     * @param finalAnswerChecks 最终答案校验器
     */
    public CodeAgent(
            Model model,
            List<? extends Tool> tools,
            CodeExecutor executor,
            int maxSteps,
            String systemPrompt,
            Integer planningInterval,
            List<? extends FinalAnswerCheck> finalAnswerChecks
    ) {
        this(model, tools, executor, maxSteps, systemPrompt, planningInterval, finalAnswerChecks, null);
    }

    /**
     * 创建可指定代码围栏与最终答案校验器的 CodeAgent。
     *
     * @param model 用于生成 Python 代码和计划的模型
     * @param tools 可供 Python 代码调用的工具
     * @param executor Python 代码执行器
     * @param maxSteps 最大动作步数
     * @param systemPrompt 自定义系统提示词；为空时使用自动生成提示词
     * @param planningInterval 规划间隔；为空表示不启用规划
     * @param finalAnswerChecks 最终答案校验器
     * @param codeBlockTags 可选自定义 literal code 开闭标记；为空兼容默认标签
     */
    public CodeAgent(
            Model model,
            List<? extends Tool> tools,
            CodeExecutor executor,
            int maxSteps,
            String systemPrompt,
            Integer planningInterval,
            List<? extends FinalAnswerCheck> finalAnswerChecks,
            CodeBlockTags codeBlockTags
    ) {
        this(
                model,
                tools,
                executor,
                maxSteps,
                systemPrompt,
                planningInterval,
                finalAnswerChecks,
                codeBlockTags,
                PlanningPromptTemplates.codeAgentDefaults()
        );
    }

    /**
     * 创建可指定代码围栏、答案校验器及规划提示词模板的 CodeAgent。
     *
     * @param model 用于生成 Python 代码和计划的模型
     * @param tools 可供 Python 代码调用的工具
     * @param executor Python 代码执行器
     * @param maxSteps 最大动作步数
     * @param systemPrompt 自定义系统提示词；为空时使用自动生成提示词
     * @param planningInterval 规划间隔；为空表示不启用规划
     * @param finalAnswerChecks 最终答案校验器
     * @param codeBlockTags 可选自定义 literal code 开闭标记；为空兼容默认标签
     * @param planningPromptTemplates 初始及更新规划提示词模板
     */
    public CodeAgent(
            Model model,
            List<? extends Tool> tools,
            CodeExecutor executor,
            int maxSteps,
            String systemPrompt,
            Integer planningInterval,
            List<? extends FinalAnswerCheck> finalAnswerChecks,
            CodeBlockTags codeBlockTags,
            PlanningPromptTemplates planningPromptTemplates
    ) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(tools, "tools");
        this.executor = Objects.requireNonNull(executor, "executor");

        ToolRegistry registry = new ToolRegistry();
        Map<String, Tool> toolMap = new LinkedHashMap<>();
        for (Tool tool : tools) {
            registry.register(tool);
            String toolName = tool.spec().name();
            if (!PythonProcessCodeExecutor.isPythonToolName(toolName)) {
                throw new ConfigurationException("Python tool name must be a valid identifier: " + toolName);
            }
            toolMap.put(toolName, tool);
        }
        FinalAnswerTool finalAnswerTool = new FinalAnswerTool();
        registry.register(finalAnswerTool);
        toolMap.put(finalAnswerTool.spec().name(), finalAnswerTool);

        List<String> authorizedImports = executor instanceof ImportPolicyProvider policy
                ? policy.authorizedImports()
                : null;
        String prompt = systemPrompt == null || systemPrompt.isBlank()
                ? defaultSystemPrompt(registry.specs(), authorizedImports, codeBlockTags)
                : systemPrompt;
        CodeActionStrategy strategy = new CodeActionStrategy(model, executor, toolMap, codeBlockTags);
        runtime = new AgentRuntime(
                prompt,
                registry,
                strategy,
                maxSteps,
                GenerationOptions.defaults(),
                model,
                planningInterval,
                finalAnswerChecks,
                Objects.requireNonNull(planningPromptTemplates, "planningPromptTemplates")
        );
    }

    /**
     * 执行任务并返回最终输出值。
     *
     * @param input Agent 输入
     * @param options 本次运行选项
     * @return 最终输出值
     */
    @Override
    public AgentValue run(AgentInput input, RunOptions options) {
        return runtime.run(input, options);
    }

    /**
     * 执行任务并返回完整运行结果。
     *
     * @param input Agent 输入
     * @param options 本次运行选项
     * @return 完整运行结果
     */
    @Override
    public RunResult runFull(AgentInput input, RunOptions options) {
        return runtime.runFull(input, options);
    }

    /**
     * 异步运行任务并发布 Agent 生命周期事件。
     *
     * @param input Agent 输入
     * @param options 本次运行选项
     * @return Agent 事件流
     */
    @Override
    public Flow.Publisher<AgentEvent> stream(AgentInput input, RunOptions options) {
        return runtime.stream(input, options);
    }

    /**
     * 返回 CodeAgent 的会话记忆。
     *
     * @return Agent 记忆
     */
    public AgentMemory memory() {
        return runtime.memory();
    }

    /**
     * 恢复使用相同系统提示词保存的 CodeAgent 会话记忆。
     *
     * @param memory 已从安全 JSON codec 恢复的记忆快照
     */
    public void restoreMemory(AgentMemory memory) {
        runtime.restoreMemory(memory);
    }

    /**
     * 注册按记忆步骤类型触发的回调。
     *
     * @param stepType 回调关注的步骤类型
     * @param callback 待调用的步骤回调
     */
    public void registerStepCallback(Class<? extends MemoryStep> stepType, StepCallback callback) {
        runtime.registerStepCallback(stepType, callback);
    }

    /**
     * 返回 CodeAgent 当前使用的工具注册表。
     *
     * @return 工具注册表
     */
    public ToolRegistry tools() {
        return runtime.tools();
    }

    /**
     * 关闭底层代码执行器及其进程资源。
     */
    @Override
    public void close() {
        executor.close();
    }

    /**
     * 根据工具 schema 和 Python import 白名单生成默认系统提示词。
     *
     * @param tools 模型可见工具定义
     * @return 描述动作协议及能力边界的系统提示词
     */
    private static String defaultSystemPrompt(
            List<ToolSpec> tools,
            List<String> authorizedImports,
            CodeBlockTags codeBlockTags
    ) {
        CodeBlockTags tags = codeBlockTags == null ? CodeBlockTags.markdown() : codeBlockTags;
        StringBuilder prompt = new StringBuilder("""
                You are a helpful assistant that solves tasks by writing Python code.
                Return each action inside the configured code block tags. The Python namespace persists between actions.
                Available Java tools can be called as Python functions using keyword arguments.
                Call final_answer(value) when the task is complete; do not assign to final_answer.
                """);
        prompt.append("Open code tag: ").append(tags.openingTag())
                .append("\nClose code tag: ").append(tags.closingTag()).append('\n');
        if (authorizedImports == null) {
            prompt.append("Only import modules allowed by the configured CodeExecutor policy.\n");
        } else if (authorizedImports.contains("*")) {
            prompt.append("All Python imports are authorized by the configured CodeExecutor.\n");
        } else {
            prompt.append("Only import modules from this allowlist: ")
                    .append(authorizedImports)
                    .append('\n');
        }
        prompt.append("Available tools:\n");
        for (ToolSpec tool : tools) {
            prompt.append("- ").append(tool.name()).append(": ").append(tool.description())
                    .append(" Schema: ").append(tool.inputSchema()).append('\n');
        }
        return prompt.toString();
    }
}
