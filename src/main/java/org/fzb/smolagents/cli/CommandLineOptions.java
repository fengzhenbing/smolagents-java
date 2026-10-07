package org.fzb.smolagents.cli;

import java.time.Duration;
import java.util.List;

/**
 * 保存解析完成的 smolagents Java CLI 选项。
 *
 * @param help 是否仅显示帮助
 * @param prompt 可选任务文本；为空时从标准输入读取
 * @param modelType 模型类型
 * @param actionType Agent 动作模式
 * @param modelId Provider 模型标识
 * @param apiBase 自定义 Provider base URL
 * @param apiKey 可选 API key
 * @param provider 可选 Hugging Face provider
 * @param tools 要装配的工具名称
 * @param additionalAuthorizedImports CodeAgent 额外允许的 Python import
 * @param chromePath Chrome/Chromium executable 完整路径
 * @param browserHeadless 是否以 headless 模式启动浏览器
 * @param modelVision 是否显式声明 Ollama/Hugging Face 模型支持图像输入
 * @param whisperBackend 语音转录后端
 * @param whisperModel Transformers Whisper checkpoint 或模型 ID
 * @param whisperPython Transformers worker 使用的 Python executable
 * @param whisperDevice Whisper 推理设备
 * @param allowWhisperDownload 是否允许 Transformers 从 Hub 下载模型
 * @param stream 是否转发模型文本增量
 * @param maxSteps Agent 步数上限
 * @param timeout 模型和运行超时
 */
record CommandLineOptions(
        boolean help,
        String prompt,
        String modelType,
        String actionType,
        String modelId,
        String apiBase,
        String apiKey,
        String provider,
        List<String> tools,
        List<String> additionalAuthorizedImports,
        String chromePath,
        boolean browserHeadless,
        boolean modelVision,
        String whisperBackend,
        String whisperModel,
        String whisperPython,
        String whisperDevice,
        boolean allowWhisperDownload,
        boolean stream,
        int maxSteps,
        Duration timeout
) {
}
