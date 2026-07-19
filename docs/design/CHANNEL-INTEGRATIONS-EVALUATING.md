# 协作与渠道集成——官方实现机制调研与 cc-pocket 机会分析

> ## ⚠️ 状态：评估中（未定案）
>
> **这是调研与机会分析，不是决策，也不是排期。** 第四章那五条候选均为**方向层草稿**——尚未录入 GitHub 台账、未评估成本、未确定要不要做。文中对官方机制的描述基于 2026-07-18 的公开信息与实测，其中 Claude Channels 处于研究预览，接口随时可能漂移。
>
> 引用本文前请注意：**任何一条都不构成「已确认要实现」的依据**。定案后应把结论录成 issue，并把本文改名去掉 `-EVALUATING` 后缀。

> 调研日期：2026-07-18。对象：Anthropic Claude Code（Channels / Slack / Claude Tag）与 OpenAI Codex（Slack / Linear / GitHub）。
> 目的：为「cc-pocket 打通企业内部接口」这一方向提供机制层参照，供挑选录入 GitHub 台账。
> 背景结论（同日全景对比）：在「手机远程驱动本机 CLI」核心形态上 cc-pocket 与官方已基本打平；真正差距在「云执行」与「生态集成」。云沙箱是战略级命题另议，**渠道集成是官方留下的结构性缝隙**——本文只展开这一条。

---

## 一、官方三种架构范式

### ① Claude Channels——事件推入本地会话（与 cc-pocket 同象限）

研究预览状态。channel 本质是一个**本地 MCP server**：

- **接入契约**：MCP server 在 capabilities 里声明 `experimental: {'claude/channel': {}}`，Claude Code 即把它 spawn 为 stdio 子进程并注册 notification listener。唯一硬依赖 `@modelcontextprotocol/sdk`。
- **入站**：server 调 `notifications/claude/channel`，`content` 为事件体，`meta` 的每个 key 变成标签属性。事件以 `<channel source="telegram" chat_id="...">正文</channel>` 形态注入**用户已开着的那个会话**（`claude --channels plugin:telegram@...` 启动时挂载）。
- **回复**：就是普通 MCP tool（如 `reply(chat_id, text)`），靠 server 的 `instructions`（进 system prompt）教模型收到 `<channel>` 后调哪个工具、回传哪个属性。
- **排队语义**：忙时事件排队，下一轮**打包**送达、按组处理；要并行处理独立事件流须开多个会话。
- **平台接法**：Telegram/Discord 为**用户自建 bot、自填 token**，本地轮询 / gateway WebSocket，**无需公网回调**；iMessage 无 bot——直接读 `~/Library/Messages/chat.db`（要 Full Disk Access）＋ AppleScript 控制 Messages.app 发回复，仅 macOS。
- **关键弱点**：无 ACK、无离线队列、无持久化——**会话没开着，消息永久丢**；投递确认要 server 自己记状态。always-on 得用户自己把 claude 挂后台。无会话管理（消息只进「那一个」会话）。

**渠道内审批 relay（最精细的部分，可直接抄的协议设计）**：

- channel 另声明 `experimental['claude/channel/permission']` 即 opt-in；覆盖 Bash/Write/Edit 等工具审批（project trust 与 MCP consent 不 relay）。
- 审批请求四字段：`request_id`（**五个小写字母，字母表排除 `l` 防手机误认 1/I**）、`tool_name`、`description`、`input_preview`。
- 远端回 `yes abcde` / `no abcde` 即裁决；格式不匹配的回复 fall through 当普通聊天消息。
- **双通道竞速**：本地终端对话框全程保持打开，先到的答案生效、另一边关闭；ID 不匹配的 verdict 静默丢弃；单次生效。
- **注入防护**（v2.1.211+）：relay 前对 description/input_preview 消毒——中和方向覆盖/不可见字符、引号尖括号形近字，空白折叠，3500 code points 上限超长中间省略。文档明说两字段按不可信对待。
- **审批权与信任绑定**：能通过渠道回话的人＝能批准工具执行，所以 sender allowlist 同时 gate 审批权。

