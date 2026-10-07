package org.fzb.smolagents.executor.remote;

/**
 * Modal 沙箱生命周期适配钩子；调用方负责提供已启动 Jupyter Kernel Gateway 的沙箱。
 */
public interface ModalSessionProvider extends JupyterSandboxProvider {
}
