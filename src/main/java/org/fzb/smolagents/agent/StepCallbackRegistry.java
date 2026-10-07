package org.fzb.smolagents.agent;

import org.fzb.smolagents.memory.MemoryStep;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 按记忆步骤类型管理有序回调。
 */
public final class StepCallbackRegistry {
    private final Map<Class<? extends MemoryStep>, CopyOnWriteArrayList<StepCallback>> callbacks =
            new ConcurrentHashMap<>();

    /**
     * 创建空的步骤回调注册表。
     */
    public StepCallbackRegistry() {
    }

    /**
     * 注册针对某种记忆步骤类型的回调。
     *
     * @param stepType 步骤类型；可以是具体步骤类或 {@link MemoryStep}
     * @param callback 待调用的回调
     */
    public void register(Class<? extends MemoryStep> stepType, StepCallback callback) {
        Objects.requireNonNull(stepType, "stepType");
        Objects.requireNonNull(callback, "callback");
        callbacks.computeIfAbsent(stepType, ignored -> new CopyOnWriteArrayList<>()).add(callback);
    }

    /**
     * 按步骤类型 specificity 和注册顺序调用所有匹配回调。
     *
     * @param step 已完成的步骤
     * @param agent 当前运行时
     */
    void invoke(MemoryStep step, AgentRuntime agent) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(agent, "agent");
        List<Map.Entry<Class<? extends MemoryStep>, CopyOnWriteArrayList<StepCallback>>> matches =
                new ArrayList<>(callbacks.entrySet().stream()
                        .filter(entry -> entry.getKey().isInstance(step))
                        .toList());
        matches.sort(Comparator.comparingInt(entry -> specificity(entry.getKey(), step.getClass())));
        for (var entry : matches) {
            for (StepCallback callback : entry.getValue()) {
                callback.onStep(step, agent);
            }
        }
    }

    /**
     * 计算注册类型相对具体步骤类的继承距离，越小越先调用。
     *
     * @param registeredType 回调注册类型
     * @param stepClass 实际步骤类型
     * @return 继承距离
     */
    private static int specificity(Class<?> registeredType, Class<?> stepClass) {
        if (registeredType.equals(stepClass)) {
            return 0;
        }
        if (registeredType.isInterface()) {
            return 1;
        }
        int distance = 1;
        Class<?> current = stepClass.getSuperclass();
        while (current != null && !registeredType.equals(current)) {
            current = current.getSuperclass();
            distance++;
        }
        return distance;
    }
}
