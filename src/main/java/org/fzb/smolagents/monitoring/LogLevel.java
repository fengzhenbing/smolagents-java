package org.fzb.smolagents.monitoring;

/**
 * 控制 AgentLogger 输出详细程度的日志级别。
 */
public enum LogLevel {
    /** 关闭所有日志输出。 */
    OFF(-1),
    /** 仅输出错误。 */
    ERROR(0),
    /** 输出常规运行信息。 */
    INFO(1),
    /** 输出详细调试信息。 */
    DEBUG(2);

    private final int verbosity;

    LogLevel(int verbosity) {
        this.verbosity = verbosity;
    }

    /**
     * 判断消息级别是否应在当前 verbosity 下输出。
     *
     * @param messageLevel 待判断消息级别
     * @return 需要输出时为 true
     */
    boolean allows(LogLevel messageLevel) {
        return this != OFF && messageLevel != OFF && messageLevel.verbosity <= verbosity;
    }
}
