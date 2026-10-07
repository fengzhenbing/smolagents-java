package org.fzb.smolagents.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;

/**
 * 一次模型生成请求的可选采样、长度、超时和结构化输出参数。
 *
 * @param temperature 采样温度；空值采用 Provider 默认值
 * @param maxTokens 最大生成 token 数；空值采用 Provider 默认值
 * @param timeout 请求超时；空值采用适配器默认值
 * @param responseSchema 可选 JSON Schema 输出约束
 */
public record GenerationOptions(
        Double temperature,
        Integer maxTokens,
        Duration timeout,
        JsonNode responseSchema
) {
    /**
     * 校验数值范围并复制结构化输出 schema。
     *
     * @param temperature 采样温度
     * @param maxTokens 最大生成 token 数
     * @param timeout 请求超时
     * @param responseSchema JSON Schema 输出约束
     */
    public GenerationOptions {
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0)) {
            throw new IllegalArgumentException("temperature must be finite and non-negative");
        }
        if (maxTokens != null && maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        responseSchema = responseSchema == null ? null : responseSchema.deepCopy();
    }

    /**
     * 返回防御性复制后的结构化输出 schema。
     *
     * @return JSON Schema；未配置时返回 {@code null}
     */
    @Override
    public JsonNode responseSchema() {
        return responseSchema == null ? null : responseSchema.deepCopy();
    }

    /**
     * 创建全部采用 Provider 默认行为的生成选项。
     *
     * @return 默认选项
     */
    public static GenerationOptions defaults() {
        return new GenerationOptions(null, null, null, null);
    }
}
