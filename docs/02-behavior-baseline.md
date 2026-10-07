# smolagents Java 重实现行为基线

> 状态：持续验收中；本文定义范围、参考测试和验收方法，Java 功能按下方状态逐项完成。  
> 前置文档：[00 总体技术方案](00-java-reimplementation-plan.md)、[01 总体架构与模块划分](01-architecture-and-modules.md)  
> Python 参考版本：`smolagents 1.27.0.dev0`（`src/smolagents/__init__.py`）

## 1. 目标和使用方式

本文件将 Python 版的可观察能力变成 Java 重实现的验收基线。基线按两种粒度组织：

- **全功能盘点**：覆盖 `src/smolagents` 的每个功能域，登记来源、现有测试和后续状态。
- **核心闭环详写**：对 Agent 运行、模型消息、工具调用、记忆、代码执行和最终答案定义可测试行为。

Python 测试是现有行为的重要依据，但不是所有 API 的完整规范。遇到源码与测试歧义时，先检查源码/文档/调用示例，再记录最终选择和证据，不能把偶然的测试实现细节当成产品承诺。

### 1.1 基线标记

| 优先级 | 意义 |
| --- | --- |
| P0 | 完成最小可用 Agent 闭环前必须通过。 |
| P1 | 完成核心闭环之后、宣称对应功能兼容之前必须通过。 |
| P2 | 较重或平台专用集成；功能清单必须保留，按用户确认的发布范围实现。 |

| 状态 | 意义 |
| --- | --- |
| `Reference` | Python 源码/测试已确认该行为，是当前参照。 |
| `Pending` | Java 侧尚未实现或尚未完成比较。 |
| `Compatible` | Java 侧通过对应确定性契约测试及适用的集成验收。 |
| `Partial` | 已实现但有记录在案的行为差异。 |
| `Unsupported` | 明确不实现；须标出限制，不能宣称全量兼容。 |

Java 行为状态应以第 7 节为准；未开始或未完成比较的能力保持 `Pending`。每次更新兼容状态时，应同时记录测试命令、Java commit、源版本、环境以及差异，而不是只改状态标签。

## 2. 功能域总清单

该清单以公开导出、源码目录、提示词、依赖 extras 和 `tests/` 作为盘点入口；括号中是当前 Python 测试的主要参考位置。

| ID | 功能域 / Python 来源 | 可观察能力和基线内容 | Python 测试参考 | 优先级 |
| --- | --- | --- | --- | --- |
| CAP-01 | Agent 编排：`agents.py` | `MultiStepAgent`、`CodeAgent`、`ToolCallingAgent`；多步运行、规划、reset/续跑、最终答案检查、managed agents、流式、完整结果及异常。 | `tests/test_agents.py`、`tests/test_final_answer.py` | P0 |
| CAP-02 | 模型和消息：`models.py` | Model 请求参数优先级、角色和消息转换、tool-call JSON、结构化输出、重试、流式 delta 聚合、Provider capability。 | `tests/test_models.py` | P0 |
| CAP-03 | 工具契约：`tools.py`、`tool_validation.py` | Tool schema/元数据、必需参数、默认值/nullable、参数错误、命名、注册集合、方法/函数适配。 | `tests/test_tools.py`、`tests/test_tool_validation.py`、`tests/test_function_type_hints_utils.py` | P0 |
| CAP-04 | 默认工具：`default_tools.py` | `final_answer`、用户输入、Python interpreter、搜索、网页访问、Wikipedia、speech-to-text 等按依赖启用。 | `tests/test_default_tools.py`、`tests/test_final_answer.py`、`tests/test_search.py` | P0（final answer/interpreter）；其他 P1/P2 |
| CAP-05 | Python 代码执行：`local_python_executor.py` | Python 表达式/语句子集、跨步 state、导入授权、工具变量、stdout、错误定位、final_answer、超时和安全限制。 | `tests/test_local_python_executor.py`、`tests/test_agents.py::TestCodeAgent` | P0 |
| CAP-06 | 远程执行：`remote_executors.py` | 远端 Python 执行生命周期、输出/错误映射、超时/断连及 Docker、E2B、Modal、Blaxel 后端。 | `tests/test_remote_executors.py`（unit 与 integration 分组） | P2 |
| CAP-07 | 运行记忆：`memory.py` | Task、Planning、Action、SystemPrompt、FinalAnswer 步骤；序列化和模型上下文转换；完整/摘要步骤；回调分发。 | `tests/test_memory.py`、`tests/test_agents.py` | P0 |
| CAP-08 | 日志/监控：`monitoring.py` | 日志级别、token 用量、时长、模型统计、运行日志及可关闭终端输出。 | `tests/test_monitoring.py`、`tests/test_agents.py` | P0（数据类型）；输出适配 P1 |
| CAP-09 | 多模态：`agent_types.py` | AgentImage/AgentAudio/文本类型包装；图片输入、工具生成图片、图像 observation 和结果类型处理。 | `tests/test_types.py`、`tests/test_agents.py`（image cases）、`tests/test_serialization.py` | P1 |
| CAP-10 | 安全序列化：`serialization.py` | 默认安全模式、允许类型、拒绝任意类/反序列化代码、兼容旧数据和显式 pickle 风险路径。 | `tests/test_serialization.py` | P1 |
| CAP-11 | MCP：`mcp_client.py` | 连接/断开生命周期、多个服务、工具发现/调用、结构化输出与错误映射。 | `tests/test_mcp_client.py` | P1 |
| CAP-12 | Hub/自定义代码保存加载：`agents.py`、`tools.py`、`utils.py` | Agent/Tool 的导入导出、版本化配置、代码保存/恢复、依赖与仓库交互。 | `tests/test_agents.py`（serialization/from-folder cases）、`tests/test_tools.py`、`tests/test_utils.py` | P1 |
| CAP-13 | CLI：`cli.py` | 参数解析、交互模式、model type/provider 装配、错误处理、进程退出码。 | `tests/test_cli.py` | P1 |
| CAP-14 | 视觉浏览器：`vision_web_browser.py` | 浏览器操作工具、截图/回退/弹窗处理、查询文本安全转义和视觉 Agent 入口。 | `tests/test_vision_web_browser.py`、`tests/test_cli.py` | P2 |
| CAP-15 | Gradio UI：`gradio_ui.py` | 运行事件转 UI 消息、消息提取、UI 端异常/状态展示。 | `tests/test_gradio_ui.py` | P2 |
| CAP-16 | 通用与文档：`utils.py`、`_function_type_hints_utils.py`、`prompts/*.yaml` | 名称/代码块/JSON 解析、截断、类型提示到 schema、提示词变量完整性、示例可运行性。 | `tests/test_utils.py`、`tests/test_function_type_hints_utils.py`、`tests/test_all_docs.py` | P1 |
| CAP-17 | 可选依赖与导入兼容 | 未安装全部 extras 时仍可 import core；启用某项集成时缺少对应依赖要给出明确错误。 | `tests/test_import.py`，对照 `pyproject.toml` extras | P1 |

