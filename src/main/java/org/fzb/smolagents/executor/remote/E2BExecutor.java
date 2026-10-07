package org.fzb.smolagents.executor.remote;

/**
 * 使用显式 E2B 生命周期 hook 和标准 Jupyter Gateway 执行代码。
 *
 * <p>原 Python 实现使用 E2B Code Interpreter SDK 的 {@code Sandbox.run_code()}。本类不模拟该 SDK；
 * 调用方必须提供实际创建 Jupyter Gateway 沙箱的适配器。</p>
 */
public final class E2BExecutor extends RemoteJupyterExecutor {
    /**
     * 启动由 E2B hook 提供的 Jupyter 沙箱。
     *
     * @param sessionProvider E2B 沙箱创建与释放适配器
     */
    public E2BExecutor(E2BSessionProvider sessionProvider) {
        this(sessionProvider, RemoteExecutorOptions.defaults());
    }

    /**
     * 使用指定的超时和响应上限启动 E2B Jupyter 沙箱。
     *
     * @param sessionProvider E2B 沙箱创建与释放适配器
     * @param options 远程执行的有界配置
     */
    public E2BExecutor(E2BSessionProvider sessionProvider, RemoteExecutorOptions options) {
        super("E2B", sessionProvider, options);
    }
}
