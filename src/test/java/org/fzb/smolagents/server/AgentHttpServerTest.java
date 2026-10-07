package org.fzb.smolagents.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.agent.Agent;
import org.fzb.smolagents.agent.MemoryBackedAgent;
import org.fzb.smolagents.core.AgentInput;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.RunOptions;
import org.fzb.smolagents.core.RunResult;
import org.fzb.smolagents.core.RunStatus;
import org.fzb.smolagents.event.AgentEvent;
import org.fzb.smolagents.memory.MemoryStep;
import org.fzb.smolagents.memory.AgentMemory;
import org.fzb.smolagents.memory.TaskStep;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.model.ModelStreamEvent;
import org.fzb.smolagents.model.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用本机 HTTP client 验证 loopback Agent SSE API 的运行、认证和输入边界。
 */
class AgentHttpServerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 验证健康检查、结构化 SSE 事件和 Agent 输入转换。
     *
     * @throws Exception 本地 HTTP 请求或 server 生命周期处理失败
     */
    @Test
    void streamsAgentEventsFromLoopbackHttpEndpoint() throws Exception {
        StubAgent agent = new StubAgent();
        try (AgentHttpServer server = server(agent, null)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(uri(server, "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, health.statusCode());
            assertEquals("ok", MAPPER.readTree(health.body()).path("status").asText());

            HttpResponse<String> stream = client.send(
                    HttpRequest.newBuilder(uri(server, "/api/run/stream"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"task\":\"answer\",\"additionalArguments\":{\"count\":3}}"
                            ))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, stream.statusCode());
            assertTrue(stream.headers().firstValue("Content-Type").orElse("")
                    .startsWith("text/event-stream"));
            assertTrue(stream.body().contains("event: user_task"));
            assertTrue(stream.body().contains("\"task\":\"answer\""));
            assertTrue(stream.body().contains("event: model_delta"));
            assertTrue(stream.body().contains("\"text\":\"working\""));
            assertTrue(stream.body().contains("event: run_completed"));
            assertTrue(stream.body().contains("\"status\":\"SUCCESS\""));
            assertEquals("answer", agent.lastInput.task());
            assertEquals(3, agent.lastInput.additionalArguments().get("count").asInt());

            HttpResponse<String> continued = client.send(
                    HttpRequest.newBuilder(uri(server, "/api/run/stream"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"task\":\"follow up\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, continued.statusCode());
            assertEquals("follow up", agent.lastInput.task());
            assertFalse(agent.lastOptions.reset());
            HttpResponse<String> history = client.send(
                    HttpRequest.newBuilder(uri(server, "/api/history")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, history.statusCode());
            assertTrue(history.body().contains("answer"));
            assertTrue(history.body().contains("follow up"));

            HttpResponse<String> newConversation = client.send(
                    HttpRequest.newBuilder(uri(server, "/api/run/stream"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"task\":\"new conversation\",\"reset\":true}"
                            ))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, newConversation.statusCode());
            assertEquals("new conversation", agent.lastInput.task());
            assertTrue(agent.lastOptions.reset());
            HttpResponse<String> resetHistory = client.send(
                    HttpRequest.newBuilder(uri(server, "/api/history")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertFalse(resetHistory.body().contains("follow up"));
            assertTrue(resetHistory.body().contains("new conversation"));
        }
    }

    /**
     * 验证多会话 factory 为不同会话创建独立 Agent、历史隔离并可释放会话资源。
     *
     * @throws Exception 本地 HTTP 请求或 server 生命周期处理失败
     */
    @Test
    void isolatesAgentMemoryAndHistoryAcrossWebSessions() throws Exception {
        ConcurrentHashMap<String, StubAgent> agents = new ConcurrentHashMap<>();
        try (AgentHttpServer server = new AgentHttpServer(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                sessionId -> {
                    StubAgent agent = new StubAgent();
                    agents.put(sessionId, agent);
                    return agent;
                },
                null
        )) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> initialSessions = client.send(
                    HttpRequest.newBuilder(uri(server, "/api/sessions")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, initialSessions.statusCode());
            assertEquals(1, MAPPER.readTree(initialSessions.body()).size());
            HttpResponse<String> crossOriginCreate = client.send(
                    HttpRequest.newBuilder(uri(server, "/api/sessions"))
                            .header("Origin", "http://attacker.example")
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(403, crossOriginCreate.statusCode());

            HttpResponse<String> created = client.send(
                    HttpRequest.newBuilder(uri(server, "/api/sessions"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(201, created.statusCode());
            String newSessionId = MAPPER.readTree(created.body()).path("id").asText();
            assertFalse(newSessionId.isBlank());
            assertEquals(2, agents.size());

            assertEquals(200, submitTask(client, server, "default", "default only").statusCode());
            assertEquals(200, submitTask(client, server, newSessionId, "separate session").statusCode());
            assertEquals("default only", agents.get("default").lastInput.task());
            assertEquals("separate session", agents.get(newSessionId).lastInput.task());

            HttpResponse<String> defaultHistory = client.send(
                    HttpRequest.newBuilder(uri(server, "/api/history?sessionId=default")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> separateHistory = client.send(
                    HttpRequest.newBuilder(uri(server, "/api/history?sessionId=" + newSessionId)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertTrue(defaultHistory.body().contains("default only"));
            assertFalse(defaultHistory.body().contains("separate session"));
            assertTrue(separateHistory.body().contains("separate session"));
            assertFalse(separateHistory.body().contains("default only"));

            HttpResponse<String> crossOriginDelete = client.send(
                    HttpRequest.newBuilder(uri(server, "/api/sessions/" + newSessionId))
                            .header("Origin", "http://attacker.example")
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(403, crossOriginDelete.statusCode());

            HttpResponse<String> deleted = client.send(
                    HttpRequest.newBuilder(uri(server, "/api/sessions/" + newSessionId))
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(204, deleted.statusCode());
            assertEquals(404, client.send(
                    HttpRequest.newBuilder(uri(server, "/api/history?sessionId=" + newSessionId)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            ).statusCode());
        }
    }

    /**
     * 验证实时 SSE 保留小型媒体输出，而历史存储只保存媒体元数据。
     *
     * @throws Exception 本地 HTTP 请求或 server 生命周期处理失败
     */
    @Test
    void retainsMediaInLiveOutputButOmitsBinaryBytesFromSessionHistory() throws Exception {
        try (AgentHttpServer server = server(new MediaAgent(), null)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> stream = client.send(
                    HttpRequest.newBuilder(uri(server, "/api/run/stream"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"task\":\"return image\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, stream.statusCode());
            assertTrue(stream.body().contains("\"base64\":\"AQID\""));

            HttpResponse<String> history = client.send(
                    HttpRequest.newBuilder(uri(server, "/api/history")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertFalse(history.body().contains("AQID"));
            assertTrue(history.body().contains("\"binaryOmitted\":true"));
        }
    }

    /**
     * 验证显式配置的本地目录可跨 server 重启恢复事件历史及内置 AgentMemory。
     *
     * @param sessionDirectory JUnit 创建的隔离临时目录
     * @throws Exception 本地文件或 HTTP 生命周期失败
     */
    @Test
    void restoresWebSessionHistoryAndMemoryFromConfiguredDirectory(@TempDir Path sessionDirectory) throws Exception {
        ConcurrentHashMap<String, PersistableAgent> firstAgents = new ConcurrentHashMap<>();
        String restoredSessionId;
        try (AgentHttpServer first = new AgentHttpServer(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                sessionId -> {
                    PersistableAgent agent = new PersistableAgent();
                    firstAgents.put(sessionId, agent);
                    return agent;
                },
                null,
                sessionDirectory
        )) {
            first.start();
            HttpClient client = HttpClient.newHttpClient();
            assertEquals(200, submitTask(client, first, "default", "persist this task").statusCode());
            HttpResponse<String> created = client.send(
                    HttpRequest.newBuilder(uri(first, "/api/sessions"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(201, created.statusCode());
            restoredSessionId = MAPPER.readTree(created.body()).path("id").asText();
            assertEquals(200, submitTask(client, first, restoredSessionId, "persist second task").statusCode());
        }

        ConcurrentHashMap<String, PersistableAgent> restoredAgents = new ConcurrentHashMap<>();
        try (AgentHttpServer restored = new AgentHttpServer(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                sessionId -> {
                    PersistableAgent agent = new PersistableAgent();
                    restoredAgents.put(sessionId, agent);
                    return agent;
                },
                null,
                sessionDirectory
        )) {
            restored.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> sessions = client.send(
                    HttpRequest.newBuilder(uri(restored, "/api/sessions")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(2, MAPPER.readTree(sessions.body()).size());
            assertTrue(restoredAgents.get("default").memory().snapshot().stream()
                    .filter(TaskStep.class::isInstance)
                    .map(TaskStep.class::cast)
                    .anyMatch(step -> step.task().equals("persist this task")));
            assertTrue(restoredAgents.get(restoredSessionId).memory().snapshot().stream()
                    .filter(TaskStep.class::isInstance)
                    .map(TaskStep.class::cast)
                    .anyMatch(step -> step.task().equals("persist second task")));

            HttpResponse<String> restoredHistory = client.send(
                    HttpRequest.newBuilder(uri(
                            restored,
                            "/api/history?sessionId=" + restoredSessionId
                    )).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertTrue(restoredHistory.body().contains("persist second task"));
            assertFalse(restoredHistory.body().contains("persist this task"));
            HttpResponse<String> deleted = client.send(
                    HttpRequest.newBuilder(uri(restored, "/api/sessions/" + restoredSessionId))
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(204, deleted.statusCode());
            assertFalse(Files.exists(sessionDirectory.resolve(restoredSessionId + ".json")));
            assertTrue(restoredAgents.get(restoredSessionId).closed.get());
        }
        assertTrue(restoredAgents.get("default").closed.get());
    }

    /**
     * 验证本机页面从同源脚本消费 SSE，并以 textContent 安全展示模型文本。
     *
     * @throws Exception 本地 HTTP 请求或 server 生命周期处理失败
     */
    @Test
    void servesMinimalSameOriginChatPageWithSafeTextRendering() throws Exception {
        try (AgentHttpServer server = server(new StubAgent(), null)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> page = client.send(
                    HttpRequest.newBuilder(uri(server, "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> script = client.send(
                    HttpRequest.newBuilder(uri(server, "/app.js")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, page.statusCode());
            assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("")
                    .contains("default-src 'none'"));
            assertTrue(page.body().contains("/app.js"));
            assertTrue(page.body().contains("id=\"session\""));
            assertEquals(200, script.statusCode());
            assertTrue(script.body().contains("document.createTextNode"));
            assertTrue(script.body().contains("document.createElement(part.type ==="));
            assertFalse(script.body().contains("innerHTML"));
        }
    }

    /**
     * 验证可选 bearer token、畸形 JSON 和公网 bind 限制。
     *
     * @throws Exception 本地 HTTP 请求或 server 生命周期处理失败
     */
    @Test
    void enforcesLoopbackAuthenticationAndInputValidation() throws Exception {
        try (AgentHttpServer server = server(new StubAgent(), "api-secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest unauthenticated = HttpRequest.newBuilder(uri(server, "/health")).GET().build();
            assertEquals(401, client.send(unauthenticated, HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpRequest malformed = HttpRequest.newBuilder(uri(server, "/api/run/stream"))
                    .header("Authorization", "Bearer api-secret")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{broken"))
                    .build();
            HttpResponse<String> badRequest = client.send(malformed, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, badRequest.statusCode());

            HttpRequest simpleContentType = HttpRequest.newBuilder(uri(server, "/api/run/stream"))
                    .header("Authorization", "Bearer api-secret")
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"task\":\"cross-site\"}"))
                    .build();
            assertEquals(415, client.send(simpleContentType, HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpRequest crossOrigin = HttpRequest.newBuilder(uri(server, "/api/run/stream"))
                    .header("Authorization", "Bearer api-secret")
                    .header("Origin", "http://attacker.example")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"task\":\"cross-site\"}"))
                    .build();
            assertEquals(403, client.send(crossOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());

            assertThrows(
                    org.fzb.smolagents.core.ConfigurationException.class,
                    () -> new AgentHttpServer(
                            new InetSocketAddress(InetAddress.getByName("8.8.8.8"), 0),
                            new StubAgent(),
                            null
                    )
            );
        }
        assertDoesNotThrow(() -> server(new StubAgent(), null).close());
    }

    /**
     * 验证 SSE 客户端断开后 server 的 heartbeat 写失败会取消正在运行的 Agent。
     *
     * @throws Exception 本地 HTTP 流或断连信号处理失败
     */
    @Test
    void disconnectingSseClientCancelsTheActiveAgentRun() throws Exception {
        BlockingAgent agent = new BlockingAgent();
        try (AgentHttpServer server = server(agent, null)) {
            server.start();
            HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri(server, "/api/run/stream"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"task\":\"long run\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            assertEquals(200, response.statusCode());
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8)
            )) {
                String line;
                do {
                    line = reader.readLine();
                } while (line != null && !line.startsWith("data:"));
                assertTrue(line != null);
            }
            assertTrue(agent.subscriptionCancelled.await(4, TimeUnit.SECONDS));
            assertTrue(agent.cancellationObserved.await(4, TimeUnit.SECONDS));
        }
    }

    /**
     * 创建绑定临时 loopback 端口的 HTTP server。
     *
     * @param agent 被测 Agent
     * @param token 可选 bearer token
     * @return 尚未启动的 HTTP server
     * @throws Exception 本地地址解析或 server 创建失败
     */
    private static AgentHttpServer server(Agent agent, String token) throws Exception {
        return new AgentHttpServer(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                agent,
                token
        );
    }

    /**
     * 构造指向测试 server 的本机 HTTP URL。
     *
     * @param server 测试 server
     * @param path endpoint path
     * @return 绝对 loopback URL
     */
    private static URI uri(AgentHttpServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.address().getPort() + path);
    }

    /**
     * 向指定会话提交 JSON task 并读取完整 SSE 响应。
     *
     * @param client 本机 HTTP client
     * @param server 被测 server
     * @param sessionId 目标会话 ID
     * @param task 任务文本
     * @return HTTP 响应
     * @throws Exception 网络或响应读取失败
     */
    private static HttpResponse<String> submitTask(
            HttpClient client,
            AgentHttpServer server,
            String sessionId,
            String task
    ) throws Exception {
        String json = MAPPER.createObjectNode()
                .put("sessionId", sessionId)
                .put("task", task)
                .toString();
        return client.send(
                HttpRequest.newBuilder(uri(server, "/api/run/stream"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    /**
     * 记录输入并发布固定 SSE 事件的 Agent 替身。
     */
    private static final class StubAgent implements Agent {
        private volatile AgentInput lastInput;
        private volatile RunOptions lastOptions;

        @Override
        public AgentValue run(AgentInput input, RunOptions options) {
            throw new UnsupportedOperationException("the test exercises event streaming");
        }

        @Override
        public RunResult runFull(AgentInput input, RunOptions options) {
            throw new UnsupportedOperationException("the test exercises event streaming");
        }

        @Override
        public Flow.Publisher<AgentEvent> stream(AgentInput input, RunOptions options) {
            lastInput = input;
            lastOptions = options;
            UUID runId = UUID.randomUUID();
            Instant started = Instant.now();
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
                            new ModelStreamEvent.MessageDelta("working")
                    ));
                    subscriber.onNext(new AgentEvent.RunCompleted(
                            runId,
                            AgentValue.text("done"),
                            new RunResult(
                                    runId,
                                    AgentValue.text("done"),
                                    RunStatus.SUCCESS,
                                    List.<MemoryStep>of(),
                                    TokenUsage.of(1, 1),
                                    started,
                                    Duration.ofMillis(10)
                            )
                    ));
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                }
            });
        }
    }

    /**
     * 发布一个增量后持续运行，直到收到 HTTP 订阅的取消信号。
     */
    private static final class BlockingAgent implements Agent {
        private final CountDownLatch subscriptionCancelled = new CountDownLatch(1);
        private final CountDownLatch cancellationObserved = new CountDownLatch(1);

        @Override
        public AgentValue run(AgentInput input, RunOptions options) {
            throw new UnsupportedOperationException("the test exercises event streaming");
        }

        @Override
        public RunResult runFull(AgentInput input, RunOptions options) {
            throw new UnsupportedOperationException("the test exercises event streaming");
        }

        @Override
        public Flow.Publisher<AgentEvent> stream(AgentInput input, RunOptions options) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean started;

                @Override
                public synchronized void request(long n) {
                    if (started) {
                        return;
                    }
                    started = true;
                    Thread.ofVirtual().start(() -> {
                        UUID runId = UUID.randomUUID();
                        subscriber.onNext(new AgentEvent.RunStarted(runId, Instant.now()));
                        subscriber.onNext(new AgentEvent.ModelDelta(
                                runId,
                                new ModelStreamEvent.MessageDelta("first")
                        ));
                        while (!options.cancellation().isCancelled()) {
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                break;
                            }

                        }
                        cancellationObserved.countDown();
                        subscriber.onComplete();
                    });
                }

                @Override
                public void cancel() {
                    options.cancellation().cancel();
                    subscriptionCancelled.countDown();
                }
            });
        }
    }

    /**
     * 发布带 PNG bytes 的终态，用于区分实时 SSE 与历史存储的媒体策略。
     */
    private static final class MediaAgent implements Agent {
        private final AgentValue output = new AgentValue(
                List.of(new ContentPart.Image(null, "image/png", new byte[]{1, 2, 3})),
                null
        );

        /**
         * 返回该测试 Agent 固定的图片值。
         *
         * @param input Agent 输入
         * @param options 运行选项
         * @return 图片内容值
         */
        @Override
        public AgentValue run(AgentInput input, RunOptions options) {
            return output;
        }

        /**
         * 此测试只覆盖事件流入口。
         *
         * @param input Agent 输入
         * @param options 运行选项
         * @return 不适用
         */
        @Override
        public RunResult runFull(AgentInput input, RunOptions options) {
            throw new UnsupportedOperationException("the test exercises event streaming");
        }

        /**
         * 发布固定的图片完成事件。
         *
         * @param input Agent 输入
         * @param options 运行选项
         * @return 带 PNG 内容的确定性事件流
         */
        @Override
        public Flow.Publisher<AgentEvent> stream(AgentInput input, RunOptions options) {
            UUID runId = UUID.randomUUID();
            Instant started = Instant.now();
            RunResult result = new RunResult(
                    runId,
                    output,
                    RunStatus.SUCCESS,
                    List.<MemoryStep>of(),
                    TokenUsage.of(1, 1),
                    started,
                    Duration.ofMillis(5)
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
                    subscriber.onNext(new AgentEvent.RunCompleted(runId, output, result));
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                }
            });
        }
    }

    /**
     * 具备可恢复 AgentMemory 的确定性会话替身。
     */
    private static final class PersistableAgent implements MemoryBackedAgent, AutoCloseable {
        private static final String SYSTEM_PROMPT = "persistent-session-test";
        private AgentMemory memory = new AgentMemory(SYSTEM_PROMPT);
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * 返回该会话的可恢复记忆。
         *
         * @return 当前 AgentMemory
         */
        @Override
        public AgentMemory memory() {
            return memory;
        }

        /**
         * 校验系统提示词后恢复安全解码的记忆。
         *
         * @param restored 已解码记忆
         */
        @Override
        public void restoreMemory(AgentMemory restored) {
            if (!SYSTEM_PROMPT.equals(restored.systemPrompt())) {
                throw new IllegalArgumentException("unexpected test system prompt");
            }
            memory = restored;
        }

        /**
         * 标记测试会话已由 factory 释放。
         */
        @Override
        public void close() {
            closed.set(true);
        }

        /**
         * 返回固定文本答案。
         *
         * @param input Agent 输入
         * @param options 运行选项
         * @return 确定性文本
         */
        @Override
        public AgentValue run(AgentInput input, RunOptions options) {
            return AgentValue.text("done");
        }

        /**
         * 此替身只验证事件流 API。
         *
         * @param input Agent 输入
         * @param options 运行选项
         * @return 不适用
         */
        @Override
        public RunResult runFull(AgentInput input, RunOptions options) {
            throw new UnsupportedOperationException("the test exercises event streaming");
        }

        /**
         * 将本轮任务加入记忆并发布 run completed 事件。
         *
         * @param input Agent 输入
         * @param options 运行选项
         * @return 本地确定性运行事件
         */
        @Override
        public Flow.Publisher<AgentEvent> stream(AgentInput input, RunOptions options) {
            int stepNumber = memory.snapshot().size() + 1;
            memory.append(new TaskStep(stepNumber, Instant.now(), input.task()));
            UUID runId = UUID.randomUUID();
            Instant started = Instant.now();
            AgentValue output = AgentValue.text("done");
            RunResult result = new RunResult(
                    runId,
                    output,
                    RunStatus.SUCCESS,
                    memory.snapshot(),
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
