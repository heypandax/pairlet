# 手机操控电脑 Claude Code —— 实现计划（参考 cc-connect，全 Kotlin 重写）

> 配套文档：需求 [`REQUIREMENTS.md`](./REQUIREMENTS.md)（要做什么）；界面设计 [`design/`](./design/)（**已产出**，claude.ai/design handoff）。本文聚焦实现（怎么做）。
>
> **现状（2026-06-14）**：本计划的 M0–M4 已基本落地并发布（daemon 1.1.0 / app 1.0.1）。下文保留当初的实现规划原貌，与最终代码有几处出入，**以代码与 [`REQUIREMENTS.md`](./REQUIREMENTS.md) §6.1 为准**：E2E 实际用 **P-256**（非草案里的 X25519）且**默认启用**；权限模式实际为 **4 档**（`default / acceptEdits / plan / bypassPermissions`）；协议消息集已扩展（语音、图片、斜杠命令、思考块、观察模式等）。

## Context（为什么做这件事）

目标：在手机上操控电脑上的 Claude Code —— 能恢复电脑上已有的会话或新建会话、能灵活切换工作目录、能在手机上批准/拒绝 Claude 的工具授权弹窗。

参考项目 `../cc-connect/`（Go，MIT）已经验证了「把 `claude` CLI 当子进程驱动」这条路可行，但它本身是为接入飞书/Telegram/Slack 等聊天平台设计的，且与本仓库无关。本仓库 `cc-dashboard` 是 macOS 菜单栏 App，只是 Claude Code 钩子的**被动消费者**（接收 `PreToolUse` 钩子、展示审批），它**从不主动驱动 claude**。所以「手机操控」是一块全新能力，不是在现有代码上改。

关键约束（来自用户）：

1. 移动端用 **KMP**（已定）。
2. 可购买云服务器（→ 云端 relay 可行）。
3. 必须拆成**独立的基础组件**，与当前项目解耦，要支持 **Linux/Windows/macOS**。当前 cc-dashboard 设计**不需要适配**。
4. 用户与 cc-connect 同为 MIT，但担心抄袭风险 → 走 clean-room 重写。

已锁定的四个决策（本轮确认）：

| 维度 | 选择 |
|---|---|
| 技术栈 | **全 Kotlin**：daemon + relay + mobile 共享一个 `kotlinx.serialization` 协议模块 |
| 移动 UI | **Compose Multiplatform**（Android + iOS 共用一套 Compose） |
| v1 连接范围 | **先局域网，再上 relay**（M0 局域网可用 → 后续加云端 relay） |
| 远程授权 | **纳入 v1**：手机处理 Claude 的工具授权弹窗 |

预期产出：一个名为 `cc-pocket`（工作名，最终命名由用户定）的**全新独立仓库**，与 cc-dashboard 平级（建议放 `/Users/lidapeng/Desktop/Project/app/cc-pocket/`），自带 MIT 协议。cc-dashboard 本身一行不改。

---

## 已核实的技术事实（直接用，来自 cc-connect 源码 + 真实磁盘格式）

这些是 **Anthropic 的公开接口/数据格式**（互操作所必需，可放心复刻；不是 cc-connect 的原创表达）：

- **驱动 claude 的方式**：把用户本机安装的 `claude` 当子进程拉起：
  ```
  claude --output-format stream-json --input-format stream-json \
         --permission-prompt-tool stdio --replay-user-messages --verbose \
         [--permission-mode <mode>] [--resume <sessionId>] [--model <m>] \
         [--append-system-prompt ...]
  ```
  - 子进程的 **cwd 就是工作目录**（`cmd.Dir = workDir`）。切目录 = 用新 cwd 重新拉起。
  - **恢复会话** = 加 `--resume <sessionId>`；**新建** = 不加。
  - stdin/stdout 上是**按行分隔的 JSON**（newline-delimited），双向通信。
  - **不需要 PTY** —— stream-json 模式下 cc-connect 用的就是普通管道（pipes），PTY 只在它一个可选的「额度抓取」功能里出现。这消除了非 Go 技术栈最大的跨平台风险。
