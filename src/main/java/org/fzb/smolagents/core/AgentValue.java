package org.fzb.smolagents.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.ContentPart.Text;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 表示工具 observation 或 Agent 最终输出，可同时携带多模态内容和结构化 JSON。
 *
 * @param content 有序内容片段
 * @param structuredContent 可选的结构化 JSON 值
 */
public record AgentValue(List<ContentPart> content, JsonNode structuredContent) {
    /**
     * 校验并复制值中的可变集合和 JSON 树。
     *
     * @param content 有序内容片段
     * @param structuredContent 可选的结构化 JSON 值
     */
    public AgentValue {
        Objects.requireNonNull(content, "content");
        content = List.copyOf(content);
        if (content.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("content must not contain null values");
        }
        if (structuredContent != null) {
            structuredContent = structuredContent.deepCopy();
        }
        if (content.isEmpty() && structuredContent == null) {
            throw new IllegalArgumentException("AgentValue must contain content or structured content");
        }
    }

    /**
     * 返回结构化 JSON 的防御性副本。
     *
     * @return JSON 副本；不存在时返回 {@code null}
     */
    @Override
    public JsonNode structuredContent() {
        return structuredContent == null ? null : structuredContent.deepCopy();
    }

    /**
     * 创建仅含文本的 Agent 值。
     *
     * @param text 输出文本
     * @return 文本值
     */
    public static AgentValue text(String text) {
        return new AgentValue(List.of(new Text(text)), null);
    }

    /**
     * 当所有内容片段均为文本时，合并并返回纯文本。
     *
     * @return 合并后的文本；含非文本片段或无内容时返回空
     */
    public Optional<String> plainText() {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : content) {
            if (!(part instanceof Text textPart)) {
                return Optional.empty();
            }
            text.append(textPart.text());
        }
        return content.isEmpty() ? Optional.empty() : Optional.of(text.toString());
    }
}
