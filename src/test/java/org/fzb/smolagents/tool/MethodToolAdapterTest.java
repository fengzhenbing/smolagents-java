package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.core.ToolValidationException;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Java 注解方法适配为 JSON Schema tool 的参数、调用和返回契约。
 */
public class MethodToolAdapterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证 schema discovery 与已注解 Java 方法的数字参数调用。
     */
    @Test
    void discoversAndInvokesAnnotatedMethods() {
        MathTools target = new MathTools();
        List<MethodToolAdapter> tools = MethodToolAdapter.discover(target);

        assertEquals(List.of("add", "describe"), tools.stream().map(tool -> tool.spec().name()).toList());
        assertEquals("integer", tools.getFirst().spec().outputType());
        assertEquals("string", tools.getLast().spec().outputType());
        ToolRegistry registry = new ToolRegistry();
        tools.forEach(registry::register);
        var result = registry.invoke(
                new ToolCall("add-call", "add", MAPPER.createObjectNode().put("left", 12).put("right", 30)),
                context("add-call")
        );

        assertEquals(42, result.structuredContent().asInt());
        assertInstanceOf(ContentPart.Json.class, result.content().getFirst());
    }

    /**
     * 验证缺省的 JSON 参数会使用 annotation 默认值，显式传入值则优先。
     */
    @Test
    void appliesDeclaredDefaultWhenArgumentIsOmitted() {
        MethodToolAdapter tool = new MethodToolAdapter(new DefaultTools(), "withDefault");
        var defaultSchema = tool.spec().inputSchema().path("properties").path("enabled").path("default");

        assertTrue(defaultSchema.isBoolean());
        assertEquals(false, defaultSchema.asBoolean());
        assertEquals(false, tool.execute(MAPPER.createObjectNode(), context("default-omitted"))
                .structuredContent().asBoolean());
        assertEquals(true, tool.execute(
                MAPPER.createObjectNode().put("enabled", true),
                context("default-explicit")
        ).structuredContent().asBoolean());
    }

    /**
     * 验证 annotation 默认 JSON 无效或与参数类型不符时会在装配阶段失败。
     */
    @Test
    void rejectsInvalidDeclaredDefault() {
        assertThrows(
                ConfigurationException.class,
                () -> new MethodToolAdapter(new InvalidDefaultTools(), "invalidDefault")
        );
    }

    /**
     * 验证可选且非 nullable 的参数必须声明可实际传入的默认值。
     */
    @Test
    void rejectsOptionalNonnullableParameterWithoutDefault() {
        assertThrows(
                ConfigurationException.class,
                () -> new MethodToolAdapter(new OptionalWithoutDefaultTools(), "optional")
        );
    }

    /**
     * 验证 optional nullable 参数映射为可选 JSON union 类型。
     */
    @Test
    void supportsOptionalNullableArguments() {
        MathTools target = new MathTools();
        MethodToolAdapter tool = new MethodToolAdapter(target, "describe");
        var schema = tool.spec().inputSchema();

        new JsonSchemaToolArgumentValidator().validate(schema, MAPPER.createObjectNode());
        assertTrue(schema.path("properties").path("prefix").path("type").isArray());
    }

    /**
     * 验证缺少必填参数在调用方法前被 schema 拒绝。
     */
    @Test
    void validatesRequiredArgumentsBeforeInvocation() {
        MathTools target = new MathTools();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new MethodToolAdapter(target, "add"));

        assertThrows(
                ToolValidationException.class,
                () -> registry.invoke(
                        new ToolCall("missing-arg", "add", MAPPER.createObjectNode().put("left", 1)),
                        context("missing-arg")
                )
        );
        assertEquals(0, target.invocations);
    }

    /**
     * 验证不支持的签名和任意 Java POJO 返回值在配置/执行时显式失败。
     */
    @Test
    void rejectsUnsupportedSignaturesAndUnsafeReturnObjects() {
        assertThrows(
                ConfigurationException.class,
                () -> new MethodToolAdapter(new UnsupportedTools(), "unsupported")
        );

        MethodToolAdapter unsafe = new MethodToolAdapter(new UnsafeTools(), "unsafeResult");
        ToolExecutionException failure = assertThrows(
                ToolExecutionException.class,
                () -> unsafe.execute(MAPPER.createObjectNode(), context("unsafe-result"))
        );
        assertTrue(failure.getMessage().contains("cannot be safely serialized"));
    }

    /**
     * 验证已注解方法抛出的业务异常被保留为执行错误。
     */
    @Test
    void preservesTargetMethodFailure() {
        MethodToolAdapter tool = new MethodToolAdapter(new FailingTools(), "fail");
        ToolExecutionException failure = assertThrows(
                ToolExecutionException.class,
                () -> tool.execute(MAPPER.createObjectNode(), context("target-fail"))
        );
        assertTrue(failure.getMessage().contains("tool implementation failed"));
    }

    /**
     * 创建独立工具执行上下文。
     *
     * @param callId 工具调用标识
     * @return 执行上下文
     */
    private static ToolExecutionContext context(String callId) {
        return new ToolExecutionContext(UUID.randomUUID(), callId, new CancellationToken());
    }

    /**
     * 提供带有原语、nullable 和失败方法的公开测试工具。
     */
    public static final class MathTools {
        private int invocations;

        /**
         * 创建测试工具对象。
         */
        public MathTools() {
        }

        /**
         * 计算两个整数之和。
         *
         * @param left 左值
         * @param right 右值
         * @return 加法结果
         */
        @ToolDefinition(name = "add", description = "Add two integers.")
        public Integer add(
                @ToolArgument(name = "left", description = "Left operand.") int left,
                @ToolArgument(name = "right", description = "Right operand.") int right
        ) {
            invocations++;
            return left + right;
        }

        /**
         * 为 nullable 前缀返回描述文本。
         *
         * @param prefix 可选前缀
         * @return 参数值或默认标记
         */
        @ToolDefinition(name = "describe", description = "Return a description.")
        public String describe(
                @ToolArgument(name = "prefix", required = false, nullable = true) String prefix
        ) {
            return prefix == null ? "default" : prefix;
        }

    }

    /**
     * 提供带有 boolean 默认值的工具方法。
     */
    public static final class DefaultTools {
        /**
         * 创建无状态默认值测试工具。
         */
        public DefaultTools() {
        }

        /**
         * 返回 enabled 参数，省略时使用 false。
         *
         * @param enabled 功能是否启用
         * @return 参数值
         */
        @ToolDefinition(name = "default_flag", description = "Return the enabled flag.")
        public Boolean withDefault(
                @ToolArgument(name = "enabled", description = "Whether the feature is enabled.",
                        required = false, hasDefault = true, defaultValueJson = "false") Boolean enabled
        ) {
            return enabled;
        }
    }

    /**
     * 提供与 Java 参数类型不兼容的 JSON 默认值。
     */
    public static final class InvalidDefaultTools {
        /**
         * 创建无状态无效默认值测试工具。
         */
        public InvalidDefaultTools() {
        }

        /**
         * 声明一个故意类型错误的 JSON 默认值。
         *
         * @param count 应为整数的参数
         * @return 参数值
         */
        @ToolDefinition(name = "invalid_default", description = "Invalid default fixture.")
        public Integer invalidDefault(
                @ToolArgument(name = "count", required = false, hasDefault = true,
                        defaultValueJson = "false") Integer count
        ) {
            return count;
        }
    }

    /**
     * 提供语义矛盾的 optional/non-nullable 参数定义。
     */
    public static final class OptionalWithoutDefaultTools {
        /**
         * 创建无状态无效配置测试工具。
         */
        public OptionalWithoutDefaultTools() {
        }

        /**
         * 声明省略后只能产生 null、但又不接受 null 的参数。
         *
         * @param value 参数值
         * @return 参数值的文本形式
         */
        @ToolDefinition(name = "optional_without_default", description = "Invalid optional parameter fixture.")
        public String optional(
                @ToolArgument(name = "value", required = false) String value
        ) {
            return value;
        }
    }

    /**
     * 用于验证不支持参数类型在适配阶段被拒绝的测试工具。
     */
    public static final class UnsupportedTools {
        /**
         * 创建无状态测试工具。
         */
        public UnsupportedTools() {
        }

        /**
         * 声明不支持的 Java 类参数。
         *
         * @param value 自定义输入类型
         * @return 值的文本形式
         */
        @ToolDefinition(name = "unsupported", description = "This signature is not supported.")
        public String unsupported(@ToolArgument(name = "value") PathLike value) {
            return value.toString();
        }
    }

    /**
     * 用于验证任意 POJO 返回值不能自动序列化的测试工具。
     */
    public static final class UnsafeTools {
        /**
         * 创建无状态测试工具。
         */
        public UnsafeTools() {
        }

        /**
         * 返回不允许自动反射序列化的应用 POJO。
         *
         * @return 自定义 Java class
         */
        @ToolDefinition(name = "unsafe_result", description = "Return a custom object.")
        public CustomResult unsafeResult() {
            return new CustomResult();
        }
    }

    /**
     * 用于验证 method invocation runtime error 的测试工具。
     */
    public static final class FailingTools {
        /**
         * 创建无状态测试工具。
         */
        public FailingTools() {
        }

        /**
         * 抛出预期业务错误。
         *
         * @return 不会返回
         */
        @ToolDefinition(name = "fail", description = "Always fail.")
        public String fail() {
            throw new IllegalStateException("tool implementation failed");
        }
    }

    /**
     * 刻意不在参数 allowlist 中的测试签名类型。
     */
    public static final class PathLike {
        @Override
        public String toString() {
            return "path";
        }
    }

    /**
     * 刻意不在 SafeSerializer allowlist 中的自定义返回值。
     */
    public static final class CustomResult {
        private final BigInteger value = BigInteger.ONE;
    }

}
