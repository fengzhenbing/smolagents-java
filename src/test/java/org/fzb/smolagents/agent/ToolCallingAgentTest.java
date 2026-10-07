package org.fzb.smolagents.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fzb.smolagents.core.AgentInput;
import org.fzb.smolagents.core.AgentRunException;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.RunOptions;
import org.fzb.smolagents.core.RunStatus;
import org.fzb.smolagents.core.ModelException;
import org.fzb.smolagents.event.AgentEvent;
import org.fzb.smolagents.memory.ActionStep;
import org.fzb.smolagents.memory.AgentMemory;
import org.fzb.smolagents.memory.AgentMemoryCodec;
import org.fzb.smolagents.memory.FinalAnswerStep;
import org.fzb.smolagents.memory.MemoryStep;
import org.fzb.smolagents.memory.PlanningStep;
import org.fzb.smolagents.memory.TaskStep;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.GenerationRequest;
import org.fzb.smolagents.model.MessageRole;
import org.fzb.smolagents.model.Model;
import org.fzb.smolagents.model.ModelCapabilities;
import org.fzb.smolagents.model.ModelStreamEvent;
import org.fzb.smolagents.model.TokenUsage;
import org.fzb.smolagents.model.ToolCall;
import org.fzb.smolagents.tool.Tool;
import org.fzb.smolagents.tool.ToolExecutionContext;
import org.fzb.smolagents.tool.FinalAnswerTool;
import org.fzb.smolagents.tool.JsonSchemaToolArgumentValidator;
import org.fzb.smolagents.tool.ManagedAgentTool;
import org.fzb.smolagents.tool.ToolResult;
import org.fzb.smolagents.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证工具调用 Agent 的运行闭环、记忆、步数限制及事件流。
 */
class ToolCallingAgentTest {
    private static final AgentInput INPUT = new AgentInput("Calculate the result.", Map.of());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证 Agent 会先执行业务工具，再将结果作为最终答案返回。
     */
    @Test
    void runsToolThenFinalAnswerAndPreservesUsageAndMemory() {
        FakeModel model = new FakeModel(false);
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of(new EchoTool()));

