# Java/Python 兼容矩阵与剩余路线

## 1. 当前验证基线

- 本地 Java 工程：`D:\code\smolagents-java`，Java 21，Maven。
- Python 行为参考：`D:\code\smolagents\src\smolagents` 与该仓库 `tests/`。
- 最新完整确定性检查：`.\mvnw.cmd test javadoc:javadoc`，212 项，0 失败、0 错误，7 项 opt-in live tests 默认跳过；Javadoc 成功。
- 本地 Ollama live 检查：`SMOLAGENTS_OLLAMA_TESTS=1`，`gemma4:26b`，CPU (`num_gpu=0`)、`think=false`；smoke、原生工具调用和 ToolCallingAgent 闭环 3/3 通过。
- 本机 Chrome/CDP live 检查：`SMOLAGENTS_BROWSER_TESTS=1`；浏览器页面导航/点击/截图与 Agent Web UI 新会话/任务/SSE 结果两个 smoke 均通过。
- Docker/Podman/WSL daemon 当前不可用，Docker 容器实机隔离与 Ollama CodeAgent live 测试受阻；不得把本地 CPython 子进程描述成安全沙箱。

## 2. 能力矩阵

| ID | 能力范围 | Java 状态 | 当前实现/证据 | 剩余差异 |
| --- | --- | --- | --- | --- |
| CAP-01 | Agent 执行循环 | 部分兼容 | ToolCallingAgent、CodeAgent、Agent-specific Python planning defaults、code/tool-calling tool prompt renderers、规划 initial/update 消息分型与 managed-agent list rendering、summary memory、`<end_plan>` stop、原始规划消息持久化、有限白名单变量的自定义规划模板、Python-style additional argument literal、订阅取消向上游传播、managed-agent task/nullable `additional_args` JSON 透传、Python 默认 task/report wrapper、final-answer check、回调、delta stream、memory save/restore；测试包含 Python injected-memory 4/6-message shape、工具 stub 格式和有/无 managed agents 的默认 planning YAML 完整文本 hash 对照 | Java additional argument map 按键排序而 Python dict 保留插入顺序；`includeRunSummary` 的结构化摘要与 Python `provide_run_summary` `<summary_of_work>` 文本不同；系统提示 custom-instruction Jinja 条件块未完整移植；Python YAML 的 `{remaining_steps}` 因单花括号不会替换，Java 有意插入动态剩余预算；工具输入 metadata/default coercion 仍不完整，旧 `ToolSpec` 构造器输出类型默认值只是兼容 fallback；CLI stream 使用另行测试；完整 Python/Java 行为逐项对照待做 |
| CAP-02 | Model 和消息 | 部分兼容 | Ollama native、OpenAI-compatible、Hugging Face Router；sync transient retry、role conversion、request-level stop sequences、tool-call/SSE；取消会关闭活动 SSE/NDJSON HTTP stream；image/audio input 按 capability gate | HF 云端未实测；更多 Provider、structured-output Provider matrix、stream retry 和跨 Provider edge cases 未完 |
| CAP-03 | Tool schema/调用 | 部分兼容 | JSON Schema validator、ToolRegistry、工具结果及 `ToolDefinition`/`ToolArgument` Java 方法适配；`ToolSpec` 保存 outputType/可选 outputSchema，Java method return types 和 MCP outputSchema 有映射；nullable、extra fields、integer-as-number 和显式 `@ToolArgument` JSON defaults 有确定性验证 | Python 从函数签名默认值推导 metadata/coercion、nullable 输入 metadata、scalar 单参数调用与完整 type-hint schema 规则尚有差异；未显式提供 outputType 的旧构造器使用兼容默认值 |
| CAP-04 | 默认工具 | 部分兼容 | final_answer、user_input、python_interpreter、搜索、网页、Wikipedia；transcriber 支持 OpenAI-compatible API、本地 Whisper CLI 和持久 Transformers Whisper worker；CLI 可选 transformers backend | Worker 启动/协议失败有确定性 fixture；已增加 opt-in 真实模型测试但尚未运行；模型/依赖需用户预装或显式允许下载 |
| CAP-05 | Python CodeExecutor | 部分兼容/安全验收阻塞 | 持久 CPython worker、JSONL IPC、变量与工具桥接、超时/取消；可选 Docker command policy tests | 不是 Python AST 子集 interpreter；Docker daemon 未验收；不可信代码不能用本地 executor |
| CAP-06 | 远程执行 | 部分兼容 | `executor.remote` 提供 Jupyter Kernel Gateway HTTP/WebSocket 执行、变量/工具桥接、超时/响应上限、内核重置与沙箱清理 hook；loopback/provider stub tests | 云服务 SDK/凭据未接入；必须由调用方提供 provider sandbox hook 和 Jupyter Gateway；E2B 原生 Code Interpreter、Modal/Blaxel 沙箱创建配置与实机行为未实现/未验收 |
| CAP-07 | Agent memory | 部分兼容 | Task/Planning/Action/FinalAnswer、安全版本化 JSON、保留 planning 原始输入/输出、文件存储及同提示词 session resume；Java full/summary 消息映射有确定性测试；已避免工具失败 observation 与 ActionStep.error 重复追加 retry 文本 | Python summary mode 会省略 system prompt 和 planning details；Java helper 先保留 system message，由 AgentRuntime 在 planning 路径移除；Python `TOOL_CALL`/`TOOL_RESPONSE` 内部角色映射到 Java provider-level assistant/tool/user；CodeAgent/protocol/final-answer-check 错误上下文仍存在角色/文本差异；旧数据导入和 schema migration 未完成 |
| CAP-08 | 日志/遥测 | 部分兼容 | AgentLogger level/filter/terminal sanitization；AgentMonitor event subscriber 汇总时间、状态与 token | Rich 结构化可视化、长期 metrics backend/telemetry exporter 未实现 |
| CAP-09 | 多模态 | 部分兼容 | Image/Audio/JSON ContentPart；OpenAI-compatible 内嵌 audio 与 image，Ollama 显式 vision image bytes，Gradio Space 同源图片/音频输出有界下载；本地 Transformers Whisper worker 和 opt-in speech-sample test；安全 memory codec | 本机缺 `accelerate`/`soundfile`/cached checkpoint，Whisper live test 未运行；音频生成、完整 Python 媒体包装与 Provider matrix 未实现 |
| CAP-10 | 安全序列化 | 部分兼容 | `safe:` allowlist serializer；拒绝 pickle/旧前缀、未知 POJO、循环；record 必须显式 opt-in；AgentMemoryCodec | NumPy/PIL full parity、旧格式 migration、跨 Python/Java fixtures 未完成 |
| CAP-11 | MCP | 部分兼容 | stdio、Streamable HTTP、legacy SSE；tools/prompts/resources、outputSchema；tool invocation/cleanup tests | subscriptions/listChanged、sampling/elicitation、协议边界完整覆盖与远端并发策略未完成 |
| CAP-12 | Hub/工具集合 | 部分兼容 | ToolCollection 管理 MCP resources；HuggingFaceHubClient 支持 Space metadata、分页文件清单/受限下载、collections 筛选分页/详情及显式源码审阅目录保存；GradioSpaceTool 支持 schema discovery、HTTPS endpoint 调用及白名单本地文件上传 | Gradio 外部/非标准 binary outputs、Hub 审阅/批准 UI 和显式可信执行流程未实现 |
| CAP-13 | CLI | 部分兼容 | Maven exec 入口；Ollama/HF/OpenAI-compatible、Agent 类型、tools/imports、stream、超时、退出码；支持显式 `--vision`、`--chrome-path`、`visual_browser` 和 Transformers Whisper backend | Python CLI 全部 Provider、交互引导、Space/Hub 选择等参数未迁移 |
| CAP-14 | 视觉网页浏览器 | 部分兼容 | Chrome DevTools Protocol 工具支持导航、点击、输入、键盘、滚动、返回、搜索、可见文本与 PNG 截图；CLI 可手动装配视觉模型与 Chrome；CDP 消息串行化、load 事件等待和本机 Chrome loopback smoke 均有验证 | 未提供 Python 等价的专用视觉 Agent；Chromium 仍以当前 OS 用户运行，不是安全沙箱；更完整弹窗/页面动作行为待对照 |
| CAP-15 | Gradio/Web UI | 部分兼容 | loopback-only、可选 bearer auth 的同源 UI 支持选择/创建/删除独立 Agent 会话、运行 SSE、取消、每会话历史和即时 image/audio 展示；最多 32 个会话，每会话历史 512 条/4 MiB，历史媒体只保留元数据；可选目录持久化历史及 `MemoryBackedAgent` memory；Origin/Fetch Metadata 检查、CSP、媒体 MIME allowlist；Chrome UI smoke tested | 不是 Gradio；持久化文件未加密，`CodeAgent` executor namespace 不随 memory 快照恢复；仅一个全局 bearer token，无用户/租户隔离；没有多模态上传和可配置模型/工具 API；单 Agent 构造入口不支持新建会话 |
| CAP-16 | 通用工具 | 部分兼容 | AgentUtils: safe JSON blob、code-block、truncate、identifier、console sanitization；CodeBlockTags 由 Agent 使用 | Python AST parsing、import/source introspection、prompt-template YAML、app template 等未迁移 |
| CAP-17 | 可选依赖/模块化 | 部分兼容 | Core 使用 Java 21/Jackson/JUnit；HTTP/HTML 复用 JDK/Jsoup；无远端 provider 必需 SDK | 当前为单一 Maven module；optional feature profiles/artifact split 与最小 classpath 测试未完成 |