### 2.1 可选集成盘点要求

每个 P1/P2 集成在进一步详细设计时至少补全：

1. 对外构造入口及必填配置。
2. 所需 extra/Java 依赖、平台/账号要求。
3. 成功、无凭据、依赖缺失、网络失败和服务端失败的预期错误。
4. 是否有真实服务集成测试；若有，默认是否跳过以及怎么显式启用。
5. 不支持能力的表达方式和文档兼容级别。

`smolagents` 的 Python `pyproject.toml` 将 OpenAI、LiteLLM、MCP、Docker、E2B、Modal、Blaxel、Gradio、telemetry、toolkit、transformers 等作为独立 extras。Java 不必复制 extras 名称，但须保留“不装某项集成即可使用 core”的用户体验。

## 3. P0 核心闭环行为映射

具体测试方法名是定位线索；Java 侧应以具有同等断言意图的测试验证，不要求一对一翻译 Python 测试代码。

| ID | 输入/条件 | 预期可观察结果 | Python 参考 |
| --- | --- | --- | --- |
| AGT-01 新任务 | Agent 接收任务，model 返回一个非最终动作和一个最终答案。 | 产生 TaskStep 与有序 Action/FinalAnswer 步骤；输出为最终答案；观察结果回到后续模型上下文。 | `tests/test_agents.py::TestAgent.test_fake_toolcalling_agent`、`test_fake_code_agent` |
| AGT-02 reset 与续跑 | 同一 Agent 先运行，再以 `reset=false` 运行，之后以 `reset=true` 运行。 | reset 清除旧运行步骤；续跑保留历史并追加新任务；输出仍来自本次运行终止结果。当前参考测试断言步骤总数分别为 3、5、3。 | `tests/test_agents.py::TestAgent.test_reset_conversations` |
| AGT-03 additional arguments | 输入包含额外变量/参数。 | 任务上下文以 Python dict repr 形式呈现附加 JSON 值；CodeAgent 执行环境可收到可访问变量，不能混入其他并发 run。Java 对键稳定排序，Python 保留 dict 插入顺序。 | `tests/test_agents.py::TestAgent.test_additional_args_added_to_task`、`TestCodeAgent.test_local_python_executor_with_custom_functions` |
| AGT-04 CodeAgent state | 代码在多个 action step 中定义并复用变量/函数。 | 同一 Agent 执行上下文内变量按约定持续；不同 run 的状态重置/保留由 reset 语义决定。 | `tests/test_agents.py::TestAgent.test_function_persistence_across_steps` |
| AGT-05 最大步数 | 模型持续给出非最终动作；覆盖 agent 默认 max 和 run 参数覆盖 max。 | 恰在配置步数后停止，写入 max-steps 错误/终止状态；不能无限调用模型。参考 `max_steps=5` 时为 Task + 5 Action + Final，覆盖值 3 时为 Task + 3 Action + Final。 | `tests/test_agents.py::TestAgent.test_fails_max_steps` |
| AGT-06 规划 | 设 planning interval，包含前置记忆或任务历史。 | 按间隔创建 PlanningStep；初始 user prompt、更新 system+summary-history+user 消息、剩余动作数、`<end_plan>` stop 与原始输入/输出记忆均有确定性测试。两类 Agent 的 Python YAML 完整默认 initial/update 文本以 SHA-256 fixture 对照；Python 源模板的单花括号 `{remaining_steps}` 不会被 Jinja 替换，Java 有意插入真实剩余步数。 | `tests/test_agents.py::TestAgent.test_planning_step_with_injected_memory` 及 planning message cases |
| AGT-07 最终答案校验 | 配置一个接受、一个拒绝或抛错的 final answer check。 | 校验获得 final answer、memory 和 agent；Java 的 `FinalAnswerCheck` 支持 CodeAgent 与 ToolCallingAgent，拒绝/异常保存在 `ActionStep.error` 并进入下一轮上下文；持续失败时遵循 max-steps fallback。 | `tests/test_agents.py::TestAgent.test_final_answer_checks`、`test_final_answer_checks_with_agent_access` |
| AGT-08 模型生成失败 | Model 在生成时抛出异常。 | 转换/记录为 AgentGenerationError，保留失败步骤和可诊断原因，不构造成功答案。 | `tests/test_agents.py::TestAgent.test_generation_errors_are_raised` |
| AGT-09 ToolCallingAgent | assistant 返回 function tool call；参数既可能为 JSON 字符串也可能已解析对象。 | 保留调用 ID/工具名，参数解析后做 schema 校验；将工具 observation 关联回正确调用；默认 final_answer 工具可用。 | `tests/test_agents.py::TestToolCallingAgent.test_toolcalling_agent_api`、`test_process_tool_calls` |
| AGT-10 多工具调用 | 一次模型响应包含多个工具调用，或流式分片逐段返回参数。 | 所有普通工具调用均被处理并生成 observation；分片按 index 聚合；最终答案工具的并行调用按源行为拒绝。 | `tests/test_agents.py::TestToolCallingAgent.test_toolcalling_agent_stream_logs_multiple_tool_calls_observations`、`test_toolcalling_agent_final_answer_cannot_be_called_with_parallel_tool_calls`；`tests/test_models.py::TestModel.test_agglomerate_stream_deltas` |
| AGT-11 CodeAgent final_answer | 代码块调用 `final_answer`，包括嵌套函数内调用。 | 识别专用终止信号；返回原始结构化值（例如浮点数），不把它当普通异常吞掉。 | `tests/test_agents.py::TestAgent.test_code_nontrivial_final_answer_works`、`tests/test_local_python_executor.py` final answer cases |
| AGT-12 工具错误 | 模型使用未知工具、错误参数或 Tool 执行抛错。 | 名称/参数/执行错误有不同可诊断结果；错误观察或 AgentToolCall/ExecutionError 遵循对应源行为，避免 success-shaped fallback。 | `tests/test_agents.py::TestToolCallingAgent.test_process_tool_calls` 及文件末尾 wrong-arguments/tool-raises cases |
| AGT-13 流式 | 调用 run stream，模型有内容增量、工具调用 delta 和终止答案。 | 事件按运行顺序发出；消费完事件后 memory 和非流式执行得到等价的结构化终态；取消行为另作明确契约。 | `tests/test_agents.py::TestToolCallingAgent.test_toolcalling_agent_stream_logs_multiple_tool_calls_observations`、`tests/test_models.py::test_streaming_tool_calls` |
| AGT-14 完整结果 | 请求 RunResult/full result；model 有或无 usage。 | 输出含 state、steps、token_usage、timing；缺任何所需用量数据时不可伪造完整 token 总数。 | `tests/test_agents.py::TestRunResult.test_full_result`、`test_no_token_usage` |
| AGT-15 Managed Agent | 管理 Agent 作为可调用能力，含 name/description、task、nullable `additional_args` 及 report 选项。 | managed agent 被呈现为动作工具；任务/附加 JSON 参数、Python 默认 task/report prompt wrapping、执行输出与 Java 可选运行摘要在父 Agent 流程中可追溯。Python run-summary 的文本结构及系统 prompt 中 managed-agent Jinja 区块另作兼容验收。 | `tests/test_agents.py::TestAgent.test_init_managed_agent`、`tests/test_agents.py::TestMultiAgents` |

