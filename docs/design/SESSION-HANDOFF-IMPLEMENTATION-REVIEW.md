# Session Handoff 实现复审与修复清单

> 日期：2026-08-02  
> 状态：待实现；作为 `SESSION-HANDOFF.md` 的实现补充  
> 面向：Claude Code / 后续 coding agent  
> 范围：当前工作区内 Claude 已实现的 Collaborator Link + Session Handoff 代码

## 1. 结论

当前代码已经搭出正确骨架：

- 首次通过二维码或深链建立长期 Collaborator Link；
- 后续从联系人列表直接选择，不再为每次 Handoff 分享二维码；
- Collaborator credential 默认没有 Session 权限；
- Handoff 是挂在 Source Session 上的持久化实体；
- 接受后通过临时 Grant + Controller Lease 控制同一 Session；
- device identity 来自 E2E transport，而不是客户端自报字段；
- 协作者 capability 使用白名单，新增 wire frame 默认不可用。

但当前版本还不能完成真实双人闭环：接收方连接会被当成普通电脑、第一条 offer 无法自然到达、接受后
不会自动进入 Source Session；另外 turn 执行中的 recall 还不是真正的即时收权。本文件列出需要继续完成的
实现，不要求推翻现有领域模型。

## 2. 已确认的产品与威胁模型调整

### 2.1 熟人协作，不按陌生人零信任设计

Collaborator Link 必须经过首次扫码和安全指纹确认，典型对象是同事、朋友或长期合作伙伴。v1 不增加：

- OS/container 级 shell 沙箱；
- 企业 IAM、组织后台或管理员策略；
- 公共联系人目录；
- 不可篡改企业审计系统；
- 每次 Handoff 重新扫码。

### 2.2 REVIEW 的新边界

保留现状中的结构化写工具硬拒绝：`Write`、`Edit`、`MultiEdit`、`NotebookEdit`、`apply_patch`。

调整此前复审意见：Bash 不再被视为必须阻断上线的 P0。允许 Bash 进入普通 PermissionAsk，由当前
Recipient 逐次确认，但必须满足：

1. 不允许对 Bash 使用 `remember=true` / “始终允许”；
2. 接受页、执行页和 History 页均诚实说明 shell 可能产生文件修改；
3. daemon 记录由谁、在哪个 Handoff、何时确认并执行了什么；
4. Initiator 在接力期间和归还后都能查看记录；
5. History 是威慑、发现与追责机制，不在文案或代码注释里宣称它能技术性阻止恶意行为。

UI 统一文案建议：

> 文件工具只读；命令需逐次确认并会记录。

不要再只显示 `READ ONLY`，否则 shell 可以写文件而 UI 仍承诺绝对只读，构成边界误导。

## 3. P0：打通 Recipient 的 offer → accept → open 闭环

### 3.1 根因

当前 `PairedDaemon` 没有 credential role。扫描 collaborator invite 后仍走普通 `doPair()`：

- App 把该绑定放进“电脑”列表并等待 `Directories`；
- App 连接后发送 `ListDirectories`；
- daemon 的 `CollaboratorCaps` 正确拒绝目录发现；
- Repository 等不到 `Directories`，约 6 秒后显示“电脑离线”；
- `HandoffService.attach(... recipientDeviceId=...)` 只在协作者发来一个允许的 frame 后发生；
- App 没有 sessionId 时不会发送 `ListHandoffs`，所以第一条 offer 也没有订阅者。

即使手工把 offer 塞进 Repository，接受页目前只挂在 `ChatScreen` 内；Recipient 在没有 Grant 前又无法打开
任何 Chat。点击接受也只发送 `AcceptHandoff`，收到 `IN_PROGRESS` 后没有自动 `OpenSession`。

### 3.2 实现要求

1. 给本地 pairing 记录增加向后兼容的 binding role，例如：
   - `OWNER`（缺省，兼容旧数据）；
   - `GUEST`；
   - `COLLABORATOR`。
2. pairing store 不应只用 `accountId` 作为唯一键；至少使用稳定 binding id 或 `(accountId, deviceId)`，避免
   同一 daemon 下不同权限 credential 互相覆盖。
3. collaborator binding 使用 inbox-only 连接状态：
   - 收到 relay `Attached` 后发送无过滤条件的 `ListHandoffs()`；
   - 收到 `HandoffListing` 即视为 collaborator channel ready；
   - 不等待 `Directories`，也不显示“电脑离线”；
   - foreground/reconnect 后重新拉取 `ListHandoffs()`。
