package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.core.ToolValidationException;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证工具注册、参数 schema 校验及调用执行行为。
 */
class ToolRegistryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolRegistry registry = new ToolRegistry();

    /**
     * 验证必填字段和参数类型错误会在工具执行前被拒绝。
     *
     * @throws Exception JSON 测试数据解析失败时抛出
     */
    @Test
    void validatesRequiredFieldsAndTypesBeforeExecution() throws Exception {
        EchoTool tool = new EchoTool();
        registry.register(tool);
        ToolExecutionContext context = context("call-1");

        ToolValidationException missing = assertThrows(
                ToolValidationException.class,
                () -> registry.invoke(new ToolCall("call-1", "echo", mapper.readTree("{}")), context)
        );
        ToolValidationException wrongType = assertThrows(
                ToolValidationException.class,
                () -> registry.invoke(new ToolCall("call-1", "echo", mapper.readTree("{\"text\":4}")), context)
        );

        assertEquals(0, tool.invocations);
        assertTrue(missing.getMessage().contains("text"));
        assertTrue(wrongType.getMessage().contains("text"));
    }

    /**
     * 验证参数通过 schema 校验后会调用对应工具并返回结果。
     *
     * @throws Exception JSON 测试数据解析失败时抛出
     */
    @Test
    void invokesToolAfterValidation() throws Exception {
        EchoTool tool = new EchoTool();
        registry.register(tool);

        ToolResult result = registry.invoke(
                new ToolCall("call-2", "echo", mapper.readTree("{\"text\":\"hello\"}")),
                context("call-2")
        );

        ContentPart.Text text = assertInstanceOf(ContentPart.Text.class, result.content().getFirst());
        assertEquals("hello", text.text());
        assertEquals(1, tool.invocations);
    }

    /**
     * 验证可选 nullable 字段通过校验，而未声明字段在执行前被拒绝。
     *
     * @throws Exception JSON 测试数据解析失败时抛出
     */
    @Test
    void acceptsOptionalNullableFieldAndRejectsAdditionalProperties() throws Exception {
        EchoTool tool = new EchoTool();
        registry.register(tool);

        ToolResult result = registry.invoke(
                new ToolCall("call-optional", "echo", mapper.readTree("{\"text\":\"hello\",\"suffix\":null}")),
                context("call-optional")
        );
        ToolValidationException invalidExtra = assertThrows(
                ToolValidationException.class,
                () -> registry.invoke(
                        new ToolCall("call-extra", "echo", mapper.readTree(
                                "{\"text\":\"hello\",\"unexpected\":true}"
                        )),
                        context("call-extra")
                )
        );

        ContentPart.Text text = assertInstanceOf(ContentPart.Text.class, result.content().getFirst());
        assertEquals("hello", text.text());
        assertEquals(1, tool.invocations);
        assertTrue(invalidExtra.getMessage().contains("unexpected"));
    }

    /**
     * 验证整数可作为 Python 工具 number 参数的有效值。
     */
    @Test
    void acceptsIntegerForNumberSchema() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        schema.set("properties", mapper.createObjectNode()
                .set("value", mapper.createObjectNode().put("type", "number")));
        schema.putArray("required").add("value");
        schema.put("additionalProperties", false);

        new JsonSchemaToolArgumentValidator().validate(
                schema,
                mapper.createObjectNode().put("value", 3)
        );
    }

    /**
     * 验证注册重复工具名称时会报告配置错误。
     */
    @Test
    void duplicateToolNamesAreRejected() {
        registry.register(new EchoTool());

        assertThrows(ConfigurationException.class, () -> registry.register(new EchoTool()));
    }

    /**
     * 验证工具运行时异常转换为可诊断执行错误并保留原始原因。
     */
    @Test
    void wrapsToolRuntimeFailureAndPreservesCause() {
        IllegalStateException original = new IllegalStateException("backend failed");
        registry.register(new FailingTool(original));

        ToolExecutionException failure = assertThrows(
                ToolExecutionException.class,
                () -> registry.invoke(
                        new ToolCall("call-failure", "failing", mapper.createObjectNode()),
                        context("call-failure")
                )
        );

        assertTrue(failure.getMessage().contains("backend failed"));
        assertSame(original, failure.getCause());
    }

    /**
     * 为工具调用构造带有新取消令牌的上下文。
     *
     * @param callId 模型工具调用 ID
     * @return 工具执行上下文
     */
    private static ToolExecutionContext context(String callId) {
        return new ToolExecutionContext(UUID.randomUUID(), callId, new org.fzb.smolagents.core.CancellationToken());
    }

    /**
     * 用于断言参数校验与实际执行顺序的文本回显工具。
     */
    private final class EchoTool implements Tool {
        private final ToolSpec spec = new ToolSpec(
                "echo",
                "Echo text.",
                schema(),
                true
        );
        private int invocations;

        /**
         * 返回回显工具定义。
         *
         * @return 工具 schema 和描述
         */
        @Override
        public ToolSpec spec() {
            return spec;
        }

        /**
         * 返回传入的文本参数。
         *
         * @param arguments 已校验的参数对象
         * @param context 工具调用上下文
         * @return 回显文本结果
         */
        @Override
        public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
            invocations++;
            return ToolResult.text(arguments.get("text").asText());
        }

        /**
         * 创建要求 text 字符串参数且禁止额外字段的 schema。
         *
         * @return JSON Schema
         */
        private JsonNode schema() {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
            schema.put("type", "object");
            ObjectNode properties = mapper.createObjectNode();
            properties.set("text", mapper.createObjectNode().put("type", "string"));
            ObjectNode suffix = mapper.createObjectNode();
            suffix.set("type", mapper.createArrayNode().add("string").add("null"));
            properties.set("suffix", suffix);
            schema.set("properties", properties);
            schema.putArray("required").add("text");
            schema.put("additionalProperties", false);
            return schema;
        }
    }

    /**
     * 用于验证工具执行异常包装的故障工具。
     */
    private final class FailingTool implements Tool {
        private final ToolSpec spec = new ToolSpec(
                "failing",
                "Always fail.",
                mapper.createObjectNode().put("type", "object"),
                true
        );
        private final RuntimeException failure;

        /**
         * 创建每次执行都抛出指定异常的工具。
         *
         * @param failure 原始执行错误
         */
        private FailingTool(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
            throw failure;
        }
    }
}
