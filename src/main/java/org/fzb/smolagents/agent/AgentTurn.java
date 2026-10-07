package org.fzb.smolagents.agent;

import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.GenerationOptions;
import org.fzb.smolagents.tool.ToolSpec;

import java.util.List;
import java.util.Objects;

/**
 * 提供给动作策略的单步不可变输入快照。
 *
 * @param stepNumber 本步序号
 * @param messages 模型上下文消息
 * @param generationOptions 模型生成选项
 * @param tools 可用工具定义
 */
public record AgentTurn(
        int stepNumber,
        List<ChatMessage> messages,
        GenerationOptions generationOptions,
        List<ToolSpec> tools
) {
    /**
     * 校验步数、消息、选项及工具，并复制集合。
     *
     * @param stepNumber 本步序号
     * @param messages 模型上下文消息
     * @param generationOptions 模型生成选项
     * @param tools 可用工具定义
     */
    public AgentTurn {
        if (stepNumber < 1) {
            throw new IllegalArgumentException("stepNumber must be positive");
        }
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        Objects.requireNonNull(generationOptions, "generationOptions");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
    }
}