4. daemon 在协作者通过 transport 身份校验后允许 `ListHandoffs()`，并把该 sink 注册为只接收
   `recipientDeviceId == 当前 deviceId` 的 fan-out target。
5. 增加独立于 ChatScreen 的 Incoming Handoff 入口：
   - App 根级覆盖层、Inbox 或 Collaborator binding 首页均可；
   - 可以查看 brief、Source Session、共享路径、操作边界；
   - 可以 Accept / Decline；
   - 不依赖已有 `convoId`、`workdir` 或 `sessionKey`。
6. `AcceptHandoff` 成功并收到属于本机 deviceId 的 `IN_PROGRESS` 后，自动发送：
   - `OpenSession(workdir = handoff.workdir, resumeId = handoff.sourceSessionId, agent = handoff.agent)`；
   - 其余 mode、takeOver、pathScope 仍由 daemon clamp，不能信任 App。
7. Accept 按钮必须有 waiting 状态，直到 daemon 确认或明确报错，不能点击后立即假装成功。
8. Offer、Accept、Decline、Return、Recall、Expire 都需要在重连后通过 daemon truth 恢复。

### 3.3 Initiator 旁观

Recipient 首次 `OpenSession` 时，daemon 会把 owner 的 wall-less hot Conversation 关闭后以 Grant 重新构建。
当前 owner 的 sink 没有自动迁移，测试只能靠 owner 手工再次 `OpenSession` 才成为旁观者。

实现必须保证：

- Recipient 的受限 Conversation ready 后，Initiator 自动附着到同一个 convo；
- Initiator 能实时看到 AssistantChunk、ToolEvent、PermissionAsk 状态和 TurnDone；
- Initiator 的 SendPrompt/Cancel/Verdict 仍被 Controller Lease 拒绝；
- 不要求 Initiator 手工退出再进入 Session；
- 不得为了保留旁观而让旧的 wall-less Conversation 继续成为第二个 writer。

可以选择迁移旧 sinks，或增加明确的 `handoff session ready` 通知让 owner 自动 re-open；不要依赖竞态性的
`HandoffUpdated(IN_PROGRESS)` 立即 re-open，因为它可能发生在 Recipient 尚未完成受限 Conversation 重建之前。

### 3.4 离线通知

设计仍要求离线 Recipient 收到不含正文的隐私 push：

- push 只包含可路由到 Handoff inbox 的 opaque id/route，不包含 brief、路径或 transcript；
- App 被唤醒后使用 collaborator credential 拉取密文 `ListHandoffs()`；
- push 丢失时，foreground/reconnect pull 仍能发现 offer；
- push 只是提醒，daemon 中的 Handoff 状态才是事实源。

### 3.5 验收

1. A、B 只做一次 collaborator QR 建联；
2. A 以后直接从联系人选择 B；
3. B 在线时不进入目录页、不显示 A 的电脑离线，并在数秒内看到 offer；
4. B 离线时收到无正文 push，上线后看到同一 offer；
5. B 点击 Accept 后自动进入 A 的 Source Session；
6. A 自动进入旁观态，能实时看到 B 的评审过程但不能输入；
7. B Return 后 A 无需重建上下文即可继续原 Session；
8. 第二次 Handoff 全程不出现二维码。

## 4. P1：Handoff History（替代重型 shell 隔离）

### 4.1 记录内容

新增 daemon 生成、客户端不可自报 actor 的审计事件。建议至少覆盖：

| 事件 | 必需字段 |
|---|---|
| CREATED / ACCEPTED / DECLINED | handoffId、sessionId、actorDeviceId、actorLabel、timestamp |
| PROMPT_SENT | actor、prompt 摘要或截断文本、timestamp |
| TOOL_REQUESTED | tool、目标路径、timestamp |
| PERMISSION_DECIDED | askId、tool、ALLOW/DENY、remember、actor、timestamp |
| COMMAND_STARTED | redacted command preview、raw command hash、actor、timestamp |
| COMMAND_FINISHED | exit code / cancelled / timeout、duration |
| FILE_TOUCHED | canonical path、来源 tool/command（能确定时） |
| RETURNED / RECALLED / COMPLETED / EXPIRED | actor、reason、timestamp |

约束：

