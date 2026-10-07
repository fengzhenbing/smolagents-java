package org.fzb.smolagents.executor;

/**
 * 隔离具体 Python 或远程代码运行环境的执行器 SPI。
 */
public interface CodeExecutor extends AutoCloseable {
    /**
     * 在执行环境中运行一次代码动作。
     *
     * @param request 代码、变量、工具和运行限制
     * @return 标准输出、结果、变量更新或失败信息
     */
    ExecutorResult execute(ExecutorRequest request);

    /**
     * 清理当前执行会话中的变量和运行状态。
     */
    void reset();

    /**
     * 释放进程、容器或远程会话等执行器资源。
     */
    @Override
    void close();
}
