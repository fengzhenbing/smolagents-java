# Core 领域模型与 API 契约

> 状态：当前领域 API 与已知差异；部分段落保留早期设计草案，实施状态以源码和 [04 兼容矩阵](04-compatibility-matrix.md) 为准。  
> 前置文档：[项目重实现计划](../README.md)、[01 总体架构与模块划分](01-architecture-and-modules.md)、[02 行为基线](02-behavior-baseline.md)  
> 参考源码：`D:\code\smolagents\src\smolagents\agents.py`、`models.py`、`memory.py`、`tools.py`

## 1. 目标与设计决定

本文把架构提议落到 Core 首批实现可遵循的 Java 类型和方法契约。目标不是逐字照搬 Python 类，而是确保模型消息、工具调用、运行记忆、Agent 循环和 Python 执行器边界能够组成可测的闭环。

第一版采用以下决定：

1. 使用 Java 21 `record` 表达不可变数据，使用 `sealed interface` 表达封闭的步骤和内容变体。
2. 采用组合式 `ActionStrategy` 区分 CodeAgent 与 ToolCallingAgent 的动作协议；公共循环由 `AgentRuntime` 承担。
3. Model 同时提供同步生成和 `java.util.concurrent.Flow.Publisher` 流式事件接口。普通运行保持直观的同步调用，流式适配器遵守 Reactive Streams 背压。
4. 工具参数和 JSON Schema 采用 Jackson `JsonNode`，避免以未类型化字符串或 `Object` 表达嵌套 JSON。Jackson 是 Core 首批依赖，具体版本在实现时统一管理。
5. 单个 `Agent` 默认拥有一个有状态会话，保持 Python 的连续记忆、Agent state 和执行器状态语义；同一实例的并发运行必须显式串行化或拒绝，不允许静默共享/交错写入。后续可提供多个独立 `AgentSession`。
6. `run()` 默认重置对话记忆，且允许设置 `reset=false` 续接对话；reset 不自动清除跨步骤 Python 执行器变量或 Agent state，和 Python 参考行为保持一致。单个 Agent 实例只允许一个活动 run；`AgentHttpServer` 的 `AgentSessionFactory` 通过创建不同 Agent 实例提供多个隔离 Web 会话，不共享 AgentMemory 或执行器；显式指定 session directory 时可持久化 history 与实现 `MemoryBackedAgent` 的 AgentMemory，但不会保存 CodeAgent 的 CPython namespace。

这些是便于开始实现的默认决策。若 Python 源码或行为基线测试证明语义不同，以可验证的兼容行为为准，并更新本文与测试。

## 2. 领域包边界

Maven 首阶段保持单模块，包按职责组织，避免 Core 导入特定 Provider、UI 或远端平台类：

| 包 | 主要类型 | 依赖边界 |
| --- | --- | --- |
| `org.fzb.smolagents.core` | `AgentInput`、`RunOptions`、`RunResult`、`RunStatus`、公共异常 | JDK 与 Jackson |
| `org.fzb.smolagents.model` | `Model`、请求/消息、token 用量、模型事件与能力 | Core；不依赖 Provider SDK |
| `org.fzb.smolagents.tool` | `Tool`、`ToolSpec`、`ToolRegistry`、`ToolResult`、参数校验 | Core；不依赖 MCP/搜索 SDK |
| `org.fzb.smolagents.memory` | `AgentMemory`、步骤变体、模型消息映射 | Core、Model |
| `org.fzb.smolagents.agent` | `AgentRuntime`、`ActionStrategy`、Agent 配置与会话 | Core、Model、Tool、Memory、Executor SPI |
| `org.fzb.smolagents.executor` | `CodeExecutor`、执行请求/结果及错误 | Core；不依赖特定 Python/远程执行产品 |
| `org.fzb.smolagents.event` | `AgentEvent` 及流式事件类型 | Core、Model |
| `org.fzb.smolagents.server` | loopback HTTP/SSE、AgentSessionFactory、多会话 UI | Core、Agent；不依赖 Web framework |
| `org.fzb.smolagents.adapters.*` | Provider、MCP、Hub、远端 Executor 适配器 | 适配器依赖 Core SPI；Core 不反向依赖适配器 |

不得为了方便把 Agent 实例、任意 Java 对象或 Provider 原始响应塞入公共 JSON/记忆结构；扩展值必须通过显式类型或序列化契约表达。

## 3. 消息与多模态值

### 3.1 聊天消息

