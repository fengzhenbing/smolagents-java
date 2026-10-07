package org.fzb.smolagents.executor;

import org.fzb.smolagents.core.AgentValue;

import java.util.Map;

/**
 * 保存一次代码执行的标准输出、返回值、变量更新或失败信息。
 *
 * @param stdout 标准输出
 * @param value 执行表达式或 final_answer 的返回值
 * @param updatedVariables 本次执行产生的变量更新
 * @param failure 执行失败详情；成功时为空
 * @param finalAnswer 本次代码是否通过 final_answer 正常终止
 */
public record ExecutorResult(
        String stdout,
        AgentValue value,
        Map<String, AgentValue> updatedVariables,
        ExecutorFailure failure,
        boolean finalAnswer
) {
    /**
     * 使用非 final-answer 默认值创建执行结果，兼容通用执行器实现。
     *
     * @param stdout 标准输出
     * @param value 执行返回值
     * @param updatedVariables 变量更新
     * @param failure 执行失败详情
     */
    public ExecutorResult(
            String stdout,
            AgentValue value,
            Map<String, AgentValue> updatedVariables,
            ExecutorFailure failure
    ) {
        this(stdout, value, updatedVariables, failure, false);
    }

    /**
     * 规范化标准输出并复制变量更新集合。
     *
     * @param stdout 标准输出
     * @param value 执行返回值
     * @param updatedVariables 变量更新
     * @param failure 执行失败详情
     * @param finalAnswer 是否通过 final_answer 正常终止
     */
    public ExecutorResult {
        stdout = stdout == null ? "" : stdout;
        updatedVariables = Map.copyOf(updatedVariables);
        if (failure != null && value != null) {
            throw new IllegalArgumentException("failed execution must not return a success value");
        }
    }

    /**
     * 判断执行结果是否成功。
     *
     * @return 未携带失败详情时为 {@code true}
     */
    public boolean succeeded() {
        return failure == null;
    }
}
