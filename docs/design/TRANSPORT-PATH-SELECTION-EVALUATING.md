# 直连与中继路径选择：适用性评估与实施提案

状态：**已评估并搁置（2026-09-26）：按本文三段式实施不合理，不作为当前实现依据。** Fiveo 评审结论为有条件通过（第 12 节），随后的根源分析（第 13 节）判定：P2P 数据面不立项，阶段 B 与 A1 不进入实施；仅 A0 作为独立缺陷修复另行跟踪。其余章节保留为评估记录，若日后重启须先复核第 3 节基线。

评估日期：2026-09-26。Pairlet 源码基线：`05e2194fc0d5d4a4f12ffd6c774daac450064bbe`。

本文可以独立交接：包含结论、源码依据、推荐方案、边界、分步实施和验收要求。评估仅来自源码阅读，未进行网络故障注入、设备实测或生产数据分析。文中的时间参数是待验证的初始值，不是已达到的性能指标。

## 1. 结论与产品目标

**适合实施，建议分两阶段交付：先做有界的连接竞争，再做前台的稳定直连回切。** 工作量主要在客户端连接所有权、消息队列和恢复流程，属于中等偏大的可靠性改造，不能按“加两个定时器”处理。

当前项目已经支持同机／局域网 E2E 直连，失败后串行回退到 E2E relay。可以在这套能力上改善两个场景：

1. 手机离开家中 Wi-Fi，仍保存旧 LAN 地址。直连遇到黑洞时，中继应尽快开始连接，不必等操作系统判断直连失败。
2. 手机在外通过中继使用，回家后中继仍然健康。客户端应在前台发现直连恢复，在安全时机切回，降低绕路和转发流量。

用户不需要手动选择网络路径；连接选择不应关闭 Agent、创建新会话、重复发送提示词或跳转页面。真实恢复未完成时仍使用现有重连反馈，不能靠一直显示“已连接”掩盖不可用。

本次范围是**已有直连通道与中继之间的选择**。不新增公网打洞、WebRTC、STUN/TURN、内置 Tailscale、多区域 relay 或后台常驻保活。Tailscale 可达地址与普通可达地址一样可被传输层连接，但现有地址发布并不等于已有 Tailscale 自动发现，本次也不补该能力。

## 2. 参考行为与独立实现边界

Orca 参考版本：`stablyai/orca` 的 `da6d483a`（2026-09-26）。本地副本只供评审核对行为，本文和后续实施不依赖 `_local` 存在。

该版本可观察到的行为（评审已按副本核对）：直连先行；直连尚未完成认证时，2.5 秒后开始竞争中继，回到前台时重新计时；走中继期间每 15 秒探测一次直连，30 秒观察窗内连续 3 次认证成功且距上次迁移至少 60 秒才回切；探测失败冷却 60 秒；配对阶段则是直连与中继同时竞速、没有先行窗口。路径包含 LAN、Tailscale 和 relay。面向用户的说明在该仓库的 `docs/site/content/docs/mobile.mdx`（评审前版本引用的 `cloud/README.md` 只描述 relay 转发，不含选路内容，已更正）。

这些是设计参考，不是 Pairlet 的接口或安全证明。按项目[原创实现约定](../ANTIPLAGIARISM.md)「Working rule for implementers」一节，**实施者在编写代码期间不得阅读 Orca 的传输层源码（含 `_local/orca` 副本）**；本节因此只保留可观察行为，评审前版本中的源码文件链接已移除。OPAS 根据本文与 Pairlet 源码独立实现，不移植 Orca 代码、类型组织或加密构造。

## 3. 当前实现的事实与影响

下列链接和符号对应评估基线；实施前需核对后续改动。

| 事实 | 源码依据 | 对方案的影响 |
|---|---|---|
| 直连优先、失败后中继；失败地址冷却 60 秒 | [PocketRepository.kt](../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/PocketRepository.kt)：`launchTransport`、`DIRECT_RETRY_COOLDOWN_MS` | 60 秒只限制下一次连接尝试，不是后台周期探测；健康 relay 不会因此自动回切 |
| 直连握手超时 3 秒，计时位于 `client.webSocket {}` 内 | [DirectE2EConnection.kt](../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/net/DirectE2EConnection.kt)：`connect` | **没有覆盖前面的 DNS／TCP／WebSocket 建连**；不能说不可达地址最多只耽误 3 秒 |
| 仓库层 12 秒 watchdog 等待 `Attached`，到期重启整个连接尝试 | `PocketRepository.startConnectWatchdog` | 黑洞可能反复中止直连而没有正常走到 `DirectUnreachableException` 回退；这是源码可构造的风险，尚未实测发生率 |
| 三种传输的 `inbound`、`control`、`deaf` 直接合流 | `PocketRepository.launchTransport` | 依赖单活动连接；并行后候选与迟到事件不能直接进入这些合流 |
| `send` 按 `directAttemptInFlight`／`directE2E.connected` 选队列 | `PocketRepository.send`、`capturePinOutbound` | 开始一次后台 LAN 探测，就可能把业务消息误送给候选；必须改为显式活动路径 |
| 两种 E2E 传输各有普通消息队列，握手后自动启动 writer | [RelayE2EConnection.kt](../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/net/RelayE2EConnection.kt)、`DirectE2EConnection` | 不能对现有两个 `connect()` 简单 `async`，再取消输的一方；在取消前可能已经发出业务消息 |
| 普通队列跨重连保留；pin 帧绑定连接代次 | [PinOutbound.kt](../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/net/PinOutbound.kt)：`ScopedOutbox` | 切换必须区分未发送、已交给 writer、连接专属帧；不能把两条队列盲目合并 |
| relay 的 `Attached` 只证明 relay 接受设备，之后才开始 E2E 握手 | `RelayE2EConnection.awaitAttached`、`connect` | `Attached` 不能作为竞争获胜条件；握手数学计算完成也不能代替对端密钥确认 |
| LAN 在首个密文成功解密后发出合成 `Attached` | `DirectE2EConnection.awaitKeyConfirmation` | 两条路径的现有“已连接”时点不同，需要统一候选可用判定 |
| 首次接触必须走 relay；受限凭据不能使用 LAN gate | [WsConnection.kt](../../daemon/src/main/kotlin/dev/ccpocket/daemon/server/WsConnection.kt)：`LanE2E`、`gateHandshake` | 新配对、guest、collaborator 不能直接套 owner 的竞速和回切 |
| LAN 在每个 `WsConnection` 内持有独立 `E2ESession`；relay 在 `DeviceSessions` 内按设备维护 active／fallback | `WsConnection.serve`、[DeviceSessions.kt](../../daemon/src/main/kotlin/dev/ccpocket/daemon/relay/DeviceSessions.kt) | **LAN 与 relay 不共用一个加密会话槽位。** 不需要为了双路候选先改加密协议；也不能把 relay 的 overlap fallback 当作跨路径迁移协议 |
| relay 每个 device 新 socket 会替换旧 socket | [RelayServer.kt](../../relay/src/main/kotlin/dev/ccpocket/relay/RelayServer.kt)：`handleDevice` | 同设备两条 relay 数据／控制 socket 仍会互踢；LAN 加 relay 候选不等于允许两个 relay socket |
| LAN sink 按实例区分，relay sink 按 `dev:<deviceId>` 区分；会话支持多个 sink | [OutboundSink.kt](../../daemon/src/main/kotlin/dev/ccpocket/daemon/conversation/OutboundSink.kt)、[SessionRegistry.kt](../../daemon/src/main/kotlin/dev/ccpocket/daemon/session/SessionRegistry.kt) | 并行订阅会产生多路输出；断开 LAN 有 30 秒清理宽限，不能假定取消 socket 就立即移除会话观察者 |
| LAN socket 在认证前已计入连接数；旧 LAN sink 的可达性判断使用全局 LAN 连接数 | `WsConnection.serve`、`SessionRegistry.onLanConnect`／`clientOccupied` | 只读候选也有连接存在性的副作用；探测不能只验证“未 OpenSession”，还要检查对旧会话回收的影响 |
| 直连期间通过独立 `RelayControlDial` 登记推送 | `PocketRepository.submitPush`、[RelayControlDial.kt](../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/net/RelayControlDial.kt) | 该短连接会与新 relay 候选冲突，必须纳入同一个 relay socket 所有权机制 |
| fleet 主／辅仓库有每台电脑一个所有者的约束 | [FleetCoordinator.kt](../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/FleetCoordinator.kt) | 新选择器不能在主辅交换、取消配对时遗留探测器或再建同设备 relay |

