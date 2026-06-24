# CC Pocket · 多 Agent 后端设计（R7：支持 Codex）

> 状态：设计稿 2026-06-24　｜　对应 ROADMAP R7（L/XL，「需先出设计」）
> 协议事实：均对 `openai/codex` `main`@`f959e7f` 源码 + committed JSON Schema 逐字核实，非文档转述

---

## 一、目标与边界

让 cc-pocket 的 daemon 除了驱动 Claude Code，还能驱动 **OpenAI Codex CLI**，手机端在新建会话时选择后端。核心诉求不变：**离开工位远程逐步批准 agent 的命令 / 改文件**——这是 cc-pocket 对官方 Remote Control 的护城河，Codex 后端必须同样支持。

非目标（本设计不含）：在同一个会话里中途切换 Claude↔Codex（会话与后端绑定）；Codex 的 cloud / review / MCP 等周边能力。

---

## 二、现状：Claude 的耦合点（要泛化的接缝）

daemon 把 `claude -p --output-format stream-json --input-format stream-json` 当子进程，双向喂 stdin / stdout。所有 Claude 协议知识集中在这些位置：

| 接缝 | 文件 | 现在写死的 Claude 语义 |
|---|---|---|
| 二进制解析 + argv | `daemon/claude/ClaudeLauncher.kt` | 找 `claude`、拼 `-p --output-format stream-json …` |
| 进程封装 | `daemon/claude/ClaudeProcess.kt` | **已与协议无关**（纯收发行 + 进程树回收），直接复用 |
| stdout 解析 | `daemon/claude/StreamParser.kt` + `StreamWire.kt` | 行 → `ClaudeEvent`（领域事件本身已基本中立）|
| 授权桥 | `daemon/claude/PermissionBridge.kt` | `can_use_tool` control_request ↔ verdict ↔ `control_response` |
| 工具元信息 | `daemon/claude/ToolMeta.kt` | 按 Claude 工具名（Bash / Edit / Read）派生标题 / 危险标记 |
| 会话编排 | `daemon/conversation/Conversation.kt` | 写 Claude `user` 帧、`control_request` interrupt 帧；`/model` `/effort` `/clear` 拦截；切 mode/model/effort 一律**杀进程重启**；`--fork-session`；transcript unhide |
| 磁盘 | `daemon/disk/{ProjectPaths,TranscriptScanner,TranscriptReplay,TranscriptPatcher,SlashCommandScanner}.kt` | `~/.claude/projects/<key>/<sid>.jsonl` 路径、扫描、回放 |
| 注册表 | `daemon/session/SessionRegistry.kt` + `DaemonCore.kt` | 持有单一 `claudeExe: Path` |
| 协议 | `protocol/Messages.kt` + `Models.kt` | `OpenSession` 无 agent 字段；`PermissionMode` 直接是 Claude CLI flag 值 |
| 移动端 | `mobile/.../ui/SessionSheets.kt`、`data/PocketRepository.kt` | model 选项写死 `opus/sonnet/haiku`；新建会话只选 mode |

`StreamWire.kt` 顶部注释已埋下设计意图：「StreamParser + StreamWire + PermissionBridge 是唯一理解 Anthropic schema 的地方，下游全部 provider 中立」。本设计就是把这句话兑现成接口，并把 `Conversation` 里残留的 Claude 语义也抽干净。

---

## 三、Codex 协议要点（app-server v2，源码核实）

### 3.1 入口：用 `codex app-server`，不是 `codex proto`，也不是 `codex exec`

- **`codex proto` 已被删除**（当前 `main` 顶层 `Subcommand` 无 `Proto`）。不要对它开发。
- **`codex exec --json`** 是一次性（一个 prompt → 跑完 → 退出），**审批只能预设策略、无逐条往返**——会废掉 cc-pocket 的核心卖点，不用。
- **`codex app-server`**：常驻、双向、stdio 上的 **JSON-RPC**，服务端会**反向请求**客户端批准每条命令 / 补丁。这才是 Claude `-p + can_use_tool` 的真正对应物，也是 OpenAI 自家 VS Code 扩展用的接口。**选它。**

> 风险：app-server 在 CLI help 里标 `[experimental]`，方法名仍在演进（见 §10）。

