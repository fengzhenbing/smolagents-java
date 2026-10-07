package org.fzb.smolagents.executor.remote;

/**
 * 定义远程服务沙箱的创建与释放边界；具体云 SDK/凭据由调用方适配，本 Java 切片不内置 provider SDK。
 */
public interface JupyterSandboxProvider {
    /**
     * 在服务端创建或获取沙箱，并返回其中已启动的 Jupyter Gateway 端点。
     *
     * @return 可访问的 Jupyter Gateway 端点
     */
    JupyterGatewayEndpoint openSandbox();

    /**
     * 释放由 {@link #openSandbox()} 创建或获取的沙箱。
     *
     * @param endpoint 对应的 Jupyter Gateway 端点
     */
    void closeSandbox(JupyterGatewayEndpoint endpoint);
}
