# Session Handoff —— 运行时上下文协作接力

> 状态：**已实现并保留，作为运行时上下文交接能力**
>
> 决策日期：2026-08-01
>
> 调整日期：2026-08-02
>
> 适用场景：同事必须使用发起人电脑上的原始 Session、进程、未提交状态或设备现场
>
> 并列能力：[`REVIEW-REQUEST.md`](./REVIEW-REQUEST.md) —— 任务上下文交接，由接收者使用自己的 Agent 和本地上下文评审
>
> 取代方案：[`PEER-CALL.md`](./PEER-CALL.md) 中的 daemon-to-daemon Agent 互调主线
>
> 产品关系：两者同时保留；Session Handoff 交接运行时上下文，ReviewRequest 交接任务上下文

---

## 0. 双能力分工说明

cc-pocket 同时保留两类跨用户协作：

- **Session Handoff（本文）**：交接运行时上下文。接收者进入发起人电脑上的原始 Session，在同一份
  代码、进程和运行状态上接续；适合难以复现的问题、设备现场、事故处理和同步联合调试。
- **ReviewRequest**：交接任务上下文。发送 MR、文档、背景、约束和完成标准，接收者使用自己的电脑、
  Agent、Skills 和历史上下文处理；适合常规代码评审和文档评审。

Session Handoff 已经实现，因此继续保留现有入口、协议和安全边界，不因新增 ReviewRequest 而隐藏或删除。
两者可以共享联系人、E2E 投递、通知和历史基础设施，但不能混用 Controller Lease、Session Grant 或
ReviewRequest capability。

## 0.1 运行时上下文方案结论

cc-pocket 不发布长期可调用的 Agent，也不建设 Agent 通讯录、AgentSpace 或 daemon-to-daemon
互调网络。只增加一个轻量的私有“协作联系人”列表：双方首次通过二维码/深链建立 E2E 信任，
后续发起 Handoff 直接选择联系人，不再重复扫码。新能力定义为：

> **协作接力（Session Handoff）是独立挂在既有 Session 上的一等实体。发起人把当前工作在稳定
> 检查点临时交给指定同事；接收者在同一台 owner 电脑、同一份代码与同一个 Session 上独占接续，
> 完成后归还控制权和结构化结果。**

默认用户行为是“发出后等待对方处理”，因此 v1 优化串行接力：

- 发起后原用户暂停输入，可以旁观、撤回；
- 接收者接受后成为唯一控制者；
- 双方不同时向 Agent 发送 prompt；
- 接收者归还后，原用户从同一 Session 继续；
- v1 不创建并行 fork 或隔离 worktree；
- v1 接收者只需 cc-pocket App，不要求其电脑安装 daemon；
- 二维码只用于首次建立 Collaborator Link；每次 Handoff 只生成临时范围授权，不重新配对；
- 所有代码、Agent 进程、模型账号和执行成本仍位于发起人的电脑；
- relay 继续只转发 E2E 密文，不理解 Handoff 内容。

内置 Skill 可以改善接力质量，但不能成为状态与安全边界：

> **Skill 负责“说清楚交什么、怎么接、如何还”；cc-pocket 本体负责“交给谁、当前谁能操作、
> 能操作什么、何时收回”。**

---

## 1. 用户问题

开发者在一个 Agent Session 中推进任务时，经常在中途需要同事：

- 评审当前实现；
- 判断一个设计或风险；
- 排查自己不熟悉的问题；
- 完成后续的一小段工作；
- 在自己暂时离开时继续推进。

今天的协作成本不在“缺一个 Agent”，而在于重新解释正在发生的工作：

- 原始目标与约束；
- 已完成和未完成的部分；
- 做过的关键决策；
- 当前 Git 状态、diff、测试和报错；
- 为什么现在需要对方；
- 对方应该做到什么程度再交还。

复制聊天、手写背景、发 diff 或屏幕共享都会丢失 Session 语境。cc-pocket 已能恢复、观察和控制
coding session，也已有 E2E 配对、受限凭证、路径作用域、有效期与撤销，因此最自然的扩展是让
Session 本身成为可临时接力的协作载体。

---

## 2. 目标与非目标

### 2.1 目标

1. 发起人从当前 Session 内用极少输入创建一份可读、可执行的协作请求。
2. Handoff 独立持久化并挂在来源 Session 上，可等待、接受、拒绝、撤回、归还和回看。
3. 同一时刻只有一个人拥有 Session 输入控制权。
4. 接收者无需重新配置项目、模型或 Agent，也无需把代码同步到自己的电脑。
5. 内置 Skill 自动构造 Handoff Brief、接续提示词和返回报告。
6. 权限绑定到“指定接收者 + 指定 Handoff + 指定 Session + 指定范围 + 指定有效期”。
7. 原用户可以旁观接续过程，并在异常时安全收回控制权。
8. 归还后把协作结果重新注入来源 Session，让原 Agent 自然继续。
9. 同一位同事首次建立信任后永久出现在私有选择器中，后续接力不再传二维码或邀请链接。

