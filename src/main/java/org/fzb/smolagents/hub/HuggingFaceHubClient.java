package org.fzb.smolagents.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.HubRequestException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 以不执行远端代码的方式读取 Hugging Face Space 元数据和受限文件内容。
 *
 * <p>仓库文件默认只以有大小上限的字节数组返回；另有明确命名的审阅保存方法，可将文件写入
 * 调用方指定且已存在的目录。任何 API 都不会导入、编译或执行下载的代码。生产 Hub 地址
 * 默认固定为 HTTPS，测试镜像需显式启用 loopback insecure HTTP。</p>
 */
public final class HuggingFaceHubClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_METADATA_BYTES = 4 * 1024 * 1024;
    private static final int MAX_LISTING_BYTES = 16 * 1024 * 1024;
    private static final int MAX_LISTING_PAGES = 1_000;
    private static final int MAX_SPACE_FILE_COUNT = 100_000;
    private static final int MAX_COLLECTION_LIMIT = 1_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_CONFIGURABLE_DOWNLOAD_BYTES = 64 * 1024 * 1024;
    private static final Pattern NEXT_LINK = Pattern.compile(
            "<([^>]+)>\\s*;[^,]*\\brel\\s*=\\s*\"?next\"?",
            Pattern.CASE_INSENSITIVE
    );
    private static final URI DEFAULT_HUB_URL = URI.create("https://huggingface.co");

    private final URI hubUrl;
    private final String token;
    private final Duration timeout;
    private final int maxDownloadBytes;
    private final HttpClient httpClient;

    /**
     * 创建连接官方 Hugging Face Hub 的客户端。
     *
     * @param token 可选的 Hugging Face access token
     * @param timeout 单个 HTTP 请求的超时
     * @param maxDownloadBytes 单个文件的最大下载字节数
     */
    public HuggingFaceHubClient(String token, Duration timeout, int maxDownloadBytes) {
        this(DEFAULT_HUB_URL, token, timeout, maxDownloadBytes, false);
    }

    /**
     * 创建可配置 Hub endpoint 的客户端。
     *
     * @param hubUrl Hub origin
     * @param token 可选的 Hugging Face access token
     * @param timeout 单个 HTTP 请求的超时
     * @param maxDownloadBytes 单个文件的最大下载字节数
     * @param allowInsecureHttp 是否显式允许 HTTP；仅用于受控测试环境
     */
    public HuggingFaceHubClient(
            URI hubUrl,
            String token,
            Duration timeout,
            int maxDownloadBytes,
            boolean allowInsecureHttp
    ) {
        this.hubUrl = validateHubUrl(hubUrl, allowInsecureHttp);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new ConfigurationException("Hub request timeout must be positive");
        }
        if (maxDownloadBytes <= 0 || maxDownloadBytes > MAX_CONFIGURABLE_DOWNLOAD_BYTES) {
            throw new ConfigurationException("maxDownloadBytes must be between 1 and 64 MiB");
        }
        this.token = token == null || token.isBlank() ? null : token;
        this.timeout = timeout;
        this.maxDownloadBytes = maxDownloadBytes;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 获取 Space 的 Hub 元数据，不访问 Space 应用运行时。
     *
     * @param repositoryId Hub Space 仓库 ID，例如 {@code owner/name}
     * @return 经过校验并保留原始 JSON 的 Space 元数据
     */
    public SpaceMetadata inspectSpace(String repositoryId) {
        String repository = validateRepositoryId(repositoryId);
        byte[] response = getBytes(resolve("/api/spaces/" + encodeRepository(repository)), MAX_METADATA_BYTES);
        JsonNode json = readJson(response, "Space metadata");
        String returnedId = json.path("id").asText("");
        if (!repository.equals(returnedId)) {
            throw new HubRequestException("Hub returned Space metadata for an unexpected repository");
        }
        List<String> tags = new ArrayList<>();
        JsonNode tagValues = json.path("tags");
        if (tagValues.isArray()) {
            tagValues.forEach(tag -> {
                if (tag.isTextual()) {
                    tags.add(tag.asText());
                }
            });
        }
        return new SpaceMetadata(
                returnedId,
                json.path("sha").asText(""),
                json.path("private").asBoolean(false),
                json.path("gated").asBoolean(false),
                json.path("sdk").asText(""),
                tags,
                json
        );
    }

    /**
     * 按 owner/item/sort 条件分页查询 Hub collections，结果数由调用方显式限制。
     *
     * <p>查询仅读取 collection 元数据，不下载其中的仓库文件，也不会执行远端代码。</p>
     *
     * @param owners 可选 owner 过滤列表；空列表表示不筛选 owner
     * @param items 可选 item 过滤列表；空列表表示不筛选 item
     * @param sort 可选排序方式
     * @param limit 本次最多返回数量，范围为 1 至 1000
     * @return Hub 返回顺序下的 collection 元数据
     */
    public List<HubCollection> listCollections(
            List<String> owners,
            List<String> items,
            CollectionSort sort,
            int limit
    ) {
        if (limit < 1 || limit > MAX_COLLECTION_LIMIT) {
            throw new ConfigurationException("collection limit must be between 1 and 1000");
        }
        List<String> checkedOwners = validateFilters(owners, "owner");
        List<String> checkedItems = validateFilters(items, "item");
        StringBuilder path = new StringBuilder("/api/collections");
        List<String> query = new ArrayList<>();
        checkedOwners.forEach(owner -> query.add("owner=" + encodeQueryValue(owner)));
        checkedItems.forEach(item -> query.add("item=" + encodeQueryValue(item)));
        if (sort != null) {
            query.add("sort=" + encodeQueryValue(sort.apiValue()));
        }
        query.add("limit=" + limit);
        path.append('?').append(String.join("&", query));

        URI nextPage = resolve(path.toString());
        Set<String> visitedPages = new HashSet<>();
        Set<String> collectionSlugs = new HashSet<>();
        List<HubCollection> collections = new ArrayList<>();
        int pages = 0;
        long totalListingBytes = 0;
        while (nextPage != null && collections.size() < limit) {
            if (++pages > MAX_LISTING_PAGES || !visitedPages.add(nextPage.toASCIIString())) {
                throw new HubRequestException("Hub collection listing exceeded pagination limits");
            }
            if (!sameOrigin(hubUrl, nextPage)) {
                throw new HubRequestException("Hub collection pagination points to a disallowed origin");
            }
            HubResponse page = getResponse(nextPage, MAX_METADATA_BYTES);
            byte[] pageBody = page.body();
            totalListingBytes += pageBody.length;
            if (totalListingBytes > MAX_LISTING_BYTES) {
                throw new HubRequestException("Hub collection listing exceeded the total size limit");
            }
            JsonNode json = readJson(pageBody, "collection listing");
            if (!json.isArray()) {
                throw new HubRequestException("Hub returned an invalid collection listing");
            }
            for (JsonNode entry : json) {
                HubCollection collection = parseCollection(entry, collections.size());
                if (!collectionSlugs.add(collection.slug())) {
                    throw new HubRequestException("Hub returned a duplicate collection slug");
                }
                collections.add(collection);
                if (collections.size() == limit) {
                    break;
                }
            }
            nextPage = collections.size() < limit
                    ? nextPageUri(page.linkHeaders(), page.requestUri())
                    : null;
        }
        return List.copyOf(collections);
    }

    /**
     * 读取指定 collection 的完整元数据和全部 item。
     *
     * @param collectionSlug Hub collection slug，例如 {@code owner/name-hash}
     * @return collection 及其 item 元数据
     */
    public HubCollection getCollection(String collectionSlug) {
        String slug = validateCollectionSlug(collectionSlug);
        URI endpoint = resolve("/api/collections/" + encodePath(slug));
        return parseCollection(
                readJson(getBytes(endpoint, MAX_METADATA_BYTES), "collection metadata"),
                0
        );
    }

    /**
     * 列出指定 Space revision 中的文件，不下载或执行文件内容。
     *
     * @param repositoryId Hub Space 仓库 ID
     * @param revision 分支、tag 或 commit；分支名可包含斜线
     * @return Hub 返回顺序下的普通文件元数据
     */
    public List<SpaceFile> listSpaceFiles(String repositoryId, String revision) {
        String repository = validateRepositoryId(repositoryId);
        String checkedRevision = validateRevision(revision);
        String path = "/api/spaces/" + encodeRepository(repository)
                + "/tree/" + encodeSegment(checkedRevision) + "?recursive=true&expand=true";
        URI nextPage = resolve(path);
        Set<String> visitedPages = new HashSet<>();
        List<SpaceFile> files = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        int pages = 0;
        long totalListingBytes = 0;
        while (nextPage != null) {
            if (++pages > MAX_LISTING_PAGES || !visitedPages.add(nextPage.toASCIIString())) {
                throw new HubRequestException("Hub Space file listing exceeded pagination limits");
            }
            if (!sameOrigin(hubUrl, nextPage)) {
                throw new HubRequestException("Hub pagination link points to a disallowed origin");
            }
            HubResponse page = getResponse(nextPage, MAX_METADATA_BYTES);
            byte[] pageBody = page.body();
            totalListingBytes += pageBody.length;
            if (totalListingBytes > MAX_LISTING_BYTES) {
                throw new HubRequestException("Hub Space file listing exceeded the total size limit");
            }
            JsonNode json = readJson(pageBody, "Space file listing");
            if (!json.isArray()) {
                throw new HubRequestException("Hub returned an invalid Space file listing");
            }
            for (JsonNode entry : json) {
                String type = entry.path("type").asText("");
                if ("directory".equals(type) || "tree".equals(type)) {
                    continue;
                }
                if (!"file".equals(type) && !"blob".equals(type)) {
                    throw new HubRequestException("Hub returned an unsupported Space file entry type");
                }
                String rawFilePath = entry.path("path").asText("");
                String filePath;
                try {
                    filePath = validateFilePath(rawFilePath);
                } catch (ConfigurationException error) {
                    throw new HubRequestException("Hub returned an unsafe Space file path", error);
                }
                if (!paths.add(filePath)) {
                    throw new HubRequestException("Hub returned a duplicate Space file path");
                }
                JsonNode sizeNode = entry.get("size");
                long size = sizeNode == null || !sizeNode.canConvertToLong() ? -1 : sizeNode.longValue();
                if (size < -1) {
                    throw new HubRequestException("Hub returned an invalid Space file size");
                }
                files.add(new SpaceFile(
                        repository,
                        checkedRevision,
                        filePath,
                        size,
                        entry.path("oid").asText("")
                ));
                if (files.size() > MAX_SPACE_FILE_COUNT) {
                    throw new HubRequestException("Hub Space file listing exceeded the maximum file count");
                }
            }
            nextPage = nextPageUri(page.linkHeaders(), page.requestUri());
        }
        return List.copyOf(files);
    }

    /**
     * 下载此前列出的单个 Space 文件到有大小上限的内存缓冲区。
     *
     * <p>传入文件元数据可确保 repository 和 revision 与列举结果一致；下载结果只作为
     * 字节返回，不会写入磁盘或执行。超出客户端上限、与 Hub 声明大小不符或路径不安全时失败。</p>
     *
     * @param repositoryId Hub Space 仓库 ID
     * @param revision 分支、tag 或 commit
     * @param file 同一仓库和 revision 的 {@link #listSpaceFiles(String, String)} 返回项
     * @return 文件字节、SHA-256 和来源元数据
     */
    public DownloadedSpaceFile downloadSpaceFile(String repositoryId, String revision, SpaceFile file) {
        String repository = validateRepositoryId(repositoryId);
        String checkedRevision = validateRevision(revision);
        Objects.requireNonNull(file, "file");
        if (!repository.equals(file.repositoryId()) || !checkedRevision.equals(file.revision())) {
            throw new ConfigurationException("Space file metadata must match the requested repository and revision");
        }
        String filePath = validateFilePath(file.path());
        if (file.sizeBytes() > maxDownloadBytes) {
            throw new HubRequestException("Space file exceeds the configured download size limit");
        }
        if (!listSpaceFiles(repository, checkedRevision).contains(file)) {
            throw new ConfigurationException("Space file must match current Hub listing metadata");
        }
        String path = "/spaces/" + encodeRepository(repository)
                + "/resolve/" + encodeSegment(checkedRevision) + "/" + encodePath(filePath);
        byte[] content = getBytes(resolve(path), maxDownloadBytes);
        if (file.sizeBytes() >= 0 && content.length != file.sizeBytes()) {
            throw new HubRequestException("Downloaded Space file size does not match Hub metadata");
        }
        return new DownloadedSpaceFile(repository, checkedRevision, filePath, content, sha256(content));
    }

    /**
     * 将已下载的单个 Space 文件保存到调用方指定的源码审阅目录。
     *
     * <p>目标目录必须预先存在。仓库相对路径经校验后原样保留；不覆盖现有文件，不跟随目录符号链接，
     * 并先验证记录中的 SHA-256。此方法只保存文件，绝不执行、加载或导入其中的内容。</p>
     *
     * @param reviewDirectory 调用方创建并指定的审阅目录
     * @param file 由本客户端受限下载得到的文件
     * @return 新创建的本地文件路径
     */
    public Path saveSpaceFileForReview(Path reviewDirectory, DownloadedSpaceFile file) {
        Objects.requireNonNull(reviewDirectory, "reviewDirectory");
        Objects.requireNonNull(file, "file");
        validateRepositoryId(file.repositoryId());
        validateRevision(file.revision());
        byte[] content = file.content();
        if (!MessageDigest.isEqual(
                sha256(content).getBytes(StandardCharsets.US_ASCII),
                file.sha256Hex().getBytes(StandardCharsets.US_ASCII)
        )) {
            throw new ConfigurationException("Downloaded Space file content does not match its SHA-256 metadata");
        }
        String filePath = validateFilePath(file.path());
        Path root;
        try {
            root = reviewDirectory.toRealPath();
        } catch (IOException error) {
            throw new HubRequestException("Source review directory must already exist", error);
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new ConfigurationException("Source review destination must be an existing directory");
        }

        String[] segments = filePath.split("/");
        Path parent = root;
        try {
            for (int index = 0; index < segments.length - 1; index++) {
                parent = parent.resolve(segments[index]).normalize();
                if (!parent.startsWith(root)) {
                    throw new ConfigurationException("Space file path escapes the source review directory");
                }
                if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.createDirectory(parent);
                    } catch (FileAlreadyExistsException ignored) {
                        // A concurrent creator is accepted only after the directory check below.
                    }
                }
                if (Files.isSymbolicLink(parent)
                        || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ConfigurationException("Source review path contains a non-directory or symlink");
                }
            }
            Path destination = parent.resolve(segments[segments.length - 1]).normalize();
            if (!destination.startsWith(root)) {
                throw new ConfigurationException("Space file path escapes the source review directory");
            }
            Path temporary = Files.createTempFile(parent, ".smolagents-review-", ".tmp");
            try {
                Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                return Files.move(temporary, destination);
            } catch (IOException | RuntimeException error) {
                Files.deleteIfExists(temporary);
                throw error;
            }
        } catch (FileAlreadyExistsException error) {
            throw new HubRequestException("Source review file already exists; refusing to overwrite it", error);
        } catch (IOException error) {
            throw new HubRequestException("Unable to save Space file for source review", error);
        }
    }

    /**
     * 发送有界 GET 请求，并只跟随受允许的 Hub/CDN 重定向。
     *
     * <p>步骤：读取响应状态；对重定向校验 scheme/host 并跨 origin 时移除 token；
     * 对成功响应先检查声明长度，再以流式方式限制实际读取字节数。</p>
     *
     * @param initialUri 初始 Hub 请求地址
     * @param maximumBytes 最大响应字节数
     * @return 完整且未超过大小上限的响应字节
     */
    private byte[] getBytes(URI initialUri, int maximumBytes) {
        return getResponse(initialUri, maximumBytes).body();
    }

    /**
     * 获取 Hub 响应，同时保留分页所需的响应 URI 和 Link headers。
     *
     * @param initialUri 初始请求 URI
     * @param maximumBytes 单页响应最大字节数
     * @return 响应字节、最终请求 URI 和分页头
     */
    private HubResponse getResponse(URI initialUri, int maximumBytes) {
        URI currentUri = initialUri;
        boolean includeToken = token != null;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(currentUri)
                    .timeout(timeout)
                    .header("Accept", "application/json, application/octet-stream")
                    .header("User-Agent", "smolagents-java/1.0")
                    .GET();
            if (includeToken) {
                requestBuilder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<byte[]> response;
            try {
                response = httpClient.send(
                        requestBuilder.build(),
                        responseInfo -> new BoundedHubBodySubscriber(maximumBytes)
                );
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new HubRequestException("Hub request was interrupted", error);
            } catch (IOException error) {
                if (causedBy(error, ResponseLimitException.class)) {
                    throw new HubRequestException("Hub response exceeds the configured size limit", error);
                }
                throw new HubRequestException("Hub request failed", error);
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                if (redirects == MAX_REDIRECTS) {
                    throw new HubRequestException("Hub response exceeded the redirect limit", status);
                }
                String locationHeader = response.headers().firstValue("Location")
                        .orElseThrow(() -> new HubRequestException("Hub redirect is missing its Location header"));
                URI location;
                try {
                    location = currentUri.resolve(locationHeader);
                } catch (IllegalArgumentException error) {
                    throw new HubRequestException("Hub returned an invalid redirect URI", error);
                }
                if (!isAllowedRedirect(currentUri, location)) {
                    throw new HubRequestException("Hub returned a redirect to a disallowed origin", status);
                }
                includeToken = includeToken && sameOrigin(currentUri, location);
                currentUri = location;
                continue;
            }
            if (status < 200 || status >= 300) {
                throw new HubRequestException("Hub request failed with HTTP " + status, status);
            }
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declaredLength > maximumBytes) {
                throw new HubRequestException("Hub response exceeds the configured size limit");
            }
            return new HubResponse(response.body(), currentUri, response.headers().allValues("Link"));
        }
        throw new HubRequestException("Hub response exceeded the redirect limit");
    }

    /**
     * 判断异常包装链是否包含指定原因类型。
     *
     * @param error 最外层异常
     * @param expectedType 目标异常类型
     * @return 包装链中存在目标类型时为 true
     */
    private static boolean causedBy(Throwable error, Class<? extends Throwable> expectedType) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (expectedType.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集一个 Hub HTTP response，且在响应尚未进入完整内存缓冲前执行大小限制。
     */
    private static final class BoundedHubBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximumBytes;
        private final ByteArrayOutputStream output;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        /**
         * 创建单个 Hub 响应体的 byte cap collector。
         *
         * @param maximumBytes 最大响应字节数
         */
        private BoundedHubBodySubscriber(int maximumBytes) {
            this.maximumBytes = maximumBytes;
            this.output = new ByteArrayOutputStream(Math.min(maximumBytes, 8_192));
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> chunks) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > maximumBytes - output.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseLimitException());
                    return;
                }
                byte[] bytes = new byte[chunk.remaining()];
                chunk.get(bytes);
                output.write(bytes, 0, bytes.length);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(output.toByteArray());
        }
    }

    /**
     * 表示 Hub 响应超过了本次请求允许的最大字节数。
     */
    private static final class ResponseLimitException extends IOException {
        /**
         * 创建有界响应超限错误。
         */
        private ResponseLimitException() {
            super("Hub response exceeded maximum allowed bytes");
        }
    }

    /**
     * 从 Hub Link header 中提取同源的下一页地址。
     *
     * @param linkHeaders 响应中的 Link header 值
     * @param responseUri 当前响应 URI，用于解析相对链接
     * @return 下一页 URI；不存在时返回 null
     */
    private static URI nextPageUri(List<String> linkHeaders, URI responseUri) {
        for (String header : linkHeaders) {
            Matcher matcher = NEXT_LINK.matcher(header);
            if (!matcher.find()) {
                continue;
            }
            try {
                URI next = responseUri.resolve(matcher.group(1));
                if (next.getUserInfo() != null
                        || next.getFragment() != null
                        || !sameOrigin(responseUri, next)) {
                    throw new HubRequestException("Hub pagination link points to a disallowed origin");
                }
                return next;
            } catch (IllegalArgumentException error) {
                throw new HubRequestException("Hub returned an invalid pagination link", error);
            }
        }
        return null;
    }

    /**
     * 将有界 HTTP 响应解析为 JSON。
     *
     * @param response HTTP 响应字节
     * @param responseName 用于错误诊断的响应名称
     * @return JSON 根节点
     */
    private static JsonNode readJson(byte[] response, String responseName) {
        try {
            return MAPPER.readTree(response);
        } catch (IOException error) {
            throw new HubRequestException("Hub returned invalid JSON for " + responseName, error);
        }
    }

    /**
     * 将一个 Hub collection JSON 对象解析为只包含受支持字段的记录。
     *
     * @param json collection JSON
     * @param fallbackPosition 列表响应未提供位置时使用的页内顺序
     * @return typed collection metadata
     */
    private static HubCollection parseCollection(JsonNode json, int fallbackPosition) {
        if (!json.isObject()) {
            throw new HubRequestException("Hub returned an invalid collection entry");
        }
        String slug;
        try {
            slug = validateCollectionSlug(json.path("slug").asText(""));
        } catch (ConfigurationException error) {
            throw new HubRequestException("Hub returned an invalid collection slug", error);
        }
        JsonNode itemValues = json.path("items");
        if (!itemValues.isArray()) {
            throw new HubRequestException("Hub collection metadata is missing its items array");
        }
        List<HubCollectionItem> items = new ArrayList<>();
        for (JsonNode item : itemValues) {
            items.add(parseCollectionItem(item));
        }
        String title = requiredHubText(json, "title", "collection title");
        String owner = requiredHubText(json, "owner", "collection owner");
        String lastUpdated = json.path("lastUpdated").asText("");
        if (lastUpdated.isBlank()) {
            throw new HubRequestException("Hub collection metadata is missing lastUpdated");
        }
        int position = json.path("position").canConvertToInt()
                ? json.path("position").asInt()
                : fallbackPosition;
        int upvotes = json.path("upvotes").canConvertToInt() ? json.path("upvotes").asInt() : 0;
        return new HubCollection(
                slug,
                title,
                owner,
                lastUpdated,
                position,
                json.path("private").asBoolean(false),
                json.path("theme").asText(""),
                upvotes,
                json.hasNonNull("description") ? json.path("description").asText() : null,
                items,
                json
        );
    }

    /**
     * 解析 collection item 的公开标识、类型、位置和纯文本备注。
     *
     * @param json collection item JSON
     * @return typed collection item
     */
    private static HubCollectionItem parseCollectionItem(JsonNode json) {
        if (!json.isObject()) {
            throw new HubRequestException("Hub returned an invalid collection item");
        }
        String objectId = requiredHubText(json, "_id", "collection item object ID");
        String itemId = json.path("slug").asText(json.path("id").asText(""));
        if (itemId.isBlank()) {
            throw new HubRequestException("Hub collection item is missing its ID");
        }
        String type = requiredHubText(json, "type", "collection item type");
        JsonNode positionNode = json.get("position");
        if (positionNode == null || !positionNode.canConvertToInt()) {
            throw new HubRequestException("Hub collection item is missing its position");
        }
        JsonNode noteNode = json.path("note");
        String note = noteNode.isTextual() ? noteNode.asText()
                : noteNode.path("text").isTextual() ? noteNode.path("text").asText()
                : null;
        return new HubCollectionItem(objectId, itemId, type, positionNode.asInt(), note);
    }

    /**
     * 读取 Hub 对象中必须存在的非空文本字段。
     *
     * @param json Hub 对象
     * @param field JSON 字段名
     * @param fieldDescription 面向异常的信息字段名称
     * @return 非空文本值
     */
    private static String requiredHubText(JsonNode json, String field, String fieldDescription) {
        String value = json.path(field).asText("");
        if (value.isBlank()) {
            throw new HubRequestException("Hub response is missing " + fieldDescription);
        }
        return value;
    }

    /**
     * 校验 collection 过滤值并复制输入列表。
     *
     * @param values owner 或 item 筛选值
     * @param fieldName 参数名称
     * @return 不含空白项的不可变过滤值
     */
    private static List<String> validateFilters(List<String> values, String fieldName) {
        Objects.requireNonNull(values, fieldName);
        if (values.size() > 50 || values.stream().anyMatch(value ->
                value == null || value.isBlank() || value.length() > 512
                        || value.chars().anyMatch(Character::isISOControl))) {
            throw new ConfigurationException("collection " + fieldName + " filters are invalid");
        }
        return List.copyOf(values);
    }

    /**
     * 校验 owner/name collection slug。
     *
     * @param slug collection slug
     * @return 有效 slug
     */
    private static String validateCollectionSlug(String slug) {
        if (slug == null || slug.length() > 512) {
            throw new ConfigurationException("collection slug must use the owner/name format");
        }
        String[] parts = slug.split("/", -1);
        if (parts.length != 2 || !isHubPathSegment(parts[0]) || !isHubPathSegment(parts[1])) {
            throw new ConfigurationException("collection slug must use the owner/name format");
        }
        return slug;
    }

    /**
     * 对 query 参数值进行 UTF-8 form encoding。
     *
     * @param value query 参数值
     * @return 可安全拼入 query string 的值
     */
    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 将经过路径编码的 API path 拼接到 Hub origin。
     *
     * @param path Hub 下的相对请求路径
     * @return 绝对请求 URI
     */
    private URI resolve(String path) {
        return URI.create(hubUrl.toASCIIString().replaceAll("/+$", "") + path);
    }

    /**
     * 校验 Hub origin，明文 HTTP 只允许显式配置的 loopback 地址。
     *
     * @param url 待校验的 Hub URL
     * @param allowInsecureHttp 是否请求启用测试用 HTTP
     * @return 不带尾部斜线的 Hub origin
     */
    private static URI validateHubUrl(URI url, boolean allowInsecureHttp) {
        Objects.requireNonNull(url, "hubUrl");
        boolean secure = "https".equalsIgnoreCase(url.getScheme());
        boolean insecureAllowed = allowInsecureHttp
                && "http".equalsIgnoreCase(url.getScheme())
                && isLoopbackHost(url.getHost());
        if ((!secure && !insecureAllowed)
                || url.getHost() == null
                || url.getUserInfo() != null
                || url.getQuery() != null
                || url.getFragment() != null
                || (url.getPath() != null && !url.getPath().isEmpty() && !"/".equals(url.getPath()))) {
            throw new ConfigurationException("Hub URL must be an HTTP(S) origin without credentials, path, or query");
        }
        return URI.create(url.toASCIIString().replaceAll("/+$", ""));
    }

    /**
     * 判断主机名是否为不触发 DNS 解析的 loopback 字面值。
     *
     * @param host URL 主机名
     * @return 本机回环地址时为 true
     */
    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalizedHost = host.toLowerCase(java.util.Locale.ROOT);
        return normalizedHost.equals("localhost")
                || normalizedHost.equals("127.0.0.1")
                || normalizedHost.equals("[::1]")
                || normalizedHost.equals("::1");
    }

    /**
     * 校验 Hugging Face owner/name 仓库 ID。
     *
     * @param repositoryId 待校验的仓库 ID
     * @return 原样返回的有效仓库 ID
     */
    private static String validateRepositoryId(String repositoryId) {
        if (repositoryId == null || repositoryId.length() > 193) {
            throw new ConfigurationException("Space repository ID must use the owner/name format");
        }
        String[] parts = repositoryId.split("/", -1);
        if (parts.length != 2 || !isHubPathSegment(parts[0]) || !isHubPathSegment(parts[1])) {
            throw new ConfigurationException("Space repository ID must use the owner/name format");
        }
        return repositoryId;
    }

    /**
     * 校验不含 URL 控制字符或遍历段的 Hub revision。
     *
     * @param revision 分支、tag 或 commit 名称
     * @return 原样返回的有效 revision
     */
    private static String validateRevision(String revision) {
        if (revision == null || revision.isBlank() || revision.length() > 256
                || revision.startsWith("/") || revision.endsWith("\\") || revision.contains("\\")
                || revision.contains("?") || revision.contains("#")) {
            throw new ConfigurationException("Space revision is invalid");
        }
        for (String segment : revision.split("/", -1)) {
            if (!isHubPathSegment(segment)) {
                throw new ConfigurationException("Space revision contains an invalid path segment");
            }
        }
        return revision;
    }

    /**
     * 校验相对于仓库根目录的文件路径。
     *
     * @param filePath Hub 返回或调用方请求的文件路径
     * @return 原样返回的有效相对路径
     */
    private static String validateFilePath(String filePath) {
        if (filePath == null || filePath.isBlank() || filePath.length() > 4_096
                || filePath.startsWith("/") || filePath.contains("\\")) {
            throw new ConfigurationException("Space file path is invalid");
        }
        for (String segment : filePath.split("/", -1)) {
            if (!isHubPathSegment(segment)) {
                throw new ConfigurationException("Space file path contains an invalid path segment");
            }
        }
        return filePath;
    }

    /**
     * 校验一个 Hub URL path segment 不为空且不是路径遍历控制段。
     *
     * @param segment 单个 URL 路径段
     * @return 路径段安全时为 true
     */
    private static boolean isHubPathSegment(String segment) {
        return segment != null
                && !segment.isEmpty()
                && !".".equals(segment)
                && !"..".equals(segment)
                && segment.length() <= 255
                && segment.chars().noneMatch(Character::isISOControl);
    }

    /**
     * 对 owner/name 各自编码，同时保留仓库 ID 中间的分隔斜线。
     *
     * @param repositoryId 已校验的仓库 ID
     * @return URL 安全的仓库路径
     */
    private static String encodeRepository(String repositoryId) {
        String[] parts = repositoryId.split("/");
        return encodeSegment(parts[0]) + "/" + encodeSegment(parts[1]);
    }

    /**
     * 分段编码仓库文件路径，并保留目录层级分隔符。
     *
     * @param path 已校验的仓库内路径
     * @return URL 安全的文件路径
     */
    private static String encodePath(String path) {
        return String.join("/", java.util.Arrays.stream(path.split("/"))
                .map(HuggingFaceHubClient::encodeSegment)
                .toList());
    }

    /**
     * 对单个 URL 路径段执行 UTF-8 百分号编码。
     *
     * @param segment 路径段
     * @return 编码后的路径段
     */
    private static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 检查重定向是否同源，或由官方 Hub HTTPS 转向受信任 Hugging Face CDN。
     *
     * @param from 当前请求地址
     * @param to 重定向目标地址
     * @return 目标允许访问时为 true
     */
    private boolean isAllowedRedirect(URI from, URI to) {
        if (to.getHost() == null
                || to.getUserInfo() != null
                || !"https".equalsIgnoreCase(to.getScheme())
                && !"http".equalsIgnoreCase(to.getScheme())) {
            return false;
        }
        if (sameOrigin(from, to)) {
            return true;
        }
        if (!"https".equalsIgnoreCase(to.getScheme())
                || !"https".equalsIgnoreCase(hubUrl.getScheme())) {
            return false;
        }
        String host = to.getHost().toLowerCase(java.util.Locale.ROOT);
        String configuredHost = hubUrl.getHost().toLowerCase(java.util.Locale.ROOT);
        return isHuggingFaceHost(configuredHost)
                && (host.equals("huggingface.co") || host.endsWith(".huggingface.co")
                || host.equals("hf.co") || host.endsWith(".hf.co"));
    }

    /**
     * 检查 origin 主机是否位于 Hugging Face 官方域名空间。
     *
     * @param host 小写主机名
     * @return 官方 Hub/CDN 域名时为 true
     */
    private static boolean isHuggingFaceHost(String host) {
        return host.equals("huggingface.co") || host.endsWith(".huggingface.co")
                || host.equals("hf.co") || host.endsWith(".hf.co");
    }

    /**
     * 依据 scheme、host 和默认端口比较两个地址是否同源。
     *
     * @param left 第一个 URI
     * @param right 第二个 URI
     * @return 两个 URI 同源时为 true
     */
    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme() != null
                && right.getScheme() != null
                && left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost() != null
                && right.getHost() != null
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    /**
     * 返回 URI 显式端口或对应 scheme 的默认端口。
     *
     * @param uri 请求地址
     * @return 有效 TCP 端口
     */
    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * 计算下载内容的 SHA-256 十六进制摘要。
     *
     * @param content 下载字节
     * @return 小写十六进制摘要
     */
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256", error);
        }
    }

    /**
     * Space 的基础 Hub 元数据。
     *
     * @param repositoryId Hub 仓库 ID
     * @param revision 当前 Hub SHA
     * @param isPrivate 是否为私有仓库
     * @param gated 是否受访问审批限制
     * @param sdk 声明的 Space SDK
     * @param tags Hub 标签
     * @param rawMetadata 原始 Hub JSON 的防御性副本
     */
    public record SpaceMetadata(
            String repositoryId,
            String revision,
            boolean isPrivate,
            boolean gated,
            String sdk,
            List<String> tags,
            JsonNode rawMetadata
    ) {
        /**
         * 校验并复制 Space 元数据。
         *
         * @param repositoryId Hub 仓库 ID
         * @param revision 当前 Hub SHA
         * @param isPrivate 是否为私有仓库
         * @param gated 是否受访问审批限制
         * @param sdk 声明的 Space SDK
         * @param tags Hub 标签
         * @param rawMetadata 原始 Hub JSON
         */
        public SpaceMetadata {
            Objects.requireNonNull(repositoryId, "repositoryId");
            revision = revision == null ? "" : revision;
            sdk = sdk == null ? "" : sdk;
            tags = List.copyOf(tags);
            rawMetadata = Objects.requireNonNull(rawMetadata, "rawMetadata").deepCopy();
        }

        /**
         * 返回原始元数据的独立副本。
         *
         * @return JSON 元数据副本
         */
        @Override
        public JsonNode rawMetadata() {
            return rawMetadata.deepCopy();
        }
    }

    /**
     * 由 Hub 文件列表返回的单文件元数据，仅适用于对应仓库 revision。
     *
     * @param repositoryId 来源仓库 ID
     * @param revision 来源 revision
     * @param path 仓库内相对路径
     * @param sizeBytes Hub 声明文件长度；未知时为 -1
     * @param oid Hub 声明的对象 ID
     */
    public record SpaceFile(String repositoryId, String revision, String path, long sizeBytes, String oid) {
        /**
         * 校验文件元数据中的路径和大小。
         *
         * @param repositoryId 来源仓库 ID
         * @param revision 来源 revision
         * @param path 仓库内相对路径
         * @param sizeBytes Hub 声明文件长度
         * @param oid Hub 声明的对象 ID
         */
        public SpaceFile {
            repositoryId = validateRepositoryId(repositoryId);
            revision = validateRevision(revision);
            path = validateFilePath(path);
            if (sizeBytes < -1) {
                throw new ConfigurationException("Space file size must be non-negative or unknown");
            }
            oid = oid == null ? "" : oid;
        }
    }

    /**
     * 受限下载的 Space 文件内容。访问器返回字节数组副本，避免调用方篡改记录状态。
     *
     * @param repositoryId 来源仓库 ID
     * @param revision 来源 revision
     * @param path 仓库内相对路径
     * @param content 文件内容
     * @param sha256Hex 内容的 SHA-256 十六进制摘要
     */
    public record DownloadedSpaceFile(
            String repositoryId,
            String revision,
            String path,
            byte[] content,
            String sha256Hex
    ) {
        /**
         * 校验并复制下载的文件内容。
         *
         * @param repositoryId 来源仓库 ID
         * @param revision 来源 revision
         * @param path 仓库内相对路径
         * @param content 文件内容
         * @param sha256Hex SHA-256 摘要
         */
        public DownloadedSpaceFile {
            Objects.requireNonNull(repositoryId, "repositoryId");
            Objects.requireNonNull(revision, "revision");
            Objects.requireNonNull(path, "path");
            content = Objects.requireNonNull(content, "content").clone();
            Objects.requireNonNull(sha256Hex, "sha256Hex");
        }

        /**
         * 返回文件内容副本。
         *
         * @return 文件字节副本
         */
        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    /**
     * Hub collection 的受限元数据视图及其原始 JSON 快照。
     *
     * @param slug owner/name 格式的 collection slug
     * @param title collection 标题
     * @param owner 创建者
     * @param lastUpdated Hub 返回的更新时间文本
     * @param position 列表位置
     * @param isPrivate 是否为私有 collection
     * @param theme Hub 主题
     * @param upvotes 投票数
     * @param description 可选的纯文本描述
     * @param items collection 内的受支持 item 元数据
     * @param rawMetadata 原始 Hub JSON 的防御性副本
     */
    public record HubCollection(
            String slug,
            String title,
            String owner,
            String lastUpdated,
            int position,
            boolean isPrivate,
            String theme,
            int upvotes,
            String description,
            List<HubCollectionItem> items,
            JsonNode rawMetadata
    ) {
        /**
         * 校验并复制 Hub collection 元数据。
         *
         * @param slug collection slug
         * @param title collection 标题
         * @param owner 创建者
         * @param lastUpdated 更新时间文本
         * @param position 列表位置
         * @param isPrivate 是否私有
         * @param theme Hub 主题
         * @param upvotes 投票数
         * @param description 可选纯文本描述
         * @param items collection items
         * @param rawMetadata 原始 JSON
         */
        public HubCollection {
            slug = validateCollectionSlug(slug);
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(lastUpdated, "lastUpdated");
            if (title.isBlank() || owner.isBlank() || lastUpdated.isBlank() || position < 0 || upvotes < 0) {
                throw new ConfigurationException("Hub collection metadata fields are invalid");
            }
            theme = theme == null ? "" : theme;
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            rawMetadata = Objects.requireNonNull(rawMetadata, "rawMetadata").deepCopy();
        }

        /**
         * 返回原始 collection JSON 的独立副本。
         *
         * @return JSON 元数据副本
         */
        @Override
        public JsonNode rawMetadata() {
            return rawMetadata.deepCopy();
        }
    }

    /**
     * Hub collection 中一条可识别的 model、dataset、Space、paper 等 item。
     *
     * @param objectId collection 数据库中的 item ID
     * @param itemId 仓库、paper 或嵌套 collection ID
     * @param type Hub item 类型
     * @param position collection 中的位置
     * @param note 可选的纯文本备注
     */
    public record HubCollectionItem(String objectId, String itemId, String type, int position, String note) {
        /**
         * 校验 collection item 标识字段。
         *
         * @param objectId collection 数据库中的 item ID
         * @param itemId Hub item ID
         * @param type Hub item 类型
         * @param position collection 中的位置
         * @param note 可选备注
         */
        public HubCollectionItem {
            Objects.requireNonNull(objectId, "objectId");
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(type, "type");
            if (objectId.isBlank() || itemId.isBlank() || type.isBlank() || position < 0) {
                throw new ConfigurationException("Hub collection item fields are invalid");
            }
        }
    }

    /**
     * Hub collection 列表支持的排序方式。
     */
    public enum CollectionSort {
        /** 按最近更新时间排序。 */
        LAST_MODIFIED("lastModified"),
        /** 按趋势热度排序。 */
        TRENDING("trending"),
        /** 按投票数排序。 */
        UPVOTES("upvotes");

        private final String apiValue;

        CollectionSort(String apiValue) {
            this.apiValue = apiValue;
        }

        private String apiValue() {
            return apiValue;
        }
    }

    /**
     * 单页 Hub HTTP 响应和用于继续分页的响应元数据。
     *
     * @param body 有界响应字节
     * @param requestUri 最终请求 URI
     * @param linkHeaders 分页响应头
     */
    private record HubResponse(byte[] body, URI requestUri, List<String> linkHeaders) {
        /**
         * 创建 Hub HTTP 响应快照。
         *
         * @param body 响应字节
         * @param requestUri 最终请求 URI
         * @param linkHeaders Link headers
         */
        private HubResponse {
            body = body.clone();
            linkHeaders = List.copyOf(linkHeaders);
        }

        /**
         * 返回响应字节的防御性副本。
         *
         * @return 响应字节副本
         */
        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
