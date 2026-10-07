package org.fzb.smolagents.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Objects;

/**
 * 表示提交给 Agent 的任务及其附加上下文。
 *
 * @param task 非空任务描述
 * @param additionalArguments 可选的 JSON 附加参数
 */
public record AgentInput(String task, Map<String, JsonNode> additionalArguments) {
    /**
     * 校验任务并对附加 JSON 参数进行防御性复制。
     *
     * @param task 非空任务描述
     * @param additionalArguments 可选的 JSON 附加参数
     */
    public AgentInput {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        Objects.requireNonNull(additionalArguments, "additionalArguments");
        additionalArguments = additionalArguments.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> Objects.requireNonNull(entry.getValue(), "additional argument value").deepCopy()
                )
        );
    }
}