- **授权握手**（因为带了 `--permission-prompt-tool stdio`）：claude 在流里发 `{"type":"control_request","request_id":...,"request":{"subtype":"can_use_tool","tool_name":...,"input":{...}}}`；我们往它 **stdin** 写回 `{"type":"control_response","response":{"subtype":"success","request_id":...,"response":{"behavior":"allow"|"deny",...}}}`。这就是「手机远程授权」的底层通道。
- **列出可恢复的会话**（无需拉起 claude）：读 `~/.claude/projects/<dir-key>/<sessionId>.jsonl`，`<dir-key>` = cwd 绝对路径把 `/` 等替换成 `-`。每个 `.jsonl` 的首条 `user` 记录带 `cwd / sessionId / gitBranch / version` 和首个 prompt，足够生成标题 + 消息数。
- **干净杀进程树**：把子进程放进独立 process group（Unix `Setpgid` + `kill(-pid)`；Windows `CREATE_NEW_PROCESS_GROUP` + `taskkill /T`），并从环境变量里**过滤掉 `CLAUDECODE`**（避免 claude 误判为嵌套会话）。

**只读参考文件**（读它们是为了确认上面的「事实」，**不照抄结构/字段名/代码**）：
- `../cc-connect/agent/claudecode/session.go` —— 拉起 flags、stream-json 解析、`control_request/response` 授权回环。
- `../cc-connect/agent/claudecode/proc_unix.go` / `proc_windows.go` —— 进程组干净杀树。
- `../cc-connect/agent/claudecode/claudecode.go` —— `.jsonl` 会话枚举 + project-key 编码。
- `../cc-connect/agent/claudecode/claude_usage.go` —— 佐证 PTY 仅用于可选额度抓取，与核心驱动无关。

---

## 架构（全 Kotlin，四个模块）

```
手机 App (KMP + Compose MP)
   │  共享 :protocol 模块（同一套 kotlinx.serialization 类型）
   ▼   ── WSS ──▶  云端 relay (Kotlin/Ktor，购买的云主机)  ◀── WSS ── 电脑 daemon
                       │  共享 :protocol                         │  共享 :protocol
                       │（M0 阶段无 relay，手机直连同网段 daemon）  ▼
                                                          claude CLI（pipes, stream-json）
                                                                 │
                                                          ~/.claude/projects/*.jsonl（恢复用）
```

daemon **主动外拨**连云端 relay（解决电脑在 NAT/防火墙后无法被直连的问题）；手机也连 relay；relay 按账号/配对把两条连接撮合起来。这是我们**完全原创**的设计 —— cc-connect 没有「手机↔电脑」云端 broker（它的 `core/relay.go` 只是同机器内 bot 之间转发），所以这块没有可抄的对象、风险最低。

### Gradle 多工程仓库布局

```
cc-pocket/                                  ← 新独立仓库（不在 cc-dashboard 内）
├── settings.gradle.kts                     include(":protocol",":daemon",":relay",":mobile:composeApp")
├── gradle/libs.versions.toml               ktor / kotlinx-serialization / coroutines / compose
│
├── protocol/                               ← KMP 共享库（唯一的线协议来源）
│   └── src/commonMain/kotlin/.../proto/
│       ├── Envelope.kt   传输封装（id, ts, to, body）
│       ├── Messages.kt   sealed Frame 全部消息类型
│       ├── Models.kt     SessionSummary / DirectoryEntry / ToolCall / TokenUsage ...
│       └── Json.kt       统一的 Json{ classDiscriminator="t"; ignoreUnknownKeys=true }
│       targets: jvm() / androidTarget() / iosArm64() / iosSimulatorArm64()
│
├── daemon/                                 ← Kotlin/JVM + Ktor，跑在用户电脑（Linux/Win/macOS）
│   └── src/main/kotlin/.../daemon/
│       ├── Main.kt                  CLI: pair | run | service-install | test-client
│       ├── claude/ClaudeProcess.kt  ProcessBuilder 拉起、管道、进程组杀树、过滤 CLAUDECODE
│       ├── claude/StreamParser.kt   stream-json 行 → 领域事件（★ Anthropic schema 只活在这里）
│       ├── claude/PermissionBridge.kt control_request→Frame；Frame→control_response→stdin（★）
│       ├── session/SessionRegistry.kt  key=(cwd,sessionId) → 活的 ClaudeProcess
│       ├── disk/TranscriptScanner.kt   读 ~/.claude/projects/* → SessionSummary[]
│       ├── disk/DirectoryService.kt    枚举/校验/切换 cwd + recents
│       ├── relay/RelayClient.kt        Ktor WS 客户端：外拨、鉴权、指数退避重连
│       └── service/                     Systemd / WindowsService / Launchd 安装器
│
├── relay/                                  ← Kotlin/JVM + Ktor，跑在购买的云主机
│   └── src/main/kotlin/.../relay/
│       ├── Routing.kt   /v1/daemon(WS) /v1/device(WS) /v1/pair(REST) /healthz
│       ├── auth/        账号 ↔ 设备 token；一次性配对码签发（≤120s、单次、限流）
│       └── broker/      内存态在线表 + 双向泵（不持久化消息体，隐私）
│
└── mobile/composeApp/                      ← KMP + Compose Multiplatform
    └── src/commonMain/.../app/
        ├── net/RelayConnection.kt   Ktor WS 客户端（Android=CIO，iOS=Darwin 引擎）
        ├── data/PocketRepository.kt 状态中枢，消费 :protocol
        └── ui/  Pairing / DaemonPicker / DirectoryPicker / SessionList / Chat / PermissionSheet
        androidMain: MainActivity + FCM 推送 + EncryptedSharedPrefs
        iosMain:     入口 + APNs token + Keychain
```

