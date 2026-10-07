package org.fzb.smolagents.adapters.huggingface;

import org.fzb.smolagents.adapters.openai.OpenAiCompatibleModel;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.model.ChatMessage;
import org.fzb.smolagents.model.GenerationRequest;
import org.fzb.smolagents.model.Model;
import org.fzb.smolagents.model.ModelCapabilities;
import org.fzb.smolagents.model.ModelStreamEvent;
import org.fzb.smolagents.model.MessageRole;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * 通过 Hugging Face Inference Providers 的 OpenAI-compatible Chat Completions API 调用模型。
 *
 * <p>默认访问 {@code https://router.huggingface.co/v1}，认证 token 默认从 {@code HF_TOKEN}
 * 环境变量读取。选择 provider 时会将其追加到 model ID，例如 {@code model-id:provider}；
 * 也可通过自定义 base URL 连接支持 Chat Completions 协议的专用端点。</p>
 *
 * <p>请求序列化、工具调用解析和 SSE 处理委托给 OpenAI-compatible adapter，因此该实现
 * 支持的是 OpenAI Chat Completions 协议交集，而不是 Hugging Face Python SDK 的全部功能。</p>
 */
public final class HuggingFaceInferenceModel implements Model {
    private static final String DEFAULT_MODEL_ID = "Qwen/Qwen3-Next-80B-A3B-Thinking";
    private static final URI DEFAULT_BASE_URL = URI.create("https://router.huggingface.co/v1");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final ModelCapabilities DEFAULT_CAPABILITIES =
            new ModelCapabilities(true, false, false, true);

    private final String modelId;
    private final OpenAiCompatibleModel delegate;

    /**
     * 创建使用默认模型标识的 Hugging Face Router 实例。
     */
    public HuggingFaceInferenceModel() {
        this(DEFAULT_MODEL_ID);
    }

    /**
     * 创建默认 Hugging Face Router 模型实例。
     *
     * @param modelId Hugging Face 模型标识
     */
    public HuggingFaceInferenceModel(String modelId) {
        this(modelId, null, null, null, DEFAULT_TIMEOUT, DEFAULT_CAPABILITIES);
    }

    /**
     * 创建可选 provider、token 与自定义 endpoint 的 Hugging Face 模型实例。
     *
     * @param modelId Hugging Face 模型标识
     * @param provider 可选 Inference Provider 标识；指定后追加到 model ID
     * @param token Hugging Face token；为 null 时读取 {@code HF_TOKEN}，空字符串显式禁用认证
     * @param baseUrl Chat Completions API base URL；空值使用 Hugging Face Router
     * @param timeout HTTP 请求默认超时时长
     * @param capabilities 调用方对模型能力的配置声明
     */
    public HuggingFaceInferenceModel(
            String modelId,
            String provider,
            String token,
            URI baseUrl,
            Duration timeout,
            ModelCapabilities capabilities
    ) {
        this(modelId, provider, token, baseUrl, timeout, capabilities, Map.of());
    }

    /**
     * 创建 Hugging Face 模型并配置 endpoint 特有的消息角色转换。
     *
     * @param modelId Hugging Face 模型标识
     * @param provider 可选 Inference Provider 标识；指定后追加到 model ID
     * @param token Hugging Face token；null 时读取 {@code HF_TOKEN}
     * @param baseUrl Chat Completions API base URL；null 使用 Hugging Face Router
     * @param timeout HTTP 请求默认超时时长
     * @param capabilities 调用方对模型能力的配置声明
     * @param roleConversions 核心消息角色到 endpoint role 字符串的转换
     */
    public HuggingFaceInferenceModel(
            String modelId,
            String provider,
            String token,
            URI baseUrl,
            Duration timeout,
            ModelCapabilities capabilities,
            Map<MessageRole, String> roleConversions
    ) {
        if (modelId == null || modelId.isBlank()) {
            throw new ConfigurationException("modelId must not be blank");
        }
        String resolvedToken = token == null ? System.getenv("HF_TOKEN") : token;
        if (provider != null && provider.isBlank()) {
            throw new ConfigurationException("provider must not be blank when specified");
        }
        if (provider != null && modelId.contains(":")) {
            throw new ConfigurationException("modelId already includes a provider; do not specify provider twice");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new ConfigurationException("timeout must be positive");
        }
        this.modelId = provider == null ? modelId : modelId + ":" + provider;
        URI endpoint = baseUrl == null ? DEFAULT_BASE_URL : baseUrl;
        this.delegate = new OpenAiCompatibleModel(
                this.modelId,
                Objects.requireNonNull(endpoint, "baseUrl"),
                resolvedToken,
                timeout,
                Objects.requireNonNull(capabilities, "capabilities"),
                Objects.requireNonNull(roleConversions, "roleConversions")
        );
    }

    /**
     * 返回发给 Inference Providers 的实际 model identifier。
     *
     * @return 原始模型标识或带 provider 后缀的模型标识
     */
    public String modelId() {
        return modelId;
    }

    /**
     * 同步调用 Hugging Face Chat Completions endpoint。
     *
     * @param request 核心生成请求
     * @return assistant 模型消息
     */
    @Override
    public ChatMessage generate(GenerationRequest request) {
        return delegate.generate(request);
    }

    /**
     * 以 OpenAI-compatible SSE 协议流式调用模型。
     *
     * @param request 核心生成请求
     * @return 模型消息增量事件流
     */
    @Override
    public Flow.Publisher<ModelStreamEvent> stream(GenerationRequest request) {
        return delegate.stream(request);
    }

    /**
     * 返回构造实例时声明的模型能力。
     *
     * @return 模型能力声明
     */
    @Override
    public ModelCapabilities capabilities() {
        return delegate.capabilities();
    }
}
