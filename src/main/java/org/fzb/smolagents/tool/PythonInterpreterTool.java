package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.executor.CodeExecutor;
import org.fzb.smolagents.executor.ExecutorFailure;
import org.fzb.smolagents.executor.ExecutorRequest;
import org.fzb.smolagents.executor.ExecutorResult;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 将 Python 代码片段委托给调用方配置的 {@link CodeExecutor} 并格式化执行结果。
 *
 * <p>每次调用前后都会重置执行器命名空间，使变量不会跨工具调用残留。
 * 执行器由调用方提供并管理生命周期；本工具不会关闭它。默认本地 CPython
 * 执行器以当前 OS 用户权限运行，不是安全沙箱。</p>
 */
public final class PythonInterpreterTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final CodeExecutor executor;
    private final Duration timeout;
    private final ToolSpec spec = new ToolSpec(
            "python_interpreter",
            "Evaluate a Python code snippet and return its stdout and result.",
            createSchema(),
            true
    );

    /**
     * 使用指定执行器和默认超时创建 Python 解释器工具。
     *
     * @param executor 调用方管理的 Python 代码执行器
     */
    public PythonInterpreterTool(CodeExecutor executor) {
        this(executor, DEFAULT_TIMEOUT);
    }

    /**
     * 使用指定执行器和单次执行时限创建 Python 解释器工具。
     *
     * @param executor 调用方管理的 Python 代码执行器
     * @param timeout 单次 Python 代码执行时限
     */
    public PythonInterpreterTool(CodeExecutor executor, Duration timeout) {
        this.executor = Objects.requireNonNull(executor, "executor");
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeout = timeout;
    }

    /**
     * 返回 python_interpreter 工具的参数定义。
     *
     * @return 要求 code 字符串的工具 schema
     */
    @Override
    public ToolSpec spec() {
        return spec;
    }

    /**
     * 在独立命名空间中执行代码并返回 stdout、最终表达式或错误。
     *
     * @param arguments 已通过 schema 校验的 code 参数
     * @param context 当前工具调用上下文
     * @return 包含 stdout 和执行结果的文本工具响应
     * @throws ToolExecutionException 代码执行失败或执行器报告错误时抛出
     */
    @Override
    public synchronized ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
        Objects.requireNonNull(context, "context");
        context.cancellation().throwIfCancelled();
        executor.reset();

        RuntimeException executionFailure = null;
        try {
            ExecutorResult result = Objects.requireNonNull(
                    executor.execute(new ExecutorRequest(
                            arguments.path("code").asText(),
                            Map.<String, AgentValue>of(),
                            Map.of(),
                            timeout,
                            context.cancellation(),
                            context.runId()
                    )),
                    "code executor result"
            );
            if (!result.succeeded()) {
                throw formatFailure(result.failure());
            }
            return ToolResult.text(formatResult(result));
        } catch (RuntimeException error) {
            executionFailure = error;
            throw error;
        } finally {
            try {
                executor.reset();
            } catch (RuntimeException resetFailure) {
                if (executionFailure != null) {
                    executionFailure.addSuppressed(resetFailure);
                } else {
                    throw resetFailure;
                }
            }
        }
    }

    /**
     * 将执行器结果格式化为与 Python 默认解释器相近的文本布局。
     *
     * @param result 成功执行结果
     * @return 包含 stdout 和最终表达式值的文本
     */
    private static String formatResult(ExecutorResult result) {
        String value = formatValue(result.value());
        return "Stdout:\n" + result.stdout() + "\nOutput: " + value;
    }

    /**
     * 将文本或结构化执行值转换为可读输出。
     *
     * @param value Python 代码的最终值
     * @return 文本、JSON 或空字符串
     */
    private static String formatValue(AgentValue value) {
        if (value == null) {
            return "";
        }
        return value.plainText().orElseGet(() -> {
            JsonNode structured = value.structuredContent();
            return structured == null ? "" : structured.toString();
        });
    }

    /**
     * 将执行器的结构化错误转换为可诊断的工具执行异常。
     *
     * @param failure 执行器失败详情
     * @return 包含错误类型及可选行号的工具错误
     */
    private static ToolExecutionException formatFailure(ExecutorFailure failure) {
        Objects.requireNonNull(failure, "failed result must include failure details");
        String location = failure.lineNumber() == null ? "" : " at line " + failure.lineNumber();
        return new ToolExecutionException(
                "Python interpreter failed with " + failure.type() + location + ": " + failure.message()
        );
    }

    /**
     * 构造仅接受 code 字段的输入 schema。
     *
     * @return JSON Schema object
     */
    private static JsonNode createSchema() {
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        var code = MAPPER.createObjectNode()
                .put("type", "string")
                .put("description", "The Python code snippet to evaluate.");
        schema.set("properties", MAPPER.createObjectNode().set("code", code));
        schema.putArray("required").add("code");
        schema.put("additionalProperties", false);
        return schema;
    }
}
