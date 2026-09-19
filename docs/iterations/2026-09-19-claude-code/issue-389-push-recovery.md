# #389：通知链路诊断、注册确认与存量用户自动恢复

来源：[Issue #389](https://github.com/heypandax/pairlet/issues/389)。批次 2；建议 P1。状态：实施方案，未执行修复或现场验收。

## 目标与来源边界

已允许通知、已配对的用户，在联网条件恢复后能自动完成推送登记，不需要重装、重新配对或发一条消息触发修复。任务完成通知要分层证明：触发正确、relay 有登记、APNs 接受、设备实际展示。

Issue 报告 Apple 双端 2.1.0、Claude Code 无通知，附诊断 Markdown。本轮没有读取该附件中的原始诊断内容；接手时先从 Issue 附件读取，提取脱敏事实，不能假装已有现场根因。

本方案吸收同日已有 [推送恢复设计草稿](../../design/PUSH-REGISTRATION-RECOVERY.md) 的契约、重试和迁移要求，必要实施内容完整列在本文件中。草稿当时为未跟踪文件，尚待用户审核；本次整理不将其改标为已批准或已实施。用户交给 Claude Code 并明确开始修复后，以本批范围执行；若草稿后续变化，先比对契约差异。

草稿记录了另一轮只读调查：候选设备登记字段为空、出现无 token 日志；候选身份没有通过用户 account 确证。这只能作为注册链路的线索，不能直接认定是 #389 的根因。

## 已确认的源码缺口

| 环节 | 当前实现／缺口 | 修复目标 |
|---|---|---|
| 系统注册 | `PushTokens.ensureStarted()` 的进程内一次性门控 | 失败、回前台、网络恢复后可有限重试 |
| iOS 生命周期 | 需核对 SwiftUI Scene 与 delegate 恢复入口 | 由实际有效的前台事件驱动恢复，不重复监听 |
| 客户端去重 | `pushRegistered` 在登记完成前设置 | 区分排队、发送、服务端确认 |
| 普通 relay 上报 | `controlOutbox` 写入成功不能证明落库 | 根据真实发送、ACK、超时协调恢复 |
| relay 保存 | `runCatching { setPushToken(...) }` 无明确回执 | 事务成功后返回可关联结果 |
| 多电脑 | 需核对主／辅助连接及每个配对注册 | 每个允许通知的配对均有独立恢复状态 |

工作区已有网络诊断和 Android 推送改动，均不能当作上述完整恢复方案已完成。

## 先确认故障位于哪一层

1. 读取 Issue 附件，确认 App build、daemon、系统版本、用户通知权限、App 开关、前后台与当前查看会话。#382 的 30 秒合并／正在查看会话抑制等既有策略应纳入判断。
2. 以脱敏事件关联任务终态、daemon 推送请求、relay 策略、登记存在性、APNs 结果、设备表现；“没有展示”不能直接等价为 APNs 拒绝。
3. 若已持有有效登记却从未触发通知，沿 `PushPolicy`／coalescer／任务终态继续诊断，不强行归因于注册。若 APNs 接受但设备不展示，单独核对系统设置、通道与展示条件。
4. 对注册缺口采用下面的完整恢复设计；若还有独立触发／投递问题，记录为本单内的另一故障分支，补对应证据后修复。需要扩大到投递队列架构时先说明新范围，不静默扩张。

## 实施顺序与文件入口

| 顺序 | 模块 | 入口 |
|---|---|---|
| 1 | wire 契约与 relay 保存 | [Messages.kt](../../../protocol/src/commonMain/kotlin/dev/ccpocket/protocol/Messages.kt)、[RelayServer.kt](../../../relay/src/main/kotlin/dev/ccpocket/relay/RelayServer.kt)、[SqliteRelayStore.kt](../../../relay/src/main/kotlin/dev/ccpocket/relay/store/SqliteRelayStore.kt) |
| 2 | 状态机、持久化、传输 | [PushTokens.kt](../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/push/PushTokens.kt)、[PocketRepository.kt](../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/PocketRepository.kt)、[FleetCoordinator.kt](../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/FleetCoordinator.kt)、[RelayE2EConnection.kt](../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/net/RelayE2EConnection.kt)、[RelayControlDial.kt](../../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/net/RelayControlDial.kt) |
| 3 | iOS token 与前台事件 | [iOSApp.swift](../../../iosApp/iosApp/iOSApp.swift)、[PushController.ios.kt](../../../mobile/composeApp/src/iosMain/kotlin/dev/ccpocket/app/push/PushController.ios.kt)、[MainViewController.kt](../../../mobile/composeApp/src/iosMain/kotlin/dev/ccpocket/app/MainViewController.kt) |
| 4 | 存量迁移、多配对、设置诊断 | 沿现有设置、资源、Diagnostics 安全事件与 Android PushController 接线；先读各文件未提交差异 |
| 5 | 跨版本与专用设备验收 | 现有 PushRegisterTest／CollaboratorInboxPushTest、relay PushTest／CollaboratorPushTest，加故障注入与 iOS 实测 |

抽出可注入时钟、平台回调、发送器与存储的恢复协调器，避免继续增加散落在 PocketRepository 中的布尔标记。协议先行，状态机独立验证后再接实际网络／原生生命周期。

## A. 增量协议与服务端一致性

- relay 的 `Attached` 增加默认 false 的 ACK 能力字段（建议 `supportsPushRegistrationAck`）。本地 LAN 生成的 Attached 不是服务器能力证明；以实际 relay 认证连接的能力为准。
- 保留 `RegisterPush(platform, token)` 原 wire 类型，增加默认 null 的 requestId。只有服务器宣布能力后才请求新回执。
- 新回执建议为 `PushRegistrationResult(requestId, result, code?)`，result 至少区分 stored／cleared／rejected／failed；code 为有限枚举，不返回 SQL、token 或原始异常。
- account/device 来自已认证 socket，不允许请求指定其他设备。数据库事务成功且目标实际存在／更新后才回 stored／cleared，失败明确返回。
- 重复 upsert／clear 幂等；保存成功但 ACK 丢失，可重试相同期望状态，无需无限 request 历史表。
- 服务端按设备协调写入顺序，并拒绝已被替换的旧连接继续覆盖状态。客户端忽略旧 ACK 不足以防止旧 token 重写数据库；需覆盖旧连接回包、排队旧帧、关闭后迟到登记的竞争。
- 只响应带 requestId 的新请求；旧客户端不会收到未知 wire 类型。协议 fixture 必须覆盖缺省字段与旧样本反序列化。

## B. 客户端状态与持久化

系统层共享 token 获取状态：idle／requesting／available／retry_wait／blocked。系统权限与 App 偏好独立保存和判断。

每个配对使用稳定身份键 `(relay, accountId, deviceId)`，登记状态为 pending／in_flight／confirmed／retry_wait／blocked／legacy_unconfirmed。键只作业务状态，不进入云端遥测。

- 一个配对同时最多一个有效任务；requestId 关联配对、token 代际和启用／关闭意图。
- token 变化、关闭开关、解绑时废弃旧任务与旧排队帧；旧 ACK 不得确认新 token、另一配对或新意图。
- **不持久化 APNs token 作为客户端缓存**。冷启动向系统取得当前值；仅进程内保留。持久化 schema、期望状态、失败预算、冷却及确认摘要，不持久化在途 Future／requestId。
- 进程重建时 in_flight 回到待核对，不能沿用“已发送”。confirmed 只证明 relay 已保存，不代表 APNs 接受或设备展示。
- 冷启动取得 token 后为全部有效配对核对一次；持续运行时，确认超过 24 小时在下一次前台且连接可用时再核对。沿用冷却，避免频繁重启绕过预算。
- owner 多配对建议最多 2 个并发登记；任一失败不阻塞聊天和其他配对。联系人 inbox 保留现有定向语义；guest、execution、headless bridge 不新增权限。

## C. iOS 注册、传输与有限重试

首次权限仍在配对后的既有时机请求，不把升级变成首启立即弹窗。已授权时静默 `registerForRemoteNotifications()`；provisional／ephemeral 按系统能力处理。失败释放在途门控，成功按当前权限／用户意图处理，迟到失败不能覆盖已取得 token。

用 SwiftUI scenePhase 或已验证的 UIApplication 生命周期通知恢复前台检查；只保留一套有效监听。没有任何原生回调时，累计 **30 秒前台有效等待**后结束本次等待，记为尚无结果，不推断权限拒绝。后台挂起时间不计入。

| 参数 | 计划默认值 |
|---|---|
| token 获取／登记各自一轮 | 最多 3 次（含首次） |
| 同轮重试 | 首次失败后 5 秒、第二次失败后 30 秒；非首次加小幅抖动 |
| ACK 等待 | 实际发到可用 socket 后 10 秒，排队期间不计作已发送 |
| 一次短连接完整操作 | 沿用有界拨号／写超时，建议最多 30 秒 |
| 失败轮次冷却 | 5 分钟 → 15 分钟 → 60 分钟封顶 |

启动、前台、relay／网络恢复、token 更新、新配对、权限／开关变化驱动同一个协调器。发送提示词前可做非阻塞检查，但不能成为恢复的必要条件，不能为每条消息重开预算。token 获取与登记预算独立，不嵌套扩大成 3×3。

普通事件不重置冷却；新 token、新配对、权限从拒绝转允许、用户主动开启通知可提前开始一轮。系统拒绝、用户关闭、身份撤销不循环重试；离线等待网络可用，后台不保活。进程内耗时用单调时钟，持久时间戳校验回拨／损坏边界。

普通 relay 在认证后即可登记，不依赖 daemon 在线或 E2E 握手完成。发送失败从状态机重新协调，不能因 controlOutbox 已取出消息就永久丢失。LAN 短连接新服务器下等待匹配 ACK 再关；优先复用现有连接，禁止辅助拨号把同设备的活跃 relay 连接顶掉。

## D. 兼容、迁移与用户可见状态

| 版本组合 | 要求 |
|---|---|
| 旧 App＋新 relay | 原报文可用，不强制新字段，不下发未知 ACK |
| 新 App＋新 relay | 保存确认、有限重试与恢复生效 |
| 新 App＋旧 relay | 保留原兼容报文；legacy_unconfirmed，不因无 ACK 每 10 秒重拨 |
| 新 App＋旧 daemon | 注册协议不依赖 daemon 升级；通知触发按该 daemon 实际能力验证 |
| relay 升级或回滚 | 每次真实连接重新读能力，自动转入相应模式 |

迁移幂等地为既有配对建立待核对状态，不能把旧 started／pushRegistered 或 migration version 当作每台电脑已恢复。覆盖：已授权但无 token、本地以为登记但服务器为空、服务器曾清空失效 token、只有主电脑登记、升级时离线或退出。保留用户关闭偏好、配对、密钥、聊天和其他诊断预算。

已拒绝权限提供系统设置入口，不反复弹窗；未决定保留原申请时机。已授权且联网的老用户无需发消息即可恢复。旧 App 从未上传 token 的用户无法单靠 relay 发布修好，发布说明必须准确。

设置页分别表达系统权限、App 偏好、登记中／失败／已确认／旧服务未确认；多配对部分成功不能全局显示全部正常。重试入口复用协调器并防连点，不提供复制 token 或发送真实测试通知按钮。

诊断只记录安全阶段、有限错误类别、重试／耗尽／恢复结果；沿现有预算和脱敏规则，不输出 token、account/device、公钥、凭据或原始附件内容。

## 验收矩阵

每个故障用注入时钟／假发送器／临时存储验证状态变化，再在专用 iPhone 验证原生路径。

| 场景 | 必须证明 |
|---|---|
| 冷启动已授权、从未成功登记 | 不弹重复权限，不发消息也能完成登记 |
| token 获取失败／无回调／挂后台 | 释放门控、有界重试，后台不错误累计超时 |
| 排队后断网、写失败、存储失败 | 不假 confirmed；条件恢复后重试 |
| 保存成功 ACK 丢失 | 幂等重试后确认，没有无限任务 |
| token A→B、登记中关闭、旧连接迟到写入 | 旧帧／ACK 不回滚最新状态；关闭最终清空 |
| 退出重启、时钟回拨、迁移中断 | 预算和待恢复状态正确，不永久停滞或重试风暴 |
| 多电脑、主辅切换、联系人、受限身份 | 每个允许的配对独立处理，权限边界不扩大 |
| LAN↔relay 切换、daemon 离线 | 登记通路正确，不相互踢连接，不阻塞聊天 |
| 新旧版本四组合及 relay 回滚 | 满足上面的兼容表 |
| 专用设备完成／出错／额度事件 | 触发、合并／抑制、登记、APNs 接受、实际展示分别有证据 |
| Android 回归 | 原权限时机与 token 登记继续工作；跨平台 expect/actual 能编译 |

## 现场保护与交付

沿用来源方案的明确现场边界：不覆盖用户当前手机 App，不擅自重启／部署生产 relay 或本机 daemon，不向真实用户发送测试推送。使用隔离服务和专用设备；生产发布与现场安装另行安排。若涉及 daemon，遵守 AGENTS.md 单实例及活动会话确认门。

推荐发布顺序为兼容 relay → 客户端，支持服务器回滚和旧自托管 relay。代码完成、relay 已部署、客户端已发布、真实设备恢复是四个不同状态。测试通过不自动关闭 Issue。

## 结果记录（实施后填写）

- #389 附件核验、实际断点及关联可信度：**未核验**。实现由另一会话完成，本记录撰写时未读取 Issue 附件中的诊断 Markdown，没有把用户现场断点与注册缺口关联起来；当前修复针对方案中已确认的源码缺口，不能据此宣称已找到反馈者的根因。
- 注册恢复与其他故障分支的完成状态：注册恢复已实现（提交 `1e1e3a6f`：`RegisterPush` 的 requestId、`PushRegistrationResult` 回执、`PushRegistrar` 恢复协调器、iOS 生命周期恢复）。2026-09-19 审核（`_local/review-2026-09-19-6df16c59/REVIEW.md`，未入库）发现五项缺陷，已全部修复：① commonMain 的 `@Volatile` 缺少公共导入，metadata 编译失败；② 已被替换的 relay 连接能在清除 ACK 后写回旧 token——`Broker` 增加按设备的条带锁，连接替换与登记写入在同一临界区核对连接身份，被替换连接的在途／排队／迟到登记一律不写库、不回执；③ relay 控制帧 writer 依赖 E2E 握手——改为收到 Attached 即启动，握手期间转发登记回执，仍复用同一条设备连接；④ token 轮换后的二次确认失败停在 PENDING——改走统一的有限重试与冷却；⑤ 同步 UNSUPPORTED 失败丢失——先订阅再请求，失败事件按请求编号关联。触发、合并抑制、APNs 接受、设备展示等其他故障分支未诊断。
- 协议／参数最终取舍、提交及相对草稿的变化：wire 未新增字段。被替换连接的登记选择静默丢弃而不是回失败码，因为现有 `REJECTED`／`no_device` 会让客户端判定 BLOCKED，`store_failed` 又会误报存储故障；客户端靠 ACK 超时在新连接上重试。`PushController.requestToken` 的平台接口改为单次失败回调，去掉全局 `onRegistrationFailed`；iOS 的 Swift bridge 不带请求标识，失败只能归到最近一次请求。daemon 离线时连接仍会在 15 秒握手超时后重连，重连空窗内的登记等下一次 Attached。审核修复尚未提交。
- 自动测试、iOS／Android 验证、实际展示与未执行项：relay 定向测试 62 项全部通过（`PushSupersedeRaceTest`、`PushTest`、`PushRegistrationAckTest`、`CollaboratorPushTest`、`RelayCoreTest`、`BridgeRelayTest`）；移动端 `compileCommonMainKotlinMetadata` 通过，定向测试 44 项全部通过（含审核复现用例及新增的“只有 relay、没有 daemon”用例）。审核的三个复现用例在修复前均失败，已作为常驻回归保留。**未执行**：iOS framework 与 Swift bridge 编译、Android 编译（本机无 SDK）、专用 iPhone 生命周期与真实通知展示、新旧版本四组合、Android 回归真机。
- relay／客户端发布需求与现场保护情况：需要先部署兼容 relay，再发布客户端。本轮未部署或重启生产 relay，未更新本机 daemon，未覆盖用户手机 App，未发送任何真实通知。

### 2026-09-19 复审补充修复：取消的控制帧跨连接重发

- 复审发现：取消旧登记后，经 LAN 短连接两次清除均收到 ACK，普通 relay 恢复时仍会发送队列中的旧 token。帧通过新连接到达，服务端的旧连接检查无法阻止。
- 已修复：等待发送的回执绑定调用方 Job，取消／超时使排队帧失效，writer 跳过失效帧；取消还会中止仍在等待的写入，且不因此停止其他请求的 writer。控制帧绑定 `(relay, accountId, deviceId)`，切换配对时拒绝错配帧。已经交给 socket 的字节无法撤回，仍由登记协调器按最新意图收敛。
- 常驻回归 `RelayControlCancellationTest` 覆盖取消后 LAN 清除再回 relay、超时后 token 更新、配对的三个身份字段分别变化；以后一有效请求的 ACK 证明前面的队列已处理完，同时验证有效排队请求能继续发送。
- 验证：`compileCommonMainKotlinMetadata`、桌面编译通过；移动端 7 个专项类共 **42 项、0 失败**（上轮推送专项加上述 3 项，不含 `ConnectionDiagnosticTest`）。仓库内容检查和 diff 格式检查通过。本次修改未提交、未部署；iOS／Android 原生编译及真机通知验收仍待执行。

## 2.1.1 发布补记（2026-09-19）

已发布／iOS 待审核，待真机验收；[GitHub 状态回执](https://github.com/heypandax/pairlet/issues/389#issuecomment-5744963021) 已写回并核对。完整构建、生产部署和剩余验收范围见 [2.1.1 发布记录](RELEASE-2.1.1.md)。上文未推送／未发布等描述保留为当时的实施快照。
