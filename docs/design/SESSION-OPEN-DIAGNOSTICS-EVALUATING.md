# 应用场景：会话打开失败追踪

状态：场景细节待 P2 按当前代码复核，2026-09-09 更新。本文件是[通用日志与故障追踪实施方案](OBSERVABILITY.md)的场景附录。

事件模型、Firebase Analytics/Sentry 分工、SDK 独立直传、遥测开关、预算和实施批次均以通用方案为准。本文件只定义打开会话的业务阶段与验收条件，不再单独建设上传体系。本文的 trace_id 对应主方案 diag_trace_id，SDK 原生 trace 另行适配。

## 1. 问题与诊断目标

用户反馈“部分会话可以打开，部分不行”，且发生在其他用户的电脑。目前不能确认内容规模是根因，也不能用自己的本机日志替代对方证据。此前检查的 relay 窗口为 2026-09-09 07:54:28–07:59:28 UTC，存在连接替换、限流和心跳超时，未命中帧超大/OOM，但没有与受影响用户建立可靠关联。

当前关键缺口：客户端收到 SessionLive 就取消打开超时；daemon 冷恢复和热重连都可能先发 live 再读取历史。因此“已收到会话响应，但历史未成功显示”会漏出原有监控。解密后 JSON 解码失败、PocketError、历史合并异常也需要进入统一诊断入口。

本场景要定位最后成功阶段，收集读取规模、耗时、实际编码字节与安全栈。App 和 daemon 各自独立上报同一 trace 下的观察，E2E 进度仅用于客户端解释和阶段确认。relay 只提供连接层证据，不接收业务 trace 或会话内容。

## 2. 一次打开的状态与超时

主会话和 [SidePanes](/Users/lidapeng/Desktop/Project/app/cc-pocket/mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/SplitPanes.kt) 使用同一个 `SessionOpenTracker`。追踪与现有业务状态分开，不能因接入诊断破坏会话身份检查、分屏归属或新建会话的重试限制。

| 阶段 | 观测点 | 能证明什么 |
|---|---|---|
| `requested` | 用户/已有逻辑发起打开 | 打开意图 |
| `queued` / `socket_written` | 本地 outbox 入队 / socket 写入完成 | 客户端已排队 / 已交给传输层，不代表对端收到 |
| `daemon_received` | 请求路由通过权限校验 | 目标 daemon 已接收 |
| `live_received` | 原有身份检查接受 SessionLive | 已绑定会话，不代表历史完成 |
| `replay_started` / `replay_ready` | daemon 读取开始 / 回放切片生成 | 读取阶段及规模 |
| `history_received` / `history_applied` | 收到带关联标记的历史 / mergeHistory 成功 | 客户端收到并合并该批数据，不代表像素已呈现 |
| `open_complete` | 本次首屏历史已交付，或明确无需历史 | 电脑侧本次首屏回放已结束 |
| `first_layout` | 对应视图实际布局完成 | 首屏实际出现；作为后续增强 |

规则：

1. `requested → live_received` 保留现有 8 秒等待与允许恢复会话再等 4 秒的策略，不给新建会话增加自动重试。诊断 ID 不参与创建/去重决策。
2. 新双方协议下，首个有效 live 后另启 **15 秒历史诊断期限**，live 本身不结束追踪。此值是初始产品阈值，先灰度评估；未收到/应用历史时报 `history_timeout`，历史已应用但缺完成标记时报 `open_confirm_timeout`；不重发 OpenSession、不杀进程、不清空已有历史。
3. `open_complete(outcome=payload)` 必须与同一 trace/attempt 的 history_applied 同时满足，才记录 `session_open_result=success`。完成标记先处理时继续等匹配历史；不能把发送端完成视为接收端已合并。
4. `empty / up_to_date / not_applicable` 加有效 live 可直接结束加载：分别表示空历史、已有游标追平、新会话无需回放。**不得为了补完成标记额外发送空 ConvoHistory**；空的 full history 当前有清屏语义。ObserveSession 已有的空 full 行为仍按原协议处理。
5. 匹配的异常、业务失败或链路中断提前结束当前追踪。用户切换、取消、分屏关闭或换电脑，终态为 `cancelled`，不发错误报告；App 后台挂起结束本次计时，前台恢复交给现有重连逻辑重新追踪。
6. 自动重试沿用同一 `trace_id`，增加 `attempt`。同一有效视图代际下，任一 attempt 成功可完成打开；旧 attempt 的错误不能终结更新 attempt。已发出的超时主报告不可撤回，迟到成功只记 `session_open_recovered`，避免重复 non-fatal。
7. trace 还需绑定本地 `machine generation + pane generation`，跨电脑、旧连接和旧视图的诊断不得污染当前状态。这些内部身份不上传。保留现有 convo/session 身份校验，诊断帧不是新信任来源。

