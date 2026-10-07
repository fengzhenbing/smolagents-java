package org.fzb.smolagents.monitoring;

import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.RunResult;
import org.fzb.smolagents.core.RunStatus;
import org.fzb.smolagents.event.AgentEvent;
import org.fzb.smolagents.memory.ActionStep;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.MessageRole;
import org.fzb.smolagents.model.TokenUsage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Agent 事件流到监控指标的映射和订阅行为。
 */
class AgentMonitorTest {
    /**
     * 验证步骤时长、最终用量、总时长和运行状态写入不可变快照。
     */
    @Test
    void aggregatesCompletedActionStepsAndRunResult() {
        AgentMonitor monitor = new AgentMonitor();
        UUID runId = UUID.randomUUID();
        ActionStep action = new ActionStep(
                1,
                Instant.EPOCH,
                Duration.ofMillis(125),
                ChatMessage.text(MessageRole.ASSISTANT, "done"),
                List.of(),
                TokenUsage.of(5, 3)
        );
        RunResult result = new RunResult(
                runId,
                AgentValue.text("answer"),
                RunStatus.SUCCESS,
                List.of(action),
                TokenUsage.of(5, 3),
                Instant.EPOCH,
                Duration.ofMillis(400)
        );
        monitor.onNext(new AgentEvent.RunStarted(runId, Instant.EPOCH));
        monitor.onNext(new AgentEvent.StepCompleted(runId, action));
        monitor.onNext(new AgentEvent.RunCompleted(runId, result.output(), result));
        monitor.onComplete();

        AgentMonitor.Snapshot snapshot = monitor.snapshot();

        assertEquals(runId, snapshot.runId());
        assertEquals(1, snapshot.completedActionSteps());
        assertEquals(List.of(Duration.ofMillis(125)), snapshot.actionStepDurations());
        assertEquals(TokenUsage.of(5, 3), snapshot.tokenUsage());
        assertEquals(Duration.ofMillis(400), snapshot.duration());
        assertEquals(RunStatus.SUCCESS, snapshot.status());
        assertTrue(snapshot.streamCompleted());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.actionStepDurations().clear());
    }

    /**
     * 验证 monitor 自动请求完整事件，并拒绝聚合多个 run 的混合数据。
     */
    @Test
    void requestsEventsAndRejectsMixedRunIds() {
        AgentMonitor monitor = new AgentMonitor();
        AtomicLong requested = new AtomicLong();
        AtomicLong cancelled = new AtomicLong();
        monitor.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                requested.set(n);
            }

            @Override
            public void cancel() {
                cancelled.incrementAndGet();
            }
        });
        monitor.onNext(new AgentEvent.RunStarted(UUID.randomUUID(), Instant.EPOCH));
        monitor.onNext(new AgentEvent.RunStarted(UUID.randomUUID(), Instant.EPOCH));

        assertEquals(Long.MAX_VALUE, requested.get());
        assertEquals(1, cancelled.get());
        assertInstanceOf(IllegalStateException.class, monitor.snapshot().failure());
    }

    /**
     * 验证事件 publisher 的失败被保留且状态标记为 FAILED。
     */
    @Test
    void recordsPublisherFailure() {
        AgentMonitor monitor = new AgentMonitor();
        RuntimeException failure = new RuntimeException("publisher failed");

        monitor.onError(failure);

        assertEquals(RunStatus.FAILED, monitor.snapshot().status());
        assertEquals(failure, monitor.snapshot().failure());
        assertTrue(monitor.snapshot().streamCompleted());
    }
}
