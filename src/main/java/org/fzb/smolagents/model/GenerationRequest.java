package org.fzb.smolagents.model;

import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.tool.ToolSpec;

import java.util.List;
import java.util.Objects;

/**
 * 封装一次模型生成所需的完整消息、选项、工具和取消信号。
 *
 * @param messages 有序上下文消息
 * @param options 生成选项
 * @param tools 可用工具定义
 * @param cancellation 请求取消令牌
 * @param stopSequences 可选的模型停止序列
 */
public record GenerationRequest(
        List<ChatMessage> messages,
        GenerationOptions options,
        List<ToolSpec> tools,
        CancellationToken cancellation,
        List<String> stopSequences
) {
    /**
     * 校验请求字段并复制消息和工具集合。
     *
     * @param messages 有序上下文消息
     * @param options 生成选项
     * @param tools 可用工具定义
     * @param cancellation 请求取消令牌
     * @param stopSequences 可选的模型停止序列
     */
    public GenerationRequest {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        stopSequences = List.copyOf(Objects.requireNonNull(stopSequences, "stopSequences"));
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(cancellation, "cancellation");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (messages.stream().anyMatch(Objects::isNull)
                || tools.stream().anyMatch(Objects::isNull)
                || stopSequences.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("request lists must not contain null values");
        }
    }

    /**
     * 创建不配置额外停止序列的生成请求。
     *
     * @param messages 有序上下文消息
     * @param options 生成选项
     * @param tools 可用工具定义
     * @param cancellation 请求取消令牌
     */
    public GenerationRequest(
            List<ChatMessage> messages,
            GenerationOptions options,
            List<ToolSpec> tools,
            CancellationToken cancellation
    ) {
        this(messages, options, tools, cancellation, List.of());
    }
}
