package org.fzb.smolagents.executor.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fzb.smolagents.core.AgentValue;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ExecutorException;
import org.fzb.smolagents.executor.ExecutorRequest;
import org.fzb.smolagents.executor.ExecutorResult;
import org.fzb.smolagents.model.ContentPart;
import org.fzb.smolagents.tool.FinalAnswerTool;
import org.fzb.smolagents.tool.Tool;
import org.fzb.smolagents.tool.ToolExecutionContext;
import org.fzb.smolagents.tool.ToolResult;
import org.fzb.smolagents.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用本机 Jupyter HTTP/WebSocket 假服务验证远程执行器协议和 provider hook。
 */
class RemoteJupyterExecutorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern MARKER = Pattern.compile("__sa_marker = \"([^\"]+)\"");
    private static final RemoteExecutorOptions OPTIONS = new RemoteExecutorOptions(
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            1024,
            16 * 1024,
            2048
    );

    @Test
    void allProviderAdaptersExecuteThroughJupyterAndReleaseTheirSandbox() throws Exception {
        try (LoopbackJupyterServer server = new LoopbackJupyterServer()) {
            for (ProviderKind kind : ProviderKind.values()) {
                server.mode = ServerMode.SUCCESS;
                ProviderStub provider = new ProviderStub(server.endpoint());
                int deletedBefore = server.deleteCount.get();
                try (RemoteJupyterExecutor executor = kind.create(provider, OPTIONS)) {
                    ExecutorResult result = executor.execute(new ExecutorRequest(
                            "counter = seed + 1\nprint('hello')\ncounter + 41",
                            Map.of("seed", new AgentValue(
                                    List.of(new ContentPart.Json(MAPPER.valueToTree(0))),
                                    MAPPER.valueToTree(0))),
                            Map.of(),
                            Duration.ofSeconds(2),
                            new CancellationToken()
                    ));

                    assertTrue(result.succeeded(), kind + " should return a successful executor result");
                    assertEquals("hello\n", result.stdout());
                    assertEquals(42, result.value().structuredContent().asInt());
                    assertEquals(1, result.updatedVariables().get("counter").structuredContent().asInt());
                }
                assertEquals(1, provider.openCount.get(), kind + " should open exactly one sandbox");
                assertEquals(1, provider.closeCount.get(), kind + " should release exactly one sandbox");
                assertEquals(deletedBefore + 1, server.deleteCount.get(), kind + " should delete its Jupyter kernel");
            }
        }
    }

    @Test
    void validatesRemoteToolArgumentsBeforeRunningTheJavaTool() throws Exception {
        try (LoopbackJupyterServer server = new LoopbackJupyterServer()) {
            server.mode = ServerMode.INVALID_TOOL_CALL;
            ProviderStub provider = new ProviderStub(server.endpoint());
            AtomicInteger calls = new AtomicInteger();
            Tool tool = new Tool() {
                private final ToolSpec spec = new ToolSpec("double", "Doubles a JSON integer", schema(), true);

                @Override
                public ToolSpec spec() {
                    return spec;
                }

                @Override
                public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
                    calls.incrementAndGet();
                    return ToolResult.text("unreachable");
                }
            };

            try (RemoteJupyterExecutor executor = new ModalExecutor(provider, OPTIONS)) {
                ExecutorResult result = executor.execute(new ExecutorRequest(
                        "double(unexpected=21)",
                        Map.of(),
                        Map.of("double", tool),
                        Duration.ofSeconds(2),
                        new CancellationToken()
                ));
                assertFalse(result.succeeded());
                assertTrue(server.toolReplyFailed);
                assertEquals(0, calls.get());
            }
        }
    }

    @Test
    void bridgesPythonToolCallsThroughJupyterStdinWithoutShellCommands() throws Exception {
        try (LoopbackJupyterServer server = new LoopbackJupyterServer()) {
            server.mode = ServerMode.TOOL_CALL;
            ProviderStub provider = new ProviderStub(server.endpoint());
            Tool tool = new Tool() {
                private final ToolSpec spec = new ToolSpec(
                        "double",
                        "Doubles a JSON integer",
                        schema(),
                        true
                );

                @Override
                public ToolSpec spec() {
                    return spec;
                }

                @Override
                public ToolResult execute(JsonNode arguments, ToolExecutionContext context) {
                    assertEquals(21, arguments.path("number").asInt());
                    assertNotNull(context.runId());
                    return ToolResult.text("42");
                }
            };

            try (RemoteJupyterExecutor executor = new ModalExecutor(provider, OPTIONS)) {
                ExecutorResult result = executor.execute(new ExecutorRequest(
                        "double(number=21)",
                        Map.of(),
                        Map.of("double", tool, "final_answer", new FinalAnswerTool()),
                        Duration.ofSeconds(2),
                        new CancellationToken()
                ));

                assertTrue(result.succeeded());
                assertEquals("42", result.value().plainText().orElseThrow());
                assertTrue(server.inputReplyReceived);
            }
            assertEquals(1, provider.closeCount.get());
        }
    }

    @Test
    void finalAnswerToolIsBridgedAndPreservesExecutorResultFlag() throws Exception {
        try (LoopbackJupyterServer server = new LoopbackJupyterServer()) {
            server.mode = ServerMode.FINAL_ANSWER;
            ProviderStub provider = new ProviderStub(server.endpoint());
            try (RemoteJupyterExecutor executor = new E2BExecutor(provider, OPTIONS)) {
                ExecutorResult result = executor.execute(new ExecutorRequest(
                        "final_answer('done')",
                        Map.of(),
                        Map.of("final_answer", new FinalAnswerTool()),
                        Duration.ofSeconds(2),
                        new CancellationToken()
                ));

                assertTrue(result.succeeded());
                assertTrue(result.finalAnswer());
                assertEquals("done", result.value().plainText().orElseThrow());
                assertTrue(server.inputReplyReceived);
            }
        }
    }

    @Test
    void resetRecreatesKernelWithoutReopeningOrLeakingProviderSandbox() throws Exception {
        try (LoopbackJupyterServer server = new LoopbackJupyterServer()) {
            ProviderStub provider = new ProviderStub(server.endpoint());
            int createdBefore = server.createCount.get();
            int deletedBefore = server.deleteCount.get();
            try (RemoteJupyterExecutor executor = new BlaxelExecutor(provider, OPTIONS)) {
                executor.reset();
                assertEquals(1, provider.openCount.get());
                assertEquals(createdBefore + 2, server.createCount.get());
                assertEquals(deletedBefore + 1, server.deleteCount.get());
            }
            assertEquals(1, provider.closeCount.get());
            assertEquals(deletedBefore + 2, server.deleteCount.get());
        }
    }

    @Test
    void boundsOutputAndReturnsTimeoutAsExecutorFailure() throws Exception {
        try (LoopbackJupyterServer server = new LoopbackJupyterServer()) {
            server.mode = ServerMode.SUCCESS;
            ProviderStub provider = new ProviderStub(server.endpoint());
            RemoteExecutorOptions smallOutput = new RemoteExecutorOptions(
                    Duration.ofSeconds(2), Duration.ofSeconds(2), 5, 16 * 1024, 2048);
            try (RemoteJupyterExecutor executor = new BlaxelExecutor(provider, smallOutput)) {
                ExecutorResult result = executor.execute(new ExecutorRequest(
                        "print('bounded output')\n42",
                        Map.of(),
                        Map.of(),
                        Duration.ofSeconds(2),
                        new CancellationToken()
                ));
                assertTrue(result.succeeded());
                assertEquals(5, result.stdout().length());
                assertTrue(result.stdout().contains("["));
            }

            server.mode = ServerMode.STALL;
            ProviderStub timeoutProvider = new ProviderStub(server.endpoint());
            try (RemoteJupyterExecutor executor = new ModalExecutor(timeoutProvider, OPTIONS)) {
                ExecutorResult timeout = executor.execute(new ExecutorRequest(
                        "while True: pass",
                        Map.of(),
                        Map.of(),
                        Duration.ofMillis(100),
                        new CancellationToken()
                ));
                assertFalse(timeout.succeeded());
                assertEquals("RemoteExecutionTimeout", timeout.failure().type());
            }
            assertTrue(server.interruptCount.get() >= 1);
        }
    }

    @Test
    void closesProviderSessionWhenKernelCreationResponseExceedsLimit() throws Exception {
        try (LoopbackJupyterServer server = new LoopbackJupyterServer()) {
            server.mode = ServerMode.OVERSIZED_CREATE;
            ProviderStub provider = new ProviderStub(server.endpoint());
            RemoteExecutorOptions limitedHttp = new RemoteExecutorOptions(
                    Duration.ofSeconds(2), Duration.ofSeconds(2), 1024, 16 * 1024, 1024);

            ExecutorException error = assertThrows(
                    ExecutorException.class,
                    () -> new E2BExecutor(provider, limitedHttp)
            );

            assertTrue(error.getMessage().contains("E2B"));
            assertEquals(1, provider.closeCount.get());
        }
    }

    @Test
    void rejectsUnsafeGatewayUrisAndInvalidBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new JupyterGatewayEndpoint(URI.create("file:///api/kernels")));
        assertThrows(IllegalArgumentException.class,
                () -> new JupyterGatewayEndpoint(URI.create("https://user:pass@example.test/api/kernels")));
        assertThrows(IllegalArgumentException.class,
                () -> new RemoteExecutorOptions(Duration.ZERO, Duration.ofSeconds(1), 100, 1024, 1024));
        ExecutorException missingHook = assertThrows(ExecutorException.class, () -> new E2BExecutor(null));
        assertTrue(missingHook.getMessage().contains("explicit sandbox session provider"));
    }

    private static ObjectNode schema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("number", MAPPER.createObjectNode().put("type", "integer"));
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("number");
        return schema;
    }

    private enum ProviderKind {
        E2B {
            @Override
            RemoteJupyterExecutor create(ProviderStub provider, RemoteExecutorOptions options) {
                return new E2BExecutor(provider, options);
            }
        },
        MODAL {
            @Override
            RemoteJupyterExecutor create(ProviderStub provider, RemoteExecutorOptions options) {
                return new ModalExecutor(provider, options);
            }
        },
        BLAXEL {
            @Override
            RemoteJupyterExecutor create(ProviderStub provider, RemoteExecutorOptions options) {
                return new BlaxelExecutor(provider, options);
            }
        };

        abstract RemoteJupyterExecutor create(ProviderStub provider, RemoteExecutorOptions options);
    }

    private static final class ProviderStub implements E2BSessionProvider, ModalSessionProvider, BlaxelSessionProvider {
        private final JupyterGatewayEndpoint endpoint;
        private final AtomicInteger openCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();

        private ProviderStub(JupyterGatewayEndpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public JupyterGatewayEndpoint openSandbox() {
            openCount.incrementAndGet();
            return endpoint;
        }

        @Override
        public void closeSandbox(JupyterGatewayEndpoint ignored) {
            closeCount.incrementAndGet();
        }
    }

    private enum ServerMode {
        SUCCESS,
        TOOL_CALL,
        FINAL_ANSWER,
        INVALID_TOOL_CALL,
        STALL,
        OVERSIZED_CREATE
    }

    private static final class LoopbackJupyterServer implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService handlers = Executors.newCachedThreadPool();
        private final Thread acceptThread;
        private final AtomicInteger createCount = new AtomicInteger();
        private final AtomicInteger deleteCount = new AtomicInteger();
        private final AtomicInteger interruptCount = new AtomicInteger();
        private volatile ServerMode mode = ServerMode.SUCCESS;
        private volatile boolean inputReplyReceived;
        private volatile boolean toolReplyFailed;

        private LoopbackJupyterServer() throws IOException {
            server = new ServerSocket(0, 32, java.net.InetAddress.getLoopbackAddress());
            acceptThread = new Thread(this::acceptConnections, "loopback-jupyter-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        private JupyterGatewayEndpoint endpoint() {
            return new JupyterGatewayEndpoint(
                    URI.create("http://127.0.0.1:" + server.getLocalPort()
                            + "/port/8888/api/kernels?token=loopback-token"),
                    Map.of("X-Provider-Test", "present")
            );
        }

        private void acceptConnections() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    handlers.submit(() -> handle(socket));
                } catch (IOException error) {
                    if (!server.isClosed()) {
                        throw new IllegalStateException(error);
                    }
                }
            }
        }

        private void handle(Socket socket) {
            try (socket) {
                socket.setSoTimeout(1500);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new BufferedInputStream(socket.getInputStream()), StandardCharsets.ISO_8859_1));
                String requestLine = reader.readLine();
                if (requestLine == null) {
                    return;
                }
                Map<String, String> headers = new LinkedHashMap<>();
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int separator = line.indexOf(':');
                    if (separator > 0) {
                        headers.put(line.substring(0, separator).toLowerCase(),
                                line.substring(separator + 1).trim());
                    }
                }
                if ("websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                    handleWebSocket(socket, reader, headers);
                } else {
                    handleHttp(socket, requestLine, headers);
                }
            } catch (Exception ignored) {
            }
        }

        private void handleHttp(Socket socket, String requestLine, Map<String, String> headers) throws IOException {
            String[] parts = requestLine.split(" ", 3);
            String method = parts[0];
            String path = parts[1];
            if (!"present".equals(headers.get("x-provider-test"))) {
                writeHttp(socket.getOutputStream(), 401, "missing provider header");
            } else if (method.equals("POST") && path.startsWith("/port/8888/api/kernels?")) {
                if (mode == ServerMode.OVERSIZED_CREATE) {
                    writeHttp(socket.getOutputStream(), 201, "x".repeat(2048));
                } else {
                    int id = createCount.incrementAndGet();
                    writeHttp(socket.getOutputStream(), 201, "{\"id\":\"loopback-kernel-" + id + "\"}");
                }
            } else if (method.equals("DELETE") && path.contains("/api/kernels/loopback-kernel-")) {
                deleteCount.incrementAndGet();
                writeHttp(socket.getOutputStream(), 204, "");
            } else if (method.equals("POST")
                    && path.contains("/api/kernels/loopback-kernel-")
                    && path.contains("/interrupt")) {
                interruptCount.incrementAndGet();
                writeHttp(socket.getOutputStream(), 204, "");
            } else {
                writeHttp(socket.getOutputStream(), 404, "not found");
            }
        }

        private void handleWebSocket(Socket socket, BufferedReader reader, Map<String, String> headers) throws Exception {
            String key = headers.get("sec-websocket-key");
            String accept = Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            output.flush();

            InputStream input = socket.getInputStream();
            ObjectNode execute = (ObjectNode) MAPPER.readTree(readClientFrame(input));
            String messageId = execute.path("header").path("msg_id").asText();
            if (mode == ServerMode.STALL) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            if (mode == ServerMode.TOOL_CALL || mode == ServerMode.FINAL_ANSWER
                    || mode == ServerMode.INVALID_TOOL_CALL) {
                ObjectNode call = MAPPER.createObjectNode()
                        .put("name", mode == ServerMode.FINAL_ANSWER ? "final_answer" : "double");
                call.set("arguments", mode == ServerMode.FINAL_ANSWER
                        ? MAPPER.createObjectNode().put("answer", "done")
                        : mode == ServerMode.INVALID_TOOL_CALL
                        ? MAPPER.createObjectNode().put("unexpected", 21)
                        : MAPPER.createObjectNode().put("number", 21));
                ObjectNode prompt = jupyterMessage("input_request", messageId);
                ((ObjectNode) prompt.path("content")).put(
                        "prompt", "__SMOLAGENTS_TOOL__" + MAPPER.writeValueAsString(call));
                prompt.set("header", MAPPER.createObjectNode().put("msg_id", "input-message"));
                writeFrame(output, MAPPER.writeValueAsBytes(prompt));
                JsonNode reply = MAPPER.readTree(readClientFrame(input));
                inputReplyReceived = "input_reply".equals(reply.path("header").path("msg_type").asText())
                        && "input-message".equals(reply.path("parent_header").path("msg_id").asText());
                JsonNode toolReply = MAPPER.readTree(reply.path("content").path("value").asText(""));
                toolReplyFailed = !toolReply.path("ok").asBoolean(false);
            }
            String code = execute.path("content").path("code").asText();
            Matcher markerMatcher = MARKER.matcher(code);
            assertTrue(markerMatcher.find(), "generated Python cell must contain a unique result marker");
            String marker = markerMatcher.group(1);

            writeFrame(output, MAPPER.writeValueAsBytes(jupyterMessage("stream", "other-request", "ignored")));
            writeFrame(output, MAPPER.writeValueAsBytes(jupyterMessage("stream", messageId, "hello\n")));
            ObjectNode result = MAPPER.createObjectNode()
                    .put("ok", mode != ServerMode.INVALID_TOOL_CALL)
                    .put("valueIsJson", true)
                    .put("isFinalAnswer", false);
            if (mode == ServerMode.INVALID_TOOL_CALL) {
                result.put("errorType", "RuntimeError").put("error", "tool schema validation failed");
            }
            if (mode == ServerMode.TOOL_CALL || mode == ServerMode.FINAL_ANSWER) {
                result.put("value", mode == ServerMode.FINAL_ANSWER ? "done" : "42");
            } else {
                result.put("value", 42);
            }
            result.set("variables", MAPPER.createObjectNode().put("counter", 1));
            result.put("isFinalAnswer", mode == ServerMode.FINAL_ANSWER);
            String payload = marker + MAPPER.writeValueAsString(result) + "\n";
            int split = Math.min(payload.length() - 3, marker.length() + 20);
            writeFrame(output, MAPPER.writeValueAsBytes(jupyterMessage("stream", messageId, payload.substring(0, split))));
            writeFrame(output, MAPPER.writeValueAsBytes(jupyterMessage("stream", messageId, payload.substring(split))));
            ObjectNode idle = jupyterMessage("status", messageId);
            ((ObjectNode) idle.path("content")).put("execution_state", "idle");
            writeFrame(output, MAPPER.writeValueAsBytes(idle));
            readClientCloseFrame(input);
            output.write(new byte[]{(byte) 0x88, 0});
            output.flush();
        }

        private ObjectNode jupyterMessage(String type, String parentId) {
            return jupyterMessage(type, parentId, null);
        }

        private ObjectNode jupyterMessage(String type, String parentId, String text) {
            ObjectNode message = MAPPER.createObjectNode().put("msg_type", type);
            message.set("parent_header", MAPPER.createObjectNode().put("msg_id", parentId));
            ObjectNode content = MAPPER.createObjectNode();
            if (text != null) {
                content.put("text", text);
            }
            message.set("content", content);
            return message;
        }

        private static String readClientFrame(InputStream input) throws IOException {
            int first = input.read();
            int second = input.read();
            if (first < 0 || second < 0 || (first & 0x0f) != 1) {
                throw new IOException("expected a WebSocket text frame");
            }
            long length = second & 0x7f;
            if (length == 126) {
                length = ((long) input.read() << 8) | input.read();
            } else if (length == 127) {
                length = 0;
                for (int index = 0; index < 8; index++) {
                    length = (length << 8) | input.read();
                }
            }
            if ((second & 0x80) == 0 || length > 1024 * 1024) {
                throw new IOException("invalid or oversized client WebSocket frame");
            }
            byte[] mask = input.readNBytes(4);
            byte[] payload = input.readNBytes(Math.toIntExact(length));
            if (mask.length != 4 || payload.length != length) {
                throw new IOException("truncated WebSocket frame");
            }
            for (int index = 0; index < payload.length; index++) {
                payload[index] ^= mask[index % 4];
            }
            return new String(payload, StandardCharsets.UTF_8);
        }

        private static void readClientCloseFrame(InputStream input) throws IOException {
            int first = input.read();
            int second = input.read();
            if (first < 0 || second < 0 || (first & 0x0f) != 8 || (second & 0x80) == 0) {
                throw new IOException("expected a masked WebSocket close frame");
            }
            long length = second & 0x7f;
            if (length == 126) {
                length = ((long) input.read() << 8) | input.read();
            } else if (length == 127) {
                length = 0;
                for (int index = 0; index < 8; index++) {
                    length = (length << 8) | input.read();
                }
            }
            byte[] mask = input.readNBytes(4);
            byte[] payload = input.readNBytes(Math.toIntExact(length));
            if (mask.length != 4 || payload.length != length) {
                throw new IOException("truncated WebSocket close frame");
            }
        }

        private static void writeFrame(OutputStream output, byte[] payload) throws IOException {
            output.write(0x81);
            if (payload.length < 126) {
                output.write(payload.length);
            } else {
                output.write(126);
                output.write((payload.length >>> 8) & 0xff);
                output.write(payload.length & 0xff);
            }
            output.write(payload);
            output.flush();
        }

        private static void writeHttp(OutputStream output, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String description = status == 201 ? "Created" : status == 204 ? "No Content"
                    : status == 401 ? "Unauthorized" : "Not Found";
            output.write(("HTTP/1.1 " + status + " " + description + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            output.write(bytes);
            output.flush();
        }

        @Override
        public void close() throws Exception {
            server.close();
            handlers.shutdownNow();
            acceptThread.join(1000);
        }
    }
}
