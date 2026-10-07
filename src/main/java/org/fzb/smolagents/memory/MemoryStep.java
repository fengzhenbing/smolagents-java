package org.fzb.smolagents.memory;

import java.time.Instant;

/**
 * Agent 会话记忆中可持久化的步骤类型。
 */
public sealed interface MemoryStep permits TaskStep, PlanningStep, ActionStep, FinalAnswerStep {
    /**
     * 返回该步骤在本次 Agent 会话中的序号。
     *
     * @return 非负步骤序号
     */
    int stepNumber();

    /**
     * 返回步骤开始时间。
     *
     * @return 开始时间
     */
    Instant startedAt();
}
