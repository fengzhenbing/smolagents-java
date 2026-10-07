package org.fzb.smolagents.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.SerializationException;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.MessageRole;
import org.fzb.smolagents.model.TokenUsage;
import org.fzb.smolagents.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 AgentMemory 的安全版本化持久化及完整步骤恢复。
 */
class AgentMemoryCodecTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    private Path temporaryDirectory;

    /**
     * 验证所有内置步骤、工具观察、多模态内容和 token 用量可往返。
     */
    @Test
    void roundTripsCompleteMemoryWithoutLoadingArbitraryClasses() throws Exception {
        AgentMemory source = new AgentMemory("system prompt");
        source.append(new TaskStep(0, Instant.parse("2024-01-01T00:00:00Z"), "task"));
        source.append(new PlanningStep(
                1,
                Instant.parse("2024-01-01T00:00:01Z"),
                "plan",
                new TokenUsage(OptionalLong.of(2), OptionalLong.empty()),
                List.of(ChatMessage.text(MessageRole.USER, "plan this task")),
                ChatMessage.text(MessageRole.ASSISTANT, "facts and steps")
        ));
        ChatMessage modelOutput = new ChatMessage(
                MessageRole.ASSISTANT,
                List.of(
                        new ContentPart.Text("thinking"),
                        new ContentPart.Image(
                                URI.create("https://example.test/image.png"),
                                "image/png",
                                new byte[]{1, 2, 3}
                        ),
                        new ContentPart.Audio(null, "audio/wav", new byte[]{4, 5}, "spoken")
                ),
                List.of(new ToolCall("call-1", "echo", MAPPER.createObjectNode().put("text", "hello"))),
                null,
                null,
                TokenUsage.of(3, 1)
        );
        source.append(new ActionStep(
                1,
                Instant.parse("2024-01-01T00:00:02Z"),
                Duration.ofMillis(250),
                modelOutput,
                List.of(new ToolObservation(
                        "call-1",
                        "echo",
                        new AgentValue(
                                List.of(new ContentPart.Text("observed")),
                                MAPPER.readTree("{\"__type__\":\"bytes\",\"data\":\"not-a-marker-here\"}")
                        )
                )),
                TokenUsage.unknown(),
                "retry context"
        ));
        source.append(new FinalAnswerStep(
                2,
                Instant.parse("2024-01-01T00:00:03Z"),
                Duration.ofMillis(50),
                new AgentValue(
                        List.of(new ContentPart.Text("answer")),
                        MAPPER.createObjectNode().put("ok", true)
                ),
                TokenUsage.of(1, 2)
        ));

        String serialized = AgentMemoryCodec.dumps(source);
        AgentMemory restored = AgentMemoryCodec.loads(serialized);
        List<MemoryStep> steps = restored.snapshot();

        assertTrue(serialized.startsWith(AgentMemoryCodec.PREFIX));
        assertEquals("system prompt", restored.systemPrompt());
        assertEquals(List.of(TaskStep.class, PlanningStep.class, ActionStep.class, FinalAnswerStep.class),
                steps.stream().map(Object::getClass).toList());
        PlanningStep planning = assertInstanceOf(PlanningStep.class, steps.get(1));
        assertTrue(planning.tokenUsage().promptTokens().isPresent());
        assertTrue(planning.tokenUsage().completionTokens().isEmpty());
        assertEquals("plan this task", ((ContentPart.Text) planning.modelInputMessages()
                .getFirst().content().getFirst()).text());
        assertEquals("facts and steps", ((ContentPart.Text) planning.modelOutputMessage()
                .content().getFirst()).text());
        ActionStep action = assertInstanceOf(ActionStep.class, steps.get(2));
        assertEquals("retry context", action.error());
        assertEquals(TokenUsage.unknown(), action.tokenUsage());
        assertEquals("call-1", action.modelOutput().toolCalls().getFirst().id());
        ContentPart.Image image = assertInstanceOf(ContentPart.Image.class, action.modelOutput().content().get(1));
        assertArrayEquals(new byte[]{1, 2, 3}, image.data());
        ContentPart.Audio audio = assertInstanceOf(ContentPart.Audio.class, action.modelOutput().content().get(2));
        assertArrayEquals(new byte[]{4, 5}, audio.data());
        assertEquals("spoken", audio.transcript());
        assertEquals(
                MAPPER.readTree("{\"__type__\":\"bytes\",\"data\":\"not-a-marker-here\"}"),
                action.observations().getFirst().result().structuredContent()
        );
    }

    /**
     * 验证默认解码只接受有版本前缀的安全 JSON。
     */
    @Test
    void rejectsUnprefixedPickleAndUnsupportedSchemaVersions() {
        assertThrows(SerializationException.class, () -> AgentMemoryCodec.loads("gASV-pickle-payload"));
        assertThrows(SerializationException.class, () -> AgentMemoryCodec.loads("pickle:unsafe"));
        assertThrows(
                SerializationException.class,
                () -> AgentMemoryCodec.loads(AgentMemoryCodec.PREFIX
                        + "{\"schema_version\":99,\"system_prompt\":\"x\",\"steps\":[]}")
        );
    }

    /**
     * 验证安全记忆文件可原子保存，并可在进程生命周期外恢复。
     */
    @Test
    void savesAndLoadsVersionedMemoryFromLocalFile() {
        AgentMemory memory = new AgentMemory("system", List.of(
                new TaskStep(0, Instant.EPOCH, "persisted task")
        ));
        Path file = temporaryDirectory.resolve("memory.json");

        AgentMemoryCodec.save(memory, file);
        AgentMemory loaded = AgentMemoryCodec.load(file);

        assertEquals(memory.systemPrompt(), loaded.systemPrompt());
        assertEquals(memory.snapshot(), loaded.snapshot());
        assertTrue(file.toFile().isFile());
        assertTrue(file.toFile().length() > AgentMemoryCodec.PREFIX.length());
    }

    /**
     * 验证未知 step type、畸形 JSON 和无效媒体编码明确失败。
     */
    @Test
    void rejectsUnknownStepTypesAndMalformedMedia() {
        String unknownStep = AgentMemoryCodec.PREFIX
                + "{\"schema_version\":1,\"system_prompt\":\"x\",\"steps\":["
                + "{\"step_type\":\"custom\",\"step_number\":0,\"started_at\":\"2024-01-01T00:00:00Z\"}]}";
        assertThrows(SerializationException.class, () -> AgentMemoryCodec.loads(unknownStep));
        assertThrows(SerializationException.class, () -> AgentMemoryCodec.loads(AgentMemoryCodec.PREFIX + "{bad"));

        String invalidImage = AgentMemoryCodec.PREFIX
                + "{\"schema_version\":1,\"system_prompt\":\"x\",\"steps\":["
                + "{\"step_type\":\"final_answer\",\"step_number\":1,"
                + "\"started_at\":\"2024-01-01T00:00:00Z\",\"duration\":{\"seconds\":0,\"nanos\":0},"
                + "\"token_usage\":{\"input_tokens\":0,\"output_tokens\":0},"
                + "\"answer\":{\"content\":[{\"type\":\"image\",\"mime_type\":\"image/png\","
                + "\"uri\":null,\"data\":\"%%\"}],\"structured_content\":null}}]}";
        assertThrows(SerializationException.class, () -> AgentMemoryCodec.loads(invalidImage));
    }

    /**
     * 验证旧版计划记忆缺少模型消息时仍可安全恢复。
     */
    @Test
    void loadsLegacyPlanningStepWithoutPromptMessages() {
        String serialized = AgentMemoryCodec.PREFIX
                + "{\"schema_version\":1,\"system_prompt\":\"system\",\"steps\":["
                + "{\"step_type\":\"planning\",\"step_number\":1,"
                + "\"started_at\":\"2024-01-01T00:00:00Z\",\"plan\":\"legacy plan\","
                + "\"token_usage\":{\"input_tokens\":0,\"output_tokens\":0}}]}";

        PlanningStep planning = assertInstanceOf(
                PlanningStep.class,
                AgentMemoryCodec.loads(serialized).snapshot().getFirst()
        );

        assertTrue(planning.modelInputMessages().isEmpty());
        assertEquals("legacy plan", ((ContentPart.Text) planning.modelOutputMessage().content().getFirst()).text());
    }
}
