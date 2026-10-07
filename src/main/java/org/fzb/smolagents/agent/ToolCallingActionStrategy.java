package org.fzb.smolagents.agent;

import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.ModelOutputException;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.core.ToolValidationException;
import org.fzb.smolagents.event.AgentEvent;
import org.fzb.smolagents.memory.ActionStep;
import org.fzb.smolagents.memory.ToolObservation;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.GenerationOptions;
import org.fzb.smolagents.model.GenerationRequest;
import org.fzb.smolagents.model.MessageRole;
import org.fzb.smolagents.model.Model;
import org.fzb.smolagents.model.ToolCall;
import org.fzb.smolagents.tool.ToolExecutionContext;
import org.fzb.smolagents.tool.ToolRegistry;
import org.fzb.smolagents.tool.ToolResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 请求模型选择工具，按调用 ID 顺序执行工具并收集观察结果。
 */
public final class ToolCallingActionStrategy implements ActionStrategy {
    private final Model model;
    private final ToolRegistry tools;
    private final String finalAnswerToolName;

    /**
     * 创建使用默认 {@code final_answer} 名称的工具调用策略。
     *
     * @param model 支持工具调用的模型
     * @param tools 工具注册表
     */
    public ToolCallingActionStrategy(Model model, ToolRegistry tools) {
        this(model, tools, "final_answer");
    }

    /**
     * 创建工具调用策略并指定最终答案工具名。
     *
     * @param model 支持工具调用的模型
     * @param tools 工具注册表
     * @param finalAnswerToolName 最终答案工具名称
     */
    public ToolCallingActionStrategy(Model model, ToolRegistry tools, String finalAnswerToolName) {
        this.model = Objects.requireNonNull(model, "model");
        this.tools = Objects.requireNonNull(tools, "tools");
        if (finalAnswerToolName == null || finalAnswerToolName.isBlank()) {
            throw new IllegalArgumentException("finalAnswerToolName must not be blank");
        }
        this.finalAnswerToolName = finalAnswerToolName;
        if (!model.capabilities().toolCalling()) {
            throw new IllegalArgumentException("model does not support tool calling");
        }
    }

    /**
     * 生成一次模型动作，校验工具调用并依序执行。
     *
     * @param turn 本步输入快照
     * @param context 本次运行上下文
     * @return 继续执行或携带最终答案的动作结果
     */
    @Override
    public ActionOutcome perform(AgentTurn turn, RunContext context) {
        return perform(turn, context, false);
    }

    /**
     * 通过模型流式接口生成动作，并将模型增量转发为 Agent 事件。
     *
     * @param turn 本步输入快照
     * @param context 本次运行上下文
     * @return 继续执行或携带最终答案的动作结果
     */
    @Override
    public ActionOutcome performStreaming(AgentTurn turn, RunContext context) {
        return perform(turn, context, true);
    }