**信任模型（官方铁律，与 cc-pocket 现状差异最大的一条）**：

- 每个插件维护 **sender allowlist**，不在名单的发送者静默丢弃。
- bootstrap 用配对码：陌生人 DM bot → bot 回配对码 → 主人在会话里 `/telegram:access pair <code>` 确认 → 该发送者的**平台数字 ID** 入名单。
- 原文两条：「**gate on sender ID, not chat/room ID**」（群聊里 gate room 会让群里任何人注入）；「**An ungated channel is a prompt injection vector**」。
- 企业管控：managed settings `channelsEnabled` 总开关 + `allowedChannelPlugins` 白名单；每会话仍需用户主动 `--channels` opt-in。

### ② Claude Slack / Claude Tag——云会话＋两种身份模型

- **Claude in Slack**（正被 Claude Tag 取代）：@提及 → 意图检测 → 以**提及者本人** claude.ai 账号在 claude.ai/code 新开云会话，吃本人限额、只能碰本人连过的 repo；进度回帖 thread，完成给 "View Session / Create PR" 按钮。DM 不支持。带警告：上下文取自 thread/频道近期消息，「Claude may follow directions from other messages in the context」——频道历史即注入面。
- **Claude Tag**（Team/Enterprise，public beta）：**身份倒置**——@Claude 是组织 provision 的 **agent 自有服务账号**（Slack 里是 app、GitHub 上是 Claude GitHub App），PR 作者是 app 不是人。
  - 会话模型：**一个 Slack thread ＝ 一个会话 ＝ 一个沙箱**；idle 即回收、thread 持久、新回复重建沙箱续跑（未推出去的文件不保留）。thread 内**任何人**回帖即可 steer，无需再 @。
  - **凭证不进沙箱**（核心机制）：密钥存 credential store，出站请求经 **Agent Proxy**（沙箱与外界的网络边界）时才注入——「the model and the sandbox itself are not given the key」。仅 HTTP/HTTPS 能过 proxy。
  - 计费：channel 工作从组织 usage balance 扣（非 per-seat），DM 回落个人账号。

### ③ Codex Slack / Linear——入口薄、执行厚

- **硬前置**：付费计划 ＋ 已连 GitHub ＋ 至少一个 cloud environment，三缺一不工作。渠道只做「身份映射＋上下文收集＋环境推断＋回链」，**执行全部收敛到 Codex cloud**。
- **路由是推断式三级**：prompt 显式指定 repo → 自动在发起人可见环境里匹配 → 回落最近使用。**无频道↔环境绑定机制**，社区大量吐槽选错 repo，官方 workaround 是「prompt 里写死」。
- **产出不落渠道**：Slack 里只回「chat 链接（＋可关的内联答案）」，Linear 里回「摘要评论＋链接」，diff 与 PR 都要人去 Codex web 签发。
- **Linear 机制**（走 Linear 公开 Agent 平台，Codex 只是其一个实现）：
  - Codex 是 Linear 的 "app user"（agent 账号）；assign 给 agent 时是 **delegate 而非 assignee**（人保留 ownership）。
  - 协议：delegation/mention 触发 `AgentSessionEvent` webhook；agent 须 **10 秒内发 `thought` activity 确认接单**；活动类型 `thought / action / response / elicitation / error`；进度回写 issue Activity 区；Linear inbox 通知「需要输入 / 可以 review」。
  - **Triage 自动派单**：Linear 原生规则可把 triage issue 自动路由给 Codex，quota 归 **issue 创建者**。
  - 该平台**任何第三方可接**（OAuth `actor=app`）——想做「派单给自家 agent」完全可行。