## 4. 模型、工具、记忆与执行器测试基线

### 4.1 模型与消息（CAP-02）

确定性协议用例至少包括：

- `ChatMessage` 的 role/content/tool calls/token usage 序列化和反序列化。
- 按 model kwargs、调用 kwargs 和移除标记解析请求参数；无工具时不传 tool choice，有工具时遵循默认及显式选择。
- provider role 转换、flatten messages、多模态消息整理及停止序列适配。
- 流式文本按序拼接；同一 tool-call index 的名称、ID、JSON 参数增量合并；token usage 汇总。
- JSON tool arguments 的合法对象、非法字符串、缺字段和数字参数场景。
- Provider rate-limit retry 的次数/最终成功或最终错误；不要用真实 sleep 验证确定性逻辑。
- 不支持结构化输出或 stop 参数的模型返回明确 capability/配置行为。

参考：`tests/test_models.py::TestModel`、`TestLiteLLMModel`、`TestOpenAIModel`、`TestInferenceClientModel`、`TestTransformersModel` 和消息工具函数测试。

### 4.2 Tool schema 与执行（CAP-03/04）

- 注册的工具名称、说明、输入参数和输出类型进入 Agent 可见定义。
- required、optional/default、nullable、schema 类型、无效额外参数、缺字段和名称冲突各自可验证。
- 工具初始化/昂贵 setup 遵循定义的首次调用生命周期；同名工具的动态变化有明确注册更新语义。
- Tool 错误保留可诊断信息，同时避免把 secret、内网响应或完整敏感栈传给模型。
- `final_answer` 是终止能力，不应与普通工具观察混淆。
- 工具集合、MCP 工具和 Java 原生 Tool 接口共用同一调用/校验契约。

参考：`tests/test_tools.py::TestTool`、`TestToolDecorator`、`TestToolCollection`，`tests/test_tool_validation.py::test_validate_tool_attributes_*`、`test_validate_tool_arguments*`，`tests/test_final_answer.py::TestFinalAnswerTool`。

### 4.3 AgentMemory 和监控（CAP-07/08）

- 初始化含 system prompt，步骤初始为空；每种具体 step 有稳定的字段与 discriminator。
- `ActionStep.dict()` 需序列化消息、tool calls、错误、图片 observation、action output、TokenUsage 和 Timing；最终答案校验错误进入后续模型上下文；不应把不安全的原始 client 对象带入快照。
- `MemoryStep.to_messages()` 把 Task/Planning/Action/FinalAnswer 按模型可读格式映射；摘要模式与完整模式分别测试。
- 回调可按 step type 注册并按执行顺序触发；回调异常的隔离/失败策略应另定，且须可观察。
- `TokenUsage`、`Timing` 汇总边界清晰；不可用字段保持 null/unknown，不写成 0 来伪装已测得。

参考：`tests/test_memory.py::TestAgentMemory`、`TestMemoryStep`、各 step `to_messages/dict` tests；`tests/test_monitoring.py`。

### 4.4 Python 代码执行边界（CAP-05/06）

Java 当前通过持久 CPython 子进程提供基础执行能力，而不是 Python AST 子集解释器；默认 `PythonProcessCodeExecutor` 仍以当前 OS 用户权限运行，不是安全沙箱。另提供显式的 `DockerPythonCodeExecutor`：执行器固定使用无网络、只读根文件系统、非 root 用户、丢弃 Linux capabilities、no-new-privileges、有限 tmpfs、进程数、CPU、内存和执行超时；宿主机只读挂载唯一的 worker 脚本，不挂载项目或用户文件。Docker CLI、daemon 与可信 Python 镜像须由部署环境预先安装，执行器以 `--pull=never` 避免运行时下载。此容器路径尚未在 Docker 可用环境中做 live 验收；容器不能对抗宿主机内核或 daemon 漏洞，也不应直接用于高风险多租户场景。继续按以下可观察契约补测并登记差异：

- 支持范围按 Python 参考执行器实际行为登记，包括赋值、函数/类、循环、条件、集合/推导式、异常、上下文管理器、import、属性和下标操作。
- 多次动作之间代码 state 保留；Agent/执行器 reset 后状态行为可断言。
- `print` 输出、最终返回值、空输出、截断和执行错误有独立字段/观察行为。
- 超时、操作数限制、授权 import、危险 builtins/属性访问和 final_answer 控制流分别有正向及负向测试。
- 拒绝访问某危险能力的测试是参考行为，不等于安全证明；进程/容器资源隔离另测。
- 远程后端区分服务启动、连接、请求、执行和清理失败；不得把错误映射为空成功结果。

参考：`tests/test_local_python_executor.py::TestEvaluatePythonCode`、`TestTimeout`、`TestLocalPythonExecutor`、`TestLocalPythonExecutorSecurity`；`tests/test_remote_executors.py` 中各 executor unit/integration 分组。

## 5. Ollama `gemma4:26b` 本地模型验证

用户提供的本机目标是 Ollama 上已运行的 `gemma4:26b`。测试覆盖两类对象：

1. 先验证 Python 参考工程连接该模型的配置路径，确认它能否用于代码动作/工具调用。
2. 在 Java Model adapter 和 Agent 闭环实现后，使用同一模型与同等任务验证 Java 请求、消息转换、动作执行和最终结果。

不能用一个不确定性较高的模型输出替换 FakeModel：Mock 测试负责严格、可重复的协议验收，live test 负责真实服务互操作性。

### 5.1 配置约定

