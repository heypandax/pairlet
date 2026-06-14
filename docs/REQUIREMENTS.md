# cc-pocket 需求文档（Requirements）

> 本文定义 cc-pocket **要做什么**（WHAT，可验收的需求）。
> **怎么做**（架构/协议/落地顺序）见实现计划 [`cc-connect-cc-connect-sequential-graham.md`](./cc-connect-cc-connect-sequential-graham.md)；**界面**见 [`design/`](./design/)（已用 claude.ai/design 产出 7 屏 handoff）。
> 文档状态：v1.1（M0–M3 已实现并发布，M4 推送部分落地；daemon 1.1.0 / app 1.0.1）　｜　初稿 2026-06-05　｜　更新 2026-06-14

---

## 1. 概述

**一句话**：cc-pocket 让开发者用手机操控**自己电脑上正在运行的 Claude Code**——恢复/新建会话、切换工作目录、实时看流式输出、并在手机上批准或拒绝 Claude 的工具授权。

**要解决的问题**：人离开电脑后，电脑上的 Claude Code 会话无法继续推进——尤其是卡在「工具授权弹窗」上时，只能等回到电脑。cc-pocket 把「接管会话 + 远程授权」搬到手机。

**目标用户**：开发者本人（单人多设备），主力在电脑终端/IDE 用 Claude Code，手机作「第二块屏 + 遥控器」。

**典型场景**
- S1：离开工位，手机上批准一个卡住的工具授权弹窗（最高频）。
- S2：通勤途中查看流式输出、追加一句 prompt。
- S3：临时在某个仓库新建会话让 Claude 跑点东西。
- S4：会话进行中把工作目录切到另一个仓库。

---

## 2. 范围

### 2.1 v1 In Scope
- 在 **Linux / Windows / macOS** 电脑上以后台 daemon 驱动本机 `claude` CLI（daemon 代码跨平台；v1 当前仅打包发布 **macOS / Apple Silicon** 的一键安装包，其余平台可自行 `:daemon:installDist` 构建运行）。
- 手机（**Android + iOS**）与电脑 daemon 连接：**先局域网直连**（M0 验证），**再经云端 relay 跨网**（M1+）。
- 会话：列出可恢复会话、恢复、新建。
- 工作目录：浏览/选择/会话中途切换。
- 对话：发送 prompt、接收流式助手输出（文本/思考）、查看工具调用事件、结束态与 token 用量。
- **远程工具授权**：手机处理 Claude 的授权弹窗（Allow/Deny），含超时保护与后台推送。
- 配对与多设备、设备吊销。
- daemon 后台常驻 + 各 OS 安装。

### 2.2 Out of Scope（v1 明确不做）
- 不做 Claude Code 之外的通用远程终端/SSH。
- 不做团队/多用户协作（v1 假设**单账号本人**）。
- 不做对 cc-dashboard 的任何改动或适配（两者严格分离）。
- 不做接入飞书/Slack/Telegram 等聊天平台（那是 cc-connect 的定位，非本项目）。
- 不做 Web 端 App（v1 仅原生移动端）。
- 不做对话历史的云端长期存储（relay 不持久化消息体）。

### 2.3 边界
- **与 cc-connect**：仅作为「`claude` 可被子进程驱动」这一事实的只读参照；本项目 clean-room 重写，线协议原创（见实现计划「反抄袭纪律」）。
- **与 cc-dashboard**：独立新仓库，cc-dashboard 一行不改。

---

## 3. 名词

| 术语 | 含义 |
|---|---|
| **daemon** | 跑在用户电脑上的后台进程，驱动本机 `claude`，对外提供连接 |
| **device / 手机** | 移动端 App（Android/iOS） |
| **relay** | 云端中转，撮合 daemon 与 device 两条连接（解决 NAT 后无法直连） |
| **convo / 会话** | 一次活的对话（对应一个被拉起的 `claude` 子进程） |
| **session（可恢复会话）** | 磁盘上 `~/.claude/projects/**.jsonl` 记录的历史会话，可 `--resume` |
| **workdir / 工作目录** | `claude` 子进程的 cwd |
| **pairing / 配对** | 把一台 device 绑定到用户账号/daemon 的一次性流程 |
| **permission mode** | Claude 的授权模式：`default / acceptEdits / plan / bypassPermissions`（4 档，与 `claude` CLI 取值一致） |