部分客户端注释将“一个设备的 relay 会话”简写成“跨两条路径共用一个会话”，与当前 daemon 的具体存储不同。实施时以代码和回归测试为准，必要时一起修正相关注释，不能据此扩大到重写 E2E。

### 3.1 评审核对补充（Fiveo，2026-09-26）

上表各行经源码核对均属实。以下是核对中补充的事实，实施时与上表同等对待；行号对应评审基线。

- **直连预算的平台差异（上表第 2、3 行）**：客户端没有安装 Ktor `HttpTimeout`。Android／桌面使用 CIO 引擎，其 `EndpointConfig` 默认 `connectTimeout = 5000 ms`、`connectAttempts = 1`（按 Ktor 3.5.2 jar 核对），TCP 黑洞会在 12 秒 watchdog 之前以 `DirectUnreachableException` 回退并写入冷却。iOS 使用 Darwin 引擎，建连只受系统默认约束，黑洞地址会先撞 watchdog。watchdog 路径已确认：`connectJob?.cancel()` 让 `DirectE2EConnection.connect` 以 `CancellationException` 退出（DirectE2EConnection.kt:157-166 只把非取消异常转成 `DirectUnreachableException`），`launchTransport` 因而不写 `directCooldownUntil`（PocketRepository.kt:2619-2634、2688-2697），下一次退避重试再次拨直连。这是 iOS 上「存有黑洞直连地址时始终连不上」的源码可构造路径；具体时长与发生率需真机验证。
- **直连地址的默认范围**：daemon `--direct-bind` 默认 `127.0.0.1`，只服务同机 App；只有显式 `0.0.0.0` 才用 `lanIp()` 发布 RFC1918 地址（Main.kt:166-171、242-246），而 `lanIp()` 只取物理网卡的站点本地 IPv4，并把 utun／tailscale 等虚拟网卡排后（Main.kt:54-75）。默认安装下手机保存的直连地址是 127.0.0.1，从手机拨号会被立即拒绝而不是黑洞。本文两个目标场景只覆盖开启 `--direct-bind 0.0.0.0` 的用户和同机桌面 App，且当前不会发布 Tailscale 地址。
- **relay 同键互踢（上表第 12、15 行）**：`RelayControlDial` 用同一个 `DeviceHello(deviceId, credential)` 连同一条 `/v1/device` 路由（RelayControlDial.kt:70-71）；relay 按 deviceId 替换旧 socket 并以 `superseded` 关闭（Broker.kt:68-72；RelayServer.kt:469-473），因此 relay 数据 socket 与推送控制短拨号不能并存。每账户 10 条交互连接的上限在替换之前检查，且计入本设备的旧 socket（RelayServer.kt:455-463）。现有代码里 `directLinkUp()` 含 `directAttemptInFlight`（PocketRepository.kt:682），直连尝试期间的推送登记走短拨号；若随后回退到 relay 而短拨号尚未关闭，两者已可能互踢。这是既有隐患，不是本方案新增。
- **sink 生命周期（上表第 13、14 行）**：relay 的 `dev:<deviceId>` sink 在设备断开时不 detach，跨重连保留（SessionRegistry.kt:868-870）。LAN sink 断开后按（convoId，连接）安排 30 秒宽限，繁忙会话无限续期；到期只移除该 sink，仅当它是最后一个观察者时才关闭会话并停掉 agent（SessionRegistry.kt:1235-1271、1323）。同键重复加入是替换而非叠加（Conversation.kt:296-302、2548），但 `ProjectPinService` 用 `putIfAbsent` 保留首个（ProjectPinService.kt:216）。空闲回收器只在 relay 模式运行，每 20 秒一次、空闲阈值 90 秒（RelayClient.kt:339-345、548）；`clientOccupied` 对非 `dev:` 的 sink 一律用全局 `lanConnected()` 判断（SessionRegistry.kt:846-885），同一计数还会抑制 owner 审批推送（RelayClient.kt:224；PushPolicy.kt:154-162）。常规路径下探测 socket 最多让回收推迟 30 秒宽限；但 rewind 分支会话与交接冷重建迁移的旁观者不进入连接的 `owned` 列表，断开时不会安排清理（RequestRouter.kt:997；SessionRegistry.kt:727-765），这类死 sink 只要有任意 LAN socket 在线就被视为有人观看。
- **LAN 扇出阻塞隐患（daemon，代码推断、未复现）**：LAN 连接的 `outbox` 是 `Channel.BUFFERED`（WsConnection.kt:106），会话扇出按顺序挂起调用每个 sink（Conversation.kt:331）；僵死 socket 让写入最多卡 10 秒（WsConnection.kt:311-316），finally 用的是 `outbox.close()` 而非 `cancel()`（WsConnection.kt:395-396），已挂起的 `send` 可能永久挂住，使回合永不结束、宽限无限续期。阶段 B 会增加 LAN socket 的开关频率，须先证明不会发生或先修正。
- **LAN gate 与首次接触（上表第 10 行）**：gate 依次检查 LanHello、握手帧、devices.json、`firstContactPending`、`restrictedCredential`（WsConnection.kt:175-212）。`firstContactPending` 只在内存（DeviceSessions.kt:97、291），daemon 重启后不再拦截；gate 自身没有密钥确认，知道已配对 deviceId 的冒名 socket 能进入 pump 长期挂着并计入 `lanConnections`（WsConnection.kt:213-237、330-337）。relay 上任何 owner 帧（含 `ClientCaps`、`ListDirectories`）都附带首次接触确认、fallback 提升和四类订阅的幂等注册（DeviceSessions.kt:470-473、500、729-751）；LAN 上这两个请求只读（RequestRouter.kt:530-549）。`OpenSession(resumeId = null)` 每次新建会话（SessionRegistry.kt:539-553）；已知 id 从第二条连接重开是加第二个 sink 而非重启（SessionRegistry.kt:466-483）。
- **第 10 节回归清单的实际位置**：mobile 没有首次配对 PSK 的测试，也没有在仓库层驱动 relay `AuthError`／`PairingInvalid` 的测试；PSK、撤销、LAN gate、宽限与回收的覆盖在 `protocol/.../e2e/E2ESessionTest.kt`、`daemon/.../relay/DeviceSessionsPskDeadlockTest.kt`、`DeviceSessionsRevocationTest.kt`、`server/LanGateMalformedHandshakeTest.kt`、`execution/ExecutionCredentialChainTest.kt`、`session/SessionRegistryReattachModeTest.kt`、`session/SessionRegistryReapTest.kt`。尚无测试驱动 `firstContactPending = true` 时的 LAN 拒绝，也没有两条真实 `/v1/device` socket 同 deviceId 的 relay 测试。