**`:protocol` 如何被三端消费**：daemon/relay 用 `implementation(project(":protocol"))` 解析到它的 **JVM** 产物；mobile 解析到 **Android/iOS** 产物。三端 `commonMain` 同一份 sealed class、同一个序列化器配置 —— **协议漂移在编译期被消除**，这是选全 Kotlin 的核心收益。

---

## 协议草案（原创 schema，刻意不同于 cc-connect 的字段/类型名）

要点：单层 `Envelope` 框 + 稳定关联 id；动词命名空间用 `pocket/*`；三个 sealed 方向根让编译器强制方向。下面是关键形状（完整集合在实现时落到 `Messages.kt`）：

```kotlin
@Serializable data class Envelope(val id: String, val ts: Long, val to: Route = Route.PEER, val body: Frame)
@Serializable enum class Route { PEER, RELAY }   // PEER=转发给配对的另一端；RELAY=relay 自己处理

@Serializable sealed interface Frame

// 手机 → daemon
@SerialName("pocket/dirs.list")     class ListDirectories(...)
@SerialName("pocket/sessions.list") class ListSessions(val workdir: String)
@SerialName("pocket/session.open")  class OpenSession(val workdir:String, val resumeId:String?=null, // null=新建
                                                      val model:String?=null, val mode:PermissionMode=DEFAULT)
@SerialName("pocket/session.switchDir") class SwitchDirectory(val convoId:String, val workdir:String)
@SerialName("pocket/prompt")        class SendPrompt(val convoId:String, val text:String, ...)
@SerialName("pocket/verdict")       class PermissionVerdict(val convoId:String, val askId:String, val decision:Decision, ...)

// daemon → 手机
@SerialName("pocket/sessions")      class Sessions(val workdir:String, val items:List<SessionSummary>)
@SerialName("pocket/session.live")  class SessionLive(val convoId:String, val sessionId:String?, val workdir:String)
@SerialName("pocket/chunk")         class AssistantChunk(val convoId:String, val seq:Long, val piece:StreamPiece) // text|thinking
@SerialName("pocket/ask")           class PermissionAsk(val convoId:String, val askId:String, val tool:String, val inputPreview:String, ...)
@SerialName("pocket/turn.done")     class TurnDone(val convoId:String, val finalText:String?, val usage:TokenUsage?)

// relay 控制面（to=RELAY，永不到对端）
@SerialName("pocket/attach")        class Attach(val token:String, val role:Role, ...)   // daemon 与 device 各自先发
@SerialName("pocket/pair.begin")    class PairBegin(...)    // daemon 请求配对码
@SerialName("pocket/pair.code")     class PairCode(val code:String, val expiresInSec:Int) // relay→daemon，展示给用户
@SerialName("pocket/peer.presence") class PeerPresence(val online:Boolean)
```