---

## 4. 功能需求

> 标记：优先级 **P0**=v1 必须 ／ **P1**=v1 应当（可顺延 v1.x） ／ **P2**=未来；里程碑 M0–M4 见 §6。
> 关键词 **必须 / 应当**（MUST / SHOULD）。

### FR-1 连接与配对

| ID | 需求 | 优先级 | 里程碑 |
|---|---|---|---|
| FR-1.1 | 局域网内 device **必须**能直连同网段 daemon（无需云）用于开发与自测 | P0 | M0 |
| FR-1.2 | device 与 daemon **必须**能经云端 relay 在**不同网络**（如手机蜂窝网）下互通 | P0 | M1 |
| FR-1.3 | daemon **必须**主动外拨连接 relay（不要求电脑有公网入站），断线后**必须**指数退避自动重连 | P0 | M1 |
| FR-1.4 | 首次使用**必须**支持配对：daemon 申请一次性配对码（**≤120s 失效、单次有效、限流**），device 扫码或输 6 位码完成绑定 | P0 | M1 |
| FR-1.5 | 全局**必须**清晰呈现连接三态：未配对 / 已配对但 daemon 离线 / 在线 | P0 | M2 |

**验收**：两进程在不同网络下经 relay 完成配对并跑通核心流程；配对码过期/重放被拒。

### FR-2 工作目录

| ID | 需求 | 优先级 | 里程碑 |
|---|---|---|---|
| FR-2.1 | **必须**能列出可选工作目录：最近使用（recents）+ 可浏览的目录树 | P0 | M0/M2 |
| FR-2.2 | 列表**必须**标识「该目录是否有可恢复的 Claude 历史会话」（及数量） | P1 | M2 |
| FR-2.3 | **必须**校验所选目录存在、可读、为目录；非法目录给出明确错误 | P0 | M0 |

**验收**：列出含 recents 的目录；对有历史会话的目录显示会话数徽标；选非法目录报错不崩。

### FR-3 会话管理

| ID | 需求 | 优先级 | 里程碑 |
|---|---|---|---|
| FR-3.1 | **必须**能列出某工作目录下的可恢复会话，**且列会话不拉起 `claude`**（读磁盘 `.jsonl`） | P0 | M0/M2 |
| FR-3.2 | 每条会话**必须**展示：标题（优先取 AI 生成标题，否则首条 prompt 截断）、真实用户消息数、git 分支、最近时间 | P0 | M2 |
| FR-3.3 | 消息数**必须**只计真实用户轮，**排除**工具结果回合 | P1 | M0 |
| FR-3.4 | **必须**能恢复指定会话（携带其 sessionId 拉起，cwd=该目录） | P0 | M0/M2 |
| FR-3.5 | **必须**能在某目录新建会话；首轮拿到的 sessionId **必须**回填给手机 | P0 | M0/M2 |
| FR-3.6 | 会话列表**必须**有醒目的「新建会话」入口 | P0 | M2 |

**验收**：对真实 `~/.claude/projects` 列出会话、标题/计数正确（计数不含工具结果）；恢复后能继续对话；新建后 sessionId 正确回填。

### FR-4 对话与流式输出

| ID | 需求 | 优先级 | 里程碑 |
|---|---|---|---|
| FR-4.1 | **必须**能向活会话发送文本 prompt | P0 | M0/M2 |
| FR-4.2 | **必须**实时流式接收助手输出（**文本**与**思考**分别可辨） | P0 | M0/M2 |
| FR-4.3 | **必须**呈现工具调用事件（工具名 + 输入预览 + 运行/成功/失败状态） | P1 | M2 |
| FR-4.4 | 一轮结束**必须**给出结束态，并在有数据时展示 token 用量（输入/输出） | P1 | M2 |
| FR-4.5 | **应当**能中断当前生成（stop） | P1 | M2 |
| FR-4.6 | 助手 markdown（含代码块）**应当**正确渲染、代码可复制 | P1 | M2 |
| FR-4.7 | 一个活会话**必须**支持多轮（同一子进程跨轮，不每轮重启） | P0 | M0 |

