package org.fzb.smolagents.executor.remote;

import java.net.URI;
import java.util.Map;

/**
 * 一次远程 Jupyter 内核会话及其所属沙箱。
 *
 * @param endpoint 沙箱 Gateway 配置
 * @param kernelId 由 Jupyter 服务创建的内核标识
 * @param channelsUri 本次会话的 Jupyter WebSocket channels 地址
 */
record RemoteKernelSession(JupyterGatewayEndpoint endpoint, String kernelId, URI channelsUri) {
    Map<String, String> headers() {
        return endpoint.headers();
    }
}
