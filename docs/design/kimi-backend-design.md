# Kimi Code CLI 第四后端设计方案（issue #206）

> 状态：P1 已实现（2026-08-06），**核心选型经 probe 修正：`kimi --wire` 在实际发布版不存在，已改接 ACP**。详见 §9 probe 实测结果与下方「⚠ 选型修正」。
> 调研时间：2026-08-06。外部事实原基于 Kimi Code CLI 官方文档（wire 协议 1.10），但装机实测（0.33.0）推翻了 wire 前提。

## ⚠ 选型修正（2026-08-06 实测，最重要）

装了 Kimi Code CLI **0.33.0**（`curl … install.sh`，176MB 单二进制，装到 `~/.kimi-code/bin/kimi`）后实测：

- **`kimi --wire` 不存在**——`error: unknown option '--wire'`。本设计 §1 的整个 wire 选型前提在发布版里不成立。
- 发布版真正的机器接口有两个：
  1. **`kimi acp`** —— 完整的 **ACP（Agent Client Protocol v1）over stdio** server。`initialize` 握手实测返回：`loadSession/resume/fork/close/delete/list` 会话能力 + `session/request_permission` 审批 + promptCapabilities（image/embeddedContext）。**这是全保真接口，本实现改接它。**
  2. `kimi -p --output-format stream-json` —— 一次性非交互（opencode 式），无交互审批通道。
- **登录是设备码流程（`kimi login` / `kimi acp --login`），需真人在浏览器完成**——本次会话无法自动登录。故 session/new、prompt、审批、落盘等**运行期行为全部未实测**（auth wall）。`initialize` 握手是唯一跑通的活体验证。

**落地决策**：daemon KIMI 后端按 **ACP v1** 实现（对应设计的「预案 A 完整审批链」——ACP 的 `session/request_permission` 天然映射 PermissionBridge）。事件映射从「wire 事件」改为「ACP `session/update`」，落盘扫描/回放按设计假设的 `~/.kimi-code/sessions` 布局**防御式**实现（未登录无法造会话，磁盘格式未证实，解析失败一律 fail-safe 空列表，不崩）。所有「wire」字样在实现里已换成 ACP 语义。

## 0. 目标与非目标

**目标**：把 Moonshot 的 Kimi Code CLI 接成 cc-pocket 的第四个 agent 后端（现有：Claude stream-json／Codex app-server JSON-RPC／OpenCode `run --format json`），手机与桌面 App 可以：列出 kimi 会话、新建会话、对话、中断、回放历史；审批（如 probe 证实可行）走既有 PermissionBridge 审批链。

**非目标**：
- 不接 legacy Python 版 kimi-cli（官方已宣布 wound down，见 §1.1）。
- 不做 kimi 特有的 goal／cron／plugins／web UI 面。
- 不做 Kimi 用量账单聚合（P2 仅把会话轮次 token 归入现有用量视图，样式层面）。
- 不改 relay（wire 兼容按 protocol 门控解决，relay 透传加密帧，无感知）。

## 1. 接口形态选型：wire 模式（`kimi --wire`）

**结论：用 wire 模式，不用 ACP，不用 print 模式（`-p --output-format stream-json`）。目标二进制是新版 Kimi Code CLI（可执行名 `kimi`，TypeScript 单二进制）。**

### 1.1 目标 CLI 的辨析（重要，防装错）

Moonshot 有两代产品：

| | legacy kimi-cli | **Kimi Code CLI（目标）** |
|---|---|---|
| 仓库 | MoonshotAI/kimi-cli | MoonshotAI/kimi-code |
| 实现 | Python 3.12–3.14（`uv tool install kimi-cli`） | TypeScript 单二进制，无 Node 依赖 |
| 状态 | 官方原话「will be gradually wound down」 | 现役，装机自动迁移 legacy 配置与会话 |
| 安装 | pip/uv | `curl -fsSL https://code.kimi.com/kimi-code/install.sh \| bash`；Windows `irm .../install.ps1 \| iex`；亦有 npm 包 |
| Windows | 未明确 | 原生支持（需 Git for Windows，用 Git Bash 作 shell） |
| 数据目录 | `~/.kimi/` | `$KIMI_CODE_HOME`（默认 `~/.kimi-code/`） |

两代都支持 wire 协议（同为 1.10），但会话落盘布局不同。**本设计只按新版 Kimi Code CLI 落地**；probe 脚本的安装指引也指向新版。

### 1.2 为什么是 wire 而不是 ACP