### 2.2 非目标

v1 不做：

- 长期发布或发现可调用 Agent；
- A-daemon 主动连接 B-daemon；
- Agent 目录、组织结构、公开社交、陌生人发现或全局用户搜索；
- 多人同时写入同一个 Session；
- 并行子任务、自动 fork、自动 worktree 或自动合并；
- 接收者在自己电脑上执行发起人的任务；
- 跨 Agent 链式调用；
- 企业 SSO、SCIM、复杂 RBAC 或审计后台；
- 仅靠 prompt 约束控制权或文件权限。

---

## 3. 核心概念

| 概念 | 含义 |
|---|---|
| Source Session | 发起协作时正在进行的 coding session |
| Handoff | 挂在 Source Session 上的一次独立协作接力实体 |
| Handoff Brief | Skill 根据 Session 和用户请求生成的结构化接力说明 |
| Initiator | 发起协作的人，通常也是 daemon 与工作区 owner |
| Recipient | 被指定接续 Session 的同事 |
| Collaborator Link | 首次扫码建立的长期 E2E 联系人连接；只证明身份和提供投递地址，不授予 Session 权限 |
| Handoff Grant | 每次接力动态创建的临时授权，绑定具体联系人、Handoff、Session、范围和有效期 |
| Controller Lease | daemon 维护的 Session 独占输入控制权租约 |
| Return Result | 接收者归还时生成的结构化结论、改动、验证和下一步 |
| Collaborator Credential | Collaborator Link 使用的长期受限凭证；基线能力不包含打开任何 Session |

Handoff 是 Session 的子实体，但拥有独立 ID、状态和生命周期：

```text
Session A
├── Transcript / Agent process / Workdir
├── Controller: Panda
├── Handoff H1: Frank · Review · Completed
└── Handoff H2: Alex · Continue · Waiting
```

一个 Session 在同一时刻最多存在一个非终态 Handoff，避免控制权和用户心智分叉。

---

## 4. 默认交互流程

### 4.1 首次建立协作连接

二维码/深链只用于第一次建立 Collaborator Link：

1. A 在“协作联系人”中选择“连接同事”；
2. A 的 daemon 铸造一次性 collaborator ticket，App 展示二维码或链接；该二维码只包含建联材料，
   不包含任何 Session、目录或 Handoff 内容；
3. B 的 App 扫码并展示 A 的设备标签与安全指纹；
4. B 确认后，用自己的长期 App device key 兑换 `COLLABORATOR` 受限凭证；
5. A 的 daemon 将 B 的 device key、标签和 push routing 记录到 `CollaboratorStore`；
6. 双方 App 保存联系人显示信息和 E2E 连接材料；
7. ticket 立即作废，Collaborator Link 保留到任一方主动解除。

Collaborator Link 是持久信任绑定和可复用凭证，不要求永久保持 WebSocket。App 可以按需连接、空闲
断开；离线 offer 用不含正文的 push 唤醒后，再通过原 E2E Link 拉取。UI 中的“已连接”表示信任
关系仍有效，不等同于对方当前在线。

连接成功后，B 默认只能：

- 接收 A 发来的 Handoff offer 通知；
- 查看发给自己的 offer 摘要；
- 接受或拒绝自己的 offer；
- 管理/解除自己与 A 的连接。

B **不能**仅凭 Collaborator Link 查看 A 的目录、Session、transcript、文件或设置。每次接力的访问
范围必须由独立 Handoff Grant 动态授予。

若双方都有自己的 daemon，一次用户可见的连接流程可以在 E2E 通道内交换反向 ticket，自动建立
两条方向相反的 Collaborator Credential，UI 显示为一个双向联系人。底层权限仍保持有向：

```text
A → B：B 可以接收并处理 A 发来的 Handoff
B → A：A 可以接收并处理 B 发来的 Handoff
```

若 B 只有 App、没有自己的 daemon，则先建立 `A → B` 单向连接：B 可以接 A 的工作，但无法从自己
的电脑发起 Source Session。未来 B 配置 daemon 后可在联系人详情补齐反向连接，无需删除原连接。

以下情况才需要再次扫码：

- 首次连接；
- 原连接被任一方解除后重新连接；
- 对方更换身份密钥且无法由已信任旧密钥签名迁移；
- 用户主动要求重新验证安全指纹。

### 4.2 发起

发起人可以点击 Session 顶部的“找同事接力”，或输入：

```text
/handoff @Frank 帮我做代码审核，重点看 relay ACK 和撤销竞态
```

系统执行：

1. 检查当前 Agent turn 已结束；
2. 检查没有待处理的 permission/question 与不可安全交接的后台任务；
3. 确保 Session 已获得可持久恢复的真实 `sessionId`；
4. daemon 确定性采集工作区事实；
5. 内置 Skill 总结语义上下文并生成 Handoff Brief；
6. 用户从已连接的协作联系人中选择 Recipient，并预览共享范围、操作级别和接力说明；
7. 用户确认后创建 Handoff，将临时 Handoff Grant 绑定到该 Collaborator device；
8. Source Session 进入 `WAITING`，发起人输入框锁定；
9. daemon 通过既有 Collaborator Link 发送 E2E Handoff offer，并发出不含正文的 push 提醒。

