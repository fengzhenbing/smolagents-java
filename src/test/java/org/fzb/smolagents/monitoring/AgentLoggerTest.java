package org.fzb.smolagents.monitoring;

import org.fzb.smolagents.event.AgentEvent;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.MessageRole;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证日志级别、纯文本输出、安全净化和写入失败报告。
 */
class AgentLoggerTest {
    /**
     * 验证 INFO logger 输出错误与普通消息，过滤 DEBUG 并移除 ANSI 控制字符。
     */
    @Test
    void filtersByLevelAndSanitizesTerminalControlCharacters() {
        StringWriter output = new StringWriter();
        AgentLogger logger = new AgentLogger(output, LogLevel.INFO);

        logger.log("task \u001B[31mFAILED", LogLevel.INFO);
        logger.log("details", LogLevel.DEBUG);
        logger.logError("unrecoverable");

        assertTrue(output.toString().contains("[INFO] task [31mFAILED"));
        assertTrue(output.toString().contains("[ERROR] unrecoverable"));
        assertFalse(output.toString().contains("\u001B"));
        assertFalse(output.toString().contains("[DEBUG]"));
    }

    /**
     * 验证 OFF logger 关闭输出，消息日志可使用纯文本格式。
     */
    @Test
    void supportsSilentAndMessageLogging() {
        StringWriter output = new StringWriter();
        new AgentLogger(output, LogLevel.OFF).log("hidden", LogLevel.ERROR);
        AgentLogger logger = new AgentLogger(output, LogLevel.DEBUG);
        logger.logMessages(List.of(ChatMessage.text(MessageRole.USER, "hello")), LogLevel.DEBUG);

        assertTrue(output.toString().contains("USER: [Text[text=hello]]"));
        assertFalse(output.toString().contains("hidden"));
    }

    /**
     * 验证 logger 可以直接订阅 Agent 事件并且不记录消息正文。
     */
    @Test
    void subscribesToLifecycleEventsWithoutLoggingModelPayloads() {
        StringWriter output = new StringWriter();
        AgentLogger logger = new AgentLogger(output, LogLevel.INFO);
        AtomicLong demand = new AtomicLong();
        logger.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                demand.set(n);
            }

            @Override
            public void cancel() {
            }
        });
        UUID runId = UUID.randomUUID();
        logger.onNext(new AgentEvent.RunStarted(runId, Instant.EPOCH));
        logger.onNext(new AgentEvent.ModelDelta(
                runId,
                new org.fzb.smolagents.model.ModelStreamEvent.MessageDelta("private model output")
        ));

        assertEquals(Long.MAX_VALUE, demand.get());
        assertTrue(output.toString().contains(runId.toString()));
        assertFalse(output.toString().contains("private model output"));
    }

    /**
     * 验证日志目标写入失败会显式向调用方报告。
     */
    @Test
    void reportsWriterFailure() {
        Writer failingWriter = new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        AgentLogger logger = new AgentLogger(failingWriter, LogLevel.INFO);

        assertThrows(IllegalStateException.class, () -> logger.log("message"));
    }
}
