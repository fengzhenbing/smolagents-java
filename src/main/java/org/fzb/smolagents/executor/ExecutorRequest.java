package org.fzb.smolagents.executor;

import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.tool.Tool;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次代码执行请求及其受控依赖。
 *
 * @param code 待执行代码
 * @param variables 可访问变量
 * @param tools 可调用工具
 * @param timeout 单次执行时限
 * @param cancellation 取消令牌
 * @param runId 所属 Agent 运行标识
 */
public record ExecutorRequest(
        String code,
        Map<String, AgentValue> variables,
        Map<String, Tool> tools,
        Duration timeout,
        CancellationToken cancellation,
        UUID runId
) {
    /**
     * 使用新运行标识创建执行请求，兼容独立使用执行器的场景。
     *
     * @param code 待执行代码
     * @param variables 可访问变量
     * @param tools 可调用工具
     * @param timeout 执行时限
     * @param cancellation 取消令牌
     */
    public ExecutorRequest(
            String code,
            Map<String, AgentValue> variables,
            Map<String, Tool> tools,
            Duration timeout,
            CancellationToken cancellation
    ) {
        this(code, variables, tools, timeout, cancellation, UUID.randomUUID());
    }

    /**
     * 校验并复制变量和工具集合。
     *
     * @param code 待执行代码
     * @param variables 可访问变量
     * @param tools 可调用工具
     * @param timeout 执行时限
     * @param cancellation 取消令牌
     * @param runId 所属 Agent 运行标识
     */
    public ExecutorRequest {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        variables = Map.copyOf(Objects.requireNonNull(variables, "variables"));
        tools = Map.copyOf(Objects.requireNonNull(tools, "tools"));
        if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(runId, "runId");
    }
}