若还没有协作联系人，选择器提供“连接新同事”，完成一次扫码后立即回到当前 Handoff 草稿继续发送。

### 4.3 接受

接收者点击 Handoff 通知后看到：

- 发起人设备标签；
- Session 标题和项目 basename；
- 协作类型；
- Handoff Brief；
- 将共享完整 Session 历史和哪些目录；
- 允许的操作级别；
- 有效期与执行成本归属。

确认后，App 通过既有 Collaborator Credential 发送 `AcceptHandoff`。daemon 校验该 device 正是
Handoff Grant 指定的 Recipient，再用原子状态转换完成：

```text
WAITING → IN_PROGRESS
controller: locked → recipientDevice
```

只有一个接收者能成功接受；冒用联系人、第二设备竞争和过期接受全部失败关闭。接受后 Handoff
Grant 才临时扩展该连接对 Source Session 的访问能力；归还、撤回或过期时立即移除。

### 4.4 接续

接收者进入同一个 Source Session：

- 重放既有 transcript；
- 顶部明确显示“正在接续 Panda 的 Session”；
- 自动注入接续提示词；
- Recipient 是唯一能发送 prompt、回答自身交互问题和发起允许操作的人；
- Initiator 保持只读旁观，可以查看流式输出和工具事件；
- 每条新消息和工具操作标注操作者身份；
- owner 可以撤回接力，但不能绕过控制权租约同时输入。

v1 不 fork。体验与技术语义都是真正的串行 Session 接续。

### 4.5 归还

接收者点击“完成并归还”，或输入：

```text
/handoff return
```

内置 Skill 根据实际过程构造 Return Result，接收者预览后提交。daemon 原子完成：

```text
IN_PROGRESS → RETURNED
controller: recipientDevice → initiatorDevice
```

随后：

1. 接收者输入权限立即失效；
2. 发起人恢复输入；
3. 原 Session 出现协作结果卡片；
4. 系统向原 Agent 注入精简的 Handoff Return 事件；
5. 发起人确认结果后将 Handoff 标记为 `COMPLETED`，或继续讨论。

---

## 5. 状态机与控制权

### 5.1 Handoff 状态

```text
DRAFT
  └── send ──> WAITING
                 ├── decline ──> DECLINED
                 ├── cancel ───> CANCELLED
                 ├── expire ───> EXPIRED
                 └── accept ───> IN_PROGRESS
                                    ├── recall ──> RECALLED
                                    └── return ──> RETURNED
                                                     └── acknowledge ──> COMPLETED
```

终态为 `DECLINED / CANCELLED / EXPIRED / RECALLED / COMPLETED`。

### 5.2 状态转换表

| 当前状态 | 动作 | 执行者 | 下一状态 | 控制权 |
|---|---|---|---|---|
| DRAFT | 发送 | Initiator | WAITING | 锁定，无人可输入；Initiator 保留撤回权 |
| WAITING | 接受 | 指定 Recipient | IN_PROGRESS | 转给 Recipient |
| WAITING | 拒绝 | Recipient | DECLINED | 归还 Initiator |
| WAITING | 撤回 | Initiator | CANCELLED | 归还 Initiator |
| WAITING | 超时 | daemon | EXPIRED | 归还 Initiator |
| IN_PROGRESS | 归还 | Recipient | RETURNED | 归还 Initiator |
| IN_PROGRESS | 收回 | Initiator | RECALLED | 归还 Initiator |
| IN_PROGRESS | 超时 | daemon | RECALLED | 归还 Initiator |
| RETURNED | 确认 | Initiator | COMPLETED | Initiator |

### 5.3 必须由 daemon 强制的不可变条件

1. 一个 Session 同时最多一个活跃 Controller Lease。
2. `WAITING` 期间不创建可输入 Lease，所有设备的 `SendPrompt` 均被拒绝；Initiator 必须先撤回才能继续。
3. `SendPrompt`、`CancelTurn`、问题回答和 permission verdict 都校验 controller 身份。
4. Handoff 只能在稳定 turn 边界发起和转移控制权。
5. 接受操作使用 compare-and-set，不能被两个设备同时接受。
6. Credential 与 Handoff、Session、Recipient device、范围和有效期绑定。
7. Credential 撤销或 Handoff 到终态后，所有关联 sink 与在途权限立即失效。
8. App 展示状态不是授权依据，daemon 持久状态才是唯一事实源。
9. Skill 文本、模型输出或客户端字段都不能改变授权范围。

### 5.4 断线、重启与收回

