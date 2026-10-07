package org.fzb.smolagents.agent;

import org.fzb.smolagents.core.AgentInput;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.RunOptions;
import org.fzb.smolagents.event.AgentEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在一次运行期间共享运行标识、取消信号、选项和事件发布能力。
 */
public final class RunContext {
    private final UUID runId;
    private final Instant startedAt;
    private final AgentInput input;
    private final RunOptions options;
    private final Consumer<AgentEvent> eventSink;
    private final boolean streaming;
    private final AtomicInteger nextStepNumber = new AtomicInteger(1);

    /**
     * 创建带事件接收器的运行上下文。
     *
     * @param runId 本次运行标识
     * @param startedAt 运行开始时间
     * @param input 原始 Agent 输入
     * @param options 本次运行选项
     * @param eventSink 事件接收器
     */
    public RunContext(
            UUID runId,
            Instant startedAt,
            AgentInput input,
            RunOptions options,
            Consumer<AgentEvent> eventSink
    ) {
        this(runId, startedAt, input, options, eventSink, false);
    }

    /**
     * 创建流式或同步运行上下文。
     *
     * @param runId 本次运行标识
     * @param startedAt 开始时间
     * @param input 原始 Agent 输入
     * @param options 本次运行选项
     * @param eventSink 事件接收器
     * @param streaming 是否应请求并转发模型流式事件
     */
    public RunContext(
            UUID runId,
            Instant startedAt,
            AgentInput input,
            RunOptions options,
            Consumer<AgentEvent> eventSink,
            boolean streaming
    ) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.input = Objects.requireNonNull(input, "input");
        this.options = Objects.requireNonNull(options, "options");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.streaming = streaming;
    }

    /**
     * 创建不发布事件的运行上下文。
     *
     * @param runId 本次运行标识
     * @param startedAt 运行开始时间
     * @param input 原始 Agent 输入
     * @param options 本次运行选项
     */
    public RunContext(UUID runId, Instant startedAt, AgentInput input, RunOptions options) {
        this(runId, startedAt, input, options, event -> { });
    }

    /**
     * 返回本次运行标识。
     *
     * @return 运行 UUID
     */
    public UUID runId() {
        return runId;
    }

    /**
     * 返回本次运行开始时间。
     *
     * @return 开始时间
     */
    public Instant startedAt() {
        return startedAt;
    }

    /**
     * 返回本次运行的原始输入。
     *
     * @return Agent 输入
     */
    public AgentInput input() {
        return input;
    }

    /**
     * 返回本次运行选项。
     *
     * @return 运行选项
     */
    public RunOptions options() {
        return options;
    }

    /**
     * 查询本次运行是否启用模型流式调用。
     *
     * @return 事件流运行时为 {@code true}
     */
    public boolean streaming() {
        return streaming;
    }

    /**
     * 返回运行选项中的取消令牌。
     *
     * @return 取消令牌
     */
    public CancellationToken cancellation() {
        return options.cancellation();
    }

    /**
     * 分配下一个正整数步骤序号。
     *
     * @return 唯一递增的步骤序号
     */
    public int nextStepNumber() {
        return nextStepNumber.getAndIncrement();
    }

    /**
     * 将 Agent 事件发布给当前运行的接收器。
     *
     * @param event 待发布事件
     */
    public void publish(AgentEvent event) {
        eventSink.accept(Objects.requireNonNull(event, "event"));
    }
}