**协议 ⇄ Anthropic stream-json 的映射只活在 daemon 的 `StreamParser` / `PermissionBridge` 两个文件里**，对 relay/手机完全隐藏 Anthropic 的 schema —— 这是反抄袭的关键：我们与 Anthropic 的格式**互操作**，但线协议**全是自己的**。

| 我们的协议（对端可见，原创） | Anthropic stream-json（仅 daemon 内部） |
|---|---|
| `AssistantChunk(TextPiece)` | `{"type":"assistant","message":{"content":[{"type":"text"}]}}` |
| `PermissionAsk` | `{"type":"control_request",...,"request":{"subtype":"can_use_tool",...}}` |
| `PermissionVerdict(ALLOW)` 写回 | `{"type":"control_response","response":{...,"response":{"behavior":"allow"}}}` |
| `SendPrompt` 写回 | `{"type":"user","message":{"role":"user","content":text}}` |
| `TurnDone` | `{"type":"result","usage":{input_tokens,output_tokens}}` |
| `SessionLive.sessionId` | `{"type":"system","session_id":...}` |

---

## 三条核心流程（端到端）

**A. 恢复已有会话**：手机 `ListDirectories` → daemon 回 recents + 扫 `~/.claude/projects/*` → 手机选目录 `ListSessions(workdir)` → daemon 读 `.jsonl` 头部回 `Sessions` → 手机选某会话 `OpenSession(workdir, resumeId=<sid>)` → daemon `claude ... --resume <sid>`（cwd=workdir）→ 回 `SessionLive` → `SendPrompt` → 流式 `AssistantChunk*` → `TurnDone`。**列会话无需拉起 claude**，恢复只是「加 `--resume`」。

**B. 新建会话**：同上选到目录后 `OpenSession(workdir, resumeId=null)` → daemon 不带 `--resume` 拉起，cwd=该目录 → 首轮 `system.session_id` 回填给手机。

**C. 切目录**：`SwitchDirectory(convoId, newdir)` → daemon 干净杀掉当前进程组、用新 cwd 重新拉起（cwd 在拉起时固定，切目录本质就是重启子进程，可选带 `--resume` 同一 sessionId）。

**D. 远程授权回环**：claude 发 `control_request` → daemon 的 `PermissionBridge`（若是 auto 模式本地直接放行；否则）映射成 `PermissionAsk` 发手机（手机在后台则触发 APNs/FCM 推送）→ 手机弹 `PermissionSheet`，用户点 Allow/Deny → `PermissionVerdict` 回 daemon → 写 `control_response` 到 claude stdin。`askId == request_id`，一一对应；daemon 对裁决设超时 → 超时自动 deny，避免工具调用挂死。

---

## 设计（UI/UX —— 已产出）

界面设计**已完成**：用 **claude.ai/design** 按统一设计系统生成了全部 7 屏，并经 **Handoff to Code** 导出到 [`design/claude-design-handoff/`](./design/claude-design-handoff/)（各屏 `.html` + `.jsx` 原型 + 设计对话 + 给 coding agent 的 `README`）。M2 的 Compose 实现以它为**像素级参照**。

> 选型经过：同一份 brief 同时跑过 Stitch 与 claude.ai/design，最终选 claude.ai/design（更贴 brief、强调色更克制、且 Handoff 闭环到 Claude Code）。对比报告与 Stitch 产物已归档到 Obsidian `~/Desktop/Brain/20_Projects/cc-pocket-设计工具评估/`，仓库内不再保留 Stitch。

### 设计系统：Calm Terminal Companion

暗色优先、克制、开发者原生——像夜里一台讲究的 IDE，靠一抹陶土色点睛。

