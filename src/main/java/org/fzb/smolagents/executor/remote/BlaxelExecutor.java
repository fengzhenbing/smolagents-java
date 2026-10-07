package org.fzb.smolagents.executor.remote;

/**
 * 使用显式 Blaxel 生命周期 hook 和标准 Jupyter Gateway 执行代码。
 *
 * <p>Blaxel sandbox 创建、认证头、资源/区域/TTL 配置及删除由 hook 实现负责；本类只处理 Jupyter
 * 内核 HTTP/WebSocket 协议。</p>
 */
public final class BlaxelExecutor extends RemoteJupyterExecutor {
    /**
     * 启动由 Blaxel hook 提供的 Jupyter 沙箱。
     *
     * @param sessionProvider Blaxel 沙箱创建与释放适配器
     */
    public BlaxelExecutor(BlaxelSessionProvider sessionProvider) {
        this(sessionProvider, RemoteExecutorOptions.defaults());
    }

    /**
     * 使用指定的超时和响应上限启动 Blaxel Jupyter 沙箱。
     *
     * @param sessionProvider Blaxel 沙箱创建与释放适配器
     * @param options 远程执行的有界配置
     */
    public BlaxelExecutor(BlaxelSessionProvider sessionProvider, RemoteExecutorOptions options) {
        super("Blaxel", sessionProvider, options);
    }
}