## 4. 推荐结构与不可破坏的约束

### 4.1 在客户端加入连接选择器

把选择、计时、候选取消、路径切换从 `PocketRepository` 抽成可注入时钟／传输工厂的对象。名称由实施者决定；本节描述职责，不要求照搬类图。

- **仓库层**继续管理 UI、配对、会话内容、业务恢复和推送期望状态。
- **选择器**持有一个绑定的连接轮次、活动路径、至多一个另一种路径的候选，以及切换事务。
- **物理连接实例**各自持有 socket、E2E 状态、计数器和生命周期；明确区分候选、活动、已退休。
- **待发消息的唯一所有者**位于逻辑连接层；物理连接只在获得发送权后接收业务帧。可渐进重构现有 `ScopedOutbox`，不得保留两个互不协调的业务积压队列。
- **relay socket 协调器**按 `(relay, accountId, deviceId)` 管理数据连接和推送控制短连接的互斥使用，覆盖主仓库和 fleet 生命周期。

外部协议优先保持不变。候选／活动标记与代次是客户端内部概念，不必增加 wire 字段。

### 4.2 核心约束

1. 一个配对身份同一时刻只有一个业务发送者；候选没有普通 outbox 的消费权。
2. 所有传输事件携带本地绑定身份、连接轮次、物理实例身份。只有当前活动实例可以改变业务／页面状态；候选只能更新选择器自己的探测结果。
3. 输入帧在投递及处理边界检查归属；旧任务的 `finally`、超时、`deaf`、`PeerPresence`、`AuthError` 不得覆盖后继连接。
4. 每个物理连接独立生成临时密钥并维护计数器；不复制 `E2ESession`，不把密文从一条路径搬到另一条。
5. 同一绑定至多一个 LAN 和一个 relay 候选／活动 socket；不增加第二个 relay socket做探测或推送登记。
6. 切换电脑、移除配对、退出连接、fleet 所有权移交时，先失效所有代次和计时器，再取消任务；清理不能依赖旧任务及时返回。
7. 同路径的新旧连接仍遵守现有有界退休和防迟到事件机制。超时退休只表示停止等待，不等于底层 socket 已经关闭，后续还必须有实例隔离和资源清理。

## 5. 阶段 A：直连先行，延迟竞争中继

> **评审修订（2026-09-26）**：本节的并行连接竞争降级为 **A1**，只在 **A0**（有界串行直连预算 + 中止即冷却，见第 12 节阻断项 A-1）上线并有数据证明仍然需要时才实施。A0 不引入双候选、队列所有权和事件归属机制；本节其余约束对 A1 继续有效。**2026-09-26 根源分析后，A1 与第 6 节的阶段 B 一并搁置（第 13 节）；A0 独立跟踪。**

### 5.1 进入条件

仅对已配对的 `OWNER` 启用竞争。没有有效直连地址、地址处于冷却／错误公钥隔离状态时直接 relay。guest、collaborator、legacy `--local` 和 demo 保持各自现有路径。

新配对的首次接触继续串行走 relay，按现有规则每次尝试消费一次 `firstTicket`；收到经过认证的业务响应后才进入普通选择流程。重启后的首次接触恢复沿用现有机制和 daemon 的 `firstContactPending` 最终检查，不从“票据变量为空”推断已完成配对，也不把票据投给两条路径。

### 5.2 时间与选择规则

建议初始参数：直连先行窗口 **1.5 秒**；直连完整预备过程上限 **4 秒**，覆盖拨号、WebSocket、E2E 和只读验证；两项均可注入并由跨平台实测调整。现有 relay 握手上限可先保留，但需增加覆盖建连前段的完整候选预算。

1. 开始 LAN 候选；立即失败则立即尝试 relay，不浪费先行窗口。
2. 先行窗口内 LAN 完成可用验证，提升 LAN，取消 relay 定时器。
3. 窗口结束且 LAN 尚未可用，在取得唯一 relay socket 使用权后开始 relay 候选；LAN 仍可继续至自身期限。
4. 第一个**通过可用验证**的候选获得活动权，另外一个立即失效并关闭。两个结果同时到达时，串行仲裁；同一仲裁批次可优先 LAN，胜者提交后不反悔。
5. 一条候选失败不会重启另一条。只有所有可用候选均失败，才进入现有逻辑连接退避；以一次逻辑尝试计数，不能两条各自触发仓库重连。

1.5 秒是中继启动预算，不是保证 1.5 秒内可用。若已有推送控制短连接正在退休，应先有界取消并等待释放；记录延迟原因，不为满足时限制造第二个 relay socket。