新增结果摘要 `session_open_result`，带结果、失败阶段、版本、agent、transport、coverage、分桶耗时及大小；若关联 Analytics 明细，仅增加通用方案定义的 diag_event_id 参数，不上传堆栈或注册高基数维度。原有 session_open_timeout 保持口径，两者不能相加成失败总数。即时诊断通过通用 HTTPS 管道上报，Analytics 不承担实时送达保证。

面对旧 daemon，只记录已有确定失败和本地阶段，`coverage=client_only`。没有历史完成协议时，不能把“没有 history 帧”直接判错：合法的空会话和无新增历史本来就可能没有该帧。首屏真正布局信号安排在后续阶段，用布局回调验证，不能用 Compose SideEffect 充当渲染成功。

## 3. daemon 协议与实现落点

### 3.1 增量协议

在 [Messages.kt](/Users/lidapeng/Desktop/Project/app/cc-pocket/protocol/src/commonMain/kotlin/dev/ccpocket/protocol/Messages.kt) 增加以下字段/帧，名称为提案：

| 消息 | 新增内容 |
|---|---|
| `ClientCaps`、`DaemonInfo` | `diagnosticsVersion: Int = 0` |
| `OpenSession` | `diagnosticContext: DiagnosticContext? = null`（至少含 traceId、spanId、attempt） |
| `SessionLive`、`ConvoHistory`、`PocketError` | 同样的可选 DiagnosticContext，仅对该次打开的请求者附加 |
| 新 `SessionOpenDiagnostic : ToPhone` | `traceId`、`attempt`、单调 `seq`、`stage`、可选 `outcome`、有界 metrics、固定安全错误码；堆栈由 daemon 独立直传 Sentry，不通过本帧代传 |

`stage/outcome` 在线上用可校验字符串；新值落为 `unknown`，不能因为新增枚举值丢掉整个消息。指标采用固定结构字段，栈帧结构只允许应用符号、源码文件名、行号，不携带原始异常文本。

启用条件是同一连接的双方 capability 均为 1 且请求有有效 trace。trace 使用随机 128 位值的 32 位十六进制表示；不是授权凭证。无效或超长诊断字段被忽略，业务请求仍按原有规则处理。连接重建后重新协商，不能沿用上一设备的 capability。

新帧每次 attempt 最多 8 条，总量最多 16 KiB，单帧不超过 8 KiB；终态优先保留。诊断缓冲满时丢弃中间进度，不能占满业务 outbox 或阻塞历史发送。首屏完成/失败事件独立于命令列表、后台任务和审批卡回放，这些附属消息不能无限拖延打开结果。

### 3.2 三条打开路径都接入

