package org.fzb.smolagents.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.agent.CodeBlockTags;
import org.fzb.smolagents.core.ModelOutputException;
import org.fzb.smolagents.serialization.SafeSerializer;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提供与 Agent 提示、工具名称和文本输出相关的安全纯 Java 辅助函数。
 *
 * <p>此类不执行 Python AST 解析、任意对象反射或 shell command validation；对无法安全
 * 转换的数据类型会显式失败。</p>
 */
public final class AgentUtils {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_LENGTH = 20_000;
    private static final String TRUNCATION_MESSAGE =
            "\n..._This content has been truncated to stay below %d characters_...\n";
    private static final Pattern MARKDOWN_CODE =
            Pattern.compile("(?is)```(?:python|py)?[ \\t]*\\R(.*?)```");
    private static final Pattern XML_CODE = Pattern.compile("(?is)<code>(.*?)</code>");
    private static final Set<String> PYTHON_KEYWORDS = Set.of(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
            "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
            "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
            "return", "try", "while", "with", "yield", "match", "case", "type"
    );

    private AgentUtils() {
    }

    /**
     * 将可见内容限制到指定前后片段长度，并附带截断说明。
     *
     * <p>内容不超过上限时会原样返回。超过上限时保留首尾各 {@code maxLength / 2} 字符；
     * 提示文字额外附加在片段之外，与 Python 实现相同。</p>
     *
     * @param content 输入文本
     * @param maxLength 首尾内容的最大总字符数
     * @return 原内容或保留头尾片段的截断文本
     */
    public static String truncateContent(String content, int maxLength) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (maxLength < 1) {
            throw new IllegalArgumentException("maxLength must be positive");
        }
        int contentLength = content.codePointCount(0, content.length());
        if (contentLength <= maxLength) {
            return content;
        }
        int half = maxLength / 2;
        int headEnd = content.offsetByCodePoints(0, half);
        int tailStart = content.offsetByCodePoints(0, contentLength - half);
        String message = TRUNCATION_MESSAGE.formatted(maxLength);
        return content.substring(0, headEnd) + message + content.substring(tailStart);
    }

    /**
     * 使用 20,000 字符默认保留长度截断文本。
     *
     * @param content 输入文本
     * @return 截断结果
     */
    public static String truncateContent(String content) {
        return truncateContent(content, DEFAULT_MAX_LENGTH);
    }

    /**
     * 从模型输出中提取自定义 code-block tags 或默认 Python Markdown fence 的代码。
     *
     * @param text 模型原始文本
     * @param codeBlockTags 可选 literal tags；null 时直接使用默认 Python fence
     * @return 合并后的代码片段；未发现代码块时返回 null
     */
    public static String extractCodeFromText(String text, CodeBlockTags codeBlockTags) {
        if (text == null) {
            return null;
        }
        if (codeBlockTags != null) {
            String custom = collectLiteralBlocks(text, codeBlockTags);
            if (!custom.isEmpty()) {
                return custom;
            }
        }
        Matcher matcher = MARKDOWN_CODE.matcher(text);
        String markdown = collectRegexBlocks(matcher);
        if (!markdown.isEmpty()) {
            return markdown;
        }
        String xml = collectRegexBlocks(XML_CODE.matcher(text));
        return xml.isEmpty() ? null : xml;
    }

    /**
     * 从模型输出中提取默认 Markdown Python/py 代码块。
     *
     * @param text 模型原始文本
     * @return 合并后的代码片段；未发现代码块时返回 null
     */
    public static String extractCodeFromText(String text) {
        return extractCodeFromText(text, null);
    }

    /**
     * 从任意文字前后包裹的模型输出中解析第一个完整 JSON object。
     *
     * <p>JSON 字符串中的转义引号与嵌套大括号由扫描状态机正确处理；无法找到完整 object
     * 或 object JSON 无效时抛出模型输出错误。返回结构保留 object 前后文本。</p>
     *
     * @param text 待解析文本
     * @return JSON object 及其前缀、后缀文本
     */
    public static ParsedJsonBlob parseJsonBlob(String text) {
        if (text == null) {
            throw new ModelOutputException("model output is missing its JSON blob");
        }
        int start = text.indexOf('{');
        if (start < 0) {
            throw new ModelOutputException("model output does not contain a JSON object");
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    String candidate = text.substring(start, index + 1);
                    try {
                        JsonNode value = MAPPER.readTree(candidate);
                        if (value == null || !value.isObject()) {
                            throw new ModelOutputException("model output JSON blob must be an object");
                        }
                        return new ParsedJsonBlob(
                                value,
                                text.substring(0, start),
                                text.substring(index + 1)
                        );
                    } catch (JsonProcessingException error) {
                        throw new ModelOutputException("model output JSON blob is invalid", error);
                    }
                }
            }
        }
        throw new ModelOutputException("model output JSON blob is incomplete");
    }

    /**
     * 安全地将支持的 JSON-compatible 值转换为 JsonNode。
     *
     * @param value 待转换值
     * @return JSON node
     * @throws org.fzb.smolagents.core.SerializationException 含不受支持类型时抛出
     */
    public static JsonNode makeJsonSerializable(Object value) {
        return SafeSerializer.toJsonSafe(value);
    }

    /**
     * 校验字符串是否为 Python 标识符且不是 Python 关键字。
     *
     * @param name 待校验名称
     * @return 合法标识符时为 true
     */
    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || PYTHON_KEYWORDS.contains(name)) {
            return false;
        }
        int first = name.codePointAt(0);
        if (first != '_' && !Character.isUnicodeIdentifierStart(first)) {
            return false;
        }
        for (int offset = Character.charCount(first); offset < name.length();) {
            int codePoint = name.codePointAt(offset);
            if (!Character.isUnicodeIdentifierPart(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    /**
     * 将任意支持输入转换为净化后的纯文本，以免在终端中注入控制序列。
     *
     * @param value 字符串、byte[] 或其它对象
     * @return ASCII 控制字符可见转义后的文本
     */
    public static String sanitizeForConsole(Object value) {
        String text;
        if (value == null) {
            text = "";
        } else if (value instanceof byte[] bytes) {
            text = decodeUtf8(bytes);
        } else if (value instanceof String string) {
            text = string;
        } else {
            text = String.valueOf(value);
        }
        StringBuilder safe = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\t' || codePoint == '\r') {
                safe.appendCodePoint(codePoint);
            } else if (codePoint < 0x20 || codePoint == 0x7f) {
                safe.append("\\x").append(String.format(java.util.Locale.ROOT, "%02x", codePoint));
            } else {
                safe.appendCodePoint(codePoint);
            }
        });
        return safe.toString();
    }

    /**
     * 以 replacement 字符解码损坏或不完整 UTF-8 byte sequence。
     *
     * @param bytes 原始字节
     * @return UTF-8 文本
     */
    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException impossibleWithReplacement) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * 提取 literal marker 对之间的内容。
     *
     * @param text 模型输出
     * @param tags 开闭 marker
     * @return 空串或由换行符拼接的代码块
     */
    private static String collectLiteralBlocks(String text, CodeBlockTags tags) {
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        while (true) {
            int start = text.indexOf(tags.openingTag(), cursor);
            if (start < 0) {
                break;
            }
            start += tags.openingTag().length();
            int end = text.indexOf(tags.closingTag(), start);
            if (end < 0) {
                break;
            }
            if (!result.isEmpty()) {
                result.append("\n\n");
            }
            result.append(text, start, end);
            cursor = end + tags.closingTag().length();
        }
        return result.toString().strip();
    }

    /**
     * 合并正则代码块并去掉每个 block 边缘空白。
     *
     * @param matcher 已定位默认 Markdown block 的 matcher
     * @return 代码文本或空串
     */
    private static String collectRegexBlocks(Matcher matcher) {
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            if (!result.isEmpty()) {
                result.append("\n\n");
            }
            result.append(matcher.group(1).strip());
        }
        return result.toString();
    }

    /**
     * 保留 JSON 结果、代码块周围自然语言文本的不可变值。
     *
     * @param value parsed JSON object
     * @param prefix JSON object 前文本
     * @param suffix JSON object 后文本
     */
    public record ParsedJsonBlob(JsonNode value, String prefix, String suffix) {
        /**
         * 防御性复制 JSON 树，避免外部修改解析结果。
         *
         * @param value parsed JSON object
         * @param prefix JSON object 前文本
         * @param suffix JSON object 后文本
         */
        public ParsedJsonBlob {
            if (value == null || !value.isObject()) {
                throw new ModelOutputException("parsed JSON blob must be an object");
            }
            value = value.deepCopy();
        }

        /**
         * 返回 JSON 节点的防御性副本。
         *
         * @return JSON object 副本
         */
        @Override
        public JsonNode value() {
            return value.deepCopy();
        }
    }
}