现有 12 秒仓库 watchdog、2 秒首屏宽限、6 秒列表等待都要调整到新状态含义：候选事件不得启动全局列表等待，某条候选期限到期不得误杀健康的另一条。UI 超时提示和重试判决继续分离。

### 5.3 候选可用的最低证据

可用需依次满足：socket 建立、身份绑定的 E2E 通过、至少一次发往当前候选的只读请求得到可解密且可解析的对应响应。

首版对 owner 使用既有 `ClientCaps` 与 `ListDirectories`／`Directories`，避免增加探测 wire 消息。候选私有 reader 先识别 `DaemonInfo`，声明兼容的能力，再验证目录响应；不要将 relay `Attached` 或单独收到握手公钥判为可用。

目录请求包含 daemon 的实际处理成本，不能把整个响应耗时当成纯网络 RTT。验证需包含大项目列表、磁盘繁忙场景；如该探针造成误判，先调整预算／验证请求，再决定是否有必要另提轻量探测协议。

候选允许的输出仅限认证、必要能力声明、只读验证和 socket 心跳。不得发 `OpenSession`、`SendPrompt`、审批决定、上传分片、pin 同步或其他修改请求。`OpenSession` 可能附加／创建执行会话，不是健康探针。

候选获得的 `DaemonInfo` 与目录快照只暂存在候选内；提升后按受控顺序交给仓库。自动广播的 review／handoff 等事件可能在探测期间到达，应隔离并在提升后重新拉取权威状态，不能无界缓存或提前影响界面。

首次配对路径尤其注意：daemon 可能需要收到设备首个加密帧才完成 PSK 确认，因此不能改成“永远等服务器先给可解密响应，再允许发送任何东西”。保留现有首次配对交互，普通候选验证不要破坏该顺序。

## 6. 阶段 B：前台探测与稳定回切

仅在阶段 A、发送权隔离和切换恢复测试通过后启用。

### 6.1 探测策略

- 活动路径为 relay、App 在前台、绑定为 owner、有允许使用的 directUrl 时才探测；活动路径为 LAN 时不常驻 relay 备用连接。
- 建议前台默认 **30 秒**一次，每台电脑加入约 ±20% 抖动；首次恢复前台可安排一次探测，但遵守失败冷却。失败冷却从现有 **60 秒**开始，连续失败递增到 **5 分钟**。
- 多电脑共用进程级限额：最多一个后台直连探测同时进行，优先当前电脑，其他按公平顺序排队；正常连接恢复优先于优化探测。探测不刷新 Agent 活跃时间，也不人为延长会话寿命。
- 候选复用阶段 A 的身份和只读验证。在同一候选上跨至少 **5 秒**得到两次有效只读响应后才认为稳定；每次只留一个待匹配探测请求。候选最多保留 **10 秒**，不能等待安全切换条件无限占用 socket。
- 前台优化切换成功后，建议 **60 秒**内不再发起优化切换；真正断线恢复不受该限制。后台立即取消优化候选和计时器；恢复时合并触发，不累积补跑。
- 新 `DaemonInfo` 的地址、绑定或网络环境发生变化时，旧候选结果不能继续使用；公钥确认失败保持既有错误地址隔离，禁止降级明文或重新信任陌生公钥。

定时器使用单调时间。墙上时钟只用于记录，不用于稳定窗口或退避间隔。

### 6.2 主动回切的安全时机

优化回切不应打断用户操作。首版必须有统一的“可主动切换”判定，覆盖普通请求、分屏和后台业务任务，不能只检查聊天是否正在输出。

遇到以下任一情况，保持健康 relay，关闭或到期释放候选，下轮再试：

- 有已写出但结果未明确的修改操作、审批决定、文件上传，或正在建立／切换会话。
- 新会话还没有可恢复的 `sessionId`；正在观察外部会话且尚无经过验证的迁移流程。
- 有不能取消后重建的连接相关请求，或恢复／切换事务已在进行。

持续的 Agent 文本输出本身不必禁止回切，但必须完成会话重挂和事件序列回补测试；若首轮实现无法证明此点，先限制在会话空闲时回切，并在交付说明中明确该范围，不能宣称流式输出无缝迁移。

新操作与切换判定必须由同一个串行控制点仲裁。不能先检查“空闲”，在用户已经发送消息后仍依据旧结果切换。

### 6.3 切换事务

1. 保持原活动链路服务，候选只做认证和只读验证。所有证明均绑定当前电脑、地址版本和物理实例。
2. 到达安全窗口后暂停普通出队，新用户操作暂存在唯一逻辑队列。等待旧 writer 结束已领取的发送；不能用一次 `drainPending()` 当作完成交接。
3. 旧 writer 无法有界停止且旧链路仍健康时，放弃优化切换；旧链路实际故障则走故障恢复，不能把未知发送结果重放给候选。
4. 以一次串行提交转移活动权、事件接收权和连接代次，失效旧 pin fence。旧连接从此只可进入关闭流程，不可再改变业务状态。
5. 在新活动路径先恢复能力和页面／会话订阅：目录、待审批、review／handoff、managed sessions、pins、主聊天及所有分屏。复用并拆分 `restoreAfterReconnect`，确保每个恢复目标一次；不得直接调用会清页面和分屏的用户级 `disconnect()`。
6. 仅在相应会话重挂及目标身份确认后释放依赖它的业务消息。保留 `promptId`，核对 `convoId` 是否变化；不能把老 `convoId` 的消息机械投到重启后的新会话。
7. 关闭旧连接并检查任务和订阅最终回收。新路径提交后失败，按新的逻辑恢复轮次处理；不重新启用已退休 reader／writer／计数器。

无缝只描述用户操作和会话连续性，不承诺零毫秒传输间隙。提交后恢复超时按真实状态提示。旧 relay／LAN 会话观察者的清理与新订阅的建立顺序，必须通过第 10 节的集成测试验证。

## 7. 消息、推送与生命周期的专项要求

### 7.1 发送语义

| 消息状态／类型 | 切换处理 |
|---|---|
| 仍在逻辑队列、从未交给物理 writer | 同一绑定内保序转交给唯一新活动 writer；切换电脑时绝不搬到新电脑 |
| 已交给 writer、发送结果未知 | 不能当作“未发送”自动跨路径重放；由该业务已有的回执／恢复机制处理 |
| 带 `promptId` 的提示词 | 沿用现有 prompt ledger 与确认机制；保留原 ID，不假定所有命令都有同等幂等能力 |
| 目录等可重建查询 | 按现有规则合并或重新发起；旧请求的 waiter 必须完成、取消或明确转入重试，不能悬挂 |
| `OpenSession` | 只对已知会话做一次受控恢复；禁止两个候选同时打开，尤其禁止两个 `resumeId=null` 请求竞争 |
| `SyncProjectPins` | 不跨物理连接搬运；退休旧 fence，新连接重新建立订阅，由持久 pin 状态重建待同步操作 |
| 上传、审批及其他有副作用的操作 | 健康链路上的主动回切等待结果；故障恢复遵循各自已有契约，不新增通用自动重放 |

