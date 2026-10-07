package org.fzb.smolagents.agent;

import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.memory.ActionStep;

import java.util.Objects;

/**
 * 表示一个动作步骤是继续运行还是已经产生最终答案。
 */
public sealed interface ActionOutcome permits ActionOutcome.Continue, ActionOutcome.Final {
    /**
     * 返回此动作产生的记忆步骤。
     *
     * @return 动作步骤
     */
    ActionStep actionStep();

    /**
     * 表示动作完成但 Agent 仍需继续运行。
     *
     * @param actionStep 已完成的动作步骤
     */
    record Continue(ActionStep actionStep) implements ActionOutcome {
        /**
         * 校验动作步骤非空。
         *
         * @param actionStep 已完成的动作步骤
         */
        public Continue {
            Objects.requireNonNull(actionStep, "actionStep");
        }
    }

    /**
     * 表示动作已取得最终答案。
     *
     * @param actionStep 已完成的动作步骤
     * @param answer 最终答案
     */
    record Final(ActionStep actionStep, AgentValue answer) implements ActionOutcome {
        /**
         * 校验动作步骤和最终答案非空。
         *
         * @param actionStep 已完成的动作步骤
         * @param answer 最终答案
         */
        public Final {
            Objects.requireNonNull(actionStep, "actionStep");
            Objects.requireNonNull(answer, "answer");
        }
    }
}
