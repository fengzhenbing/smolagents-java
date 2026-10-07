package org.fzb.smolagents.monitoring;

import org.fzb.smolagents.core.RunStatus;
import org.fzb.smolagents.event.AgentEvent;
import org.fzb.smolagents.memory.ActionStep;
import org.fzb.smolagents.model.TokenUsage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Flow;

/**
 * 订阅 Agent 事件并维护一次运行的步骤时长、token 用量和结束状态。
 *
 * <p>可直接传给 {@code agent.stream(...).subscribe(monitor)}。监控器不写入 stdout，
 * 不记录 prompt 或工具参数，也不会改变被监控 Agent 的运行状态。</p>
 */
public final class AgentMonitor implements Flow.Subscriber<AgentEvent> {
    private final List<Duration> actionStepDurations = new ArrayList<>();
    private Flow.Subscription subscription;
    private UUID runId;
    private TokenUsage tokenUsage = TokenUsage.unknown();
    private Duration duration;
    private RunStatus status;
    private Throwable failure;
    private boolean streamCompleted;

    /**
     * 创建用于汇总单次 Agent 事件流的监控器。
     */
    public AgentMonitor() {
    }

    /**
     * 接受单订阅者 Agent 事件流并请求全部事件。
     *
     * @param subscription Reactive Streams 订阅
     */
    @Override
    public synchronized void onSubscribe(Flow.Subscription subscription) {
        Objects.requireNonNull(subscription, "subscription");
        if (this.subscription != null) {
            subscription.cancel();
            return;
        }
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
    }

    /**
     * 根据运行、动作步骤和完成事件更新监控快照。
     *
     * @param event Agent 生命周期事件
     */
    @Override
    public synchronized void onNext(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        if (runId == null) {
            runId = event.runId();
        } else if (!runId.equals(event.runId())) {
            failure = new IllegalStateException("monitor cannot aggregate events from multiple runs");
            if (subscription != null) {
                subscription.cancel();
            }
            return;
        }
        if (event instanceof AgentEvent.StepCompleted completed
                && completed.step() instanceof ActionStep action) {
            actionStepDurations.add(action.duration());
        } else if (event instanceof AgentEvent.RunCompleted completed) {
            tokenUsage = completed.result().tokenUsage();
            duration = completed.result().duration();
            status = completed.result().status();
        } else if (event instanceof AgentEvent.RunFailed failed) {
            failure = failed.error();
            status = RunStatus.FAILED;
        }
    }

    /**
     * 保存 publisher 发出的流错误。
     *
     * @param throwable 事件流错误
     */
    @Override
    public synchronized void onError(Throwable throwable) {
        failure = Objects.requireNonNull(throwable, "throwable");
        if (status == null) {
            status = RunStatus.FAILED;
        }
        streamCompleted = true;
    }

    /**
     * 标记事件流正常完成。
     */
    @Override
    public synchronized void onComplete() {
        streamCompleted = true;
    }

    /**
     * 返回当前监控状态的防御性快照。
     *
     * @return 当前运行监控指标
     */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                runId,
                actionStepDurations.size(),
                actionStepDurations,
                tokenUsage,
                duration,
                status,
                failure,
                streamCompleted
        );
    }

    /**
     * 保存不可变的单次 Agent 运行监控指标。
     *
     * @param runId Agent run ID；尚未收到事件时为空
     * @param completedActionSteps 已完成动作数
     * @param actionStepDurations 各动作的运行时长
     * @param tokenUsage 汇总 token 用量
     * @param duration 总运行时长
     * @param status 运行终止状态
     * @param failure 流或运行错误
     * @param streamCompleted Publisher 是否已终止
     */
    public record Snapshot(
            UUID runId,
            int completedActionSteps,
            List<Duration> actionStepDurations,
            TokenUsage tokenUsage,
            Duration duration,
            RunStatus status,
            Throwable failure,
            boolean streamCompleted
    ) {
        /**
         * 防御性复制动作时长，并验证计数与数据一致。
         *
         * @param runId Agent run ID
         * @param completedActionSteps 已完成动作数
         * @param actionStepDurations 各动作的运行时长
         * @param tokenUsage 汇总 token 用量
         * @param duration 总运行时长
         * @param status 运行终止状态
         * @param failure 流或运行错误
         * @param streamCompleted Publisher 是否已终止
         */
        public Snapshot {
            actionStepDurations = List.copyOf(actionStepDurations);
            Objects.requireNonNull(tokenUsage, "tokenUsage");
            if (completedActionSteps != actionStepDurations.size()) {
                throw new IllegalArgumentException("completedActionSteps must match actionStepDurations size");
            }
        }
    }
}