当前 `ScopedOutbox.runWriter` 没有通用的端到端发送回执。此次改造不能宣称对所有操作提供 exactly-once；需要证明的是不会因竞速新增双写、错机器发送或无声丢弃未发送消息。队列容量、背压和取消也需覆盖并发入队，不能在迁移时依赖 `trySend` 失败后只记日志。

### 7.2 推送控制连接

`RelayControlDial` 和 relay 主连接必须取得同一绑定的独占使用权：

- relay 候选或活动 socket 已存在时，`RegisterPush` 复用它的控制平面；不必等 daemon 在线或 E2E 可用。
- 当前只有 LAN 时，可保留现有控制短连接；开始 relay 候选前，将待登记意图交还 `PushRegistrar`，取消／退休短连接，再取得使用权。
- 回执按请求、绑定和实际控制连接分发，不能被候选业务事件过滤器一起丢掉。已经写出的请求取消后仍是结果未知，不伪造已确认。
- 候选输掉后，不为等推送回执无限保留它；保持登记期望，由 registrar 按现有重试规则收敛。关闭通知的清除意图同样适用。

不要再让 `directAttemptInFlight` 决定推送路由；候选 LAN 正在探测时，活动 relay 依然是合法的控制路径。

### 7.3 会话观察者与多电脑

LAN 与 relay 的 sink 身份不同，现有 `CloseSession` 也不等于无副作用的“关闭旧传输订阅”。不能为了清理旧路径广播关闭会话或杀 Agent。

首版优先验证客户端改变是否足够：候选不 `OpenSession`，提升后一次重挂，旧路径退出后按既有规则清理。测试必须覆盖 LAN 30 秒宽限、繁忙会话宽限续期、relay keyed sink 保留、老版本 daemon 以及 zombie sink 对输出的影响。如果证明旧观察者残留会阻塞或重复分发，先单列 daemon 清理修正与兼容评审，再开启阶段 B；不能把“30 秒后大概会好”当作验收。

另有已确认的耦合：候选 socket 会增加 daemon 全局 `lanConnections`，可能使仍挂有旧 LAN sink 的会话暂时被视为有人观看。需要用周期探测与 idle reaper 交错测试证明不会持续保活无人使用的会话；若不成立，应修正 daemon 对实际订阅连接的占用判断，或在旧 daemon 上禁用阶段 B。不能只把探测频率调低就视为问题已解决。

fleet 热提升／冷切换时移交的是整个逻辑连接所有权，连同探测、控制连接租约和 pin lease；不得只改 `paired`。取消配对先撤销所有任务的本地权利，后续到达的候选结果不得恢复该绑定。

## 8. 安全、兼容与范围控制

- 继续使用当前密钥绑定、首次配对 PSK、撤销和受限凭据门禁。一个可连接的地址不是身份认证；错误公钥不能靠回退明文解决。
- LAN 的明文 `LanHello` 会把高熵设备标识发送给缓存地址的当前持有者。周期探测增加次数，因此需要限频；只连接已配对 daemon 发布并经现有校验接受的地址，不扫描网段、不猜测公网端口。跨网络复用私网地址的残余可关联性应保留在评审结论中；若需要网络范围绑定，另列明确的前置任务与跨平台验证。
- 候选成功之前不持久化它发布的新地址／能力，不把失败候选的 `AuthError` 直接映射成全局 `PairingInvalid`。有效撤销／凭据失效证据按其来源单独处理，不能借“候选隔离”绕过撤销。
- 首选不修改 protocol／relay／daemon。新 App 配旧 daemon／relay，旧 App 配新 daemon 的互通必须验证；不增加新 opcode、消息类型或强制字段来省略客户端重构。
- 如果实施确需 wire 变更，先补可选能力协商、旧端降级及独立兼容评审；如果改密钥、计数器、握手或认证代码，按仓库规则增加专项安全评审。
- 冷连接竞争与自动回切使用两个独立内部开关，便于分别回退到串行策略；不新增面向用户的网络设置页。关闭开关后也必须正确释放候选并保存未发业务队列。

## 9. OPAS 的实施顺序与交付物

评审结论落实后，在基线有变化时先复核第 3 节，不把本文当作当前源码永远不变的描述。

| 步骤 | 主要工作 | 完成条件 |
|---|---|---|
| 0. 复核和建模 | 明确事件来源、普通消息队列所有权、在途操作登记、relay 控制连接租约；为现有行为补必要回归夹具 | Fiveo 的阻断项均关闭；能用可控时钟和假传输重现黑洞与迟到事件 |
| 1. 物理／逻辑连接分离 | 连接实例化、候选模式、显式提升与退休、统一活动引用；整合 `send`、pins、push | 单路径行为不变，现有重连、推送、fleet、pin 测试通过 |
| 2. 连接竞争 | 完整候选期限、延迟启动 relay、单次仲裁、失败汇总和原退避衔接 | 阶段 A 验收通过，可单独交付 |
| 3. 迁移与回切 | 在途操作门、受控订阅恢复、旧 sink 清理验证、前台探测与防抖 | 阶段 B 验收通过，再启用自动回切 |
| 4. 跨平台验证 | iOS／Android／desktop 的拨号取消、前后台、代理和多电脑实测 | 实际平台结果、耗时分布、未覆盖项随实施报告交付 |

预计涉及客户端 `net/`、`PocketRepository`、`FleetCoordinator`、`ProjectPinLink`、推送控制适配和相关测试。daemon 初始只增加集成验证；只有明确测试证据要求时才修正服务端。避免把整个八千行仓库类或全套连接协议顺手重写。

每阶段交付代码、必要测试、诊断字段说明、验证结果、开关／回退方式和文档状态更新。评审和实施不自动包含安装用户设备、正式发版或生产 relay 部署。若后续确实修改并同步 daemon，遵守根 [AGENTS.md](../../AGENTS.md) 的单实例更新脚本、会话确认门及 daemon 后代会话的 detached 更新规则。

## 10. 验收矩阵

以下均为待实施的验收要求，本次文档评估没有执行这些测试。