- actorDeviceId 必须来自 transport 或当前 Lease，不从 wire event 字段读取；
- command preview 复用现有 secret redaction，并限制长度；不保存 stdout、文件正文和 secret；
- raw command 只保存稳定 hash，用于关联，不把潜在 token 再复制进审计记录；
- 每个 Handoff 有数量上限和终态保留策略，避免无限增长；
- History 存储失败不能静默伪装为“已经记录”：至少记录 daemon 错误并在 UI 标识 history incomplete；
- Recipient 只能读取与自己绑定的 Handoff History，不能读取其他协作者或 owner Session。

### 4.2 数据与 wire 建议

不要把不断增长的完整 History 塞进每次 `HandoffUpdated`。建议增加独立存储和分页 frame：

- `HandoffAuditStore` / `handoff-audit.json`；
- `ListHandoffHistory(handoffId, beforeSeq?, limit)`；
- `HandoffHistoryListing(handoffId, items, nextBeforeSeq?)`；
- `SessionHandoff` 只追加可选的 `historyCount` / `lastHistoryAt`，均带默认值。

所有新增 protocol 字段保持尾部可选/default；新增 enum 必须 tolerant decode 到 `UNKNOWN`，并补 common wire
compat tests。daemon 端按 owner / bound recipient 做可见性过滤。

### 4.3 UI

- Initiator 的 Handoff 详情增加“活动记录”；
- Recipient 可查看自己的记录，知道哪些动作会被留痕；
- 时间线优先显示人和结果，不渲染原始协议名；
- Bash 审批卡显示“本次命令将记录到 Handoff History”；
- Return 结果卡可以汇总命令数、工具数、涉及文件数，并可展开明细；
- 若日志写入失败，双方都看到“部分活动可能未记录”，不能显示绿色完整状态。

### 4.4 验收

- B 执行 `ls`、批准一条 Bash、拒绝另一条 Bash、读取文件并 Return；
- A 的 History 能看到顺序、actor、决定、命令摘要、退出状态和路径；
- History 中没有 stdout、文件内容或明文 token；
- B 不能请求别人的 History，也不能通过 protocol 伪造 actor；
- daemon 重启后记录仍在；
- 审计存储不可写时 UI 明确提示不完整。

## 5. P1：Recall 必须在稳定边界真正收权

当前 `RecallHandoff` 立即删除 Lease，但正在执行的 turn 继续 headless 运行。这样 UI 已显示“已撤回”，旧 turn
仍可能继续读文件、运行已经批准的命令或产生输出。

实现要求：

1. 若 Session idle：直接 `IN_PROGRESS -> RECALLED` 并删除 Lease；
2. 若 turn executing：
   - `requestRecall(sessionId)` 标记 `recallRequested=true`；
   - 拒绝 Recipient 后续 prompt/verdict；
   - 向当前 backend 发 interrupt/cancel；
   - 等待 TurnDone、进程确认停止或有上限的 timeout；
   - 到达稳定点后再转 `RECALLED`、删除 Lease并通知双方；
3. Initiator 在稳定收权前保持等待态，不能和旧 turn 并发输入；
4. 已运行的外部子进程若无法立即停止，要在 UI/History 中诚实标明；
5. restart recovery 继续以 daemon store + Lease 为事实源。

验收必须覆盖 Claude 与 Codex 的 executing-turn recall，不能只测 idle registry transition。

## 6. P1：V1 只接受已经完整实现的授权组合

Protocol 已定义 `CONTINUE` / `CONTINUE_SCOPED`，但注释明确它们是后续里程碑。当前 registry 只拒绝
`UNKNOWN`，因此 raw client 可以创建 CONTINUE Grant；接收页却始终硬编码 REVIEW / READ ONLY。

V1 要求：

- daemon 仅接受 `kind == REVIEW && access == REVIEW_READ_ONLY`；
- 其他已知但尚未实现的组合返回明确的 `handoff_not_supported`；
- UI 必须从 daemon 返回的 `kind/access` 渲染，不再硬编码；
- UNKNOWN 继续 fail closed；
- 后续开放 CONTINUE 时，再一次性实现 allowedRoots 校验、编辑 ceiling、对应 UI 和测试。

注意：这里的 `REVIEW_READ_ONLY` 按第 2 节的新定义解释——结构化文件工具只读，Bash 逐次确认并留痕；
UI 应显示诚实说明，而不是绝对 `READ ONLY`。