1. **wire 是 Kimi 自家的全保真内部协议**——官方文档原话：终端 TUI 与 ACP server 都是构建在 wire 之上的前端。ACP 是面向 IDE 的适配层，官方自报覆盖率 stable agent 侧 10/12（83%）、client 反向 RPC 4/9（44%），且带一堆我们用不上的 IDE 语义（文件 I/O 路由回客户端等）。
2. **wire 原语与我们的 AgentBackend 契约几乎一一对应**：`prompt`（发轮）、`cancel`（中断）、`steer`（轮中注入——正好对应我们的中途消息排队语义）、`replay`（读 `wire.jsonl` 重放历史）、`set_plan_mode`；server→client 的 `ApprovalRequest`（响应枚举 `approve` / `approve_for_session` / `reject`，与 `respondPermission(allow, remember, denyMessage)` 完美映射）与 `QuestionRequest`（AskUserQuestion 同类物）；`StatusUpdate` 事件带 `context_tokens` / `max_context_tokens` / `token_usage`（占用统计线现成数据源）。
3. **与 Codex 先例架构对称**：Codex 也是用其原生 app-server JSON-RPC 而非任何中间层，`daemon/codex/` 的握手机与审批映射可以近乎逐行对照着写。
4. print 模式（`-p`）是 one-shot 无审批通道，形态上等于又一个 OpenCode——放弃审批面没有必要，wire 就在那里。

### 1.3 wire 协议速览（1.10，均已有官方文档背书）

- 传输：JSON-RPC 2.0 over stdin/stdout，一行一个 JSON。启动：`kimi --wire`。
- client→server 请求：`initialize`（1.1 起，可选握手；不支持时回 `-32601`，客户端应回退无握手模式）、`prompt`、`replay`（1.3）、`steer`（1.4）、`set_plan_mode`（1.4，需 capability 协商）、`cancel`。
- server→client：`event` 通知（`{"type": …, "payload": {…}}`）与 `request` 请求（`type ∈ ApprovalRequest | ToolCallRequest | QuestionRequest | HookRequest`，必须回响应）。
- 事件类型全集：`TurnBegin / TurnEnd / StepBegin / StepInterrupted / StepRetry / CompactionBegin / CompactionEnd / StatusUpdate / ContentPart / ToolCall / ToolCallPart / ToolResult / ApprovalResponse / SubagentEvent / BtwBegin / BtwEnd / SteerInput / PlanDisplay / HookTriggered / HookResolved`。
- `prompt` 响应终态：`finished | cancelled | max_steps_reached`；错误码 `-32000`（轮次冲突）、`-32001`（LLM 未配置）、`-32002`、`-32003`。
- 会话：launch flag `--session <id>` 复用、`--continue` 最近一个；wire 内没有开新会话／换会话的方法（每进程一个会话，换会话＝relaunch）。

### 1.4 会话落盘（官方 data-locations 文档）

```
$KIMI_CODE_HOME  (默认 ~/.kimi-code)
├── config.toml                    # default_model 等
├── session_index.jsonl            # 全局索引：每行 {sessionId, sessionDir, workDir}
└── sessions/<workDirKey>/<sessionId>/
    ├── state.json                 # title、lastPrompt、创建/更新时间戳、forkedFrom
    ├── agents/main/wire.jsonl     # 主 agent 完整 wire 事件流（resume/replay 的数据源）
    └── agents/agent-0/…           # 子 agent 各自的 wire.jsonl
```

- `workDirKey = wd_<slug>_<sha256 前 12 位>`——slug 规则未文档化，**但我们不需要算它**：列会话直接过滤 `session_index.jsonl` 的 `workDir` 字段（吸取 dirKey 编码 bug 的教训——cwd 含 `_`／`.` 时自算 key 极易踩坑）。
- `wire.jsonl` 官方注明还携带 request trace（发给模型的 tool schema、MCP 工具列表等调试信息）——**离线解析必须按 type 白名单挑事件行，且对单行做长度守卫**（#81 巨行渲染崩溃前科）。

## 2. daemon 模块设计

新建 `daemon/src/main/kotlin/dev/ccpocket/daemon/kimi/`，9 个文件，对照 codex 模块（约 1010 行）估算总量 900–1100 行：

