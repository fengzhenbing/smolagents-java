package org.fzb.smolagents.core;

/**
 * 表示 Agent 控制循环失败，并可选携带故障时的部分结果。
 */
public final class AgentRunException extends SmolagentsException {
    /** 运行失败时捕获的部分结果。 */
    private final RunResult partialResult;

    /**
     * 创建不带异常原因和部分结果的运行错误。
     *
     * @param message 错误描述
     */
    public AgentRunException(String message) {
        this(message, null, null);
    }

    /**
     * 创建保留原始原因的运行错误。
     *
     * @param message 错误描述
     * @param cause 原始异常
     */
    public AgentRunException(String message, Throwable cause) {
        this(message, cause, null);
    }

    /**
     * 创建可携带故障现场快照的运行错误。
     *
     * @param message 错误描述
     * @param cause 原始异常；可为空
     * @param partialResult 故障时的部分运行结果；可为空
     */
    public AgentRunException(String message, Throwable cause, RunResult partialResult) {
        super(message, cause);
        this.partialResult = partialResult;
    }

    /**
     * 返回运行失败时保存的部分结果。
     *
     * @return 部分结果；未保存时返回 {@code null}
     */
    public RunResult partialResult() {
        return partialResult;
    }
}