| 路径 | 落点与要求 |
|---|---|
| 请求接收 | [RequestRouter](/Users/lidapeng/Desktop/Project/app/cc-pocket/daemon/src/main/kotlin/dev/ccpocket/daemon/server/RequestRouter.kt) 在原有鉴权后创建请求级 `OpenTraceContext`，记录接收；请求之前的拒绝只沿用现有安全错误路径 |
| 冷恢复/新建 | [SessionRegistry](/Users/lidapeng/Desktop/Project/app/cc-pocket/daemon/src/main/kotlin/dev/ccpocket/daemon/session/SessionRegistry.kt) 传入上下文；Conversation 的异步 open 子协程内捕获异常并记录阶段，外层 router 的 try/catch 不能代替它 |
| 热会话重连 | `Conversation.replayReattach(newSink, sinceSeq)` 包裹当前请求的回放，不把 trace 存为 Conversation 全局可变字段 |
| 外部会话观察 | ObserveSession 首次扫描与回放记录本次打开；后续 1.5 秒尾读循环不重复报打开成功，后续失败归为独立观察错误 |
| 文件读取 | 复用现有读取过程统计 file bytes、扫描行数、跳过/损坏行数、回放行数及截断情况；避免为了诊断额外再解析整份文件。已有吞错分支须产生安全原因计数，不能把“吞错后空结果”一概称为成功 |
| 编码/发送 | [DeviceSessions](/Users/lidapeng/Desktop/Project/app/cc-pocket/daemon/src/main/kotlin/dev/ccpocket/daemon/relay/DeviceSessions.kt) 在已有序列化结果上记录 JSON UTF-8 bytes、密文帧 bytes、发送耗时，避免重复序列化；加密锁内不调用上报 SDK/执行网络诊断上传 |

所有耗时用各自进程的单调时钟计算；不拿手机时间减电脑时间算网络延迟。文件大小取当前读取的文件/句柄元信息，若并发追加导致变化则记录观测值，不宣称精确快照。

`OpenTraceContext` 与发起者的 sink/连接代际绑定。向该请求者发送时可复制已有业务帧并附加 trace；其他订阅者收到原有业务帧，不收到该 trace 或错误栈。完成标记通过同一有序业务发送通道在历史之后发送，网络发送成功也只代表写入传输层。

所有新异常边界先透传 CancellationException，再处理业务失败；尤其不能把协程取消或观察者正常关闭记成历史读取异常。收发字节统计以历史业务帧为对象，不递归统计诊断帧自己。

只为已有完整权限的自有配对客户端启用首版 E2E 诊断回复。guest、共享会话、bridge 和跨用户控制入口默认不开放；相关出站 allowlist 保持默认拒绝。客户端断线时无法继续送达 E2E 进度，但 daemon 仍按通用方案的遥测设置、Sentry DSN 与 SDK 缓冲策略独立上报；不能借失败上报绕过 E2E、鉴权或设备归属。

### 3.3 混合版本必须验证

| 客户端 | daemon | 期望 |
|---|---|---|
| 旧 | 新 | 无 capability，不发送新帧；旧打开行为正常 |
| 新 | 旧 | `coverage=client_only`；不等待新的完成标记，不误报空历史 |
| 新 | 新 | 协商后开启阶段与完成标记；精确关联 history |
| 新、capability 尚未返回 | 新 | 此次按旧链路执行，不为了诊断阻塞打开；下次再启用 |
| 任意 | 重连/换设备 | 清除上一连接的协商与追踪归属 |

`ignoreUnknownKeys=true` 只解决新增字段，不意味着旧版本认识新的 sealed Frame 子类型。实施时使用旧版本序列化器/固定 wire fixtures 验证，不只做新版自我 round-trip。

## 4. 关键验收

- 冷恢复、热会话重连、外部会话观察三条路径都能给出明确首屏结果。
- 大历史包含中文、密集转义、多图片、超过首屏行数和多 MB 文件，统计基于实际读取与编码过程，不额外扫描整份文件。
- 空会话、全量空历史、空 delta、游标追平和新建会话均保持原有清屏/游标语义。
- live 前读取失败、live 后回放失败、错误 JSON、merge 抛错、发送断开、缺完成标记能产生不同代码或阶段；证据不足不能武断归因。
- 重试、后台、取消、换机器、多设备和分屏不串 trace；旧 attempt 的错误不终结新 attempt；关闭视图不产生 non-fatal。
- 遥测开启、Sentry 配置有效且网络可达时，关闭手机后 daemon 仍能独立上报；没有手机接收诊断不能影响电脑侧记录。
- 通用隐私哨兵、capability 混合版本、出站权限与数据去重验收全部通过。

这里的 history_applied 证明数据已合并，不证明像素已呈现。后续 first_layout 要由真实布局回调提供；只有超时 watchdog 栈时，不得声称已获得阻塞线程堆栈。