| 文件 | 对照物 | 职责 |
|---|---|---|
| `KimiBackend.kt` | `CodexBackend.kt`（472 行） | `AgentBackend` 实现。`kind = AgentKind.KIMI`；`processMode = LONG_RUNNING`；`promptDelivery = STDIN_REPLAY`（`TurnBegin` 回显 `user_input` 即消费凭证，映射为 `UserReplay`）。`attach()` 发 `initialize`（`protocol_version = "1.10"`，`capabilities = {supports_question: true, supports_plan_mode: true}`），收到响应（或 `-32601` 回退）后发 `AgentEvent.SessionInit`；会话 id 不在握手里，从落盘侧补（见 V2）。`parse()` 分发 `event`／`request`／响应帧；`sendPrompt` 发 `prompt` 请求（响应异步收，турn 终态以 `prompt` 响应 + `TurnEnd` 汇合成 `TurnResult`）；`interrupt()` 发 `cancel`。审批：`request` 帧的 `ApprovalRequest` → `pendingApprovals[askId] = rpcId`（**复合键教训：askId 用 `payload.id`，回写时取回 JSON-RPC id**）→ `AgentEvent.ControlRequest`；`respondPermission` 回 `{"request_id": payload.id, "response": approve/approve_for_session/reject}`（remember → `approve_for_session`，deny → `reject` 带 feedback）。`applySettings`：model 变更返回 true（launch flag，需 relaunch）；mode 变更除 PLAN（走 `set_plan_mode`，返回 false）外返回 true。 |
| `KimiLauncher.kt` | `CodexLauncher.kt` | 解析二进制 + 组 argv：`kimi --wire [--session <id>] [--model <m>] [--yolo]`。`envBin = CC_POCKET_KIMI_BIN`；`exeNames = ["kimi"]`（Windows 加 `.exe`/`.cmd`，实际后缀待 V-win 验证）；fallbackDirs 复用 codex 的探测集（`~/.local/bin`、npm/volta/bun/deno 全局 bin），另加官方 install.sh 的安装目录（待 V-win/V-install 验证后补）。永不走 shell。 |
| `KimiJson.kt` | `CodexJson.kt` | 宽松 JSON 访问器 + wire 帧判别小函数（isEvent/isServerRequest/isResponse）。 |
| `KimiPaths.kt` | `CodexPaths.kt` | `kimiHome()`（`KIMI_CODE_HOME` env 或 `~/.kimi-code`）、`sessionIndex()`、`sessionDir(sessionId)`（经 index 的 `sessionDir` 字段定位）、`mainWireLog(sessionDir)`。 |
| `KimiWireParser.kt` | `OpenCodeStreamParser.kt` | **live 与离线共用**的事件翻译器：wire `event`/`request` 帧 → `AgentEvent`（映射表见 §3）。这是 kimi 后端比 codex 优雅的一点——`wire.jsonl` 落盘的就是 wire 帧本身，replay 与 live 走同一个解析器，天然不漂移。 |
| `KimiTranscriptScanner.kt` | `CodexTranscriptScanner.kt` | `listSessions(workdir)`：读 `session_index.jsonl`，按 `workDir` 与 `ProjectPaths.canonicalKey(workdir)` 匹配过滤，再读各 `state.json` 取 title/lastPrompt/时间戳 → `SessionSummary(agent = AgentKind.KIMI)`。`cwdsByNewest()` 供 DirectoryService。防御：index 行损坏跳过；state.json 缺失时用目录 mtime。 |
| `KimiTranscriptReplay.kt` | `CodexTranscriptReplay.kt` | `agents/main/wire.jsonl` → `List<HistoryMessage>`：逐行过 `KimiWireParser`，`AssistantText`→ASSISTANT 行、`UserReplay`(TurnBegin)→USER 行、`AssistantToolUse`+`ToolResult` 合并成 `ChatRole.TOOL` 卡（工具名过 ToolNameMapper）。seq＝源行号，接共享 `ReplaySlicer` 实现 `replaySlice`/`replayPage`（#147）。白名单外的行（request trace、Hook*、Btw*）直接跳过；单行超长截断（renderClip 同款守卫）。 |
| `KimiModelService.kt` | `OpenCodeModelService.kt` | `fetch()`：子进程 `kimi provider catalog list` 解析模型列表（输出形状待 V8），失败降级为只含 config 默认模型的单行列表 → `ModelsList(agent = KIMI)`。 |
| `KimiDefaultModel.kt` | `CodexDefaultModel.kt` | 读 `config.toml` 顶层 `default_model = "…"`（正则扫行、遇 `[table]` 停、never throws、失败降级 null——#96 契约，切记不许急切启动 kimi 进程，claude ≥1.3.1 急切解析 crash-loop 前科）。 |

### daemon 既有文件触点（改动点，均为尾追式）

