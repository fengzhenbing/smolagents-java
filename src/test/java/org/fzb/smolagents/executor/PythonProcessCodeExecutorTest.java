package org.fzb.smolagents.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ExecutorException;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.ToolCall;
import org.fzb.smolagents.tool.PythonInterpreterTool;
import org.fzb.smolagents.tool.Tool;
import org.fzb.smolagents.tool.ToolExecutionContext;
import org.fzb.smolagents.tool.ToolRegistry;
import org.fzb.smolagents.tool.ToolResult;
import org.fzb.smolagents.tool.ToolSpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证持久 CPython 执行器的状态、输出、工具桥接、错误和取消边界。
 */
class PythonProcessCodeExecutorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PYTHON = System.getProperty(
            "smolagents.python",
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? "python"
                    : "python3"
    );

    /**
     * Python 未安装时跳过依赖外部解释器的执行器测试。
     */
    @BeforeAll
    static void requirePythonRuntime() {
        Assumptions.assumeTrue(isPythonAvailable(), "CPython is required for process executor tests");
    }

    /**
     * 验证变量和函数定义在多个动作之间保持，且捕获 stdout 与表达式结果。
     */
    @Test
    void preservesNamespaceAndReturnsLogsAndLastExpression() {
        try (PythonProcessCodeExecutor executor = new PythonProcessCodeExecutor(PYTHON)) {
            ExecutorResult first = executor.execute(request("value = 40\nprint('answer', value)\nvalue", Map.of()));
            ExecutorResult second = executor.execute(request("value + 2", Map.of()));

            assertTrue(first.succeeded());
            assertEquals("answer 40\n", first.stdout());
            assertEquals(40, first.value().structuredContent().asInt());
            assertEquals(42, second.value().structuredContent().asInt());
            assertEquals(40, first.updatedVariables().get("value").structuredContent().asInt());
        }
    }

    /**
     * 验证 final_answer 保留结构化值并终止当前代码动作。
     */
    @Test
    void finalAnswerPreservesStructuredValueAndTerminationSignal() {
        try (PythonProcessCodeExecutor executor = new PythonProcessCodeExecutor(PYTHON)) {
            ExecutorResult result = executor.execute(
                    request("final_answer({'answer': 42})", Map.of())
            );

            assertTrue(result.succeeded());
            assertTrue(result.finalAnswer());
            assertEquals(42, result.value().structuredContent().path("answer").asInt());
        }
    }

    /**
     * 验证 Python 函数代理可以调用 Java 工具并接收 JSON 结果。
     */
    @Test
    void bridgesPythonToolCallsToJavaTools() {
        Tool add = new AddTool();
        try (PythonProcessCodeExecutor executor = new PythonProcessCodeExecutor(PYTHON)) {
            ExecutorResult result = executor.execute(request("add(a=3, b=5)", Map.of("add", add)));

            assertTrue(result.succeeded());
            assertEquals(8, result.value().structuredContent().asInt());
        }
    }

    /**
     * 验证后续动作未提供的工具不会残留在 Python 全局命名空间。
     */
    @Test
    void removesToolsThatAreAbsentFromTheNextRequest() {
        try (PythonProcessCodeExecutor executor = new PythonProcessCodeExecutor(PYTHON)) {
            assertTrue(executor.execute(request("add(a=3, b=5)", Map.of("add", new AddTool()))).succeeded());

            ExecutorResult next = executor.execute(request("add(a=3, b=5)", Map.of()));
            assertFalse(next.succeeded());
            assertEquals("NameError", next.failure().type());
        }
    }

    /**
     * 验证工具桥接将执行请求的运行标识和取消令牌传递给 Java 工具。
     */
    @Test
    void forwardsRunIdAndCancellationToBridgedTool() {
        UUID runId = UUID.randomUUID();
        CancellationToken cancellation = new CancellationToken();
        AtomicReference<ToolExecutionContext> observed = new AtomicReference<>();
        Tool tool = new AddTool() {
            @Override
            public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
                observed.set(context);
                return super.execute(arguments, context);
            }
        };
        try (PythonProcessCodeExecutor executor = new PythonProcessCodeExecutor(PYTHON)) {
            ExecutorResult result = executor.execute(new ExecutorRequest(
                    "add(a=3, b=5)",
                    Map.of(),
                    Map.of("add", tool),
                    Duration.ofSeconds(5),
                    cancellation,
                    runId
            ));

            assertTrue(result.succeeded());
            assertEquals(runId, observed.get().runId());
            assertTrue(observed.get().callId().startsWith("python-"));
            assertSame(cancellation, observed.get().cancellation());
        }
    }

    /**
     * 验证保留字和无效标识符不能作为 Python 初始变量名。
     */
    @Test
    void rejectsReservedAndInvalidVariableNames() {
        try (PythonProcessCodeExecutor executor = new PythonProcessCodeExecutor(PYTHON)) {
            assertThrows(
                    ConfigurationException.class,
                    () -> executor.execute(requestWithVariables(
                            "1",
                            Map.of("for", AgentValue.text("reserved"))
                    ))
            );
            assertThrows(
                    ConfigurationException.class,
                    () -> executor.execute(requestWithVariables(
                            "1",
                            Map.of("not-valid", AgentValue.text("invalid"))
                    ))
            );
        }
    }

    /**
     * 验证无效 worker 协议会终止进程，后续执行能使用全新 worker 恢复。
     */
    @Test
    void protocolFailureStopsWorkerAndAllowsRecovery() {
        try (PythonProcessCodeExecutor executor = new PythonProcessCodeExecutor(
                PYTHON, Duration.ofSeconds(5), 1000, List.of()
        )) {
            assertThrows(
                    ExecutorException.class,
                    () -> executor.execute(request(
                            "print.__self__.__import__('os')._exit(0)",
                            Map.of()
                    ))
            );

            ExecutorResult result = executor.execute(request("6 * 7", Map.of()));
            assertTrue(result.succeeded());
            assertEquals(42, result.value().structuredContent().asInt());
        }
    }

    /**
     * 验证不在授权清单中的 import 返回明确执行失败，而非空成功结果。
     */
    @Test
    void reportsUnauthorizedImportsAsExecutionFailure() {
        try (PythonProcessCodeExecutor executor = new PythonProcessCodeExecutor(
                PYTHON, Duration.ofSeconds(5), 1000, List.of()
        )) {
            ExecutorResult result = executor.execute(request("import os\n1", Map.of()));

            assertFalse(result.succeeded());
            assertEquals("ImportError", result.failure().type());
            assertEquals(1, result.failure().lineNumber());
            assertTrue(result.failure().message().contains("not allowed"));
        }
    }

    /**
     * 验证执行超时会终止失控 worker，并允许后续请求启动新进程。
     */
    @Test
    void timeoutStopsWorkerAndAllowsNextRequest() {
        try (PythonProcessCodeExecutor executor = new PythonProcessCodeExecutor(
                PYTHON, Duration.ofSeconds(5), 1000, List.of()
        )) {
            assertThrows(
                    ExecutorException.class,
                    () -> executor.execute(new ExecutorRequest(
                            "while True:\n    pass",
                            Map.of(),
                            Map.of(),
                            Duration.ofMillis(500),
                            new CancellationToken()
                    ))
            );
            ExecutorResult next = executor.execute(request("6 * 7", Map.of()));

            assertTrue(next.succeeded());
            assertEquals(42, next.value().structuredContent().asInt());
        }
    }

    /**
     * 验证 reset 清空 Python 命名空间但不关闭执行器。
     */
    @Test
    void resetClearsPersistentNamespace() {
        try (PythonProcessCodeExecutor executor = new PythonProcessCodeExecutor(PYTHON)) {
            executor.execute(request("value = 9", Map.of()));
            executor.reset();
            ExecutorResult result = executor.execute(request("value + 1", Map.of()));

            assertFalse(result.succeeded());
            assertEquals("NameError", result.failure().type());
        }
    }

    /**
     * 验证 PythonInterpreterTool 复用真实 worker 执行代码并隔离相邻工具调用状态。
     *
     * @throws Exception 测试工具参数 JSON 解析失败时抛出
     */
    @Test
    void pythonInterpreterToolExecutesCodeWithoutRetainingVariables() throws Exception {
        try (PythonProcessCodeExecutor executor = new PythonProcessCodeExecutor(PYTHON)) {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new PythonInterpreterTool(executor));

            ToolResult result = registry.invoke(
                    new ToolCall("interpreter-call-1", "python_interpreter", MAPPER.readTree(
                            "{\"code\":\"print(6 * 7)\\n6 * 7\"}"
                    )),
                    new ToolExecutionContext(
                            UUID.randomUUID(),
                            "interpreter-call-1",
                            new CancellationToken()
                    )
            );
            ContentPart.Text output = assertInstanceOf(ContentPart.Text.class, result.content().getFirst());
            assertEquals("Stdout:\n42\n\nOutput: 42", output.text());

            assertThrows(
                    ToolExecutionException.class,
                    () -> registry.invoke(
                            new ToolCall("interpreter-call-2", "python_interpreter", MAPPER.readTree(
                                    "{\"code\":\"interpreter_value\"}"
                            )),
                            new ToolExecutionContext(
                                    UUID.randomUUID(),
                                    "interpreter-call-2",
                                    new CancellationToken()
                            )
                    )
            );
        }
    }

    /**
     * 验证附加资源清理仅在 worker 启动后运行，并在关闭时恰好执行一次。
     */
    @Test
    void runsProcessStopActionOnlyAfterWorkerWasStarted() {
        AtomicInteger cleanupCount = new AtomicInteger();
        PythonProcessCodeExecutor executor = new PythonProcessCodeExecutor(
                PYTHON,
                Duration.ofSeconds(5),
                1000,
                List.of(),
                (executable, script) -> List.of(executable, "-u", script.toString()),
                cleanupCount::incrementAndGet
        );

        assertEquals(0, cleanupCount.get());
        assertTrue(executor.execute(request("6 * 7", Map.of())).succeeded());
        executor.close();
        executor.close();

        assertEquals(1, cleanupCount.get());
    }

    /**
     * 验证 worker 关闭时的资源清理失败以明确执行器异常报告。
     */
    @Test
    void reportsProcessStopActionFailureOnClose() {
        PythonProcessCodeExecutor executor = new PythonProcessCodeExecutor(
                PYTHON,
                Duration.ofSeconds(5),
                1000,
                List.of(),
                (executable, script) -> List.of(executable, "-u", script.toString()),
                () -> {
                    throw new IllegalStateException("cleanup failed");
                }
        );
        assertTrue(executor.execute(request("6 * 7", Map.of())).succeeded());

        ExecutorException failure = assertThrows(ExecutorException.class, executor::close);

        assertTrue(failure.getCause().getMessage().contains("Failed to stop Python worker resources"));
        assertTrue(failure.getCause().getCause().getMessage().contains("cleanup failed"));
    }

    /**
     * 构造使用新取消令牌且无初始变量的 Python 执行请求。
     *
     * @param code 待执行 Python 代码
     * @param tools 可供 Python 调用的 Java 工具
     * @return 一次执行请求
     */
    private static ExecutorRequest request(String code, Map<String, Tool> tools) {
        return new ExecutorRequest(code, Map.of(), tools, Duration.ofSeconds(5), new CancellationToken());
    }

    /**
     * 构造带指定初始变量的 Python 执行请求。
     *
     * @param code 待执行 Python 代码
     * @param variables 执行前注入的变量
     * @return 一次执行请求
     */
    private static ExecutorRequest requestWithVariables(String code, Map<String, AgentValue> variables) {
        return new ExecutorRequest(code, variables, Map.of(), Duration.ofSeconds(5), new CancellationToken());
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
     * 为工具桥接测试提供确定性的整数加法工具。
     */
    private static class AddTool implements Tool {
        private final ToolSpec spec = new ToolSpec(
                "add",
                "Add two integers.",
                createSchema(),
                true
        );

        /**
         * 返回加法工具的名称和参数 schema。
         *
         * @return 工具定义
         */
        @Override
        public ToolSpec spec() {
            return spec;
        }

        /**
         * 执行两个整数的加法。
         *
         * @param arguments 已校验的工具参数
         * @param context 工具调用上下文
         * @return 和的 JSON 结果
         */
        @Override
        public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
            int sum = arguments.path("a").asInt() + arguments.path("b").asInt();
            return new ToolResult(
                    List.of(new ContentPart.Json(MAPPER.getNodeFactory().numberNode(sum))),
                    MAPPER.getNodeFactory().numberNode(sum)
            );
        }

        /**
         * 创建要求两个整数参数的 JSON Schema。
         *
         * @return 工具输入 schema
         */
        private static JsonNode createSchema() {
            var properties = MAPPER.createObjectNode();
            properties.set("a", MAPPER.createObjectNode().put("type", "integer"));
            properties.set("b", MAPPER.createObjectNode().put("type", "integer"));
            var schema = MAPPER.createObjectNode().put("type", "object");
            schema.set("properties", properties);
            schema.putArray("required").add("a").add("b");
            schema.put("additionalProperties", false);
            return schema;
        }
    }
}