状态仅表示已完成的实现和测试范围，不等同于对 Python 全部行为的兼容承诺。`部分兼容` 必须结合矩阵中的剩余差异解读。

### CAP-06 远程执行边界

- Java 端使用 JDK `HttpClient` 对 `POST /api/kernels`、`DELETE /api/kernels/{id}`（超时后尽力 `POST .../interrupt`），并通过 JDK WebSocket 发送 Jupyter `execute_request`、读取关联输出/结果/idle。`E2BExecutor`、`ModalExecutor`、`BlaxelExecutor` 均需要显式的 `*SessionProvider`；其 `openSandbox`/`closeSandbox` 负责云端 sandbox、Jupyter Gateway、认证头和账单资源的真实生命周期。没有内置 SDK、凭据发现或云端创建实现。
- Modal/Blaxel 的 Python 参考实现启动 Kernel Gateway 后建内核；Java 仅兼容该 Jupyter 协议路径。E2B Python 参考实现使用 `Sandbox.run_code()`，与 Jupyter 不同；Java 的 E2B hook 仅支持调用方自行提供的 Jupyter Gateway 沙箱，**不支持原生 E2B Code Interpreter API/SDK**。
- 输入变量、最终值、变量快照及工具桥接只传 JSON 安全值；常规 Python `input()`、图片/富媒体 `display_data`、包自动安装和 pickle 不支持。不会在本地通过 shell 执行代码或安装包。执行超时/响应体/消息/输出均有界；实际远端隔离、认证和 provider 清理正确性仍取决于 hook 与服务配置，必须在各云服务上另行验收。

