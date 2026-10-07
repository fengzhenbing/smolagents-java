package org.fzb.smolagents.agent;

import com.fasterxml.jackson.databind.JsonNode;
import org.fzb.smolagents.core.AgentInput;
import org.fzb.smolagents.core.AgentRunException;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.ConcurrentRunException;
import org.fzb.smolagents.core.ModelOutputException;
import org.fzb.smolagents.core.RunOptions;
import org.fzb.smolagents.core.RunResult;
import org.fzb.smolagents.core.RunStatus;
import org.fzb.smolagents.event.AgentEvent;
import org.fzb.smolagents.memory.AgentMemory;
import org.fzb.smolagents.memory.ActionStep;
import org.fzb.smolagents.memory.FinalAnswerStep;
import org.fzb.smolagents.memory.MemoryStep;
import org.fzb.smolagents.memory.PlanningStep;
import org.fzb.smolagents.memory.TaskStep;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.GenerationOptions;
import org.fzb.smolagents.model.GenerationRequest;
import org.fzb.smolagents.model.MessageRole;
import org.fzb.smolagents.model.Model;
import org.fzb.smolagents.model.TokenUsage;
import org.fzb.smolagents.tool.ToolRegistry;
import org.fzb.smolagents.tool.ToolSpec;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理单个 Agent 实例的会话记忆、串行运行生命周期及事件发布。
 */
public final class AgentRuntime implements Agent {
    private volatile AgentMemory memory;
    private final ToolRegistry tools;
    private final ActionStrategy actionStrategy;
    private final int maxSteps;
    private final GenerationOptions generationOptions;
    private final Model planningModel;
    private final Integer planningInterval;
    private final List<FinalAnswerCheck> finalAnswerChecks;
    private final PlanningPromptTemplates planningPromptTemplates;
    private final StepCallbackRegistry stepCallbacks = new StepCallbackRegistry();
    private final AtomicBoolean active = new AtomicBoolean();

    /**
     * 创建 Agent 运行时并绑定提示词、工具、动作策略和步数上限。
     *
     * @param systemPrompt 系统提示词
     * @param tools 工具注册表
     * @param actionStrategy 每一步的动作执行策略
     * @param maxSteps 默认最大动作步数
     * @param generationOptions 模型生成选项
     */
    public AgentRuntime(
            String systemPrompt,
            ToolRegistry tools,
            ActionStrategy actionStrategy,
            int maxSteps,
            GenerationOptions generationOptions
    ) {
        this(systemPrompt, tools, actionStrategy, maxSteps, generationOptions, null, null, List.of());
    }

    /**
     * 创建支持按指定间隔生成运行计划的 Agent 运行时。
     *
     * @param systemPrompt 系统提示词
     * @param tools 工具注册表
     * @param actionStrategy 每一步的动作执行策略
     * @param maxSteps 默认最大动作步数
     * @param generationOptions 模型生成选项
     * @param planningModel 用于生成计划的模型；不启用规划时可为空
     * @param planningInterval 规划间隔；为空表示不启用规划
     */
    public AgentRuntime(
            String systemPrompt,
            ToolRegistry tools,
            ActionStrategy actionStrategy,
            int maxSteps,
            GenerationOptions generationOptions,
            Model planningModel,
            Integer planningInterval
    ) {
        this(systemPrompt, tools, actionStrategy, maxSteps, generationOptions, planningModel, planningInterval, List.of());
    }

    /**
     * 创建支持规划及最终答案校验的 Agent 运行时。
     *
     * @param systemPrompt 系统提示词
     * @param tools 工具注册表
     * @param actionStrategy 每一步的动作执行策略
     * @param maxSteps 默认最大动作步数
     * @param generationOptions 模型生成选项
     * @param planningModel 用于生成计划的模型；不启用规划时可为空
     * @param planningInterval 规划间隔；为空表示不启用规划
     * @param finalAnswerChecks 最终答案校验器；所有校验器均通过后才接受答案
     */
    public AgentRuntime(
            String systemPrompt,
            ToolRegistry tools,
            ActionStrategy actionStrategy,
            int maxSteps,
            GenerationOptions generationOptions,
            Model planningModel,
            Integer planningInterval,
            List<? extends FinalAnswerCheck> finalAnswerChecks
    ) {
        this(
                systemPrompt,
                tools,
                actionStrategy,
                maxSteps,
                generationOptions,
                planningModel,
                planningInterval,
                finalAnswerChecks,
                PlanningPromptTemplates.defaults()
        );
    }