- Recipient 短暂断线时 Lease 保留，允许原设备重连，不立即让 Initiator 抢回造成双写；
- Lease 达到 `leaseExpiresAt` 后由 daemon 收回；
- Initiator 主动收回时，若当前无 turn，立即转移；
- 若 Agent 正在执行，标记 `recallRequested`，取消当前 turn、等待进程进入稳定状态后收回；
- daemon 重启后从 HandoffStore 恢复非终态 Handoff 与 Lease；
- durable 绑定使用 provider `sessionId + workdir + agentKind`，不能只存进程期 `convoId`；
- 找不到可恢复 Source Session 时，Handoff 进入安全失败态并归还 owner，禁止新建空白 Session 冒充接续。

---

## 6. 数据模型

以下为领域模型草案；实现时所有 wire 字段遵循 additive-with-defaults，具体序列化形状需通过
`:protocol` backward-compat 测试。

```kotlin
@Serializable
enum class CollaboratorDirection {
    CAN_RECEIVE_FROM_ME,
    MUTUAL,
    UNKNOWN,
}

@Serializable
data class CollaboratorLink(
    val id: String,
    val peerDeviceId: String,
    val peerDevicePubkey: String,
    val label: String,
    val direction: CollaboratorDirection,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
    val revoked: Boolean = false,
)

@Serializable
enum class HandoffKind {
    REVIEW,
    CONTINUE,
}

@Serializable
enum class HandoffStatus {
    DRAFT,
    WAITING,
    IN_PROGRESS,
    RETURNED,
    COMPLETED,
    DECLINED,
    CANCELLED,
    EXPIRED,
    RECALLED,
    UNKNOWN,
}

@Serializable
enum class HandoffAccess {
    REVIEW_READ_ONLY,
    CONTINUE_SCOPED,
    UNKNOWN,
}

@Serializable
data class SessionHandoff(
    val id: String,
    val sourceSessionId: String,
    val sourceConvoId: String? = null,
    val sourceEventSeq: Long = 0,
    val workdir: String,
    val agent: AgentKind,
    val initiatorDeviceId: String,
    val initiatorLabel: String? = null,
    val collaboratorId: String,
    val recipientDeviceId: String? = null,
    val recipientLabel: String? = null,
    val kind: HandoffKind,
    val status: HandoffStatus,
    val access: HandoffAccess,
    val brief: HandoffBrief,
    val allowedRoots: List<String> = emptyList(),
    val createdAt: Long,
    val expiresAt: Long,
    val acceptedAt: Long? = null,
    val returnedAt: Long? = null,
    val result: HandoffResult? = null,
)

@Serializable
data class HandoffBrief(
    val request: String,
    val originalGoal: String? = null,
    val completedWork: List<String> = emptyList(),
    val currentState: String? = null,
    val decisions: List<String> = emptyList(),
    val focusAreas: List<String> = emptyList(),
    val relevantFiles: List<String> = emptyList(),
    val verification: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val definitionOfDone: List<String> = emptyList(),
)

@Serializable
data class HandoffResult(
    val verdict: String? = null,
    val summary: String,
    val findings: List<HandoffFinding> = emptyList(),
    val workCompleted: List<String> = emptyList(),
    val changedFiles: List<String> = emptyList(),
    val verification: List<String> = emptyList(),
    val remainingRisks: List<String> = emptyList(),
    val recommendedNextSteps: List<String> = emptyList(),
    val returnedByDeviceId: String,
    val returnedAt: Long,
)

@Serializable
data class SessionControllerLease(
    val sessionId: String,
    val handoffId: String,
    val controllerDeviceId: String,
    val acquiredAt: Long,
    val leaseExpiresAt: Long,
    val recallRequested: Boolean = false,
)
```

`SessionControllerLease` 必须独立于 `HandoffStatus` 存储和校验，不能靠 UI 或“状态看起来是
IN_PROGRESS”推断谁可以发送 prompt。`WAITING` 期间不存在活跃 Lease，由 HandoffGuard 将 Source
Session 锁定；进入 `IN_PROGRESS` 时才为 Recipient 原子创建 Lease，离开该状态时立即删除。

---

## 7. 内置 Session Handoff Skill

### 7.1 定位

Skill 名称建议为 `session-handoff`，由 cc-pocket 内置并按需注入，不要求用户自行安装到
`~/.codex/skills`。它必须在 Claude、Codex 与后续 backend 上提供一致的领域行为：

- UI 或 `/handoff` 触发发起阶段；
- 接受 Handoff 后触发接续阶段；
- `/handoff return` 或“完成并归还”触发返回阶段。

backend 可以采用不同的原生 Skill、system prompt 或 tool 适配，但 daemon 持有统一模板、结构化
输入输出和状态工具。Skill 不直接读写 HandoffStore。

### 7.2 确定性事实与模型判断分离

daemon 确定性采集：

- Session ID、Agent kind、workdir 和当前分支；
- Git HEAD、status、diff stats、changed files；
- Session 触及文件；
- 最近一次测试/构建工具事件及退出状态；
- 当前 turn、pending approval/question 和 background work；
- 当前权限模式和可共享 roots。

Skill 负责总结：

