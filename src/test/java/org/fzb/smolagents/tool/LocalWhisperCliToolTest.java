package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.model.ContentPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证本地 Whisper CLI 转录编排、checkpoint 配置和临时文件清理。
 */
class LocalWhisperCliToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    private Path temporaryDirectory;

    /**
     * 验证模型文件路径、音频输入和语言提示以独立参数传给本地 CLI。
     *
     * @throws Exception 临时文件创建或测试 runner 写出结果失败
     */
    @Test
    void passesExplicitModelAndAudioToCliWithoutShellAndCleansWorkspace() throws Exception {
        Path executable = Files.writeString(temporaryDirectory.resolve("whisper.exe"), "test executable");
        Path checkpoint = Files.writeString(temporaryDirectory.resolve("tiny.pt"), "local checkpoint");
        AtomicReference<List<String>> commandSeen = new AtomicReference<>();
        LocalWhisperCliTool tool = new LocalWhisperCliTool(
                executable,
                checkpoint,
                Duration.ofSeconds(2),
                1_024,
                (command, directory, timeout, cancellation) -> {
                    commandSeen.set(command);
                    Files.writeString(directory.resolve("input.json"), "{\"text\":\"recognized locally\"}");
                    return 0;
                }
        );
        var arguments = MAPPER.readTree("""
                {"audio":{"data":"AQID","mimeType":"audio/wav"},"language":"en"}
                """);

        ToolResult result = tool.execute(arguments, context());

        assertEquals("recognized locally",
                assertInstanceOf(ContentPart.Text.class, result.content().getFirst()).text());
        List<String> command = commandSeen.get();
        assertEquals(executable.toRealPath().toString(), command.getFirst());
        assertEquals(checkpoint.toRealPath().toString(), command.get(command.indexOf("--model") + 1));
        assertEquals("en", command.get(command.indexOf("--language") + 1));
        assertTrue(command.contains("--output_format"));
        assertTrue(command.contains("json"));
        assertFalse(command.contains("/c") || command.contains("-c"));
        Path workDirectory = Path.of(command.get(command.indexOf("--output_dir") + 1));
        assertTrue(Files.notExists(workDirectory));
    }

    /**
     * 验证 CLI 非零退出、错误音频和缺失的 checkpoint 均显式失败。
     *
     * @throws Exception 临时测试文件创建失败
     */
    @Test
    void reportsCliAndInputErrorsWithoutReturningSuccessShapedText() throws Exception {
        Path executable = Files.writeString(temporaryDirectory.resolve("whisper.exe"), "test executable");
        Path checkpoint = Files.writeString(temporaryDirectory.resolve("tiny.pt"), "local checkpoint");
        LocalWhisperCliTool failingTool = new LocalWhisperCliTool(
                executable,
                checkpoint,
                Duration.ofSeconds(2),
                1_024,
                (command, directory, timeout, cancellation) -> 17
        );

        ToolExecutionException failure = assertThrows(
                ToolExecutionException.class,
                () -> failingTool.execute(audioArguments("AQID"), context())
        );
        assertTrue(failure.getMessage().contains("exited with code 17"));
        assertThrows(
                ToolExecutionException.class,
                () -> failingTool.execute(audioArguments("not-base64"), context())
        );
        assertThrows(
                ConfigurationException.class,
                () -> new LocalWhisperCliTool(
                        executable,
                        temporaryDirectory.resolve("missing.pt"),
                        Duration.ofSeconds(2),
                        1_024
                )
        );
    }

    /**
     * 创建包含 base64 音频输入的最小工具参数。
     *
     * @param data base64 音频
     * @return 输入参数 JSON
     */
    private static com.fasterxml.jackson.databind.JsonNode audioArguments(String data) {
        return MAPPER.createObjectNode().set(
                "audio",
                MAPPER.createObjectNode().put("data", data).put("mimeType", "audio/wav")
        );
    }

    /**
     * 创建隔离的工具调用上下文。
     *
     * @return 当前测试调用上下文
     */
    private static ToolExecutionContext context() {
        return new ToolExecutionContext(UUID.randomUUID(), "local-whisper-call", new CancellationToken());
    }
}
