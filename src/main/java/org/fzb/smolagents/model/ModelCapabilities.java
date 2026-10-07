package org.fzb.smolagents.model;

/**
 * 描述一个模型实例已配置并可使用的协议能力。
 *
 * @param toolCalling 是否支持工具调用
 * @param structuredOutput 是否支持 JSON Schema 输出
 * @param vision 是否支持图像输入
 * @param streaming 是否支持流式生成
 * @param audio 是否支持内嵌音频输入
 */
public record ModelCapabilities(
        boolean toolCalling,
        boolean structuredOutput,
        boolean vision,
        boolean streaming,
        boolean audio
) {
    /**
     * 创建未声明音频输入能力的模型能力配置，兼容原有四参数构造方式。
     *
     * @param toolCalling 是否支持工具调用
     * @param structuredOutput 是否支持 JSON Schema 输出
     * @param vision 是否支持图像输入
     * @param streaming 是否支持流式生成
     */
    public ModelCapabilities(
            boolean toolCalling,
            boolean structuredOutput,
            boolean vision,
            boolean streaming
    ) {
        this(toolCalling, structuredOutput, vision, streaming, false);
    }
}
