package org.fzb.smolagents.cli;

import org.fzb.smolagents.adapters.huggingface.HuggingFaceInferenceModel;
import org.fzb.smolagents.adapters.ollama.OllamaModel;
import org.fzb.smolagents.adapters.openai.OpenAiCompatibleModel;
import org.fzb.smolagents.agent.Agent;
import org.fzb.smolagents.agent.CodeAgent;
import org.fzb.smolagents.agent.ToolCallingAgent;
import org.fzb.smolagents.core.AgentInput;
import org.fzb.smolagents.core.CancellationToken;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.RunOptions;
import org.fzb.smolagents.core.RunResult;
import org.fzb.smolagents.core.RunStatus;
import org.fzb.smolagents.core.SmolagentsException;
import org.fzb.smolagents.event.AgentEvent;
import org.fzb.smolagents.executor.CodeExecutor;
import org.fzb.smolagents.executor.PythonProcessCodeExecutor;
import org.fzb.smolagents.model.Model;
import org.fzb.smolagents.model.ModelCapabilities;
import org.fzb.smolagents.model.ModelStreamEvent;
import org.fzb.smolagents.tool.ApiWebSearchTool;
import org.fzb.smolagents.tool.DuckDuckGoSearchTool;
import org.fzb.smolagents.tool.GoogleSearchTool;
import org.fzb.smolagents.tool.PythonInterpreterTool;
import org.fzb.smolagents.tool.SpeechToTextTool;
import org.fzb.smolagents.tool.TransformersWhisperTool;
import org.fzb.smolagents.tool.Tool;
import org.fzb.smolagents.tool.UserInputTool;
import org.fzb.smolagents.tool.VisitWebpageTool;
import org.fzb.smolagents.tool.VisualBrowserTool;
import org.fzb.smolagents.tool.WebSearchTool;
import org.fzb.smolagents.tool.WikipediaSearchTool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 提供 smolagents Java 的非交互和简易交互命令行入口。
 *
 * <p>支持 Ollama 原生模型、Hugging Face Inference Router 和 OpenAI-compatible endpoint；
 * 支持 CodeAgent 与 ToolCallingAgent，以及内置网页搜索、网页访问、Wikipedia 和用户输入工具。
 * CodeAgent 默认使用当前操作系统权限的本地 Python 进程，不是安全沙箱。</p>
 */
public final class SmolagentsCli {
    private static final int DEFAULT_MAX_STEPS = 6;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(3);
    private static final String DEFAULT_MODEL_ID = "gemma4:26b";
    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a helpful assistant. Use the available tools when they help solve the task. "
                    + "Call final_answer when you are ready to respond.";

    /**
     * 创建 CLI 应用入口。
     */
    public SmolagentsCli() {
    }