| 配置 | 建议 | 备注 |
| --- | --- | --- |
| Provider | Ollama 本地服务 | 不向云模型服务转发此用例的任务内容。 |
| Ollama 模型 tag | `gemma4:26b` | 以本机 `ollama list` 返回的精确 tag 为准。 |
| Python LiteLLM provider model ID | 优先验证 `ollama_chat/gemma4:26b` | 仓库中文/英文 guided tour 使用 `ollama_chat/<tag>`；用户配置显示名 `ollama/gemma4:26b` 需在装配时核实其实际映射。 |
| Base URL | 默认 `http://localhost:11434`，由 `OLLAMA_BASE_URL` 覆盖 | Java 使用 Ollama 原生 `/api/chat`；adapter 会兼容并剥离配置值末尾的 `/v1`。 |
| Enable flag | `SMOLAGENTS_OLLAMA_TESTS=1` | 未显式启用时 live test 应 skip，并说明启用命令。 |
| Model override | `OLLAMA_MODEL=gemma4:26b` | 配置最终传给 Python LiteLLM / Java adapter 的模型名时记录具体格式。 |
| Timeout | `OLLAMA_TEST_TIMEOUT_SECONDS` 可配置 | 26B 本机加载和推理可能较慢；避免短超时造成随机误报。 |
| 本机运行选项 | `OLLAMA_NUM_GPU=0`、`OLLAMA_THINK=false` | 本机 Ollama 0.33.3 的 OpenAI-compatible 路径启动 llama-server 时发生 CUDA 初始化崩溃；原生 API 通过 CPU 推理和关闭 thinking 已验证可用。 |
| API key | 本机默认不要求密钥；如 adapter 强制字段，仅提供本地占位值 | 不把真实密钥写入仓库、fixture、测试报告或日志。 |

仓库中的 Python Ollama 示例在 `docs/source/zh/guided_tour.md`：`LiteLLMModel`、`model_id="ollama_chat/llama3.2"`、`api_base="http://localhost:11434"`；`models.py` 也对以 `ollama` 开头的 LiteLLM model ID 设置消息 flatten 默认。以这些作为配置线索，但在连接时仍需核对用户当前 Ollama/adapter 版本。

### 5.2 双层测试矩阵

| ID | 层次 | 用例 | 断言方式 | 启用要求 |
| --- | --- | --- | --- | --- |
| MOCK-01 | Mock / 常规 CI | 用 FakeModel 生成预设 user/assistant/tool-call 消息，驱动单步和多步流程。 | 精确断言步骤类型/次序、Tool 调用 ID/参数、observation、reset、max steps 和最终答案。 | 默认运行；无服务、无密钥。 |
| MOCK-02 | Mock / 常规 CI | 模拟分块流式文本与工具参数。 | 精确断言 delta 聚合、调用顺序、token 用量和事件终态。 | 默认运行。 |
| OLL-01 | Ollama smoke | 确认本机服务可达、目标模型可选用，并生成一条短文本响应。 | 响应非空且与简单约束相符；检查 model/provider 元信息和错误分类。不比较完整自然语言快照。 | 显式 enable flag。 |
| OLL-02 | Ollama + CodeAgent | 提交简单可验证任务（例如整数乘法），让 CodeAgent 完成代码动作和 final answer。 | 断言运行含代码 action、执行器成功、最终数值正确；不要要求模型输出某一段逐字固定的 Python 代码。 | OLL-01 通过，Python executor 可用。 |
| OLL-03 | Ollama + ToolCallingAgent | 提供副作用可观察的简单 `add(a,b)` 工具，提示模型调用该工具并返回结果。 | 若 endpoint/model 宣告支持 tool calling，要求真实产生一次合法调用、参数 schema 通过、工具执行结果正确、Agent 收敛；否则报告明确的 capability 缺口，不能伪记为通过。 | 显式 enable；单独记录 tool-call capability。 |
| OLL-04 | Ollama 流式（后续） | 如果当前 adapter/model 支持 stream，验证 chunk 返回及结束。 | 只断言最终聚合文本/调用结构与非流式语义等价，不比较 chunk 边界和时序。 | 显式 enable；unsupported 时记录能力状态。 |

### 5.3 执行和报告策略

- CI 和普通单元测试不依赖 Ollama 正在运行，也不下载模型。
- 本机实测：Ollama `0.33.3`、`gemma4:26b`。默认 GPU/OpenAI-compatible 请求返回 HTTP 500（`CUDA error: shared object initialization failed`）；切换原生 `/api/chat` 并设 `num_gpu=0`、`think=false` 后，短文本 smoke 通过，且模型成功返回 `echo` 工具调用。GPU 路径仍未通过。
- Java live tests：`OLL-01` smoke、模型级工具调用及 `OLL-03` ToolCallingAgent + `add` 工具完整闭环均已通过；使用 Ollama 原生 `/api/chat`、CPU 推理（`num_gpu=0`）和 `think=false`。OpenAI-compatible/GPU 路径仍受本机 CUDA 初始化错误影响。`OLL-02` 尚未运行；基础 CodeAgent 和 Python 进程执行器已实现，但尚未用 Ollama 端到端验证。
- 执行 live test 时，先检查 enable flag、endpoint 和 `gemma4:26b` 是否可访问；未启用时 skip。已启用但 endpoint 不可达或 model 缺失时，应显式失败并说明故障，不得把它当成 capability skip。
- PowerShell 显式启用示例：设置 `SMOLAGENTS_OLLAMA_TESTS=1`、`OLLAMA_NUM_GPU=0`、`OLLAMA_THINK=false` 后运行 `.\mvnw.cmd test '-Dtest=OllamaModelIntegrationTest'`。
- ToolCallingAgent 只有在 provider/model 经验证能实际使用工具调用时，才把 OLL-03 标为 `Compatible`；若模型不能 function-call，记录为 `Unsupported` 或 `Partial`，但不能影响 MockModel 下的协议兼容。
- 对结果按语义/结构断言：是否调用了工具、参数是否合规、执行是否成功、最终结果是否正确。自然语言措辞、思维过程、token 总数、推理耗时和 chunk 分割都不做固定 golden 快照。
- 记录 Python 版本、smolagents commit/version、LiteLLM/adapter 版本、Ollama 版本、model tag、base URL（不含秘密）、测试项、耗时、成功/失败/unsupported 和脱敏错误类型。
- 测试 prompt 使用无敏感信息、低副作用输入；`add` 工具只做确定性本地计算。禁止通过 live 测试触发网页写入、账户操作或任意外部 side effect。
- 用户本机模型验证不等同于 Java 的 Provider/ToolCalling 行为已兼容；Java 实现完成后须对照同一用例再运行。

## 6. 兼容性比较方法

### 6.1 可做严格比较的内容

- Message role/content block 类型、tool call 的数量/name/ID/JSON 参数。
- Agent step discriminator、step 次序、终止状态和错误类别。
- 参数校验结果、工具副作用次数、代码执行结果、final answer 原始类型。
- 配置决定的 reset、max steps、planning interval、final answer check 和序列化 schema。
- 确定性 FakeModel 序列下的完整运行轨迹。

