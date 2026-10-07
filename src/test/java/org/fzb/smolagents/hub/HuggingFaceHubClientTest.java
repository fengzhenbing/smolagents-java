package org.fzb.smolagents.hub;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.HubRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用本机 HTTP stub 验证 Hub Space 元数据发现及受限文件下载。
 */
class HuggingFaceHubClientTest {
    private static final byte[] TOOL_SOURCE = "print('review me')".getBytes(StandardCharsets.UTF_8);

    @TempDir
    private Path temporaryDirectory;

    /**
     * 验证 Space 信息和树列表只暴露元数据，且 JSON 快照不可被调用方反向修改。
     *
     * @throws Exception stub server 启动或处理失败时抛出
     */
    @Test
    void inspectsSpaceAndListsOnlyFilesWithoutExecutingRemoteContent() throws Exception {
        try (HubStub server = new HubStub()) {
            HuggingFaceHubClient client = client(server, "hub-token", 1_024);

            HuggingFaceHubClient.SpaceMetadata metadata = client.inspectSpace("org/demo");
            assertEquals("org/demo", metadata.repositoryId());
            assertEquals("commit-123", metadata.revision());
            assertEquals("docker", metadata.sdk());
            assertTrue(metadata.isPrivate());
            assertFalse(metadata.gated());
            assertEquals(2, metadata.tags().size());
            assertInstanceOf(ObjectNode.class, metadata.rawMetadata()).put("id", "changed");
            assertEquals("org/demo", metadata.rawMetadata().path("id").asText());

            var files = client.listSpaceFiles("org/demo", "main");
            assertEquals(1, files.size());
            assertEquals("tool.py", files.getFirst().path());
            assertEquals(TOOL_SOURCE.length, files.getFirst().sizeBytes());
            assertEquals(0, server.downloadRequests.get());
        }
    }