    /**
     * 创建支持自定义规划提示词及最终答案校验的 Agent 运行时。
     *
     * @param systemPrompt 系统提示词
     * @param tools 工具注册表
     * @param actionStrategy 每一步的动作执行策略
     * @param maxSteps 默认最大动作步数
     * @param generationOptions 模型生成选项
     * @param planningModel 规划模型；不启用规划时可为空
     * @param planningInterval 规划间隔；为空表示不启用规划
     * @param finalAnswerChecks 最终答案校验器
     * @param planningPromptTemplates 规划阶段的三个可定制提示词模板
     */
    public AgentRuntime(
            String systemPrompt,
            ToolRegistry tools,
            ActionStrategy actionStrategy,
            int maxSteps,
            GenerationOptions generationOptions,
            Model planningModel,
            Integer planningInterval,
            List<? extends FinalAnswerCheck> finalAnswerChecks,
            PlanningPromptTemplates planningPromptTemplates
    ) {
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be at least 1");
        }
        if (planningInterval != null && planningInterval < 1) {
            throw new IllegalArgumentException("planningInterval must be at least 1");
        }
        if (planningInterval != null && planningModel == null) {
            throw new IllegalArgumentException("planningModel is required when planning is enabled");
        }
        this.memory = new AgentMemory(Objects.requireNonNull(systemPrompt, "systemPrompt"));
        this.tools = Objects.requireNonNull(tools, "tools");
        this.actionStrategy = Objects.requireNonNull(actionStrategy, "actionStrategy");
        this.maxSteps = maxSteps;
        this.generationOptions = Objects.requireNonNull(generationOptions, "generationOptions");
        this.planningModel = planningModel;
        this.planningInterval = planningInterval;
        this.finalAnswerChecks = List.copyOf(Objects.requireNonNull(finalAnswerChecks, "finalAnswerChecks"));
        this.planningPromptTemplates = Objects.requireNonNull(
                planningPromptTemplates,
                "planningPromptTemplates"
        );
    }

    /**
     * 运行任务并仅返回最终输出。
     *
     * @param input Agent 输入
     * @param options 本次运行选项
     * @return 最终输出值
     */
    @Override
    public AgentValue run(AgentInput input, RunOptions options) {
        return runFull(input, options).output();
    }

    /**
     * 执行任务并返回完整运行结果。
     *
     * @param input Agent 输入
     * @param options 本次运行选项
     * @return 带状态、步骤、耗时和 token 用量的结果
     */
    @Override
    public RunResult runFull(AgentInput input, RunOptions options) {
        return execute(input, options, UUID.randomUUID(), event -> { }, false);
    }

    /**
     * 以异步方式执行任务并发布运行事件，流支持单个订阅者。
     *
     * @param input Agent 输入
     * @param options 本次运行选项；订阅取消会触发其取消令牌
     * @return Agent 事件流
     */
    @Override
    public Flow.Publisher<AgentEvent> stream(AgentInput input, RunOptions options) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(options, "options");
        UUID runId = UUID.randomUUID();
        AtomicBoolean subscribed = new AtomicBoolean();
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            if (!subscribed.compareAndSet(false, true)) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                    }

                    @Override
                    public void cancel() {
                    }
                });
                subscriber.onError(new IllegalStateException("Agent run stream supports one subscriber"));
                return;
            }
            SubmissionPublisher<AgentEvent> publisher = new SubmissionPublisher<>();
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(long n) {
                            subscription.request(n);
                        }

                        @Override
                        public void cancel() {
                            options.cancellation().cancel();
                            subscription.cancel();
                        }
                    });
                }

                @Override
                public void onNext(AgentEvent item) {
                    subscriber.onNext(item);
                }

                @Override
                public void onError(Throwable throwable) {
                    subscriber.onError(throwable);
                }

                @Override
                public void onComplete() {
                    subscriber.onComplete();
                }
            });
            CompletableFuture.runAsync(() -> {
                try {
                    execute(input, options, runId, publisher::submit, true);
                } catch (RuntimeException error) {
                    publisher.submit(new AgentEvent.RunFailed(runId, error));
                } finally {
                    publisher.close();
                }
            });
        };
    }

    /**
     * 返回此运行时持有的会话记忆。
     *
     * @return Agent 记忆
     */
    public AgentMemory memory() {
        return memory;
    }

    /**
     * 在无运行任务时恢复同系统提示词的会话记忆快照。
     *
     * @param restored 已通过安全 codec 解码的会话记忆
     * @throws ConcurrentRunException Agent 正在运行时抛出
     * @throws org.fzb.smolagents.core.ConfigurationException 记忆系统提示词不匹配时抛出
     */
    public void restoreMemory(AgentMemory restored) {
        Objects.requireNonNull(restored, "restored");
        if (!active.compareAndSet(false, true)) {
            throw new ConcurrentRunException("cannot restore memory while an Agent run is active");
        }
        try {
            AgentMemory current = memory;
            if (!current.systemPrompt().equals(restored.systemPrompt())) {
                throw new org.fzb.smolagents.core.ConfigurationException(
                        "restored AgentMemory system prompt does not match this Agent"
                );
            }
            memory = restored;
        } finally {
            active.set(false);
        }
    }

    /**
     * 返回此运行时使用的工具注册表。
     *
     * @return 工具注册表
     */
    public ToolRegistry tools() {
        return tools;
    }

    /**
     * 注册按记忆步骤类型触发的回调。
     *
     * @param stepType 回调关注的步骤类型
     * @param callback 待调用的步骤回调
     */
    public void registerStepCallback(Class<? extends MemoryStep> stepType, StepCallback callback) {
        stepCallbacks.register(stepType, callback);
    }

    /**
     * 执行一次任务闭环：准备上下文、逐步决策、完成最终答案或生成步数上限答复。
     *
     * @param input Agent 输入
     * @param options 本次运行选项
     * @param runId 本次运行标识
     * @param eventSink 事件接收器
     * @return 本次运行结果
     */
    private RunResult execute(
            AgentInput input,
            RunOptions options,
            UUID runId,
            java.util.function.Consumer<AgentEvent> eventSink,
            boolean streaming
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(options, "options");
        if (!active.compareAndSet(false, true)) {
            throw new ConcurrentRunException("this AgentRuntime already has an active run");
        }

        Instant startedAt = Instant.now();
        RunContext context = new RunContext(runId, startedAt, input, options, eventSink, streaming);
        try {
            // 步骤一：按需重置记忆，再记录新任务并确定本次有效步数上限。
            if (options.reset()) {
                memory.reset();
            }
            eventSink.accept(new AgentEvent.RunStarted(runId, startedAt));
            String task = withAdditionalArguments(input);
            memory.append(new TaskStep(0, startedAt, task));
            int effectiveMaxSteps = options.maxSteps() == null ? maxSteps : options.maxSteps();

            // 步骤二：逐步执行动作策略，记录动作和事件，直到得到最终答案。
            for (int step = 1; step <= effectiveMaxSteps; step++) {
                checkRunControls(context);
                context.publish(new AgentEvent.StepStarted(runId, step, Instant.now()));
                AgentTurn turn = new AgentTurn(
                        context.nextStepNumber(),
                        memory.toMessages(false),
                        effectiveGenerationOptions(context),
                        tools.specs()
                );
                if (shouldPlan(turn.stepNumber())) {
                    PlanningStep planningStep = createPlanningStep(turn, context, effectiveMaxSteps);
                    stepCallbacks.invoke(planningStep, this);
                    memory.append(planningStep);
                    context.publish(new AgentEvent.StepCompleted(runId, planningStep));
                    turn = new AgentTurn(
                            turn.stepNumber(),
                            memory.toMessages(false),
                            turn.generationOptions(),
                            turn.tools()
                    );
                }
                checkRunControls(context);
                ActionOutcome outcome = context.streaming()
                        ? actionStrategy.performStreaming(turn, context)
                        : actionStrategy.perform(turn, context);
                if (outcome instanceof ActionOutcome.Final finalOutcome) {
                    String checkError = validateFinalAnswer(finalOutcome.answer());
                    if (checkError != null) {
                        outcome = new ActionOutcome.Continue(withError(outcome.actionStep(), checkError));
                    }
                }
                stepCallbacks.invoke(outcome.actionStep(), this);
                memory.append(outcome.actionStep());
                context.publish(new AgentEvent.StepCompleted(runId, outcome.actionStep()));
                if (outcome instanceof ActionOutcome.Final finalOutcome) {
                    FinalAnswerStep finalStep = new FinalAnswerStep(
                            turn.stepNumber() + 1,
                            Instant.now(),
                            outcome.actionStep().duration(),
                            finalOutcome.answer(),
                            TokenUsage.of(0, 0)
                    );
                    stepCallbacks.invoke(finalStep, this);
                    memory.append(finalStep);
                    context.publish(new AgentEvent.StepCompleted(runId, finalStep));
                    return complete(
                            context,
                            finalOutcome.answer(),
                            RunStatus.SUCCESS,
                            startedAt,
                            runId
                    );
                }
            }

            // 步骤三：达到动作上限后请求兜底答案，并以 MAX_STEPS_REACHED 标记结果。
            checkRunControls(context);
            FinalAnswerResponse answer = actionStrategy.provideFinalAnswer(memory.toMessages(false), context);
            FinalAnswerStep finalStep = new FinalAnswerStep(
                    effectiveMaxSteps + 1,
                    Instant.now(),
                    Duration.ZERO,
                    answer.answer(),
                    answer.tokenUsage()
            );
            stepCallbacks.invoke(finalStep, this);
            memory.append(finalStep);
            context.publish(new AgentEvent.StepCompleted(runId, finalStep));
            return complete(context, answer.answer(), RunStatus.MAX_STEPS_REACHED, startedAt, runId);
        } catch (RuntimeException error) {
            // 失败时保留已产生的记忆快照与失败状态，供调用方诊断。
            RunStatus status = failureStatus(options, startedAt);
            RunResult partial = new RunResult(
                    runId,
                    null,
                    status,
                    memory.snapshot(),
                    TokenUsage.unknown(),
                    startedAt,
                    Duration.between(startedAt, Instant.now())
            );
            AgentRunException failure = error instanceof AgentRunException agentError
                    ? new AgentRunException(agentError.getMessage(), agentError.getCause(), partial)
                    : new AgentRunException("Agent run failed", error, partial);
            throw failure;
        } finally {
            active.set(false);
        }
    }

    /**
     * 汇总记忆中的 token 用量，构建成功结果并发送完成事件。
     *
     * @param context 本次运行上下文
     * @param output 最终输出
     * @param status 运行结束状态
     * @param startedAt 运行开始时间
     * @param runId 本次运行标识
     * @return 完整运行结果
     */
    private RunResult complete(
            RunContext context,
            AgentValue output,
            RunStatus status,
            Instant startedAt,
            UUID runId
    ) {
        // 步骤一：汇总已知用量；任何未知用量会使总量保持未知。
        TokenUsage usage = TokenUsage.of(0, 0);
        boolean knownUsage = true;
        boolean hasUsage = false;
        for (var step : memory.snapshot()) {
            TokenUsage stepUsage = tokenUsage(step);
            if (stepUsage != null) {
                if (stepUsage.totalTokens().isEmpty()) {
                    knownUsage = false;
                    break;
                }
                usage = usage.plus(stepUsage);
                hasUsage = true;
            }
        }
        if (!knownUsage || !hasUsage) {
            usage = TokenUsage.unknown();
        }
        // 步骤二：封装最终结果并向订阅者发布完成事件。
        RunResult result = new RunResult(
                runId,
                output,
                status,
                memory.snapshot(),
                usage,
                startedAt,
                Duration.between(startedAt, Instant.now())
        );
        context.publish(new AgentEvent.RunCompleted(runId, output, result));
        return result;
    }

    /**
     * 从记忆步骤中提取模型调用用量。
     *
     * @param step 待检查的记忆步骤
     * @return 对应 token 用量；非模型步骤返回 {@code null}
     */
    private static TokenUsage tokenUsage(MemoryStep step) {
        if (step instanceof ActionStep actionStep) {
            return actionStep.tokenUsage();
        }
        if (step instanceof PlanningStep planningStep) {
            return planningStep.tokenUsage();
        }
        if (step instanceof FinalAnswerStep finalAnswerStep) {
            return finalAnswerStep.tokenUsage();
        }
        return null;
    }

    /**
     * 运行配置的最终答案校验器，并将失败转换为可重试的动作错误。
     *
     * @param answer 候选最终答案
     * @return 校验错误；全部通过时为空
     */
    private String validateFinalAnswer(AgentValue answer) {
        for (int index = 0; index < finalAnswerChecks.size(); index++) {
            try {
                if (!finalAnswerChecks.get(index).isValid(answer, memory, this)) {
                    return "Final answer check " + (index + 1) + " returned false.";
                }
            } catch (Exception error) {
                String detail = error.getMessage();
                return "Final answer check " + (index + 1) + " threw "
                        + error.getClass().getSimpleName()
                        + (detail == null || detail.isBlank() ? "." : ": " + detail);
            }
        }
        return null;
    }

    /**
     * 复制动作步骤并附加最终答案校验错误。
     *
     * @param step 原始动作步骤
     * @param error 校验错误
     * @return 带错误的动作步骤
     */
    private static ActionStep withError(ActionStep step, String error) {
        return new ActionStep(
                step.stepNumber(),
                step.startedAt(),
                step.duration(),
                step.modelOutput(),
                step.observations(),
                step.tokenUsage(),
                error
        );
    }

    /**
     * 合并运行级超时与默认生成选项。
     *
     * @param options 本次运行选项
     * @return 实际用于该步的生成选项
     */
    private GenerationOptions effectiveGenerationOptions(RunContext context) {
        Duration runTimeout = context.options().timeout();
        if (runTimeout == null) {
            return generationOptions;
        }
        Duration remaining = runTimeout.minus(Duration.between(context.startedAt(), Instant.now()));
        if (remaining.isNegative() || remaining.isZero()) {
            throw new AgentRunException("Agent run timed out after " + runTimeout);
        }
        Duration timeout = generationOptions.timeout() == null || generationOptions.timeout().compareTo(remaining) > 0
                ? remaining
                : generationOptions.timeout();
        return new GenerationOptions(
                generationOptions.temperature(),
                generationOptions.maxTokens(),
                timeout,
                generationOptions.responseSchema()
        );
    }

    /**
     * 判断当前动作是否到达规划周期。
     *
     * @param stepNumber 当前动作步数
     * @return 需要生成初始计划或更新计划时为 true
     */
    private boolean shouldPlan(int stepNumber) {
        return planningInterval != null
                && (stepNumber == 1 || (stepNumber - 1) % planningInterval == 0);
    }

    /**
     * 使用运行历史、工具定义和剩余步数生成计划并记录模型用量。
     *
     * @param turn 当前动作输入
     * @param context 本次运行上下文
     * @param effectiveMaxSteps 本次运行的动作上限
     * @return 可供后续动作使用的计划记忆
     */
    private PlanningStep createPlanningStep(AgentTurn turn, RunContext context, int effectiveMaxSteps) {
        Instant startedAt = Instant.now();
        boolean initialPlan = memory.snapshot().size() == 1;
        List<ToolSpec> planningTools = tools.planningToolSpecs();
        List<ToolSpec> managedAgents = tools.managedAgentSpecs();
        List<ChatMessage> messages = new ArrayList<>();
        if (initialPlan) {
            messages.add(ChatMessage.text(
                    MessageRole.USER,
                    planningPromptTemplates.renderInitialPlan(
                            context.input().task(),
                            planningTools,
                            managedAgents
                    )
            ));
        } else {
            messages.add(ChatMessage.text(
                    MessageRole.SYSTEM,
                    planningPromptTemplates.renderUpdatePlanSystem(context.input().task())
            ));
            List<ChatMessage> history = memory.toMessages(true);
            if (history.size() > 1) {
                messages.addAll(history.subList(1, history.size()));
            }
            messages.add(ChatMessage.text(
                    MessageRole.USER,
                    planningPromptTemplates.renderUpdatePlanUser(
                            context.input().task(),
                            effectiveMaxSteps - turn.stepNumber(),
                            planningTools,
                            managedAgents
                    )
            ));
        }

        context.cancellation().throwIfCancelled();
        GenerationRequest request = new GenerationRequest(
                messages,
                effectiveGenerationOptions(context),
                List.of(),
                context.cancellation(),
                List.of("<end_plan>")
        );
        ChatMessage output = StreamingModelResponse.generate(planningModel, request, context);
        if (output.role() != MessageRole.ASSISTANT || !output.toolCalls().isEmpty()) {
            throw new ModelOutputException("planning model must return assistant text without tool calls");
        }
        String planText = textContent(output.content());
        if (planText.isBlank()) {
            throw new ModelOutputException("planning model returned an empty plan");
        }
        String plan = initialPlan
                ? "Here are the facts I know and the plan of action that I will follow to solve the task:\n```\n"
                        + planText.strip() + "\n```"
                : "I still need to solve the task I was given:\n```\n"
                        + context.input().task()
                        + "\n```\n\nHere are the facts I know and my new/updated plan of action to solve the task:\n```\n"
                        + planText.strip()
                        + "\n```";
        return new PlanningStep(
                turn.stepNumber(),
                startedAt,
                plan,
                output.tokenUsage(),
                messages,
                output
        );
    }

    /**
     * 将计划模型返回的文本和 JSON 片段转换为可读计划内容。
     *
     * @param content 模型响应内容
     * @return 拼接后的计划文本
     */
    private static String textContent(List<ContentPart> content) {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : content) {
            if (part instanceof ContentPart.Text value) {
                text.append(value.text());
            } else if (part instanceof ContentPart.Json value) {
                text.append(value.value());
            } else {
                throw new ModelOutputException(
                        "planning model returned unsupported content: " + part.getClass().getSimpleName()
                );
            }
        }
        return text.toString();
    }

    /**
     * 在动作开始前检查取消请求和总运行时限。
     *
     * @param context 本次运行上下文
     */
    private static void checkRunControls(RunContext context) {
        context.cancellation().throwIfCancelled();
        Duration timeout = context.options().timeout();
        if (timeout != null && Duration.between(context.startedAt(), Instant.now()).compareTo(timeout) >= 0) {
            throw new AgentRunException("Agent run timed out after " + timeout);
        }
    }

    /**
     * 根据取消标记和超时状态确定失败结果状态。
     *
     * @param options 本次运行选项
     * @param startedAt 本次运行开始时间
     * @return 取消、超时或一般失败状态
     */
    private static RunStatus failureStatus(RunOptions options, Instant startedAt) {
        if (options.cancellation().isCancelled()) {
            return RunStatus.CANCELLED;
        }
        Duration timeout = options.timeout();
        if (timeout != null && Duration.between(startedAt, Instant.now()).compareTo(timeout) >= 0) {
            return RunStatus.TIMED_OUT;
        }
        return RunStatus.FAILED;
    }

    /**
     * 将额外输入参数稳定地附加到任务文本。
     *
     * @param input Agent 输入
     * @return 包含额外参数说明的任务文本
     */
    private static String withAdditionalArguments(AgentInput input) {
        if (input.additionalArguments().isEmpty()) {
            return input.task();
        }
        Map<String, JsonNode> arguments = new TreeMap<>();
        input.additionalArguments().forEach(arguments::put);
        return input.task()
                + "\nYou have been provided with these additional arguments, that you can access directly using the "
                + "keys as variables:\n"
                + pythonDictionary(arguments)
                + ".";
    }

    /**
     * 按 Python dict/list/primitive repr 格式生成确定性任务上下文文本。
     *
     * @param values 附加 JSON 参数
     * @return 按键排序并使用 Python 字面量语法的字典文本
     */
    private static String pythonDictionary(Map<String, JsonNode> values) {
        return values.entrySet().stream()
                .map(entry -> pythonString(entry.getKey()) + ": " + pythonValue(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(", ", "{", "}"));
    }

    /**
     * 将 JSON 树转换为 Python 风格的字符串、字典、列表和 primitive 字面量。
     *
     * @param value JSON value
     * @return Python 风格的文本表示
     */
    private static String pythonValue(JsonNode value) {
        if (value.isNull()) {
            return "None";
        }
        if (value.isTextual()) {
            return pythonString(value.textValue());
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? "True" : "False";
        }
        if (value.isNumber()) {
            return value.asText();
        }
        if (value.isArray()) {
            List<String> elements = new ArrayList<>(value.size());
            value.forEach(element -> elements.add(pythonValue(element)));
            return elements.stream().collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        }
        if (value.isObject()) {
            Map<String, JsonNode> members = new TreeMap<>();
            value.fields().forEachRemaining(entry -> members.put(entry.getKey(), entry.getValue()));
            return pythonDictionary(members);
        }
        throw new IllegalArgumentException("unsupported additional argument JSON value: " + value.getNodeType());
    }

    /**
     * 将文本编码为 Python 单引号字符串字面量。
     *
     * @param value 原始字符串
     * @return 带转义的单引号字符串
     */
    private static String pythonString(String value) {
        StringBuilder literal = new StringBuilder("'");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> literal.append("\\\\");
                case '\'' -> literal.append("\\'");
                case '\n' -> literal.append("\\n");
                case '\r' -> literal.append("\\r");
                case '\t' -> literal.append("\\t");
                case '\b' -> literal.append("\\x08");
                case '\f' -> literal.append("\\x0c");
                default -> {
                    if (character < 0x20) {
                        literal.append("\\x")
                                .append(Character.forDigit((character >>> 4) & 0x0f, 16))
                                .append(Character.forDigit(character & 0x0f, 16));
                    } else {
                        literal.append(character);
                    }
                }
            }
        }
        return literal.append('\'').toString();
    }
}
