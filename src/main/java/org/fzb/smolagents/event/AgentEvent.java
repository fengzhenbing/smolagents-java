package org.fzb.smolagents.event;

import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.RunResult;
import org.fzb.smolagents.memory.MemoryStep;
import org.fzb.smolagents.model.ModelStreamEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Agent 一次运行期间对外发布的有序生命周期事件。
 */
public sealed interface AgentEvent permits
        AgentEvent.RunStarted,
        AgentEvent.StepStarted,
        AgentEvent.ModelDelta,
        AgentEvent.ToolStarted,
        AgentEvent.ToolCompleted,
        AgentEvent.ToolFailed,
        AgentEvent.StepCompleted,
        AgentEvent.RunCompleted,
        AgentEvent.RunFailed {
    /**
     * 返回产生此事件的运行标识。
     *
     * @return 运行 UUID
     */
    UUID runId();

    /**
     * 表示一次 Agent 运行已开始。
     *
     * @param runId 运行标识
     * @param startedAt 开始时间
     */
    record RunStarted(UUID runId, Instant startedAt) implements AgentEvent {
        /**
         * 校验运行标识和开始时间。
         *
         * @param runId 运行标识
         * @param startedAt 开始时间
         */
        public RunStarted {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(startedAt, "startedAt");
        }
    }

    /**
     * 表示 Agent 即将执行一个动作步骤。
     *
     * @param runId 运行标识
     * @param stepNumber 步骤序号
     * @param startedAt 步骤开始时间
     */
    record StepStarted(UUID runId, int stepNumber, Instant startedAt) implements AgentEvent {
        /**
         * 校验运行标识、正数步骤序号和开始时间。
         *
         * @param runId 运行标识
         * @param stepNumber 步骤序号
         * @param startedAt 开始时间
         */
        public StepStarted {
            Objects.requireNonNull(runId, "runId");
            if (stepNumber < 1) {
                throw new IllegalArgumentException("stepNumber must be positive");
            }
            Objects.requireNonNull(startedAt, "startedAt");
        }
    }

    /**
     * 转发模型生成过程中的增量事件。
     *
     * @param runId 运行标识
     * @param delta 模型流增量
     */
    record ModelDelta(UUID runId, ModelStreamEvent delta) implements AgentEvent {
        /**
         * 校验运行标识和增量事件。
         *
         * @param runId 运行标识
         * @param delta 模型流增量
         */
        public ModelDelta {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(delta, "delta");
        }
    }

    /**
     * 表示一次工具调用开始。
     *
     * @param runId 运行标识
     * @param stepNumber 所属步骤序号
     * @param callId 模型工具调用 ID
     * @param toolName 工具名称
     */
    record ToolStarted(UUID runId, int stepNumber, String callId, String toolName) implements AgentEvent {
        /**
         * 校验运行标识、调用 ID 和工具名称。
         *
         * @param runId 运行标识
         * @param stepNumber 所属步骤序号
         * @param callId 模型工具调用 ID
         * @param toolName 工具名称
         */
        public ToolStarted {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(toolName, "toolName");
        }
    }

    /**
     * 表示一次工具调用已完成。
     *
     * @param runId 运行标识
     * @param stepNumber 所属步骤序号
     * @param callId 模型工具调用 ID
     * @param toolName 工具名称
     */
    record ToolCompleted(UUID runId, int stepNumber, String callId, String toolName) implements AgentEvent {
        /**
         * 校验运行标识、调用 ID 和工具名称。
         *
         * @param runId 运行标识
         * @param stepNumber 所属步骤序号
         * @param callId 模型工具调用 ID
         * @param toolName 工具名称
         */
        public ToolCompleted {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(toolName, "toolName");
        }
    }

    /**
     * 表示一次工具调用失败，并附带供运行观察者诊断的错误摘要。
     *
     * @param runId 运行标识
     * @param stepNumber 所属步骤序号
     * @param callId 模型工具调用 ID
     * @param toolName 工具名称
     * @param error 错误摘要
     */
    record ToolFailed(UUID runId, int stepNumber, String callId, String toolName, String error)
            implements AgentEvent {
        /**
         * 校验运行标识、调用 ID、工具名称和错误摘要。
         *
         * @param runId 运行标识
         * @param stepNumber 所属步骤序号
         * @param callId 模型工具调用 ID
         * @param toolName 工具名称
         * @param error 错误摘要
         */
        public ToolFailed {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(toolName, "toolName");
            if (error == null || error.isBlank()) {
                throw new IllegalArgumentException("error must not be blank");
            }
        }
    }

    /**
     * 表示一个记忆步骤已完成。
     *
     * @param runId 运行标识
     * @param step 已完成的记忆步骤
     */
    record StepCompleted(UUID runId, MemoryStep step) implements AgentEvent {
        /**
         * 校验运行标识和记忆步骤。
         *
         * @param runId 运行标识
         * @param step 已完成的记忆步骤
         */
        public StepCompleted {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(step, "step");
        }
    }

    /**
     * 表示 Agent 运行已得到输出并正常结束。
     *
     * @param runId 运行标识
     * @param output 最终输出
     * @param result 完整运行结果
     */
    record RunCompleted(UUID runId, AgentValue output, RunResult result) implements AgentEvent {
        /**
         * 校验运行标识、输出和结果。
         *
         * @param runId 运行标识
         * @param output 最终输出
         * @param result 完整运行结果
         */
        public RunCompleted {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(result, "result");
        }
    }

    /**
     * 表示 Agent 流中报告的运行失败。
     *
     * @param runId 运行标识
     * @param error 失败原因
     */
    record RunFailed(UUID runId, Throwable error) implements AgentEvent {
        /**
         * 校验运行标识和异常对象。
         *
         * @param runId 运行标识
         * @param error 失败原因
         */
        public RunFailed {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(error, "error");
        }
    }
}