| 场景 | 必须观察到的结果 |
|---|---|
| LAN 快速可用 | 不发起 relay 数据候选；允许独立且受协调的必要推送登记 |
| LAN TCP／WebSocket 黑洞，relay 正常 | 在先行窗口及调度容差内开始 relay；不等旧 12 秒 watchdog；只发一轮业务请求 |
| LAN 立即拒绝 | 立即开始 relay；正常失败冷却生效 |
| 两路几乎同时通过／输家迟到 | 只有一个胜者；输家的帧、`finally`、错误和心跳不改变活动状态，不消费业务队列 |
| relay 已 Attached，但 daemon 离线或 E2E 无响应 | relay 不被误判为业务可用；若 LAN 可用仍能成功 |
| 首次配对／首个回执丢失／重启恢复 | 票据不双重消费、不绕过首次接触；保留现有 PSK 恢复行为 |
| 错误公钥／受限凭据／撤销 | 不降级认证、不走受限 LAN、不因健康探测恢复已撤销权限 |
| relay 健康时 LAN 探测失败 | 业务不重连、不出现探测造成的离线横幅，不改变退避或活跃会话 |
| Wi-Fi 与蜂窝来回变化 | 达到稳定条件才回切；短期抖动不来回迁移；当前链路真故障时立即走恢复 |
| 正在发送提示词／审批／上传／创建会话 | 主动回切延后；故障恢复不双写、不复制未知结果的修改，不错误重放到新 convo |
| 主聊天、多个分屏、历史回放、观察模式 | 恢复一次、历史不重叠追加、`eventSeq` 不倒退、不新开或误关 Agent；不支持的主动迁移按约定延后 |
| pin 同步与 managed sessions 推送 | 旧连接帧被围栏拒绝；新订阅恢复，pin 持久待办不丢且不跨电脑 |
| 推送注册与 relay 候选同时发生 | 同绑定仅一个 relay socket 使用者；不出现自发 supersede 循环；回执与清除意图收敛 |
| 多电脑与 fleet 主辅切换／移除绑定 | 没有两套选择器争同一绑定；探测限额与公平性成立；旧结果不复活解绑电脑 |
| 后台、恢复前台、取消、长时间关闭未返回 | 优化探测停止；恢复合并触发；旧线程／协程事件不污染后继；资源数量最终有界 |
| LAN 断开 30 秒及繁忙续期后 | 新连接上的同一会话不被旧 cleanup 关闭；旧 sink 最终回收，不阻塞扇出 |
| 周期候选与 idle reaper 重叠、daemon 有遗留 LAN sink | 未订阅会话的候选不导致无人使用的会话持续保活；如需新 daemon，旧端禁用阶段 B 的条件可验证 |
| old daemon／old relay、legacy／demo、关闭开关 | 各自现有功能可用，未获得新能力也不会收到不能理解的 wire |
| 队列满、并发入队、writer 已领取但未完成、切换中失败 | 背压可控，未发送消息不静默丢失，已提交状态不盲目回滚 |

验证分层：

1. 可控时钟单元测试覆盖仲裁、稳定窗口、代次、消息所有权、控制租约和取消。
2. 使用临时端口、临时密钥、假 router／隔离 daemon 组件的真实 WebSocket 集成测试；不能只用假 transport 宣称 Ktor socket 取消与 E2E 都正确。
3. 回归至少覆盖 `ReconnectStormTest`、`ScopedOutboxTest`、`ProjectPinLinkRaceTest`、`DeviceSessionsOverlapTest`、首次 PSK／撤销／LAN gate、fleet、push 与 session close 相关测试。必要的新场景补集成用例，不只是改常量期望值。
4. 实施迭代运行针对性测试；单阶段收尾 `bash scripts/check-all.sh --affected`，集成合并前 `bash scripts/check-all.sh`。客户端编译使用 `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :mobile:composeApp:compileKotlinDesktop`；iOS／Android 的平台编译与故障场景另行记录，Desktop 测试不能替代它们。

隔离测试不得启动抢占当前账号和 8799 的第二个真实 daemon，不使用 `:daemon:run`、临时 `nohup` 或生产 relay 故障注入。

## 11. 诊断、衡量与回退

沿用现有 Diagnostics／Telemetry 的枚举与脱敏规范。需要能区分：逻辑连接总耗时、候选拨号／认证／验证耗时、获胜路径、回退原因、探测次数与失败原因、主动切换成功／延后／失败、旧任务被忽略、relay 控制租约等待，以及消息发送结果未知。

只记录固定类别、数量和耗时；不上传 directUrl、IP、SSID、公钥、deviceId、ticket、凭据、请求正文或原始错误文本。新增枚举按观测模块现有流程登记，不能另开自由字符串上报路径。

上线前后比较：首个业务可用响应的 p50／p95、黑洞场景中继启动延迟、每逻辑连接重试次数、活动路径切换次数、失败探测数量、relay 转发字节。电量与网络开销需要设备实测；本次未证明 30 秒探测或 1.5 秒窗口是最优值。

出现双写、错机器路由、推送互踢、未发送消息丢失、会话误关或频繁路径抖动时，停止启用对应阶段。阶段 B 可独立关闭，保留阶段 A；阶段 A 也可退回串行选择，不能要求生产服务同步回滚才能恢复客户端可用性。

## 12. Fiveo 评审清单与结论记录

请明确给出“通过／有条件通过／退回”，区分实施前阻断项与可后续优化项。尤其需要审查：

1. 是否同意客户端优先、无需新增 wire 的两阶段路线；完整连接预算与候选验证是否覆盖黑洞和未确认密钥。
2. 唯一业务队列、在途操作登记、单次活动权提交是否能在 Kotlin 多平台协程下实现；有哪些业务请求不能自动迁移。
3. relay 数据／控制 socket 租约是否覆盖推送、fleet 和解绑；取消中的控制请求如何归还 registrar。
4. LAN／relay 独立 crypto 与不同 sink 身份的分析是否正确；旧订阅清理是否需要 daemon 前置修正。
5. 首次配对、旧端、guest／collaborator、pin 围栏、分屏与观察模式是否有遗漏。
6. 是否接受首版的保守回切窗口与地址可关联性残余；哪些条件应成为阶段 B 的阻断项。