`ChatMessage` 表示一条具有角色的模型上下文消息。角色至少支持 `SYSTEM`、`USER`、`ASSISTANT`、`TOOL`；不认识的 Provider 原始角色只能由适配器保存并映射，不得悄然转成 `USER`。

```java
record ChatMessage(
    MessageRole role,
    List<ContentPart> content,
    List<ToolCall> toolCalls,
    String name,
    String toolCallId,
    TokenUsage tokenUsage
) {}

record ToolCall(String id, String name, JsonNode arguments) {}
```

- 构造器复制集合并拒绝 `null` 元素；空 `content` 和空 `toolCalls` 是合法的。
- `ToolCall.arguments` 必须是 JSON object；缺少、非法或非 object 的参数在模型适配/解析边界抛出 `ModelOutputException`，不可替换为空对象。
- `TOOL` 消息必须带 `toolCallId`，其他角色不得被误当作工具 observation。
- 模型请求和记忆都使用同一消息类型，适配器只在 Provider 边界转换角色、内容和工具调用格式。

### 3.2 内容值

```java
sealed interface ContentPart permits ContentPart.Text, ContentPart.Image, ContentPart.Audio, ContentPart.Json {
    record Text(String text) implements ContentPart {}
    record Image(URI uri, String mimeType, byte[] data) implements ContentPart {}
    record Audio(URI uri, String mimeType, byte[] data, String transcript) implements ContentPart {}
    record Json(JsonNode value) implements ContentPart {}
}
```

当前代码已提供 Text/Image/Audio/Json 内容片段；图片/音频字节数组和 JSON 树执行防御性复制。OpenAI-compatible 与 Ollama adapter 依 `ModelCapabilities` 限制部分图像/音频请求。Provider 间媒体格式、音频生成和完整 Python `AgentImage`/`AgentAudio` 行为仍是 CAP-09 待办。

`AgentValue` 用于任务参数、工具观察和最终结果，可包含文本、JSON 或内容列表。不得通过 `toString()` 将图片/音频对象降级成伪文本。P0 的工具结果主要使用文本和 JSON，视觉/音频结果通过显式内容值扩展。

## 4. Model SPI

```java
interface Model {
    ChatMessage generate(GenerationRequest request);
    Flow.Publisher<ModelStreamEvent> stream(GenerationRequest request);
    ModelCapabilities capabilities();
}

record GenerationRequest(
    List<ChatMessage> messages,
    GenerationOptions options,
    List<ToolSpec> tools,
    CancellationToken cancellation,
    List<String> stopSequences
) {}
```

- `messages` 是完整上下文，顺序即时间顺序；`tools` 为空表示不发送工具定义。
- `stopSequences` 是可选的请求级停止字符串；OpenAI-compatible 使用 `stop`，原生 Ollama 使用 `options.stop`。旧四参数构造方式等价于空列表。
- 请求选项含 temperature、token 上限、超时、结构化输出 schema 等可选值；未设置值由适配器采用 Provider 默认值，禁止 Core 擅自覆盖调用方显式选项。
- `ModelCapabilities` 显式声明工具调用、JSON schema 输出、视觉、流式及音频输入能力。OpenAI-compatible adapter 对未声明的图像/音频输入以 `ConfigurationException` 拒绝；Ollama adapter 仅在调用方为所选模型显式打开 vision 时传输内嵌图像，当前不支持音频。
- OpenAI-compatible adapter 可通过显式 `MessageRole` 转换表适配要求 `developer` 等非标准角色名的 Provider；未配置时使用标准小写角色。
- `HuggingFaceInferenceModel` 默认使用 `HF_TOKEN` 与 Inference Router，可在不加载用户类的情况下配置 provider-qualified model ID、能力与角色映射；OpenAI-compatible 同步请求仅对已知 transient HTTP/I/O 错误有限重试。
- `generate()` 对超时、拒绝、网络和 Provider 错误抛出带 cause 的 `ModelException` 子类；返回 `null`、空 assistant 消息或成功形状的错误都不允许。
- 流式事件含消息增量、工具调用增量、usage、完成和失败；增量聚合由 Model 适配器负责，完成事件只发一次。订阅取消应取消上游请求。

流式回答不承诺与非流式回答逐字相同或具有固定 chunk 边界；协议、最终聚合消息和工具调用 ID 必须可确定性测试。

## 5. Tool 契约

