# Dot 会话与执行进度接入方案

> 2026-10-06 状态补记：通用只读观察（P1）已合入本地 main。用一个真实 Dot 任务核对后确认，这类任务在本机不留会话记录，读本机文件看不到它；Dot 接入暂缓，见[案例集 D7](../DECLINED-REQUIREMENTS.md)。

状态：P0 探测与 P1 通用只读基础已实施（2026-10-05，分支 `feat/dots-session-observation`）；真实 Dot 样例验收待完成，P2 未实施。证据与差异见第 10 节。

本方案中的 DOTS 指 OpenAI 的 Dot。若实际产品不同，先修正数据源部分，不照搬接口。

## 1. 要解决的问题与交付边界

用户希望在 Pairlet 的项目里看到 Dot 正在执行哪些任务、对应哪些会话、最新做到了哪里，以及是否需要回到原应用处理。

目标体验：用户导入一条 Dot 创建的本地 Codex 会话后，能在手机和桌面持续查看消息、工具记录、最近活动与有依据的回合状态；打开和重连均不会接管 Dot 正在驱动的会话。

第一阶段交付 **本地 Codex 子会话的只读观察**。它只能说明子会话执行情况，不能声称已接入 Dot 主会话、全部后台 Agent 或云端任务。云端和主任务的阶段性信息可在 P2 通过主动上报补充；完整读取既有 Dot 会话需要另外验证官方接口。

实现原则：

- 本地 Codex 子会话继续使用 `AgentKind.CODEX`；Dot 是任务来源或上层协调者。不要为了显示来源新增一个能启动进程的 `DotsBackend`。
- 保留现有“发现 → 用户导入 → 已管理列表”的语义，不把所有外部会话自动塞进项目。
- 查看、控制、数据新鲜度分别建模。读到历史不代表能够发送消息；读到工具活动不代表整个任务已经完成。
- 复用现有历史分页和设备加密传输；P1 不需要 relay 业务变更，不需要读取 ChatGPT 登录凭据。
- 本次交接不包含发版、生产部署或向其他人发送消息。实施时遵循当次用户授权和仓库规则。

## 2. 已核实事实与尚待验证的条件

### 2.1 产品与接口事实

