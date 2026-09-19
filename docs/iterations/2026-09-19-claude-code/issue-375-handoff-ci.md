# #375：Handoff 权限切断测试的时序稳定性

来源：[Issue #375](https://github.com/heypandax/pairlet/issues/375)及 2026-09-15 评论。批次 3；建议 P2。状态：已有修复基础上的定位方案，未运行本轮测试。

## 已知状态

原始失败是 expiry sweep 后收件方帧数偶发多一帧。当前主线已含 `44905bcf` 的相关测试修复：`awaitLive` 等带 seededTitle 的完整 SessionLive；计数前调用 `fanOutPing`。不要重写一份旧方案已经做过的等待逻辑。

最新评论又报告 `an_idle_recall_cuts_the_recipients_view` 偶发失败，断言是 owner 应在 switchMode 返回前收到广播。当前 `fanOutPing` 仍在 `registry.switchMode(...)` 返回后立即检查集合。是否存在异步回调、相同状态不广播或别的竞争，尚需检查生产链路和实际失败。

盘点时 main 的 [CI run 34971179095](https://github.com/heypandax/pairlet/actions/runs/34971179095) 的 test job 为 failure；本轮没有取得证明具体失败用例的日志，**不能归因于 #375，也不能据此判定新竞态已复现**。

入口：

- [HandoffTerminalSinkCutTest.kt](../../../daemon/src/test/kotlin/dev/ccpocket/daemon/handoff/HandoffTerminalSinkCutTest.kt)：awaitLive、fanOutPing、terminalCutsTheRecipient。
- [SessionRegistry.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/session/SessionRegistry.kt)、[Conversation.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/conversation/Conversation.kt)：switchMode／广播语义。
- [HandoffService.kt](../../../daemon/src/main/kotlin/dev/ccpocket/daemon/handoff/HandoffService.kt)：仅核对切断契约，不先修改生产撤权逻辑。
- [ci.yml](../../../.github/workflows/ci.yml)：Ubuntu 测试与失败报告产物。

## 不可削弱的断言

1. 终态前收件方实际收到此会话的广播，测试不是从未绑定成功的假阳性。
2. 终态后 owner 仍收到新产生的会话数据，证明会话没有被整体关闭。
3. 终态后收件方不会收到新的会话帧，同时仍能收到 Handoff 状态通知。
4. return、idle recall、graceful recall、complete、expiry 及 waiting 下 decline/cancel 的相关边界继续覆盖；真实 scripted turn 的隔离断言保留。

## 实施步骤

1. 获取失败 run 的测试报告和 SHA；报告过期或无权限时，明确记为未取得，再用本地同 SHA 与当前 HEAD 做有界复现。不要从 job 名猜失败根因。
2. 跟踪 switchMode 到 sink 的执行模型，确认“函数返回”是否承诺完成所有目标广播。给 fixture 增加可观察的事件／广播完成屏障，而不是简单延长 sleep。
3. 在终态前，等待准确对应本轮 ping 的 owner 与 recipient 投递完成，再取 recipient 基线；匹配 convoId＋本轮模式／可关联事件，不使用可能命中旧帧的 any 判断。
4. 终态完成后产生新的可关联广播，等待该广播的整个 fan-out 完成，再验证 owner 收到、recipient 未收到。**只等 owner 收到不能证明 recipient 不会稍后收到**；必要时在 test seam 中控制调度／收集发送完成，屏障必须覆盖整次分发。
5. 对无 agent turn 的 lazy open，不能等待永远不会出现的 TurnDone；当前 seededTitle 等待保留其用途。模式切换需确保确实引起状态变化，避免 no-op 导致没有广播。
6. 用可控延迟／调度故意把广播推迟到原断言窗口之外，让旧 helper 的竞态稳定暴露，再证明新 helper 在相同顺序下通过。保留超时，超时时打印脱敏事件次序，避免测试永久挂起。
7. 若证据表明终态后实际仍有新帧泄漏，这是生产权限缺陷，不能归类为测试噪声。记录证据并单独提出最小生产修复范围，不通过重新取基线、丢帧或放宽断言吞掉它。

## 验证策略

- 先针对本类所有方法跑一遍，并验证受控延迟场景；证据仍有时序不确定性时，固定执行例如 20 次的诊断样本，记录每次而非“直到绿”。这个次数只是本轮调查计划，不加到永久 CI。
- 新回归必须在故意恢复旧竞态的 helper 下失败、正确屏障下通过；同时验证若让 revoked recipient 真收到后续帧，测试仍能失败。
- 最后跑 daemon 测试与正常 CI；不加 retry 插件、不吞 assertion、不提高允许帧数、不用 arbitrary sleep 当稳定性证明。
- Windows 可能跳过依赖 sh 的 scripted backend 用例，需在正常 POSIX／Ubuntu 环境补齐；不为“观察”新建无限压力环境。

本单预期只改测试／必要的测试可观察接口，无产品发布需求。是否可关闭取决于根因已解释、断言有效及相关 CI 验证，不以一次成功或一组重跑成功作为唯一证据。

## 结果记录（实施后填写）

- 失败 SHA／用例／实际错误与根因：**CI 失败报告未取得**（本机无 GitHub CLI 与 token，run 34971179095 的日志与 SHA 都读不到，不作归因）。本轮在基线 `e5ee088f` 上做本地有界复现：`an_idle_recall_cuts_the_recipients_view` 连同全类循环 150 次、并用单线程 `limitedParallelism(1)` 挤压调度，均未复现失败，因此**新竞态未在本机复现**。跟踪执行模型的结论是：`registry.switchMode` → `Conversation.switchMode` →（no-op 分支或 `recordPendingSettings`）→ `sink.emit(live(...))`，而 `sink` 是对 `sinks.values` 的顺序、全程 `await` 的挂起扇出循环——**函数返回确实完成了本帧对当时在册 sink 的整次分发**。真正不可靠的是旧 helper 的断言方式，不是扇出本身：它用「switchMode 返回后 owner 帧表里有没有这帧」一次性承担三件互不相同的判断，而三种失败看起来完全一样——会话已不在册（`get(convoId)?.switchMode(…) ?: Unit` 是静默 no-op）、owner 不在扇出集合内、模式切换本身是 no-op 因而没有新东西可观察；并且它推导目标模式用的是 owner 的历史帧，历史帧可能落后于会话真实模式，正好会退化成第三种。另外 `awaitLive` 是 `any` 形状的等待，同一帧表上更早会话的 `SessionLive` 会立刻满足它（收件方同时评审两个会话时命中旧帧）。
- 屏障语义、负向故障注入及提交：新增两个生产侧只读测试缝——`Conversation.fanOutProbe`（在**最后一个 sink 的 emit 返回之后**回调一次，携带该帧实际送达的 `sinkKey` 集合；生产不安装，null 时零分配）与 `SessionRegistry.conversationForTest`。`fanOutPing` 改为：目标模式取自会话自身的 `Conversation.currentMode()`，保证确实发生状态变化、不会 no-op；断言落在**本轮这帧的分发记录**上（convoId ＋ 本轮模式，只看 mark 之后的窗口，不用可能命中旧帧的 `any`）；「收件方是否在本次广播里」改成读分发时刻的集合成员关系，而不是「到现在为止还没收到」；owner 的**到达**另用有界等待，并关联到同一帧实例。终态后再加一条更强的断言：该会话在终态之后**完成**的每一次分发都不含收件方身份——覆盖整次 fan-out 与所有生产者（含 `Conversation.open` 自己 `scope.launch` 的 announce 尾巴），而不只是测试自己发的那一帧。`awaitLive` 改为携带开会话前的 convoId 排除快照。负向注入两条，都固化成常驻用例：其一，给 owner 注入「先确认、稍后投递」的排队式 transport，把广播推到旧断言窗口之外——旧 helper 每次都在它自己那条断言上失败，新屏障在同一顺序下通过；其二，在终态后把收件方 sink 重新挂回会话（模拟切断漏掉的视图），收件方帧数与分发记录两条检查都必须转红。另外临时把 `HandoffService.cutRecipientSinks` 改成空实现做生产回归验证，终态用例如期转红（`the recipient must receive NOTHING … expected: <3> but was: <4>`），随后已还原。未提交、未推送。
- 定向／有界重复／daemon／CI 结果：`:daemon:test --tests '*HandoffTerminalSinkCutTest'` 通过，13 个用例、0 失败。有界重复固定 20 次（每次 `--rerun`），**20 次全部通过、0 失败**，逐次记录在案，不是「跑到绿为止」。相邻受影响套件 `*Conversation* *SessionRegistry* *Handoff* *Collaborator*` 有 7 条失败，已用「还原改动跑同一命令」对照确认**基线同样是这 7 条**（`ConversationPushTest`、`CollaboratorGrantEnforcementTest`、`SessionRegistryBridgeApprovalRouteTest`，均依赖 `sh` 脚本化 agent），属 Windows 环境不满足，非本次补丁引入。依赖 sh 的 `the_recipient_stops_receiving_assistant_chunks_the_moment_control_returns` 在 Windows 上提前返回，记为**未执行**，需在 POSIX／Ubuntu 补齐。正常 CI 本轮未执行。
- 是否存在生产泄漏、剩余观察与关闭建议：**未发现生产泄漏**——终态之后没有任何一次完成的分发把收件方列入扇出集合；撤权逻辑本身未改动（`HandoffService.kt` 仅核对契约）。剩余观察：CI 失败报告始终未取得，本机也未复现原报告的偶发失败，所以「根因已解释」只覆盖到「旧断言方式为何不可靠、新屏障强在哪里」，不能宣称已复现并消灭线上那一次失败。建议：先在 Ubuntu CI 上跑完整 daemon 测试（含被 Windows 跳过的 scripted backend 用例）；若后续再失败，新断言会直接指出是「没有分发记录」「分发记录里没有 owner」还是「分发记录里仍有收件方」，并附脱敏的分发次序，届时再据此判断可否关闭。
- 提交状态（2026-09-19 补记）：已提交到 main，提交 `63d41463`；未推送、未部署、未发布。上文“未提交”指子任务实施当时的状态。