```java
interface Tool {
    ToolSpec spec();
    ToolResult execute(JsonNode arguments, ToolExecutionContext context);
}

record ToolSpec(
    String name,
    String description,
    JsonNode inputSchema,
    boolean enabled,
    String outputType,
    JsonNode outputSchema
) {}

record ToolResult(List<ContentPart> content, JsonNode structuredContent) {}
```

- 工具名在 `ToolRegistry` 中唯一；重复注册立即抛 `ConfigurationException`。schema 必须是 JSON Schema object，工具对外暴露的参数名、必需字段和类型须与实际执行逻辑一致。
- `ToolSpec` 现保存 Python 兼容的 `outputType` 和可选 `outputSchema`，并对 schema getter 做防御性复制；旧四参数构造器保留兼容，未显式提供输出类型时默认 `string`（`final_answer` 默认 `any`）。`MethodToolAdapter` 按 Java 返回类型映射输出类型，MCP `outputSchema` 映射为 `object` 并保留 schema。任意工具的 legacy 默认值、Python `Tool.inputs` 的额外 metadata/default coercion 和完整 structured-output 行为仍需逐项对照。
- 在执行工具副作用前完成 JSON 结构和 schema 校验。多余字段、缺失字段、类型不符及 nullable/default 处理以 Python `tool_validation.py` 的测试基线为准；错误包含工具名和字段路径，不记录敏感参数值。
- `MethodToolAdapter` 可通过 `@ToolArgument(hasDefault = true, defaultValueJson = "...", required = false)` 声明 JSON 默认值；装配时先校验 JSON 语法和参数类型，schema 保留该值，调用省略参数时使用它。Java 不从方法体或签名推断默认值；普通 `ToolSpec` 中 JSON Schema 的 `default` 注解也不会被通用 registry 自动注入。
- 每个调用使用模型提供的 `ToolCall.id`，执行后以同一 ID 建立工具 observation；缺失或重复 ID 属于格式错误，不生成虚构 ID。
- 工具异常映射为 `ToolExecutionException` 并保留原始 cause。不得吞掉异常、返回空结果或把异常文本伪装为成功输出。
- `ToolExecutionContext` 当前提供取消信号和运行/调用 ID，不提供 logger，也不暴露可变 Agent 内部对象。
- `ManagedAgentTool` 接受必需的 `task` 与可选/nullable `additional_args` JSON 对象，将附加值传入子 Agent 的 `AgentInput`，并应用 Python 默认 task/report 包装模板。Java `includeRunSummary` 使用结构化摘要而非 Python `<summary_of_work>` 文本；系统提示的 Jinja agent-list/custom-instruction 条件仍未实现。
- ToolCallingAgent 按 Python 兼容规则处理一轮中的一个或多个 tool call；调用并行执行时，需保证异常和取消可追踪，写入记忆的 observation 顺序遵循行为基线（当前参考为按 call ID 稳定排序）。

内置 `final_answer` 是正常的终止动作，不是普通工具异常。它产生 `FinalAnswerStep`，保留文本/JSON/多模态答案值，并结束当前运行。

## 6. Agent 输入、步骤记忆和结果

### 6.1 输入与运行选项

```java
record AgentInput(String task, Map<String, JsonNode> additionalArguments) {}

record RunOptions(
    boolean reset,
    Integer maxSteps,
    boolean returnFullResult,
    Duration timeout,
    CancellationToken cancellation
) {}
```

- `task` 不能为空白。JSON additional arguments 会以 Python 字面量表示（键按字典序稳定排序）附加到任务上下文，并在 CodeAgent 路径注入 Python 命名空间；Python 变量名由 executor 校验。它们不是工具参数，不通过规划模板变量替换，也不自动授予额外 import 权限。
- `reset` 默认 `true`；`maxSteps == null` 表示使用 Agent 配置。小于 1 的步数限制在入口拒绝。
- `run()` 返回最终答案，`runFull()` 返回完整 `RunResult`。`RunOptions.returnFullResult` 当前为兼容数据字段，runtime 不据此切换返回类型；调用方应使用 `runFull()` 获取完整结果。
- 超时和取消作为运行控制信号，需产生明确的终止状态/异常及最后已提交步骤，不能悄悄当作最终答案成功。

### 6.2 步骤模型

