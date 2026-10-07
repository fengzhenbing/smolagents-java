package org.fzb.smolagents.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fzb.smolagents.core.SerializationException;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 使用带类型标记的 JSON 安全序列化基础数据。
 *
 * <p>支持 JSON 原语、标准集合、字节数组、复数值、日期时间、时长、十进制数和路径。
 * 不执行 Java 对象反序列化、不加载类型名，也不提供 Java 原生序列化或 pickle 回退；
 * 未支持的对象会显式失败。编码前缀与 Python {@code SafeSerializer} 一致。</p>
 */
public final class SafeSerializer {
    /** 用于区分受限 JSON 编码的前缀。 */
    public static final String SAFE_PREFIX = "safe:";
    private static final String PICKLE_PREFIX = "pickle:";
    private static final int MAX_NESTING_DEPTH = 100;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SafeSerializer() {
    }

    /**
     * 将支持的 Java 值转换为 JSON-safe 树。
     *
     * @param value 待转换值
     * @return 只包含 JSON 原语、数组和对象的树
     * @throws SerializationException 遇到不支持的类型、循环引用或过深嵌套时抛出
     */
    public static JsonNode toJsonSafe(Object value) {
        return toJsonSafe(value, new IdentityHashMap<>(), 0);
    }

    /**
     * 将值编码为带 {@code safe:} 前缀的 JSON 字符串。
     *
     * @param value 待编码值
     * @return 安全格式字符串
     */
    public static String dumps(Object value) {
        try {
            return SAFE_PREFIX + MAPPER.writeValueAsString(toJsonSafe(value));
        } catch (JsonProcessingException error) {
            throw new SerializationException("Failed to encode safe JSON", error);
        }
    }

    /**
     * 解码安全 JSON 字符串，不接受 pickle 或无前缀旧数据。
     *
     * @param serialized 带 {@code safe:} 前缀的 JSON 字符串
     * @return 解码后的原语、集合或受支持类型
     * @throws SerializationException 输入非安全格式或编码无效时抛出
     */
    public static Object loads(String serialized) {
        if (serialized == null) {
            throw new SerializationException("Serialized data must not be null");
        }
        if (!serialized.startsWith(SAFE_PREFIX)) {
            String reason = serialized.startsWith(PICKLE_PREFIX)
                    ? "Pickle data rejected: Java safe mode accepts only safe: JSON data."
                    : "Unprefixed legacy data rejected: Java safe mode accepts only safe: JSON data.";
            throw new SerializationException(reason);
        }
        try {
            JsonNode parsed = MAPPER.readTree(serialized.substring(SAFE_PREFIX.length()));
            if (parsed == null) {
                throw new SerializationException("Safe serialization is missing its JSON value");
            }
            return fromJsonSafe(parsed);
        } catch (JsonProcessingException error) {
            throw new SerializationException("Safe serialization contains invalid JSON", error);
        }
    }

    /**
     * 从 JSON-safe 树恢复支持的值；类型标记仅映射到本类显式支持的 JDK 类型。
     *
     * @param value JSON-safe 树节点
     * @return 转换后的值
     * @throws SerializationException 类型标记字段格式错误时抛出
     */
    public static Object fromJsonSafe(JsonNode value) {
        return fromJsonSafe(value, 0);
    }