| 项目 | 内容 |
|---|---|
| 评审者／日期／评审基线 | Fiveo（Claude Fable 5.1）／2026-09-26／`05e2194fc0d5d4a4f12ffd6c774daac450064bbe`，工作区仅文档改动。方式：源码阅读、Ktor 3.5.2 引擎默认值核对、本地 Orca 副本 `da6d483a` 行为核对。未做设备实测、网络故障注入或生产数据分析。 |
| 结论 | **有条件通过。** 同意客户端优先、不改 wire、两阶段的总路线；第 3、4、7、8 节的事实与约束经核对成立（补充见 3.1）。不同意按第 5 节原样先做并行竞争：阶段 A 拆为 A0（有界串行，独立交付）与 A1（并行竞争，按数据决定），候选／所有权重构归入阶段 B。理由见 12.1。**同日稍后被第 13 节的根源分析覆盖：方案整体搁置，仅 A0 保留。** |
| 实施前阻断项及关闭依据 | A-1 至 A-4 为 A0／A1 前置，B-1 至 B-3 为阶段 B 前置，明细见 12.2。A-2 已由本次评审关闭。 |
| 接受的范围限制与后续项 | 见 12.3。 |
| OPAS 实施使用的最终方案版本 | 无。第 13 节根源分析后本文不再进入 OPAS 实施；A0 作为独立缺陷修复另行跟踪，评审记录保留供参考。 |

### 12.1 结论理由

1. 黑洞问题的根源是直连预算在 iOS 上无界，且 watchdog 中止直连不写冷却（3.1 第一条）。A0 只需把一次直连尝试的完整预算在三个平台统一有界，并让预算到期与 watchdog 中止都写入冷却，即可修复；这不需要双候选。
2. 并行竞争相对有界串行只在「LAN 慢但可用」（超过先行窗口、未超预算）时多赢一次；黑洞场景下两者的中继启动时点只差预算与先行窗口之差。为此引入双候选、唯一业务队列、事件归属和切换事务，收益不成比例。
3. 直连地址默认只发布 127.0.0.1（3.1 第二条），目标场景只覆盖开启 `--direct-bind 0.0.0.0` 的用户与同机桌面 App。重构规模应先用现有 `Connected` 事件的 `Transport` 维度（`direct-e2e`／`relay` 占比）与 A0 的 fallback 诊断证明必要。
4. 阶段 B 是真正需要候选与所有权机制的部分，其价值同样限于上述用户群；daemon 侧已确认的旧 sink 清理缺口与扇出阻塞隐患（3.1）必须先关闭。

### 12.2 阻断项明细

- **A-1 A0 定义与验收。** 直连一次尝试的完整预算（拨号、WebSocket、握手、密钥确认）在三个平台统一有界，建议 2.5–3 秒、可注入；预算到期或 watchdog 中止都写入 `directCooldownUntil` 并计入 fallback 诊断；候选事件不得启动全局列表等待。关闭依据：可控时钟单测覆盖「直连被取消或超时 → 下一次尝试走 relay」；iOS 真机在蜂窝网络下保存 RFC1918 地址时，中继启动不晚于预算加调度容差；Android／桌面行为不变。
- **A-2 原创实现约定。** 实施者不得阅读 Orca 传输层源码；第 2 节已改为只保留可观察行为。已由本次评审关闭；实施报告仍须声明未读取。
- **A-3 relay socket 租约。** 第 7.2 节为强制项：任何让 relay 数据 socket 与 `RelayControlDial` 同时存在的路径都算缺陷，并处理「连接上限检查先于替换」的边界。关闭依据：relay 集成测试用两条真实 `/v1/device` socket 同 deviceId 走 `handleDevice`；客户端测试覆盖「直连尝试中登记推送 → 直连失败 → relay 启动」不互踢。
- **A-4 候选事件隔离。** 候选的 `Attached` 不得触发 `ensurePushLink`、`startListWait` 或 `connGen` 递增；候选的 `AuthError` 不得置 `pairingInvalid`；候选的 `DaemonInfo` 不得写 directUrl、hostName 或能力集（现有处理见 PocketRepository.kt `handleControl` 与 `DaemonInfo` 分支）。关闭依据：新增仓库层测试；当前没有在仓库层驱动 relay `AuthError` 的测试。
- **B-1 daemon 前置（阶段 B 开始前）。** (i) 3.1 所述 LAN outbox `close()` 与扇出挂起隐患，先以集成测试证明不发生，或修正为等价于 `cancel()` 的语义；(ii) rewind 分支与交接迁移旁观者的死 LAN sink 补清理，或把 `clientOccupied` 改为按实际挂载的 sink 判断。关闭依据：周期候选与 idle reaper 交错测试通过；做不到则阶段 B 只在新 daemon 版本启用，且版本门槛可验证。
- **B-2 阶段 B 首版范围。** 只在空闲时回切：主聊天与所有分屏无流式输出，无在途 prompt、审批、上传、`OpenSession`，无 pin 在途。流式中迁移另立评审，不得宣称无缝迁移流式输出。
- **B-3 开关。** A1 与阶段 B 各一个内部开关；关闭后回到 A0 行为并保存未发业务队列。

### 12.3 接受的范围限制与后续项

- 不改 protocol／relay。`Messages.kt` 受 `packaging/brand-compatibility.json` 的冻结哈希门禁（`scripts/check-brand-compatibility.py`）保护；若确需改 `ClientCaps` 或 `DaemonInfo`，须刷新哈希并做 wire 兼容评审。
- 当前不会发布 Tailscale 地址，本次不补自动发现。
- 接受 `LanHello` 明文 deviceId 的可关联残余。后续项：探测触发优先改用平台网络变化事件；蜂窝网络下可直接跳过 RFC1918 地址。
- `firstContactPending` 只在内存，daemon 重启后 LAN gate 不再拦首次接触；因 lanUrl 只能从握手后的 `DaemonInfo` 学到，记录为已知限制。
- relay 上 `ClientCaps`／`ListDirectories` 的登记副作用与今日冷连接相同，接受；relay 上 `ListDirectories` 在唯一读循环内同步执行（DeviceSessions.kt:791），探测请求不得高频。
- 诊断：`TelEvent`／`TelKey` 已有 `AnalyticsCatalogAlignmentTest` 对齐；新增 `ErrorPath` 须同步 iOS `PocketDiagnostics.swift` 的硬编码列表，否则会被误报为 EP-30。
- 初始参数参考 Orca（先行 2.5 秒、探测 15 秒、30 秒内 3 次成功且距上次迁移至少 60 秒、失败冷却 60 秒）；本文取值同量级，可作初始值，最终以实测为准。

本文件至今只交付评估、提案、评审结论与根源分析（第 13 节），不代表已经实现或验证上述行为。方案已搁置：A0 之外的任何阶段不得开始；若日后重启，须先复核第 3 节基线并重新关闭对应阻断项。

## 13. 根源分析：P2P 是否适合本项目，以及本方案的最终定位

补充日期：2026-09-26。触发：第 12 节评审完成后，用户要求从根源上分析「用 P2P 的方式」是否适合本项目。本节只做分析与定位，依据为源码阅读、仓库文档与 GitHub issue 检索，不代表已实现或验证任何行为。

