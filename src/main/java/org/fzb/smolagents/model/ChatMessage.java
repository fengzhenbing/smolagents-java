package org.fzb.smolagents.model;

import java.util.List;
import java.util.Objects;

/**
 * 表示模型请求、响应或工具 observation 中的一条聊天消息。
 *
 * @param role 消息角色
 * @param content 有序消息内容
 * @param toolCalls assistant 消息携带的工具调用
 * @param name 可选工具名称
 * @param toolCallId 工具 observation 对应的调用标识
 * @param tokenUsage Provider 返回的 token 用量
 */
public record ChatMessage(
        MessageRole role,
        List<ContentPart> content,
        List<ToolCall> toolCalls,
        String name,
        String toolCallId,
        TokenUsage tokenUsage
) {
    /**
     * 校验角色和工具调用关系，并复制内容集合。
     *
     * @param role 消息角色
     * @param content 有序消息内容
     * @param toolCalls 工具调用列表
     * @param name 可选工具名称
     * @param toolCallId 对应的工具调用标识
     * @param tokenUsage token 用量
     */
    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(tokenUsage, "tokenUsage");
        content = List.copyOf(Objects.requireNonNull(content, "content"));
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
        if (content.stream().anyMatch(Objects::isNull) || toolCalls.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("message lists must not contain null values");
        }
        if (role == MessageRole.TOOL && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("tool message requires a toolCallId");
        }
        if (role != MessageRole.TOOL && toolCallId != null) {
            throw new IllegalArgumentException("toolCallId is only valid for tool messages");
        }
        if (!toolCalls.isEmpty() && role != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("only assistant messages may contain tool calls");
        }
    }

    /**
     * 创建 token 用量未知的聊天消息。
     *
     * @param role 消息角色
     * @param content 有序消息内容
     * @param toolCalls 工具调用列表
     * @param name 可选工具名称
     * @param toolCallId 工具调用标识
     */
    public ChatMessage(
            MessageRole role,
            List<ContentPart> content,
            List<ToolCall> toolCalls,
            String name,
            String toolCallId
    ) {
        this(role, content, toolCalls, name, toolCallId, TokenUsage.unknown());
    }

    /**
     * 创建仅含一个文本片段的消息。
     *
     * @param role 消息角色
     * @param text 文本内容
     * @return 聊天消息
     */
    public static ChatMessage text(MessageRole role, String text) {
        return new ChatMessage(
                role, List.of(new ContentPart.Text(text)), List.of(), null, null, TokenUsage.unknown()
        );
    }

    /**
     * 创建关联到特定调用 ID 的工具结果消息。
     *
     * @param toolName 工具名称
     * @param callId 对应的工具调用 ID
     * @param content 工具返回内容
     * @return 工具角色消息
     */
    public static ChatMessage toolResult(String toolName, String callId, List<ContentPart> content) {
        return new ChatMessage(
                MessageRole.TOOL, content, List.of(), toolName, callId, TokenUsage.unknown()
        );
    }
}
