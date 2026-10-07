package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.model.ContentPart;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 使用调用方准备的本地/显式授权 Whisper checkpoint 做 opt-in 转录集成测试。
 */
class TransformersWhisperToolIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 预热真实 Transformers 模型并转录调用方提供的语音样本。
     *
     * @throws Exception 模型载入、音频读取或真实转录失败
     */
    @Test
    void transcribesConfiguredSpeechAudioWithTransformersWhenExplicitlyEnabled() throws Exception {
        Assumptions.assumeTrue(
                "1".equals(System.getenv("SMOLAGENTS_WHISPER_TESTS")),
                "Set SMOLAGENTS_WHISPER_TESTS=1 to run the local-model integration test"
        );
        String audioPath = System.getenv("WHISPER_TEST_AUDIO");
        Assumptions.assumeTrue(audioPath != null && !audioPath.isBlank(),
                "WHISPER_TEST_AUDIO must point to a speech-containing audio file");
        Path audioFile = Path.of(audioPath).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(audioFile), "WHISPER_TEST_AUDIO file does not exist");
        byte[] audio = Files.readAllBytes(audioFile);
        Assumptions.assumeTrue(audio.length > 0 && audio.length <= 25 * 1024 * 1024,
                "WHISPER_TEST_AUDIO must be between 1 byte and 25 MiB");

        String extension = fileExtension(audioFile);
        Assumptions.assumeTrue(
                java.util.Set.of("wav", "wave", "mp3", "m4a", "flac", "ogg", "webm").contains(extension),
                "Use a WAV, MP3, M4A, FLAC, OGG, or WebM speech sample"
        );
        String model = System.getenv().getOrDefault("WHISPER_TEST_MODEL", "openai/whisper-tiny");
        String python = System.getenv().getOrDefault("SMOLAGENTS_PYTHON", "python");
        String device = System.getenv().getOrDefault("WHISPER_TEST_DEVICE", "cpu");
        boolean allowDownload = "1".equals(System.getenv("SMOLAGENTS_WHISPER_ALLOW_DOWNLOAD"));
        String mimeType = switch (extension) {
            case "wav", "wave" -> "audio/wav";
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "flac" -> "audio/flac";
            case "ogg" -> "audio/ogg";
            case "webm" -> "audio/webm";
            default -> throw new IllegalStateException("Unsupported audio extension");
        };

        try (TransformersWhisperTool tool = new TransformersWhisperTool(
                model,
                python,
                device,
                !allowDownload,
                Duration.ofMinutes(15),
                Duration.ofMinutes(5),
                25 * 1024 * 1024
        )) {
            tool.warmUp();
            ToolResult result = tool.execute(
                    MAPPER.createObjectNode().set(
                            "audio",
                            MAPPER.createObjectNode()
                                    .put("data", Base64.getEncoder().encodeToString(audio))
                                    .put("mimeType", mimeType)
                    ),
                    new ToolExecutionContext(
                            UUID.randomUUID(),
                            "whisper-integration",
                            new CancellationToken()
                    )
            );
            String transcript = assertInstanceOf(ContentPart.Text.class, result.content().getFirst()).text();
            assertFalse(transcript.isBlank(), "Speech-containing audio must produce a non-empty transcript");
        }
    }

    /**
     * 返回调用方语音样本的小写扩展名。
     *
     * @param audioFile 音频文件路径
     * @return 不含点号的扩展名；缺少扩展名时为空字符串
     */
    private static String fileExtension(Path audioFile) {
        String name = audioFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
