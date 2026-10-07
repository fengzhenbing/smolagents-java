package org.fzb.smolagents.agent;

import org.fzb.smolagents.core.AgentInput;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.RunOptions;
import org.fzb.smolagents.core.RunResult;
import org.fzb.smolagents.event.AgentEvent;

import java.util.concurrent.Flow;

/**
 * 定义可同步运行、获取完整运行结果及订阅事件的 Agent 接口。
 */
public interface Agent {
    /**
     * 执行任务并返回最终输出值。
     *
     * @param input Agent 输入
     * @param options 本次运行选项
     * @return 最终输出
     */
    AgentValue run(AgentInput input, RunOptions options);

    /**
     * 执行任务并返回状态、记忆步骤及用量等完整结果。
     *
     * @param input Agent 输入
     * @param options 本次运行选项
     * @return 完整运行结果
     */
    RunResult runFull(AgentInput input, RunOptions options);

    /**
     * 启动一次异步运行并发布运行过程事件。
     *
     * @param input Agent 输入
     * @param options 本次运行选项
     * @return 仅支持一个订阅者的事件发布器
     */
    Flow.Publisher<AgentEvent> stream(AgentInput input, RunOptions options);
}
