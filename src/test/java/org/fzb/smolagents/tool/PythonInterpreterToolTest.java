package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.executor.CodeExecutor;
import org.fzb.smolagents.executor.ExecutorFailure;
import org.fzb.smolagents.executor.ExecutorRequest;
import org.fzb.smolagents.executor.ExecutorResult;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Python 解释器工具委托执行、隔离调用状态并传播错误。
 */
class PythonInterpreterToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证工具返回 stdout 和最终值，且每次调用前后重置执行器。
     *
     * @throws Exception 测试 JSON 参数解析失败时抛出
     */
    @Test
    void formatsExecutionOutputAndResetsExecutorAroundCall() throws Exception {
        AtomicInteger resets = new AtomicInteger();
        AtomicReference<ExecutorRequest> request = new AtomicReference<>();
        CodeExecutor executor = new StubExecutor(
                resets,
                request,
                new ExecutorResult("printed\n", AgentValue.text("42"), Map.of(), null),
                null,
                null
        );
        UUID runId = UUID.randomUUID();
        PythonInterpreterTool tool = new PythonInterpreterTool(executor, Duration.ofSeconds(8));

        ToolResult result = tool.execute(
                MAPPER.readTree("{\"code\":\"print('printed')\\n6 * 7\"}"),
                context("python-call", runId, new CancellationToken())
        );

        ContentPart.Text output = assertInstanceOf(ContentPart.Text.class, result.content().getFirst());
        assertEquals("Stdout:\nprinted\n\nOutput: 42", output.text());
        assertEquals(2, resets.get());
        assertEquals("print('printed')\n6 * 7", request.get().code());
        assertEquals(Duration.ofSeconds(8), request.get().timeout());
        assertEquals(runId, request.get().runId());
    }

    /**
     * 验证执行器失败被转换为包含错误类型和行号的工具异常。
     *
     * @throws Exception 测试 JSON 参数解析失败时抛出
     */
    @Test
    void reportsExecutorFailureAndStillResetsNamespace() throws Exception {
        AtomicInteger resets = new AtomicInteger();
        CodeExecutor executor = new StubExecutor(
                resets,
                new AtomicReference<>(),
                new ExecutorResult("", null, Map.of(), new ExecutorFailure("SyntaxError", "invalid syntax", 3)),
                null,
                null
        );

        ToolExecutionException failure = assertThrows(
                ToolExecutionException.class,
                () -> new PythonInterpreterTool(executor).execute(
                        MAPPER.readTree("{\"code\":\"broken\"}"),
                        context("python-failure", UUID.randomUUID(), new CancellationToken())
                )
        );

        assertTrue(failure.getMessage().contains("SyntaxError at line 3"));
        assertEquals(2, resets.get());
    }

    /**
     * 验证清理失败作为 suppressed 异常附加，不覆盖代码执行故障。
     *
     * @throws Exception 测试 JSON 参数解析失败时抛出
     */
    @Test
    void preservesExecutionFailureWhenNamespaceCleanupAlsoFails() throws Exception {
        RuntimeException original = new IllegalStateException("executor failed");
        CodeExecutor executor = new StubExecutor(
                new AtomicInteger(),
                new AtomicReference<>(),
                null,
                original,
                new IllegalStateException("reset failed")
        );
        ToolRegistry registry = new ToolRegistry();
        registry.register(new PythonInterpreterTool(executor));

        ToolExecutionException failure = assertThrows(
                ToolExecutionException.class,
                () -> registry.invoke(
                        new ToolCall(
                                "python-double-failure",
                                "python_interpreter",
                                MAPPER.readTree("{\"code\":\"broken\"}")
                        ),
                        context("python-double-failure", UUID.randomUUID(), new CancellationToken())
                )
        );

        assertSame(original, failure.getCause());
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertTrue(failure.getCause().getSuppressed()[0].getMessage().contains("reset failed"));
    }

    /**
     * 为一次 Python 工具调用构造运行上下文。
     *
     * @param callId 工具调用标识
     * @param runId Agent 运行标识
     * @param cancellation 运行取消令牌
     * @return 工具执行上下文
     */
    private static ToolExecutionContext context(String callId, UUID runId, CancellationToken cancellation) {
        return new ToolExecutionContext(runId, callId, cancellation);
    }

    /**
     * 使用预设执行结果记录 Python 解释器对执行器的调用。
     */
    private static final class StubExecutor implements CodeExecutor {
        private final AtomicInteger resets;
        private final AtomicReference<ExecutorRequest> request;
        private final ExecutorResult result;
        private final RuntimeException executeFailure;
        private final RuntimeException resetFailure;

        /**
         * 创建可配置成功/失败路径的轻量执行器。
         *
         * @param resets reset 调用计数
         * @param request 最近的代码执行请求
         * @param result 预设执行结果
         * @param executeFailure 可选执行异常
         * @param resetFailure 可选清理异常
         */
        private StubExecutor(
                AtomicInteger resets,
                AtomicReference<ExecutorRequest> request,
                ExecutorResult result,
                RuntimeException executeFailure,
                RuntimeException resetFailure
        ) {
            this.resets = resets;
            this.request = request;
            this.result = result;
            this.executeFailure = executeFailure;
            this.resetFailure = resetFailure;
        }

        @Override
        public ExecutorResult execute(ExecutorRequest executorRequest) {
            request.set(executorRequest);
            if (executeFailure != null) {
                throw executeFailure;
            }
            return result;
        }

        @Override
        public void reset() {
            if (resets.incrementAndGet() == 2 && resetFailure != null) {
                throw resetFailure;
            }
        }

        @Override
        public void close() {
        }
    }
}