- 原始目标；
- 已完成工作；
- 关键决策与当前阻塞；
- 为什么现在需要同事；
- 评审重点或待完成部分；
- 完成标准和返回格式。

Skill 不得凭空声称测试通过、文件已修改或命令已执行；这些事实只能来自 daemon 提供的结构化证据。

### 7.3 发起阶段

Skill 默认只追问一个真正阻塞的问题，例如：

> “希望对方只给出评审意见，还是可以继续修改代码？”

随后输出 `HandoffBrief` 草稿供用户编辑。推荐模板：

```markdown
# 协作接力

## 本次角色
代码评审者。请独立检查实现，不要默认认同原方案。

## 原始目标
{{originalGoal}}

## 已完成
{{completedWork}}

## 当前状态
{{currentState}}

## 请你处理
{{request}}

## 重点关注
{{focusAreas}}

## 相关文件与验证
{{relevantFilesAndVerification}}

## 允许操作
{{allowedActions}}

## 完成标准
{{definitionOfDone}}
```

### 7.4 接续阶段

Recipient 接受后，Skill 向 Source Session 注入一条带结构化边界的接续事件：

```markdown
你正在通过 CC Pocket 接续 {{initiatorLabel}} 的 Session。

- 当前角色：{{kind}}
- 本次请求：{{request}}
- 允许操作：{{allowedActions}}
- 禁止操作：{{deniedActions}}
- 完成后：调用 `return_handoff`，不要自行扩大任务范围。

以下为发起人确认过的 Handoff Brief：
{{brief}}
```

接续提示词只说明任务和流程，不承担权限隔离。即使模型忽略提示，daemon 仍按 Credential、Caps、
path scope 与 Controller Lease 拒绝越权帧和工具调用。

### 7.5 返回阶段

Skill 根据协作期间的实际 transcript、tool events 和 Git 事实生成 `HandoffResult` 草稿。代码评审
至少返回：

- 总体 verdict；
- 按优先级排列的 findings；
- 文件与行号；
- 已执行验证及结果；
- 推荐下一步；
- 未确认或无法验证的内容。

Recipient 必须预览确认后提交。返回 Source Session 的注入事件保持精简：

```markdown
{{recipientLabel}} 已完成协作并归还 Session。
结论：{{verdict}}。
Handoff {{handoffId}} 包含 {{findingCount}} 条发现。
请先阅读完整结果，再与用户确认下一步；不要把建议视为已经执行的修改。
```

---

## 8. 权限与安全模型

### 8.1 长期身份连接与临时权限分离

不得把 Recipient 写入 owner 的全权 `devices.json`。在既有 `BRIDGE / GUEST` 受限凭证体系旁新增
`CredentialKind.COLLABORATOR`，并单独持久化到 `collaborators.json`，保证旧 daemon 降级时 fail
closed。

Collaborator Credential 是长期身份和投递连接，只绑定：

- Collaborator ID 与 Recipient device public key；
- E2E 静态密钥材料；
- owner account 的受限 relay credential；
- push routing；
- 创建时间、标签、方向与撤销状态。

它的基线 Caps 不包含 `OpenSession`、`SendPrompt`、目录、history 或文件访问。连接建立后长期保留，
因此不能把“认识这个设备”和“允许它看当前工作”混成同一个永久授权。

每次创建 Handoff 时，daemon 另存一份短期 Handoff Grant，固定绑定：

- `handoffId`；
- `sourceSessionId`；
- `collaboratorId + recipientDeviceId`；
- `allowedRoots`；
- `HandoffAccess`；
- `expiresAt`。

Handoff Grant 不是一套需要重新扫码兑换的新长期密钥，而是 daemon 对既有 Collaborator Credential
动态开放的一次能力窗口。只有 Handoff 进入 `IN_PROGRESS` 后，HandoffGuard 才允许该 device 访问
绑定的 Source Session；离开 `IN_PROGRESS` 立即关闭。

解除 Collaborator Link 必须：

- 撤销持久 Collaborator Credential；
- 取消 `WAITING` offer；
- 收回 `IN_PROGRESS` Handoff；
- 关闭关联连接并拒绝旧 credential 重连；
- 在对方 UI 显示连接已解除，而不是静默失败。

### 8.2 能力边界

Recipient 只允许：

- 接受、拒绝和归还自己的 Handoff；
- 打开或重连绑定的 Source Session；
- 在持有 Controller Lease 时发送 prompt；
- 获取被共享 Session 的 transcript；
- 在 allowed roots 内读取必要文件和 diff；
- 根据 access mode 使用受限工具；
- 查看自己的 Handoff 状态和结果。

Recipient 禁止：

- 列出 owner 的其他目录和 Session；
- resume 任意其他 sessionId；
- 创建无关新 Session；
- 修改 Handoff 范围、模式、接收者或有效期；
- 管理设备、bridge、share、凭证、模型账号或 daemon 设置；
- 将接力再次转交给第三人；
- 使用 LAN 全权入口绕过 relay 受限凭证路径；
- 进入 `BYPASS_PERMISSIONS`。

### 8.3 REVIEW_READ_ONLY

