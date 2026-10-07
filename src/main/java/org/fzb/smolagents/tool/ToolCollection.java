package org.fzb.smolagents.tool;

import java.util.List;
import java.util.Objects;

/**
 * 持有一组工具及其可选外部资源生命周期的不可变集合。
 *
 * <p>通过 {@link #fromMcp(List, boolean, boolean)} 创建时，集合拥有 MCP 客户端和连接，
 * 应使用 try-with-resources 确保服务器资源关闭。</p>
 */
public final class ToolCollection implements AutoCloseable {
    private final List<Tool> tools;
    private final AutoCloseable resource;
    private boolean closed;

    /**
     * 创建仅包含本地工具且不管理外部资源的集合。
     *
     * @param tools 待持有的工具列表
     */
    public ToolCollection(List<Tool> tools) {
        this(tools, null);
    }

    /**
     * 创建绑定资源生命周期的不可变工具集合。
     *
     * @param tools 工具列表
     * @param resource 集合关闭时一并关闭的资源；可为空
     */
    private ToolCollection(List<Tool> tools, AutoCloseable resource) {
        this.tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        if (this.tools.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("tools must not contain null values");
        }
        this.resource = resource;
    }

    /**
     * 从单个 MCP stdio server 创建并托管工具集合。
     *
     * @param serverParameters MCP server 命令和环境配置
     * @param trustRemoteCode 显式确认允许启动本地 server 命令
     * @param structuredOutput 是否处理 structuredContent
     * @return 发现工具并托管连接的集合
     */
    public static ToolCollection fromMcp(
            MCPClient.ServerParameters serverParameters,
            boolean trustRemoteCode,
            boolean structuredOutput
    ) {
        MCPClient client = new MCPClient(serverParameters, trustRemoteCode, structuredOutput);
        return new ToolCollection(client.getTools(), client);
    }

    /**
     * 从单个 Streamable HTTP MCP server 创建并托管工具集合。
     *
     * @param serverParameters MCP HTTP endpoint 配置
     * @param trustRemoteCode 显式确认允许调用远端 server
     * @param structuredOutput 是否处理 structuredContent
     * @return 发现工具并托管 HTTP session 的集合
     */
    public static ToolCollection fromMcp(
            MCPClient.StreamableHttpServerParameters serverParameters,
            boolean trustRemoteCode,
            boolean structuredOutput
    ) {
        MCPClient client = new MCPClient(serverParameters, trustRemoteCode, structuredOutput);
        return new ToolCollection(client.getTools(), client);
    }

    /**
     * 从单个 legacy HTTP+SSE MCP server 创建并托管工具集合。
     *
     * @param serverParameters MCP SSE endpoint 配置
     * @param trustRemoteCode 显式确认允许调用远端 server
     * @param structuredOutput 是否处理 structuredContent
     * @return 发现工具并托管 SSE session 的集合
     */
    public static ToolCollection fromMcp(
            MCPClient.SseServerParameters serverParameters,
            boolean trustRemoteCode,
            boolean structuredOutput
    ) {
        MCPClient client = new MCPClient(serverParameters, trustRemoteCode, structuredOutput);
        return new ToolCollection(client.getTools(), client);
    }

    /**
     * 从多个 MCP stdio/Streamable HTTP server 创建并托管工具集合。
     *
     * @param serverParameters MCP server 命令和环境配置
     * @param trustRemoteCode 显式确认允许启动本地 server 命令
     * @param structuredOutput 是否处理 structuredContent
     * @return 发现工具并托管连接的集合
     */
    public static ToolCollection fromMcp(
            List<? extends MCPClient.ServerConfiguration> serverParameters,
            boolean trustRemoteCode,
            boolean structuredOutput
    ) {
        MCPClient client = new MCPClient(serverParameters, trustRemoteCode, structuredOutput);
        return new ToolCollection(client.getTools(), client);
    }

    /**
     * 返回不可变工具列表。
     *
     * @return 当前集合内的工具
     */
    public List<Tool> tools() {
        return tools;
    }

    /**
     * 关闭集合管理的资源；重复调用不会重复关闭资源。
     *
     * @throws IllegalStateException 受管资源关闭失败时抛出
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to close tool collection resources", error);
        }
    }
}