- **配色**：base `#0E0F11` ／ surface `#16181B` ／ raised `#1E2125` ／ 描边 `#2A2E33`；文本 `#ECEDEE`、次 `#9BA1A6`、弱 `#6B7177`；强调陶土 `#D97757`；语义 success `#4FB477` / warning `#E0A93B` / danger `#E5604D`。
- **字体**：UI 用 **Inter**；**JetBrains Mono 专用**于路径 / sessionId / 分支 / 代码 / token / 命令（开发者气质的关键）。
- **层级**：靠**色阶叠加 + 1px 描边**表达，不用重阴影。圆角 12 卡片 / 20 底部 sheet / 999 胶囊；4pt 间距栅格；触达 ≥44pt。
- 强调色**克制**：每屏一个主操作 + 流式光标 + 激活态。
- 完整规格见 [`design/UI-DESIGN.md`](./design/UI-DESIGN.md)；喂给工具的 prompt 见 [`design/CLAUDE-DESIGN-PROMPT.md`](./design/CLAUDE-DESIGN-PROMPT.md)。

### 7 屏 ⇄ 功能/流程对应

| 屏 | 对应能力（FR / 流程） | handoff 文件 |
|---|---|---|
| **Chat** | 发 prompt + 流式输出 + 工具事件 + 权限模式徽标（FR-4，流程 A/B/D） | `Chat.html` ／ `chat-app.jsx` |
| **Permission** | 远程授权弹窗：工具名 + 命令预览 + 目录/分支 + 倒计时环 + Allow/Deny（FR-5，流程 D） | `Permission.html` ／ `permission-app.jsx` |
| **Sessions** | 会话列表：恢复行（标题/消息数/分支/时间）+ 醒目「新建」（FR-3，流程 A/B） | `Sessions.html` ／ `sessions-app.jsx` |
| **Directory** | 选工作目录：recents + 浏览 + 「N sessions」徽标（FR-2） | `Directory.html` ／ `directory-app.jsx` |
| **Pairing** | 扫码 / 6 位码配对（FR-1，M1） | `Pairing.html` ／ `pairing-app.jsx` |
| **Computers**（即 DaemonPicker） | 多电脑选择 + 在线态（FR-8.3） | `Computers.html` ／ `computers-app.jsx` |
| **Settings** | 默认权限模式（6 种）/ 已配对设备（吊销）/ 外观 / 关于（FR-5.10 / FR-8.2） | `Settings.html` ／ `settings-app.jsx` |

（共享 `ios-frame.jsx` —— iOS 设备外壳，仅 mockup 展示用。）

### M2 如何消费这份设计

- **目标技术是 Compose Multiplatform**，handoff 的 HTML/JSX/Tailwind 是**视觉参照、不是要还原的代码**——把每屏的布局/间距/配色/组件在 Compose 里重建。
- **设备外壳只是 mockup**：handoff 用 iOS 26 外壳（灵动岛/状态栏/Home 条）演示；真身 Android + iOS 共享一套 Compose 内容，**外壳换各平台原生、内容布局两端一致**。
- **复用组件**（在 `commonMain` 落一套）：ConnectionBar（连接条）、SessionCard、DirectoryRow、消息（用户顶格 / 助手 markdown）、CodeBlock、思考折叠块、ToolEventRow、PermissionCard、Composer（输入栏）、ModeBadge（权限模式徽标）、OTP 输入、Segmented（分段控件）、状态占位（空/加载/错误/离线）。
- **本地预览** handoff（React/Babel 走 CDN）：
  ```
  cd docs/design/claude-design-handoff/project/cc-pocket
  python3 -m http.server 8080
  # 浏览器开 http://127.0.0.1:8080/Settings.html（或 Chat/Sessions/Directory/Pairing/Computers/Permission）
  ```

### 关键设计决策（与需求/实现挂钩）

- 用户消息走**文档流顶格 + 「你」标签**（非 IM 双气泡），更像终端日志。
- 权限弹窗**倒计时环**对应 FR-5.4「超时自动 Deny」；危险模式（`bypass`）视觉升级 + 需显式确认（FR-5.10）。
- 切目录在 UI 上**明确告知历史是否延续**（对应需求 OQ-2，默认「新目录开全新会话」）。
- 双主题：以暗色为主、浅色为系统切换备选（规格已含两套 token）。

---

## 里程碑