| 文件 | 改动 |
|---|---|
| `Main.kt` | L155 附近加 `--kimi-bin` option；L174 附近加 banner 惰性探测（`runCatching`，不崩）；L192 后注册 `AgentKind.KIMI to AgentBackendFactory { KimiBackend(kimiBin) }`；`missingAgentsMessage` 提及 kimi。 |
| `server/RequestRouter.kt` | L621 后加 `FetchModels` KIMI 分支 → `kimiModels.fetch()`；L243/1051/1076/1094 的 caps 过滤逻辑扩展为「peer 未声明 `kimi` 则剔除 KIMI 行」（对称 `supportsOpencode` 门控，建议实现为通用 `supportsAgent(kind)` 谓词而不是再加一个布尔）。 |
| `disk/DirectoryService.kt` | L79-82 externalAgents 集合加 KIMI（`includeKimi` 门控对称 `includeOpencode`）。 |
| `disk/SessionFilesService.kt` | L407/420 附近：KIMI 分支走 `KimiPaths` 定位（Changes 白名单／文件预览如适用；P1 可先 no-op 对齐 OPENCODE）。 |
| `disk/UsageService.kt` | P2：如把 kimi 轮次归入用量图，加 KIMI 归色。 |
| `handoff/CollaboratorGuard.kt` | L128：**P1 保守起见 KIMI 同 OPENCODE 一并拒绝 handoff**（新后端审批语义未经实战），P2 审批面稳定后再评估放开。 |
| `conversation/Conversation.kt` | 启动看门狗：LONG_RUNNING 后端复用现有超时路径，无需 OPENCODE 式零 stdout 看门狗特判；确认 L981 的 initialPrompt 特判不误伤 KIMI（KIMI 不用 initialPrompt）。 |

## 3. 事件映射表（kimi wire → AgentEvent）

| wire 事件/帧 | AgentEvent | 备注 |
|---|---|---|
| `TurnBegin {user_input}` | `UserReplay(text)` | prompt 消费凭证（STDIN_REPLAY 契约的回执）；`user_input` 为 ContentPart 数组时拼 text part。 |
| `ContentPart {type:"text"}` | `AssistantText(text)` | 流式增量，Conversation 已有聚合。 |
| `ContentPart {type:"think"}` | `AssistantThinking(think)` | `encrypted` 字段忽略。 |
| `ContentPart` image/audio/video | `Ignored` | P1 不渲染多模态输出。 |
| `ToolCall {id, function:{name, arguments}}` | `AssistantToolUse(id, name→ToolNameMapper, input=parse(arguments))` | kimi 工具名（`Shell`／`Grep` 等）过 ToolNameMapper 归一到 Claude 形状（codex/opencode 已共用，抬升复用）；`arguments` 是 JSON 字符串需二次解析。是否完整见 V10。 |
| `ToolCallPart` | 视 V10：忽略或攒流 | 若 `ToolCall` 自带完整 arguments 则忽略片段。 |
| `ToolResult {tool_call_id, return_value:{is_error, output, message}}` | `ToolResult(toolUseId, content=output∥message, isError)` | |
| `StatusUpdate {token_usage}` | `AssistantUsage(input_other, input_cache_creation, input_cache_read)` | 占用取**最后一次** StatusUpdate（last-vs-total 规则同 Claude/Codex）；`context_tokens` 可直接作占用来源，优先于自算。 |
| `TurnEnd` + `prompt` 响应 | `TurnResult(finalText, usage, isError)` | 终态汇合：`finished`→正常；`cancelled`→中断（isError=false）；`max_steps_reached`→带说明的正常收尾；`-32001/-32003` 错误响应→isError=true。 |
| `request {type:"ApprovalRequest"}` | `ControlRequest(requestId=payload.id, toolName=sender→ToolNameMapper, input=payload, diff=display 中提取?)` | diff 来源待 V4 观察 `display` 字段；rpcId 存 pendingApprovals。 |
| `request {type:"QuestionRequest"}` | P1：自动回避（不声明 supports_question）；P2：`ControlRequest` 走 AskQuestions 形状 | 见 §4 预案。 |
| `request {type:"ToolCallRequest"}` | 不会出现 | 仅当 initialize 注册 external_tools；我们不注册。防御性回 `-32601`。 |
| `request {type:"HookRequest"}` | 不会出现（不订阅 hooks）；防御回 `-32601` | |
| `SubagentEvent {parent_tool_call_id, event}` | 解包内层事件，`parentId = parent_tool_call_id` | 对齐 Claude 子 agent 的 parentId 语义；P1 可先 `Ignored`，P2 接。 |
| `SteerInput` | `UserReplay(text)`（轮中回执） | 排队消息注入成功的凭证。 |
| `StepBegin/StepInterrupted/StepRetry/CompactionBegin/CompactionEnd/BtwBegin/BtwEnd/PlanDisplay/HookTriggered/HookResolved/ApprovalResponse` | `Ignored(type)` | P1 全忽略；PlanDisplay P2 可转 AssistantText。 |
| 解析失败的行 | `Unparseable(raw)` | 契约：parse 永不抛。 |

## 4. 审批策略（两套预案，probe V4/V9 定夺）

### 预案 A（主）：完整审批链，对齐 Codex