首发代码评审面向已经通过首次扫码确认身份的熟人协作者。v1 的目标不是构造企业级零信任
沙箱，而是在不增加过重隔离设施的前提下，做到边界诚实、逐次确认和事后可追溯：

- 文件 Read、搜索和 diff 可用；
- Write、Edit、apply patch 等结构化文件写入工具继续拒绝；
- shell 不做额外沙箱或绝对禁止，仍需逐次 PermissionAsk，Recipient 可以确认执行；
- 每次 prompt、工具调用、shell 命令、权限决定、退出状态、涉及路径以及 Handoff 状态变化都写入
  daemon 持久化的 Handoff History，Initiator 可以在接力详情中查看；
- shell 可能间接修改文件，因此 UI 不得再笼统承诺“绝对只读”，应明确显示：
  “文件工具只读；命令需逐次确认并会记录”；
- 不提供“始终允许”给 shell；网络、密钥、系统目录和高风险命令也不得因 `REVIEW` 自动通过。

现有文件夹分享不是 OS 级沙箱，不能把 workdir guard 宣称为绝对文件系统隔离。v1 通过只读工具
限制、逐次确认和可审计历史降低熟人协作风险。History 的作用是威慑、发现和追责，不是技术上阻止
恶意协作者，也不应被描述为不可篡改审计。未来若面向陌生人或开放高自治跨人修改，再增加真实隔离
执行层、命令沙箱或更强的不可篡改日志。

### 8.4 CONTINUE_SCOPED

`CONTINUE` 作为后续里程碑：

- 发起人必须明确选择允许编辑；
- 文件工具限定在 `allowedRoots`；
- permission mode 最高到 `ACCEPT_EDITS`，永不允许 bypass；
- 危险 Bash、凭证、网络外发和系统级动作仍由 owner 审批或拒绝；
- 返回时必须报告 changed files 与验证结果；
- v1 串行接力期间发起人不继续修改同一工作区，因此暂不创建 worktree。

### 8.5 共享前的明确告知

真正接续同一 Session 意味着 Recipient 可以看到该 Session 的既有 transcript，以及授权 roots 中
执行任务所需的代码。发送前必须展示这一事实，不能仅展示“分享一个评审请求”。

Handoff Brief 是导航与说明，不是 transcript 脱敏机制。选择性历史、摘要式隔离 Session 可作为后续
模式，不能在 v1 文案中虚假承诺。

---

## 9. 协议与 daemon 落点

### 9.1 protocol

新增领域模型与 additive 消息，命名可在实现时微调：

```text
CreateCollaboratorInvite  owner → daemon
CollaboratorInviteCreated daemon → owner
AcceptCollaboratorInvite  peer → daemon
ListCollaborators         owner → daemon
CollaboratorListing       daemon → owner
RevokeCollaborator        owner/peer → daemon
CollaboratorUpdated       daemon → allowed clients
CreateHandoff       owner → daemon
HandoffCreated      daemon → owner
ListHandoffs        owner/recipient → daemon
HandoffListing      daemon → caller
AcceptHandoff       recipient → daemon
DeclineHandoff      recipient → daemon
CancelHandoff       owner → daemon
RecallHandoff       owner → daemon
ReturnHandoff       recipient → daemon
CompleteHandoff     owner → daemon
HandoffUpdated      daemon → attached allowed clients
```

要求：

- 新 enum 必须有 `UNKNOWN` 安全回退；
- 新字段使用 trailing optional/default；
- 旧 App 收到未知帧可以忽略，不影响普通 Session；
- 新 App 连接旧 daemon 时隐藏 Handoff 入口并显示可理解的升级提示；
- `SessionLive.origin` 新增 `handoff:<handoffId>` 值域时保持字段形状不变；
- 所有序列化变更必须补新旧 JSON wire-compat 测试，并由 protocol wire reviewer 检查。

### 9.2 daemon 新模块

建议新增：

```text
daemon/handoff/
├── CollaboratorStore.kt     持久化联系人 identity/link，0600
├── CollaboratorRegistry.kt  首次 pairing、双向连接、push、revoke
├── HandoffStore.kt          持久化实体、Grant 与 Lease，0600
├── HandoffRegistry.kt       状态机、CAS、过期、重启恢复
├── HandoffGuard.kt          session/recipient/range/control gate
├── HandoffCaps.kt           ingress/egress frame 白名单
├── CollaboratorCredential.kt mint/finalize/revoke 与基线零权限 caps
├── HandoffContext.kt        确定性 Session/Git 事实采集
└── HandoffSkill.kt          backend-neutral prompt/input/output contract
```

必须接入的现有咽喉：

- `DeviceSessions.transport`：识别 COLLABORATOR credential，按 Handoff Grant 动态安装 caps/guard；
- `RequestRouter`：路由 Handoff 控制帧并限制目录、history 和 session；
- `SessionRegistry`：在 session open/reattach/send/cancel/answer/permission 上检查 Lease；
- `Conversation`：给消息与工具事件记录操作者 provenance；
- `BridgeRegistry` 同族 binding：复用 ticket-PSK、provisional finalize、revoke；
- push hook：等待接受、接受、归还、拒绝、超时与收回通知；
- idle reaper：活跃 Handoff/Lease 不能被当普通闲置会话错误回收。