| 事实 | 对实现的含义 | 依据 |
|---|---|---|
| Dot 可以创建或继续本地 Codex 任务，也可以创建云端任务 | 必须按执行位置区分数据源；“能访问本机”不等于“全部历史保存在本机” | [Tasks and memory](https://learn.chatgpt.com/docs/dots/tasks-and-memory) |
| Dot 可使用账户支持的插件，本地技能依赖已连接的电脑 | 主动上报值得做连通性探测，但不能先假定自建插件可在该账户和每一种执行环境调用 | [Computers and apps](https://learn.chatgpt.com/docs/dots/computers-and-apps) |
| 云端编排与本地执行分离；相关场景不支持本地配置／插件中的普通 hooks，企业远程 MCP hooks 有另行限制 | 不把本地 `SessionEnd` hook 或 OTel 当作 Dot 全量生命周期事件源 | [Local computer access](https://learn.chatgpt.com/docs/enterprise/cloud-local-access) |
| Agents API 支持 API 会话的事件流、历史读取和状态查询 | 这是未来接入的候选机制，不能据此宣称可读取用户在 ChatGPT 中已有的 Dot 会话 | [Agents API sessions](https://developers.openai.com/api/docs/guides/agents-api/sessions) |

上述官方资料于本轮调研核对。实施前回读相关条目；产品更新时以实际可调用能力为准。尚未找到“第三方枚举当前账户所有既有 Dot 会话并订阅其进展”的已验证接口。

### 2.2 当前仓库的接入点

下表为本轮查看源码所得；历史设计文档不能替代这些实现。

| 当前能力／限制 | 源码入口 |
|---|---|
| 扫描 `$CODEX_HOME/sessions`，默认 `~/.codex/sessions`；按逻辑 session ID 合并 resume 文件 | [CodexPaths.kt](../../daemon/src/main/kotlin/dev/ccpocket/daemon/codex/CodexPaths.kt) |
| 按 canonical cwd 匹配项目；普通摘要要求识别到真实用户输入；`live` 目前使用最近 20 秒文件修改时间 | [CodexTranscriptScanner.kt](../../daemon/src/main/kotlin/dev/ccpocket/daemon/codex/CodexTranscriptScanner.kt) |
| 历史回放、增量游标、旧消息分页 | [CodexTranscriptReplay.kt](../../daemon/src/main/kotlin/dev/ccpocket/daemon/codex/CodexTranscriptReplay.kt)、[ReplaySlice.kt](../../daemon/src/main/kotlin/dev/ccpocket/daemon/disk/ReplaySlice.kt) |
| 观察外部会话：约 1.5 秒检查文件变化，发送历史和 `SessionLive`；当前 Reader 状态只含模型／上下文信息 | [ObserveSession.kt](../../daemon/src/main/kotlin/dev/ccpocket/daemon/conversation/ObserveSession.kt) |
| 外部 writer 探测决定普通打开是否进入观察；闲置会话可能进入可控制路径 | [SessionRegistry.kt](../../daemon/src/main/kotlin/dev/ccpocket/daemon/session/SessionRegistry.kt) |
| 已管理列表与显式导入；扫描结果不自动修改成员和排序 | [ManagedSessionService.kt](../../daemon/src/main/kotlin/dev/ccpocket/daemon/session/ManagedSessionService.kt)、[ManagedSessionStore.kt](../../daemon/src/main/kotlin/dev/ccpocket/daemon/disk/ManagedSessionStore.kt) |
| 会话列表、打开响应、能力协商的 wire 契约 | [Models.kt](../../protocol/src/commonMain/kotlin/dev/ccpocket/protocol/Models.kt)、[Messages.kt](../../protocol/src/commonMain/kotlin/dev/ccpocket/protocol/Messages.kt)、[ManagedSessions.kt](../../protocol/src/commonMain/kotlin/dev/ccpocket/protocol/ManagedSessions.kt) |
| 导入界面和列表状态映射 | [ImportSessionsView.kt](../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/session/ImportSessionsView.kt)、[SessionStateUi.kt](../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/session/SessionStateUi.kt) |
| `observing` 界面目前仍可能提供“在这里继续” | [App.kt](../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/App.kt)、[PocketRepository.kt](../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/PocketRepository.kt)、[ChatPane.kt](../../mobile/composeApp/src/desktopMain/kotlin/dev/ccpocket/app/desktop/ChatPane.kt) |

本轮只读抽样看到了本地 Codex 的会话头和索引结构，但没有把一条由用户指定的 Dot Activity 任务与本地 session ID 对照成功。因此，**Dot 子任务是否按当前格式落盘、如何识别来源及父任务关系，均不是已证实事实**。

## 3. P0：先用一条真实任务建立对应关系

不要先写基于假设的解析器。用一个用户可识别的 Dot 任务在独立临时工作区做受控验证，任务只读取文件或运行无副作用命令。若需要创建付费任务、修改账户连接或授权，按当次任务授权处理，不自动改变配置。

采集以下信息，原始记录只放已忽略的 `_local/dots-observability/`，提交的样例必须脱敏。

| 探测项 | 需要得到的证据 |
|---|---|
| 身份对应 | Dot Activity 中的任务 ↔ 原生 thread/session ID ↔ rollout 或其他可读记录；不能靠相似标题认定 |
| 执行位置 | 本地 Codex、本地 Work、云端编排＋本地工具、纯云端分别是什么；至少确认用户目标任务属于哪一种 |
| 目录与项目 | 实际 Codex home、cwd、workspace roots、worktree；是否与 Pairlet 当前项目 canonical path 相同 |
| 数据格式 | 会话头、第一条业务消息、一次工具调用与结果、回合开始和结束的字段结构 |
| 活跃生命周期 | 工作中、长命令无输出、等待输入、正常结束、取消、失败、应用离线的实际记录；未能触发的状态标“未验证” |
| 可见性 | 是否在导入候选列表；若不在，是路径不匹配、扫描受限、缺少真实用户输入，还是完全没有本地记录 |
| 来源归属 | 是否有文档化或实测稳定的 Dot 来源／父任务字段；没有时只能由用户手工关联 |
| 续跑 | 同 session 续跑是否换 rollout 文件；重启后是否还可读取历史 |

探测器建议新增为 `scripts/probe-dots-observation.py`，默认只读，输出字段名、计数和脱敏分类；完整内容导出必须显式指定，默认不打印提示词、账户 ID、token 或浏览器数据。没有 Dot 专用 ID 字段也应正常输出“未验证”。

P0 输出一张结果表并补到本文末尾：环境版本、任务类型、数据源、支持的状态、缺失字段、对应的脱敏 fixture。

决策规则：

1. **存在可解析本地 Codex 记录**：进入 P1；按证据修复扫描或导入问题。
2. **有本地记录但格式变化**：先补最小解析与回放兼容，再进入 P1；不能只做一个展示标题的假接入。
3. **只有 Work／云端任务且没有可读 transcript**：P1 不适用于该任务；验证 P2 上报能力。不能把本地 Codex 支持报告成用户目标已经完成。
4. **没有本地记录，也无法上报**：提交探测结果和明确限制；完整接入依赖尚未验证的数据源，不制造空状态或假百分比。

## 4. P1：本地 Codex 子会话的只读接入

### 4.1 数据流与身份

```text
本地 Codex 记录
  → CodexPaths / CodexTranscriptScanner / CodexTranscriptReplay
  → 原有 DiscoverSessions 与显式导入
  → SessionRegistry 的明确只读打开路径
  → ObserveSession（历史、进度快照）
  → 原有加密设备通道
  → 手机／桌面的会话详情和列表
```

复用 `ManagedSessionKey(agent, canonicalWorkdir, nativeSessionId)` 作为 daemon 内身份；客户端缓存再带上电脑身份。`convoId` 仅是本次观察连接，不作为持久会话 ID。子会话继续保留真实 Codex ID，不用 Dot 标题生成 ID。

Dot 来源和父任务关联存入 Pairlet 自有元数据，禁止改写 Codex 原始记录。建议在现有 managed member 上添加可选 `observationBinding`，由一次原子写入完成“导入＋绑定”，避免导入成功后只读策略没有落盘的窗口。存储版本和旧 daemon 降级行为需在实现时一并验证。

建议绑定字段：`source = "openai_dot"`、`attribution = "verified" | "user_assigned"`、可选 `parentTaskRef`、`readOnly = true`。这些是 **Pairlet 自定义字段**，不是 OpenAI 返回字段。手工关联只表示用户归类，不可显示成“已验证来自 Dot”。既有 `ManagedSessionOrigin` 仍表示 created_here／explicit_import／legacy_adopted，不能挪作 Dot 来源。链接只作为展示元数据，限定已验证的 HTTPS 目标；缺失时不拼造 deep link，点击不经 shell 执行。

无可验证父 ID 或链接时，显示“未关联主任务”。不根据同一目录、标题、模型、时间接近、`originator = Codex Desktop` 推导 Dot 归属，也不使用 `forkedFrom`／`rewindOf` 表示 Dot 父子任务。

### 4.2 发现与导入

- 复用现有导入入口，在候选详情中提供“以只读方式关联到 Dot”；已有成员可在详情中设置关联。无需在新建会话的 Agent 选择器增加 Dot。
- 已验证来源可预填，手工关联需展示其来源。导入只写 Pairlet 元数据，不启动 Codex，不改变 Dot 的任务。
- 创建持久只读绑定与控制会话打开必须串行校验。若 Pairlet 已有该会话的可控制实例，拒绝设置绑定并说明原因，不自动打断或降权原驱动者；一次性的 `observeOnly` 仍可独立查看它。这样不会出现“已绑定只读，但旧控制实例继续接收命令”的竞态。
- 若 P0 发现 Dot 的首轮不含普通 user message，只放宽已实测的会话类型；不得全局取消 synthetic context 过滤，否则会把系统提示、guardian 和内部子线程混入普通候选。
- 当前路径与 worktree 不同，先显示真实目录，由用户在对应项目导入；P1 不把多个 worktree 静默合成一个项目。
- 扫描上限、权限失败、部分解析失败保留现有 completeness 语义；缺失记录不能在不完整扫描时被判定已删除。
- 如果实际 Codex home 不同，先验证它确实属于目标任务。需要多数据源支持时单列变更，不扫描整个用户主目录，不直接更改 daemon 的全局 home 指向。
- 来源元数据随删除 Pairlet 成员而清除，原生 transcript 不删除。后续普通导入遵循现有语义；“移除关联后接管”不放在本次入口里。

### 4.3 明确只读打开，不依赖进程探测

现有 `observing` 只描述打开后的状态；P1 还需要表达调用方要求“无论是否检测到 writer，都只读”。建议在 `OpenSession` 末尾新增默认 `false` 的 `observeOnly`，并通过 capability 协商确认 daemon 理解这一语义。

daemon 的处理顺序：

1. 验证身份、工作目录、agent 和 session ID，读取该成员的已持久化只读策略。
2. 只读绑定或 `observeOnly = true` 时，必须有已存在、可读取、身份匹配的会话；缺失时返回明确错误，不落入创建新会话路径。
3. 在复用可控制 `Conversation`、外部 writer 探测、resume／fork 和进程启动之前进入只读分支。即使本 daemon 已驱动同一会话，也创建独立观察订阅，不让该观察客户端获得控制权。
4. 复用并补强 `ObserveSession` 的订阅回收。按电脑／agent／canonical workdir／session／设备订阅身份去重，不仅按裸 session ID。
5. 对已绑定只读的会话拒绝 `takeOver`、prompt、interrupt、审批答复、原生重命名、模型／权限切换、rewind／fork 等修改请求。所有入口复用同一策略判断；不能只隐藏按钮。

新界面隐藏“在这里继续”、发送、停止、审批和参数修改入口；允许翻历史、看文件记录、复制内容以及管理 Pairlet 自己的列表归属。需要处理输入或审批时提示回原应用。

旧客户端即使仍显示接管按钮，新 daemon 也必须拒绝。遇到旧 daemon 时，新客户端不能悄悄发送 `observeOnly` 后假定它生效，必须要求升级后才能使用此入口。只读绑定格式损坏或未知时，拒绝相关操作并报错，不回退成可写。

### 4.4 进度字段与状态规则

先定义内部 `ObservedProgress`，再确定可选 wire 投影。下列命名为设计建议；字段语义是实现约束：

| 字段 | 含义 |
|---|---|
| `scope` | 固定 `turn`；P1 只描述子会话的一轮执行 |
| `state` | `unknown / running / waiting_input / idle / failed / cancelled`，未知值按 unknown 处理 |
| `evidence` | `native_event / transcript_activity`；不把时间启发式包装成原生状态 |
| `sourceEventAt` | 原记录提供的事件时间，可空 |
| `lastActivityAt` | 最近可见业务活动时间；读取文件不更新它 |
| `observedAt` | daemon 本次成功观察时间，不等于任务活动时间 |
| `freshness` | `current / stale / unavailable / unknown`；与执行结果独立 |
| `currentAction` | 有依据的当前工具／步骤摘要，可空；未知时不从长文本猜测 |
| `turnId` | 来源确实提供时才填，可空 |

状态映射必须建立在 P0 fixture 上，事件名不能靠记忆写死：

| 证据 | 展示规则 |
|---|---|
| 当前回合明确开始，尚无对应终态 | 记录 running；无持续存活证据时同时展示最后更新时间 |
| 明确的等待输入／审批事件，且之后没有解除事件 | waiting_input；只显示“请在原应用处理”，不构造 Pairlet 可答复的审批 |
| 当前回合明确正常结束 | idle，文案“本轮已结束”；不能标为 Dot 总任务完成 |
| 对应回合明确失败／取消 | failed／cancelled；后续新回合重新开始时更新 |
| 只有新文本、工具写入或文件 mtime | 展示“最近有活动”；state 为 unknown，不能据此认定仍在运行 |
| 单个工具返回非零，但 Agent 后续仍在修复 | 展示工具失败记录；不能把整轮直接置为 failed |
| 文件不再增长、进程退出、连接中断或读取失败 | 不推断成功或取消；按实际证据标陈旧／不可用 |

非终态在 60 秒内没有新的原生状态证据或明确活跃信号时，默认标 stale，显示“上次记录：运行中／等待输入”。这是 UI 的证据新鲜度阈值，不是任务超时。仅仅成功重新读取同一个文件，不能刷新原生状态证据；长工具无输出要保留其最后状态并说明未更新。明确终态在没有新回合证据前可保留。

不展示推算百分比，不把模型上下文使用率当任务进度，不根据“所有可见子会话 idle”推出 Dot 主任务完成。列表与详情必须消费同一 reducer；现有 `live || busy` 和 `SurfaceState.COMPLETE` 的兜底不得覆盖新的 unknown／stale 语义。普通会话保持原来的映射。

### 4.5 历史、续跑与连接恢复

- 重用现有 replay cursor 和历史分页；未追加业务消息时，不重复发送整段历史。
- 当前 `ObserveSession` 持有固定 `Path`。P0 若证实续跑换文件，观察器需定期按逻辑 ID 重新解析最新文件；文件切换时重置文件级游标／generation，先发送一致的完整窗口，再接增量，防止旧文件行号截掉新历史。
- 截断、替换、删除重建不能只看 mtime；结合文件身份、长度、修改时间判断读入 generation。半行 JSON 等待下次补齐，不能永久吞掉。
- 不在每个列表刷新中全量重读所有历史。复用扫描缓存；若增加进度解析，合并进已有解析过程或使用可失效的增量状态，避免观察一次解析两遍大文件。
- 手机断线／后台后停止无用推送；重连先恢复最新快照，再接历史增量。旧设备、旧项目、旧 convo 的响应不能覆盖当前详情。
- 读取失败要发可识别的观察状态和受控诊断，不能像当前失败关闭路径一样让 UI 无声停在“运行中”。退避重试，不循环刷错误。

### 4.6 最小协议演进

建议能力名 `supportsSessionObservationV1`，两端默认 `false`；以当前 `DaemonInfo`／`ClientCaps` 模式协商。需要的增量为：

- `OpenSession.observeOnly = false`。
- `ImportSession` 可选观察绑定，以及对已有成员设置绑定的 owner-only 请求；新请求／回复仅在双方声明能力后发送。
- `SessionSummary` 和 `SessionLive` 可选的 `observation` 快照，包含来源、只读属性和上节进度。字段默认 null，未知字符串值映射到 unknown。
- daemon 内持久化只读绑定是控制判断依据，客户端传来的来源或 `readOnly` 不是授权事实。

优先扩展现有帧承载快照，不建立第二套聊天流。新的设置请求需要 requestId、明确成功／失败回执和 managed revision 更新；并发更新沿用现有原子变更机制。列表页和推送同时携带最新绑定，避免只有当前客户端知道只读。

| 组合 | 必须表现 |
|---|---|
| 新 App ＋新 daemon | 导入、只读打开、进度状态完整可用 |
| 新 App ＋旧 daemon | 新入口不可用并说明需升级；普通 Codex 行为不变 |
| 旧 App ＋新 daemon | 原帧可解码；仍是 Codex 行；对只读成员的修改请求在服务端拒绝 |
| 降级到旧 daemon | 不支持本功能；新版客户端关闭观察入口。旧二进制可能绕过 managed 列表直接恢复原生会话，不能承诺新只读策略继续生效 |

对旧 App 的只读打开可返回现有 `observing = true` 与 `notice`，即便它不认识新增快照，也能读取历史；其接管操作返回稳定错误码。新模型和消息评审需使用 protocol-wire-compat-reviewer；涉及控制鉴权／凭据／密钥处理的实现另做相应安全评审。

当前 managed store 会拒绝未知 schema；新增持久字段时应设计版本迁移，避免旧实现写回时丢字段。但这只能保护存储，不能让已发布的旧 daemon 获得新控制策略。只读保证限定在支持本能力的 daemon；回退版本要在交付说明中明确这一限制，不能把 schema 升级当作对旧二进制的完整控制门。

## 5. P2：用主动上报补充主任务进展（可选）

### 5.1 能力及前提

用户若需要 Dot 主任务的阶段、阻塞和结果，设计一个 Pairlet 自有报告工具，让 Dot 在关键步骤主动调用。这只提供“Dot 上报的进展”，没有自动同步既有聊天历史，也不能保证覆盖每一次工具调用。

先在目标账户验证一次真实调用。官方支持插件不等于已证实本方案的工具在该 Dot 环境可调用。P1 没有本地记录时，P2 是否满足用户目标需要明确说明，不以“完成了基础设施”替代真实可见数据。

### 5.2 先做本地上报链路

```text
Dot 可调用的工具／技能
  → 提议的新 CLI：pairlet observe report --stdin
  → 现有 LocalControlClient 与已鉴权的 loopback 控制通道
  → ExternalTaskReportStore
  → 支持该能力的手机／桌面
```

以上 CLI、工具和 store 均为拟新增，当前仓库并不存在。工具只返回受理回执，不向模型返回本地控制 token，不接受远程主机地址，不自动启动 daemon。它只提交报告，不复用 `pairlet agent run` 或开放 Agent 执行权限。

只连接本机 loopback 的工具无法在纯云端直接调用；若必须支持纯云端，另行设计经过认证、可撤销、端到端加密的网络接入。不能把 8799 暴露公网，也不能让 relay 接收或保存明文进度。

### 5.3 报告契约

建议 owner 在 Pairlet 中建立一个“观察任务”，获得绑定到具体项目的 `trackingId`；它只是相关标识，不能当作凭据。模型不必知道原生 Dot 主任务 ID，不得自己编造一个冒充官方 ID。

虚构输入示例（Pairlet 自定义格式）：

```json
{
  "schemaVersion": 1,
  "trackingId": "example-tracking-id",
  "eventId": "example-event-id",
  "runId": "example-run-id",
  "sequence": 7,
  "state": "running",
  "summary": "已完成接口调整，正在运行测试",
  "childSessions": [
    { "agent": "codex", "sessionId": "example-native-session-id" }
  ]
}
```

具体规则：

- `trackingId` 在 daemon 侧绑定 canonical project，输入不提供任意可读取的 workdir；关联本地子会话时按同一项目和真实 ID 校验。
- `runId` 由登记接口分配，每次重新执行／恢复开始一个已登记的新 run；同一 run 只有一个报告协调者。子 Agent 可作为报告内容，不并发写同一 sequence。
- `(trackingId, eventId)` 幂等；同 ID 同内容返回原受理结果，不同内容返回冲突。同一 run 的 sequence 仅接受递增值；重复不推进 revision，旧值不覆盖新状态。
- 幂等受理记录至少保留 7 天，独立于详情里的 200 条展示记录；超出重试窗口的旧序号明确拒绝，不按新事件受理。报告存储、sequence 与受理记录在同一事务提交；重试不能只凭内存去重。
- 状态可用 `running / waiting_input / completed / failed / cancelled / unknown`；这里 scope 是 task，`completed` 仅表示上报者声明完成，与 P1 的回合 idle 分开。
- 同一 run 的终态不被晚到 running 覆盖；新 run 才能重新进入 running。离线或不再上报只影响新鲜度，不能自动转完成。
- daemon 写入 `receivedAt` 和单调 revision，排序不信任客户端时钟。只有持久化成功才返回 accepted；失败重试沿用 eventId。
- v1 限制每条报告编码后 16 KiB、摘要 2 KiB、子会话引用最多 20 条；超限明确拒绝。每个观察任务保留最近 200 条报告和最新快照，容量回收不丢最新终态。
- 日志只记计数、结果码和耗时，不记摘要／任务正文／凭据。删除观察任务同时清除报告与关联，不删除原生会话。

### 5.4 展示与协议

P2 的主任务不是原生 Codex 会话，不能伪造 `SessionSummary.sessionId`、调用 `OpenSession` 或挂到 Codex 恢复路径。

在项目的观察任务入口展示独立报告详情；详情可跳转已验证的本地子会话。先不自动把新报告插进现有聊天流，也不发完成推送。文案必须出现“Dot 上报”与更新时间，陈旧时显示“尚无更新”。

拟新增 `supportsExternalTaskReportsV1`，默认为 false；列表、详情、创建／删除观察任务、报告快照和错误帧均按 capability 发放，仅供 owner。分页和编码帧大小复用 managed 列表的限额思路，但使用独立 task ID。旧客户端不接收新帧，新客户端面对旧 daemon 不显示入口。

本地上报通过现有本机身份认证；未来远程上报必须限制到指定 trackingId／项目、仅有报告写权限，并实现撤销、过期、速率限制和重复投递处理。不要把 owner 设备密钥或本地万能控制 token 交给云端插件。

## 6. 完整云端会话接入的重启条件

以下条件同时满足，才为读取既有 Dot 主会话编写正式 adapter：

1. 官方文档和实测确认可列出／读取目标账户中已存在的 Dot 任务，不只是通过 API 新建的另一套 Agent 会话。
2. 有受支持的授权方式，能够限定读取范围、断开连接并撤销；无需提取桌面 App 的 cookie、token 或逆向私有接口。
3. 明确任务／子任务身份、分页、顺序、断线恢复和删除语义。
4. 有稳定的状态证据和可接受的数据延迟。

届时可以增加单独的云端任务数据源，复用 P2 的只读任务展示；不把云端任务硬塞成依赖本地 cwd 和 OS 进程的 AgentBackend。企业 Analytics／Compliance 数据需按其用途和账户权限单独评估，不能默认用作个人账户实时会话接口。

## 7. Fable 实施任务清单

| 步骤 | 工作与文件范围 | 可验收产物 |
|---|---|---|
| P0 | 新增只读 probe；整理脱敏 fixtures；更新本文证据表 | 一条真实目标任务的数据源和身份链，已验证／未验证状态清单 |
| P1-A | `codex/` 路径、扫描、回放兼容；只改 P0 命中的缺口 | 目标会话可被发现、正确归属项目并读到历史 |
| P1-B | protocol 的能力／观察字段；managed store 绑定；SessionRegistry 显式只读分支 | 原子导入、不可绕过的只读策略、旧端兼容 |
| P1-C | ObserveSession 与进度 reducer，文件 generation／重连处理 | 实际新增内容与有依据的状态持续可见 |
| P1-D | common UI、PocketRepository、桌面 RepoDesktopModel／ChatPane／Sidebar、资源文案 | 手机与桌面同语义，只读入口清晰，未知／陈旧不会显示完成 |
| P1-E | 针对性测试、真实设备观察、更新文档 | 证据、限制、兼容矩阵及改动列表 |
| P2 | 仅当需要主任务汇报且真实工具调用已通过，实施 CLI／接收／存储／独立任务 UI | 一条实际报告从 Dot 到 Pairlet 可见，断线重试不重复 |

先完成 P0，再按 A → B → C → D → E 推进。不要在 P0 未通过时一次性搭出多后端框架。若实际只需导入已有会话即可看到历史，也要单独核实进度语义与只读边界，报告已有能力和新增改动各是什么。

## 8. 验证与完成标准

### 8.1 必须覆盖的回归

| 场景 | 预期 |
|---|---|
| 外部新建／续跑同一逻辑会话 | 候选可见；续跑不产生重复行，观察跟随正确文件 |
| Dot 特有首轮／无普通 user message | 只处理 P0 证实的格式；synthetic、guardian 和无关内部线程不误入列表 |
| 同名任务、不同项目、不同电脑 | 不串会话，不合并来源，不串历史缓存 |
| idle、writer 探测不到或返回 UNKNOWN | 绑定只读会话仍只读，不启动／resume／fork 任何 Agent |
| 已有可控制 Conversation 的同 session 只读打开 | 独立观察订阅，不返回可写 convo，不影响原驱动者 |
| 新旧客户端绕过 UI 发送修改请求 | 服务端拒绝，原 Dot 任务继续运行，原始 transcript 不被 Pairlet 修改 |
| 每一种 P0 证实的生命周期 | 按同一 reducer 映射；回合结束不冒充主任务结束 |
| 超过 60 秒无输出的长工具 | 保留最后证据并标陈旧，不报已完成／失败 |
| 半行 JSON、未知事件、截断、换文件 | 不崩溃、不丢补齐行、不重复消息，必要时正确重放 |
| 大历史、分页、断线与重连、切换项目 | 有限帧大小；旧响应不能覆盖新会话；无重复 observer |
| 扫描失败或源文件缺失 | 明确 unknown／unavailable；保留已管理成员和最后可读历史 |
| daemon 重启、存储版本不兼容、只读绑定损坏 | 只读策略不丢失；无法理解时拒绝，不降级可写 |
| 普通 Claude／Codex 会话 | 原有发送、接管、审批、导入、分组行为通过原回归 |
| P2 重复／乱序／重试／超限／删除 | 幂等、终态不倒退、明确拒绝、无残留关联 |

测试优先扩展已有 `CodexTranscriptTest`、`CodexResumeRolloutDedupeTest`、`SessionRegistryObserveTest`、`ManagedSessionServiceTest`、`ManagedSessionStoreTest`、协议兼容测试和 `SessionStateUiTest`。针对真实新增行为添加测试，不用大量实现镜像测试代替上述场景。

实施后的基础命令：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 bash scripts/check-all.sh --affected
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :mobile:composeApp:compileKotlinDesktop
python3 scripts/check-repository-content.py
git diff --check
```

`check-all.sh --affected` 在 protocol 改动时执行全套受影响检查，默认使用 JVM 协议测试；iOS Simulator 测试按脚本的 `CHECK_IOS=1` 前提运行，不直接用 `:protocol:allTests` 绕过本机 SDK 条件。跨平台 common UI 改动还需完成项目要求的 iOS／Android 编译检查和手机可见验收；同排文字按仓库要求使用 `tightCenter`。

更新本机 daemon 只使用仓库规定的 update 脚本；daemon 驱动的会话用 detached 版本。更新脚本若列出其他活跃会话，必须按 AGENTS.md 的会话确认门处理。不得通过 `:daemon:run` 启动第二个 daemon 验证。

P1 完成需要同时满足：真实 Dot 本地子任务身份已对应、明确导入可见、消息和可证实的状态持续更新、手机／桌面只读、终态和未知状态不误报、重连／续跑／旧端通过验证。没有真实 Dot 样例验收时，只能报告“通用只读基础完成，Dot 兼容待验收”。

交付说明必须写明支持的任务类型与未覆盖范围；“本地子任务可看”“主任务上报可看”“完整云端会话可读”分别列出，不合并成一个“已支持 Dot”。

## 9. 可直接交给 Fable 的执行说明

> 按 `docs/design/DOTS-SESSION-OBSERVABILITY.md` 实施 Dot 只读会话观察。先阅读 AGENTS.md，完成 P0 真实任务与本地数据源对照，保存脱敏 fixtures；依据探测结果实施 P1，并完成协议兼容、只读控制边界和真实观察验收。保留已有用户修改。数据源和状态未证实时明确 unknown，不猜 Dot 来源、不用 mtime 推断完成、不新增能启动 Dot 的伪后端。若目标任务没有本地记录，按 P0 分支说明结果并验证 P2 的实际调用能力；不声称已覆盖云端。P2 为可选扩展，完整云端接入遵守第 6 节的条件。最终提供改动、测试、真实验收证据和剩余限制；本次不自动发版或部署生产服务。

### 用户追加的执行要求

用户已要求通过 CLI 启动 Fable，后续由 Fable 自行推进实现和验证。先自行判断本次改动是否需要新的 UI／交互设计：能沿用现有组件与交互就直接实现，不额外出稿；确有需要时，可自行使用 Claude Design 或 CQ，再完成实现，不重复设计、不把设计当作所有步骤的前置门。使用 Claude Design 时读取本仓库 `.agents/skills/design-run/SKILL.md`，由 Fable 控制投递、可见验收和必要修订，只保存本次采用的设计与简要依据。

任务在独立的后台交互会话中运行，发起方确认接收后即可结束。Fable 完成或遇到必须由用户处理的阻塞时写明结果并保留会话待续；不执行 `/exit`，不主动停止或删除自身进程，也不为空保活反复调用模型。工作区还有其他人的修改，必须保留，不回退、不夹带；需要隔离时自行建立工作区并携带本文。

## 10. 实施证据表（Fable 2026-10-05 填写）

探测工具：`python3 scripts/probe-dots-observation.py`（只读；`--session <id>` 输出每回合证据；`--fixture <id> --out <file>` 产出脱敏 rollout）。原始记录在仓库外；提交的 fixture 为 `daemon/src/test/resources/codex/rollout-observed-turns.jsonl`（文本、路径、账户字段均已替换）。

| 项目 | 当前结果 |
|---|---|
| 用户目标 Dot 任务与实际类型 | 未指定样例。本机没有任何可归因于 Dot 的本地记录；无法判定目标任务是本地 Codex、Work 还是云端 |
| ChatGPT／Codex 版本与任务执行环境 | ChatGPT.app 26.930.41038（内嵌 Codex 框架，运行 `codex exec-server --remote` 连接 `codex-cloud-environments.chatgpt.com`）；`codex-cli 0.155.1`；Codex home `~/.codex` |
| Dot 任务 ↔ 原生 session ID 对应依据 | **未验证**。rollout `session_meta` 与 `state_5.sqlite.threads` 均无 Dot 字段；`originator` 只见 `Codex Desktop` / `cc-pocket`。`exec-server --remote` 进程不持有 rollout 文件。关联只能由用户手工设置（`attribution = user_assigned`） |
| 本地落盘位置与记录格式 | `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`（续跑产生 `_<runId>` 第二文件）；记录为 `{timestamp,type,payload}`。回合生命周期：`event_msg/task_started` → `task_complete` 或 `turn_aborted(reason=interrupted)`；活动：`item_completed`（UserMessage/AgentMessage/CommandExecution/FileChange/Reasoning/Extension）、`response_item` 工具调用与输出 |
| 已验证状态与脱敏 fixture | running（task_started 无终态）、idle（task_complete）、cancelled（turn_aborted/interrupted）、unknown（仅文件活动）已由 fixture + 单测覆盖。**waiting_input 未验证**（120 个本地 rollout 中无 `request_user_input`／审批记录，v1 不产生该状态）；**failed 未验证**（`event_msg/error` 仅在回合进行中才采信） |
| 来源／父任务字段 | 未验证；不能自动认定。实现为 Pairlet 自有 `ObservationBinding{source=openai_dot, attribution=user_assigned, parentTaskRef?, readOnly=true}`，持久化在 managed store（schema 2，仅含绑定时写入） |
| P1 实施与验收 | 通用只读基础完成：协议能力 `supportsSessionObservationV1`、`OpenSession.observeOnly`、导入＋绑定原子写入、`SetSessionObservation`、daemon 侧拒绝接管／改名、ObserveSession 跟随续跑文件与截断、同一 reducer 供列表与详情、手机／桌面只读条与导入开关、会话详情关联入口。协议／daemon／客户端定向测试通过。**真实 Dot 样例验收未完成**：按第 8 节只能报告“通用只读基础完成，Dot 兼容待验收” |
| P2 插件／技能真实上报能力 | 未验证，未实施（P2 可选） |
| 完整读取既有 Dot 云端会话接口 | 未验证；第 6 节重启条件均未满足 |

### 10.1 已落地的契约（与第 4 节建议的差异）

- 来源／归属／状态／新鲜度均为字符串词汇（`ObservationSources`、`ObservationAttributions`、`ObservedStates`、`ObservedFreshness`），未知值由 `normalize` 映射到 unknown；没有给既有 enum 增值。
- `ManagedMember.observation` 为空时不写入文件（schema 1 字节不变）；存在绑定时文件写为 `schemaVersion = 2`，旧 daemon 读到后按既有规则判为 `STORE_CORRUPT`（只读，不改写），不会丢绑定但也不会执行新策略——这是第 4.6 节已说明的回退限制。
- 只读分支位于 `SessionRegistry.openClaimed` 最前（在 reattach、writer 探测、resume／fork 之前）；store 不可读（损坏／未知 schema）时普通打开退化为只读观察（无绑定、`notice` 提示），接管与改名拒绝（`session_read_only`），不回退成可写；本 daemon 自己正在驱动的会话不受此影响（它不可能已绑定，重连仍 reattach）。`observeOnly` 而无 `resumeId` 返回 `observe_unavailable`，不新建会话。
- 绑定与打开的竞态：打开方在会话进入可控表之后再读一次策略，发现已绑定即撤回该会话；绑定方在落盘之后再查一次是否已被本机驱动，发现是则撤回绑定并返回 `managed_observation_conflict`。两边各看一次，任一交错都不会留下“已绑定只读 + 可控会话”的组合。
- 已打开的观察视图每约 7.5 秒重读持久化绑定：在会话详情里关联／取消关联无需重开视图即生效；`observeOnly` 与 store 不可读属于调用方自身策略，不随绑定解除而放开。
- `takeOver` 对已绑定会话返回 `PocketError(session_read_only)`；prompt／interrupt／审批／模式切换按 convoId 路由，观察 convo 不在可控表中，天然拒绝；rewind 需要可控 convo，同理。
- 列表进度由 `SessionObservationProjector` 从扫描 memo 读取（不二次解析），观察 tick 复用同一 memo；新鲜度变化（60 秒）即使文件未变也会重新宣告。
- 旧客户端：只读打开仍收到 `observing = true` + `notice`，不收到 `observation` 快照（按连接能力剥离）；其“在这里继续”请求被服务端拒绝。

### 10.2 未覆盖范围

- 没有真实 Dot 子任务样例：是否按当前 rollout 格式落盘、首轮是否含普通 user message、续跑是否换文件，均未在 Dot 场景实测。
- 等待输入／失败两种状态无原生样例，UI 不会显示“等待输入”（除非未来事件被证实并加入 reducer）。
- 本次未在真机上做手机可见验收（会话内无法更新本机 daemon；见 `_local/dots-observability/STATUS.md`）。