### 13.1 结论

P2P 不适合作为 Pairlet 的核心或默认数据面。根源在于本项目对服务器的依赖本质上不是转发，而是身份、汇合、在线状态与推送；P2P 只能替代转发这一层，而转发不是当前的痛点。合理的形态是把现有直连扩展成「用户自带可达地址」，以及把 relay 部署到离用户更近的位置。

据此，本文的三段式方案（A0 → 阶段 B → A1）整体搁置：A1 与阶段 B 的收益不成比例（12.1），目标用户又限于开启 `--direct-bind 0.0.0.0` 的用户（3.1）；只有 A0 是独立成立的缺陷修复，另行跟踪。

### 13.2 根源

1. **传输需求的本质。** 手机与 daemon 之间是一条长连接，双向、以小帧为主、偶发大帧（历史回放上限与 relay `MAX_FRAME` 一致，为 4 MB）。流量低，交互式但不是实时媒体。手机进入后台即挂起 socket（`PocketRepository.onAppBackground`／`onAppForeground` 的注释），任何数据面都要在每次回前台时重建。这些特征对中继友好，对打洞不友好。
2. **服务器职责去不掉。** relay 承担配对汇合与一次性票据、设备凭据与撤销广播（`DeviceRevoked`）、在线状态（`PeerPresence`）、推送 token 存储与定向唤醒（`RegisterPush`、`MAX_TARGETED_PUSH_PER_HOUR`）、账户内设备池与限流（`MAX_LIVE_DEVICES` = 10、`MAX_LIVE_HEADLESS` = 5）、协作者与 headless 凭据。没有一项能由 P2P 承担，离线手机只能由服务器唤醒。P2P 不是 relay 的替代，而是在 relay 之外再加一套数据面。
3. **安全模型是产品承诺。** [SECURITY.md](../SECURITY.md) 与 README 把 daemon 描述为只出站、永不监听公网；直连监听默认只绑回环（Main.kt:166-171）。公网打洞要求 daemon 接受互联网入站流量，预认证阶段的包处理成为攻击面；3.1 已确认 LAN gate 存在「知道已配对 deviceId 即可挂住 socket 并计入连接数」的弱点，公网化之前必须先加固。
4. **NAT 穿透的现实。** 手机蜂窝网普遍是 CGNAT 与对称 NAT，中国大陆移动网络尤其如此（本项目在中国区 App Store 上架，见 [PAIRLET-ROLLOUT.md](../PAIRLET-ROLLOUT.md)）；企业网络常屏蔽 UDP；本机 TUN／fake-IP 代理会干扰（根 AGENTS.md 记录过 cask 版 daemon 因此连不上 relay）。打洞成功率不确定，必须保留 TURN 级回退，而 TURN 就是再造一个 relay。最终形态是「两套数据面加选路器」，正是第 12 节认定收益不成比例的候选与所有权重构，再叠加 UDP 与 NAT 的状态机。
5. **平台栈成本。** 客户端是 Kotlin Multiplatform 加 Ktor（CIO／Darwin 引擎），没有跨平台的 WebRTC 或 QUIC 打洞实现；iOS 与 Android 需各接原生库，桌面端与 JVM daemon 需 JNI 或 sidecar。daemon 分发是纯 JVM 的 installDist、jpackage、cask 与 scoop，原生依赖会波及三个平台的打包与 Windows 服务。
6. **收益侧缺少证据。** GitHub issue 检索（heypandax/pairlet，2026-09-26）：`tailscale`、`p2p`、`打洞` 各 0 条；`延迟` 9 条均与 relay 无关；涉及 relay 的 issue 全是可靠性问题（如 #107、#298、#340），没有延迟、带宽或费用反馈。遥测只有 `TelKey.Transport`（relay／direct）维度，没有往返时延。relay 单点在香港、Cloudflare 前置，对远离香港的用户是延迟来源，但解决延迟最短的路径是多区域或自托管 relay（relay 已支持自托管），不是 P2P。

### 13.3 形态评估

| 形态 | 能解决什么 | 代价 | 判断 |
|---|---|---|---|
| WebRTC 数据通道（ICE／STUN／TURN） | 公网直连，部分场景省转发 | 三平台原生栈、daemon 原生依赖、仍需 TURN；iOS 后台不可用 | 不适合 |
| QUIC 打洞库（iroh、libp2p 等） | 同上，自带中继回退 | Rust FFI 接三端与 JVM daemon，协议与安全审计 | 现阶段不适合，远期观察 |
| 用户自带 overlay（Tailscale、ZeroTier、WireGuard） | 跨网直连，NAT 由成熟产品处理，daemon 仍不监听公网 | 代码量很小，用户需自装 | 适合，作为直连的扩展 |
| 多区域／边缘 relay、自托管 | 延迟根源 | 运维 | 适合，前提是延迟被证实 |
| 现有 LAN 直连 | 同网快路径 | 先修 A0 | 适合 |

### 13.4 最终定位与后续项

- **不立项 P2P 数据面。** 第 1 节「不新增公网打洞、WebRTC、STUN/TURN、内置 Tailscale」由范围声明升级为有依据的决定。
- **本文三段式方案搁置。** 阶段 B 与 A1 不进入实施；第 12 节的阻断项 A-3、A-4、B-1 至 B-3 随之挂起，仅在重启本方案时恢复。
- **A0 独立跟踪。** 直连一次尝试的完整预算在三个平台统一有界，预算到期或 watchdog 中止都写入冷却（3.1 第一条、12.2 A-1）。它是缺陷修复，不依赖本文其余部分；建议录入 issue 后按普通缺陷流程处理。
- **先补测量。** 利用现有 `Connected` 事件的 transport 占比，再增加枚举级的 relay 往返时延分桶（按第 11 节的脱敏规范登记枚举）。没有数据不谈延迟优化。
- **直连扩展到用户自带地址。** `lanIp()` 目前只取物理网卡的 RFC1918 地址并把 utun／tailscale 网卡排后（Main.kt:54-75），可改为允许发布 tailnet 地址或由用户在 App 内手填 directUrl，仍走现有 Noise KK 认证与错误公钥隔离。前置：A0 完成、LAN gate 加固（3.1 第六条）。这一步覆盖「不在同一网络也想直连」的用户，成本远低于阶段 B。
- **重新评估 P2P 的触发条件。** 三者同时成立再回头看：relay 转发量或延迟成为用户可感知的主因；Kotlin Multiplatform 生态出现 JVM 可用的成熟打洞库；daemon 只出站的承诺经安全评审可以放宽。