```java
sealed interface MemoryStep permits TaskStep, PlanningStep, ActionStep, FinalAnswerStep {}

record TaskStep(int stepNumber, Instant startedAt, String task) implements MemoryStep {}
record PlanningStep(int stepNumber, Instant startedAt, String plan, TokenUsage tokenUsage,
                    List<ChatMessage> modelInputMessages, ChatMessage modelOutputMessage) implements MemoryStep {}
record ActionStep(int stepNumber, Instant startedAt, Duration duration, ChatMessage modelOutput,
                  List<ToolObservation> observations, TokenUsage tokenUsage, String error) implements MemoryStep {}
record FinalAnswerStep(int stepNumber, Instant startedAt, Duration duration, AgentValue answer,
                       TokenUsage tokenUsage) implements MemoryStep {}
```

步骤按追加顺序保存；Task、Planning、Action、FinalAnswer 都是可观察记忆。Action 必须保留模型输出和每个工具 observation 的关联；Java `ActionStep` 还记录可重试的错误文本。系统提示词存于 `AgentMemory.systemPrompt`，当前未实现统一 `PromptTemplates` 类。

`AgentMemory` 提供只读快照、追加、清空和模型上下文转换。模型上下文映射依据步骤类型生成 Java/provider 兼容的 assistant/tool/user 消息；摘要模式与完整模式必须来自同一组步骤，截断摘要不能改写原始步骤。当前 `toMessages(true)` 保留 system message，`AgentRuntime` 在组合 planning update 历史时移除它；Python `write_memory_to_messages(summary_mode=True)` 则由各 step 直接省略 system prompt。Python 的 `TOOL_CALL`/`TOOL_RESPONSE` 内部角色在 Java 中映射为 assistant toolCalls 与 provider tool observations；工具失败已由带 call ID 的 observation 表达时不会再追加重复的 user error。协议错误、CodeAgent 执行错误及 final-answer-check 错误仍使用独立 user error 消息，因此 message-role/text mapping 仍不完全相同。

### 6.3 运行结果和状态

```java
record RunResult(
    UUID runId,
    AgentValue output,
    RunStatus status,
    List<MemoryStep> steps,
    TokenUsage tokenUsage,
    Instant startedAt,
    Duration duration
) {}
```

`RunStatus` 第一版至少包含 `SUCCESS`、`MAX_STEPS_REACHED`、`CANCELLED`、`TIMED_OUT`、`FAILED`。模型/工具/执行错误默认抛出类型化异常，并可携带部分结果；不以 `FAILED` + 看似正常的 output 混淆调用方。达到 max steps 的停止文案和是否抛错应逐项遵从 AGT 行为基线，而不是只凭状态名推断。

Token 用量分别记录 prompt、completion 和总量；Provider 未提供时使用显式 unknown 状态，不将缺失值伪造为真实的 0。聚合统计只有已知值才参与求和。

## 7. Agent Runtime、策略与状态生命周期

```java
interface ActionStrategy {
    ActionOutcome perform(AgentTurn turn, RunContext context);
}

interface Agent {
    AgentValue run(AgentInput input, RunOptions options);
    RunResult runFull(AgentInput input, RunOptions options);
    Flow.Publisher<AgentEvent> stream(AgentInput input, RunOptions options);
}
```

`AgentRuntime` 实现公共循环：

1. 按 reset 选项处理既有记忆，创建 run ID 与 `RunContext`，追加 TaskStep。
2. 按配置注入 PlanningStep；将系统提示、任务和步骤转换为 Model 请求。
3. 调用 Model，由 `ActionStrategy` 解释动作，调用 CodeExecutor 或 Tool。
4. 追加完整 ActionStep/FinalAnswerStep、汇总 usage、发布事件。
5. 检查 final answer、取消/超时及 max steps，并产出结果或明确异常。

Agent 可选配置正整数 `planningInterval`：第 1 个动作前生成初始计划，之后按 interval 更新。初始规划使用 user 消息；后续更新使用专用 system 提示、summary-mode 历史（移除旧 system/计划正文）及 user 指令，并按当前执行点计算剩余动作数。规划请求使用 `<end_plan>` stop sequence；计划作为 `PlanningStep` 追加原始模型输入、原始模型输出和格式化计划，并在后续动作上下文中出现。CodeAgent/ToolCallingAgent 的 Python YAML 默认初始/更新提示在有/无 managed agents 的固定工具样例中已有完整哈希对照，规划中的 managed-agent list 使用固定安全格式渲染。Python 源 YAML 的单花括号 `{remaining_steps}` 是 Jinja 不会替换的字面文本；Java 保留可用的实时步数并将这处差异明确归一化。`PlanningPromptTemplates` 仍只支持有限白名单变量，system-prompt custom-instruction 条件块、managed-agent task/report wrapping 和更广泛的 Python input metadata/default 规则尚未迁移。