## 7. P2：修复 collaborator QR / 深链路由

邀请编码为 `ccpocket://collab#...`，但 App 根级 deep link 和通用 PairingScreen 扫码都调用只识别普通
pair URL 的 `handlePairUrl()`。目前只有 Join Folder 内的专用输入能 decode collaborator invite。

实现要求：

- 增加统一 `handleIncomingLink(raw)`：按 scheme/host 先分发 collab、share、pair、handoff/push route；
- collaborator link 必须先进入 `ConfirmConnectionScreen`，不能扫描后直接 redeem；
- iOS `onOpenURL`、Android intent、PairingScreen scanner、Join Folder paste 共用同一解析函数；
- bare base64 只在明确的粘贴入口支持，不在任意 deep link 中猜测；
- 补 full URI、fragment 丢失、错误 base64、过期 ticket 和取消确认测试。

## 8. P2：修复 handoff 测试并发问题

强制重跑 daemon handoff tests 时出现：

```text
CollaboratorGrantEnforcementTest
the_owner_spectates_the_rebuilt_convo_via_ordinary_reattach
java.util.ConcurrentModificationException
```

`Collections.synchronizedList` 只保护单次方法，不保护 `none` / `filterIsInstance` 的整个迭代。

修复要求：

- `awaitLive()` 在同步块中复制 snapshot 后查询，或改用 `CopyOnWriteArrayList` / Channel；
- 不通过增加 delay 掩盖竞态；
- 相关测试强制重复运行至少 20 次无失败；
- 增加真实 recipient bootstrap 集成测试，不能只在 fixture 中手工调用 `routeAsCollaborator(ListHandoffs)`。

## 9. 建议实现顺序

1. pairing binding role + collaborator inbox connection；
2. 全局 incoming offer、Accept 后自动 OpenSession；
3. Initiator 自动旁观；
4. Handoff History 数据、采集点和 UI；
5. executing-turn graceful recall；
6. V1 kind/access 服务端校验与诚实文案；
7. deep link 统一分发；
8. 测试竞态、端到端测试和回归。

前 3 步完成后再做双人真机试用；History 和 recall 完成前不要宣称“完整可追责”或“撤回后立即停止”。

## 10. 重点代码位置

- `protocol/.../Collaborator.kt`：联系人、invite、credential direction；
- `protocol/.../Handoff.kt`：实体、状态、Grant、Lease、frames；
- `daemon/.../handoff/CollaboratorCaps.kt`：协作者双向 frame 白名单；
- `daemon/.../handoff/CollaboratorGuard.kt`：Grant 与 Source Session 绑定；
- `daemon/.../handoff/HandoffRegistry.kt`：状态机、Lease、recallRequested；
- `daemon/.../handoff/HandoffService.kt`：fan-out、recipient sink、reconcile；
- `daemon/.../agent/PermissionBridge.kt`：文件工具拒绝、Bash ask、审计采集点；
- `daemon/.../relay/DeviceSessions.kt`：transport device identity、credential kind；
- `daemon/.../server/RequestRouter.kt`：create/list/mutation/drive gates；
- `daemon/.../session/SessionRegistry.kt`：hot-to-cold rebuild、旁观 reattach；
- `mobile/.../pairing/Pairing.kt`：本地 binding role 与持久化键；
- `mobile/.../data/PocketRepository.kt`：连接 phase、inbox pull、accept/open；
- `mobile/.../ui/App.kt`：根级 incoming handoff；
- `mobile/.../ui/handoff/`：确认、接受、历史和返回 UI。

## 11. 完成定义

实现完成必须同时满足：

- 首次 QR 后，后续 Handoff 只选联系人；
- Recipient 在线/离线都能发现 offer；
- Accept 后自动进入同一 Source Session；
- Initiator 自动旁观且无法并发输入；
- Return 后 Initiator 直接继续；
- Recall 执行中会先中断并在稳定边界收权；
- REVIEW 的 UI 与真实 shell 行为一致；
- 每个关键动作有 actor 可信、持久化、受可见性约束的 History；
- daemon 不接受未完整实现的 CONTINUE；
- collaborator QR、paste、iOS/Android deep link 都走确认流程；
- 强制重跑 handoff 测试无并发失败；
- `bash scripts/check-all.sh` 全绿；
- 不启动第二个 daemon；如需更新本机 daemon，遵守仓库 `AGENTS.md` 的 detached 规则。