**验收**：发 prompt 后看到逐字流式输出 + 工具事件 + 结束 token；连续两轮在同一会话上下文内。

### FR-5 远程工具授权（核心安全能力）

| ID | 需求 | 优先级 | 里程碑 |
|---|---|---|---|
| FR-5.1 | Claude 请求使用工具时，daemon **必须**把授权请求转成弹窗推给手机 | P0 | M0/M2 |
| FR-5.2 | 用户在手机点 **Allow/Deny**，结果**必须**回传并真正放行/拒绝该工具调用 | P0 | M0/M2 |
| FR-5.3 | 授权请求**必须**展示：工具名、输入预览（命令/路径/diff 摘要）、目标目录与分支 | P0 | M2 |
| FR-5.4 | 裁决**必须**有超时保护：超时**自动 Deny**（安全优先），不得让工具调用永久挂起 | P0 | M0 |
| FR-5.5 | `bypassPermissions` 模式下 daemon **应当**本地直接放行，不打扰手机 | P1 | M0 |
| FR-5.6 | Claude 撤回某授权请求时，手机端对应弹窗**必须**自动消失 | P1 | M2 |
| FR-5.7 | App 退到后台/不在线时，未决授权**必须**通过推送（APNs/FCM）触达；点击深链到授权页 | P0 | M4 |
| FR-5.8 | 推送 payload **必须**不含 prompt 内容（仅「需要授权」+ 会话标识，隐私） | P0 | M4 |
| FR-5.9 | 每个会话的未决授权 relay **应当**仅缓存最新一条，设备重连时重放 | P1 | M4 |
| FR-5.10 | 权限模式**必须**支持 4 个真实取值（`default/acceptEdits/plan/bypassPermissions`）；`bypassPermissions` 高风险模式**必须**有 App 内显式确认 | P0 | M2 |

**验收**：default 模式下手机收到弹窗→Allow 后工具执行、Deny 后被拒；不答复约 30s 自动 Deny；auto 模式不弹窗；App 后台时推送可达并能批准。

### FR-6 会话中途切换工作目录

| ID | 需求 | 优先级 | 里程碑 |
|---|---|---|---|
| FR-6.1 | **必须**能在活会话中切换工作目录：daemon 干净杀掉当前进程、用新 cwd 重启 | P0 | M0/M2 |
| FR-6.2 | 切目录的会话语义**必须**明确并告知用户（见 §10 待确认 OQ-2：默认「新目录开全新会话」） | P0 | M2 |

**验收**：切目录后新子进程 cwd 正确、旧进程树被回收；UI 明确提示历史是否延续。

### FR-7 进程与生命周期（daemon 内）

| ID | 需求 | 优先级 | 里程碑 |
|---|---|---|---|
| FR-7.1 | daemon **必须**能干净杀掉 `claude` 及其全部子孙进程（含 MCP server），**无孤儿、无 100% CPU 残留**，三个 OS 均成立 | P0 | M0 |
| FR-7.2 | daemon **必须**能在用户本机定位真实 `claude` 可执行文件并以子进程拉起（不经会污染输出的 shell 包装） | P0 | M0 |
| FR-7.3 | 拉起子进程**必须**从环境中过滤掉会导致嵌套会话误判的变量（`CLAUDECODE`） | P0 | M0 |
| FR-7.4 | daemon **应当**支持随系统后台常驻（systemd --user / Windows Service / launchd），注销/重启后存活并自动重连 | P1 | M3 |
| FR-7.5 | daemon **应当**提供 CLI 子命令（如 `run / pair / service-install`）便于运维 | P1 | M3 |

**验收**：在 Linux/Windows/macOS 各跑「拉起→流式→强杀进程树」无残留；一条命令完成安装；重启后自动恢复连接。

### FR-8 多设备与设备管理