        var result = agent.runFull(INPUT, RunOptions.defaults());

        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals("7.2904", result.output().plainText().orElseThrow());
        assertEquals(4, result.steps().size());
        assertEquals(8, result.tokenUsage().totalTokens().orElseThrow());
        ActionStep firstAction = assertInstanceOf(ActionStep.class, result.steps().get(1));
        assertEquals("echo", firstAction.observations().getFirst().toolName());
        assertEquals("call-echo", model.requests.get(1).messages().getLast().toolCallId());
    }

    /**
     * 验证 reset 选项分别控制跨运行记忆延续和清空行为。
     */
    @Test
    void resetOptionControlsConversationMemory() {
        ToolCallingAgent agent = new ToolCallingAgent(new FakeModel(false), List.of(new EchoTool()));

        agent.runFull(INPUT, RunOptions.defaults());
        var continued = agent.runFull(INPUT, new RunOptions(false, null, true, null,
                new org.fzb.smolagents.core.CancellationToken()));
        assertEquals(8, continued.steps().size());

        var reset = agent.runFull(INPUT, RunOptions.defaults());
        assertEquals(4, reset.steps().size());
    }

    /**
     * 验证安全 JSON 恢复的会话记忆可注入 Agent，并拒绝系统提示词不匹配的快照。
     */
    @Test
    void restoresSafeMemorySnapshotIntoAgent() {
        FakeModel model = new FakeModel(false);
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of(new EchoTool()), 6, "system");
        AgentMemory saved = new AgentMemory("system", List.of(
                new TaskStep(0, Instant.EPOCH, "previous task")
        ));
        AgentMemory restored = AgentMemoryCodec.loads(AgentMemoryCodec.dumps(saved));

        agent.restoreMemory(restored);

        assertSame(restored, agent.memory());
        assertEquals("previous task", ((TaskStep) agent.memory().snapshot().getFirst()).task());
        var resumed = agent.runFull(
                INPUT,
                new RunOptions(false, null, true, null, new CancellationToken())
        );
        assertEquals(RunStatus.SUCCESS, resumed.status());
        assertTrue(containsText(model.requests.getFirst().messages(), "previous task"));
        assertThrows(
                ConfigurationException.class,
                () -> agent.restoreMemory(new AgentMemory("different system prompt"))
        );
    }

    /**
     * 验证附加参数以稳定文本进入任务上下文。
     */
    @Test
    void includesAdditionalArgumentsInTaskContext() {
        ScriptedModel model = new ScriptedModel(List.of(
                FakeModel.message("Return answer.", List.of(
                        new ToolCall("call-final", "final_answer", FakeModel.args("answer", "done"))
                ))
        ));
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of());
        AgentInput input = new AgentInput("Use the provided context.", Map.of(
                "region", MAPPER.getNodeFactory().textNode("north"),
                "limit", MAPPER.getNodeFactory().numberNode(3)
        ));

        agent.runFull(input, RunOptions.defaults());

        assertTrue(containsText(model.requests.getFirst().messages(), "Use the provided context."));
        assertTrue(containsText(model.requests.getFirst().messages(), "limit"));
        assertTrue(containsText(model.requests.getFirst().messages(), "region"));
        assertTrue(containsText(model.requests.getFirst().messages(), "north"));
    }

    /**
     * 验证达到动作步数上限后会生成兜底答案并保留对应状态。
     */
    @Test
    void maxStepsReturnsFallbackAnswerAndNonSuccessStatus() {
        ToolCallingAgent agent = new ToolCallingAgent(new FakeModel(true), List.of(new EchoTool()), 1, "system");

        var result = agent.runFull(INPUT, RunOptions.defaults());

        assertEquals(RunStatus.MAX_STEPS_REACHED, result.status());
        assertEquals("Best answer from partial work.", result.output().plainText().orElseThrow());
        assertEquals(8, result.tokenUsage().totalTokens().orElseThrow());
    }

    /**
     * 验证本次运行的 maxSteps 会覆盖 Agent 默认动作上限。
     */
    @Test
    void runOptionsCanOverrideMaximumActionSteps() {
        FakeModel model = new FakeModel(true);
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of(new EchoTool()), 5, "system");
        RunOptions options = new RunOptions(false, 2, true, null, new CancellationToken());

        var result = agent.runFull(INPUT, options);

        assertEquals(RunStatus.MAX_STEPS_REACHED, result.status());
        assertEquals(2, result.steps().stream().filter(ActionStep.class::isInstance).count());
        assertEquals(3, model.requests.size());
    }

    /**
     * 验证模型未报告 token 用量时完整结果仍保留未知值。
     */
    @Test
    void fullResultPreservesUnknownModelTokenUsage() {
        ChatMessage response = new ChatMessage(
                MessageRole.ASSISTANT,
                List.of(new ContentPart.Text("Returning answer.")),
                List.of(new ToolCall("call-final", "final_answer", FakeModel.args("answer", "done"))),
                null,
                null,
                TokenUsage.unknown()
        );
        ToolCallingAgent agent = new ToolCallingAgent(new ScriptedModel(List.of(response)), List.of());

        var result = agent.runFull(INPUT, RunOptions.defaults());

        assertTrue(result.tokenUsage().totalTokens().isEmpty());
    }

    /**
     * 验证校验器可读取候选答案、当前记忆和 Agent，并拒绝后触发新一轮动作。
     */
    @Test
    void rejectedFinalAnswerIsRecordedAndRetried() {
        FakeModel model = new FakeModel(false);
        java.util.concurrent.atomic.AtomicInteger validationCount = new java.util.concurrent.atomic.AtomicInteger();
        FinalAnswerCheck check = (answer, memory, agent) -> {
            assertEquals("7.2904", answer.plainText().orElseThrow());
            assertSame(agent.memory(), memory);
            int attempt = validationCount.incrementAndGet();
            assertEquals(attempt == 1 ? 2 : 4, memory.snapshot().size());
            assertTrue(agent.tools().specs().stream().anyMatch(spec -> spec.name().equals("echo")));
            return attempt > 1;
        };
        ToolCallingAgent agent = new ToolCallingAgent(
                model,
                List.of(new EchoTool()),
                4,
                "system",
                null,
                List.of(check)
        );

        var result = agent.runFull(INPUT, RunOptions.defaults());

        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals(2, validationCount.get());
        List<ActionStep> actions = result.steps().stream()
                .filter(ActionStep.class::isInstance)
                .map(ActionStep.class::cast)
                .toList();
        assertEquals("Final answer check 1 returned false.", actions.get(1).error());
        assertTrue(containsText(model.requests.get(2).messages(), "Final answer check 1 returned false."));
    }

    /**
     * 验证校验器异常会被记录；若动作预算耗尽则走现有兜底答案路径。
     */
    @Test
    void checkerExceptionIsRecordedBeforeMaxStepsFallback() {
        FinalAnswerCheck check = (answer, memory, agent) -> {
            throw new IllegalStateException("validator exploded");
        };
        ToolCallingAgent agent = new ToolCallingAgent(
                new FakeModel(false),
                List.of(new EchoTool()),
                2,
                "system",
                null,
                List.of(check)
        );

        var result = agent.runFull(INPUT, RunOptions.defaults());

        assertEquals(RunStatus.MAX_STEPS_REACHED, result.status());
        assertEquals("Best answer from partial work.", result.output().plainText().orElseThrow());
        ActionStep rejectedAnswer = result.steps().stream()
                .filter(ActionStep.class::isInstance)
                .map(ActionStep.class::cast)
                .filter(step -> step.error() != null)
                .findFirst()
                .orElseThrow();
        assertTrue(rejectedAnswer.error().contains("IllegalStateException: validator exploded"));
        assertTrue(containsText(agent.memory().toMessages(false), "validator exploded"));
    }

    /**
     * 验证参数 schema 错误会反馈给模型，并允许其修正后完成任务。
     */
    @Test
    void invalidToolArgumentsAreRecordedAndRetried() {
        ScriptedModel model = new ScriptedModel(List.of(
                FakeModel.message("Bad call.", List.of(
                        new ToolCall("call-echo-1", "echo", MAPPER.createObjectNode().put("text", 42))
                )),
                FakeModel.message("Corrected call.", List.of(
                        new ToolCall("call-echo-2", "echo", FakeModel.args("text", "fixed"))
                )),
                FakeModel.message("Final answer.", List.of(
                        new ToolCall("call-final", "final_answer", FakeModel.args("answer", "fixed"))
                ))
        ));
        EchoTool echoTool = new EchoTool();
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of(echoTool), 3, "system");

        var result = agent.runFull(INPUT, RunOptions.defaults());

        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals("fixed", result.output().plainText().orElseThrow());
        assertEquals(1, echoTool.invocations);
        ActionStep failed = assertInstanceOf(ActionStep.class, result.steps().get(1));
        assertTrue(failed.error().contains("ToolValidationException"), failed.error());
        assertEquals("call-echo-1", failed.observations().getFirst().callId());
        assertTrue(failed.observations().getFirst().result().plainText().orElseThrow().contains("Error:"));
        assertTrue(containsText(model.requests.get(1).messages(), "ToolValidationException"));
    }

    /**
     * 验证工具执行异常以可诊断错误进入记忆，模型可随后改用其他动作完成任务。
     */
    @Test
    void toolExecutionFailureIsRecordedAndRetried() {
        ScriptedModel model = new ScriptedModel(List.of(
                FakeModel.message("Call unstable tool.", List.of(
                        new ToolCall("call-unstable", "unstable", MAPPER.createObjectNode())
                )),
                FakeModel.message("Return answer.", List.of(
                        new ToolCall("call-final", "final_answer", FakeModel.args("answer", "recovered"))
                ))
        ));
        Tool unstable = new Tool() {
            private final ToolSpec spec = new ToolSpec(
                    "unstable",
                    "A tool that fails.",
                    MAPPER.createObjectNode().put("type", "object"),
                    true
            );

            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
                throw new IllegalStateException("backend unavailable");
            }
        };
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of(unstable), 2, "system");

        var result = agent.runFull(INPUT, RunOptions.defaults());

        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals("recovered", result.output().plainText().orElseThrow());
        ActionStep failed = assertInstanceOf(ActionStep.class, result.steps().get(1));
        assertTrue(failed.error().contains("ToolExecutionException"));
        assertTrue(failed.error().contains("backend unavailable"));
        assertTrue(containsText(model.requests.get(1).messages(), "backend unavailable"));
    }

    /**
     * 验证可恢复的工具错误会发布 ToolFailed，且运行仍可正常完成。
     *
     * @throws InterruptedException 等待运行事件时线程被中断
     */
    @Test
    void publishesToolFailureEventAndCompletesAfterRetry() throws InterruptedException {
        ScriptedModel model = new ScriptedModel(List.of(
                FakeModel.message("Call unstable tool.", List.of(
                        new ToolCall("call-unstable", "unstable", MAPPER.createObjectNode())
                )),
                FakeModel.message("Return answer.", List.of(
                        new ToolCall("call-final", "final_answer", FakeModel.args("answer", "recovered"))
                ))
        ));
        Tool unstable = new Tool() {
            private final ToolSpec spec = new ToolSpec(
                    "unstable",
                    "A tool that fails.",
                    MAPPER.createObjectNode().put("type", "object"),
                    true
            );

            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
                throw new IllegalStateException("backend unavailable");
            }
        };
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of(unstable), 2, "system");
        CountDownLatch completed = new CountDownLatch(1);
        List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<Throwable> runError = new AtomicReference<>();

        agent.stream(INPUT, RunOptions.defaults()).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentEvent event) {
                events.add(event);
            }

            @Override
            public void onError(Throwable throwable) {
                runError.set(throwable);
                completed.countDown();
            }

            @Override
            public void onComplete() {
                completed.countDown();
            }
        });

        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertNull(runError.get());
        assertTrue(events.stream().anyMatch(AgentEvent.ToolFailed.class::isInstance));
        assertTrue(events.stream().anyMatch(AgentEvent.RunCompleted.class::isInstance));
    }

    /**
     * 验证 final_answer 不会和其他工具并行执行，模型收到协议错误后可单独重试。
     */
    @Test
    void finalAnswerCannotRunAlongsideAnotherTool() {
        ScriptedModel model = new ScriptedModel(List.of(
                FakeModel.message("Mixed final and tool call.", List.of(
                        new ToolCall("call-echo", "echo", FakeModel.args("text", "should-not-run")),
                        new ToolCall("call-final", "final_answer", FakeModel.args("answer", "recovered"))
                )),
                FakeModel.message("Final answer only.", List.of(
                        new ToolCall("call-final-2", "final_answer", FakeModel.args("answer", "recovered"))
                ))
        ));
        EchoTool echoTool = new EchoTool();
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of(echoTool), 2, "system");

        var result = agent.runFull(INPUT, RunOptions.defaults());

        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals("recovered", result.output().plainText().orElseThrow());
        assertEquals(0, echoTool.invocations);
        ActionStep rejected = assertInstanceOf(ActionStep.class, result.steps().get(1));
        assertTrue(rejected.error().contains("cannot be called in parallel"));
        assertTrue(containsText(model.requests.get(1).messages(), "cannot be called in parallel"));
    }

    /**
     * 验证模型返回多个普通工具调用时均会执行，并按调用 ID 顺序记录观察。
     */
    @Test
    void executesMultipleToolCallsInStableOrder() {
        ScriptedModel model = new ScriptedModel(List.of(
                FakeModel.message("Call tools.", List.of(
                        new ToolCall("call-z", "echo", FakeModel.args("text", "last")),
                        new ToolCall("call-a", "echo", FakeModel.args("text", "first"))
                )),
                FakeModel.message("Return answer.", List.of(
                        new ToolCall("call-final", "final_answer", FakeModel.args("answer", "done"))
                ))
        ));
        EchoTool echoTool = new EchoTool();
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of(echoTool), 2, "system");

        var result = agent.runFull(INPUT, RunOptions.defaults());

        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals(2, echoTool.invocations);
        ActionStep action = assertInstanceOf(ActionStep.class, result.steps().get(1));
        assertEquals(List.of("call-a", "call-z"), action.observations().stream()
                .map(org.fzb.smolagents.memory.ToolObservation::callId)
                .toList());
    }

    /**
     * 验证未知工具名成为模型可见错误，并允许下一轮改用有效工具。
     */
    @Test
    void unknownToolIsReportedAndRetried() {
        ScriptedModel model = new ScriptedModel(List.of(
                FakeModel.message("Call missing tool.", List.of(
                        new ToolCall("call-missing", "missing", MAPPER.createObjectNode())
                )),
                FakeModel.message("Return answer.", List.of(
                        new ToolCall("call-final", "final_answer", FakeModel.args("answer", "recovered"))
                ))
        ));
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of(), 2, "system");

        var result = agent.runFull(INPUT, RunOptions.defaults());

        assertEquals(RunStatus.SUCCESS, result.status());
        ActionStep failed = assertInstanceOf(ActionStep.class, result.steps().get(1));
        assertTrue(failed.error().contains("unknown tool: missing"));
        assertTrue(containsText(model.requests.get(1).messages(), "unknown tool: missing"));
    }

    /**
     * 验证模型调用异常以失败结果传播，并保留尚未完成的运行快照。
     */
    @Test
    void modelGenerationFailurePreservesPartialRunResult() {
        Model model = new Model() {
            @Override
            public ChatMessage generate(GenerationRequest request) {
                throw new ModelException("provider unavailable");
            }

            @Override
            public Flow.Publisher<ModelStreamEvent> stream(GenerationRequest request) {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override
            public ModelCapabilities capabilities() {
                return new ModelCapabilities(true, false, false, true);
            }
        };
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of());

        AgentRunException failure = org.junit.jupiter.api.Assertions.assertThrows(
                AgentRunException.class,
                () -> agent.runFull(INPUT, RunOptions.defaults())
        );

        assertNotNull(failure.getCause());
        assertInstanceOf(ModelException.class, failure.getCause());
        assertNotNull(failure.partialResult());
        assertEquals(RunStatus.FAILED, failure.partialResult().status());
        assertEquals(1, failure.partialResult().steps().size());
    }

    /**
     * 验证步骤回调按具体类型优先、注册顺序触发，且终止步骤也会通知回调。
     */
    @Test
    void invokesTypedAndGenericCallbacksForCompletedSteps() {
        ToolCallingAgent agent = new ToolCallingAgent(new FakeModel(false), List.of(new EchoTool()));
        List<String> callbackOrder = new ArrayList<>();
        List<Integer> memorySizes = new ArrayList<>();
        agent.registerStepCallback(MemoryStep.class, (step, runtime) -> {
            callbackOrder.add("generic:" + step.getClass().getSimpleName());
            memorySizes.add(runtime.memory().snapshot().size());
        });
        agent.registerStepCallback(ActionStep.class, (step, runtime) ->
                callbackOrder.add("action:" + step.stepNumber()));

        agent.runFull(INPUT, RunOptions.defaults());

        assertEquals(List.of(
                "action:1",
                "generic:ActionStep",
                "action:2",
                "generic:ActionStep",
                "generic:FinalAnswerStep"
        ), callbackOrder);
        assertEquals(List.of(1, 2, 3), memorySizes);
    }

    /**
     * 验证步骤回调异常会让运行失败，并保留回调触发前的部分记忆。
     */
    @Test
    void callbackFailureFailsRunWithoutCommittingTheStep() {
        ToolCallingAgent agent = new ToolCallingAgent(new FakeModel(false), List.of(new EchoTool()));
        agent.registerStepCallback(ActionStep.class, (step, runtime) -> {
            throw new IllegalStateException("callback failed");
        });

        AgentRunException failure = org.junit.jupiter.api.Assertions.assertThrows(
                AgentRunException.class,
                () -> agent.runFull(INPUT, RunOptions.defaults())
        );

        assertEquals("callback failed", failure.getCause().getMessage());
        assertEquals(1, failure.partialResult().steps().size());
    }

    /**
     * 验证受管 Agent 接收父任务与 additional_args，并可附带运行摘要返回。
     */
    @Test
    void delegatesTaskToManagedAgentAndReturnsItsSummary() {
        ScriptedModel childModel = new ScriptedModel(List.of(
                FakeModel.message("Child answer.", List.of(
                        new ToolCall("child-final", "final_answer", FakeModel.args("answer", "sub-result"))
                ))
        ));
        ToolCallingAgent childAgent = new ToolCallingAgent(childModel, List.of());
        ManagedAgentTool managed = new ManagedAgentTool(
                "researcher",
                "Research a focused question.",
                childAgent,
                true
        );
        JsonNode managedSchema = managed.spec().inputSchema();
        assertEquals(1, managedSchema.path("required").size());
        assertEquals("task", managedSchema.path("required").get(0).asText());
        new JsonSchemaToolArgumentValidator().validate(
                managedSchema,
                MAPPER.createObjectNode().put("task", "Task without extra inputs")
        );
        new JsonSchemaToolArgumentValidator().validate(
                managedSchema,
                MAPPER.createObjectNode().put("task", "Task with null extra inputs").putNull("additional_args")
        );
        ObjectNode managedArguments = MAPPER.createObjectNode().put("task", "Find the sub-answer");
        managedArguments.set("additional_args", MAPPER.createObjectNode().put("project", "smolagents"));
        ScriptedModel parentModel = new ScriptedModel(List.of(
                FakeModel.message("Delegate the task.", List.of(
                        new ToolCall("call-researcher", "researcher", managedArguments)
                )),
                FakeModel.message("Use the delegated result.", List.of(
                        new ToolCall("call-final", "final_answer", FakeModel.args("answer", "parent-result"))
                ))
        ));
        ToolCallingAgent parentAgent = new ToolCallingAgent(parentModel, List.of(managed), 2, "system");

        var result = parentAgent.runFull(INPUT, RunOptions.defaults());

        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals("parent-result", result.output().plainText().orElseThrow());
        assertTrue(containsText(childModel.requests.getFirst().messages(), "Find the sub-answer"));
        assertTrue(containsText(childModel.requests.getFirst().messages(), "helpful agent named 'researcher'"));
        assertTrue(containsText(childModel.requests.getFirst().messages(), "final_answer WILL HAVE"));
        TaskStep childTask = assertInstanceOf(TaskStep.class, childAgent.memory().snapshot().getFirst());
        assertTrue(childTask.task().contains("### 1. Task outcome (short version):"));
        assertTrue(childTask.task().contains("### 2. Task outcome (extremely detailed version):"));
        assertTrue(childTask.task().contains("### 3. Additional context (if relevant):"));
        assertTrue(containsText(childModel.requests.getFirst().messages(), "project"));
        assertTrue(containsText(childModel.requests.getFirst().messages(), "smolagents"));
        ActionStep delegatedAction = assertInstanceOf(ActionStep.class, result.steps().get(1));
        assertEquals("sub-result", delegatedAction.observations().getFirst()
                .result().structuredContent().path("answer").asText());
        assertEquals(
                "Here is the final answer from your managed agent 'researcher':\nsub-result",
                delegatedAction.observations().getFirst().result().plainText().orElseThrow()
        );
        assertEquals("SUCCESS", delegatedAction.observations().getFirst()
                .result().structuredContent().path("status").asText());
    }

    /**
     * 验证规划按间隔运行，并将历史观察和计划带入后续动作请求。
     */
    @Test
    void createsAndUpdatesPlansAtConfiguredInterval() {
        PlanningModel model = new PlanningModel();
        ManagedAgentTool managed = new ManagedAgentTool(
                "researcher",
                "Research a focused question.",
                new ToolCallingAgent(new FakeModel(false), List.of())
        );
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of(new EchoTool(), managed), 3, "system", 2);

        var result = agent.runFull(INPUT, RunOptions.defaults());

        List<PlanningStep> plans = result.steps().stream()
                .filter(PlanningStep.class::isInstance)
                .map(PlanningStep.class::cast)
                .toList();
        assertEquals(RunStatus.SUCCESS, result.status());
        assertEquals(2, plans.size());
        assertEquals(1, plans.get(0).stepNumber());
        assertEquals(3, plans.get(1).stepNumber());
        assertTrue(containsText(model.planningRequests.getFirst().messages(), "You can also give tasks to team members."));
        assertTrue(containsText(
                model.planningRequests.getFirst().messages(),
                "Takes inputs: {'task': {'type': 'string', 'description': 'Long detailed description of the task.'}"
        ));
        assertTrue(plans.get(0).plan().contains("plan of action"));
        assertTrue(plans.get(1).plan().contains("new/updated plan"));
        assertEquals(1, plans.getFirst().modelInputMessages().size());
        assertEquals(MessageRole.USER, plans.getFirst().modelInputMessages().getFirst().role());
        assertEquals("<end_plan>", model.planningRequests.getFirst().stopSequences().getFirst());
        assertTrue(containsText(model.planningRequests.get(0).messages(), INPUT.task()));
        assertTrue(containsText(model.planningRequests.get(1).messages(), "7.2904"));
        assertEquals(MessageRole.SYSTEM, plans.get(1).modelInputMessages().getFirst().role());
        assertEquals(MessageRole.USER, plans.get(1).modelInputMessages().getLast().role());
        assertTrue(containsText(model.planningRequests.get(1).messages(), "Beware that you have 0 steps remaining"));
        assertTrue(plans.get(1).modelInputMessages().stream()
                .noneMatch(message -> containsText(List.of(message), "Here are the facts I know and the plan")));
        assertTrue(containsText(model.actionRequests.get(1).messages(), "facts collected"));
        assertTrue(containsText(model.actionRequests.get(2).messages(), "new/updated plan"));
        assertEquals(18, result.tokenUsage().totalTokens().orElseThrow());
    }

    /**
     * 验证续跑时的前序会话记忆会进入初始规划上下文。
     */
    @Test
    void includesInjectedConversationMemoryInPlanningRequest() {
        PlanningModel model = new PlanningModel();
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of(new EchoTool()), 2, "system", 1);
        agent.memory().append(new TaskStep(0, Instant.now(), "Previous user request"));

        agent.runFull(INPUT, new RunOptions(false, null, true, null, new CancellationToken()));

        PlanningStep firstPlan = assertInstanceOf(
                PlanningStep.class,
                agent.memory().snapshot().stream()
                        .filter(PlanningStep.class::isInstance)
                        .findFirst()
                        .orElseThrow()
        );
        List<ChatMessage> firstMessages = firstPlan.modelInputMessages();
        assertEquals(4, firstMessages.size());
        assertEquals(MessageRole.SYSTEM, firstMessages.getFirst().role());
        assertEquals(MessageRole.USER, firstMessages.getLast().role());
        assertTrue(containsText(firstMessages, "Previous user request"));
        assertTrue(containsText(firstMessages, INPUT.task()));
        assertTrue(containsText(firstMessages, "Beware that you have 1 steps remaining"));

        List<PlanningStep> plans = agent.memory().snapshot().stream()
                .filter(PlanningStep.class::isInstance)
                .map(PlanningStep.class::cast)
                .toList();
        assertEquals(2, plans.size());
        assertEquals(6, plans.get(1).modelInputMessages().size());
        assertTrue(containsText(plans.get(1).modelInputMessages(), "Previous user request"));
        assertTrue(containsText(plans.get(1).modelInputMessages(), "7.2904"));
        assertTrue(containsText(plans.get(1).modelInputMessages(), "Beware that you have 0 steps remaining"));
        assertTrue(containsText(model.planningRequests.getFirst().messages(), "Previous user request"));
        assertTrue(model.planningRequests.getFirst().messages().stream()
                .anyMatch(message -> message.role() == MessageRole.SYSTEM));
    }

    /**
     * 验证 additional arguments 按 Python dict repr 进入任务上下文。
     *
     * @throws Exception JSON fixture 解析失败时抛出
     */
    @Test
    void rendersAdditionalArgumentsAsPythonLiteralText() throws Exception {
        ScriptedModel model = new ScriptedModel(List.of(
                FakeModel.message("Finish the task.", List.of(
                        new ToolCall("call-final", "final_answer", FakeModel.args("answer", "done"))
                ))
        ));
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of(), 2, "system");
        Map<String, JsonNode> additionalArguments = Map.of(
                "values", MAPPER.readTree("[1,null,\"x\"]"),
                "quote", MAPPER.getNodeFactory().textNode("it's\nok"),
                "enabled", MAPPER.getNodeFactory().booleanNode(true)
        );

        agent.runFull(new AgentInput("read the context", additionalArguments), RunOptions.defaults());

        assertTrue(containsText(
                model.requests.getFirst().messages(),
                "You have been provided with these additional arguments, that you can access directly using the "
                        + "keys as variables:\n{'enabled': True, 'quote': 'it\\'s\\nok', 'values': [1, None, 'x']}."
        ));
    }

    /**
     * 验证调用方规划模板只渲染白名单变量并分别用于初始/更新消息角色。
     */
    @Test
    void usesCallerProvidedPlanningPromptTemplates() {
        PlanningModel model = new PlanningModel();
        PlanningPromptTemplates templates = new PlanningPromptTemplates(
                "expert task planner custom-initial task={{task}} tools={{tools}}",
                "expert task planner custom-update task={{task}}",
                "There are {{remaining_steps}} action steps remaining; task={{task}} tools={{tools}}"
        );
        ToolCallingAgent agent = new ToolCallingAgent(
                model,
                List.of(new EchoTool()),
                3,
                "system",
                2,
                List.of(),
                templates
        );

        var result = agent.runFull(INPUT, RunOptions.defaults());

        assertEquals(RunStatus.SUCCESS, result.status());
        assertTrue(containsText(model.planningRequests.getFirst().messages(), "custom-initial"));
        assertTrue(containsText(model.planningRequests.getFirst().messages(), "echo:"));
        assertTrue(containsText(model.planningRequests.getFirst().messages(), "Schema:"));
        assertEquals(MessageRole.USER, model.planningRequests.getFirst().messages().getFirst().role());
        assertTrue(containsText(model.planningRequests.get(1).messages(), "custom-update"));
        assertTrue(containsText(model.planningRequests.get(1).messages(), "There are 0 action steps remaining"));
        assertEquals(MessageRole.SYSTEM, model.planningRequests.get(1).messages().getFirst().role());
        assertEquals(MessageRole.USER, model.planningRequests.get(1).messages().getLast().role());
    }

    /**
     * 验证规划模板不能引用未声明变量或执行模板表达式。
     */
    @Test
    void rejectsUnknownPlanningPromptVariables() {
        PlanningPromptTemplates templates = new PlanningPromptTemplates(
                "task={{task}} {{java_expression}}",
                "task={{task}}",
                "remaining={{remaining_steps}}"
        );

        assertThrows(
                ConfigurationException.class,
                () -> templates.renderInitialPlan("task", List.of())
        );
    }

    /**
     * 验证 CodeAgent 与 ToolCallingAgent 默认规划模板使用各自 Python prompt 工具格式。
     */
    @Test
    void rendersPythonCompatiblePlanningDefaultsForEachAgentKind() {
        ToolSpec echo = new EchoTool().spec();
        List<ToolSpec> tools = List.of(echo, new FinalAnswerTool().spec());
        PlanningPromptTemplates codeTemplates = PlanningPromptTemplates.codeAgentDefaults();
        PlanningPromptTemplates toolCallingTemplates = PlanningPromptTemplates.toolCallingDefaults();

        String codePrompt = codeTemplates.renderInitialPlan("find a value", tools);
        String toolCallingPrompt = toolCallingTemplates.renderInitialPlan("find a value", tools);
        String codeUpdate = codeTemplates.renderUpdatePlanUser("find a value", 2, tools);
        String toolCallingUpdate = toolCallingTemplates.renderUpdatePlanUser("find a value", 2, tools);

        assertTrue(codePrompt.contains("### 1.1. Facts given in the task"));
        assertTrue(codePrompt.contains("```python\ndef echo(text: string) -> string:"), codePrompt);
        assertTrue(codePrompt.contains("def echo(text: string) -> string:"));
        assertTrue(codePrompt.contains("def final_answer(answer: any) -> any:"));
        assertTrue(codePrompt.contains("find a value"));
        assertTrue(toolCallingPrompt.contains("### 1.2. Facts to look up"));
        assertTrue(toolCallingPrompt.contains("echo: Return the input text."));
        assertTrue(toolCallingPrompt.contains("Takes inputs: {'text': {'type': 'string'"));
        assertTrue(toolCallingPrompt.contains("- final_answer: Provides a final answer to the given problem."));
        assertTrue(toolCallingPrompt.contains("final_answer: Provides a final answer to the given problem."));
        assertTrue(toolCallingPrompt.contains(
                "Takes inputs: {'answer': {'type': 'any', 'description': 'The final answer to the problem'}}"
        ));
        assertTrue(toolCallingPrompt.contains("Returns an output of type: any"));
        assertTrue(codeUpdate.contains("Beware that you have 2 steps remaining."));
        assertTrue(codeUpdate.contains("```python"));
        assertTrue(toolCallingUpdate.contains("Beware that you have 2 steps remaining."));
        assertTrue(toolCallingUpdate.contains("echo: Return the input text."));
    }

    /**
     * 验证事件流最终发布 RunCompleted，且正常关闭时不报告错误。
     *
     * @throws InterruptedException 等待运行事件时线程被中断
     */
    @Test
    void streamPublishesTerminalRunEvent() throws InterruptedException {
        ScriptedModel model = new ScriptedModel(List.of(
                FakeModel.message("Returning answer.", List.of(
                        new ToolCall("call-final", "final_answer", FakeModel.args("answer", "streamed"))
                ))
        ));
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of(new EchoTool()));
        CountDownLatch completed = new CountDownLatch(1);
        List<org.fzb.smolagents.event.AgentEvent> events = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        agent.stream(INPUT, RunOptions.defaults()).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(org.fzb.smolagents.event.AgentEvent event) {
                events.add(event);
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                completed.countDown();
            }

            @Override
            public void onComplete() {
                completed.countDown();
            }
        });

        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertNull(error.get());
        assertTrue(events.stream().anyMatch(org.fzb.smolagents.event.AgentEvent.RunCompleted.class::isInstance));
        assertTrue(events.stream()
                .filter(AgentEvent.ModelDelta.class::isInstance)
                .map(AgentEvent.ModelDelta.class::cast)
                .anyMatch(event -> event.delta() instanceof ModelStreamEvent.MessageDelta));
        assertTrue(events.stream()
                .filter(AgentEvent.ModelDelta.class::isInstance)
                .map(AgentEvent.ModelDelta.class::cast)
                .anyMatch(event -> event.delta() instanceof ModelStreamEvent.ToolCallDelta));
    }

    /**
     * 验证取消 Agent 事件订阅会取消正在等待增量的上游模型订阅。
     *
     * @throws InterruptedException 等待取消传播时线程被中断
     */
    @Test
    void cancellationPropagatesToModelStreamThatStopsProducingEvents() throws InterruptedException {
        CountDownLatch upstreamCancelled = new CountDownLatch(1);
        CountDownLatch deltaReceived = new CountDownLatch(1);
        AtomicReference<Flow.Subscription> agentSubscription = new AtomicReference<>();
        CancellationToken cancellation = new CancellationToken();
        Model model = new Model() {
            @Override
            public ChatMessage generate(GenerationRequest request) {
                throw new UnsupportedOperationException("streaming path expected");
            }

            @Override
            public Flow.Publisher<ModelStreamEvent> stream(GenerationRequest request) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    private boolean sent;

                    @Override
                    public void request(long n) {
                        if (!sent) {
                            sent = true;
                            subscriber.onNext(new ModelStreamEvent.MessageDelta("partial answer"));
                        }
                    }

                    @Override
                    public void cancel() {
                        upstreamCancelled.countDown();
                    }
                });
            }

            @Override
            public ModelCapabilities capabilities() {
                return new ModelCapabilities(true, false, false, true);
            }
        };
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of());

        agent.stream(INPUT, new RunOptions(false, null, false, null, cancellation))
                .subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        agentSubscription.set(subscription);
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(AgentEvent event) {
                        if (event instanceof AgentEvent.ModelDelta delta
                                && delta.delta() instanceof ModelStreamEvent.MessageDelta) {
                            agentSubscription.get().cancel();
                            deltaReceived.countDown();
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                    }

                    @Override
                    public void onComplete() {
                    }
                });

        assertTrue(deltaReceived.await(5, TimeUnit.SECONDS));
        assertTrue(upstreamCancelled.await(5, TimeUnit.SECONDS));
        assertTrue(cancellation.isCancelled());
    }

    /**
     * 验证模型流未产生完整消息时运行失败，而不会构造成功答案。
     *
     * @throws InterruptedException 等待运行事件时线程被中断
     */
    @Test
    void emptyModelStreamPublishesRunFailure() throws InterruptedException {
        Model model = new Model() {
            @Override
            public ChatMessage generate(GenerationRequest request) {
                throw new UnsupportedOperationException("streaming path expected");
            }

            @Override
            public Flow.Publisher<ModelStreamEvent> stream(GenerationRequest request) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                        subscriber.onComplete();
                    }

                    @Override
                    public void cancel() {
                    }
                });
            }

            @Override
            public ModelCapabilities capabilities() {
                return new ModelCapabilities(true, false, false, true);
            }
        };
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of());
        CountDownLatch completed = new CountDownLatch(1);
        List<AgentEvent> events = new ArrayList<>();

        agent.stream(INPUT, RunOptions.defaults()).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentEvent event) {
                events.add(event);
            }

            @Override
            public void onError(Throwable throwable) {
                completed.countDown();
            }

            @Override
            public void onComplete() {
                completed.countDown();
            }
        });

        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertTrue(events.stream().anyMatch(AgentEvent.RunFailed.class::isInstance));
        assertTrue(events.stream().noneMatch(AgentEvent.RunCompleted.class::isInstance));
    }

    /**
     * 验证动作上限后的兜底答复也会发布模型文本增量。
     *
     * @throws InterruptedException 等待运行事件时线程被中断
     */
    @Test
    void streamsFallbackAnswerAfterActionLimit() throws InterruptedException {
        ScriptedModel model = new ScriptedModel(List.of(
                FakeModel.message("Use echo.", List.of(
                        new ToolCall("call-echo", "echo", FakeModel.args("text", "partial"))
                )),
                FakeModel.message("Fallback answer.", List.of())
        ));
        ToolCallingAgent agent = new ToolCallingAgent(model, List.of(new EchoTool()), 1, "system");
        CountDownLatch completed = new CountDownLatch(1);
        List<AgentEvent> events = new ArrayList<>();

        agent.stream(INPUT, RunOptions.defaults()).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentEvent event) {
                events.add(event);
            }

            @Override
            public void onError(Throwable throwable) {
                completed.countDown();
            }

            @Override
            public void onComplete() {
                completed.countDown();
            }
        });

        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertTrue(events.stream()
                .filter(AgentEvent.ModelDelta.class::isInstance)
                .map(AgentEvent.ModelDelta.class::cast)
                .map(AgentEvent.ModelDelta::delta)
                .filter(ModelStreamEvent.MessageDelta.class::isInstance)
                .map(ModelStreamEvent.MessageDelta.class::cast)
                .anyMatch(delta -> delta.text().equals("Fallback answer.")));
        assertTrue(events.stream()
                .filter(AgentEvent.RunCompleted.class::isInstance)
                .map(AgentEvent.RunCompleted.class::cast)
                .anyMatch(event -> event.result().status() == RunStatus.MAX_STEPS_REACHED));
    }

    /**
     * 为 Agent 测试提供一个符合工具 schema 的文本回显工具。
     */
    private static final class EchoTool implements Tool {
        private int invocations;
        private final ToolSpec spec = new ToolSpec(
                "echo",
                "Return the input text.",
                schema(),
                true
        );

        /**
         * 返回回显工具的模型可见定义。
         *
         * @return 工具定义
         */
        @Override
        public ToolSpec spec() {
            return spec;
        }

        /**
         * 返回输入的 text 参数。
         *
         * @param arguments 已校验的调用参数
         * @param context 工具执行上下文
         * @return 回显结果
         */
        @Override
        public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
            invocations++;
            return ToolResult.text(arguments.get("text").asText());
        }

        /**
         * 构建仅接受 text 字符串字段的工具 schema。
         *
         * @return JSON Schema
         */
        private static JsonNode schema() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", MAPPER.createObjectNode()
                    .set("text", MAPPER.createObjectNode().put("type", "string")));
            schema.putArray("required").add("text");
            schema.put("additionalProperties", false);
            return schema;
        }
    }

    /**
     * 按顺序返回预设响应并保存模型请求，以验证错误重试上下文。
     */
    private static final class ScriptedModel implements Model {
        private final List<ChatMessage> responses;
        private final List<GenerationRequest> requests = new ArrayList<>();

        /**
         * 创建按动作顺序返回响应的模型替身。
         *
         * @param responses assistant 响应
         */
        private ScriptedModel(List<ChatMessage> responses) {
            this.responses = List.copyOf(responses);
        }

        @Override
        public ChatMessage generate(GenerationRequest request) {
            requests.add(request);
            int index = requests.size() - 1;
            if (index >= responses.size()) {
                throw new IllegalStateException("No scripted model response remains");
            }
            return responses.get(index);
        }

        @Override
        public Flow.Publisher<ModelStreamEvent> stream(GenerationRequest request) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean emitted;

                @Override
                public void request(long n) {
                    if (n <= 0 || emitted) {
                        return;
                    }
                    emitted = true;
                    ChatMessage response = generate(request);
                    response.content().stream()
                            .filter(ContentPart.Text.class::isInstance)
                            .map(ContentPart.Text.class::cast)
                            .forEach(part -> subscriber.onNext(new ModelStreamEvent.MessageDelta(part.text())));
                    for (int index = 0; index < response.toolCalls().size(); index++) {
                        ToolCall call = response.toolCalls().get(index);
                        subscriber.onNext(new ModelStreamEvent.ToolCallDelta(
                                index,
                                call.id(),
                                call.name(),
                                call.arguments().toString()
                        ));
                    }
                    subscriber.onNext(new ModelStreamEvent.Completed(response));
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    emitted = true;
                }
            });
        }

        @Override
        public ModelCapabilities capabilities() {
            return new ModelCapabilities(true, false, false, true);
        }
    }

    /**
     * 根据测试场景返回预设工具调用或步数上限答复的模型替身。
     */
    private static final class FakeModel implements Model {
        private final List<GenerationRequest> requests = new ArrayList<>();
        private final boolean maxStepsOnly;

        /**
         * 创建模型替身并选择是否持续请求 echo 工具。
         *
         * @param maxStepsOnly 是否忽略最终工具调用以触发步数上限
         */
        private FakeModel(boolean maxStepsOnly) {
            this.maxStepsOnly = maxStepsOnly;
        }

        /**
         * 根据当前请求次序返回工具调用或最终答复。
         *
         * @param request Agent 生成请求
         * @return 预设 assistant 响应
         */
        @Override
        public ChatMessage generate(GenerationRequest request) {
            requests.add(request);
            if (request.tools().isEmpty()) {
                return message("Best answer from partial work.", List.of());
            }
            if (maxStepsOnly || requests.size() % 2 == 1) {
                return message(
                        "Using echo.",
                        List.of(new ToolCall("call-echo", "echo", args("text", "7.2904")))
                );
            }
            return message(
                    "Returning the answer.",
                    List.of(new ToolCall("call-final", "final_answer", args("answer", "7.2904")))
            );
        }

        /**
         * 以单条完成事件模拟模型流式响应。
         *
         * @param request Agent 生成请求
         * @return 完成事件发布器
         */
        @Override
        public Flow.Publisher<ModelStreamEvent> stream(GenerationRequest request) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean emitted;

                @Override
                public void request(long n) {
                    if (n <= 0 || emitted) {
                        return;
                    }
                    emitted = true;
                    subscriber.onNext(new ModelStreamEvent.Completed(generate(request)));
                    subscriber.onComplete();
                }

                /**
                 * 声明模型支持工具调用。
                 *
                 * @return 测试模型能力
                 */
                @Override
                public void cancel() {
                    emitted = true;
                }
            });
        }

        @Override
        public ModelCapabilities capabilities() {
            return new ModelCapabilities(true, false, false, true);
        }

        /**
         * 构造带指定文本、工具调用和固定用量的 assistant 响应。
         *
         * @param text 响应文本
         * @param calls 工具调用列表
         * @return assistant 消息
         */
        private static ChatMessage message(String text, List<ToolCall> calls) {
            return new ChatMessage(
                    MessageRole.ASSISTANT,
                    List.of(new ContentPart.Text(text)),
                    calls,
                    null,
                    null,
                    TokenUsage.of(3, 1)
            );
        }

        /**
         * 创建只有一个字符串字段的 JSON 参数对象。
         *
         * @param name 字段名
         * @param value 字段值
         * @return JSON 参数对象
         */
        private static JsonNode args(String name, String value) {
            return MAPPER.createObjectNode().put(name, value);
        }
    }

    /**
     * 分别记录计划与动作请求并返回确定性工具调用的模型替身。
     */
    private static final class PlanningModel implements Model {
        private final List<GenerationRequest> planningRequests = new ArrayList<>();
        private final List<GenerationRequest> actionRequests = new ArrayList<>();

        @Override
        public ChatMessage generate(GenerationRequest request) {
            if (request.tools().isEmpty()
                    && (containsText(request.messages(), "expert task planner")
                    || containsText(request.messages(), "world expert at analyzing")
                    || containsText(request.messages(), "action steps remaining")
                    || containsText(request.messages(), "Beware that you have"))) {
                planningRequests.add(request);
                return new ChatMessage(
                        MessageRole.ASSISTANT,
                        List.of(new ContentPart.Text("facts collected; continue with the next step")),
                        List.of(),
                        null,
                        null,
                        TokenUsage.of(2, 1)
                );
            }
            if (request.tools().isEmpty()) {
                return FakeModel.message("Best answer from partial work.", List.of());
            }
            actionRequests.add(request);
            List<ToolCall> calls = actionRequests.size() < 3
                    ? List.of(new ToolCall("call-echo", "echo", FakeModel.args("text", "7.2904")))
                    : List.of(new ToolCall("call-final", "final_answer", FakeModel.args("answer", "7.2904")));
            return FakeModel.message("Proceeding with the plan.", calls);
        }

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

        @Override
        public ModelCapabilities capabilities() {
            return new ModelCapabilities(true, false, false, true);
        }
    }

    /**
     * 判断指定文本是否出现在任一消息的文本内容中。
     *
     * @param messages 待检查消息
     * @param expected 预期子串
     * @return 找到子串时为 true
     */
    private static boolean containsText(List<ChatMessage> messages, String expected) {
        return messages.stream()
                .flatMap(message -> message.content().stream())
                .filter(ContentPart.Text.class::isInstance)
                .map(ContentPart.Text.class::cast)
                .anyMatch(part -> part.text().contains(expected));
    }
}
