package org.fzb.smolagents.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * 记录用户提交的新任务。
 *
 * @param stepNumber 记忆步骤序号
 * @param startedAt 任务进入运行时的时间
 * @param task 已格式化任务文本
 */
public record TaskStep(int stepNumber, Instant startedAt, String task) implements MemoryStep {
    /**
     * 校验序号、时间和任务描述。
     *
     * @param stepNumber 记忆步骤序号
     * @param startedAt 开始时间
     * @param task 任务文本
     */
    public TaskStep {
        if (stepNumber < 0) {
            throw new IllegalArgumentException("stepNumber must not be negative");
        }
        Objects.requireNonNull(startedAt, "startedAt");
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
    }
}
