package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ToolExecutionException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 在工具调用期间向用户提出问题，并将用户输入作为文本结果返回。
 *
 * <p>默认构造器使用标准输入/输出；自动化和嵌入式调用方可注入 reader/writer。
 * 此工具会阻塞等待一行输入，取消令牌只能在等待前后检查，不能中断底层 reader。</p>
 */
public final class UserInputTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final BufferedReader input;
    private final Writer output;
    private final ToolSpec spec = new ToolSpec(
            "user_input",
            "Ask the user a question and return their answer.",
            createSchema(),
            true
    );

    /**
     * 使用标准输入和标准输出创建交互工具。
     */
    public UserInputTool() {
        this(
                new InputStreamReader(System.in, StandardCharsets.UTF_8),
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
        );
    }

    /**
     * 使用注入的输入和输出流创建交互工具。
     *
     * <p>工具不会关闭调用方提供的流。</p>
     *
     * @param input 用户答案来源
     * @param output 提问输出目标
     */
    public UserInputTool(Reader input, Writer output) {
        Reader source = Objects.requireNonNull(input, "input");
        this.input = source instanceof BufferedReader buffered ? buffered : new BufferedReader(source);
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * 返回 user_input 工具的参数定义。
     *
     * @return 要求 question 字符串的工具 schema
     */
    @Override
    public ToolSpec spec() {
        return spec;
    }

    /**
     * 显示指定问题并读取用户的一行回答。
     *
     * @param arguments 已通过 schema 校验的 question 参数
     * @param context 当前工具调用上下文
     * @return 用户输入的文本结果
     * @throws ToolExecutionException 输入结束或读写失败时抛出
     */
    @Override
    public synchronized ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
        Objects.requireNonNull(context, "context");
        CancellationToken cancellation = context.cancellation();
        cancellation.throwIfCancelled();
        String question = arguments.path("question").asText();
        try {
            output.write(question);
            output.write(" => Type your answer here:");
            output.flush();
            String answer = input.readLine();
            if (answer == null) {
                throw new ToolExecutionException("No user input available for question");
            }
            cancellation.throwIfCancelled();
            return ToolResult.text(answer);
        } catch (IOException error) {
            throw new ToolExecutionException("Unable to read user input", error);
        }
    }

    /**
     * 构造仅接受 question 字段的输入 schema。
     *
     * @return JSON Schema object
     */
    private static JsonNode createSchema() {
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        var question = MAPPER.createObjectNode()
                .put("type", "string")
                .put("description", "The question to ask the user.");
        schema.set("properties", MAPPER.createObjectNode().set("question", question));
        schema.putArray("required").add("question");
        schema.put("additionalProperties", false);
        return schema;
    }
}