### 3.2 信封（`codex-rs/app-server-protocol/src/rpc.rs`）

- **线上不带 `jsonrpc` 字段**（源码注释明确「we neither send nor expect `"jsonrpc":"2.0"`」）。不要发、解析时也不要求。
- `id` 是 **string 或 int**（`untagged`）。
- 四类信封靠字段区分：
  - client→server **request**：`{id, method, params?}`
  - server→client **response**：`{id, result}` 或 `{id, error:{code,message,data?}}`
  - **notification**：`{method, params?}`，**无 `id`**
  - **server→client request**（审批问询）：与 request 同形，`{id, method, params}` —— **有 `id`** 是它和 notification 的唯一区别
- 解析必须**对未知 `method` / 未知 item `type` 容错**（Codex 升级会加），绝不抛出。

### 3.3 一条会话的生命周期

```
initialize(req) → result → initialized(notif)        # 每连接一次，先于一切
  → thread/start(req) → result.thread.{id, sessionId} # 拿到 threadId / sessionId
  → turn/start(req {threadId, input:[…]}) → result    # 每个用户回合一次
      ← turn/started(notif {threadId, turn:{id}})      # 拿到 turnId（interrupt 要用）
      ← item/agentMessage/delta(notif {…, delta})      # 流式正文
      ← item/commandExecution/requestApproval(req!)    # 审批问询 → 回 {id, result:{decision}}
      ← item/completed(notif {item:{type:"agentMessage", text}})
      ← thread/tokenUsage/updated(notif {tokenUsage})  # token 用量（独立通知！）
      ← turn/completed(notif {turn:{status}})          # 回合结束
```

### 3.4 关键消息（逐字字段，源码核实）

**握手**：`initialize` 入参 `{clientInfo:{name,version,title?}, capabilities?:{experimentalApi?:bool, optOutNotificationMethods?:[…]}}`；回 `{userAgent, codexHome, platformFamily, platformOs}`；随后发 `initialized`（无参）。

**thread/start**：入参全可选（`model? cwd? approvalPolicy? sandbox?(扁平字符串 SandboxMode) …`）；回包里 **`result.thread.id` = threadId、`result.thread.sessionId` = sessionId**（嵌套，非顶层）。

**turn/start**：必填 `threadId` + `input`（数组）。`input` 项：`{type:"text", text}`、`{type:"image", url}`、`{type:"localImage", path}`。逐轮可带 `model? cwd? approvalPolicy? sandboxPolicy?(对象，tag 驼峰) effort? summary?`。

> 坑：thread/start 用 `sandbox`（扁平字符串 `read-only`/`workspace-write`/`danger-full-access`）；turn/start 用 `sandboxPolicy`（对象 `{type:"workspaceWrite"|"readOnly"|"dangerFullAccess"}`）。逐轮推理强度字段是 `effort`（`low|medium|high|xhigh|…`）不是 `reasoningEffort`。

**turn/interrupt**：必填 `{threadId, turnId}`；线程存活，可立即再 `turn/start`（对应 Claude 的 control_request interrupt）。

**审批往返**（server→client request → 回同 `id`）：
- 命令：`item/commandExecution/requestApproval`，参 `{itemId, startedAtMs, threadId, turnId, command?(单字符串), cwd?, reason?}`；回 `{decision}` ∈ `accept | acceptForSession | decline | cancel`。
- 补丁：`item/fileChange/requestApproval`，参 `{itemId, startedAtMs, threadId, turnId, reason?, grantRoot?}`；回 `{decision}` 四值同上。diff 通过 `fileChange` item 的 `changes[]` 旁路下发。
- 答复后服务端发 `serverRequest/resolved {threadId, requestId}` 确认。

**流式通知**：`item/agentMessage/delta {delta}`（正文增量）；`item/reasoning/textDelta|summaryTextDelta {delta}`（思考增量）；`item/completed {item}`（item.type ∈ `agentMessage|reasoning|commandExecution|fileChange|…`，正文在 `text`）；`item/commandExecution/outputDelta {delta}`（命令输出）；`turn/completed {turn:{status, error?}}`；`thread/tokenUsage/updated {tokenUsage:{total:{inputTokens,cachedInputTokens,outputTokens,…}}}`；错误是单词 method **`error`** `{error:{message}, willRetry, threadId, turnId}`。