    /**
     * 递归编码对象，并拒绝任意 POJO 反射序列化。
     *
     * @param value 待转换对象
     * @param active 当前递归路径上的复合对象
     * @param depth 当前嵌套层数
     * @return JSON-compatible 节点
     */
    private static JsonNode toJsonSafe(Object value, IdentityHashMap<Object, Boolean> active, int depth) {
        checkDepth(depth);
        if (value == null) {
            return MAPPER.nullNode();
        }
        if (value instanceof JsonNode node) {
            return fromJsonNode(node, active, depth + 1);
        }
        if (value instanceof String || value instanceof Boolean) {
            return MAPPER.valueToTree(value);
        }
        if (value instanceof Character character) {
            return MAPPER.getNodeFactory().textNode(character.toString());
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger) {
            return MAPPER.valueToTree(value);
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                throw unsupported(value, "non-finite floating point numbers are not portable JSON values");
            }
            return MAPPER.valueToTree(number);
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw unsupported(value, "non-finite floating point numbers are not portable JSON values");
            }
            return MAPPER.valueToTree(number);
        }
        if (value instanceof byte[] bytes) {
            return marker("bytes").put("data", Base64.getEncoder().encodeToString(bytes));
        }
        if (value instanceof ComplexValue complex) {
            return marker("complex")
                    .put("real", complex.real())
                    .put("imag", complex.imaginary());
        }
        if (value instanceof LocalDateTime dateTime) {
            return marker("datetime").put("data", dateTime.toString());
        }
        if (value instanceof OffsetDateTime dateTime) {
            return marker("datetime").put("data", dateTime.toString());
        }
        if (value instanceof ZonedDateTime dateTime) {
            return marker("datetime").put("data", dateTime.toString());
        }
        if (value instanceof LocalDate date) {
            return marker("date").put("data", date.toString());
        }
        if (value instanceof OffsetTime time) {
            return marker("time").put("data", time.toString());
        }
        if (value instanceof LocalTime time) {
            return marker("time").put("data", time.toString());
        }
        if (value instanceof Duration duration) {
            BigDecimal totalSeconds = BigDecimal.valueOf(duration.getSeconds())
                    .add(BigDecimal.valueOf(duration.getNano(), 9));
            return marker("timedelta").put("total_seconds", totalSeconds);
        }
        if (value instanceof BigDecimal decimal) {
            return marker("Decimal").put("data", decimal.toPlainString());
        }
        if (value instanceof Path path) {
            return marker("Path").put("data", path.toString());
        }
        if (value.getClass().isRecord() && value instanceof SafeSerializableRecord) {
            return withCycleCheck(value, active, () -> serializeRecord(value, active, depth));
        }
        if (value.getClass().isArray()) {
            return withCycleCheck(value, active, () -> {
                ArrayNode array = MAPPER.createArrayNode();
                int length = Array.getLength(value);
                for (int index = 0; index < length; index++) {
                    array.add(toJsonSafe(Array.get(value, index), active, depth + 1));
                }
                return array;
            });
        }
        if (value instanceof List<?> list) {
            requireStandardCollection(value);
            return withCycleCheck(value, active, () -> {
                ArrayNode array = MAPPER.createArrayNode();
                for (Object item : list) {
                    array.add(toJsonSafe(item, active, depth + 1));
                }
                return array;
            });
        }
        if (value instanceof Set<?> set) {
            requireStandardCollection(value);
            return withCycleCheck(value, active, () -> {
                ArrayNode data = MAPPER.createArrayNode();
                for (Object item : set) {
                    data.add(toJsonSafe(item, active, depth + 1));
                }
                return marker("set").set("data", data);
            });
        }
        if (value instanceof Map<?, ?> map) {
            requireStandardCollection(value);
            return withCycleCheck(value, active, () -> serializeMap(map, active, depth));
        }
        throw unsupported(value, "only JSON primitives and explicitly supported JDK value types are allowed");
    }

    /**
     * 递归转换 Jackson JSON 节点，并拒绝其内部携带的 POJO 节点。
     *
     * @param node 待转换节点
     * @param active 当前递归路径
     * @param depth 当前嵌套层数
     * @return 深度复制且通过校验的节点
     */
    private static JsonNode fromJsonNode(JsonNode node, IdentityHashMap<Object, Boolean> active, int depth) {
        checkDepth(depth);
        if (node == null || node.isNull()) {
            return MAPPER.nullNode();
        }
        if (node.isTextual()) {
            return MAPPER.getNodeFactory().textNode(node.asText());
        }
        if (node.isBoolean()) {
            return MAPPER.getNodeFactory().booleanNode(node.asBoolean());
        }
        if (node.isNumber()) {
            return MAPPER.valueToTree(node.numberValue());
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            node.forEach(item -> result.add(fromJsonNode(item, active, depth + 1)));
            return result;
        }
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            node.fields().forEachRemaining(entry ->
                    result.set(entry.getKey(), fromJsonNode(entry.getValue(), active, depth + 1)));
            return result;
        }
        throw new SerializationException("Unsupported Jackson node type: " + node.getNodeType());
    }

    /**
     * 将 Java Map 编码为普通 JSON object 或保留非字符串键的 marker。
     *
     * @param map 待编码映射
     * @param active 当前递归路径
     * @param depth 当前嵌套层数
     * @return JSON 对象节点
     */
    private static JsonNode serializeMap(
            Map<?, ?> map,
            IdentityHashMap<Object, Boolean> active,
            int depth
    ) {
        boolean hasOnlyStringKeys = map.keySet().stream().allMatch(String.class::isInstance);
        if (hasOnlyStringKeys) {
            ObjectNode result = MAPPER.createObjectNode();
            map.forEach((key, item) ->
                    result.set((String) key, toJsonSafe(item, active, depth + 1)));
            return result;
        }
        ArrayNode entries = MAPPER.createArrayNode();
        map.forEach((key, item) -> {
            ArrayNode pair = MAPPER.createArrayNode();
            pair.add(toJsonSafe(key, active, depth + 1));
            pair.add(toJsonSafe(item, active, depth + 1));
            entries.add(pair);
        });
        return marker("dict_with_complex_keys").set("data", entries);
    }

    /**
     * 将 Java record 转为 Python-compatible dataclass marker；解码后仅返回 Map。
     *
     * @param value 待转换的 record
     * @param active 当前递归路径
     * @param depth 当前嵌套层数
     * @return dataclass marker 节点
     */
    private static JsonNode serializeRecord(
            Object value,
            IdentityHashMap<Object, Boolean> active,
            int depth
    ) {
        ObjectNode data = MAPPER.createObjectNode();
        try {
            for (java.lang.reflect.RecordComponent component : value.getClass().getRecordComponents()) {
                Object field = component.getAccessor().invoke(value);
                data.set(component.getName(), toJsonSafe(field, active, depth + 1));
            }
        } catch (IllegalAccessException | InvocationTargetException error) {
            throw new SerializationException("Cannot safely read record components", error);
        }
        Package typePackage = value.getClass().getPackage();
        String module = typePackage == null ? "" : typePackage.getName();
        return marker("dataclass")
                .put("class_name", value.getClass().getSimpleName())
                .put("module", module)
                .set("data", data);
    }

    /**
     * 按允许列表约束集合实现，避免调用任意用户定义容器的迭代器。
     *
     * @param value 待校验集合
     */
    private static void requireStandardCollection(Object value) {
        Package typePackage = value.getClass().getPackage();
        if (typePackage == null || !"java.util".equals(typePackage.getName())) {
            throw unsupported(value, "custom collection implementations are not allowed");
        }
    }

    /**
     * 在当前递归路径中检测对象环，并在转换完成后移除路径标记。
     *
     * @param value 复合对象
     * @param active 当前递归路径
     * @param conversion 当前节点转换逻辑
     * @return 转换后的节点
     */
    private static JsonNode withCycleCheck(
            Object value,
            IdentityHashMap<Object, Boolean> active,
            NodeConversion conversion
    ) {
        if (active.put(value, Boolean.TRUE) != null) {
            throw new SerializationException("Circular references are not supported");
        }
        try {
            return conversion.convert();
        } finally {
            active.remove(value);
        }
    }

    /**
     * 递归恢复 JSON 节点，且只创建预定义 JDK 类型。
     *
     * @param node 已解析 JSON 节点
     * @param depth 当前嵌套层数
     * @return 恢复后的值
     */
    private static Object fromJsonSafe(JsonNode node, int depth) {
        checkDepth(depth);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(item -> values.add(fromJsonSafe(item, depth + 1)));
            return values;
        }
        if (!node.isObject()) {
            throw new SerializationException("Unsupported JSON node type: " + node.getNodeType());
        }
        String type = node.path("__type__").asText(null);
        if (type == null) {
            return decodeObject(node, depth);
        }
        return switch (type) {
            case "bytes" -> decodeBytes(requiredText(node, "data"));
            case "complex" -> new ComplexValue(requiredNumber(node, "real"), requiredNumber(node, "imag"));
            case "tuple" -> Collections.unmodifiableList(decodeArray(node, "data", depth));
            case "set" -> new LinkedHashSet<>(decodeArray(node, "data", depth));
            case "frozenset" -> Set.copyOf(decodeArray(node, "data", depth));
            case "dict_with_complex_keys" -> decodeComplexKeyMap(node, depth);
            case "datetime" -> decodeDateTime(requiredText(node, "data"));
            case "date" -> parse(LocalDate::parse, requiredText(node, "data"), "date");
            case "time" -> decodeTime(requiredText(node, "data"));
            case "timedelta" -> decodeDuration(requiredNumberNode(node, "total_seconds"));
            case "Decimal" -> parse(BigDecimal::new, requiredText(node, "data"), "Decimal");
            case "Path" -> Path.of(requiredText(node, "data"));
            case "ndarray" -> decodeArray(node, "data", depth);
            case "PIL.Image" -> decodeImageMarker(node);
            case "dataclass" -> decodeDataclass(node, depth);
            default -> decodeObject(node, depth);
        };
    }

    /**
     * 恢复普通 JSON object 为有序 Java Map。
     *
     * @param node JSON object
     * @param depth 当前嵌套层数
     * @return 可变有序映射
     */
    private static Map<String, Object> decodeObject(JsonNode node, int depth) {
        Map<String, Object> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry ->
                result.put(entry.getKey(), fromJsonSafe(entry.getValue(), depth + 1)));
        return result;
    }

    /**
     * 恢复携带任意安全键和值的映射。
     *
     * @param node 复杂键字典 marker
     * @param depth 当前嵌套层数
     * @return 有序映射
     */
    private static Map<Object, Object> decodeComplexKeyMap(JsonNode node, int depth) {
        JsonNode entries = requiredArray(node, "data");
        Map<Object, Object> result = new LinkedHashMap<>();
        for (JsonNode pair : entries) {
            if (!pair.isArray() || pair.size() != 2) {
                throw new SerializationException("dict_with_complex_keys entries must be two-item arrays");
            }
            result.put(fromJsonSafe(pair.get(0), depth + 1), fromJsonSafe(pair.get(1), depth + 1));
        }
        return result;
    }

    /**
     * 解码 Python-compatible bytes marker。
     *
     * @param value Base64 字符串
     * @return 字节数组
     */
    private static byte[] decodeBytes(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new SerializationException("bytes marker contains invalid base64 data", error);
        }
    }

    /**
     * 恢复序列化 dataclass 时的安全字典表示，不实例化原始类。
     *
     * @param node dataclass marker
     * @param depth 当前嵌套层数
     * @return 包含模块/类名元数据的普通 Map
     */
    private static Map<String, Object> decodeDataclass(JsonNode node, int depth) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("__dataclass__", requiredText(node, "class_name"));
        result.put("__module__", requiredText(node, "module"));
        JsonNode data = node.get("data");
        if (data == null || !data.isObject()) {
            throw new SerializationException("dataclass marker requires object data");
        }
        data.fields().forEachRemaining(entry ->
                result.put(entry.getKey(), fromJsonSafe(entry.getValue(), depth + 1)));
        return result;
    }

    /**
     * 在不依赖图像库的情况下保留 Python image marker 为安全 Map。
     *
     * @param node image marker
     * @return marker 和 base64 数据的 Map
     */
    private static Map<String, Object> decodeImageMarker(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("__type__", "PIL.Image");
        result.put("data", requiredText(node, "data"));
        return result;
    }

    /**
     * 恢复预期为数组的 marker 字段。
     *
     * @param node 类型 marker object
     * @param field 数据字段名
     * @param depth 当前嵌套层数
     * @return 解码后的数组值
     */
    private static List<Object> decodeArray(JsonNode node, String field, int depth) {
        JsonNode array = requiredArray(node, field);
        List<Object> result = new ArrayList<>();
        array.forEach(item -> result.add(fromJsonSafe(item, depth + 1)));
        return result;
    }

    /**
     * 解码日期时间，保留有时区和无时区两种表示。
     *
     * @param value ISO-8601 字符串
     * @return OffsetDateTime、ZonedDateTime 或 LocalDateTime
     */
    private static Object decodeDateTime(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return ZonedDateTime.parse(value);
            } catch (DateTimeParseException ignoredAgain) {
                return parse(LocalDateTime::parse, value, "datetime");
            }
        }
    }

    /**
     * 解码本地时间或带时区偏移的时间。
     *
     * @param value ISO-8601 时间字符串
     * @return OffsetTime 或 LocalTime
     */
    private static Object decodeTime(String value) {
        try {
            return OffsetTime.parse(value);
        } catch (DateTimeParseException ignored) {
            return parse(LocalTime::parse, value, "time");
        }
    }

    /**
     * 按 total_seconds 浮点数恢复 Java Duration。
     *
     * @param totalSeconds seconds 数值节点
     * @return 纳秒精度 Duration
     */
    private static Duration decodeDuration(JsonNode totalSeconds) {
        BigDecimal nanos = decimalValue(totalSeconds)
                .movePointRight(9)
                .setScale(0, RoundingMode.HALF_UP);
        BigInteger totalNanos = nanos.toBigIntegerExact();
        BigInteger[] parts = totalNanos.divideAndRemainder(BigInteger.valueOf(1_000_000_000L));
        try {
            return Duration.ofSeconds(parts[0].longValueExact(), parts[1].longValueExact());
        } catch (ArithmeticException error) {
            throw new SerializationException("timedelta value is outside Java Duration range", error);
        }
    }

    /**
     * 将已知结构的日期值解析失败转换为安全序列化异常。
     *
     * @param parser 标准库解析函数
     * @param value ISO 字符串
     * @param type 类型名称
     * @return 解析后的类型值
     */
    private static <T> T parse(java.util.function.Function<String, T> parser, String value, String type) {
        try {
            return parser.apply(value);
        } catch (DateTimeParseException | NumberFormatException error) {
            throw new SerializationException("Invalid " + type + " value", error);
        }
    }

    /**
     * 读取类型 marker 中的非空文本字段。
     *
     * @param node JSON object
     * @param field 必填字段名
     * @return 文本值
     */
    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new SerializationException("Type marker requires textual '" + field + "'");
        }
        return value.asText();
    }

    /**
     * 读取类型 marker 中的有限数值字段。
     *
     * @param node JSON object
     * @param field 必填数值字段名
     * @return 数值字段
     */
    private static double requiredNumber(JsonNode node, String field) {
        return requiredNumberNode(node, field).doubleValue();
    }

    /**
     * 读取类型 marker 中的数值节点。
     *
     * @param node JSON object
     * @param field 必填数值字段名
     * @return 数值节点
     */
    private static JsonNode requiredNumberNode(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new SerializationException("Type marker requires finite numeric '" + field + "'");
        }
        return value;
    }

    /**
     * 转换 Jackson 数值节点为精确十进制数。
     *
     * @param value JSON number
     * @return BigDecimal
     */
    private static BigDecimal decimalValue(JsonNode value) {
        if (value.isBigDecimal()) {
            return value.decimalValue();
        }
        if (value.isIntegralNumber()) {
            return new BigDecimal(value.bigIntegerValue());
        }
        return BigDecimal.valueOf(value.doubleValue());
    }

    /**
     * 读取包含数组值的 marker 字段。
     *
     * @param node marker object
     * @param field 数组字段名
     * @return 数组节点
     */
    private static JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new SerializationException("Type marker requires array '" + field + "'");
        }
        return value;
    }

    /**
     * 创建带有指定 type marker 的 JSON object。
     *
     * @param type 类型标签
     * @return 含有 __type__ 字段的 object
     */
    private static ObjectNode marker(String type) {
        return MAPPER.createObjectNode().put("__type__", type);
    }

    /**
     * 校验递归最大深度，避免深层输入造成栈耗尽。
     *
     * @param depth 当前深度
     */
    private static void checkDepth(int depth) {
        if (depth > MAX_NESTING_DEPTH) {
            throw new SerializationException("Maximum safe serialization nesting depth exceeded");
        }
    }

    /**
     * 创建包含失败类型的安全序列化异常。
     *
     * @param value 不支持的对象
     * @param detail 附加诊断
     * @return 安全序列化异常
     */
    private static SerializationException unsupported(Object value, String detail) {
        return new SerializationException(
                "Cannot safely serialize object of type " + value.getClass().getName() + ": " + detail
        );
    }

    /**
     * 表示 Python {@code complex} 类型的安全 Java 值。
     *
     * @param real 实部
     * @param imaginary 虚部
     */
    public record ComplexValue(double real, double imaginary) {
        /**
         * 拒绝非有限复数值，确保可编码为标准 JSON number。
         */
        public ComplexValue {
            if (!Double.isFinite(real) || !Double.isFinite(imaginary)) {
                throw new IllegalArgumentException("complex components must be finite");
            }
        }
    }

    /**
     * 延迟计算当前 JSON 节点的私有转换回调。
     */
    @FunctionalInterface
    private interface NodeConversion {
        /**
         * 执行容器节点转换。
         *
         * @return 转换后的 JSON 节点
         */
        JsonNode convert();
    }
}
