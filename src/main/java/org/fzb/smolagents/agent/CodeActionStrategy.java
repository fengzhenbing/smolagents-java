package org.fzb.smolagents.agent;

import org.fzb.smolagents.core.AgentRunException;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ModelOutputException;
import org.fzb.smolagents.executor.CodeExecutor;
import org.fzb.smolagents.executor.ExecutorFailure;
import org.fzb.smolagents.executor.ExecutorRequest;
import org.fzb.smolagents.executor.ExecutorResult;
import org.fzb.smolagents.memory.ActionStep;
import org.fzb.smolagents.memory.ToolObservation;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.GenerationOptions;
import org.fzb.smolagents.model.GenerationRequest;
import org.fzb.smolagents.model.MessageRole;
import org.fzb.smolagents.model.Model;
import org.fzb.smolagents.tool.Tool;
import org.fzb.smolagents.util.AgentUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将模型生成的 Python 代码提交给 CodeExecutor，并把执行结果映射为 Agent observation。
 */
final class CodeActionStrategy implements ActionStrategy {
    private final Model model;
    private final CodeExecutor executor;
    private final Map<String, Tool> tools;
    private final CodeBlockTags codeBlockTags;
    private final Map<String, AgentValue> variables = new LinkedHashMap<>();

    /**
     * 创建持有模型、执行器、工具和跨动作变量快照的代码策略。
     *
     * @param model 生成 Python 动作的模型
     * @param executor 执行 Python 代码的后端
     * @param tools 可从 Python 代码调用的工具
     */
    CodeActionStrategy(Model model, CodeExecutor executor, Map<String, Tool> tools) {
        this(model, executor, tools, null);
    }

    /**
     * 创建使用自定义 literal code tags 的代码策略。
     *
     * @param model 生成 Python 动作的模型
     * @param executor Python 执行后端
     * @param tools 可从 Python 调用的工具
     * @param codeBlockTags 可选开闭标记；null 时兼容 XML 和 Markdown
     */
    CodeActionStrategy(Model model, CodeExecutor executor, Map<String, Tool> tools, CodeBlockTags codeBlockTags) {
        this.model = Objects.requireNonNull(model, "model");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.tools = Map.copyOf(Objects.requireNonNull(tools, "tools"));
        this.codeBlockTags = codeBlockTags;
    }

    /**
     * 请求模型生成代码，执行动作并保存工具观察和最终答案。
     *
     * @param turn 本步输入快照
     * @param context 本次运行上下文
     * @return 继续运行或携带 final_answer 值的动作结果
     */
    @Override
    public synchronized ActionOutcome perform(AgentTurn turn, RunContext context) {
        Instant startedAt = Instant.now();
        context.cancellation().throwIfCancelled();

        // 步骤一：请求纯文本代码响应并确认角色和内容符合协议。
        GenerationRequest request = new GenerationRequest(
                turn.messages(),
                turn.generationOptions(),
                List.of(),
                context.cancellation()
        );
        ChatMessage modelOutput = StreamingModelResponse.generate(model, request, context);
        if (modelOutput.role() != MessageRole.ASSISTANT) {
            throw new ModelOutputException("CodeAgent model response must have assistant role");
        }
        String responseText = textContent(modelOutput.content());
        if (responseText.isBlank()) {
            throw new ModelOutputException("CodeAgent model response did not contain code");
        }
        String code = extractCode(responseText, codeBlockTags);

        // 步骤二：合并输入变量、执行代码，并将每次成功运行的变量快照延续到下一步。
        context.input().additionalArguments().forEach((name, value) ->
                variables.put(name, new AgentValue(List.of(new ContentPart.Json(value)), value)));
        ExecutorResult result = executor.execute(new ExecutorRequest(
                code,
                Map.copyOf(variables),
                tools,
                remainingTimeout(context),
                context.cancellation(),
                context.runId()
        ));
        variables.putAll(result.updatedVariables());
        if (result.failure() != null) {
            throw executionFailure(result.failure());
        }
        AgentValue output = result.value() == null ? AgentValue.text("None") : result.value();

        // 步骤三：把 stdout 与最后结果写入 observation，并生成本步记忆记录。
        String callId = "python-step-" + turn.stepNumber();
        ToolObservation observation = new ToolObservation(
                callId,
                "python_interpreter",
                AgentValue.text(formatObservation(result.stdout(), output))
        );
        ActionStep actionStep = new ActionStep(
                turn.stepNumber(),
                startedAt,
                Duration.between(startedAt, Instant.now()),
                modelOutput,
                List.of(observation),
                modelOutput.tokenUsage()
        );
        return result.finalAnswer()
                ? new ActionOutcome.Final(actionStep, output)
                : new ActionOutcome.Continue(actionStep);
    }