---

## 四、决定抽象怎么切的几处结构性差异

| 维度 | Claude（现状） | Codex（app-server） | 对抽象的影响 |
|---|---|---|---|
| 启动后能否直接发 prompt | 能，sessionId 首回合 init 回填 | **不能**：要先 `initialize`+`thread/start` 拿 threadId 才能 `turn/start` | 后端需「就绪前缓冲首个 prompt」；`Conversation.open` 的「先报 SessionLive 再回填 sessionId」逻辑要泛化 |
| 协议状态 | 基本无状态，`StreamParser` 是 `object` | **有状态**：id 计数器、pending-request map、当前 threadId/turnId | Codex 后端必须是**每会话实例**，不能是 object |
| 切 mode/model/effort | `-p` 启动即定死 → **杀进程重启** | **逐轮 `turn/start` 参数** → 不重启，改下轮参数即可 | 「应用设置变更」要后端策略化：Claude=重启，Codex=改下轮参数 |
| 授权标识 | tool 的 `request_id` | JSON-RPC request 的 `id` | 都能抽象成「一个待答复的 askId」，PermissionBridge 模型可复用 |
| token 用量 | 在 `result` 里随回合返回 | **独立 `thread/tokenUsage/updated`** | 累计到下一个 TurnDone |
| 流式 + 终稿 | 增量块 + 终块 | 增量 delta + `item/completed` 终稿 | 二者都发：流式 delta 当 chunk，终稿用于 TurnDone.finalText，**不重复 emit** |
| 图片输入 | base64 内联 | 仅 `image{url}` / `localImage{path}`，**无 base64 变体** | Tier A Codex 先不支持贴图；后续用 data: URL 或落临时文件走 localImage |
| 磁盘落点 | `~/.claude/projects/<key>/*.jsonl` | `~/.codex/sessions/rollout-<ts>-<threadId>.jsonl`（`RolloutLine` schema，首行 `SessionMeta`）| 扫描 / 回放要各做一份 |

---

## 五、抽象设计

两层：**共享事件词汇** + **每会话后端驱动**。`Conversation` 退化为 provider 中立的编排器。

### 5.1 共享事件词汇：`ClaudeEvent` → `AgentEvent`

把 `StreamWire.kt` 的 `ClaudeEvent` 重命名为 `AgentEvent`（语义已中立），按需补几个变体：

- `SessionInit(sessionId, cwd, model)`：Claude=system/init；Codex=`thread/started`（取 `thread.id` 作 sessionId）。
- `AssistantText` / `AssistantThinking`：Claude=text/thinking 块；Codex=`agentMessage`/`reasoning` 的 delta + 终稿。
- `AssistantToolUse`：Claude=tool_use；Codex=`commandExecution`/`fileChange` 的 `item/started`。
- `ToolResult`：Claude=tool_result；Codex=`item/completed`（命令 exitCode / 输出）。
- `ApprovalRequest(askId, kind, toolName, input, preview, …)`：**泛化** `ControlRequest`——Claude=`can_use_tool`；Codex=两类 `requestApproval`。`kind` 区分命令 / 补丁。
- `TurnResult(finalText, usage…, isError)`：Claude=result；Codex=`turn/completed` + 累计的 tokenUsage。
- 其余 `BackgroundTask* / ControlCancel / Ignored / Unparseable` 保留。

### 5.2 每会话后端驱动：`AgentBackend`