- 默认权限档启动（**不带** `--yolo`），wire `ApprovalRequest` 上行为 `ControlRequest`，进 PermissionBridge（provider-neutral 执法层：ToolMeta、Always-allow 规则、ApprovalTimeout 自动 deny+withdraw 全部免费获得）。
- `respondPermission`：allow+remember → `approve_for_session`；allow → `approve`；deny → `reject`（denyMessage 放 `feedback`）。
- PermissionMode 映射：

| PermissionMode | kimi 落点 | 生效方式 |
|---|---|---|
| `DEFAULT` | wire 默认（审批全部上手机） | — |
| `PLAN` | `set_plan_mode {enabled:true}` | wire 方法，免 relaunch（applySettings 返回 false） |
| `BYPASS_PERMISSIONS` | `--yolo` launch flag | 需 relaunch（返回 true） |
| `ACCEPT_EDITS` | 无对应物：P1 映射为 `DEFAULT` | V9 实测 `--auto` 语义后如合适可改映射 |

- QuestionRequest：P1 initialize 时**不声明** `supports_question`（官方保证：未声明则 AskUserQuestion 类工具对 LLM 隐藏，请求不会到达）；P2 声明并接 AskQuestions 形状（QuestionRequest 的 payload 形状文档没给全，见 V-question）。

### 预案 B（备）：OpenCode 式全自动

触发条件：V4 证实默认档 ApprovalRequest 不可靠（不出现、形状与文档不符、或 approve 后行为异常）。

- 启动恒带 `--yolo`；backend 不发 `ControlRequest`，`respondPermission` 只 warn（对齐 OpenCodeBackend L133）。
- App 侧权限 sheet 对 KIMI 显示 auto-approve 告示（复用 `OpenCodeAutoApproveNotice` 的结构，Permissions.kt:142——「security semantics deception P0」原则：不能假装有审批）。
- `CollaboratorGuard` 对 KIMI 维持拒绝（无审批通道不能共享）。

两套预案在 KimiBackend 内以一个编译期常量／构造参数切换（`approvalCapable: Boolean`），使 probe 结论落地只改一处 + App 侧 sheet 分支。

## 5. 会话列表与回放方案

- **列表**：`session_index.jsonl` 逐行读（尾部新会话在后，倒序取），`workDir` 匹配用 `ProjectPaths.canonicalKey` 归一（V2 需确认 index 里存的是原始路径还是已归一路径；含 `_`／`.` 的 cwd 必测——dirKey 前科）。title/lastPrompt/timestamps 来自 `state.json`；`messageCount` P1 可用 wire.jsonl 行数近似或置 0（App 已容忍）。
- **回放**：`agents/main/wire.jsonl` 过 `KimiWireParser` → HistoryMessage 流（§2 表）。`replaySlice`/`replayPage` 接 `ReplaySlicer`（#147 契约：delta＋滚动分页）。子 agent 的 `agents/agent-*/wire.jsonl` P1 不读。
- **resume**：冷恢复＝`kimi --wire --session <id>` relaunch（V7 验证组合可用）；**不用 wire 的 `replay` 方法做回放**——离线解析落盘文件即可，不必为读历史拉起进程（对齐三个先例的「no process launch」纪律）；`replay` 方法仅留作 probe 对照（验证我们的离线解析与官方重放事件集一致）。
- `resumeContextTokens`：wire.jsonl 尾扫最后一个带 `context_tokens` 的 `StatusUpdate`。
- `resumeModel`：state.json 是否记 model 待 V2 观察；拿不到就返回 null（合法降级，Codex 同款）。
- `renameSession`：wire 无改名方法 → 默认 false（`/title` 是 TUI slash 命令，P2 可试 prompt 通道注入，暂不做）。

## 6. protocol 变更与混版矩阵

### 变更（全部尾追，须过 protocol-wire-compat-reviewer）

1. `Models.kt` `AgentKind` 尾追 `@SerialName("kimi") KIMI`（现 L44-46 之后）。
2. `AGENT_WIRE_KIMI = "kimi"` 常量（对称 `AGENT_WIRE_OPENCODE`，供 `ClientCaps.supportsAgents` 声明）。
3. `isModelCompatibleWithAgent` 加 KIMI 分支：**放行任意非空 id，但拒绝 Claude 别名集**（对齐 CODEX 分支 `m.lowercase() !in CLAUDE_MODEL_ALIAS_IDS`——防止残留的 claude 别名默认值串进 kimi 会话）；V8 拿到真实 id 形状后如有更强判别再收紧。
4. `ContextWindow.kt` 不加 per-kind 分支：kimi 的窗口占用直接来自 `StatusUpdate.context_tokens/max_context_tokens`，不需要查表（这反而是最准的路径，避开 ctx% 虚高全家桶）。

### 混版矩阵

