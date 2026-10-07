package org.fzb.smolagents.core;

/**
 * Agent 运行的终止状态。
 */
public enum RunStatus {
    /** 成功生成最终答案。 */
    SUCCESS,
    /** 达到动作步数上限后生成了尽力答案。 */
    MAX_STEPS_REACHED,
    /** 调用方取消了运行。 */
    CANCELLED,
    /** 运行超过配置的时限。 */
    TIMED_OUT,
    /** 运行因模型、工具或执行器错误失败。 */
    FAILED
}