| ID | 需求 | 优先级 | 里程碑 |
|---|---|---|---|
| FR-8.1 | 一个账号**应当**可配对多台 device | P1 | M1 |
| FR-8.2 | **必须**能在 App 内吊销某台设备，使其 token 立即失效 | P0 | M1 |
| FR-8.3 | 多台电脑（多 daemon）时**应当**能在手机选择要操控的那台，并显示在线状态 | P1 | M2 |

**验收**：吊销后该设备无法再连；多 daemon 时可选择且离线机置灰。

---

## 5. 非功能需求（NFR）

| ID | 类别 | 需求 | 优先级 |
|---|---|---|---|
| NFR-1 | 跨平台 | daemon **必须**在 Linux / Windows / macOS 上行为一致（不依赖 PTY，仅用管道） | P0 |
| NFR-2 | 跨平台 | 移动端**必须** Android + iOS 共用一套 Compose Multiplatform 逻辑 | P0 |
| NFR-3 | 安全 | 全程 `wss://`；**必须**双向鉴权（长期账号 token + 短时单次配对码、限流） | P0 |
| NFR-4 | 安全 | relay 在中转「远程执行」，**必须**默认最小权限：默认 `default` 授权模式，高风险模式需显式确认 | P0 |
| NFR-5 | 隐私 | relay **必须不持久化**消息体；推送 payload 不含 prompt 内容；端上凭证存安全区（Keychain/EncryptedSharedPrefs） | P0 |
| NFR-6 | 隐私（可选） | **应当**支持端到端加密（配对时 X25519 ECDH，relay 只见路由元数据不见内容） | P1 |
| NFR-7 | 可靠性 | 断网/切网**必须**静默重连且不丢已收消息；授权超时**必须**安全兜底（Deny） | P0 |
| NFR-8 | 性能 | 首字节流式延迟**应当**接近本机（局域网 < 300ms，relay 视网络）；长输出不卡顿 | P1 |
| NFR-9 | 可维护 | 所有 Anthropic `stream-json` schema 知识**必须**集中在 daemon 两个文件（StreamParser/PermissionBridge），用 `ignoreUnknownKeys` + 防御式取值抗漂移；**应当**有夜间契约测试跑真实 `claude` 探漂移 | P0 |
| NFR-10 | 打包 | daemon **应当**每个 OS 出一键安装包（jpackage 内嵌裁剪 runtime，~30–50MB；GraalVM native 留作优化） | P1 |
| NFR-11 | 合规 | 仓库自带 **MIT**；**必须** clean-room：不照抄 cc-connect 的字段名/结构/文件组织，留溯源声明 `docs/ANTIPLAGIARISM.md` | P0 |
| NFR-12 | 兼容 | **必须**与用户本机已安装的 `claude` CLI 互操作；对其 flags / stream-json / `.jsonl` 磁盘格式做防御式适配 | P0 |

---

## 6. 里程碑（需求 → 发布阶段）

| 里程碑 | 主题 | 交付的需求 | 验收要点 |
|---|---|---|---|
| **M0** | 局域网 MVP（单机可测，先不需手机） | FR-2/3/4/5（daemon 侧）/6/7 + NFR-1/9/12 | 单机对真实 `claude` 跑通：列目录→列会话→恢复→流式→新建→批准一次授权；强杀无残留 |
| **M1** | 云端 relay + 配对 | FR-1.2/1.3/1.4 + FR-8.1/8.2 + NFR-3/4/5 | 两端跨网经 relay 跑通核心流程；配对码安全 |
| **M2** | 移动端多会话/多目录 UX | FR-1.5/2.2/3.2/3.6/4.3/4.4/5.3/5.6/5.10/6.2/8.3 | 真机 Android + iOS 经 relay 跑通三流程 + 切目录 + 授权 |
| **M3** | 打包 + 后台常驻 | FR-7.4/7.5 + NFR-10 | daemon 注销/重启后存活并自动重连；每 OS 一条命令安装 |
| **M4** | 后台授权推送 | FR-5.7/5.8/5.9 | App 完全退后台时两平台都能批准一次授权 |