| 场景 | 行为 | 依赖机制 |
|---|---|---|
| 老 App＋新 daemon | 老 App 握手 `ClientCaps.supportsAgents` 不含 `"kimi"` → daemon 在会话列表、目录聚合、active state 全部**剔除 KIMI 行**（RequestRouter L1051/1076/1094 的门控扩展）；reattach KIMI 会话拒绝（SessionRegistry L315/319 对称）。老 App 永远收不到 KIMI 枚举 → 不触发反序列化问题。 | ClientCaps 能力门控（**唯一可靠机制**：`coerceInputValues` 只救「已含该字段默认值的未来 build」，已发布老 App 对未知枚举是 hard-fail，Json.kt L12-16 注释明示） |
| 新 App＋老 daemon | daemon `backends` 无 KIMI → `open` 回 `PocketError("agent_unavailable")`，App toast 兜底；选择卡层面：App 依 daemon 下发的目录 `sessionAgents`／externalAgents 判断是否展示 kimi 入口（老 daemon 不会下发 KIMI 行，入口自然不出现）。 | SessionRegistry L397-399 现成路径 |
| 新 App＋新 daemon | 全功能。 | — |

## 7. App UI 触点清单（文件级，对称 OPENCODE 的全部位置）

前缀 `mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/`：

| 文件:行（现状） | KIMI 对称改动 |
|---|---|
| `theme/Theme.kt:37-38/57-58/78-79/106-107` | 加 `Tok.kimi` 颜色 token（暗＋亮）。建议取 Moonshot 品牌蓝紫系且与 `codex` teal、`opencode` 紫拉开区分度（如暗 `0xFF4D8DFF` 系），实现时可微调。 |
| `ui/AgentIdentity.kt:49/54/59/82` | 颜色→`Tok.kimi`；名 `"Kimi"`；标语 `"Kimi Code · Moonshot"`；`AgentGlyph` 加 KIMI 分支（新 glyph，如新月弧）。 |
| `ui/Permissions.kt:249` | 新会话 agent 选择卡加 `AgentOption(KIMI, …)`。 |
| `ui/Permissions.kt:142/266/274/366/402` | 预案 A：KIMI 走 Claude 同款权限档选择（DEFAULT/PLAN/BYPASS 三档，隐藏 ACCEPT_EDITS）；预案 B：对称 OPENCODE 的 auto-approve 告示＋强制 BYPASS＋`Tok.warn` chip。`sessionDefaultsLabel` 加 KIMI 分支。 |
| `ui/App.kt:1480` | 会话列表 agent 过滤器加 `"kimi" -> it.agent == AgentKind.KIMI`。 |
| `ui/DirList.kt:135` | 字符串→枚举映射加 `"kimi" -> KIMI`。 |
| `ui/SessionSheets.kt:110/507/545` | `modelLabelForAgent`：KIMI→原样显示；`modelChoicesFor`：KIMI→daemonModels 映射（FetchModels 通道）。 |
| `ui/UsageScreen.kt:493` | P2：用量图 KIMI→`Tok.kimi`。 |
| `data/PocketRepository.kt:204/552/559/1569/1587/5360` | `defaultKimiModel` state＋SecureStore `K_DEFAULT_KIMI_MODEL`＋`defaultModelFor` 分支。 |
| `data/PocketRepository.kt:1789` | **关键**：`ClientCaps(supportsAgents = listOf(AGENT_WIRE_OPENCODE, AGENT_WIRE_KIMI), …)`——漏了这行新 daemon 会把 KIMI 行全过滤掉，表现为「装了新 App 也看不到 kimi 会话」。 |
| codex-only 面（`CODEX_PRESETS`、service tier、effort） | **不**套用到 KIMI：kimi 无 approvalPolicy×sandbox 两轴、无 priority tier、无 reasoning effort 面（wire 未暴露）。 |

## 8. 实现分期

### P1 最小可用（预计 2 天）

范围：列表／新建／对话／中断／回放＋审批（按 probe 结论选预案）。

1. protocol：AgentKind 尾追＋常量＋兼容函数分支（半天内，含 wire-compat 审查）。
2. daemon `kimi/` 9 文件＋触点接线（1 天：Backend/Launcher/Parser 是主体，Scanner/Replay 因落盘格式即 wire 帧而显著比 codex 省）。
3. App 对称 UI＋ClientCaps（半天）。

验收：见末尾清单第 4 步。

### P2 完善（预计 1 天）

- QuestionRequest（声明 `supports_question`＋AskQuestions 形状接入）。
- 模型列表/切换全通（`kimi provider catalog list` 解析＋切换 relaunch 链路）＋默认模型设置页。
- steer 接排队语义（daemon Conversation 的中途消息注入改走 `steer` 而非等轮次边界）。
- SubagentEvent parentId 接入；用量归色；PlanDisplay 呈现。
- CollaboratorGuard 放开评估（仅预案 A 且实战稳定后）。