**M0 —— 局域网 MVP，单机可测（不上云、先不要手机也能验证核心）。** ★ v1 的第一个可运行版本
- 落 `:protocol`（Envelope/Frame/Models/Json）。
- 落 `:daemon` 核心：`ClaudeProcess`（拉起/管道/进程组杀树/过滤 CLAUDECODE）、`StreamParser`、`PermissionBridge`、`SessionRegistry`、`TranscriptScanner`、`DirectoryService`。
- daemon 此阶段直接起一个 Ktor **WS 服务端**在 `127.0.0.1:8765`（还没 relay）；写一个 `daemon test-client` 子命令（或用 Compose Desktop 调试窗复用 `composeApp` 的 commonMain）连上去跑通 A/B/C/D 四条流程。
- **验收**：在一台 Mac/Linux 上，对真实 `claude` 跑通：列目录 → 列 `.jsonl` 会话 → 恢复 → 发 prompt → 看到流式输出 → 新建会话 → 批准一个授权弹窗。**这一步把 80% 的硬骨头（daemon↔claude）在任何云/iOS 工作之前就啃下来。**

**M1 —— 云端 relay + 配对。** daemon 的 WS 从「服务端」翻成「外拨客户端」`RelayClient` 连 `relay`；建 `relay`（Ktor：`/v1/daemon`、`/v1/device`、`/v1/pair`、`broker`）。配对流程：daemon `PairBegin` → relay 发短码（终端里同时渲染成二维码）→ 手机扫码/输码 → relay 绑定设备 token → 两端 `Attach`。relay 用 `docker run` 部署到购买的小云主机。**验收**：两个进程在不同网络下经 relay 互通，A/B/C/D 在「手机不在同一局域网」下成立。

**M2 —— 移动端多会话/多目录 UX。** **界面已设计完成**（见上「设计」节），Compose Multiplatform 据 [`design/claude-design-handoff/`](./design/claude-design-handoff/) **像素级重建** 7 屏：Chat / Permission / Sessions / Directory / Pairing / Computers / Settings + 切目录。**先 Android**（内循环最快），**再尽早起 iOS** target（先在最简单的 Pairing 页验证 iOS 工具链，别留到最后）。**验收**：真机 Android + iOS 都能经 relay 跑通三条流程 + 会话中途切目录，且视觉与 handoff 一致。

**M3 —— 打包 + 后台常驻。** daemon 用 `jpackage`（内嵌 `jlink` 裁剪过的 runtime）出每个 OS 的安装包（~30–50MB；保底方案，GraalVM native-image 作为后续优化）。服务安装器：systemd `--user` unit（Linux）/ Windows Service（WinSW 或 `sc.exe` 包一层）/ launchd plist（macOS），`daemon service-install` 写入并加载。relay 出加固的容器镜像 + healthcheck。**验收**：daemon 注销/重启后存活并自动重连 relay；每个 OS 一条命令安装。