`RunContext` 只持有单次运行的不可变配置和可取消运行态：run ID、开始时间、`RunOptions`、当前步骤计数、取消信号和受控事件发布器。它不是 Agent 全局 state 的替代品。

Agent 会话状态分三层：

| 状态 | 生命周期 | reset 行为 |
| --- | --- | --- |
| `AgentMemory` | Agent 会话/跨多次 run | `reset=true` 清除；`false` 续接 |
| Agent `state` | Agent 会话，给代码执行或调用参数使用 | 保留，与 Python `self.state` 一致 |
| CodeExecutor 环境 | Executor 会话，跨 Action 执行 | 不由普通记忆 reset 隐式清除；独立显式 reset/close |

同一个 Agent 会话仅允许一个活动 run；运行时拒绝重入，禁止让记忆/state 互相污染。Web 层已经实现受限多会话 API：factory 必须为每个会话创建独立 Agent，最多 32 个会话；单 Agent 构造器保持兼容且不支持新建会话。可选 JSON session directory 原子写入历史和 `MemoryBackedAgent` 记忆，但不加密且由部署方保护目录；`CodeAgent` 的本机 CPython worker/命名空间 state 不能由当前 snapshot 恢复。后续仍可抽取不可变 Agent 配置与轻量 `AgentSession`，避免每个会话重复装配共享模型/工具，并统一 memory/state/executor 的所有权契约。

## 8. CodeExecutor SPI

```java
interface CodeExecutor extends AutoCloseable {
    ExecutorResult execute(ExecutorRequest request);
    void reset();
    @Override void close();
}

record ExecutorRequest(
    String code,
    Map<String, AgentValue> variables,
    Map<String, Tool> tools,
    Duration timeout,
    CancellationToken cancellation,
    UUID runId
) {}

record ExecutorResult(
    String stdout,
    AgentValue value,
    Map<String, AgentValue> updatedVariables,
    ExecutorFailure failure,
    boolean finalAnswer
) {}
```

- Executor 必须定义变量更新、stdout、返回值、异常和超时的语义；失败通过 `ExecutorFailure` 或类型化 `ExecutorException` 明确表达。
- Python 代码仍由 Python runtime 执行。Java 版不以 JShell、`ScriptEngine` 或自制 `eval` 冒充 Python 兼容执行器。
- `finalAnswer` 表示 Python `final_answer(value)` 控制流已终止当前动作；该标记与普通表达式返回值分离，保留数字、JSON 等原始结果类型。
- SPI 只定义调用契约；本地 Python 进程、容器和远端执行器分别由适配器实现。进程隔离、允许导入、文件/网络访问控制是独立安全边界，在 executor 详细设计中制定。
- Agent state 同步到执行环境时，变量名冲突和不可序列化值应报错，不能静默覆盖。

首个本地实现 `PythonProcessCodeExecutor` 使用持久 CPython 子进程和 JSON Lines 协议，可跨动作保留命名空间，并通过 Java 回调桥接工具调用。工具回调复用请求的 `runId` 和取消令牌；新动作会更新可用工具集合，避免旧工具代理残留。默认单次执行时限为 30 秒，可由请求覆盖。它不是安全沙箱：模型生成的代码仍以当前操作系统用户权限运行；执行不可信代码前必须改用隔离容器或远程执行器。

## 9. 事件与流式 API

`AgentEvent` 使用封闭事件类型，至少包含 `RunStarted`、`StepStarted`、`ModelDelta`、`ToolStarted`、`ToolCompleted`、`StepCompleted`、`RunCompleted`、`RunFailed`。事件包含 `runId` 和必要的 step/call ID；不携带秘密参数或完整凭据。

普通 `runFull()` 与 `stream()` 共用同一 runtime 状态机，避免维护两份行为逻辑。stream 消费者取消订阅后触发取消 token，并停止后续模型/工具调用；运行结束只发一个 terminal event。流事件属于观察接口，不能替代最终 `RunResult` 或使步骤记忆缺失。

## 10. 异常层次与错误传播