## 9. 风险与 probe 清单

### probe 待验证项（`scripts/probe-kimi-wire.py` 逐项覆盖，编号一致）

| # | 验证点 | 若为否的后果／预案 |
|---|---|---|
| V1 | `initialize` 握手＋capabilities 协商；`-32601` 回退路径 | 回退无握手模式（协议允许），能力协商功能全关 |
| V2 | wire 模式下会话是否落盘；`session_index.jsonl`／`state.json` 字段形状；`workDir` 是否原始路径；含 `_`／`.` 的 cwd 匹配 | 不落盘＝列表/回放方案重写（改用 wire `replay` 方法拉历史，需拉起进程，成本高——重大发现须回文档改 §5） |
| V3 | 事件序（TurnBegin→ContentPart→TurnEnd）＋`StatusUpdate.context_tokens` 有无 | 无 context_tokens 则占用线降级为 token_usage 累计 |
| V4 | 默认档 `ApprovalRequest` 是否可靠到达；`payload.id`/`tool_call_id`/JSON-RPC id 三者关系；`display` 字段有无 diff | 不可靠→预案 B |
| V5 | `steer` 轮中注入＋`SteerInput` 回执 | 失败则排队语义维持现状（轮次边界注入） |
| V6 | `cancel` → prompt 响应 `cancelled` | 失败则中断退化为杀进程（Claude 无 SIGINT 通道同款兜底） |
| V7 | `--wire --session <id>` 组合可用；官方 `replay` 事件集与我们离线解析一致 | 组合不可用＝resume 断路，须另寻恢复通道（重大） |
| V8 | `kimi provider catalog list` 输出形状；`config.toml` `default_model`；`--model` 与 `--wire` 组合 | 解析不出→ModelService 降级只报默认模型 |
| V9 | `--yolo` 下 ApprovalRequest 归零；`set_plan_mode` 往返 | plan 不可用则 PLAN 档隐藏 |
| V10 | `ToolCall.arguments` 完整 vs 需拼 `ToolCallPart` | 需拼则 Parser 加攒流 buffer |

### probe 实测结果（2026-08-06，kimi 0.33.0，未登录）

probe 脚本 `scripts/probe-kimi-wire.py` 假设 `kimi --wire`，实测该 flag 不存在，脚本首个 `initialize` 即因进程退出报错。以下为**手工用 ACP 接口**探测的结果：

| # | 结果 | 实测 |
|---|---|---|
| V1 | **FAIL（wire）→ 改测 ACP PASS** | `kimi --wire` 报 `unknown option`。改测 `kimi acp` 的 ACP `initialize`：返回 `protocolVersion:1`、`agentInfo={Kimi Code CLI,0.33.0}`、`agentCapabilities.sessionCapabilities={list,resume,close,delete,fork}`、`authMethods=[login(terminal device-code)]`。握手完好。 |
| V2 | **未测（auth 阻塞）** | 未登录无法造会话，`~/.kimi-code/sessions` 不存在，落盘布局/`session_index.jsonl` 字段形状**未证实**。Scanner/Replay 按设计假设防御式实现，fail-safe 空。 |
| V3 | **未测（auth 阻塞）** | `session/prompt` 需已配置模型；`kimi -p … stream-json` 实测报 `No model configured`。turn 事件序未观察。 |
| V4 | **未测（auth 阻塞）→ 走 ACP 审批** | ACP 提供 `session/request_permission`（server→client，options 带 `optionId`+`kind`∈allow_once/allow_always/reject_once/reject_always）。实现按此映射 PermissionBridge（预案 A）。活体审批未跑。 |
| V5 | **N/A** | ACP 无 `steer`；轮中注入走再次 `session/prompt`（P2）。排队语义维持轮次边界。 |
| V6 | **未测** | ACP `session/cancel`（notification）→ prompt 响应 `stopReason:cancelled`。已按此实现，未跑。 |
| V7 | **部分（能力声明 PASS）** | ACP `session/load`（resume）+ `fork` 在 handshake 的 `sessionCapabilities` 里声明支持。实际 resume/replay 未跑（auth）。注意 ACP `session/load` 会用 `session/update` 重放历史——后端在 load 响应前**丢弃**这些（磁盘另做回放），已实现。 |
| V8 | **PASS（形状已知）** | `kimi provider list --json` 返回 `{"providers":{},"models":{}}`（未配置时空）；`models` 的 key 即模型别名 = ModelService 数据源。`kimi provider catalog list` 返回公共 catalog（97+ providers）。已按 `provider list --json` 实现。 |
| V9 | **未测** | ACP 权限模式走 session modes（P2）；`-y/--yolo`、`--auto`、`--plan` 是**顶层交互 flag，不作用于 `acp` 子命令**（acp 只接受 `--login`）。P1 审批恒走 request_permission。 |
| V10 | **未测（auth 阻塞）** | ACP `tool_call`/`tool_call_update` 的 rawInput/content 形状未活体观察；解析器按 ACP spec 实现（tool_call 带完整 rawInput，无需攒流）。 |

