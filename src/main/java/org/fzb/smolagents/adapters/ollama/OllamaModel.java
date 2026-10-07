package org.fzb.smolagents.adapters.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import org.fzb.smolagents.model.GenerationRequest;
import org.fzb.smolagents.model.Model;
import org.fzb.smolagents.model.ModelCapabilities;
import org.fzb.smolagents.model.ModelStreamEvent;
import org.fzb.smolagents.model.ChatMessage;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Flow;

/**
 * Ollama 原生聊天 API 模型的公开配置入口。
 */
public final class OllamaModel implements Model {
    private static final URI DEFAULT_BASE_URI = URI.create("http://localhost:11434");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(3);

    private final NativeOllamaModel delegate;

    /**
     * 使用默认服务地址、超时及模型选项创建 Ollama 客户端。
     *
     * @param modelId Ollama 模型标识
     */
    public OllamaModel(String modelId) {
        this(modelId, DEFAULT_BASE_URI, DEFAULT_TIMEOUT, Map.of(), null);
    }

    /**
     * 使用指定服务地址和请求超时创建 Ollama 客户端。
     *
     * @param modelId Ollama 模型标识
     * @param baseUri Ollama 服务基础 URI
     * @param timeout 请求超时
     */
    public OllamaModel(String modelId, URI baseUri, Duration timeout) {
        this(modelId, baseUri, timeout, Map.of(), null);
    }

    /**
     * 使用完整 Ollama 原生选项创建客户端。
     *
     * @param modelId Ollama 模型标识
     * @param baseUri Ollama 服务基础 URI
     * @param timeout 请求超时
     * @param options 原生运行选项，例如 {@code num_gpu}
     * @param think 思考模式开关；null 时使用服务端默认值
     */
    public OllamaModel(
            String modelId,
            URI baseUri,
            Duration timeout,
            Map<String, JsonNode> options,
            Boolean think
    ) {
        this(
                modelId,
                baseUri,
                timeout,
                options,
                think,
                new ModelCapabilities(true, false, false, true)
        );
    }

    /**
     * 使用完整运行选项及调用方声明的模型能力创建 Ollama 客户端。
     *
     * @param modelId Ollama 模型标识
     * @param baseUri Ollama 服务基础 URI
     * @param timeout 请求超时
     * @param options Ollama 原生运行选项
     * @param think 思考模式开关
     * @param capabilities 针对所选模型显式声明的能力
     */
    public OllamaModel(
            String modelId,
            URI baseUri,
            Duration timeout,
            Map<String, JsonNode> options,
            Boolean think,
            ModelCapabilities capabilities
    ) {
        this.delegate = new NativeOllamaModel(modelId, baseUri, timeout, options, think, capabilities);
    }

    /**
     * 发送一次非流式聊天请求。
     *
     * @param request 核心生成请求
     * @return assistant 模型消息
     */
    @Override
    public ChatMessage generate(GenerationRequest request) {
        return delegate.generate(request);
    }

    /**
     * 以 NDJSON 流方式生成模型响应事件。
     *
     * @param request 核心生成请求
     * @return 模型事件流
     */
    @Override
    public Flow.Publisher<ModelStreamEvent> stream(GenerationRequest request) {
        return delegate.stream(request);
    }

    /**
     * 返回 Ollama 适配器的模型能力。
     *
     * @return 模型能力
     */
    @Override
    public ModelCapabilities capabilities() {
        return delegate.capabilities();
    }
}
