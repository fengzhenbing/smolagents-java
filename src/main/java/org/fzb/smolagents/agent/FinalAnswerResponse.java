package org.fzb.smolagents.agent;

import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.model.TokenUsage;

import java.util.Objects;

/**
 * 表示步数上限后的最终答案以及生成该答案的模型用量。
 *
 * @param answer 最终输出值
 * @param tokenUsage 模型 token 用量
 */
public record FinalAnswerResponse(AgentValue answer, TokenUsage tokenUsage) {
    /**
     * 校验答案和用量非空。
     *
     * @param answer 最终输出值
     * @param tokenUsage 模型 token 用量
     */
    public FinalAnswerResponse {
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(tokenUsage, "tokenUsage");
    }
}