**M4 —— 后台授权推送。** relay 加一个极小的推送扇出：当 `PermissionAsk`（或 `TurnDone`）的目标设备 socket 不在线/空闲时，调 **APNs**（iOS）/**FCM**（Android），payload 极简、不带 prompt 内容（只「Claude 需要授权」+ convoId，隐私）。App 在配对时注册 token；点通知深链到 PermissionSheet。relay 对每个 convo 缓存**唯一一条**未决 `PermissionAsk`，设备重连时重放。**验收**：App 完全退到后台时，两个平台都能批准一次授权。

---

## 反抄袭纪律（写进仓库 `docs/ANTIPLAGIARISM.md`）

**可放心复刻（接口/事实/通用模式，非原创表达）**：所有 Claude CLI flags 及语义、stream-json 事件形状、`control_request/response` 授权协议、`~/.claude/projects/<key>/<sid>.jsonl` 磁盘布局；以及「设 cwd 切目录、进程组干净杀树、过滤 CLAUDECODE、从 `.jsonl` 头部取标题」这些**技法**；以及「设备↔云 relay↔daemon 外拨 WS」这种**通用三层拓扑**。

**必须避免**：照抄 cc-connect 的 Go 字段名/结构体布局/函数切分/文件组织；镜像它内部的事件名（如 `core.Event`/`EventToolUse`/`PermissionResult.Behavior`）—— 我们用自己的（`AssistantChunk`/`ToolEvent`/`PermissionVerdict.Decision`）；逐行把 Go 翻成 Kotlin。**实操原则**：实现时只看本计划的「映射表」+ Anthropic 文档 + 自己跑 `claude --help`，**不一边读 cc-connect 非协议代码一边写**。在 `docs/ANTIPLAGIARISM.md` 留一句溯源声明。法理上：互操作格式受 merger doctrine / Google v. Oracle 保护，真实风险低；不同语言 + clean-room 把残余风险降到最低。

---

## 主要风险与化解

| 风险 | 化解 |
|---|---|
| Windows 进程/PTY | **不用 PTY**，只用 `ProcessBuilder` + 管道（已核实 stream-json 走管道）。杀树用 `taskkill /T` 或 `ProcessHandle.descendants()`。M0 验收在每个 OS 跑「拉起→流式→强杀进程树」。 |
| JVM 打包体积/「要 JRE」 | `jpackage` 内嵌裁剪 runtime（~30–50MB），开发者一次性装后台服务可接受；GraalVM native-image 留作后续优化（不用 PTY、依赖精简后可行）。relay 在服务端，体积无所谓。 |
| iOS Compose 成熟度 | Compose MP iOS 自 2025-05 起 Stable。逻辑全放 commonMain，iOS 特有面尽量薄；个别页有糙点可用 SwiftUI 在同一套 KMP 逻辑上重绘。iOS 尽早起。 |
| claude stream-json schema 漂移 | `ignoreUnknownKeys=true` + 防御式按 `type` 取值；所有 Anthropic schema 知识集中在 `StreamParser`/`PermissionBridge` 两文件，升级一处改。加一条夜间契约测试：跑本机真实 `claude` + 固定 prompt 探漂移。 |
| relay 安全（它在中转远程执行！） | 双向鉴权（长期账号 token + 短时单次配对码、限流）；可选 E2E 加密（配对时 X25519 ECDH，relay 只见 Envelope 路由元数据、不见内容）；relay 不持久化消息体；默认 `DEFAULT` 授权模式，`BYPASS` 需 App 内显式确认；全程 `wss://`；App 内「吊销此设备」使 token 失效。 |

---

## 与 cc-dashboard 的关系

cc-pocket 是**全新独立仓库**，cc-dashboard **一行不改**（满足「现有设计不需适配」「独立组件」）。`:protocol` + `:daemon` 就是用户要的「跨平台基础组件」，天然支持 Linux/Windows/macOS。

可选的未来联动（**不在 v1**）：macOS 上 daemon 与 cc-dashboard 都理解 Claude 会话，将来可让两者共享审批 —— 但本期严格分离。

---

## 验证（如何端到端测）

- **单元/契约**：`StreamParser` 喂固定 stream-json fixtures 断言；`TranscriptScanner` 喂真实 `.jsonl` 样本断言标题/计数；夜间契约测试跑真实 `claude`。
- **M0 手动**：`./gradlew :daemon:run` 起 daemon → `daemon test-client` 跑通 A/B/C/D（对真实 `claude`，本机即可）。
- **M2 移动**：Android 模拟器 + iOS 模拟器经局域网/relay 连 daemon，跑三条流程 + 切目录 + 授权。
- **M1/M4 远程**：relay 部署到云主机，手机切到蜂窝网络验证「不在同一局域网」可用；M4 验证 App 后台时授权推送可达。
- **跨平台杀树**：在 Linux/Windows/macOS 各验证「强杀会话 → claude 及其 MCP 子孙进程全部回收，无 100% CPU 残留」。

---

## 落地顺序（首批要创建的文件）

1. `cc-pocket/protocol/src/commonMain/kotlin/.../proto/Messages.kt` —— 原创 schema（先定线协议）。
2. `cc-pocket/daemon/src/main/kotlin/.../claude/StreamParser.kt` + `PermissionBridge.kt` —— Anthropic schema 的唯一落点。
3. `cc-pocket/daemon/src/main/kotlin/.../claude/ClaudeProcess.kt` —— 拉起/管道/杀树。
4. `cc-pocket/daemon/.../Main.kt` 的 `test-client` + M0 的本机 WS 服务端 —— 打通单机闭环。
5. （M1）`cc-pocket/relay/.../broker/Broker.kt` —— 原创配对/路由核心。
