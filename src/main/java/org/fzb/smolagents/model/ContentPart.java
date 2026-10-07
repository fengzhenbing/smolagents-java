package org.fzb.smolagents.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Objects;

/**
 * 消息或工具结果中的文本、媒体或结构化 JSON 内容片段。
 */
public sealed interface ContentPart permits ContentPart.Text, ContentPart.Image, ContentPart.Audio, ContentPart.Json {
    /**
     * 表示纯文本内容。
     *
     * @param text 文本值
     */
    record Text(String text) implements ContentPart {
        /** 校验文本不为空引用。 */
        public Text {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * 表示图片内容；可使用 URI 引用或内嵌二进制数据。
     *
     * @param uri 图片 URI；可为空
     * @param mimeType 图片 MIME 类型
     * @param data 图片二进制数据；可为空
     */
    record Image(URI uri, String mimeType, byte[] data) implements ContentPart {
        /** 校验图片来源和 MIME 类型，并复制输入字节数组。 */
        public Image {
            if (uri == null && data == null) {
                throw new IllegalArgumentException("image requires a URI or binary data");
            }
            requireMimeType(mimeType);
            data = data == null ? null : data.clone();
        }

        /**
         * 返回图片数据副本，防止调用方修改内部状态。
         *
         * @return 图片数据副本；未内嵌数据时返回 {@code null}
         */
        @Override
        public byte[] data() {
            return data == null ? null : data.clone();
        }
    }

    /**
     * 表示音频内容及可选的识别文本。
     *
     * @param uri 音频 URI；可为空
     * @param mimeType 音频 MIME 类型
     * @param data 音频二进制数据；可为空
     * @param transcript 可选转录文本
     */
    record Audio(URI uri, String mimeType, byte[] data, String transcript) implements ContentPart {
        /** 校验音频来源和 MIME 类型，并复制输入字节数组。 */
        public Audio {
            if (uri == null && data == null) {
                throw new IllegalArgumentException("audio requires a URI or binary data");
            }
            requireMimeType(mimeType);
            data = data == null ? null : data.clone();
        }

        /**
         * 返回音频数据副本，防止调用方修改内部状态。
         *
         * @return 音频数据副本；未内嵌数据时返回 {@code null}
         */
        @Override
        public byte[] data() {
            return data == null ? null : data.clone();
        }
    }

    /**
     * 表示结构化 JSON 内容。
     *
     * @param value JSON 值
     */
    record Json(JsonNode value) implements ContentPart {
        /** 校验并复制 JSON 树。 */
        public Json {
            Objects.requireNonNull(value, "value");
            value = value.deepCopy();
        }

        /**
         * 返回 JSON 树的防御性副本。
         *
         * @return JSON 副本
         */
        @Override
        public JsonNode value() {
            return value.deepCopy();
        }
    }

    /**
     * 校验媒体类型字符串。
     *
     * @param mimeType 待校验的 MIME 类型
     */
    private static void requireMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType must not be blank");
        }
    }
}
