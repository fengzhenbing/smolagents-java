package org.fzb.smolagents.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.MessageRole;
import org.fzb.smolagents.model.TokenUsage;
import org.fzb.smolagents.model.ToolCall;
import org.fzb.smolagents.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Agent 记忆快照、重置和 token 用量汇总语义。
 */
class AgentMemoryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证记忆快照不可变，且重置仅清空当前记忆。
     */
    @Test
    void snapshotsAreImmutableAndResetClearsSteps() {
        AgentMemory memory = new AgentMemory();
        memory.append(new TaskStep(0, Instant.EPOCH, "task"));

        var snapshot = memory.snapshot();
        assertThrows(UnsupportedOperationException.class, snapshot::clear);

        memory.reset();

        assertEquals(1, snapshot.size());
        assertEquals(0, memory.snapshot().size());
    }

    /**
     * 验证未知 token 用量保持未知，且不会被已知计数覆盖。
     */
    @Test
    void tokenUsagePreservesUnknownAndAggregatesKnownCounts() {
        assertEquals(5, TokenUsage.of(2, 3).totalTokens().orElseThrow());
        assertTrue(TokenUsage.unknown().totalTokens().isEmpty());
        assertTrue(TokenUsage.of(2, 3).plus(TokenUsage.unknown()).totalTokens().isEmpty());
    }

    /**
     * 验证完整与摘要上下文保留任务、工具观察和错误并省略计划细节。
     */
    @Test
    void mapsMemoryStepsToFullAndSummaryModelMessages() {
        AgentMemory memory = new AgentMemory("system prompt");
        memory.append(new TaskStep(0, Instant.EPOCH, "find an answer"));
        memory.append(new PlanningStep(1, Instant.EPOCH, "planning details", TokenUsage.of(1, 1)));
        memory.append(new ActionStep(
                1,
                Instant.EPOCH,
                java.time.Duration.ZERO,
                new ChatMessage(
                        MessageRole.ASSISTANT,
                        List.of(new ContentPart.Text("calling echo")),
                        List.of(new ToolCall("call-1", "echo", MAPPER.createObjectNode().put("text", "x"))),
                        null,
                        null,
                        TokenUsage.of(2, 1)
                ),
                List.of(new ToolObservation("call-1", "echo", AgentValue.text("observed"))),
                TokenUsage.of(2, 1),
                "retry this action"
        ));

        List<ChatMessage> full = memory.toMessages(false);
        List<ChatMessage> summary = memory.toMessages(true);

        assertEquals(MessageRole.SYSTEM, full.getFirst().role());
        assertTrue(containsText(full, "New task:\nfind an answer"));
        assertTrue(containsText(full, "planning details"));
        assertTrue(containsText(full, "observed"));
        assertTrue(containsText(full, "retry this action"));
        assertTrue(summary.stream().noneMatch(message -> containsText(message, "planning details")));
        assertTrue(containsText(summary, "observed"));
        assertTrue(containsText(summary, "retry this action"));
        assertFalse(summary.stream().anyMatch(message -> message.role() == MessageRole.ASSISTANT
                && message.toolCalls().isEmpty()
                && message.content().stream()
                        .filter(ContentPart.Text.class::isInstance)
                        .map(ContentPart.Text.class::cast)
                        .anyMatch(part -> part.text().contains("calling echo"))));
    }

    /**
     * 验证工具失败已由带调用 ID 的 observation 表达时不会重复追加 user 错误消息。
     */
    @Test
    void doesNotDuplicateRecordedToolFailureInModelContext() {
        String detail = "ToolValidationException: invalid arguments";
        String retry = "Now let's retry: take care not to repeat previous errors!";
        ChatMessage output = new ChatMessage(
                MessageRole.ASSISTANT,
                List.of(new ContentPart.Text("calling echo")),
                List.of(new ToolCall("call-1", "echo", MAPPER.createObjectNode())),
                null,
                null,
                TokenUsage.unknown()
        );
        AgentMemory memory = new AgentMemory("system");
        memory.append(new TaskStep(0, Instant.EPOCH, "task"));
        memory.append(new ActionStep(
                1,
                Instant.EPOCH,
                java.time.Duration.ZERO,
                output,
                List.of(new ToolObservation("call-1", "echo", AgentValue.text("Error: " + detail + "\n" + retry))),
                TokenUsage.unknown(),
                "Tool call call-1 (echo) failed: " + detail
        ));

        List<ChatMessage> messages = memory.toMessages(false);
        long retryMessages = messages.stream()
                .flatMap(message -> message.content().stream())
                .filter(ContentPart.Text.class::isInstance)
                .map(ContentPart.Text.class::cast)
                .filter(part -> part.text().contains(retry))
                .count();

        assertEquals(1, retryMessages);
        assertTrue(messages.stream().anyMatch(message -> message.role() == MessageRole.TOOL
                && "call-1".equals(message.toolCallId())));
    }

    /**
     * 检查消息文本内容是否包含指定片段。
     *
     * @param messages 待扫描消息
     * @param expected 预期文本片段
     * @return 找到该片段时为 {@code true}
     */
    private static boolean containsText(List<ChatMessage> messages, String expected) {
        return messages.stream().anyMatch(message -> containsText(message, expected));
    }

    /**
     * 检查单条聊天消息的文本内容是否包含指定片段。
     *
     * @param message 待扫描消息
     * @param expected 预期文本片段
     * @return 找到该片段时为 {@code true}
     */
    private static boolean containsText(ChatMessage message, String expected) {
        return message.content().stream()
                .filter(ContentPart.Text.class::isInstance)
                .map(ContentPart.Text.class::cast)
                .anyMatch(part -> part.text().contains(expected));
    }
}