```kotlin
enum class AgentKind { CLAUDE, CODEX }

/** 驱动“一条活会话 / 一个进程”的全部 provider 私有逻辑。每会话一个实例（持有协议状态）。 */
interface AgentBackend {
    val kind: AgentKind

    /** 解析过的二进制路径 + 该 spec → ProcessBuilder（Claude 与 Codex 的 argv 不同）。 */
    fun processBuilder(spec: LaunchSpec): ProcessBuilder

    /** 进程起来后做一次性引导：Claude 无操作；Codex 发 initialize → initialized → thread/start。 */
    suspend fun bootstrap(io: AgentIo)

    /** 一行 stdout → 领域事件（可更新内部协议状态：记录 threadId/turnId/pending id）。 */
    fun parse(line: String): List<AgentEvent>

    /** 编码并写一个用户回合。Codex 会在 thread 就绪前缓冲。 */
    suspend fun sendPrompt(io: AgentIo, text: String, images: List<ImageData>)

    /** 打断在途回合；不支持则 no-op。 */
    suspend fun interrupt(io: AgentIo)

    /** 答复一个待决审批（askId 来自 AgentEvent.ApprovalRequest）。 */
    suspend fun writeApproval(io: AgentIo, askId: String, decision: Decision, updatedInput: String?)

    /** 应用 mode/model/effort 变更，返回是否需要重启进程（Claude=true；Codex=false，只改下轮参数）。 */
    fun applySettings(mode: PermissionMode?, model: String?, effort: String?): Boolean

    /** 磁盘：该后端的会话目录 / 列表 / 历史回放（Tier B）。 */
    fun transcriptDir(workdir: String): Path
    fun listSessions(workdir: String): List<SessionSummary>
    fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage>
}
```

- `AgentIo`：薄封装，提供 `writeLine(json)` 与 `emit(Frame)`（即现在 `ClaudeProcess::writeLine` 与 sink）。
- `ClaudeBackend`：把现有 `ClaudeLauncher` / `StreamParser` / `PermissionBridge` / Claude 帧编码搬进来，行为不变。
- `CodexBackend`：新实现。持有 `idSeq`、`pending: Map<id, kind>`、`threadId?`、`currentTurnId?`、`bootstrapped` 状态机、`pendingPrompt` 缓冲；`parse` 维护这些状态并产出 `AgentEvent`。

### 5.3 `Conversation` 中立化

- 构造改为持有 `backend: AgentBackend`，`pump` 改用 `backend.parse(line)`，`when(AgentEvent)` 分支保持不变（事件已中立）。
- `sendPrompt` / `cancelTurn` / `submitVerdict` 委托 `backend`。
- `switchMode/Model/Effort`：调 `backend.applySettings(...)`，**返回 true 才走现有 relaunch**；Codex 直接生效下一轮，省掉杀进程。
- `PermissionBridge` 泛化为「翻译 `ApprovalRequest`→`PermissionAsk`、等 verdict、超时 deny」，把「写回授权」委托给 `backend.writeApproval`。
- Claude 专属的 `--fork-session`、`unhideTranscript` 收进 `ClaudeBackend`（对 Codex no-op）。

### 5.4 注册与选择

- `SessionRegistry` / `DaemonCore` 改为持有一个 `backends: Map<AgentKind, AgentBackendFactory>`，按 `OpenSession.agent` 取工厂建实例。
- `Main.kt`：解析 `claude` 与 `codex` 两个二进制（各自可缺；缺的后端在被选时报友好错误），新增 `--codex-bin`。

---

## 六、协议改动（向后兼容）

`protocol/` 改动必须对老 daemon / 老 App 兼容（两端独立发版，见 `.claude/agents/protocol-wire-compat-reviewer`）。全部用「**加带默认值的字段**」，不动既有字段语义。

- `OpenSession` 增 `val agent: AgentKind = AgentKind.CLAUDE`（默认 Claude → 老 App 发来的包行为不变）。
- `SessionLive` 增 `val agent: AgentKind? = null`（让手机头部显示「Claude / Codex」徽标）。
- `SessionSummary` 增 `val agent: AgentKind? = null`（Tier B：列表区分来源）。
- 新增枚举 `AgentKind { @SerialName("claude") CLAUDE; @SerialName("codex") CODEX }`。
- `PermissionMode` **不动**（见 §七，用映射而非新枚举，最小化两端 UI 改造）。
- `Decision`（allow/deny）**不动**：Codex 的 `accept→allow`、`decline→deny` 在 `CodexBackend.writeApproval` 内翻译；「记住本会话」(`remember`) 映射到 `acceptForSession`。

---

## 七、权限模式映射

Claude 把审批 + 沙箱揉进一个 `PermissionMode`；Codex 拆成 `approvalPolicy` × `sandbox` 两个正交轴。为**不改手机 UI 与协议枚举**，由 `CodexBackend` 把 4 个 mode 翻译成 Codex 两轴：