> 设计已就绪：7 屏（Chat / Permission / Sessions / Directory / Pairing / Computers / Settings）见 `design/claude-design-handoff/`，M2 据此实现。

### 6.1 实现现状（2026-06-14）

四个模块均已落地并发布：daemon 经 Homebrew cask 分发（当前 **daemon 1.1.0**），移动端 Android/iOS 同步发版（当前 **app 1.0.1**——daemon 与 app 是两条独立的版本线）。M0–M3 主体已实现，M4 后台推送仅部分落地（见下）。

| 里程碑 | 状态 | 代码落点（节选） |
|---|---|---|
| **M0** 局域网 MVP | ✅ 已实现 | `daemon/`：`claude/`（ClaudeLauncher/ClaudeProcess/StreamParser/StreamWire/PermissionBridge）、`conversation/`、`disk/`（TranscriptScanner/DirectoryService/SlashCommandScanner）、本机 WS（`--local`）；`./gradlew :daemon:run` + `test-client` 可跑通 |
| **M1** 云端 relay + 配对 | ✅ 已实现 | `relay/`：`Broker`、`auth/`（daemon Ed25519 签名挑战 + device bearer 凭据）、`pairing/`（一次性票 + 6 位码 + 限流）、`store/`（SQLite/InMemory）；daemon `RelayClient` 主动外拨 + 退避重连 |
| **M2** 移动端多会话/多目录 | ✅ 已实现 | `mobile/composeApp`：5 屏（Pairing/Connect/Directory/Sessions/Chat）+ 弹窗（Permission/Mode/Settings）；`data/PocketRepository`、`net/`（relay E2E + LAN 直连）、`pairing/`、`secure/`；Android · iOS · desktop 共用 commonMain |
| **M3** 打包 + 后台常驻 | ✅ 已实现 | daemon `service/ServiceInstaller`（macOS launchd）+ `Main` 子命令（`run/pair/test-client/service-install`）；jpackage 自带 JRE（`:daemon:packageDaemon`）、签名+公证（`scripts/release-macos.sh`）、Homebrew cask 一键安装；relay `deploy/`（Caddy + systemd + Cloudflare） |
| **M4** 后台授权推送 | 🟡 部分 | relay 侧 `PushProvider` 接口已就位（当前为 `LoggingPushProvider` 桩，**尚未接真实 APNs/FCM 投递**）；客户端目前**不依赖推送**，而是 App 回到前台时自动重连（指数退避）+ **turn-state 同步**（`SessionLive.executing`）补齐状态，避免漏审/按钮卡死 |

> **超出原计划的增强**（已实现，需求初稿未列）：
> - **端到端加密默认启用**（`protocol/e2e/`：P-256 ECDH + HKDF + AES-256-GCM，relay 零知识只转密文）——原 NFR-6 / OQ-3 的「P1 可选」提前转正为 v1 默认。
> - **语音输入**：iOS 系统级实时听写（`SFSpeechRecognizer`，首选语言自适应、CJK 友好）；Android／桌面录音回传 Mac、由 whisper-cpp 本地转写（daemon `transcribe/`）。
> - **图片附件**：单条消息最多 4 张，端上压缩到 256 KiB 帧上限内。
> - **斜杠命令补全**：daemon 扫描 4 个来源（内置／用户 `~/.claude/commands`／项目 `.claude/commands`／skill），App 输入 `/` 自动补全。
> - **会话内动态切换**：切权限模式、`/model` 切模型（重启子进程但保留「本会话总是允许」规则）。
> - **观察模式**：只读旁观电脑终端里正在跑的 `claude` 会话，点「Continue here」接管为手机控制。
> - **思考块 + token 用量**：extended thinking 实时流式（可折叠），结束态含缓存命中 token。
> - **打断与排队**：会话进行中可中断（`CancelTurn`）；忙碌时追加消息自动排队，按回合依次送入。
> - **桌面接力**：会话回收后在电脑 `claude --resume` 原地续接。
> - **中文本地化**：App 内置 en/zh 两套文案。**匿名遥测**：客户端 Firebase 仅上报枚举级元数据（AppLaunch/Paired/Connected…），不含 prompt／会话内容。

