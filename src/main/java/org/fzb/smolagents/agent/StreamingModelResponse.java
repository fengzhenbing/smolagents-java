package org.fzb.smolagents.agent;

import org.fzb.smolagents.core.ModelException;
import org.fzb.smolagents.core.ModelOutputException;
import org.fzb.smolagents.core.SmolagentsException;
import org.fzb.smolagents.event.AgentEvent;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.GenerationRequest;
import org.fzb.smolagents.model.Model;
import org.fzb.smolagents.model.ModelStreamEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 收集模型流事件并在 Agent 事件流中转发可观察增量。
 */
final class StreamingModelResponse {
    private StreamingModelResponse() {
    }

    /**
     * 在流式 Agent run 中使用模型流生成消息；非流式 run 继续调用同步模型 API。
     *
     * @param model 请求模型
     * @param request 完整生成请求
     * @param context 当前运行上下文
     * @return 模型完成消息
     */
    static ChatMessage generate(Model model, GenerationRequest request, RunContext context) {
        if (!context.streaming() || !model.capabilities().streaming()) {
            return model.generate(request);
        }
        CompletableFuture<ChatMessage> completed = new CompletableFuture<>();
        AtomicReference<ChatMessage> message = new AtomicReference<>();
        AtomicReference<SmolagentsException> modelFailure = new AtomicReference<>();
        AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();
        try {
            model.stream(request).subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    if (!subscriptionRef.compareAndSet(null, subscription)) {
                        subscription.cancel();
                        completed.completeExceptionally(
                                new ModelException("model stream supplied multiple subscriptions")
                        );
                        return;
                    }
                    if (request.cancellation().isCancelled()) {
                        subscription.cancel();
                        completed.completeExceptionally(new ModelException("model stream was cancelled"));
                        return;
                    }
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(ModelStreamEvent event) {
                    if (request.cancellation().isCancelled()) {
                        Flow.Subscription subscription = subscriptionRef.get();
                        if (subscription != null) {
                            subscription.cancel();
                        }
                        completed.completeExceptionally(new ModelException("model stream was cancelled"));
                        return;
                    }
                    try {
                        context.publish(new AgentEvent.ModelDelta(context.runId(), event));
                        if (event instanceof ModelStreamEvent.Completed result) {
                            if (!message.compareAndSet(null, result.message())) {
                                modelFailure.compareAndSet(
                                        null,
                                        new ModelOutputException("model stream emitted more than one completed message")
                                );
                            }
                        } else if (event instanceof ModelStreamEvent.Failed failure) {
                            modelFailure.compareAndSet(null, failure.error());
                        }
                    } catch (RuntimeException error) {
                        completed.completeExceptionally(error);
                        Flow.Subscription subscription = subscriptionRef.get();
                        if (subscription != null) {
                            subscription.cancel();
                        }
                    }
                }

                @Override
                public void onError(Throwable error) {
                    completed.completeExceptionally(error);
                }

                @Override
                public void onComplete() {
                    SmolagentsException failure = modelFailure.get();
                    if (failure != null) {
                        completed.completeExceptionally(failure);
                        return;
                    }
                    ChatMessage result = message.get();
                    if (result == null) {
                        completed.completeExceptionally(
                                new ModelOutputException("model stream completed without a message")
                        );
                    } else {
                        completed.complete(result);
                    }
                }
            });
            // 步骤一：定期观察 Agent 的取消令牌，避免上游 Publisher 不发事件时运行线程永久等待。
            while (!completed.isDone()) {
                request.cancellation().throwIfCancelled();
                try {
                    completed.get(25, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // 轮询间隔仅用于及时传播外部取消，不改变模型请求超时策略。
                } catch (ExecutionException error) {
                    break;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new ModelException("waiting for model stream was interrupted", error);
                }
            }
            // 步骤二：只在成功完成时返回消息；异常原因由下方统一解包。
            return completed.join();
        } catch (CompletionException error) {
            Flow.Subscription subscription = subscriptionRef.get();
            if (subscription != null) {
                subscription.cancel();
            }
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ModelException("model stream failed", cause);
        } catch (RuntimeException error) {
            Flow.Subscription subscription = subscriptionRef.get();
            if (subscription != null) {
                subscription.cancel();
            }
            throw error;
        }
    }
}