| PermissionMode | approvalPolicy | sandbox / sandboxPolicy | 语义 |
|---|---|---|---|
| `DEFAULT` | `untrusted` | `workspaceWrite` | 逐步问询（**核心远程批准场景**）|
| `ACCEPT_EDITS` | `on-request` | `workspaceWrite` | 工作区内改动放行，危险操作仍升级 |
| `PLAN` | `untrusted` | `readOnly` | 只读咨询，不落改动 |
| `BYPASS_PERMISSIONS` | `never` | `dangerFullAccess` | 全自动不问 |

`remember=true` 的 ALLOW → 回 `acceptForSession`（Codex 原生「本会话always」），免去 cc-pocket 侧再维护 allowRules（Claude 侧维持现状）。

> 后续可选：手机端为 Codex 暴露原生两轴控制，但非本期目标。

---

## 八、磁盘层（Tier B）

- 新增 `CodexPaths`：`~/.codex/sessions/`（`CODEX_HOME` 可覆盖），文件名 `rollout-<ts>-<threadId>.jsonl`。
- 新增 `CodexTranscriptScanner` / `CodexTranscriptReplay`：解析 `RolloutLine{timestamp, item}`，首行 `SessionMeta{session_id,id,cwd,…}` 出 `SessionSummary`（cwd 过滤当前 workdir）；`ResponseItem` / `EventMsg` 行回放成 `HistoryMessage`。
- `RequestRouter.ListSessions` / `DirectoryService`：按 agent 合并两个来源（或 Tier A 先只列 Claude）。
- resume：`OpenSession.resumeId` 对 Codex = threadId，走 `thread/resume {threadId}`。

---

## 九、分阶段实施

**P0 · 抽象重构（无行为变化）**——先把 Claude 抽到 `AgentBackend` 后面，行为逐字不变，所有现有测试绿。这是后续一切的地基。
- 文件：`AgentEvent`(rename)、`AgentBackend` + `AgentIo` 接口、`ClaudeBackend`(搬运)、`Conversation`/`SessionRegistry`/`DaemonCore` 中立化、`PermissionBridge` 泛化。
- 验收：现有 daemon 测试全绿，Claude 端到端行为无差异。

**P1 · Tier A：Codex 活会话（含核心远程批准）**——新建 Codex 会话可用，但不 resume / 不进项目列表的历史。
- 文件：`CodexBackend`（握手状态机 + parse + turn/start + 审批 + interrupt + 设置映射）、`CodexLauncher`（找 `codex` + `app-server` argv）、协议加 `AgentKind` + `OpenSession.agent`/`SessionLive.agent`、`Main.kt` `--codex-bin`、移动端新建会话加 agent 选择 + 头部徽标。
- 暂不支持：Codex 贴图、resume、历史回放、Codex 会话列表。
- 验收：手机选 Codex 新建会话 → 发指令 → 看到流式正文 → 命令触发审批弹窗 → 远程 allow/deny 生效 → 打断生效。

**P2 · Tier B：Codex resume / 列表 / 回放**——`~/.codex/sessions` 扫描、`thread/resume`、历史回放、项目列表双源合并。

**P3 · Tier C：打磨**——Codex 贴图（localImage / data URL）、命令输出流式当 tool 事件、token 用量、file-change 审批 diff 展示、推送标签适配。

---

## 十、风险与开放问题

1. **app-server `[experimental]`**：方法名 / 字段在演进（近期 delta 事件刚改名）。对策：parse 容错未知 method/type；`experimentalApi=false` 只吃稳定 schema；pin Codex 版本，升级时回归。
2. **首轮缓冲时序**：thread/start 异步，首个 prompt 要等就绪——`CodexBackend.sendPrompt` 内部排队，避免死锁（参考 `Conversation.open` 现有「先报 live 再回填」思路）。
3. **图片**：Codex 无 base64 内联，Tier A 先文本；Tier C 决定 data URL vs 临时文件 localImage。
4. **ToolMeta**：Codex 审批预览来自 `command` / diff，不走 Claude 工具名表——`CodexBackend` 自建 preview/title/danger（可复用 `ToolMetadata` 的危险正则于 `command` 字符串）。
5. **两个二进制都可能缺失**：未装 `codex` 时选 Codex 要给清晰报错（指向安装方式），而非启动失败。

