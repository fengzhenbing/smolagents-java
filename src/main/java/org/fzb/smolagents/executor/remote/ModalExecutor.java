package org.fzb.smolagents.executor.remote;

/**
 * 使用显式 Modal 生命周期 hook 和标准 Jupyter Gateway 执行代码。
 *
 * <p>Modal Python SDK 的 App、Secret、Sandbox 与 tunnel 创建由 hook 实现负责；本类只处理 Jupyter
 * 内核 HTTP/WebSocket 协议。</p>
 */
public final class ModalExecutor extends RemoteJupyterExecutor {
    /**
     * 启动由 Modal hook 提供的 Jupyter 沙箱。
     *
     * @param sessionProvider Modal 沙箱创建与释放适配器
     */
    public ModalExecutor(ModalSessionProvider sessionProvider) {
        this(sessionProvider, RemoteExecutorOptions.defaults());
    }

    /**
     * 使用指定的超时和响应上限启动 Modal Jupyter 沙箱。
     *
     * @param sessionProvider Modal 沙箱创建与释放适配器
     * @param options 远程执行的有界配置
     */
    public ModalExecutor(ModalSessionProvider sessionProvider, RemoteExecutorOptions options) {
        super("Modal", sessionProvider, options);
    }
}