    /**
     * 计算扣除模型与前序步骤耗时后的剩余运行时限。
     *
     * @param context 本次运行上下文
     * @return 剩余时限；未配置运行时限时返回 null
     */
    private static Duration remainingTimeout(RunContext context) {
        Duration timeout = context.options().timeout();
        if (timeout == null) {
            return null;
        }
        Duration remaining = timeout.minus(Duration.between(context.startedAt(), Instant.now()));
        if (remaining.isNegative() || remaining.isZero()) {
            throw new AgentRunException("Agent run timed out after " + timeout);
        }
        return remaining;
    }

    /**
     * 达到动作上限后请求模型根据现有记忆给出纯文本兜底答案。
     *
     * @param messages 当前模型上下文
     * @param context 本次运行上下文
     * @return 最终答案及其模型用量
     */
    @Override
    public FinalAnswerResponse provideFinalAnswer(List<ChatMessage> messages, RunContext context) {
        var finalMessages = new java.util.ArrayList<>(messages);
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
        if (response.role() != MessageRole.ASSISTANT || response.content().isEmpty()
                || !response.toolCalls().isEmpty()) {
            throw new ModelOutputException("CodeAgent max-step response must be non-empty assistant text");
        }
        return new FinalAnswerResponse(new AgentValue(response.content(), null), response.tokenUsage());
    }

    /**
     * 解析 Python worker 错误并转换为 Agent 执行失败。
     *
     * @param failure 执行器失败详情
     * @return 包含行号上下文的运行异常
     */
    private static AgentRunException executionFailure(ExecutorFailure failure) {
        String location = failure.lineNumber() == null ? "" : " at line " + failure.lineNumber();
        return new AgentRunException(
                "Python code execution failed" + location + ": " + failure.type() + ": " + failure.message()
        );
    }

    /**
     * 从 Markdown 或 XML 代码围栏提取 Python 代码，未使用围栏时保留原始响应。
     *
     * @param response 模型文本响应
     * @return 待执行的代码
     */
    private static String extractCode(String response, CodeBlockTags codeBlockTags) {
        String code = AgentUtils.extractCodeFromText(response, codeBlockTags);
        if (code != null && !code.isBlank()) {
            return code;
        }
        if (response.isBlank()) {
            throw new ModelOutputException("CodeAgent could not find a Python code block");
        }
        return response.strip();
    }

    /**
     * 合并 assistant 内容中的文本和 JSON 片段为模型代码文本。
     *
     * @param content 消息内容
     * @return 可解析的模型输出文本
     */
    private static String textContent(List<ContentPart> content) {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : content) {
            if (part instanceof ContentPart.Text value) {
                text.append(value.text());
            } else if (part instanceof ContentPart.Json value) {
                text.append(value.value());
            } else {
                throw new ConfigurationException(
                        "CodeAgent requires text model output; received " + part.getClass().getSimpleName()
                );
            }
        }
        return text.toString();
    }

    /**
     * 构造便于下一轮模型读取的执行日志和最后表达式结果。
     *
     * @param stdout Python 标准输出
     * @param output 最后执行结果
     * @return observation 文本
     */
    private static String formatObservation(String stdout, AgentValue output) {
        StringBuilder observation = new StringBuilder("Execution logs:\n").append(stdout);
        observation.append("Last output from code snippet:\n")
                .append(output.plainText().orElseGet(() -> {
                    if (output.structuredContent() != null) {
                        return output.structuredContent().toString();
                    }
                    return output.content().stream()
                            .map(part -> part instanceof ContentPart.Json json
                                    ? json.value().toString()
                                    : part instanceof ContentPart.Text text ? text.text() : "[media]")
                            .collect(java.util.stream.Collectors.joining());
                }));
        return observation.toString();
    }
}