### 6.2 应归一化或作为诊断数据的内容

- wall-clock 时间、模型响应耗时、Provider request ID 和服务端原始对象。
- LLM 生成的自然语言前言/推理和 Python 代码的非语义格式差异。
- 不同硬件或后端返回的 token 计数；除非该字段被 capability 明确保证。
- Streaming delta 的边界；只比较合并后的内容/工具调用结构。
- 抛错中的临时目录、路径分隔符和第三方 SDK 的动态错误文本；比较类型/归类/必要上下文，不逐字比对 stack trace。

### 6.3 Golden fixture 规则

- 从确定性 Python FakeModel 测试中抽取无密钥、无私人数据的输入/响应和结构化步骤。
- 对字节/图片 fixture 注明编码、尺寸、色彩空间和来源，不把大文件 base64 塞进普通 JSON。
- fixture 带版本与用例 ID；升级 Python 参考源码时显式 review 差异，不能自动覆盖 golden。
- 实时 Ollama 响应只存脱敏测试摘要，不固化为文本 golden。

## 7. Baseline 验收清单

| 项目 | 完成条件 | 当前状态 |
| --- | --- | --- |
| Python 源模块与可选集成全量登记 | CAP-01 至 CAP-17 均有源码入口、测试参考、优先级。 | Reference inventory recorded; Java state is tracked per CAP in `04-compatibility-matrix.md` |
| P0 Agent 行为 | AGT-01 至 AGT-15 的适用用例映射至 Java 确定性测试。 | Partial；已覆盖工具错误重试与 `ToolFailed`、类型化步骤回调、managed-agent `additional_args` 透传、模型文本/工具调用 delta 转发、空流失败、流式步数兜底、订阅取消传播和上游模型流取消、planning injected-memory 消息形状，以及 Python 工具调用/code stub 格式断言；managed-agent/task-report Jinja prompt 与其余源码差异仍待核对 |
| Tool/Model/Memory/Executor 基线 | 第 4 节的结构、错误和边界用例落实为 Java tests。 | Partial；已增加 optional/nullable/additional-properties schema、工具执行异常包装、记忆 full/summary 映射、模型 schema 防御性复制、ToolSpec 输出元数据、integer-as-number 和显式 MethodToolAdapter JSON default 测试；Provider 重试、从 Python 函数签名推导默认值/输入 metadata、完整媒体和执行器安全边界仍需分项验收 |
| Ollama smoke | OLL-01 通过，并记录模型 tag 和 endpoint 配置。 | Passed；原生 API，CPU (`num_gpu=0`)，`think=false` |
| Ollama CodeAgent | OLL-02 通过，记录执行器和动作输出摘要。 | Pending；已实现默认本地 CPython 进程执行器及可选 Docker 隔离执行器，Docker daemon 未安装且 OLL-02 尚未实测；本地进程执行器不是安全沙箱 |
| Ollama tool calling | OLL-03 通过或记录明确 capability 差异。 | Passed；Gemma 调用 `add` 工具、结果观察及最终答案闭环通过；仅原生 API CPU 路径通过 |
| 常规测试可离线运行 | 禁用 live flag 后所有 P0 Fake/Mock 测试不访问网络/模型。 | Passed；当前完整套件 212 项，0 失败、0 错误，7 项 opt-in live tests 默认跳过（3 项 Ollama、1 项 Docker、2 项 Chrome、1 项真实 Whisper） |
| 兼容差异登记 | 每项未通过差异包含影响、决定、后续修复或 Unsupported 依据。 | Pending |

### 7.0 历史实现记录（截至 2026-09-28；当前状态以 7.1 和 `04-compatibility-matrix.md` 为准）

截至 2026-09-28，已完成 `03-core-domain-and-api.md` 中的主要领域类型、MockModel 驱动的 ToolCallingAgent 闭环、基础规划步骤，以及基于持久 CPython 子进程的基础 `CodeExecutor` / `CodeAgent` 闭环。代码执行器支持跨动作命名空间、最终表达式、`final_answer`、受控 import、Java 工具桥接、输出截断、超时/取消及 worker 重启；另新增可选 `DockerPythonCodeExecutor`，带无网络、只读根文件系统、非 root、资源限制和仅挂载 worker 脚本等容器参数，并在 worker 退出后尝试清理专属容器。当前主机未安装 Docker/Podman，也没有 WSL 发行版，因此 Docker 后端仅验证命令策略，尚未做 daemon 级运行验证；默认本地进程执行器仍不可用于不可信代码。已新增可注入输入/输出流的 `UserInputTool`、要求调用方显式提供 `CodeExecutor` 且隔离每次调用状态的 `PythonInterpreterTool`、Brave-compatible HTTP `ApiWebSearchTool`、支持 DuckDuckGo Lite/Bing RSS/Exa 的 `WebSearchTool`、支持摘要/全文及 WIKI/HTML 提取格式的 `WikipediaSearchTool`，以及支持常见 HTML 转 Markdown、截断和默认拒绝私有网络地址的 `VisitWebpageTool`；解释器工具已通过真实 CPython worker 集成测试，网络工具均通过本机 loopback HTTP stub 验证。新增的 MCP stdio、Streamable HTTP 与 legacy HTTP+SSE adapter 支持显式信任门、initialize/initialized 握手、多服务命名空间、工具发现/调用、prompt 和 resource metadata 发现/获取、schema 验证、取消和连接清理；`outputSchema` 通过防御性 getter 安全暴露，且 `ToolCollection` 管理连接生命周期。`GradioSpaceTool` 可基于显式参数 schema 或 `/gradio_api/info` 自动发现的 primitive/file schema 调用 Space POST/SSE；本地文件上传需显式根目录白名单、尺寸限制和 HTTPS endpoint，loopback HTTP 仅限测试开关，未授权路径与超限上传前拒绝；同源标准图片/音频输出以受限二进制读取。规划步骤现区分初始 user prompt 与后续 system+summary-history+user 更新；后续规划排除旧计划正文，剩余动作数精确传递，`<end_plan>` 作为 provider stop sequence，`PlanningStep` 持久保存模型原始输入/输出并兼容旧快照；Java 已支持三段纯文本规划模板及 `task`/`tools`/`remaining_steps` 白名单变量；Python YAML/Jinja 完整模板和 managed-agent 模板变量仍未迁移。CodeAgent 与 ToolCallingAgent 现支持 `FinalAnswerCheck`；校验拒绝或抛错会记录在动作记忆中并触发重试，步数耗尽时沿用现有兜底答复。ToolCallingAgent 还支持工具协议/执行错误的模型重试、步骤类型回调和 managed-agent 工具包装。Agent 流式运行会把模型文本与工具调用增量转发为 `ModelDelta`，并在订阅取消时轮询并取消上游模型订阅。核心测试现覆盖工具 schema 正负路径、执行错误包装、记忆 full/summary 映射、消息/生成 schema 防御性复制及未知 token 语义。此历史快照的全量测试为 177 项、0 失败、4 项 opt-in live tests 默认跳过；这些数字不代表当前验证基线。

