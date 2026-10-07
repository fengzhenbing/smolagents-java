package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证交互式用户输入工具的提问、回答和输入结束行为。
 */
class UserInputToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证工具输出问题提示，并将输入的一行原样返回。
     *
     * @throws Exception 测试 JSON 参数解析失败时抛出
     */
    @Test
    void asksQuestionAndReturnsInputLine() throws Exception {
        StringWriter output = new StringWriter();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new UserInputTool(new StringReader("Use the first option.\n"), output));

        ToolResult result = registry.invoke(
                new ToolCall("user-input-call", "user_input", MAPPER.readTree(
                        "{\"question\":\"Which option should I use?\"}"
                )),
                context("user-input-call")
        );

        assertEquals("Which option should I use? => Type your answer here:", output.toString());
        ContentPart.Text answer = assertInstanceOf(ContentPart.Text.class, result.content().getFirst());
        assertEquals("Use the first option.", answer.text());
    }

    /**
     * 验证输入流已结束时工具返回明确执行错误而非空成功结果。
     *
     * @throws Exception 测试 JSON 参数解析失败时抛出
     */
    @Test
    void reportsEndOfInput() throws Exception {
        UserInputTool tool = new UserInputTool(new StringReader(""), new StringWriter());

        ToolExecutionException failure = assertThrows(
                ToolExecutionException.class,
                () -> tool.execute(
                        MAPPER.readTree("{\"question\":\"Continue?\"}"),
                        context("user-input-eof")
                )
        );

        assertTrue(failure.getMessage().contains("No user input"));
    }

    /**
     * 为一次工具调用创建独立运行标识和取消令牌。
     *
     * @param callId 工具调用标识
     * @return 工具执行上下文
     */
    private static ToolExecutionContext context(String callId) {
        return new ToolExecutionContext(UUID.randomUUID(), callId, new CancellationToken());
    }
}