### 9.3 relay

v1 不增加跨账号数据面路由，不新增明文授权表，不持久化 Collaborator 标签、Handoff 关系或正文。
relay 仍会保存现有配对机制所需的 opaque device id、公钥、credential hash 与 push routing；它不知道
该受限 device 在产品语义上被命名为哪位同事，也看不到发给他的协作内容。

首次连接时，Recipient App 兑换成 owner 账号内的长期受限 collaborator device，沿用现有 device →
relay → owner daemon 路径。后续 Handoff offer 与接续流量复用该 E2E 连接；离线时 relay 只发不含
正文的 push 提醒，App 上线后从 daemon 拉取密文 offer。

双向联系人底层是两条有向受限注册，不要求 relay 支持 account A → account B 的明文关系或路由表。
需要评估 collaborator credential 是否计入现有 headless/guest 容量，但不得为此改变 relay 零知识边界。

### 9.4 mobile / desktop

最低 UI 面：

1. 轻量“协作联系人”列表与解除连接；
2. 首次连接二维码/链接与安全指纹确认；
3. Session 顶部“找同事接力”；
4. Recipient 选择器、最近协作与“连接新同事”；
5. Handoff 草稿预览与共享范围确认；
6. Recipient 接受预览；
7. `WAITING` 锁定 banner；
8. `IN_PROGRESS` 的 controller 与旁观状态；
9. Recipient “完成并归还”；
10. Initiator “撤回接力”；
11. Session 内 Handoff 结果卡片；
12. Session 详情中的历史 Handoff 列表。

不新增独立社交首页、Agent 页面或组织后台。

---

## 10. v1 产品规则

以下均为已确认默认值：

| 决策 | v1 规则 |
|---|---|
| 首发类型 | `REVIEW` |
| 执行位置 | Initiator 的 daemon 与工作区 |
| 接收方要求 | cc-pocket App；不要求 daemon |
| Session 语义 | 同一 Session 串行独占接续 |
| 发起人行为 | 默认等待；可以旁观、撤回，不能同时输入 |
| 并行 | 不支持 |
| 文件修改 | REVIEW 禁止结构化文件写工具；shell 逐次确认并留痕，可能产生修改；CONTINUE 后置 |
| 接收者 | 单个定向 Recipient |
| 联系人 | 首次扫码建立私有 Collaborator Link，后续直接选择 |
| 每次 Handoff | 不扫码；通过既有 E2E Link 投递 offer，临时 Grant 动态授权 |
| 联系方向 | UI 可显示双向联系人；底层保持两条有向 credential |
| 通知与发现 | 已连接联系人使用隐私 push；无公开目录或全局搜索 |
| 上下文 | 明确共享完整 Session transcript 和授权目录 |
| Relay | 不改跨账号路由，不见明文 |
| Skill | cc-pocket 内置、按需注入、backend-neutral contract |
| 安全事实源 | daemon，不是 App 或 prompt |
| Handoff 数量 | 每个 Session 同时最多一个非终态 Handoff |
| 链式转交 | 禁止 |

---

## 11. 分阶段实现

### M1：领域与安全骨架

内容：

- protocol 模型与 wire-compat 测试；
- CollaboratorStore / Registry、首次配对、列表与 revoke；
- HandoffStore / Registry / 状态机；
- Controller Lease；
- COLLABORATOR credential、基线零权限 Caps、Handoff Grant 与动态 Guard；
- daemon 重启和过期恢复。

验收：

- 两个测试设备对同一 Handoff 竞争接受，只有一个成功；
- 同一联系人完成两次 Handoff，第二次不产生 pairing ticket 或扫码流程；
- 没有活跃 Handoff Grant 的 Collaborator 无法打开任何 Session；
- 非 controller 的 `SendPrompt` 必须被 daemon 拒绝；
- Recipient 无法打开任何其他 Session 或目录；
- revoke、超时和 daemon 重启后均不会出现双 controller。

### M2：Review 端到端闭环

内容：

- Session 内创建入口；
- Skill 构造 Handoff Brief；
- 从 Collaborator 选择、E2E offer、push 与接受；
- 同 Session 重放与接续提示；
- REVIEW_READ_ONLY；
- 旁观、归还、结果卡片和通知。

验收：

1. Panda 与 Frank 首次扫码建立 Collaborator Link；
2. Panda 在一个真实 Codex/Claude Session 中选择 Frank 发起代码评审；
3. Frank 不再扫码，只从通知进入并用另一台已安装 cc-pocket 的手机接受；
4. Frank 看到原 Session、diff 和 Handoff Brief；
5. Panda 在期间只能旁观，任何输入被明确拒绝；
6. Frank 能完成评审；结构化文件写工具被拒绝，若确认执行 shell，则命令及结果进入 Handoff History；
7. Frank 归还后 Panda 立即恢复控制；
8. Panda 再次选择 Frank 发起另一条 Handoff，全程无二维码；
9. 结果卡包含 verdict、findings、文件行号、验证和下一步；
10. relay 日志与存储不出现联系人标签、Handoff 关系、prompt、brief、路径或结果明文；仅保留既有
    opaque device routing 元数据。

