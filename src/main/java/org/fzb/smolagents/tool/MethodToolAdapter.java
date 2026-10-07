package org.fzb.smolagents.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fzb.smolagents.core.AgentRunException;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.SerializationException;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.core.ToolValidationException;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.serialization.SafeSerializer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 将显式声明的 Java 实例方法转换为 JSON-schema-backed {@link Tool}。
 *
 * <p>反射只用于找到带 {@link ToolDefinition} 的公开方法；方法签名必须使用带
 * {@link ToolArgument} 注解的参数。输入仅支持有限的原语/字符串类型，避免未经配置的
 * 类型构造、任意 JSON-to-POJO 映射或类加载。</p>
 */
public final class MethodToolAdapter implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<Class<?>> SUPPORTED_TYPES = Set.of(
            String.class,
            boolean.class,
            Boolean.class,
            int.class,
            Integer.class,
            long.class,
            Long.class,
            double.class,
            Double.class,
            float.class,
            Float.class,
            short.class,
            Short.class,
            byte.class,
            Byte.class,
            BigInteger.class,
            BigDecimal.class
    );

    private final Object target;
    private final Method method;
    private final List<Parameter> parameters;
    private final ToolSpec spec;

    /**
     * 将目标对象的某个公开方法适配为单个 Agent tool。
     *
     * @param target 实例方法所属对象
     * @param methodName 带 {@link ToolDefinition} 的方法名称
     * @throws ConfigurationException 方法缺失、重载歧义或签名不支持时抛出
     */
    public MethodToolAdapter(Object target, String methodName) {
        this.target = Objects.requireNonNull(target, "target");
        if (methodName == null || methodName.isBlank()) {
            throw new ConfigurationException("methodName must not be blank");
        }
        List<Method> candidates = Arrays.stream(target.getClass().getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .filter(candidate -> candidate.isAnnotationPresent(ToolDefinition.class))
                .toList();
        if (candidates.size() != 1) {
            throw new ConfigurationException(
                    "Expected exactly one public @ToolDefinition method named '" + methodName
                            + "', found " + candidates.size()
            );
        }
        this.method = candidates.getFirst();
        if (Modifier.isStatic(method.getModifiers())
                || method.isVarArgs()
                || !Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
            throw new ConfigurationException("Tool method must be a non-static, non-varargs public instance method");
        }
        this.parameters = inspectParameters(method);
        ToolDefinition definition = method.getAnnotation(ToolDefinition.class);
        if (definition.name().isBlank()) {
            throw new ConfigurationException("ToolDefinition.name must not be blank");
        }
        this.spec = new ToolSpec(
                definition.name(),
                definition.description(),
                createSchema(parameters),
                true,
                outputType(method.getReturnType())
        );
    }

    /**
     * 创建目标上所有已显式注解且可公开调用的工具。
     *
     * @param target 方法所属对象
     * @return 按工具名称排序的工具适配器
     */
    public static List<MethodToolAdapter> discover(Object target) {
        Objects.requireNonNull(target, "target");
        return Arrays.stream(target.getClass().getMethods())
                .filter(method -> method.isAnnotationPresent(ToolDefinition.class))
                .map(method -> new MethodToolAdapter(target, method.getName()))
                .sorted(java.util.Comparator.comparing(tool -> tool.spec().name()))
                .toList();
    }

    /**
     * 返回适配后工具名称、描述和 JSON Schema。
     *
     * @return 模型可见工具定义
     */
    @Override
    public ToolSpec spec() {
        return spec;
    }

    /**
     * 严格转换 schema 校验后的参数并调用已固定的公开方法。
     *
     * @param arguments 已通过工具参数校验的 JSON 对象
     * @param context 当前调用上下文
     * @return 方法返回值对应的文本/JSON 工具结果
     */
    @Override
    public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
        Objects.requireNonNull(context, "context");
        context.cancellation().throwIfCancelled();
        Object[] values = new Object[parameters.size()];
        for (int index = 0; index < parameters.size(); index++) {
            Parameter parameter = parameters.get(index);
            JsonNode value = arguments.get(parameter.definition().name());
            if (value == null && parameter.defaultValue() != null) {
                value = parameter.defaultValue();
            }
            if (value == null) {
                values[index] = null;
            } else if (value.isNull()) {
                if (!parameter.definition().nullable()) {
                    throw new ToolExecutionException(
                            "Argument '" + parameter.definition().name() + "' must not be null"
                    );
                }
                values[index] = null;
            } else {
                values[index] = convertValue(value, parameter);
            }
        }
        try {
            Object result = method.invoke(target, values);
            if (result == null) {
                return ToolResult.text("");
            }
            if (result instanceof String text) {
                return ToolResult.text(text);
            }
            JsonNode encoded;
            try {
                encoded = SafeSerializer.toJsonSafe(result);
            } catch (SerializationException serializationFailure) {
                throw new ToolExecutionException(
                        "Tool method returned a value that cannot be safely serialized",
                        serializationFailure
                );
            }
            return new ToolResult(List.of(new ContentPart.Json(encoded)), encoded);
        } catch (IllegalAccessException error) {
            throw new ToolExecutionException("Unable to access configured tool method", error);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ToolExecutionException executionFailure) {
                throw executionFailure;
            }
            if (cause instanceof AgentRunException runFailure) {
                throw runFailure;
            }
            if (cause instanceof RuntimeException runtime) {
                throw new ToolExecutionException(
                        "Configured tool method failed: "
                                + Objects.toString(cause.getMessage(), cause.getClass().getName()),
                        runtime
                );
            }
            throw new ToolExecutionException(
                    "Configured tool method failed: " + Objects.toString(cause.getMessage(), cause.getClass().getName()),
                    cause
            );
        } catch (IllegalArgumentException error) {
            throw new ToolExecutionException("Tool arguments do not match the configured method signature", error);
        }
    }

    /**
     * 校验每个方法参数有唯一名称并使用受支持类型。
     *
     * @param method 工具方法
     * @return 不可变参数元数据
     */
    private static List<Parameter> inspectParameters(Method method) {
        List<Parameter> parameters = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (java.lang.reflect.Parameter parameter : method.getParameters()) {
            ToolArgument definition = parameter.getAnnotation(ToolArgument.class);
            if (definition == null) {
                throw new ConfigurationException(
                        "Every tool method parameter must have a @ToolArgument annotation: " + parameter.getName()
                );
            }
            if (definition.name().isBlank() || !names.add(definition.name())) {
                throw new ConfigurationException("Tool argument names must be non-empty and unique");
            }
            Class<?> rawType = parameter.getType();
            Class<?> schemaType = rawType.isPrimitive() ? primitiveBoxType(rawType) : rawType;
            if (!SUPPORTED_TYPES.contains(schemaType)) {
                throw new ConfigurationException(
                        "Unsupported tool method parameter type: " + parameter.getParameterizedType().getTypeName()
                );
            }
            if (definition.nullable() && rawType.isPrimitive()) {
                throw new ConfigurationException("Primitive tool parameters cannot be nullable");
            }
            if (!definition.required() && rawType.isPrimitive()) {
                throw new ConfigurationException("Optional tool parameters must use a boxed Java type");
            }
            if (!definition.required() && !definition.nullable() && !definition.hasDefault()) {
                throw new ConfigurationException(
                        "Optional non-nullable tool parameters must declare a JSON default: "
                                + definition.name()
                );
            }
            parameters.add(new Parameter(schemaType, definition, parseDefaultValue(schemaType, definition)));
        }
        return List.copyOf(parameters);
    }

    /**
     * 解析并按参数 schema 校验 annotation 中可选的 JSON 默认值。
     *
     * @param type 参数包装类型
     * @param definition 参数 annotation
     * @return 默认 JSON 值；未配置时返回 null
     */
    private static JsonNode parseDefaultValue(Class<?> type, ToolArgument definition) {
        if (!definition.hasDefault()) {
            return null;
        }
        if (definition.required()) {
            throw new ConfigurationException(
                    "A parameter with a default value must not be marked required: " + definition.name()
            );
        }
        JsonNode defaultValue;
        try {
            defaultValue = MAPPER.readTree(definition.defaultValueJson());
        } catch (JsonProcessingException error) {
            throw new ConfigurationException(
                    "ToolArgument defaultValueJson is invalid for parameter " + definition.name(),
                    error
            );
        }
        if (defaultValue == null) {
            throw new ConfigurationException(
                    "ToolArgument defaultValueJson must contain a JSON value for parameter " + definition.name()
            );
        }

        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        ObjectNode property = MAPPER.createObjectNode().put("type", schemaType(type));
        if (definition.nullable()) {
            property.set("type", MAPPER.createArrayNode().add(schemaType(type)).add("null"));
        }
        schema.set("properties", MAPPER.createObjectNode().set(definition.name(), property));
        schema.putArray("required").add(definition.name());
        schema.put("additionalProperties", false);
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.set(definition.name(), defaultValue);
        try {
            new JsonSchemaToolArgumentValidator().validate(schema, arguments);
        } catch (ToolValidationException invalidDefault) {
            throw new ConfigurationException(
                    "ToolArgument defaultValueJson does not match parameter " + definition.name(),
                    invalidDefault
            );
        }
        return defaultValue.deepCopy();
    }

    /**
     * 将受支持的 Java 返回值类型映射到 Python Tool 的 output_type。
     *
     * @param returnType 工具方法的返回类型
     * @return 兼容的工具输出类型
     */
    private static String outputType(Class<?> returnType) {
        if (returnType == void.class || returnType == Void.class) {
            return "null";
        }
        if (CharSequence.class.isAssignableFrom(returnType)) {
            return "string";
        }
        if (returnType == boolean.class || returnType == Boolean.class) {
            return "boolean";
        }
        if (returnType == byte.class || returnType == Byte.class
                || returnType == short.class || returnType == Short.class
                || returnType == int.class || returnType == Integer.class
                || returnType == long.class || returnType == Long.class
                || returnType == BigInteger.class) {
            return "integer";
        }
        if (returnType == float.class || returnType == Float.class
                || returnType == double.class || returnType == Double.class
                || returnType == BigDecimal.class) {
            return "number";
        }
        if (java.util.Map.class.isAssignableFrom(returnType)) {
            return "object";
        }
        if (Iterable.class.isAssignableFrom(returnType) || returnType.isArray()) {
            return "array";
        }
        return "any";
    }

    /**
     * 创建工具参数 object schema。
     *
     * @param parameters 反射提取的参数定义
     * @return JSON Schema object
     */
    private static JsonNode createSchema(List<Parameter> parameters) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        ArrayNode required = MAPPER.createArrayNode();
        for (Parameter parameter : parameters) {
            ToolArgument definition = parameter.definition();
            ObjectNode property = MAPPER.createObjectNode()
                    .put("type", schemaType(parameter.type()))
                    .put("description", definition.description());
            if (definition.nullable()) {
                property.set("type", MAPPER.createArrayNode().add(schemaType(parameter.type())).add("null"));
            }
            if (parameter.defaultValue() != null) {
                property.set("default", parameter.defaultValue().deepCopy());
            }
            properties.set(definition.name(), property);
            if (definition.required()) {
                required.add(definition.name());
            }
        }
        schema.set("properties", properties);
        schema.set("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 将 JSON 节点严格转换为方法参数值。
     *
     * @param value JSON argument value
     * @param parameter 方法参数元数据
     * @return 已转换值
     */
    private static Object convertValue(JsonNode value, Parameter parameter) {
        Class<?> type = parameter.type();
        if (type == String.class) {
            return value.textValue();
        }
        if (type == Boolean.class) {
            return value.booleanValue();
        }
        if (type == BigInteger.class) {
            return value.bigIntegerValue();
        }
        if (type == BigDecimal.class) {
            return value.decimalValue();
        }
        if (type == Integer.class) {
            if (!value.canConvertToInt()) {
                throw outOfRange(parameter);
            }
            return value.intValue();
        }
        if (type == Long.class) {
            if (!value.canConvertToLong()) {
                throw outOfRange(parameter);
            }
            return value.longValue();
        }
        if (type == Short.class) {
            if (!value.canConvertToInt()
                    || value.intValue() < Short.MIN_VALUE
                    || value.intValue() > Short.MAX_VALUE) {
                throw outOfRange(parameter);
            }
            return value.shortValue();
        }
        if (type == Byte.class) {
            if (!value.canConvertToInt()
                    || value.intValue() < Byte.MIN_VALUE
                    || value.intValue() > Byte.MAX_VALUE) {
                throw outOfRange(parameter);
            }
            return (byte) value.intValue();
        }
        if (type == Double.class) {
            double result = value.doubleValue();
            if (!Double.isFinite(result)) {
                throw outOfRange(parameter);
            }
            return result;
        }
        if (type == Float.class) {
            float result = value.floatValue();
            if (!Float.isFinite(result)) {
                throw outOfRange(parameter);
            }
            return result;
        }
        throw new ToolExecutionException("Unsupported parameter conversion for " + type.getName());
    }

    /**
     * 创建指出越界参数名的执行异常。
     *
     * @param parameter 参数元数据
     * @return 执行异常
     */
    private static ToolExecutionException outOfRange(Parameter parameter) {
        return new ToolExecutionException("Argument '" + parameter.definition().name() + "' is out of range");
    }

    /**
     * 返回 schema 使用的 JSON primitive type。
     *
     * @param type 已归一化参数类型
     * @return JSON Schema type
     */
    private static String schemaType(Class<?> type) {
        if (type == String.class) {
            return "string";
        }
        if (type == Boolean.class) {
            return "boolean";
        }
        if (Set.of(Integer.class, Long.class, Short.class, Byte.class, BigInteger.class).contains(type)) {
            return "integer";
        }
        if (Set.of(Double.class, Float.class, BigDecimal.class).contains(type)) {
            return "number";
        }
        throw new ConfigurationException("Unsupported parameter schema type: " + type.getName());
    }

    /**
     * 将 Java primitive 规范化到受支持 wrapper。
     *
     * @param type 原始参数类型
     * @return 对应 wrapper 或原类型
     */
    private static Class<?> primitiveBoxType(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        return type;
    }

    /**
     * 保存 Java 参数原始类型与模型可见字段定义。
     *
     * @param type 参数包装类型
     * @param definition JSON 参数 annotation
     * @param defaultValue 可选且已校验的 JSON 默认值
     */
    private record Parameter(Class<?> type, ToolArgument definition, JsonNode defaultValue) {
    }
}