    /**
     * 运行 CLI 并使用指定标准流读写。
     *
     * @param args 命令行参数
     * @param input 标准输入
     * @param output 标准输出
     * @param error 标准错误输出
     * @return 进程退出码；成功为 0，参数错误为 2，运行失败为 1
     */
    public int run(String[] args, InputStream input, PrintStream output, PrintStream error) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
        Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        return run(args, reader, writer, output, error);
    }

    /**
     * 运行 CLI 并允许调用方注入可复用 Reader/Writer。
     *
     * @param args 命令行参数
     * @param source 标准输入来源
     * @param promptOutput 用户问题提示输出
     * @param output 标准输出
     * @param error 标准错误输出
     * @return 进程退出码
     */
    public int run(String[] args, Reader source, Writer promptOutput, PrintStream output, PrintStream error) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(promptOutput, "promptOutput");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        CommandLineOptions options;
        try {
            options = parseArguments(args);
        } catch (IllegalArgumentException failure) {
            error.println("Error: " + failure.getMessage());
            printUsage(error);
            return 2;
        }
        if (options.help()) {
            printUsage(output);
            return 0;
        }

        BufferedReader input = source instanceof BufferedReader buffered ? buffered : new BufferedReader(source);
        try {
            String prompt = options.prompt();
            if (prompt == null) {
                promptOutput.write("Task prompt: ");
                promptOutput.flush();
                prompt = input.readLine();
                if (prompt == null || prompt.isBlank()) {
                    error.println("Error: a non-empty task prompt is required");
                    return 2;
                }
            }
            return execute(options, prompt, input, promptOutput, output, error);
        } catch (IOException failure) {
            error.println("Unable to read or write CLI input: " + failure.getMessage());
            return 1;
        } catch (ConfigurationException | IllegalArgumentException failure) {
            error.println("Configuration error: " + failure.getMessage());
            return 2;
        } catch (SmolagentsException failure) {
            error.println("Agent execution failed: " + failure.getMessage());
            return 1;
        }
    }

    /**
     * 解析命令行 flag 与可选任务文本。
     *
     * @param args 原始参数数组
     * @return 已验证 CLI 配置
     * @throws IllegalArgumentException 参数缺失、非法或互相冲突时抛出
     */
    static CommandLineOptions parseArguments(String[] args) {
        Objects.requireNonNull(args, "args");
        MutableOptions parsed = new MutableOptions();
        List<String> promptTokens = new ArrayList<>();
        boolean positionalOnly = false;
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (positionalOnly) {
                promptTokens.add(argument);
            } else if ("--".equals(argument)) {
                positionalOnly = true;
            } else if ("--help".equals(argument) || "-h".equals(argument)) {
                parsed.help = true;
            } else if ("--model-type".equals(argument)) {
                parsed.modelType = nextValue(args, ++index, argument);
            } else if ("--action-type".equals(argument)) {
                parsed.actionType = nextValue(args, ++index, argument);
            } else if ("--model-id".equals(argument)) {
                parsed.modelId = nextValue(args, ++index, argument);
            } else if ("--api-base".equals(argument) || "--base-url".equals(argument)) {
                parsed.apiBase = nextValue(args, ++index, argument);
            } else if ("--api-key".equals(argument)) {
                parsed.apiKey = nextValue(args, ++index, argument);
            } else if ("--provider".equals(argument)) {
                parsed.provider = nextValue(args, ++index, argument);
            } else if ("--chrome-path".equals(argument)) {
                parsed.chromePath = nextValue(args, ++index, argument);
            } else if ("--browser-headless".equals(argument)) {
                parsed.browserHeadless = true;
            } else if ("--vision".equals(argument)) {
                parsed.modelVision = true;
            } else if ("--whisper-backend".equals(argument)) {
                parsed.whisperBackend = nextValue(args, ++index, argument);
            } else if ("--whisper-model".equals(argument)) {
                parsed.whisperModel = nextValue(args, ++index, argument);
            } else if ("--whisper-python".equals(argument)) {
                parsed.whisperPython = nextValue(args, ++index, argument);
            } else if ("--whisper-device".equals(argument)) {
                parsed.whisperDevice = nextValue(args, ++index, argument);
            } else if ("--allow-whisper-download".equals(argument)) {
                parsed.allowWhisperDownload = true;
            } else if ("--tools".equals(argument)) {
                String value = nextValue(args, ++index, argument);
                parsed.tools.clear();
                for (String tool : value.split(",")) {
                    if (!tool.isBlank()) {
                        parsed.tools.add(tool.strip());
                    }
                }
            } else if ("--imports".equals(argument)) {
                String value = nextValue(args, ++index, argument);
                for (String module : value.split(",")) {
                    if (!module.isBlank()) {
                        parsed.additionalAuthorizedImports.add(module.strip());
                    }
                }
            } else if ("--import".equals(argument)) {
                parsed.additionalAuthorizedImports.add(nextValue(args, ++index, argument));
            } else if ("--tool".equals(argument)) {
                parsed.tools.add(nextValue(args, ++index, argument));
            } else if ("--max-steps".equals(argument)) {
                parsed.maxSteps = parsePositiveInt(nextValue(args, ++index, argument), argument);
            } else if ("--timeout-seconds".equals(argument)) {
                parsed.timeout = Duration.ofSeconds(parsePositiveLong(
                        nextValue(args, ++index, argument),
                        argument
                ));
            } else if ("--stream".equals(argument)) {
                parsed.stream = true;
            } else if ("--no-stream".equals(argument)) {
                parsed.stream = false;
            } else if (argument.startsWith("-")) {
                throw new IllegalArgumentException("unknown option: " + argument);
            } else {
                promptTokens.add(argument);
            }
        }
        if (parsed.modelType != null && !Set.of("ollama", "huggingface", "openai-compatible")
                .contains(parsed.modelType)) {
            throw new IllegalArgumentException("unsupported model type: " + parsed.modelType);
        }
        if (parsed.actionType != null && !Set.of("code", "tool_calling").contains(parsed.actionType)) {
            throw new IllegalArgumentException("unsupported action type: " + parsed.actionType);
        }
        String modelType = parsed.modelType == null ? "ollama" : parsed.modelType;
        if (parsed.provider != null && !"huggingface".equals(modelType)) {
            throw new IllegalArgumentException("--provider is supported only with --model-type huggingface");
        }
        if (!Set.of("api", "transformers").contains(parsed.whisperBackend)) {
            throw new IllegalArgumentException("--whisper-backend must be api or transformers");
        }
        if (parsed.tools.contains("visual_browser")) {
            if (parsed.chromePath == null || parsed.chromePath.isBlank()) {
                throw new IllegalArgumentException("visual_browser requires --chrome-path");
            }
            if (!"tool_calling".equals(parsed.actionType)) {
                throw new IllegalArgumentException("visual_browser requires --action-type tool_calling");
            }
            if (!parsed.modelVision && !"openai-compatible".equals(modelType)) {
                throw new IllegalArgumentException("visual_browser requires a vision-capable model; specify --vision");
            }
        }
        String prompt = promptTokens.isEmpty() ? null : String.join(" ", promptTokens);
        try {
            if (parsed.chromePath != null) {
                java.nio.file.Path.of(parsed.chromePath);
            }
        } catch (java.nio.file.InvalidPathException failure) {
            throw new IllegalArgumentException("--chrome-path is not a valid filesystem path", failure);
        }
        return new CommandLineOptions(
                parsed.help,
                prompt,
                modelType,
                parsed.actionType == null ? "code" : parsed.actionType,
                parsed.modelId == null ? DEFAULT_MODEL_ID : parsed.modelId,
                parsed.apiBase,
                parsed.apiKey,
                parsed.provider,
                List.copyOf(parsed.tools),
                List.copyOf(parsed.additionalAuthorizedImports),
                parsed.chromePath,
                parsed.browserHeadless,
                parsed.modelVision,
                parsed.whisperBackend,
                parsed.whisperModel,
                parsed.whisperPython,
                parsed.whisperDevice,
                parsed.allowWhisperDownload,
                parsed.stream,
                parsed.maxSteps,
                parsed.timeout
        );
    }

    /**
     * 按命令行选项装配模型与工具，并运行选定 Agent。
     *
     * @param options 已验证选项
     * @param prompt 用户任务
     * @param input 交互输入
     * @param promptOutput 工具提问输出
     * @param output CLI 标准输出
     * @param error CLI 标准错误输出
     * @return CLI 退出码
     * @throws IOException 输入/输出操作失败时抛出
     */
    private static int execute(
            CommandLineOptions options,
            String prompt,
            BufferedReader input,
            Writer promptOutput,
            PrintStream output,
            PrintStream error
    ) throws IOException {
        try (CliResources resources = new CliResources()) {
            List<Tool> tools = createTools(options, input, promptOutput, resources);
            ensureUniqueTools(tools);
            Model model = createModel(options);
            AgentInput agentInput = new AgentInput(prompt, java.util.Map.of());
            RunOptions runOptions = new RunOptions(
                    true,
                    options.maxSteps(),
                    true,
                    options.timeout(),
                    new CancellationToken()
            );
            if ("code".equals(options.actionType())) {
                error.println(
                        "Warning: CodeAgent runs generated Python as the current OS user; "
                                + "this is not a security sandbox."
                );
                try (CodeAgent agent = new CodeAgent(
                        model,
                        tools,
                        new PythonProcessCodeExecutor(options.additionalAuthorizedImports()),
                        options.maxSteps(),
                        null
                )) {
                    return runAgent(agent, agentInput, runOptions, options.stream(), output, error);
                }
            }
            if (options.tools().contains("python_interpreter")) {
                error.println(
                        "Warning: python_interpreter runs code as the current OS user; this is not a security sandbox."
                );
            }
            ToolCallingAgent agent = new ToolCallingAgent(
                    model,
                    tools,
                    options.maxSteps(),
                    DEFAULT_SYSTEM_PROMPT
            );
            return runAgent(agent, agentInput, runOptions, options.stream(), output, error);
        }
    }

    /**
     * 运行 Agent 的同步或事件流入口，并输出最终结果或显式失败。
     *
     * @param agent 已配置 Agent
     * @param input Agent 任务输入
     * @param options 运行选项
     * @param stream 是否展示模型文本 delta
     * @param output 标准输出
     * @param error 标准错误输出
     * @return 进程退出码
     */
    private static int runAgent(
            Agent agent,
            AgentInput input,
            RunOptions options,
            boolean stream,
            PrintStream output,
            PrintStream error
    ) {
        if (!stream) {
            return reportResult(agent.runFull(input, options), output, error);
        }
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<RunResult> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();
        StringBuilder streamedText = new StringBuilder();
        agent.stream(input, options).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriptionRef.set(subscription);
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentEvent event) {
                if (event instanceof AgentEvent.ModelDelta delta
                        && delta.delta() instanceof ModelStreamEvent.MessageDelta textDelta
                        && !textDelta.text().isEmpty()) {
                    synchronized (streamedText) {
                        streamedText.append(textDelta.text());
                    }
                    output.print(textDelta.text());
                    output.flush();
                } else if (event instanceof AgentEvent.ToolFailed toolFailure) {
                    error.println("Tool " + toolFailure.toolName() + " failed: " + toolFailure.error());
                } else if (event instanceof AgentEvent.RunCompleted completed) {
                    result.set(completed.result());
                } else if (event instanceof AgentEvent.RunFailed failed) {
                    failure.set(failed.error());
                }
            }

            @Override
            public void onError(Throwable throwable) {
                failure.set(throwable);
                finished.countDown();
            }

            @Override
            public void onComplete() {
                finished.countDown();
            }
        });

        try {
            if (!finished.await(streamWaitTimeoutMillis(options.timeout()), TimeUnit.MILLISECONDS)) {
                Flow.Subscription subscription = subscriptionRef.get();
                if (subscription != null) {
                    subscription.cancel();
                } else {
                    options.cancellation().cancel();
                }
                error.println("Agent event stream did not finish before the CLI wait timeout");
                return 1;
            }
        } catch (InterruptedException interrupted) {
            Flow.Subscription subscription = subscriptionRef.get();
            if (subscription != null) {
                subscription.cancel();
            } else {
                options.cancellation().cancel();
            }
            Thread.currentThread().interrupt();
            error.println("Interrupted while waiting for Agent event stream");
            return 1;
        }
        Throwable streamFailure = failure.get();
        if (streamFailure != null) {
            error.println("Agent execution failed: " + Objects.toString(
                    streamFailure.getMessage(),
                    streamFailure.getClass().getName()
            ));
            return 1;
        }
        RunResult completed = result.get();
        if (completed == null) {
            error.println("Agent event stream completed without a RunCompleted event");
            return 1;
        }
        boolean finalAnswerWasStreamed = completed.output().plainText()
                .map(answer -> {
                    synchronized (streamedText) {
                        return streamedText.toString().strip().equals(answer.strip());
                    }
                })
                .orElse(false);
        if (streamedText.length() > 0) {
            output.println();
        }
        if (finalAnswerWasStreamed) {
            return hasUsableAnswer(completed) ? 0 : 1;
        }
        return reportResult(completed, output, error);
    }

    /**
     * 将运行时限转换为 stream subscriber 的最大等待毫秒数，并预留完成事件调度时间。
     *
     * @param runTimeout Agent 单次运行 timeout
     * @return saturating millisecond timeout
     */
    private static long streamWaitTimeoutMillis(Duration runTimeout) {
        if (runTimeout == null) {
            return TimeUnit.MINUTES.toMillis(5);
        }
        try {
            long millis = runTimeout.toMillis();
            return millis > Long.MAX_VALUE - TimeUnit.SECONDS.toMillis(5)
                    ? Long.MAX_VALUE
                    : millis + TimeUnit.SECONDS.toMillis(5);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * 创建当前 CLI 支持的模型适配器。
     *
     * @param options 已验证 Provider 选项
     * @return 配置完成的模型
     */
    private static Model createModel(CommandLineOptions options) {
        String apiKey = options.apiKey();
        return switch (options.modelType()) {
            case "ollama" -> new OllamaModel(
                    options.modelId(),
                    options.apiBase() == null
                            ? URI.create(System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434"))
                            : URI.create(options.apiBase()),
                    options.timeout(),
                    java.util.Map.of(),
                    null,
                    new ModelCapabilities(true, false, options.modelVision(), true)
            );
            case "huggingface" -> new HuggingFaceInferenceModel(
                    options.modelId(),
                    options.provider(),
                    apiKey,
                    options.apiBase() == null ? null : URI.create(options.apiBase()),
                    options.timeout(),
                    new ModelCapabilities(true, false, options.modelVision(), true)
            );
            case "openai-compatible" -> new OpenAiCompatibleModel(
                    options.modelId(),
                    URI.create(options.apiBase() == null
                            ? System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1")
                            : options.apiBase()),
                    apiKey == null ? System.getenv("OPENAI_API_KEY") : apiKey,
                    options.timeout(),
                    new ModelCapabilities(true, false, true, true)
            );
            default -> throw new ConfigurationException("Unsupported model type: " + options.modelType());
        };
    }

    /**
     * 将工具名称解析为默认工具实例。
     *
     * @param names 工具短名称
     * @param input 标准输入
     * @param promptOutput 用户提问 writer
     * @return 按命令行顺序创建的工具
     */
    private static List<Tool> createTools(
            CommandLineOptions options,
            BufferedReader input,
            Writer promptOutput,
            CliResources resources
    ) {
        List<Tool> tools = new ArrayList<>();
        for (String name : options.tools()) {
            Tool tool = switch (name) {
                case "web_search", "duckduckgo_search" -> new DuckDuckGoSearchTool();
                case "google_search" -> new GoogleSearchTool();
                case "api_web_search" -> new ApiWebSearchTool();
                case "exa_search" -> new WebSearchTool(10, WebSearchTool.SearchEngine.EXA);
                case "visit_webpage" -> new VisitWebpageTool();
                case "wikipedia_search" -> new WikipediaSearchTool();
                case "transcriber" -> createTranscriber(options);
                case "visual_browser" -> new VisualBrowserTool(
                        java.nio.file.Path.of(options.chromePath()),
                        options.timeout(),
                        options.browserHeadless(),
                        false
                );
                case "user_input" -> new UserInputTool(input, promptOutput);
                case "python_interpreter" -> {
                    CodeExecutor executor = new PythonProcessCodeExecutor();
                    resources.register(executor);
                    yield new PythonInterpreterTool(executor);
                }
                default -> throw new ConfigurationException("Unknown built-in tool: " + name);
            };
            tools.add(tool);
            if (tool instanceof AutoCloseable closeable) {
                resources.register(closeable);
            }
        }
        return List.copyOf(tools);
    }

    /**
     * 按语音转录后端配置创建 API 或本地 Transformers 工具。
     *
     * @param options 完整 CLI 配置
     * @return 已配置的音频转录工具
     */
    private static Tool createTranscriber(CommandLineOptions options) {
        if ("api".equals(options.whisperBackend())) {
            return options.apiKey() == null
                    ? new SpeechToTextTool()
                    : new SpeechToTextTool(options.apiKey());
        }
        if ("transformers".equals(options.whisperBackend())) {
            String python = options.whisperPython() == null
                    ? System.getenv().getOrDefault("SMOLAGENTS_PYTHON", "python")
                    : options.whisperPython();
            return new TransformersWhisperTool(
                    options.whisperModel(),
                    python,
                    options.whisperDevice(),
                    !options.allowWhisperDownload(),
                    Duration.ofMinutes(10),
                    options.timeout(),
                    25 * 1024 * 1024
            );
        }
        throw new ConfigurationException("Unsupported transcriber backend: " + options.whisperBackend());
    }

    /**
     * 拒绝同一搜索工具名称的重复注册。
     *
     * @param tools 已装配工具
     */
    private static void ensureUniqueTools(List<Tool> tools) {
        Set<String> names = new HashSet<>();
        for (Tool tool : tools) {
            if (!names.add(tool.spec().name())) {
                throw new ConfigurationException(
                        "Multiple selected tools expose the same tool name: " + tool.spec().name()
                );
            }
        }
    }

    /**
     * 将 Agent 成功输出写到标准输出，并将非成功状态映射为非零退出码。
     *
     * @param result 完整 Agent 运行结果
     * @param output CLI 标准输出
     * @param error CLI 标准错误输出
     * @return 运行退出码
     */
    private static int reportResult(RunResult result, PrintStream output, PrintStream error) {
        if (!hasUsableAnswer(result)) {
            error.println("Agent finished with status: " + result.status());
            return 1;
        }
        String answer = result.output().plainText().orElseGet(() -> {
            var structured = result.output().structuredContent();
            return structured == null ? "[Agent produced non-text output]" : structured.toString();
        });
        output.println(answer);
        return 0;
    }

    /**
     * 判定运行结果是否包含 CLI 可返回的最终/尽力答案。
     *
     * @param result Agent 完整运行结果
     * @return SUCCESS 或 MAX_STEPS_REACHED 且包含 output 时为 true
     */
    private static boolean hasUsableAnswer(RunResult result) {
        return result.output() != null
                && (result.status() == RunStatus.SUCCESS || result.status() == RunStatus.MAX_STEPS_REACHED);
    }

    /**
     * 读取 flag 后的必需非空参数。
     *
     * @param args 原始参数
     * @param index 下一个参数索引
     * @param flag 当前选项
     * @return 参数值
     */
    private static String nextValue(String[] args, int index, String flag) {
        if (index >= args.length || args[index].startsWith("-")) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return args[index];
    }

    /**
     * 解析严格为正的整数选项。
     *
     * @param value 输入字符串
     * @param option 选项名称
     * @return 正整数
     */
    private static int parsePositiveInt(String value, String option) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 使用统一的选项诊断消息报告非法值。
        }
        throw new IllegalArgumentException(option + " must be a positive integer");
    }

    /**
     * 解析严格为正的长整数选项。
     *
     * @param value 输入字符串
     * @param option 选项名称
     * @return 正长整数
     */
    private static long parsePositiveLong(String value, String option) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 使用统一的选项诊断消息报告非法值。
        }
        throw new IllegalArgumentException(option + " must be a positive integer");
    }

    /**
     * 打印 CLI 使用方式、模型选项和本地代码执行边界。
     *
     * @param output 输出目标
     */
    private static void printUsage(PrintStream output) {
        output.println("""
                Usage: smolagents [options] [task prompt]
                  --model-type <ollama|huggingface|openai-compatible>
                  --model-id <id>                 Default: gemma4:26b
                  --action-type <code|tool_calling> Default: code
                  --api-base <url>                Optional model endpoint
                  --api-key <key>                 Prefer provider environment variables
                  --provider <name>              Hugging Face provider
                    --vision                       Explicitly enable Ollama/HF image input
                    --chrome-path <path>           Chrome executable (required by visual_browser)
                    --browser-headless             Start visual_browser without a window
                    --tools <name,name,...>         Default: web_search
                    --whisper-backend <api|transformers> Default: api
                    --whisper-model <id>            Local Transformers Whisper checkpoint
                    --whisper-python <path>         Python executable for Transformers worker
                    --whisper-device <device>       Default: auto
                    --allow-whisper-download        Allow downloading missing Whisper model files
                  --imports <name,name,...>       Additional Python imports allowed by CodeAgent
                  --import <name>                 Add one Python import (repeatable)
                  --tool <name>                   Add one tool (repeatable)
                  --max-steps <count>             Default: 6
                  --timeout-seconds <seconds>     Default: 180
                  --no-stream                     Wait and print only the final result
                  --help                          Show this help

                Built-in tools: web_search, google_search, api_web_search, exa_search,
                visit_webpage, wikipedia_search, transcriber, visual_browser, user_input, python_interpreter.
                Transformers Whisper is local-only by default and requires its Python dependencies.
                CodeAgent executes generated Python with current OS permissions; it is not a sandbox.
                """);
    }

    /**
     * 保存参数解析过程中的可覆盖默认值。
     */
    private static final class MutableOptions {
        private boolean help;
        private String modelType;
        private String actionType;
        private String modelId;
        private String apiBase;
        private String apiKey;
        private String provider;
        private String chromePath;
        private boolean browserHeadless;
        private boolean modelVision;
        private String whisperBackend = "api";
        private String whisperModel = "openai/whisper-large-v3-turbo";
        private String whisperPython;
        private String whisperDevice = "auto";
        private boolean allowWhisperDownload;
        private final List<String> tools = new ArrayList<>(List.of("web_search"));
        private final List<String> additionalAuthorizedImports = new ArrayList<>();
        private boolean stream = true;
        private int maxSteps = DEFAULT_MAX_STEPS;
        private Duration timeout = DEFAULT_TIMEOUT;
    }

    /**
     * 管理 CLI 为辅助工具创建的代码执行器。
     */
    private static final class CliResources implements AutoCloseable {
        private final List<AutoCloseable> resources = new ArrayList<>();

        /**
         * 将可关闭执行器或工具加入 CLI 资源生命周期。
         *
         * @param resource 待关闭资源
         */
        private void register(AutoCloseable resource) {
            resources.add(resource);
        }

        /**
         * 按创建逆序关闭资源并保留多个清理错误。
         */
        @Override
        public void close() {
            RuntimeException failure = null;
            for (int index = resources.size() - 1; index >= 0; index--) {
                try {
                    resources.get(index).close();
                } catch (Exception error) {
                    if (failure == null) {
                        failure = new SmolagentsException("Failed to close CLI resource", error);
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