- **封闭性**：无公开 cloud tasks API/webhook，第三方无法自建 Slack 式渠道；唯一脚本化入口是 `codex cloud exec` CLI（可 `--attempts 1-4` best-of-N）。SDK 只控制**本地** app-server。
- 已知短板：审批在渠道内如何交互文档未说明；静默失败开放 issue（delegation 建了 session 却无任何产出无报错，openai/codex#26898）；自家护栏把已批准的 Slack 发送判为 unacceptable risk（#30744）。

---

## 二、cc-pocket Bridge 现状对照

| 维度 | Claude Channels | Claude Tag | Codex Slack/Linear | cc-pocket Bridge（1.4.0 预览） |
|---|---|---|---|---|
| 执行位置 | 本机（用户开着的会话） | Anthropic 云沙箱 | Codex cloud | **本机 daemon，内网可达** |
| 常驻性 | 无——会话关了消息丢 | thread 持久＋沙箱重建 | 云任务 | **daemon 常驻＋resume** |
| 会话映射 | 单会话注入 | thread=会话=沙箱 | 任务制 | `chat_id:root_id → session`（同构于 Tag） |
| 路由 | — | — | 推断式（易错） | **`/bind` 显式绑定**（更优，不动） |
| 信任 gate | **sender allowlist＋配对码** | 组织 access bundle | workspace 成员＋本人前置 | **群成员即边界（gate 在 chat）← 差距** |
| 渠道内审批 | **relay 协议（竞速＋消毒）** | 文档未说明 | 文档未说明 | 不支持——审批只路由 owner 手机（urgent 推送） |
| AskUserQuestion | 非交互模式下禁用 | thread 内任何人可 steer | elicitation 类型存在 | **群友答不了，只弹 owner ← 差距** |
| 凭证边界 | token 本地 .env | **Agent Proxy 注入、不进沙箱** | secrets 只给 setup 阶段 | E2E 覆盖 bridge↔daemon；回帖明文过 IM 云端（已在 README 诚实边界） |
| 开放契约 | **开放**（MCP 两个 capability） | 封闭 | **封闭** | **开放**（bridge 凭证＋wire；InProcessBridgeEngine 内置路线） |
| 国内 IM | 永远不会做 | 同 | 同 | **飞书内置引擎已落地** |

cc-pocket 已做对的：显式绑定、常驻可靠性（对比 Channels 的丢消息）、thread→session 映射、开放契约、双路线（内置引擎＋外部适配器共用 BridgeGuard）。

---

## 三、结构性空间（为什么这块是我们的）

1. **执行面够不到内网**：Codex 渠道强制云执行（GitHub＋environment 硬前置），Claude Slack/Tag 也是云沙箱。内网 GitLab、内部 API、飞书项目这类资源云沙箱摸不着。cc-pocket 本机/内网执行是**结构性**优势。
2. **渠道覆盖不会碰国内 IM**：官方只有 Slack/Linear/Telegram/Discord/iMessage；飞书、企微、钉钉大概率永不在路线图。且 Channels 机制（自建 bot＋长连接＋本地轮询、无需公网回调）证明这条路技术上就是 FeishuEngine 已经在走的路。
3. **离线可靠性官方是空白**：Channels 无队列会话关了即丢；Codex 有静默失败开放 issue。daemon 常驻＋resume＋urgent 推送已更可靠。
4. 附带：官方远程功能一律强制订阅登录、禁第三方网关——企业自托管/网关用户官方路线直接没得用。

---

## 四、候选台账条目（方向层草稿，挑着录）

> 每条已按 record-issue 三段结构预写要点；录入时保持方向层、不细化实现。

### ① sender allowlist＋配对码——定掉 /bind 信任模型悬案
- **命题**：bridge 现在 gate 在 chat（「谁在群里谁就能驱动」），官方铁律是 gate 在 sender ID——「An ungated channel is a prompt injection vector」。这同时是飞书 bridge backlog 里「/bind 群主兜底信任模型」待定决策的答案。
- **方向**：per-sender allowlist（open_id 粒度）＋配对码 bootstrap（陌生人得码→owner 确认入名单）；allowlist 同时约束后续更高权能（审批、答题）。
- **边界**：不改 workdir 白名单与 BridgeGuard 既有闸；默认行为兼容现有已绑群（可全员档位向后兼容）；管理入口细节留方案阶段。

