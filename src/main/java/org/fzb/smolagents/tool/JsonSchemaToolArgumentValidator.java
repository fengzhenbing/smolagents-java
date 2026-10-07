package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.fzb.smolagents.core.ToolValidationException;

import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 使用 networknt JSON Schema Draft 2020-12 校验工具参数。
 */
public final class JsonSchemaToolArgumentValidator implements ToolArgumentValidator {
    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /**
     * 创建基于 JSON Schema Draft 2020-12 的参数校验器。
     */
    public JsonSchemaToolArgumentValidator() {
    }

    /**
     * 验证参数对象；错误信息按字段路径排序后统一报告。
     *
     * @param schema 工具输入 JSON Schema
     * @param arguments 实际 JSON 参数
     * @throws ToolValidationException 参数不是 object 或不符合 schema 时抛出
     */
    @Override
    public void validate(JsonNode schema, JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw new ToolValidationException("tool arguments must be a JSON object");
        }
        // 步骤一：拒绝非 object 参数，保证后续校验遵循工具参数协议。
        Set<ValidationMessage> errors = factory.getSchema(schema).validate(arguments);
        if (!errors.isEmpty()) {
            // 步骤二：按稳定顺序整理 schema 校验错误，便于测试和模型修正。
            String message = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.joining("; "));
            throw new ToolValidationException("tool arguments do not match schema: " + message);
        }
    }
}
