package org.fzb.smolagents.executor.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fzb.smolagents.core.ExecutorException;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 通过 Jupyter Kernel Gateway HTTP API 管理内核，并委托 provider hook 管理沙箱。
 */
final class JupyterKernelGatewayProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String providerName;
    private final JupyterSandboxProvider sandboxProvider;
    private final HttpClient httpClient;
    private final RemoteExecutorOptions options;

    JupyterKernelGatewayProvider(
            String providerName,
            JupyterSandboxProvider sandboxProvider,
            HttpClient httpClient,
            RemoteExecutorOptions options
    ) {
        this.providerName = Objects.requireNonNull(providerName, "providerName");
            if (sandboxProvider == null) {
                throw new ExecutorException(providerName
                        + " requires an explicit sandbox session provider; cloud SDK provisioning is not bundled");
            }
            this.sandboxProvider = sandboxProvider;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.options = Objects.requireNonNull(options, "options");
    }

    RemoteKernelSession openSession() {
        JupyterGatewayEndpoint endpoint = null;
        try {
            endpoint = Objects.requireNonNull(sandboxProvider.openSandbox(), "sandbox provider returned no endpoint");
            return createKernel(endpoint);
        } catch (RuntimeException error) {
            if (endpoint != null) {
                closeSandboxAfterFailure(endpoint, error);
            }
            if (error instanceof ExecutorException executorError) {
                throw executorError;
            }
            throw new ExecutorException(providerName + " sandbox/Jupyter configuration failed: " + safeMessage(error), error);
        }
    }

    RemoteKernelSession resetSession(RemoteKernelSession current) {
        Objects.requireNonNull(current, "current");
        try {
            deleteKernel(current);
            return createKernel(current.endpoint());
        } catch (RuntimeException error) {
            closeSandboxAfterFailure(current.endpoint(), error);
            throw error;
        }
    }

    void closeSession(RemoteKernelSession session) {
        if (session == null) {
            return;
        }
        RuntimeException failure = null;
        try {
            deleteKernel(session);
        } catch (RuntimeException error) {
            failure = error;
        }
        try {
            sandboxProvider.closeSandbox(session.endpoint());
        } catch (RuntimeException error) {
            if (failure == null) {
                failure = new ExecutorException(providerName + " sandbox cleanup failed", error);
            } else {
                failure.addSuppressed(error);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    void interruptSession(RemoteKernelSession session) {
        URI interruptUri = operationUri(session.endpoint().kernelsUri(), session.kernelId(), "interrupt");
        try {
            HttpResponse<byte[]> response = send(session.endpoint(), interruptUri, "POST");
            if (response.statusCode() != 200 && response.statusCode() != 204) {
                throw new ExecutorException(providerName + " Jupyter kernel interrupt failed (HTTP "
                        + response.statusCode() + ")");
            }
        } catch (Exception error) {
            throw new ExecutorException(providerName + " could not interrupt its Jupyter kernel", error);
        }
    }

    private RemoteKernelSession createKernel(JupyterGatewayEndpoint endpoint) {
        try {
            HttpResponse<byte[]> response = send(endpoint, endpoint.kernelsUri(), "POST");
            if (response.statusCode() != 201) {
                throw new ExecutorException(providerName + " Jupyter kernel creation failed (HTTP "
                        + response.statusCode() + "): " + clippedBody(response.body()));
            }
            JsonNode body = MAPPER.readTree(response.body());
            String kernelId = body.path("id").asText("");
            if (kernelId.isBlank()) {
                throw new ExecutorException(providerName + " Jupyter kernel creation response did not contain an id");
            }
            return new RemoteKernelSession(endpoint, kernelId, channelsUri(endpoint.kernelsUri(), kernelId));
        } catch (ExecutorException error) {
            throw error;
        } catch (Exception error) {
            throw new ExecutorException(providerName + " could not create a Jupyter kernel: " + safeMessage(error), error);
        }
    }

    private void deleteKernel(RemoteKernelSession session) {
        try {
            URI deleteUri = childUri(session.endpoint().kernelsUri(), session.kernelId());
            HttpResponse<byte[]> response = send(session.endpoint(), deleteUri, "DELETE");
            if (response.statusCode() != 200 && response.statusCode() != 204 && response.statusCode() != 404) {
                throw new ExecutorException(providerName + " Jupyter kernel deletion failed (HTTP "
                        + response.statusCode() + "): " + clippedBody(response.body()));
            }
        } catch (ExecutorException error) {
            throw error;
        } catch (Exception error) {
            throw new ExecutorException(providerName + " could not delete its Jupyter kernel: " + safeMessage(error), error);
        }
    }

    private HttpResponse<byte[]> send(JupyterGatewayEndpoint endpoint, URI uri, String method) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(options.setupTimeout())
                .header("Accept", "application/json");
        endpoint.headers().forEach(request::header);
        if ("POST".equals(method)) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.DELETE();
        }
        try {
            return httpClient.sendAsync(request.build(), info -> new BoundedBodySubscriber(options.maxHttpResponseBytes()))
                    .get(options.setupTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ExecutorException("Interrupted while calling " + providerName + " Jupyter HTTP API", error);
        } catch (ExecutionException | TimeoutException error) {
            throw new ExecutorException("Timed out or failed calling " + providerName + " Jupyter HTTP API", error);
        }
    }

    private URI channelsUri(URI kernelsUri, String kernelId) {
        URI child = childUri(kernelsUri, kernelId);
        String text = child.toASCIIString();
        int query = text.indexOf('?');
        String path = query < 0 ? text : text.substring(0, query);
        String suffix = query < 0 ? "" : text.substring(query);
        return URI.create(toWebSocketScheme(path) + "/channels" + suffix);
    }

    private static URI childUri(URI kernelsUri, String child) {
        return appendPath(kernelsUri, URLEncoder.encode(child, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    private static URI operationUri(URI kernelsUri, String kernelId, String operation) {
        URI kernelUri = childUri(kernelsUri, kernelId);
        return appendPath(kernelUri, operation);
    }

    private static URI appendPath(URI baseUri, String segment) {
        String text = baseUri.toASCIIString();
        int query = text.indexOf('?');
        String path = query < 0 ? text : text.substring(0, query);
        String suffix = query < 0 ? "" : text.substring(query);
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return URI.create(path + "/" + segment + suffix);
    }

    private static String toWebSocketScheme(String uri) {
        int schemeSeparator = uri.indexOf(':');
        String scheme = schemeSeparator < 0 ? "" : uri.substring(0, schemeSeparator);
        if ("https".equalsIgnoreCase(scheme)) {
            return "wss" + uri.substring(schemeSeparator);
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return "ws" + uri.substring(schemeSeparator);
        }
        throw new IllegalArgumentException("Jupyter URI must use http or https");
    }

    private void closeSandboxAfterFailure(JupyterGatewayEndpoint endpoint, RuntimeException original) {
        try {
            sandboxProvider.closeSandbox(endpoint);
        } catch (RuntimeException cleanupError) {
            original.addSuppressed(cleanupError);
        }
    }

    private static String clippedBody(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() <= 512 ? text : text.substring(0, 512) + "…";
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximumBytes;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private BoundedBodySubscriber(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                int size = item.remaining();
                if (bytes.size() + size > maximumBytes) {
                    subscription.cancel();
                    body.completeExceptionally(new ExecutorException("Jupyter HTTP response exceeded configured size limit"));
                    return;
                }
                byte[] chunk = new byte[size];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }

}
