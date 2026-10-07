package org.fzb.smolagents.model;

import org.fzb.smolagents.core.SmolagentsException;

import java.util.Objects;

/**
 * 表示模型流式生成过程中的增量、统计、完成或失败事件。
 */
public sealed interface ModelStreamEvent permits
        ModelStreamEvent.MessageDelta,
        ModelStreamEvent.ToolCallDelta,
        ModelStreamEvent.Usage,
        ModelStreamEvent.Completed,
        ModelStreamEvent.Failed {

    /**
     * 表示新增的文本片段。
     *
     * @param text 文本增量
     */
    record MessageDelta(String text) implements ModelStreamEvent {
        /** 校验文本片段不为空引用。 */
        public MessageDelta {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * 表示工具调用的部分字段或参数增量。
     *
     * @param index 当前响应中的工具调用序号
     * @param id 可选调用 ID 增量
     * @param name 可选工具名称增量
     * @param argumentsFragment 参数 JSON 文本增量
     */
    record ToolCallDelta(int index, String id, String name, String argumentsFragment) implements ModelStreamEvent {
        /** 校验工具调用序号和参数片段。 */
        public ToolCallDelta {
            if (index < 0) {
                throw new IllegalArgumentException("index must not be negative");
            }
            Objects.requireNonNull(argumentsFragment, "argumentsFragment");
        }
    }

    /**
     * 表示模型报告的 token 用量。
     *
     * @param tokenUsage token 用量
     */
    record Usage(TokenUsage tokenUsage) implements ModelStreamEvent {
        /** 校验用量字段。 */
        public Usage {
            Objects.requireNonNull(tokenUsage, "tokenUsage");
        }
    }

    /**
     * 表示流式输出已聚合为完整消息。
     *
     * @param message 聚合后的 assistant 消息
     */
    record Completed(ChatMessage message) implements ModelStreamEvent {
        /** 校验完整消息。 */
        public Completed {
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * 表示流式生成失败。
     *
     * @param error 可诊断的 smolagents 异常
     */
    record Failed(SmolagentsException error) implements ModelStreamEvent {
        /** 校验异常字段。 */
        public Failed {
            Objects.requireNonNull(error, "error");
        }
    }
}