---

## 7. 端到端验收（核心流程，对真实 `claude`）

- **A 恢复会话**：列目录 → 列 `.jsonl` 会话 → 选会话恢复 → 发 prompt → 看到流式输出 → 结束态。
- **B 新建会话**：选目录新建 → sessionId 回填 → 对话。
- **C 切目录**：会话中切到新目录 → 旧进程树回收 → 新 cwd 生效。
- **D 远程授权**：触发工具 → 手机收弹窗 → Allow/Deny 生效；超时自动 Deny；后台经推送可批准。
- **跨平台杀树**：Linux/Windows/macOS 各验证强杀无孤儿、无 100% CPU。

---

## 8. 约束与假设

- **技术栈锁定**：全 Kotlin（daemon/relay/mobile 共享一个 `kotlinx.serialization` 协议模块）；移动端 Compose Multiplatform。
- **用户本机已安装并可登录 `claude` CLI**（cc-pocket 不负责安装/登录 Claude）。
- **可购买云主机**用于部署 relay。
- **单账号本人**使用（非多租户/团队）。
- 产物为**全新独立 MIT 仓库**，与 cc-dashboard 平级、互不影响。

---

## 9. 风险（需求视角，详见实现计划）

| 风险 | 对需求的影响 | 化解方向 |
|---|---|---|
| Windows 进程/杀树差异 | FR-7.1 可能在 Windows 留残留 | 不用 PTY；`ProcessHandle.descendants()` + `taskkill /T`；M0 三 OS 验收 |
| `claude` stream-json 漂移 | FR-4/5 可能解析失败 | NFR-9：schema 集中 + 防御式 + 夜间契约测试 |
| relay 安全（中转远程执行） | NFR-3/4/5 是硬要求 | 双向鉴权 + 最小权限 + 不持久化 + 可选 E2E |
| iOS Compose 成熟度 | FR/NFR-2 的 iOS 面 | 逻辑全 commonMain，糙点可 SwiftUI 重绘；iOS 尽早起 |
| JVM 打包体积 | NFR-10 体验 | jpackage 内嵌裁剪 runtime；后续 GraalVM |

---

## 10. 待确认（Open Questions）

| ID | 问题 | 现状/建议默认 |
|---|---|---|
| OQ-1 | 产品最终命名（现工作名 `cc-pocket`） | ✅ 已定：产品名 **CC Pocket**，仓库 / 包名保留 `cc-pocket`（线协议 `pocket/*` 不变） |
| OQ-2 | 会话中切目录的语义：新目录**开全新会话** vs `--resume` 续接同一会话 | 建议 v1 默认「新会话」（最稳，去掉版本相关不确定性），续接作为开关 |
| OQ-3 | E2E 加密是否纳入 v1 | ✅ 已定：E2E **纳入 v1 并默认开启**（P-256 ECDH + HKDF + AES-256-GCM，relay 零知识只转密文），见 §6.1 |
| OQ-4 | relay 的账号体系（如何标识「同一用户」并撮合） | 待定：邮箱/第三方登录/纯设备绑定；影响 FR-1/8 |
| OQ-5 | 是否在 v1 暴露「选择 model」与「权限模式」给用户 | 权限模式已纳入（FR-5.10）；model 选择建议 P1 |
| OQ-6 | 多电脑（多 daemon）是否 v1 必须 | 建议 P1（FR-8.3），v1 先单 daemon 体验 |
| OQ-7 | 是否需要本地（无 relay）长期可用模式 | M0 局域网直连用于自测；是否作为正式特性保留待定 |

---

## 附：相关文档
- 实现计划（HOW）：[`cc-connect-cc-connect-sequential-graham.md`](./cc-connect-cc-connect-sequential-graham.md)
- 界面设计（已产出）：[`design/`](./design/)（`README.md` / `UI-DESIGN.md` / `claude-design-handoff/`）
- 反抄袭声明（待建）：`docs/ANTIPLAGIARISM.md`