    /**
     * 验证下载必须使用同仓库 revision 的文件清单项，且下载结果带安全副本和摘要。
     *
     * @throws Exception stub server 或摘要计算失败时抛出
     */
    @Test
    void downloadsListedFileWithBearerTokenAndContentDigest() throws Exception {
        try (HubStub server = new HubStub()) {
            HuggingFaceHubClient client = client(server, "hub-token", 1_024);
            HuggingFaceHubClient.SpaceFile file = client.listSpaceFiles("org/demo", "main").getFirst();

            HuggingFaceHubClient.DownloadedSpaceFile downloaded =
                    client.downloadSpaceFile("org/demo", "main", file);

            assertArrayEquals(TOOL_SOURCE, downloaded.content());
            assertEquals(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(TOOL_SOURCE)
            ), downloaded.sha256Hex());
            assertEquals("Bearer hub-token", server.authorization.get());
            byte[] returnedBytes = downloaded.content();
            returnedBytes[0] = 0;
            assertArrayEquals(TOOL_SOURCE, downloaded.content());
            assertEquals(1, server.downloadRequests.get());
        }
    }

    /**
     * 验证明确命名的源码审阅保存流程校验摘要、限制目标目录且拒绝覆盖。
     *
     * @throws Exception 临时审阅目录或文件操作失败时抛出
     */
    @Test
    void savesVerifiedSourceOnlyIntoExplicitReviewDirectory() throws Exception {
        try (HubStub server = new HubStub()) {
            HuggingFaceHubClient client = client(server, null, 1_024);
            HuggingFaceHubClient.SpaceFile listed =
                    client.listSpaceFiles("org/demo", "main").getFirst();
            HuggingFaceHubClient.DownloadedSpaceFile downloaded =
                    client.downloadSpaceFile("org/demo", "main", listed);
            Path reviewDirectory = Files.createDirectory(temporaryDirectory.resolve("review"));

            Path saved = client.saveSpaceFileForReview(reviewDirectory, downloaded);

            assertEquals(reviewDirectory.resolve("tool.py"), saved);
            assertArrayEquals(TOOL_SOURCE, Files.readAllBytes(saved));
            assertThrows(
                    HubRequestException.class,
                    () -> client.saveSpaceFileForReview(reviewDirectory, downloaded)
            );
            HuggingFaceHubClient.DownloadedSpaceFile tampered = new HuggingFaceHubClient.DownloadedSpaceFile(
                    "org/demo",
                    "main",
                    "tampered.py",
                    TOOL_SOURCE,
                    "0".repeat(64)
            );
            assertThrows(
                    ConfigurationException.class,
                    () -> client.saveSpaceFileForReview(reviewDirectory, tampered)
            );
        }
    }

    /**
     * 验证 Hub 返回 Link header 时会继续读取同源文件清单的下一页。
     *
     * @throws Exception stub server 启动失败时抛出
     */
    @Test
    void followsSameOriginLinkPagination() throws Exception {
        try (HubStub server = new HubStub()) {
            var files = client(server, null, 1_024).listSpaceFiles("org/demo", "paged");

            assertEquals(2, files.size());
            assertEquals("tool.py", files.getFirst().path());
            assertEquals("README.md", files.get(1).path());
            assertEquals(2, server.pagedListingRequests.get());
        }
    }

    /**
     * 验证 collection 过滤、分页、完整 item 读取及查询结果的安全类型映射。
     *
     * @throws Exception stub server 启动或 JSON fixture 处理失败时抛出
     */
    @Test
    void listsAndReadsCollectionsWithBoundedPagination() throws Exception {
        try (HubStub server = new HubStub()) {
            HuggingFaceHubClient client = client(server, "hub-token", 1_024);

            var listed = client.listCollections(
                    List.of("org"),
                    List.of("spaces/org/demo"),
                    HuggingFaceHubClient.CollectionSort.TRENDING,
                    10
            );
            assertEquals(2, listed.size());
            assertEquals("org/demo-collection", listed.getFirst().slug());
            assertEquals("Reviewed item", listed.getFirst().items().getFirst().note());
            assertEquals(2, server.collectionRequests.get());
            assertTrue(server.collectionQuery.get().contains("owner=org"));
            assertTrue(server.collectionQuery.get().contains("sort=trending"));
            assertTrue(server.collectionQuery.get().contains("limit=10"));
            assertEquals("Bearer hub-token", server.collectionAuthorization.get());

            HuggingFaceHubClient.HubCollection complete = client.getCollection("org/demo-collection");
            assertEquals("Complete metadata", complete.description());
            assertEquals(1, complete.items().size());
            assertEquals("org/demo", complete.items().getFirst().itemId());
            assertEquals(3, server.collectionRequests.get());
        }
    }

    /**
     * 验证路径穿越、来源不匹配、超大文件及不安全跨域跳转均被拒绝。
     *
     * @throws Exception stub server 启动失败时抛出
     */
    @Test
    void rejectsUnsafePathsMismatchedMetadataOversizedFilesAndExternalRedirects() throws Exception {
        try (HubStub server = new HubStub()) {
            HuggingFaceHubClient client = client(server, null, 8);
            HuggingFaceHubClient.SpaceFile file = client.listSpaceFiles("org/demo", "main").getFirst();

            assertThrows(ConfigurationException.class,
                    () -> new HuggingFaceHubClient.SpaceFile("org/demo", "main", "../tool.py", 1, ""));
            assertThrows(ConfigurationException.class,
                    () -> client.downloadSpaceFile("org/demo", "other", file));
            assertThrows(ConfigurationException.class,
                    () -> client.downloadSpaceFile(
                            "org/demo",
                            "main",
                            new HuggingFaceHubClient.SpaceFile("org/demo", "main", "unlisted.py", -1, "")
                    ));
            HubRequestException oversized = assertThrows(HubRequestException.class,
                    () -> client.downloadSpaceFile("org/demo", "main", file));
            assertTrue(oversized.getMessage().contains("size limit"));
            assertEquals(0, server.downloadRequests.get());

            HubRequestException redirect = assertThrows(HubRequestException.class,
                    () -> client.inspectSpace("org/redirect"));
            assertTrue(redirect.getMessage().contains("redirect"));
            var unknownSize = client.listSpaceFiles("org/demo", "unknown").getFirst();
            HubRequestException streamingLimit = assertThrows(
                    HubRequestException.class,
                    () -> client.downloadSpaceFile("org/demo", "unknown", unknownSize)
            );
            assertTrue(streamingLimit.getMessage().contains("size limit"));
        }
    }

    /**
     * 验证请求令牌和 HTTP 模式的显式安全配置。
     *
     * @throws Exception stub server 启动失败时抛出
     */
    @Test
    void rejectsHttpHubWithoutOptInAndReportsHttpStatus() throws Exception {
        assertThrows(ConfigurationException.class, () -> new HuggingFaceHubClient(
                URI.create("http://huggingface.co"),
                null,
                Duration.ofSeconds(2),
                1_024,
                false
        ));
        assertThrows(ConfigurationException.class, () -> new HuggingFaceHubClient(
                URI.create("http://attacker.example"),
                null,
                Duration.ofSeconds(2),
                1_024,
                true
        ));
        try (HubStub server = new HubStub()) {
            HubRequestException failure = assertThrows(HubRequestException.class,
                    () -> client(server, null, 1_024).inspectSpace("org/missing"));
            assertEquals(404, failure.statusCode());
        }
    }

    /**
     * 使用本地测试 Hub endpoint 创建客户端。
     *
     * @param server 本地 Hub stub
     * @param token 可选访问 token
     * @param maxBytes 文件最大长度
     * @return 允许 loopback HTTP 的测试客户端
     */
    private static HuggingFaceHubClient client(HubStub server, String token, int maxBytes) {
        return new HuggingFaceHubClient(
                server.baseUrl(),
                token,
                Duration.ofSeconds(2),
                maxBytes,
                true
        );
    }

    /**
     * 为 Hub REST 与 resolve API 提供隔离的 loopback 响应。
     */
    private static final class HubStub implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> authorization = new AtomicReference<>();
        private final AtomicReference<String> collectionAuthorization = new AtomicReference<>();
        private final AtomicReference<String> collectionQuery = new AtomicReference<>();
        private final AtomicInteger downloadRequests = new AtomicInteger();
        private final AtomicInteger pagedListingRequests = new AtomicInteger();
        private final AtomicInteger collectionRequests = new AtomicInteger();

        /**
         * 启动一个仅用于单元测试的本机 Hub endpoint。
         *
         * @throws IOException 无法打开本机端口时抛出
         */
        private HubStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/spaces/org/demo", this::handleSpaceApi);
            server.createContext("/api/collections", this::handleCollections);
            server.createContext("/api/spaces/org/redirect", exchange -> {
                try (exchange) {
                    exchange.getResponseHeaders().add("Location", "https://attacker.example/metadata");
                    exchange.sendResponseHeaders(302, -1);
                }
            });
            server.createContext("/spaces/org/demo/resolve/main/tool.py", exchange -> {
                try (exchange) {
                    downloadRequests.incrementAndGet();
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    respond(exchange, 200, TOOL_SOURCE, "text/plain");
                }
            });
            server.createContext("/spaces/org/demo/resolve", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(TOOL_SOURCE);
                }
            });
            server.start();
        }

        /**
         * 响应 Hub metadata 和分页 tree 查询。
         *
         * @param exchange 当前 HTTP 请求
         * @throws IOException 写入响应失败
         */
        private void handleSpaceApi(HttpExchange exchange) throws IOException {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/tree/main")) {
                    respond(exchange, 200, """
                            [
                              {"type":"directory","path":"assets"},
                              {"type":"file","path":"tool.py","size":%d,"oid":"0123456789abcdef"}
                            ]
                            """.formatted(TOOL_SOURCE.length).getBytes(StandardCharsets.UTF_8), "application/json");
                } else if (path.endsWith("/tree/unknown")) {
                    respond(exchange, 200, """
                            [{"type":"file","path":"large.bin","oid":"unknown-size"}]
                            """.getBytes(StandardCharsets.UTF_8), "application/json");
                } else if (path.endsWith("/tree/paged")) {
                    pagedListingRequests.incrementAndGet();
                    if ("cursor=next".equals(exchange.getRequestURI().getRawQuery())) {
                        respond(exchange, 200, """
                                [{"type":"file","path":"README.md","size":4,"oid":"page-2"}]
                                """.getBytes(StandardCharsets.UTF_8), "application/json");
                    } else {
                        exchange.getResponseHeaders().add(
                                "Link",
                                "</api/spaces/org/demo/tree/paged?cursor=next>; rel=\"next\""
                        );
                        respond(exchange, 200, """
                                [{"type":"file","path":"tool.py","size":%d,"oid":"page-1"}]
                                """.formatted(TOOL_SOURCE.length).getBytes(StandardCharsets.UTF_8), "application/json");
                    }
                } else if ("GET".equals(exchange.getRequestMethod())) {
                    respond(exchange, 200, """
                            {"id":"org/demo","sha":"commit-123","private":true,"gated":false,
                             "sdk":"docker","tags":["docker","gradio"]}
                            """.getBytes(StandardCharsets.UTF_8), "application/json");
                } else {
                    respond(exchange, 405, new byte[0], "text/plain");
                }
            }
        }

        /**
         * 响应 collection list 的两页数据及单 collection 完整视图。
         *
         * @param exchange 当前 HTTP 请求
         * @throws IOException 写入响应失败
         */
        private void handleCollections(HttpExchange exchange) throws IOException {
            try (exchange) {
                collectionRequests.incrementAndGet();
                collectionAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String path = exchange.getRequestURI().getPath();
                if ("/api/collections".equals(path)) {
                    String query = exchange.getRequestURI().getRawQuery();
                    collectionQuery.compareAndSet(null, query);
                    if (query != null && query.contains("cursor=next")) {
                        respond(exchange, 200, """
                                [
                                  {"slug":"org/second-collection","title":"Second","owner":"org",
                                   "items":[],"lastUpdated":"2026-09-28T00:00:00Z","private":false,
                                   "theme":"blue","upvotes":1,"description":null}
                                ]
                                """.getBytes(StandardCharsets.UTF_8), "application/json");
                    } else {
                        exchange.getResponseHeaders().add(
                                "Link",
                                "</api/collections?cursor=next>; rel=\"next\""
                        );
                        respond(exchange, 200, """
                                [
                                  {"slug":"org/demo-collection","title":"Demo","owner":"org",
                                   "items":[{"_id":"item-1","id":"org/demo","type":"space","position":0,
                                             "note":{"text":"Reviewed item"}}],
                                   "lastUpdated":"2026-09-28T00:00:00Z","private":false,
                                   "theme":"green","upvotes":7,"description":"Complete metadata"}
                                ]
                                """.getBytes(StandardCharsets.UTF_8), "application/json");
                    }
                } else if ("/api/collections/org/demo-collection".equals(path)) {
                    respond(exchange, 200, """
                            {
                              "slug":"org/demo-collection","title":"Demo","owner":"org",
                              "items":[{"_id":"item-1","id":"org/demo","type":"space","position":0,
                                        "note":{"text":"Reviewed item"}}],
                              "lastUpdated":"2026-09-28T00:00:00Z","position":2,"private":false,
                              "theme":"green","upvotes":7,"description":"Complete metadata"
                            }
                            """.getBytes(StandardCharsets.UTF_8), "application/json");
                } else {
                    respond(exchange, 404, new byte[0], "text/plain");
                }
            }
        }

        /**
         * 返回本地 Hub origin。
         *
         * @return loopback 服务地址
         */
        private URI baseUrl() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        /**
         * 写入带内容类型的固定测试响应。
         *
         * @param exchange 当前 HTTP 请求
         * @param status HTTP 状态码
         * @param body 响应字节
         * @param contentType 响应媒体类型
         * @throws IOException 写入响应失败
         */
        private static void respond(HttpExchange exchange, int status, byte[] body, String contentType)
                throws IOException {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
