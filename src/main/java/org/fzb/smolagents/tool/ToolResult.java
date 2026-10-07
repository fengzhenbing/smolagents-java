package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.fzb.smolagents.model.ContentPart;

import java.util.List;

/**
 * 表示工具返回的多模态内容及可选结构化结果。
 *
 * @param content 有序内容片段
 * @param structuredContent 可选结构化 JSON
 */
public record ToolResult(List<ContentPart> content, JsonNode structuredContent) {
    /**
     * 复制内容集合和 JSON 树，并拒绝空结果。
     *
     * @param content 有序内容片段
     * @param structuredContent 可选结构化 JSON
     */
    public ToolResult {
        content = List.copyOf(content);
        if (content.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("content must not contain null values");
        }
        structuredContent = structuredContent == null ? null : structuredContent.deepCopy();
        if (content.isEmpty() && structuredContent == null) {
            throw new IllegalArgumentException("tool result must not be empty");
        }
    }

    /**
     * 返回结构化结果的防御性副本。
     *
     * @return JSON 副本；无结构化结果时返回 {@code null}
     */
    @Override
    public JsonNode structuredContent() {
        return structuredContent == null ? null : structuredContent.deepCopy();
    }

    /**
     * 创建仅包含一段文本的工具结果。
     *
     * @param text 工具返回文本
     * @return 文本结果
     */
    public static ToolResult text(String text) {
        return new ToolResult(List.of(new ContentPart.Text(text)), null);
    }
}