### M3：Continue 接力

内容：

- CONTINUE_SCOPED；
- 发起人明确授权编辑；
- changed files 与测试结果回执；
- owner 危险动作审批；
- 长任务断线重连和主动收回。

验收：

- Recipient 可以在明确 roots 内继续任务；
- Initiator 等待期间不存在工作区并发写；
- 超范围写、bypass 和危险命令 fail closed；
- 归还时原 Session 可直接继续，而非创建无上下文新会话。

### M4：验证后再决定

只有真实使用证明需要时再评估：

- 多设备归并为一个跨设备用户身份；
- 摘要式/选择性历史接力；
- fork + worktree 并行子任务；
- 在 Recipient 电脑执行；
- daemon-to-daemon Peer Call；
- 多人评审或串联接力。

这些能力不得提前进入 M1–M3。

---

## 12. 测试矩阵

### 12.1 状态机单元测试

- 每个合法状态转换；
- 所有非法转换；
- 重复接受、重复归还、归还后发送；
- cancel/accept、recall/return、expire/accept 并发竞争；
- 时钟推进与 daemon 重启恢复；
- unknown enum 安全回退。

### 12.2 安全测试

- Handoff key 不能作为 owner/guest/bridge key 使用；
- 凭证不能换 Handoff ID、sessionId、device 或 roots；
- Recipient 不能 list/resume 其他 Session；
- owner 在 Recipient Lease 期间不能从另一设备发送 prompt；
- REVIEW 拒绝 Write/Edit 等结构化写入；Bash 必须逐次确认并生成可查询的审计记录；
- 接收页诚实显示“文件工具只读；命令需逐次确认并会记录”，不声称 OS 级只读；
- History 记录 actor、prompt、工具/命令、权限决定、结果、路径和状态变化，且 Recipient 不能通过协议改写；
- revoke 立即终止在途权限和后续重连；
- relay 只见密文；
- 降级旧 daemon 时 HANDOFF key fail closed。

### 12.3 集成测试

- App A → relay → daemon A → App B 的完整建联、offer、接受、接续、归还；
- Source Session 冷态 resume 与热态 reattach；
- App A/App B 各自断线重连；
- daemon 在 WAITING/IN_PROGRESS/RETURNED 各状态重启；
- Agent turn 执行中 recall；
- push 点击回到正确 Handoff 与 Session；
- Claude、Codex 分别跑通，OpenCode 不支持时明确失败而非降级越权。

### 12.4 UI 测试

- 发起前共享范围与 transcript 告知；
- 等待、旁观、接续、归还各状态无歧义；
- 非 controller composer 不可输入且解释原因；
- offer 过期、被撤回、已被他人接受均有明确终态；
- 结果卡可定位 findings 的文件与行号。

---

## 13. 成功标准与隐私遥测

首轮验证只记录枚举和时长，不记录 prompt、路径、diff 或结果正文：

- Handoff created / accepted / declined / returned / recalled / expired；
- 从创建到接受、从接受到归还的时长；
- 类型（Review / Continue）；
- 是否重复与同一位已知接收者协作（仅本地或不可逆匿名标识）；
- 创建后完成率；
- 7 日内重复发起率。

产品验证标准：

- 真实用户不用手工重写大量背景即可发出评审；
- Recipient 能在 1 分钟内理解“做到哪里、要我做什么、怎么归还”；
- 完成一次协作过程中没有双写、越权或 Session 分叉；
- 返回结果能让原用户和原 Agent 直接继续；
- 同一协作关系出现重复使用，而非一次性演示。

---

## 14. 与旧 Peer Call 方案的关系

旧方案的关键命题是“A 的 daemon/Agent 调用 B 的 daemon/Agent”，需要 `PEER` credential、
`PeerClient` device 腿、长期 Grant/Link 以及 daemon-to-daemon 调用。

本文确认的命题不同：

> **A 在自己的 Session 中需要 B 接一棒；B 的 App 临时进入 A 的 daemon 和 Session。**

因此当前实现顺序为：

1. 暂停 `PeerClient`、AgentSpace 与长期 Peer Grant；
2. 先实现 Session Handoff、COLLABORATOR credential、Handoff Grant 和 Controller Lease；
3. 以代码评审闭环验证跨用户协作需求；
4. 只有出现明确的“必须在接收者电脑执行”需求，才重新评估 daemon-to-daemon Peer Call。

`PEER-CALL.md` 保留为历史技术探索，不再作为当前实现依据。

---

## 15. 一句话产品表达

> **你不用把工作重新解释一遍，把当前 Session 接给同事就行。**