2026-09-28 本轮重新启用本地 Ollama opt-in 集成测试，使用 `gemma4:26b`、CPU (`num_gpu=0`)、`think=false` 和 240 秒请求超时；smoke、原生工具调用与 ToolCallingAgent 闭环 3 项均通过，0 跳过。OLL-02 CodeAgent live test 仍未运行：没有已验证的沙箱后端，不能将本地 CPython 进程视作隔离环境。OpenAI-compatible/Ollama 同步生成请求现在共享有界 HTTP retry policy，对网络异常及 408/429/5xx 最多重试三次，保留其他 4xx 的即时错误行为；loopback contract tests 覆盖 transient 重试、不重试认证错误与重试次数上限。

新增的 `GoogleSearchTool` 兼容 SerpApi 与 Serper，可按年份筛选，并通过 loopback stub 覆盖请求编码、两种响应结构、无结果和 HTTP 错误。新增的 `DuckDuckGoSearchTool` 提供最大结果数与可选速率限制，通过 DuckDuckGo Lite endpoint 复用 HTML 解析器；与 Python `ddgs` 实现相比尚未迁移其 SDK 专属选项。新增的 `HuggingFaceInferenceModel` 通过 Hugging Face Router 的 OpenAI-compatible Chat Completions 协议适配工具调用/文本消息和 SSE；其序列化、provider-qualified model ID、token header 与响应映射均以 loopback stub 验证，尚未做真实云端调用。OpenAI-compatible 与 Ollama 原生 adapters 支持请求级 stop sequences，覆盖初始规划和更新规划的 `<end_plan>` 终止标记。新增的 `MCPClient` 实现 stdio、Streamable HTTP 与 legacy HTTP+SSE JSON-RPC initialize/tools/prompts/resources/call 生命周期，支持多服务工具名称隔离、tool outputSchema 元数据、prompt 参数发现/渲染、resource metadata/文本和多模态数据读取，且构造时要求显式信任确认；`ToolCollection.fromMcp` 托管服务器连接生命周期。新增 `GradioSpaceTool` 支持显式 API schema 及 `/gradio_api/info` primitive/file schema 发现、Gradio call/SSE 调用和根目录白名单下的受限 multipart 本地文件上传；读取 metadata 和上传过程均不执行 Space 代码。新增的 `HuggingFaceHubClient` 支持 Space metadata、带同源 Link 头分页的文件清单、限量内存下载、Hub collections 查询和安全源码审阅目录保存，测试验证鉴权、分页、revision/file 绑定、redirect origin 限制、大小上限、SHA-256 和拒绝覆盖，不执行远端 Python 文件。新增的 `SafeSerializer` 使用 `safe:` JSON 格式与 allowlist marker 支持基础容器、日期时间、bytes、Decimal、Path 及显式 opt-in Java record；反序列化不加载类或执行 Java 原生反序列化，并明确拒绝 pickle/旧式无前缀数据。完整验证更新为 Maven 测试 177 项、0 失败、4 项 live tests 默认跳过，Javadoc 生成成功。

新增 Java CLI (`org.fzb.Main`) 支持 Ollama/Hugging Face/OpenAI-compatible 模型、CodeAgent/ToolCallingAgent、工具选择、交互式 prompt、最大步数/超时、流式文本 delta、帮助和退出码；由 Maven exec 插件提供启动入口，可用 `.\mvnw.cmd exec:java "-Dexec.args=--help"` 查看选项。当选择代码执行模式或 Python interpreter 工具时会明确提示本地执行并非安全沙箱。定向 CLI 测试验证参数、帮助、配置错误、流式 CodeAgent 闭环与非零退出行为；模型行为用 loopback SSE stub 验证，不访问云端模型。MCP stdio、Streamable HTTP 和 legacy HTTP+SSE 三种传输均由 loopback fixture 验证，HTTP 覆盖 session/version header、JSON/SSE response 与 DELETE 关闭；MCP resource/prompt list/read 和 outputSchema 元数据也由 stdio fixture 覆盖。新增 `AgentMonitor` Reactive Streams subscriber，聚合动作时长、RunResult token 用量、总耗时和失败状态，不输出 prompt/工具参数；`AgentLogger` 提供可关闭的纯文本日志与级别控制，也可直接订阅 Agent stream 输出生命周期摘要，默认不记录模型 delta 正文。新增 `AgentMemoryCodec`，以带 schema 版本前缀的 allowlist JSON 持久化/恢复所有内置记忆步骤、消息、工具调用、多模态内容和 token 用量，不动态实例化类；支持同目录原子文件写入/加载，`CodeAgent`/`ToolCallingAgent` 可在空闲时恢复与系统提示词匹配的记忆快照。新增 `SpeechToTextTool`，将 base64 音频通过受限 multipart 请求发送到 OpenAI-compatible transcription API，并以 loopback stub 测试认证、内容类型、结果及错误；它是远程 API 适配器，不是本地 Transformers/Whisper pipeline。`OpenAiCompatibleModel` 另支持内嵌 WAV/MP3/MP4/WebM 音频输入，并明确拒绝未内嵌音频数据的 URI-only 请求；图像/音频请求均依据 `ModelCapabilities` 显式门控。`SafeSerializer` 对 Java record 要求实现 `SafeSerializableRecord` 显式允许，且该选择会调用其 accessor；未 opt-in 的 record 按任意 POJO 拒绝。新增 `ToolDefinition`/`ToolArgument` 与 `MethodToolAdapter`，可由显式注解的方法生成参数 JSON Schema、验证后安全转换类型并调用目标对象；不支持任意 POJO 入参，也拒绝不安全的 POJO 返回序列化。

Ollama native adapter 支持调用方显式声明视觉能力后，将图像 bytes 映射到原生 `images` 数组；默认能力仍关闭 vision，避免将模型未确认的多模态支持误报为可用。Hugging Face adapter 复用 endpoint 自定义消息角色转换配置。OpenAI-compatible 与 Ollama 同步请求共享对 transient HTTP 状态/I/O 错误的有界重试。

CodeAgent 可传入 additional authorized imports；白名单会同时用于本地 CPython worker 的实际 import 检查与自动生成系统提示词。CLI 接受逗号分隔的 `--imports` 及可重复 `--import`。显式授权 `*` 表示允许任意 import，仅适用于可信代码，并不形成安全沙箱。