    /**
     * 生成并执行一次工具调用动作。
     *
     * @param turn 本步输入快照
     * @param context 本次运行上下文
     * @param streaming 是否通过模型流生成
     * @return 继续执行或携带最终答案的动作结果
     */
    private ActionOutcome perform(AgentTurn turn, RunContext context, boolean streaming) {
        Instant startedAt = Instant.now();
        context.cancellation().throwIfCancelled();
        // 步骤一：请求模型，并校验角色、工具调用列表和调用 ID 唯一性。
        GenerationRequest request = new GenerationRequest(
                turn.messages(),
                turn.generationOptions(),
                turn.tools(),
                context.cancellation()
        );
        ChatMessage output = streaming
                ? StreamingModelResponse.generate(model, request, context)
                : model.generate(request);
        if (output.role() != MessageRole.ASSISTANT) {
            return protocolFailure(turn, startedAt, output, "model response must have assistant role");
        }
        if (output.toolCalls().isEmpty()) {
            return protocolFailure(turn, startedAt, output, "tool-calling response did not contain a tool call");
        }

        List<ToolCall> calls = output.toolCalls().stream()
                .sorted(java.util.Comparator.comparing(ToolCall::id))
                .toList();
        Set<String> callIds = new HashSet<>();
        int finalAnswerCount = 0;
        for (ToolCall call : calls) {
            if (!callIds.add(call.id())) {
                return protocolFailure(
                        turn,
                        startedAt,
                        output,
                        "model returned duplicate tool call id: " + call.id()
                );
            }
            if (call.name().equals(finalAnswerToolName)) {
                finalAnswerCount++;
            }
        }
        if (finalAnswerCount > 1) {
            return protocolFailure(turn, startedAt, output, "model returned more than one final_answer call");
        }
        if (finalAnswerCount == 1 && calls.size() > 1) {
            return protocolFailure(
                    turn,
                    startedAt,
                    output,
                    "final_answer cannot be called in parallel with other tools"
            );
        }

        // 步骤二：按调用 ID 稳定排序，逐个发布事件、校验并执行工具。
        List<ToolObservation> observations = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        AgentValue finalAnswer = null;
        for (ToolCall call : calls) {
            context.cancellation().throwIfCancelled();
            context.publish(new AgentEvent.ToolStarted(context.runId(), turn.stepNumber(), call.id(), call.name()));
            try {
                ToolResult result = tools.invoke(
                        call,
                        new ToolExecutionContext(context.runId(), call.id(), context.cancellation())
                );
                AgentValue value = asAgentValue(result);
                observations.add(new ToolObservation(call.id(), call.name(), value));
                context.publish(new AgentEvent.ToolCompleted(context.runId(), turn.stepNumber(), call.id(), call.name()));
                if (call.name().equals(finalAnswerToolName)) {
                    finalAnswer = value;
                }
            } catch (ModelOutputException | ToolValidationException | ToolExecutionException error) {
                String detail = error.getClass().getSimpleName()
                        + ": "
                        + (error.getMessage() == null ? "no detail available" : error.getMessage());
                String observation = "Error: " + detail
                        + "\nNow let's retry: take care not to repeat previous errors! "
                        + "If you have retried several times, try a completely different approach.";
                errors.add("Tool call " + call.id() + " (" + call.name() + ") failed: " + detail);
                observations.add(new ToolObservation(call.id(), call.name(), AgentValue.text(observation)));
                context.publish(new AgentEvent.ToolFailed(
                        context.runId(),
                        turn.stepNumber(),
                        call.id(),
                        call.name(),
                        detail
                ));
            }
        }

        // 步骤三：保存模型输出和工具观察，判断本步是否产生最终答案。
        Duration duration = Duration.between(startedAt, Instant.now());
        ActionStep step = new ActionStep(
                turn.stepNumber(),
                startedAt,
                duration,
                output,
                observations,
                output.tokenUsage(),
                errors.isEmpty() ? null : String.join("\n", errors)
        );
        return errors.isEmpty() && finalAnswer != null
                ? new ActionOutcome.Final(step, finalAnswer)
                : new ActionOutcome.Continue(step);
    }

    /**
     * 将模型协议错误保存为可供下一轮修正的动作，而不重放无效工具调用。
     *
     * @param turn 当前动作
     * @param startedAt 动作开始时间
     * @param output 模型原始响应
     * @param error 协议错误
     * @return 带错误记忆的继续动作
     */
    private static ActionOutcome protocolFailure(
            AgentTurn turn,
            Instant startedAt,
            ChatMessage output,
            String error
    ) {
        ChatMessage safeOutput = new ChatMessage(
                MessageRole.ASSISTANT,
                output.content(),
                List.of(),
                null,
                null,
                output.tokenUsage()
        );
        ActionStep step = new ActionStep(
                turn.stepNumber(),
                startedAt,
                Duration.between(startedAt, Instant.now()),
                safeOutput,
                List.of(),
                output.tokenUsage(),
                error
        );
        return new ActionOutcome.Continue(step);
    }

    /**
     * 达到最大动作步数后请求不带工具的纯文本最终答复。
     *
     * @param messages 当前对话消息
     * @param context 本次运行上下文
     * @return 最终答案及模型用量
     */
    @Override
    public FinalAnswerResponse provideFinalAnswer(List<ChatMessage> messages, RunContext context) {
        List<ChatMessage> finalMessages = new ArrayList<>(messages);
        finalMessages.add(ChatMessage.text(
                MessageRole.USER,
                "The action limit has been reached. Give the best final answer based on the work so far."
        ));
        GenerationRequest request = new GenerationRequest(
                finalMessages,
                new GenerationOptions(null, null, context.options().timeout(), null),
                List.of(),
                context.cancellation()
        );
        ChatMessage response = StreamingModelResponse.generate(model, request, context);
        if (response.role() != MessageRole.ASSISTANT) {
            throw new ModelOutputException("final answer response must have assistant role");
        }
        if (response.content().isEmpty()) {
            throw new ModelOutputException("final answer response did not contain content");
        }
        if (!response.toolCalls().isEmpty()) {
            throw new ModelOutputException("final answer response unexpectedly requested a tool");
        }
        List<ContentPart> content = response.content();
        return new FinalAnswerResponse(new AgentValue(content, null), response.tokenUsage());
    }

    /**
     * 将工具结果映射为 Agent 值并拒绝完全无内容的结果。
     *
     * @param result 工具执行结果
     * @return Agent 可存储和返回的值
     */
    private static AgentValue asAgentValue(ToolResult result) {
        if (result.structuredContent() != null) {
            return new AgentValue(result.content(), result.structuredContent());
        }
        if (result.content().isEmpty()) {
            throw new ModelOutputException("tool returned an empty result");
        }
        return new AgentValue(result.content(), null);
    }
}