### 人工补测项（脚本外）

- V-win：Windows 真机——install.ps1 安装路径、可执行后缀（`.exe`？）、Git Bash 依赖对 daemon 子进程拉起的影响、`%USERPROFILE%\.kimi-code` 路径。
- V-question：`QuestionRequest` payload 形状（文档缺失）——声明 `supports_question` 后诱导 AskUserQuestion 观察。
- V-auth：未登录时 `--wire` 的表现（预期 `-32001`）→ open 失败文案要能指引用户去终端 `kimi` `/login`。
- V-bigline：`wire.jsonl` request trace 行的典型/极端长度（决定 Scanner/Replay 的行长守卫阈值）。
- V-upgrade：`tui.toml` `[upgrade].auto_install` 默认开——CLI 自更新可能带协议漂移，probe 应纳入「升级 kimi 后必跑」惯例（对齐 probe-claude-wire）。

### 其他风险

1. **协议漂移**：Kimi Code CLI 迭代极快（自动更新默认开）。缓解：probe 常态化＋KimiWireParser 对未知 type 一律 `Ignored` 不崩。
2. **wire.jsonl 巨行**：request trace 含全量 tool schema。缓解：白名单＋行长守卫（#81 前科）。
3. **审批形状文档不全**（display/diff）。缓解：预案 A/B 开关一处切换。
4. **两代 CLI 混淆**：用户机器上可能装着 legacy Python kimi-cli（同名 `kimi`）。缓解：Launcher 解析后跑 `--version` 特征校验成本高，P1 先不做；open 失败文案提示「需要 Kimi Code CLI（新版）」；probe V1 的 server.name 可判别。

## 10. 给实现 agent 的执行顺序清单

1. **先跑 probe 补全待验证项**：装机 `curl -fsSL https://code.kimi.com/kimi-code/install.sh | bash` → `kimi` 内 `/login` → `python3 scripts/probe-kimi-wire.py`。把 V1–V10 结论回填本文档 §9 表格（改「待验证」为实测值）；V4/V9 结论落到 §4 选定预案 A 或 B；V2 若推翻落盘假设，先改 §5 再动工。验收：probe 全绿或每个 FAIL 都有已定预案。
2. **protocol 变更**（§6）：AgentKind 尾追＋`AGENT_WIRE_KIMI`＋`isModelCompatibleWithAgent` 分支；跑 `:protocol` 测试＋**必须**过 protocol-wire-compat-reviewer agent。验收：老序列化样本回归绿；reviewer ACCEPT。
3. **daemon P1**（§2/§3/§4/§5）：9 文件＋触点接线。先 Scanner/Replay（纯离线、可单测：用 probe 真跑产出的 `~/.kimi-code/sessions/**` 样本做 fixture），再 Backend/Launcher/Parser。每文件对照表列的 codex/opencode 对照物写。验收：`daemon:test` 绿；新增 KimiWireParser/Scanner/Replay 单测（fixture 驱动）；本机 `bash scripts/update-local-daemon.sh` 后桌面 App 能列出 kimi 会话并回放。
4. **App P1**（§7）：全部触点对称扩展＋ClientCaps 声明。验收：`compileKotlinDesktop` 绿＋`bash scripts/check-all.sh` 三套全绿；桌面 App 实测：新建 kimi 会话→对话→中断→（预案 A）手机收到审批卡并 approve/deny 生效→回放冷会话。
5. **混版自查**：模拟老 caps（去掉 `"kimi"` 声明）确认 daemon 过滤 KIMI 行；老 daemon（不注册 KIMI factory）确认 open 报 `agent_unavailable`。
6. **P2**（§8）按序做，每项独立可交付。
7. **收尾**：CLAUDE.md「升级 kimi CLI 后跑 probe-kimi-wire.py」惯例入档；本文档状态改「已实现」，回填偏差。

---

### 附：估时修正

原估 3–5 天。基于本次调研修正为 **3–4 天**：probe 0.5 天＋P1 2 天＋P2 1 天＋联调收尾 0.5 天。利好：wire 协议文档质量高、审批语义与 respondPermission 天然同构、落盘即 wire 帧使 Scanner/Replay 比 codex 省一半、双先例代码可逐行对照。风险敞口：V2（不落盘）或 V7（resume 断路）任一被推翻＋Windows 意外，各 +1 天。

Refs #206
