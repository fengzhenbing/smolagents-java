package org.fzb.smolagents.executor.remote;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * 描述一个已启动、可通过标准 Jupyter Kernel Gateway 访问的远程沙箱端点。
 *
 * @param kernelsUri Jupyter 内核集合端点，例如 {@code https://host/api/kernels}
 * @param headers 调用 Jupyter HTTP/WebSocket 接口时使用的认证头
 */
public record JupyterGatewayEndpoint(URI kernelsUri, Map<String, String> headers) {
    /**
     * 校验端点并复制认证头。
     *
     * @param kernelsUri Jupyter 内核集合端点
     * @param headers HTTP/WebSocket 请求头
     */
    public JupyterGatewayEndpoint {
        Objects.requireNonNull(kernelsUri, "kernelsUri");
        if (!kernelsUri.isAbsolute()
                || kernelsUri.getHost() == null
                || !("http".equalsIgnoreCase(kernelsUri.getScheme())
                || "https".equalsIgnoreCase(kernelsUri.getScheme()))) {
            throw new IllegalArgumentException("kernelsUri must be an absolute http(s) URI");
        }
        if (kernelsUri.getUserInfo() != null || kernelsUri.getFragment() != null) {
            throw new IllegalArgumentException("kernelsUri must not contain user-info or a fragment");
        }
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        headers.forEach((name, value) -> {
            if (name == null || name.isBlank() || value == null
                    || name.chars().anyMatch(Character::isISOControl)
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("HTTP headers must have non-empty names and safe values");
            }
        });
    }

    /**
     * 创建不需要额外认证头的端点。
     *
     * @param kernelsUri Jupyter 内核集合端点
     */
    public JupyterGatewayEndpoint(URI kernelsUri) {
        this(kernelsUri, Map.of());
    }
}