### ② 渠道内审批 tier——可信 sender 在群里批准低危操作
- **命题**：审批目前只路由 owner 手机；官方 Channels 证明「allowlist sender 渠道内批准」是成立的中间档（request_id 竞速＋字段消毒＋单次生效的协议设计可参照）。
- **方向**：作为 bridge 的可选 tier，默认仍 owner-only；开启后 owner 手机与渠道双通道竞速，先到生效；渠道展示字段须消毒（方向覆盖字符/形近字/长度上限）。
- **边界**：危险档（rm/force-push 类）永远只归 owner；依赖条目①的 allowlist，未定 allowlist 前不开工；不改 PermissionBridge 对 App 侧的既有语义。

### ③ AskUserQuestion 开放给 allowlist sender 群内作答
- **命题**：模型选择题现在只弹 owner 手机，群友答不了、owner 不答超时按未回答——群协作场景的真缺口。官方 Claude Tag thread 内任何人可 steer。
- **方向**：allowlist sender 可在群内以回帖答 AskUserQuestion（选项渲染成渠道消息，回帖匹配选项）；owner 手机保留竞速答题权。
- **边界**：仅 AskUserQuestion，不含权限审批（那是条目②）；非 allowlist 成员回帖不触发；超时语义不变。

### ④ 企业内部系统适配器——飞书项目（Meegle）派单形态
- **命题**：Linear delegate 范式（issue 派给 agent、10 秒接单确认、进度回写 Activity、完成回摘要+链接、triage 自动派单）是被验证的交互形态；官方永远不会接飞书项目/内网系统，而这正是本机执行的主场。
- **方向**：以 InProcessBridgeEngine/外部适配器契约接飞书项目——issue 指派触发 cc-pocket 会话，进度与产出回写工作项；triage 规则自动派单后置。
- **边界**：先单一系统（飞书项目）打样，不做通用「工单平台抽象」；回写只做评论/状态两类，不碰字段体系；quota/并发沿用 bridge 凭证既有 max-sessions。

### ⑤ 内网凭证注入——daemon 侧出站代理（远期）
- **命题**：Claude Tag 的「凭证不进沙箱、Agent Proxy 出站注入」是企业安全叙事核心；cc-pocket 接企业内部 API 时同样不该让 agent 直接持有 token。
- **方向**：内部接口凭证存 daemon 侧配置，出站时注入，不进会话上下文。
- **边界**：远期项，依赖条目④出现真实需求后再立案；不做通用 secrets 管理平台。

---

## 五、来源

- Claude：code.claude.com/docs/en/channels.md、channels-reference.md、slack.md、mobile.md、remote-control.md、feature-availability.md；claude.com/docs/claude-tag/*（overview / how-it-works / agent-identity）；官方插件源码 github.com/anthropics/claude-plugins-official（external_plugins：telegram/discord/imessage）
- Codex：learn.chatgpt.com/docs/third-party/{slack,linear,github}、cloud、codex-sdk、cli、pricing、enterprise/admin-setup（developers.openai.com/codex/* 已 308 并入）；linear.app/developers/agents、linear.app/changelog/2025-12-04-openai-codex-agent；openai/codex issues #26898、#30744
- 第三方：claudefa.st channels guide、github.com/Masashi-Ono0611/claude-channels-telegram-mcp（契约开放性佐证）
- cc-pocket 现状：examples/feishu-bridge/README.md、daemon/.../bridge/InProcessBridgeEngine.kt、daemon/.../feishu/FeishuEngine.kt；飞书 bridge 评审 backlog（0717，两决策待定其一即条目①）