## 3. 按顺序继续实施

1. 完成核心行为对照：补齐 Python fixtures；重点检查 planning prompts、stream cancellation/error、retry semantics、memory serialization/resume，并修订 CAP-01/02/07/08/16。
2. 完成执行安全验收：在 Docker daemon 可用环境验证 container lifecycle、资源与文件/网络边界后才跑 Ollama CodeAgent；没有 daemon 时保持 blocked，不用本地权限执行替代。
3. 完成 Hub 安全流程：已实现 Space metadata/同源分页文件清单和限量内存下载，以及 collections 筛选分页/详情读取；下载默认只返回内存内容，显式审阅保存 API 仅写入用户指定且不覆盖的目录，绝不执行。Gradio 本地文件上传需显式根目录授权、尺寸限制并经 HTTPS 传输；仍需实现审阅/批准 UI。远端 Python source 执行必须显式信任且单独测试。
4. 完成多模态及工具集成：真实 Transformers Whisper runtime 验收、Gradio 外部/非标准 binary outputs、图像/音频 provider matrix。
5. 完成可选平台能力：E2B 原生 Code Interpreter、Modal/Blaxel SDK lifecycle、视觉 Agent 示例/自动装配、Web/Gradio 用户隔离/模型配置/多模态上传与 telemetry；逐项明确 optional dependency 和 failure contract。
6. 完成发行前验收：完整 CAP-01..17 Python prompt/behavior 对照、可复现 fixtures、示例、release checklist，并评估是否拆分 Maven modules。

每个能力 slice 需先有确定性 loopback/MockModel tests，再运行完整 Maven/Javadoc；真实 Provider 或 daemon tests 必须显式 opt-in 并记录环境和不支持边界。