CodeAgent 还提供 literal `CodeBlockTags` 自定义开闭标签，默认兼容 XML/Markdown fenced code；当系统提示词由 Agent 自动生成时，会显式列出配置的开闭 marker。

新增 `AgentUtils`，合并承载 Python utility 的安全子集：JSON blob 扫描、code block 提取、Unicode-aware 内容截断、终端控制字符清理、标识符校验及 SafeSerializer-backed JSON tree conversion；Python AST parsing、源码 introspection、Gradio templating 等依赖 Python runtime 的行为未迁移。

新增 `GradioSpaceTool` 可基于调用方显式 schema 或 `/gradio_api/info` 只读发现的 primitive/file schema 创建工具，发起 Gradio call/SSE 请求并将文本、JSON 或标准媒体 URL 映射为 ToolResult；默认拒绝解析到本机/私有网络的 endpoint，文件上传必须使用 HTTPS（loopback 测试需显式启用）并配置本地根目录白名单及最大文件尺寸，上传响应转换为 Gradio FileData JSON。非授权路径和超大文件在上传前拒绝。新增 `HuggingFaceHubClient` 可读取 Space Hub metadata、分页列出 revision 文件并按可配置大小上限下载指定文件至内存，也支持按 owner/item/sort 筛选、分页列出及读取完整 Hub collections；可选 `saveSpaceFileForReview` 会核验 SHA-256，将内容安全保存到预先存在的用户指定目录且拒绝覆盖，不会执行文件。受控下载校验 repository/revision/file 清单关系、路径穿越、实际内容长度和跨域重定向，token 不会发送到 CDN。Gradio 外部/非标准 binary outputs、源码审阅/批准 UI 与任意远端 Python tool 执行仍未实现。

新增 `LocalWhisperCliTool` 作为本地音频识别的可选 CLI adapter，要求调用方显式指定已安装的 OpenAI Whisper CLI 和本地 checkpoint，不隐式下载模型；通过固定参数列表启动子进程（无 shell），限制音频大小/运行时长，尊重取消信号，并仅清理自身临时目录。它不等同于 Transformers pipeline；测试使用确定性命令 runner stub，不依赖本机 Whisper GPU/模型。OpenAI-compatible 与 Ollama 原生模型流均将外部订阅取消传递到底层 HTTP 请求 future 和正在读取的 SSE/NDJSON body stream；loopback server 测试确认连接在流停滞时被关闭。新增 `AgentHttpServer` 提供 loopback-only `/health`、`/api/run/stream` JSON/SSE API 和最小同源任务页面，可选 bearer token 认证；run endpoint 强制 JSON 并校验浏览器 Origin/Fetch Metadata，客户端断连会取消 Agent 订阅，输入 body 有 1 MiB 上限，SSE 单事件与内嵌媒体也有限制，页面用 CSP 与 `textContent` 显示模型文本。它不支持会话历史/多模态 UI，也不会更改注入 Agent 的执行隔离策略。

### 7.1 2026-10-07 当前实现、差异与最新验收

本节覆盖此前记录中关于 Whisper Transformers、视觉浏览器、HTTP 历史和测试数量的旧状态。

