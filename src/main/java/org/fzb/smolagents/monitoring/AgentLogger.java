package org.fzb.smolagents.monitoring;

import org.fzb.smolagents.event.AgentEvent;
import org.fzb.smolagents.model.ChatMessage;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Objects;

/**
 * 提供无第三方终端依赖的可配置 Agent 文本日志。
 *
 * <p>日志按纯文本写入，不解释 Rich/ANSI markup；控制字符经过净化，且日志仅在调用方
 * 主动传入内容时输出。设置 {@link LogLevel#OFF} 可关闭所有终端输出。</p>
 */
public final class AgentLogger implements java.util.concurrent.Flow.Subscriber<AgentEvent> {
    private final Writer output;
    private final LogLevel level;
    private java.util.concurrent.Flow.Subscription subscription;

    /**
     * 创建写入指定目标的 AgentLogger。
     *
     * @param output 日志 Writer；不会由本对象关闭
     * @param level 最大日志详细程度
     */
    public AgentLogger(Writer output, LogLevel level) {
        this.output = Objects.requireNonNull(output, "output");
        this.level = Objects.requireNonNull(level, "level");
    }

    /**
     * 创建写入标准错误的常规 INFO logger。
     *
     * @return 控制台 logger
     */
    public static AgentLogger console() {
        return new AgentLogger(new java.io.OutputStreamWriter(System.err, java.nio.charset.StandardCharsets.UTF_8),
                LogLevel.INFO);
    }

    /**
     * 创建不输出任何文本的 logger。
     *
     * @return 静默 logger
     */
    public static AgentLogger silent() {
        return new AgentLogger(Writer.nullWriter(), LogLevel.OFF);
    }

    /**
     * 按当前配置输出一条 INFO 消息。
     *
     * @param message 日志文本
     */
    public void log(String message) {
        log(message, LogLevel.INFO);
    }

    /**
     * 按级别输出单条净化后的纯文本消息。
     *
     * @param message 日志文本
     * @param messageLevel 消息级别
     */
    public synchronized void log(String message, LogLevel messageLevel) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(messageLevel, "messageLevel");
        if (!level.allows(messageLevel)) {
            return;
        }
        try {
            output.write("[" + messageLevel.name() + "] " + sanitize(message) + System.lineSeparator());
            output.flush();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to write Agent log output", error);
        }
    }

    /**
     * 以 ERROR 级别记录错误文本。
     *
     * @param message 错误摘要
     */
    public void logError(String message) {
        log(message, LogLevel.ERROR);
    }

    /**
     * 输出包含标题的任务文本。
     *
     * @param task 任务正文
     * @param title 可选运行标题
     */
    public void logTask(String task, String title) {
        String prefix = title == null || title.isBlank() ? "Task" : "Task: " + title;
        log(prefix + System.lineSeparator() + sanitize(Objects.requireNonNull(task, "task")), LogLevel.INFO);
    }

    /**
     * 以纯文本形式输出 Markdown 内容。
     *
     * @param content Markdown 文本
     * @param title 可选标题
     * @param messageLevel 日志级别
     */
    public void logMarkdown(String content, String title, LogLevel messageLevel) {
        String message = title == null || title.isBlank()
                ? Objects.requireNonNull(content, "content")
                : title + System.lineSeparator() + Objects.requireNonNull(content, "content");
        log(message, messageLevel);
    }

    /**
     * 以代码块格式输出代码文本，不执行或解释其中的内容。
     *
     * @param title 代码标题
     * @param code 代码文本
     * @param messageLevel 日志级别
     */
    public void logCode(String title, String code, LogLevel messageLevel) {
        log(Objects.requireNonNull(title, "title") + System.lineSeparator() + "```"
                + System.lineSeparator() + Objects.requireNonNull(code, "code")
                + System.lineSeparator() + "```", messageLevel);
    }

    /**
     * 输出一组聊天消息的安全文本摘要。
     *
     * @param messages 聊天消息列表
     * @param messageLevel 日志级别
     */
    public void logMessages(List<ChatMessage> messages, LogLevel messageLevel) {
        Objects.requireNonNull(messages, "messages");
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < messages.size(); index++) {
            if (index > 0) {
                text.append(System.lineSeparator());
            }
            ChatMessage message = Objects.requireNonNull(messages.get(index), "message");
            text.append(message.role()).append(": ").append(message.content());
        }
        log(text.toString(), messageLevel);
    }

    /**
     * 订阅并请求 Agent 生命周期事件，以便记录单次运行的状态摘要。
     *
     * @param subscription publisher subscription
     */
    @Override
    public synchronized void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
        Objects.requireNonNull(subscription, "subscription");
        if (this.subscription != null) {
            subscription.cancel();
            return;
        }
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
    }

    /**
     * 记录事件类型与安全摘要，不自动输出模型正文、prompt 或工具参数。
     *
     * @param event Agent 生命周期事件
     */
    @Override
    public void onNext(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof AgentEvent.RunStarted started) {
            log("Run started: " + started.runId(), LogLevel.INFO);
        } else if (event instanceof AgentEvent.StepStarted started) {
            log("Step " + started.stepNumber() + " started", LogLevel.INFO);
        } else if (event instanceof AgentEvent.ModelDelta) {
            log("Model emitted a streaming delta", LogLevel.DEBUG);
        } else if (event instanceof AgentEvent.ToolStarted started) {
            log("Tool started: " + started.toolName(), LogLevel.INFO);
        } else if (event instanceof AgentEvent.ToolCompleted completed) {
            log("Tool completed: " + completed.toolName(), LogLevel.INFO);
        } else if (event instanceof AgentEvent.ToolFailed failed) {
            logError("Tool failed (" + failed.toolName() + "): " + failed.error());
        } else if (event instanceof AgentEvent.StepCompleted completed) {
            log("Step " + completed.step().stepNumber() + " completed", LogLevel.DEBUG);
        } else if (event instanceof AgentEvent.RunCompleted completed) {
            var result = completed.result();
            log("Run completed: " + result.status() + ", duration=" + result.duration()
                    + ", tokens=" + result.tokenUsage().totalTokens().stream()
                    .mapToObj(Long::toString)
                    .findFirst()
                    .orElse("unknown"), LogLevel.INFO);
        } else if (event instanceof AgentEvent.RunFailed failed) {
            logError("Run failed: " + Objects.toString(failed.error().getMessage(), failed.error().getClass().getName()));
        }
    }

    /**
     * 将 publisher 错误写入 ERROR 日志。
     *
     * @param throwable publisher error
     */
    @Override
    public void onError(Throwable throwable) {
        Throwable error = Objects.requireNonNull(throwable, "throwable");
        logError("Agent event stream failed: "
                + Objects.toString(error.getMessage(), error.getClass().getName()));
    }

    /**
     * 记录 Agent 事件流已结束。
     */
    @Override
    public void onComplete() {
        log("Agent event stream completed", LogLevel.DEBUG);
    }

    /**
     * 移除除换行和制表外的 ASCII 控制字符，避免终端控制序列注入。
     *
     * @param value 原始日志文本
     * @return 可直接写入终端的文本
     */
    private static String sanitize(String value) {
        StringBuilder sanitized = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\t' || codePoint >= 0x20 && codePoint != 0x7f) {
                sanitized.appendCodePoint(codePoint);
            }
        });
        return sanitized.toString();
    }
}
