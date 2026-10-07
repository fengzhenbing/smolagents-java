package org.fzb.smolagents.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.agent.Agent;
import org.fzb.smolagents.core.AgentInput;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.RunOptions;
import org.fzb.smolagents.core.RunResult;
import org.fzb.smolagents.core.RunStatus;
import org.fzb.smolagents.event.AgentEvent;
import org.fzb.smolagents.memory.MemoryStep;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.ModelStreamEvent;
import org.fzb.smolagents.model.TokenUsage;
import org.fzb.smolagents.tool.ToolExecutionContext;
import org.fzb.smolagents.tool.ToolResult;
import org.fzb.smolagents.tool.VisualBrowserTool;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通过可选的本机 Chrome 验证多会话 Web UI 脚本和 SSE 端到端交互。
 */
class AgentHttpServerUiIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 打开本机 UI、创建会话、提交任务并读取 Agent 最终答案。
     *
     * @throws Exception Chrome 或本机 loopback 服务调用失败
     */
    @Test
    void createsSessionAndRunsTaskFromChromeUiWhenExplicitlyEnabled() throws Exception {
        Assumptions.assumeTrue(
                "1".equals(System.getenv("SMOLAGENTS_BROWSER_TESTS")),
                "Set SMOLAGENTS_BROWSER_TESTS=1 to launch a local Chrome process"
        );
        String configured = System.getenv("CHROME_PATH");
        Path chrome = configured == null || configured.isBlank()
                ? Path.of("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe")
                : Path.of(configured);
        Assumptions.assumeTrue(Files.isRegularFile(chrome), "Configured Chrome executable does not exist");

        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0);
        try (AgentHttpServer server = new AgentHttpServer(address, sessionId -> new UiAgent(), null);
             VisualBrowserTool browser = new VisualBrowserTool(
                     chrome,
                     Duration.ofSeconds(15),
                     true,
                     true
             )) {
            server.start();
            ToolResult loaded = execute(
                    browser,
                    "{\"action\":\"navigate\",\"url\":\"http://127.0.0.1:"
                            + server.address().getPort() + "/\"}"
            );
            assertTrue(pageText(loaded).contains("会话"));

            execute(browser, "{\"action\":\"click\",\"selector\":\"#new-session\"}");
            ToolResult created = execute(browser, "{\"action\":\"wait\",\"milliseconds\":300}");
            assertTrue(pageText(created).contains("[new conversation]"));

            execute(browser, "{\"action\":\"click\",\"selector\":\"#task\"}");
            execute(browser, "{\"action\":\"type\",\"selector\":\"#task\",\"text\":\"browser task\"}");
            execute(browser, "{\"action\":\"click\",\"selector\":\"#run\"}");
            ToolResult completed = execute(browser, "{\"action\":\"wait\",\"milliseconds\":500}");
            assertTrue(pageText(completed).contains("browser task"));
            assertTrue(pageText(completed).contains("done"));
        }
    }

    /**
     * 运行一个 Chrome 浏览器动作。
     *
     * @param browser 正在运行的 CDP 浏览器工具
     * @param arguments JSON action 输入
     * @return 页面文本和当前截图
     * @throws Exception 参数解码或浏览器调用失败
     */
    private static ToolResult execute(VisualBrowserTool browser, String arguments) throws Exception {
        return browser.execute(
                MAPPER.readTree(arguments),
                new ToolExecutionContext(UUID.randomUUID(), "ui-browser-test", new CancellationToken())
        );
    }

    /**
     * 提取浏览器工具返回的可见页面文本。
     *
     * @param result 浏览器动作结果
     * @return 文本摘要
     */
    private static String pageText(ToolResult result) {
        return ((ContentPart.Text) result.content().getFirst()).text();
    }

    /**
     * 发布固定完成事件，避免 UI smoke test 使用外部模型。
     */
    private static final class UiAgent implements Agent {
        /**
         * 返回固定文本答案。
         *
         * @param input Agent 输入
         * @param options 本次运行选项
         * @return 固定答案
         */
        @Override
        public AgentValue run(AgentInput input, RunOptions options) {
            return AgentValue.text("done");
        }

        /**
         * 返回最小同步运行结果。
         *
         * @param input Agent 输入
         * @param options 本次运行选项
         * @return 成功状态的固定结果
         */
        @Override
        public RunResult runFull(AgentInput input, RunOptions options) {
            UUID runId = UUID.randomUUID();
            Instant now = Instant.now();
            return new RunResult(
                    runId,
                    AgentValue.text("done"),
                    RunStatus.SUCCESS,
                    List.<MemoryStep>of(),
                    TokenUsage.of(1, 1),
                    now,
                    Duration.ZERO
            );
        }

        /**
         * 发布文本增量和成功终态供 HTTP 页面渲染。
         *
         * @param input Agent 输入
         * @param options 本次运行选项
         * @return 固定文本事件流
         */
        @Override
        public Flow.Publisher<AgentEvent> stream(AgentInput input, RunOptions options) {
            UUID runId = UUID.randomUUID();
            Instant started = Instant.now();
            AgentValue output = AgentValue.text("done");
            RunResult result = new RunResult(
                    runId,
                    output,
                    RunStatus.SUCCESS,
                    List.<MemoryStep>of(),
                    TokenUsage.of(1, 1),
                    started,
                    Duration.ofMillis(1)
            );
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean emitted;

                @Override
                public void request(long n) {
                    if (emitted) {
                        return;
                    }
                    emitted = true;
                    subscriber.onNext(new AgentEvent.RunStarted(runId, started));
                    subscriber.onNext(new AgentEvent.ModelDelta(
                            runId,
                            new ModelStreamEvent.MessageDelta("done")
                    ));
                    subscriber.onNext(new AgentEvent.RunCompleted(runId, output, result));
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                }
            });
        }
    }
}
