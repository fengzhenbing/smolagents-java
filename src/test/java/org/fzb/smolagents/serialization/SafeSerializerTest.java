package org.fzb.smolagents.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.SerializationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证安全 JSON 编码、类型标记、兼容恢复和拒绝不安全对象的行为。
 */
public class SafeSerializerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证受支持原语、标准集合、字节和扩展类型可以往返。
     */
    @Test
    void roundTripsSupportedValuesWithoutNativeObjectSerialization() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", "smolagents");
        input.put("integer", 1234567890123456789L);
        input.put("decimal", new BigDecimal("3.141590"));
        input.put("bytes", new byte[]{0, 1, 2, (byte) 255});
        input.put("complex", new SafeSerializer.ComplexValue(1.25, -2.5));
        input.put("date", LocalDate.of(2024, 1, 2));
        input.put("local-time", LocalTime.of(12, 30, 45, 123_000_000));
        input.put("local-datetime", LocalDateTime.of(2024, 1, 2, 12, 30));
        input.put("offset-datetime", OffsetDateTime.parse("2024-01-02T12:30:00+08:00"));
        input.put("duration", Duration.ofNanos(-500_000_000));
        input.put("path", Path.of("cache", "result.json"));
        input.put("list", List.of(1, "two", true));
        input.put("set", new LinkedHashSet<>(List.of("a", "b")));
        input.put("null", null);

        String serialized = SafeSerializer.dumps(input);
        Map<?, ?> result = assertInstanceOf(Map.class, SafeSerializer.loads(serialized));

        assertTrue(serialized.startsWith(SafeSerializer.SAFE_PREFIX));
        assertEquals(input.get("text"), result.get("text"));
        assertEquals(input.get("integer"), result.get("integer"));
        assertEquals(input.get("decimal"), result.get("decimal"));
        assertArrayEquals((byte[]) input.get("bytes"), (byte[]) result.get("bytes"));
        assertEquals(input.get("complex"), result.get("complex"));
        assertEquals(input.get("date"), result.get("date"));
        assertEquals(input.get("local-time"), result.get("local-time"));
        assertEquals(input.get("local-datetime"), result.get("local-datetime"));
        assertEquals(input.get("offset-datetime"), result.get("offset-datetime"));
        assertEquals(input.get("duration"), result.get("duration"));
        assertEquals(input.get("path"), result.get("path"));
        assertEquals(input.get("list"), result.get("list"));
        assertEquals(input.get("set"), result.get("set"));
        assertTrue(result.containsKey("null"));
        assertNull(result.get("null"));
    }

    /**
     * 验证非字符串键以安全 marker 编码，且 Python tuple marker 可恢复为列表键。
     */
    @Test
    void preservesNonStringMapKeysWithTaggedPairs() {
        Map<Object, Object> input = new LinkedHashMap<>();
        input.put(null, "null-key");
        input.put(7, "integer-key");
        input.put(List.of("x", 2), "tuple-key");

        String serialized = SafeSerializer.dumps(input);
        Map<?, ?> result = assertInstanceOf(Map.class, SafeSerializer.loads(serialized));

        assertEquals("null-key", result.get(null));
        assertEquals("integer-key", result.get(7));
        assertEquals("tuple-key", result.get(List.of("x", 2)));
        assertTrue(serialized.contains("dict_with_complex_keys"));
    }

    /**
     * 验证安全模式拒绝任意 POJO、循环引用、pickle 和无前缀旧数据。
     */
    @Test
    void rejectsUnsupportedObjectsCyclesAndUnsafePayloads() {
        assertThrows(SerializationException.class, () -> SafeSerializer.dumps(new CustomPayload()));
        assertThrows(
                SerializationException.class,
                () -> SafeSerializer.dumps(new UntrustedFixture("not opted in"))
        );

        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        assertThrows(SerializationException.class, () -> SafeSerializer.dumps(cycle));

        SerializationException pickle = assertThrows(
                SerializationException.class,
                () -> SafeSerializer.loads("pickle:YWJj")
        );
        assertTrue(pickle.getMessage().contains("Pickle data rejected"));
        assertThrows(SerializationException.class, () -> SafeSerializer.loads("YWJj"));
    }

    /**
     * 验证 Python 兼容 type marker 不会触发类加载，未知 marker 作为普通 Map 保留。
     *
     * @throws Exception 测试 JSON 数据创建失败时抛出
     */
    @Test
    void doesNotInstantiateTaggedClassesAndPreservesUnknownMarkers() throws Exception {
        Object dataclass = SafeSerializer.loads(
                SafeSerializer.SAFE_PREFIX
                        + "{\"__type__\":\"dataclass\",\"class_name\":\"Dangerous\",\"module\":\"evil\","
                        + "\"data\":{\"value\":7}}"
        );
        Map<?, ?> decoded = assertInstanceOf(Map.class, dataclass);
        assertEquals("Dangerous", decoded.get("__dataclass__"));
        assertEquals("evil", decoded.get("__module__"));
        assertEquals(7, decoded.get("value"));

        Object unknown = SafeSerializer.fromJsonSafe(
                MAPPER.readTree("{\"__type__\":\"future\",\"data\":[1,2]}")
        );
        assertInstanceOf(Map.class, unknown);
        assertNotNull(unknown);
    }

    /**
     * 验证 Java record 使用 dataclass 标记编码，且安全解码仅返回字段 Map。
     */
    @Test
    void serializesRecordsWithoutReconstructingTheirClass() {
        SafeSerializerTest.Fixture input = new SafeSerializerTest.Fixture("record-value", 12);

        Map<?, ?> result = assertInstanceOf(Map.class, SafeSerializer.loads(SafeSerializer.dumps(input)));

        assertEquals("Fixture", result.get("__dataclass__"));
        assertEquals("record-value", result.get("name"));
        assertEquals(12, result.get("count"));
    }

    /**
     * 验证空输入、格式错误和损坏的 bytes marker 被明确拒绝。
     *
     * @throws Exception 测试 JSON 数据创建失败时抛出
     */
    @Test
    void reportsMalformedJsonAndTypeMarkers() throws Exception {
        assertThrows(SerializationException.class, () -> SafeSerializer.loads("safe:"));
        assertThrows(SerializationException.class, () -> SafeSerializer.loads("safe:{bad"));
        assertThrows(
                SerializationException.class,
                () -> SafeSerializer.fromJsonSafe(MAPPER.readTree(
                        "{\"__type__\":\"bytes\",\"data\":\"not-valid-base64!!!\"}"
                ))
        );
    }

    /**
     * 用于验证安全序列化器不会反射处理任意应用 POJO 的测试类型。
     */
    private static final class CustomPayload {
    }

    /**
     * 用于验证 Java record 的 safe marker 映射。
     *
     * @param name 名称字段
     * @param count 数值字段
     */
    public record Fixture(String name, int count) implements SafeSerializableRecord {
    }

    /**
     * 用于验证未显式 opt-in 的应用 record 不会触发 accessor 调用。
     *
     * @param value 测试字段
     */
    public record UntrustedFixture(String value) {
        @Override
        public String value() {
            throw new IllegalStateException("must not invoke untrusted accessor");
        }
    }
}