---

## 十一、实现状态（2026-06-24 落地：P0+P1+P2 全量）

范围已定：**P0+P1+P2 一次到位**。已实现并通过编译 + 单测（daemon 83 测试绿；protocol 序列化往返绿；移动端 desktop 编译绿）。

**真机协议验证**（对本机已装的 codex 0.124.0 实跑 `app-server`）：
- 握手 `initialize → initialized → thread/start` 回包字段、`result.thread.id` 位置、`thread/started` 通知——与实现逐字一致。
- 回合 `turn/start`（回 `result.turn.id`）、`turn/started`、`item/agentMessage/delta{delta}`、`thread/tokenUsage/updated{total:{inputTokens,outputTokens,cachedInputTokens}}`、`turn/completed{turn:{status}}`——全部坐实（模型实回 “hi”）。
- 杂通知（`mcpServer/*`、`thread/status/changed`、`warning`、`account/rateLimits/updated`）被未知分支安全吞掉。
- daemon 本地模式启动，两后端均接线；codex 经 bun 全局解析到 `codex.js`（带 shebang，可直接 exec）。

**落点文件**：
- 抽象：`daemon/agent/{AgentEvent,AgentBackend,AgentIo,AgentSpec,AgentProcess,PermissionBridge,ToolMeta}.kt`
- Claude：`daemon/claude/{ClaudeBackend,ClaudeLauncher,StreamParser}.kt`
- Codex：`daemon/codex/{CodexBackend,CodexLauncher,CodexPaths,CodexTranscriptScanner,CodexTranscriptReplay}.kt`
- 中立化：`conversation/Conversation.kt`、`session/SessionRegistry.kt`、`DaemonCore.kt`、`server/RequestRouter.kt`、`Main.kt`
- 协议：`protocol/{Models.kt(AgentKind,SessionSummary.agent),Messages.kt(OpenSession.agent,SessionLive.agent)}`
- 移动端：`data/PocketRepository.kt`、`ui/Permissions.kt`（选择器）、`ui/App.kt`（徽标 + 列表）

**纠正研究的一处**：codex 0.124 的 rollout 落盘是 `~/.codex/sessions/YYYY/MM/DD/rollout-<ts>-<threadId>.jsonl`（**按日期分子目录**，研究说的是扁平），扫描已改递归。

## 十二、已知限制（留待 Tier C）

- **Codex 不支持贴图**：`turn/start` 只收 `image{url}`/`localImage{path}`，无 base64 内联；当前 Codex 会话发图被静默忽略（仅发文本）。后续用临时文件走 localImage。
- **slash 命令仍扫 `~/.claude/commands`**：Codex 会话会看到 Claude 的自定义命令（`/model`、`/effort`、`/clear` 守护拦截仍正常）。应做成 backend 感知。
- **model 选项写死 Claude 别名**：Codex 会话里选 opus/sonnet/haiku 会被丢弃、退回 codex 默认模型（不报错）。应按 agent 给不同 model 列表。
- **项目列表发现**：仅 Codex（无 Claude 历史、未进 recents）的目录不会出现在项目列表；首次开会话后即进 recents。可后续把 Codex cwd 并入 `DirectoryService`。
- **Codex 终端会话不可观察**：observe（只读旁观）仍是 Claude 专属；Codex resume 走 `thread/resume`（控制），不处理与终端并发持有同一 thread 的冲突。
- **app-server 是 experimental**：方法名/字段会漂移。已 `experimentalApi=false` 只吃稳定面、parse 容错未知；升级 codex 后用 `scratchpad/codex_turn_probe.py` 思路回归。

## 十三、还需你做的事

- **真机端到端**：手机选 Codex 新建会话 → 发指令 → 看流式 → 触发命令审批 → 手机点允许/拒绝 → 看是否生效。daemon↔codex 这段已实跑验证，手机↔daemon 是既有成熟链路；唯一没在真机点过的是「审批 tap」那一下（逻辑已单测 + transport 复用 Claude 成熟路径）。
- **发版**：codex 后端要随 daemon 发；`service-install` 已支持透传 `--codex-bin`（缺省自动探测）。两台 daemon 主机（本机 cask + ark-prod）都需重装/重启服务才生效。
