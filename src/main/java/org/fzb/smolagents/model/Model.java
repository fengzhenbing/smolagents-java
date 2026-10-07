package org.fzb.smolagents.model;

import java.util.concurrent.Flow;

/**
 * 模型 Provider 的统一同步与流式调用接口。
 */
public interface Model {
    /**
     * 同步生成一条 assistant 消息。
     *
     * @param request 完整生成请求
     * @return 模型响应
     * @throws org.fzb.smolagents.core.ModelException Provider 调用或响应协议失败
     */
    ChatMessage generate(GenerationRequest request);

    /**
     * 以 Reactive Streams 事件形式生成消息。
     *
     * @param request 完整生成请求
     * @return 模型增量与完成事件发布器
     */
    Flow.Publisher<ModelStreamEvent> stream(GenerationRequest request);

    /**
     * 返回当前模型实例支持的协议能力。
     *
     * @return 模型能力声明
     */
    ModelCapabilities capabilities();
}