- `TransformersWhisperTool` 已实现持久 Python JSONL worker、请求 ID、取消/超时终止、音频/文本/协议大小限制、设备选择及可选本地模型下载策略；默认 `localFilesOnly=true`，Python 依赖与真实 checkpoint 由使用方准备。确定性测试仅使用 worker fixture，不表示真实模型已验收。CLI 可通过 `--tools transcriber --whisper-backend transformers` 配置，需显式 `--allow-whisper-download` 才允许从 Hub 下载。
- 新增 opt-in `TransformersWhisperToolIntegrationTest`：需要显式 `SMOLAGENTS_WHISPER_TESTS=1`、一段含语音的 `WHISPER_TEST_AUDIO`、模型缓存或额外显式的 `SMOLAGENTS_WHISPER_ALLOW_DOWNLOAD=1`；支持模型/设备/Python 命令环境变量。默认离线测试不会加载或下载 checkpoint。worker 协议 fixture 覆盖 startup error/EOF/invalid JSON/mismatched ID/empty transcript/error response，不代替真实模型精度及音频前处理验收。
- `VisualBrowserTool` 已通过 CDP 实现有限导航、点击、输入、按键、滚动、返回、文本搜索、页面文本和截图；CDP 消息串行发送并按 loader load 事件等待导航完成。`SMOLAGENTS_BROWSER_TESTS=1` 的本机 Chrome live test 在 loopback fixture 页面验证导航、点击后文本变化和 PNG 截图。CLI 需要 `--chrome-path`；Ollama/Hugging Face 需显式 `--vision`，OpenAI-compatible 配置沿用该 adapter 已有的 vision capability。浏览器仍是以当前 OS 用户运行的子进程，不构成安全沙箱。
- `AgentHttpServer` 已提供 `AgentSessionFactory` 多会话模式：`GET/POST /api/sessions`、`DELETE /api/sessions/{id}`，每个会话持有独立 Agent、并发运行锁和历史；最多 32 个会话，每会话 512 条/4 MiB 历史，删除/停服时释放 factory 创建的 AutoCloseable Agent。兼容单 Agent 构造器仅保留一个由调用方持有的会话。页面可切换/新建/删除会话，展示文本与 allowlisted image/audio 数据。实时 SSE 可内嵌有界媒体；历史保存前去除媒体 base64。可选 session directory 使用原子 JSON 文件持久化事件历史及 `MemoryBackedAgent` 的 allowlist AgentMemory，单文件最多 22 MiB，重启后恢复；CodeAgent 的 CPython executor namespace/运行时 state 不包含在该快照中。文件不加密，必须使用访问受限目录。系统仍不是 Gradio、独立用户/租户认证或多模态上传 UI。
- 对照 Python `test_planning_step_with_injected_memory` 的确定性规划测试现断言更新提示的 4/6 条消息形状、system/user role、注入的前序任务、动作观察和剩余步数；Python YAML 默认模板的全文/Jinja渲染仍存在差异。
- `CodeAgent` 和 `ToolCallingAgent` 使用分开的 Python 默认规划 YAML 文本，并按各自动作协议呈现工具 stub；规划 prompt 的 Jinja managed-agent list 分支已由固定安全模板渲染并建立哈希对照。`ManagedAgentTool` 已加入默认子任务/报告包装；系统提示中的 managed-agent/custom-instruction 条件块仍未移植。自定义规划模板只接受 `task`、`tools`、`managed_agents`、`remaining_steps` 白名单变量，不执行任意 Jinja 表达式。
- 使用 Python venv 直接实例化 `Tool` 并调用 YAML/Jinja renderer 做源码探针；Java `PlanningPromptTemplatesTest` 对 primitive 参数、output type、structured-output JSON stub 以及有/无 managed agents 的两种 Agent 完整 initial/update prompt 做精确/哈希回归。Python 源 YAML 的单花括号 `{remaining_steps}` 原样保留，Java 会显示实时剩余数，并在静态模板哈希中显式归一化此差异。该探针不是 pytest，也未验证 system prompt custom-instruction 插值。
- `ToolSpec` 已携带 `outputType` 与可选 `outputSchema`；兼容旧四参数构造器并提供明确默认。`MethodToolAdapter` 依据 Java 返回类型映射 string/boolean/integer/number/object/array/null/any，MCP `outputSchema` 以防御性副本进入 ToolSpec；structured-output tool stub 也按 Python 格式呈现 schema。`@ToolArgument` 可显式声明并校验 JSON 默认值；Java 不会像 Python decorator 一样自动从函数签名推导默认参数，其他 built-in/用户工具 metadata 仍需逐项校准。
- `ManagedAgentTool` 的输入 schema 现接受必需的 `task` 与可选/nullable `additional_args` 对象，执行时将每个 JSON 值传给子 Agent 的 `AgentInput`；默认 task/report 包装也按 Python YAML 实现并由确定性测试覆盖。Java `includeRunSummary` 返回结构化摘要，与 Python `provide_run_summary` 追加 `<summary_of_work>` 文本不同；系统提示中的专用 Jinja managed-agent 条件尚未移植。
- Python `validate_tool_arguments` 直接探针确认：整数可用于 number 字段、nullable 字段接受 null 且可省略、非 nullable 字段拒绝 null、未知键拒绝；Java JSON Schema path 对应行为由既有 schema tests 和新增 `ToolRegistryTest.acceptsIntegerForNumberSchema` 覆盖。Python helper 还接受单参数的 scalar 调用，而 Java ToolRegistry 契约始终要求 JSON object；default/type-hint 转换仍未完整对齐。
- `AgentRuntime` 的 additional arguments 文本现使用 Python 风格 dict/list/string/bool/null 字面量，包含 quote/newline 转义；为了稳定回归，Java 按键排序，因此保留了明确的顺序差异。
- `MethodToolAdapter` 现支持显式 `@ToolArgument(hasDefault=true, defaultValueJson=..., required=false)`，并在装配时验证 JSON 默认值类型、写入 schema default、调用省略参数时传入默认值；不从 Java 方法签名推断默认值。默认值案例及类型不匹配配置失败有确定性测试。
- Python memory source 对照显示，summary mode 不含 system prompt，planning step 省略；Java `AgentMemory.toMessages(true)` 保留 system 以供普通上下文组合，planning runtime 会剥离它。Python `TOOL_CALL`/`TOOL_RESPONSE` 内部消息在 Java 中映射为 provider-level assistant toolCalls 和 tool 结果；工具失败 observation 已绑定 call ID 时不再重复追加 `ActionStep.error` user 消息。CodeAgent/protocol/final-answer-check 错误仍使用独立 user error text，剩余角色和消息文本差异列于 CAP-07/PAR-05。
- Python 原测试 `tests/test_agents.py::test_planning_step_with_injected_memory` 本轮未能执行：项目 `.venv` 和系统 Python 均缺少 `pytest`，尝试通过 `uv` 仅安装 pytest 时因当前网络连接被拒绝失败；未改动 Python 仓库或安装其余大型测试 extras。另用 Python venv 直接调用 `_generate_planning_step` 探针，确认注入前序任务时更新规划消息为 system + 2 条 Task/history + user 共 4 条；这是源码探针，不等同 pytest。Java 确定性测试另外覆盖无额外 Plan 的 4/6 条更新消息结构。
- 最新完整验证 `.\mvnw.cmd test javadoc:javadoc`：212 项、0 失败、0 错误、7 项 opt-in live test 默认跳过，Javadoc 成功。新增规划提示词回归覆盖 Python Tool 调用/CodeAgent stub 格式、output type、structured-output schema、有/无 managed agents 的默认 YAML 完整文本哈希；工具校验新增 integer-as-number、显式 JSON 默认值和无效 optional 配置拒绝对照。Transformer Whisper fixture 覆盖启动错误/EOF、错误 request ID、非法 JSON、空转录和推理错误后 worker 继续可用；真实模型 opt-in 测试需 `SMOLAGENTS_WHISPER_TESTS=1`、含语音的 `WHISPER_TEST_AUDIO`，下载只能在再显式设置 `SMOLAGENTS_WHISPER_ALLOW_DOWNLOAD=1` 时发生。本机尚缺 `accelerate`/`soundfile`，且没有 Whisper checkpoint。Chrome/CDP 浏览器与 Web UI smoke 均通过；`SMOLAGENTS_OLLAMA_TESTS=1` Gemma 测试 3/3 通过。Docker daemon 与原生云 provider 仍未验证。
- E2B/Modal/Blaxel 仍只有通用 Jupyter Gateway transport 和显式 lifecycle provider hooks；没有内置云端 SDK 创建/鉴权实现，尤其 E2B `Sandbox.run_code()` 原生协议与当前 Jupyter hook 不兼容。不能宣称原生云 Provider 已完成。

## 8. Python 测试入口索引

Java 实现及剩余差异的逐 CAP 状态、验证基线和按序实施路线见 [`04-compatibility-matrix.md`](04-compatibility-matrix.md)。

- Agent：`D:\code\smolagents\tests\test_agents.py`
- Final answer：`D:\code\smolagents\tests\test_final_answer.py`
- Models/messages：`D:\code\smolagents\tests\test_models.py`
- Tools/default tools/schema：`test_tools.py`、`test_default_tools.py`、`test_tool_validation.py`、`test_search.py`
- Python executor/security：`D:\code\smolagents\tests\test_local_python_executor.py`
- Remote executor：`D:\code\smolagents\tests\test_remote_executors.py`
- Memory/monitoring/media：`test_memory.py`、`test_monitoring.py`、`test_types.py`
- Serialization/MCP：`test_serialization.py`、`test_mcp_client.py`
- CLI/browser/UI/telemetry：`test_cli.py`、`test_vision_web_browser.py`、`test_gradio_ui.py`、`test_telemetry.py`
- Import/utils/function schema/docs：`test_import.py`、`test_utils.py`、`test_function_type_hints_utils.py`、`test_all_docs.py`
- Ollama 配置参考：`D:\code\smolagents\docs\source\zh\guided_tour.md`、`D:\code\smolagents\src\smolagents\models.py`
