package org.fzb.smolagents.agent;

import org.fzb.smolagents.core.AgentInput;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.RunOptions;
import org.fzb.smolagents.core.RunResult;
import org.fzb.smolagents.event.AgentEvent;
import org.fzb.smolagents.memory.AgentMemory;
import org.fzb.smolagents.memory.MemoryStep;
import org.fzb.smolagents.model.GenerationOptions;
import org.fzb.smolagents.model.Model;
import org.fzb.smolagents.tool.FinalAnswerTool;
import org.fzb.smolagents.tool.Tool;
import org.fzb.smolagents.tool.ToolRegistry;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * 通过模型工具调用实现的完整 Agent，内置 final_answer 工具。
 */
public final class ToolCallingAgent implements MemoryBackedAgent {
    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a helpful assistant. Use the available tools when they help solve the task. "
                    + "Call final_answer when you are ready to respond.";

    private final AgentRuntime runtime;

    /**
     * 使用默认步数上限和系统提示词创建工具调用 Agent。
     *
     * @param model 支持工具调用的模型
     * @param tools 可用工具
     */
    public ToolCallingAgent(Model model, List<? extends Tool> tools) {
        this(model, tools, 6, DEFAULT_SYSTEM_PROMPT);
    }

    /**
     * 使用指定步数上限和系统提示词创建工具调用 Agent。
     *
     * @param model 支持工具调用的模型
     * @param tools 可用工具
     * @param maxSteps 每次运行最大动作步数
     * @param systemPrompt Agent 系统提示词
     * @throws ConfigurationException 模型不支持工具调用时抛出
     */
    public ToolCallingAgent(Model model, List<? extends Tool> tools, int maxSteps, String systemPrompt) {
        this(model, tools, maxSteps, systemPrompt, null);
    }

    /**
     * 使用指定步数上限、系统提示词和计划间隔创建工具调用 Agent。
     *
     * @param model 支持工具调用及计划文本生成的模型
     * @param tools 可用工具
     * @param maxSteps 每次运行最大动作步数
     * @param systemPrompt Agent 系统提示词
     * @param planningInterval 规划间隔；为空表示不启用规划
     * @throws ConfigurationException 模型不支持工具调用时抛出
     */
    public ToolCallingAgent(
            Model model,
            List<? extends Tool> tools,
            int maxSteps,
            String systemPrompt,
            Integer planningInterval
    ) {
        this(model, tools, maxSteps, systemPrompt, planningInterval, List.of());
    }

    /**
     * 使用指定计划间隔和最终答案校验器创建工具调用 Agent。
     *
     * @param model 支持工具调用及计划文本生成的模型
     * @param tools 可用工具
     * @param maxSteps 每次运行最大动作步数
     * @param systemPrompt Agent 系统提示词
     * @param planningInterval 规划间隔；为空表示不启用规划
     * @param finalAnswerChecks 最终答案校验器
     * @throws ConfigurationException 模型不支持工具调用时抛出
     */
    public ToolCallingAgent(
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
                maxSteps,
                systemPrompt,
                planningInterval,
                finalAnswerChecks,
                PlanningPromptTemplates.toolCallingDefaults()
        );
    }

    /**
     * 使用调用方规划模板和最终答案校验器创建工具调用 Agent。
     *
     * @param model 支持工具调用及计划文本生成的模型
     * @param tools 可用工具
     * @param maxSteps 每次运行最大动作步数
     * @param systemPrompt Agent 系统提示词
     * @param planningInterval 规划间隔；为空表示不启用规划
     * @param finalAnswerChecks 最终答案校验器
     * @param planningPromptTemplates 初始计划及更新计划提示词模板
     * @throws ConfigurationException 模型不支持工具调用时抛出
     */
    public ToolCallingAgent(
            Model model,
            List<? extends Tool> tools,
            int maxSteps,
            String systemPrompt,
            Integer planningInterval,
            List<? extends FinalAnswerCheck> finalAnswerChecks,
            PlanningPromptTemplates planningPromptTemplates
    ) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(tools, "tools");
        if (!model.capabilities().toolCalling()) {
            throw new ConfigurationException("ToolCallingAgent requires a model with tool-calling capability");
        }
        ToolRegistry registry = new ToolRegistry();
        tools.forEach(registry::register);
        registry.register(new FinalAnswerTool());
        ToolCallingActionStrategy strategy = new ToolCallingActionStrategy(model, registry);
        this.runtime = new AgentRuntime(
                Objects.requireNonNull(systemPrompt, "systemPrompt"),
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
     * 运行任务并返回最终输出值。
     *
     * @param input Agent 输入
     * @param options 本次运行选项
     * @return 最终输出
     */
    @Override
    public AgentValue run(AgentInput input, RunOptions options) {
        return runtime.run(input, options);
    }

    /**
     * 运行任务并返回完整运行结果。
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
     * 异步运行任务并发布 Agent 事件。
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
     * 返回此 Agent 的会话记忆。
     *
     * @return Agent 记忆
     */
    public AgentMemory memory() {
        return runtime.memory();
    }

    /**
     * 恢复使用相同系统提示词保存的 Agent 会话记忆。
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
     * 返回此 Agent 的工具注册表。
     *
     * @return 工具注册表
     */
    public ToolRegistry tools() {
        return runtime.tools();
    }
}