| 异常 | 来源 | 调用方可采取的动作 |
| --- | --- | --- |
| `ConfigurationException` | 缺模型能力、重复工具名、非法提示模板/配置 | 修复 Agent 配置后重试 |
| `ModelException` | Provider 连接、拒绝、超时或协议失败 | 根据子类判断重试；保留 cause |
| `ModelOutputException` | malformed tool call、非法 JSON、不可映射响应 | 调整模型/提示词或报告协议错误 |
| `ToolValidationException` | 工具参数缺失、类型/schema 校验失败 | 让模型修正参数或修正工具定义 |
| `ToolExecutionException` | 工具业务逻辑执行失败 | 根据工具幂等性决定是否重试 |
| `ExecutorException` | Python 解析/运行、进程、沙箱或超时错误 | 检查代码及执行器环境 |
| `AgentRunException` | runtime 控制/运行阶段失败，可附带 `RunResult` | 展示失败位置并按策略恢复 |
| `ConcurrentRunException` | 同一有状态会话被并发使用 | 串行化请求或建立独立会话 |

异常不能丢失原始 cause。只捕获可恢复且明确的错误；不得用宽泛 catch 返回空内容、忽略工具失败或继续生成貌似成功的最终答案。日志记录异常类型、run/step/call ID，不记录密钥、Authorization header 或未经脱敏的敏感参数。

## 11. 与 Python 参考实现的对应和兼容验证

| Java 契约 | Python 参考 | 核心验证 |
| --- | --- | --- |
| `AgentRuntime` + `ActionStrategy` | `MultiStepAgent`、`CodeAgent`、`ToolCallingAgent` | AGT fake agent、step 计数、final answer、reset/续跑 |
| `ChatMessage` / `ToolCall` | `ChatMessage`、`ToolCall`、`ChatMessageToolCall` | 角色映射、arguments JSON、工具调用 ID 往返 |
| `ToolSpec` / `ToolRegistry` | `Tool`、`ToolCollection`、`tool_validation.py` | schema 必填/default/nullable 与调用错误 |
| `MemoryStep` / `AgentMemory` | `TaskStep`、`PlanningStep`、`ActionStep` 等 | 完整/摘要步骤、消息映射、计划注入 |
| `CodeExecutor` | `CodeExecutor`、`LocalPythonExecutor`、远端 executor | 跨步函数/变量、stdout、final_answer 和失败传播 |
| `RunResult` / `AgentEvent` | `RunResult`、step callbacks、monitoring/stream | full result、usage 缺失、terminal 状态及取消 |

Java Core 的确定性单测使用 FakeModel、FakeTool、FakeCodeExecutor 和固定事件序列；任何真实模型请求都不进入普通 Maven test。Ollama live 集成测试按照 `02-behavior-baseline.md` 的显式开关运行，实际验证 `gemma4:26b` tool calling 能力后再更新兼容标签。

## 12. 实施顺序与暂定项

Core 实现依次落地：JSON/消息与 token 类型 → ToolSpec/校验/registry → steps/AgentMemory → AgentInput/RunOptions/RunResult/异常 → Model 与 CodeExecutor SPI → Fake 驱动的 ToolCallingAgent 最小闭环 → CodeAgent 执行流程及 stream。

以下剩余项需按根目录 `README.md` 中的待办逐项验收：

- 当前依赖已固定为 Jackson `2.22.3` 和 networknt JSON Schema validator `1.5.9`；发布前仍需核实并汇总依赖许可。
- `RunOptions.returnFullResult` 是保留字段但 runtime 不读取；调用方通过 `runFull()` 获取完整结果，需决定字段的弃用/兼容策略。
- system-prompt custom-instruction Jinja 条件、managed-agent task/report wrapping、更多工具 input metadata/default 及 Python type-hint 规则仍需行为对照；planning default prompt 完整文本已对有/无 managed agents 的固定工具样例建立 Python SHA-256 回归。
- Docker daemon 实际隔离、E2B/Modal/Blaxel 原生创建与认证、Whisper checkpoint live test 均未通过真实服务/环境验收。
- Java `AgentHttpServer` 会话快照只包含 `MemoryBackedAgent` 的 AgentMemory 和事件历史；CodeAgent 的 CPython worker namespace/state 不会持久化。

## 13. 本地构建

项目要求 JDK 21 或更高版本；Maven 由仓库中的 Maven Wrapper 自动下载，无需预装 Maven。在 Windows PowerShell 中运行：

```powershell
.\mvnw.cmd test
```

若 JDK 没有配置在 `PATH`，先设置 `JAVA_HOME` 并将其 `bin` 加入 `PATH`。真实 Ollama 请求不属于此命令执行的普通单元测试。
