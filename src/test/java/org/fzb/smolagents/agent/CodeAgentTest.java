package org.fzb.smolagents.agent;

import org.fzb.smolagents.core.AgentInput;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.RunOptions;
import org.fzb.smolagents.core.RunStatus;
import org.fzb.smolagents.executor.PythonProcessCodeExecutor;
import org.fzb.smolagents.memory.ActionStep;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.GenerationRequest;
import org.fzb.smolagents.model.Model;
import org.fzb.smolagents.model.ModelCapabilities;
import org.fzb.smolagents.model.ModelStreamEvent;
import org.fzb.smolagents.model.MessageRole;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 CodeAgent 的模型代码动作、Python 状态及 final_answer 闭环。
 */
class CodeAgentTest {
    private static final String PYTHON = System.getProperty(
            "smolagents.python",
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? "python"
                    : "python3"
    );

    /**
     * Python 未安装时跳过需要本地执行进程的 Agent 测试。
     */
    @BeforeAll
    static void requirePythonRuntime() {
        Assumptions.assumeTrue(isPythonAvailable(), "CPython is required for CodeAgent tests");
    }

    /**
     * 验证 Agent 可跨动作保留 Python 变量并返回 final_answer 原始数值。
     */
    @Test
    void executesMultipleCodeActionsAndReturnsFinalAnswer() {
        ScriptedModel model = new ScriptedModel(List.of(
                "```python\nanswer = 6 * 7\nanswer\n```",
                "```python\nfinal_answer(answer)\n```"
        ));
        try (CodeAgent agent = new CodeAgent(model, List.of(), new PythonProcessCodeExecutor(PYTHON))) {
            var result = agent.runFull(
                    new AgentInput("Calculate six times seven.", Map.of()),
                    RunOptions.defaults()
            );

            assertEquals(RunStatus.SUCCESS, result.status());
            assertEquals(42, result.output().structuredContent().asInt());
            assertEquals(2, model.requests.get());
            assertTrue(agent.memory().snapshot().stream()
                    .filter(org.fzb.smolagents.memory.ActionStep.class::isInstance)
                    .map(org.fzb.smolagents.memory.ActionStep.class::cast)
                    .allMatch(step -> !step.observations().isEmpty()));
        }
    }

    /**
     * 验证调用方可向默认 Python 白名单追加依赖并使用新增模块。
     */
    @Test
    void allowsExplicitAdditionalPythonImports() {
        ScriptedModel model = new ScriptedModel(List.of(
                "```python\nimport json\nfinal_answer(json.dumps({'ok': True}))\n```"
        ));
        try (CodeAgent agent = new CodeAgent(model, List.of(), List.of("json"))) {
            var result = agent.runFull(
                    new AgentInput("Return a JSON value.", Map.of()),
                    RunOptions.defaults()
            );

            assertEquals(RunStatus.SUCCESS, result.status());
            assertEquals("{\"ok\": true}", result.output().plainText().orElseThrow());
        }
    }

    /**
     * 验证自定义 literal code block tags 与 CodeAgent 默认提示保持一致。
     */
    @Test
    void extractsActionsFromCustomCodeBlockTags() {
        ScriptedModel model = new ScriptedModel(List.of("Reasoning.\n<py>final_answer(42)</py>"));
        try (CodeAgent agent = new CodeAgent(
                model,
                List.of(),
                new PythonProcessCodeExecutor(PYTHON),
                6,
                null,
                null,
                List.of(),
                new CodeBlockTags("<py>", "</py>")
        )) {
            var result = agent.runFull(
                    new AgentInput("Return forty-two.", Map.of()),
                    RunOptions.defaults()
            );

            assertEquals(RunStatus.SUCCESS, result.status());
            assertEquals(42, result.output().structuredContent().asInt());
        }
    }

    /**
     * 验证 CodeAgent 的最终答案校验失败会进入后续代码动作并可再次通过。
     */
    @Test
    void retriesFinalAnswerRejectedByCheck() {
        ScriptedModel model = new ScriptedModel(List.of(
                "```python\nfinal_answer(42)\n```",
                "```python\nfinal_answer(42)\n```"
        ));
        AtomicInteger validationCount = new AtomicInteger();
        FinalAnswerCheck check = (answer, memory, agent) -> {
            int attempt = validationCount.incrementAndGet();
            assertEquals(42, answer.structuredContent().asInt());
            assertSame(agent.memory(), memory);
            assertEquals(attempt == 1 ? 1 : 2, memory.snapshot().size());
            return attempt > 1;
        };

        try (CodeAgent agent = new CodeAgent(
                model,
                List.of(),
                new PythonProcessCodeExecutor(PYTHON),
                2,
                null,
                null,
                List.of(check)
        )) {
            var result = agent.runFull(
                    new AgentInput("Return forty-two.", Map.of()),
                    RunOptions.defaults()
            );

            assertEquals(RunStatus.SUCCESS, result.status());
            assertEquals(2, validationCount.get());
            assertTrue(agent.memory().snapshot().stream()
                    .filter(ActionStep.class::isInstance)
                    .map(ActionStep.class::cast)
                    .anyMatch(step -> step.error() != null && step.error().contains("returned false")));
        }
    }

    /**
     * 检测本机 Python 命令是否可启动。
     *
     * @return 命令在限定时间内退出时为 true
     */
    private static boolean isPythonAvailable() {
        try {
            Process process = new ProcessBuilder(PYTHON, "--version").redirectErrorStream(true).start();
            return process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    && process.exitValue() == 0;
        } catch (Exception error) {
            return false;
        }
    }

    /**
     * 按固定顺序返回代码动作的模型替身。
     */
    private static final class ScriptedModel implements Model {
        private final List<String> responses;
        private final AtomicInteger requests = new AtomicInteger();

        /**
         * 创建带有序文本响应队列的模型替身。
         *
         * @param responses 每轮模型响应文本
         */
        private ScriptedModel(List<String> responses) {
            this.responses = List.copyOf(responses);
        }

        /**
         * 返回下一条预置代码动作。
         *
         * @param request Agent 生成请求
         * @return assistant 代码响应
         */
        @Override
        public ChatMessage generate(GenerationRequest request) {
            int index = requests.getAndIncrement();
            if (index >= responses.size()) {
                throw new IllegalStateException("No scripted response remains");
            }
            return ChatMessage.text(MessageRole.ASSISTANT, responses.get(index));
        }

        /**
         * 提供最小的完成事件流适配，供 Model 契约使用。
         *
         * @param request Agent 生成请求
         * @return 单事件模型流
         */
        @Override
        public Flow.Publisher<ModelStreamEvent> stream(GenerationRequest request) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    if (n > 0) {
                        subscriber.onNext(new ModelStreamEvent.Completed(generate(request)));
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                }
            });
        }

        /**
         * 声明此模型通过普通文本生成代码，不使用结构化工具调用。
         *
         * @return 不启用工具调用的模型能力
         */
        @Override
        public ModelCapabilities capabilities() {
            return new ModelCapabilities(false, false, false, true);
        }
    }
}
