# Sentry 实施进度

开始：2026-09-09；基线 `6ca60173`；分支 `codex/sentry-observability`。

依据：[实施方案](../design/OBSERVABILITY.md)、[核心路径和任务](ERROR-PATHS.md)。用户已授权启动开发，按顺序推进代码、测试和集成；云端 DSN/真实事件、设备与发布证据分别记录，不能由本地测试替代。

**最新执行状态（2026-09-11）**：A–D 主体实现与集中回归已有证据，当前按 [夜间抽样计划](NIGHT-PLAN.md) 收尾。Android 与 iPhone 12 已实际配对；Codex/Claude 基本旅程、跨设备历史查看、两端开关重启、Android 离线恢复与文件显示取得新增证据。新 Android Errors/Logs 和 iOS Logs 已在 Sentry 核实；GA4 已看到 desktop 启动及连接恢复结果，手机结果仍需后台回执。三条新增布局 Compose 回归通过，但 Android 的一次 layout 超时尚未定位，不标已修复。完整发布、原生 fatal、符号和成熟观察仍未验收，详见 [ACCEPTANCE](ACCEPTANCE.md) 首节。

## 当前检查点

夜间增量没有修改产品运行代码，仅补布局回归与计划/证据文档。实际手机继续使用前次已签名并核对哈希的包；首次使用、配对和采集开关通过 ADB / Xcode WDA 操作，测试后的采集偏好已恢复。下列 OBS 历史范围不因抽样自动整行关闭。

- OBS-01：进行中。已建立 KMP 契约、固定分类/安全栈、独立操作阶段、限频/异步队列；本批补 SDK 交付前的持久额度，本地 JVM/Native 文件及 SDK 出口测试通过。完整健康计数持久性、SDK 离线/退出与全部平台实际包验收仍待完成。
- OBS-02–04：进行中。已接 Android/desktop/daemon/relay 初始化、App 持久开关、daemon 独立开关；会话超时/打开确认、协议解析、outbox、relay 拒绝、历史部分读取、Agent 启动与管道等入口。尚不能按 EP 整行关闭任务。
- OBS-05–09：实现及本地回归已推进，覆盖审批结果、文件真实布局、上传、存档、调度、协作、Git 与更新等入口；全部适用分支和实际包旅程仍依清单补验，未逐项关闭。
- OBS-10：进行中。Pairlet 五项目、Sentry 四组看板和 GA4 四个探索配置已建立。最终 desktop/daemon/relay 开发包已部署；本轮补 Android 真机 Errors/Logs、iOS 新日志，以及 GA4 desktop 连接恢复结果。手机 GA4 后台、留存配置、发行配置/符号与成熟观察仍未通过；iOS 安全栈抽查只到函数，文件/行号仍为 unknown。见 [当前证据](ACCEPTANCE.md) 与 [历史回执](PAIRLET.md)。
- 保留原有 Analytics 和 Crashlytics 自动 fatal；初次 Sentry 接入只提交明确构造的安全事件，未启用自动内容采集。

## 验证记录（追加）

- 2026-09-09：当前仓库除方案文档外无未提交代码；原 OpenCodeLauncher 修改已包含于基线提交。读取官方 KMP 0.27.0 对应 Cocoa 8.58.2，正核查 Java 依赖和 API。环境与仓库配置没有 Sentry 配置键；没有输出任何凭据。

## 恢复规则

恢复时读取本页、Git diff 与最近测试输出，继续未完成任务。每个任务记录源码入口和实际测试命令；仅编译通过不标云端完成。SDK 出口、wire 或鉴权变更须按方案完成隐私/兼容/安全验证。禁止通过第二 daemon 进行测试。

## 已验证的实现选择与当前限制

- 使用 Java SDK 8.41.0、Cocoa SDK 8.58.2，公共 KMP 模块不依赖供应商。KMP 包装 SDK 0.27.0 的公开异常模型不能设置安全的原始栈，故在 Java/Cocoa 两个出口直接构造 SDK 事件；此处是为保留原始捕获位置作出的实现调整。
- 自动 fatal 暂由既有 Crashlytics/桌面 crash guard 负责；没有启用 Sentry 原生自动崩溃、Replay、Profiling、自动 HTTP 追踪或全局 logger 转发。
- `SessionLive` 只证明打开确认，不证明历史/首屏完整。P1 记录 `stage=attach`、`result_quality=unknown`；显式历史完成信号与跨端关联仍属 P2。
- 当前源头仍有进程内限频，另在 SDK worker 交付前预留持久日额度；重启、版本更换或开关不再重新发放已用额度。Java 事件仍为内存队列；Cocoa 使用独立临时 SDK 缓存，完整离线/退出丢失边界待故障测试，不能宣称可靠补传。详见 RELIABILITY。
- Android/daemon/relay/desktop 及 iOS 编译通过；完整 JVM/desktop 回归与公共模块 iOS Native、Cocoa SDK 实际上传请求的本地测试已通过。最近结果及增量验证见文末，测试不访问真实 Sentry 项目。
- 已取得五个项目 DSN，并验证三个 JVM 项目的合成错误和日志；已安装/重启本机 desktop、daemon，并部署 relay，实际 relay production 日志有后台回执。真实设备符号、全平台正式发布及 7 天观察均未完成。

### 2026-09-09 后续进展

- Android/Desktop/daemon/relay 编译通过；App 开关接入和 iOS 原生桥接已写入。移除接受自由文本的旧 `Telemetry.recordError` API，配对错误改为固定类型诊断。
- 会话打开开始/排队/等待/重试/确认已有端内操作对象；新增真实路径断言通过（打开超时、慢响应恢复、分屏/历史现有回归）。打开确认仍不代表历史完成。
- 公共模块现有 9 项测试在 JVM 和 iOS Simulator 通过，包含真实原始函数栈；iOS 栈当前保留 Kotlin 函数名，尚不带源码行号，不能以此替代原生符号验收。
- Java SDK 5 项测试通过，包括最终 envelope、真实 HTTP 429/Retry-After、满队列与停止；daemon 3 项真实分类/读历史/独立配置测试通过。对未知 Frame 的首个测试发现序列化库在错误前加了偏移前缀，已按实测格式修复并回归。
- Cocoa 实际 envelope 测试已加入 `DiagnosticTests` XCTest target，尚在构建。SwiftPM 尝试下载本包全部二进制变体且长时间停滞；使用官方 URL 续传、逐个校验官方 SHA-256 并填充 SwiftPM 缓存，未改变 SDK 版本或二进制内容。
- 正在运行包含最后一批 Agent/管道/历史传输记录的全量回归。未启用跨端字段/原生 spans，未部署或上传云端。

## 首批捕获点（入口覆盖，不等于整条 EP 已验收）

| 路径 | 本轮入口 | 已知边界 |
|---|---|---|
| EP-01/02 | daemon 监听失败/核心协程异常、desktop crash guard | 自动 native fatal 未切换；Android/iOS 启动依赖崩溃仍走 Crashlytics |
| EP-03/04/05 | App 连接/直连回退、配对分类、握手超时；daemon 拒绝非法握手 | 正常取消不采集；所有握手材料留在本地，不上传 |
| EP-06/07 | App/daemon Envelope 解码、App 发送失败 | 未知 Frame 降级为日志；部分 outbox 内部队列状态尚待补齐 |
| EP-08 | relay 替换/拒绝/关闭、接收循环异常和超大帧类别 | 连接日志有窗口限频；真实超大帧断连仍待集成/云端验证 |
| EP-09/10 | 后端列表扫描失败、Claude 文件扫描部分结果、App 打开确认与超时、registry 打开失败 | 各后端内部吞错的完整覆盖未完成；打开确认不是历史完成 |
| EP-11/12/13/14 | Claude 历史读取/解析、App 分页超时、daemon 编码/发送、App/分屏共享历史合并 | 其他后端历史内部降级、fan-out 汇总、完成标记待续 |
| EP-16/17/18/19 | 共用 AgentProcess 启动与管道、Conversation 后端配置/attach/解析记录/失败终态、App 回执超时与 ACK 后无输出的两阶段看门狗 | 六种后端共用入口已有覆盖；每个后端专有 RPC/协议状态矩阵未全部验证 |
| EP-23/24 | SessionArchive 读取回退/写失败、Scheduler 执行/周期失败 | 其余 stores、后台 workflow 仍待推进 |
| EP-30 | 固定 schema、栈过滤、独立操作、采样/限频、队列/字节上限、429、关闭 | 进程重启后的持久预算、SDK 离线缓存/恢复、全部平台云端证据未完成 |

上述节点继续标“开发中”，不能把定义了 30 个枚举或编译通过当作 30 条路径已交付。后续按 ERROR-PATHS 的 OBS-05–10 和本表缺口推进。

## 本轮本地验证收尾

- 全量 `scripts/check-all.sh` 两次通过，最近一次 5m50s。当次结果：observability 9、Java SDK 5、protocol 273、daemon 1,879、relay 54、desktop 1,398，共 3,618 项，其中 1 项按既有条件跳过；无失败。
- 之后增加测试环境隔离和 Runtime 隔离断言：observability 9、Java SDK **6**、desktop 1,398 再次通过。最新握手/接收边界的 daemon DeviceSessions 回归、relay 测试、Android 与 iOS 编译均通过。
- Xcode **26.3 (17C528)**、iOS Simulator **26.2**：Cocoa XCTest 的实际 envelope 安全测试通过；关闭测试宿主 Firebase 后再次通过，测试确认隔离标记已生效。公共模块 9 项 iOS Native 测试也通过。
- 最终复核统一了 Cocoa/Java 的 `result_quality`、`returned_count` 等字段与 `suppressed_count`，并修正 Prompt 的超时阶段：没有回执为 ACK；收到 ACK 后没有输出为 EXECUTE。新增 3 项真实 Repository 诊断测试与既有 16 项 Prompt 回归、6 项 Java SDK 测试均通过；Cocoa 实际 envelope 测试再次通过（`Test-DiagnosticTests-2026.09.09_22-59-07--0700.xcresult`）。正常输出和排队等待无超时误报。
- `:daemon:installDist` 已构建成功。检查发现本机仍有 1 个会话进程，按 `scripts/update-local-daemon.sh` 的活跃会话保护，尚未安装/重启；已向用户询问，不自动 FORCE。
- 云端仍缺 Sentry DSN；未部署 relay、未切换自动 fatal、未安装桌面/iPhone 发布包。不能宣称 P0–P3 或 EP-01–30 全部完成。

当时的下一检查点：取得云端配置后逐组件验收真实 Events/Logs；完善重启预算与离线/关闭证据，然后继续 ERROR-PATHS 中未完成的 OBS-02–09，最后执行 OBS-10 发布验收。

## 2026-09-10 Pairlet 云端接入

- 用户明确同意服务条款、美国区创建 Pairlet，并删除旧组织 ibelove。Pairlet 五个项目已创建；ibelove 当前页面为 **Deletion Scheduled**，后台最终清理尚未完成。未启用付费试用或付款方式。
- 新增 `:observability-sentry:smoke` 独立探针及两项测试：固定 staging/EP-30/smoke_test，发送一条安全栈错误和一条结构化日志，不启动 daemon，也不修改持久采集开关。为短命探针增加最多五次有界 flush，另提供仅用于该合成探针的显式 SDK 调试选项。
- 已在后台逐项核对 desktop/daemon/relay 错误、日志与诊断编号，详见 [Pairlet 配置及回执](PAIRLET.md)。SDK 返回成功与队列接收均不替代该后台验证。
- 发现 Sentry 云端会补充 IP 地理信息：启用组织级默认脱敏、禁止存储 IP，增加 `$user.geo.**` 移除规则。规则后的 relay 错误已确认没有 User/Geography 上下文；初始合成测试事件不会被追溯清理。
- 本机五组件 staging 配置由 Git 忽略。Android 编译、desktop 1,401 项测试、daemon/relay installDist 均通过；公共模块 9 项及 Java SDK/探针 8 项测试通过。iOS `-showBuildSettings` 验证 DSN 展开与 staging 环境正确；这不是 iOS 设备上传回执。
- 本轮没有安装/重启 daemon，没有部署 relay，没有安装 iOS/Android/desktop 发布包。

下一检查点：真实应用与常驻进程的版本/开关/配置核对、移动端云端回执及符号验证；继续持久预算、离线边界与未完成的 OBS-02–09。整体方案仍在开发中。

## 2026-09-10 实际部署验证

- 用户授权真实设备和 daemon/relay 部署，并明确批准中断当前 Kimi 会话进行 daemon 更新。desktop、daemon、relay 分发构建成功；桌面更新脚本和获准后的 `FORCE=1` daemon 更新脚本均完成。daemon 更新后保持单实例，loopback 与 relay 连接通过；独立 diagnostics 开关已开启。
- 桌面端实际安装版本 1.9.8，已通过 UI 刷新列表、打开一个已有会话并看到历史。daemon 与 desktop 已核对安装产物中的 Sentry 项目和 staging 环境；正常打开不代表已产生新错误，尚未取得这两个实际进程的新事件回执。
- relay 部署前备份旧分发包、服务 unit 和 Caddyfile；部署后源站/公网健康通过，实际进程环境为 production。对实际 `/v1/device` 发一条 `{}`，收到 `1008 / expected_hello`，Sentry 确认 EP-08/unsupported/connect，诊断编号 `16ae3b48f996498cb50433a12d75206f`。未运行会启动第二 daemon 的旧生产 smoke 脚本。
- 新增共享 `iosApp.xcscheme`，恢复原安装脚本依赖的主 App 方案；通过真实 generic iOS Debug 构建和签名校验，Info.plist 的 Pairlet DSN 与 staging 已验证。Pandaa 解锁后的无线安装重试仍返回 CoreDevice 1011、连接尝试 4000；尚未安装、拉起或收到手机事件。已询问手机当前 Wi-Fi IP，继续定位需要这一连接信息或设备恢复可达。
- 具体版本、进程、备份与云端字段见 [部署回执](PAIRLET.md)。当前没有 Android 真机部署证据，也没有全平台正式发版。

下一检查点：恢复 Pandaa 无线连接并安装现成签名包，验证手机真实上传与符号；补齐实际 daemon/desktop 事件、关闭/离线及持久预算。原有未完成路径和整体开发状态不变。

- 同日 00:29 PDT 续查：已经通过 mDNS 获取 Pandaa 局域网地址并确认 62078 可达，不再缺少手机 IP。再次安装、直接主机名连接及刷新 Mac CoreDevice 服务均未恢复无线开发通道，Xcode 仍为 Disconnected，安装仍报 1011。手机尚未安装；需要设备恢复开发连接，可先通过 USB 让 Xcode 重新识别。

## 2026-09-10 00:34 PDT Pandaa 安装完成

- 用户插线后 Pandaa 状态变为 connected；通过带本机 Sentry xcconfig 的标准 `scripts/install-pandaa.sh` 重新构建、校验新鲜度、安装并启动。设备侧确认 App 版本 `1.9.8 (19)`，进程 `9234` 存在。安装阻塞已解除，无 OTA 发布。
- iOS Sentry 后台首次查询已有 5 条实际日志，包含连接回退与关闭。已展开核对 `538ebc26316842d8b3318a06a97d307f`：staging、`cc-pocket-ios@1.9.8`、EP-03/fallback_used/connect、direct、DarwinHttpRequestException；详见 [真机回执](PAIRLET.md)。日志接通不代表错误栈符号或完整会话链路验收。
- 本轮复核签名、安装产物 DSN/环境、设备版本与进程；`git diff --check` 通过。未改业务实现，也未重复已通过的全量测试。

下一检查点：iOS 错误栈及符号、实际 daemon/desktop 新事件、Android 真机、关闭/离线与持久预算；继续原有未完成路径。手机安装不再是阻塞。

## 2026-09-10 后续任务确认稿

- 根据用户“先出个文档确认下”的要求，整理 FOLLOW-UP-PLAN.md，沿用 OBS-01–10，将剩余工作分为采集基础、会话与 Agent 链路、其余核心路径、排障与发布四批，列出交付物、故障/正常对照、平台依赖及整体完成标准。
- 本轮只更新文档与入口，未修改业务代码、未注入故障、未重启或再次部署。现有阶段结果继续保留，后续实现等待本稿确认。

下一检查点：记录用户对范围、顺序和可靠性目标的确认或修改，再按确认稿继续。

## 2026-09-10 产品使用与问题分析补充

- 用户进一步要求同时了解实际问题与用户使用，以指导产品改进。核对 TelEvent、PocketRepository 和三平台 Telemetry 出口，选择首次价值、核心操作完成、连续使用稳定性、功能采用/复用、留存/版本五个视角；首批优先前三项。
- 新增 PRODUCT-INSIGHTS.md，明确 Firebase/GA4 的行为/结果/留存口径、Sentry 的故障/性能证据、安装数与事件数区别、Demo/内部流量隔离以及统一业务结果来源。核对官方 Analytics/GA4、Sentry 与 Measurement Protocol 文档；当前 desktop 固定 engagement_time 不可用作真实时长，纯 MP 标准报表能力需单独验证。
- 调整确认稿执行顺序：A 必要基础后尽早推进 B，其他平台/符号补验交错进行。本轮只更新文档，没有读取实际用户报表或创建云端视图，没有业务代码、配置、部署和通知变更。

下一检查点：按更新的确认稿收敛事件/指标字典及首批三个视角，再进入实现；已有缺口和证据状态不因本稿自动关闭。

## 2026-09-10 启动前任务完整性核对

- 对照实施主方案、ERROR-PATHS 的 OBS-01–10 / EP-01–30 和 PRODUCT-INSIGHTS 的五个视角核对 FOLLOW-UP-PLAN。技术范围已有承接，产品采用/复用、留存/版本和部分结果事件此前主要在产品稿中，现补为总计划明确任务与验收项。
- 补齐启动/配对、采集说明及开关出口、独立失联验证的显式任务；新增范围映射和 EVENT-CATALOG、两侧查询/回执、首轮 PRODUCT-REVIEW 的交付要求，既有路径与发布范围保留。
- 范围核对通过；这不是新的实现或验收通过。本轮仅更新文档，没有业务代码、设备、服务、云端视图或通知变更。
- 文档验证：`git diff --check` 通过；5 份相关文档的 38 个本地链接有效；范围索引覆盖 OBS-01–10 和 EP-01–30，结果事件与交付物条目齐全。这些是文档结构检查，未运行新的业务测试或云端验收。

下一检查点：用户启动后，复核现有工作区与实际进度，先完成 A 的事件/指标字典和必要采集基础，再尽早进入 B 的会话打开与首次有效使用链路；其他平台/符号补验交错推进。

## 2026-09-10 A 批恢复开发

- 用户明确启动后续开发，按已核对的 A–D 范围继续，暂停解除。本批先补持久诊断额度、实际可操作的 App 采集开关、桌面 Analytics 的有界发送与取消、三端统一维度及 EVENT-CATALOG。
- 新增公共 DiagnosticBudget 与 JVM/Android、iOS 的原子文件存储。实际 SDK worker 先预留额度，成功落盘才交付；重启/更换 SDK/版本、开关切换不重置，回拨不提前发放，坏文件当日不再发放。关闭期间 I/O 晚到会被再次检查丢弃；文件只存有界汇总计数。
- desktop GA4 增加 32 条队列、串行请求和代际检查；关闭清队列并取消在途请求，旧生产者准备的事件不能跨关闭/重开进入新队列。Android/iOS 关闭时调用 SDK 数据重置与未发送 Crashlytics 报告删除 API，真实缓存/网络仍需平台验收。
- 设置的支持与关于页新增共享开关和中英文说明，App/daemon 授权独立。三端自定义 Analytics 事件新增 schema/platform/environment/internal/usage_mode 维度；未知环境不猜为 production，自定义工具名归 other。既有事件仍为原来的意图语义，B/C 结果事件尚未接入。
- EVENT-CATALOG 与 RELIABILITY 分别记录事件/指标、代码来源及队列/额度机制，特别区分已预留、已排队和后台收到，以及目前仍在进程内的部分健康计数。
- 初轮验证：公共 JVM 17 项、公共 iOS Native 16 项、Java Sentry 10 项、Telemetry 14 项测试通过；desktop、Android Debug、iOS Arm64 编译通过。Android 兼容问题已改用 API 26 可用的 NIO 读取方式。最初没有可用模拟器导致 Native 测试启动失败，创建隔离的 Pairlet Diagnostics A（iOS 26.2）后测试通过。

- 完整回归通过：3,640 项、0 失败、1 项既有跳过。之后的错误字节预留、关闭/在途取消和两套设置页调整分别通过最终增量：公共 JVM 17、iOS Native 16、Java Sentry 10、Telemetry 16、Cocoa SDK 2 项；Android Debug 和各目标构建通过。计数包含关系及精确证据路径见 [ACCEPTANCE](ACCEPTANCE.md)，不把增量重复计入全量。
- 日志与错误在源头和持久额度均保留独立字节空间，日志不能耗尽剩余错误额度。最终 SDK 出口再次检查关闭状态；桌面请求提交前检查协程取消，持久预算拒绝不计入 sink 的已预留字节。
- 原生 desktop 设置页与手机共用采集开关；偏好保存失败会显示可重试提示，当前关闭先于偏好写入生效。实际桌面安装包已更新，实机界面可见说明，其他组件本批同步证据见 ACCEPTANCE。
- 本批 desktop、Pandaa、daemon、relay 均已完成开发包同步；Android 仅编译。Pandaa 解锁后成功启动，实际沙盒已有新版额度预留记录。daemon 的活跃会话结束后按标准脚本更新，独立核验为单实例；新 relay 包身份、源站/公网健康以及 daemon 重新收到 Pong 均已核对。
- relay 标准脚本在新包替换后的启动步骤遭遇 SSH 认证拒绝，服务短暂中断；已通过单次远端会话启动新包并验证恢复。旧包备份仍在，原脚本失败与人工恢复分别记录在 ACCEPTANCE。OBS-10 增加具体部署恢复加固验收，未把本次恢复说成脚本机制已经修复。

下一检查点：继续 B 的会话结果闭环；A 的完整离线/退出、全量健康汇总、原生符号和所有平台云端回执仍逐项推进，未按整批完成关闭任务。本批部署及本地验证不替代本批 GA4/Sentry 正式查询回执。

## 2026-09-10 A–D 连续实施

用户要求完整推进 A–D，实现完成后集中测试。开发阶段记录如下；未执行测试、未把旧回归结果用于新代码。

| 范围 | 当前执行状态 |
|---|---|
| A 剩余可靠性、计数、SDK 与符号 | 开发中；平台实验留到集中测试 |
| B 协商、跨端关联、历史完成、业务结果 | 开发中；新旧版本与多后端留到集中测试 |
| C 核心路径与功能采用 | 已接入主要来源；剩余分支审计与结果覆盖收尾 |
| D 查询、配置、发布及故障恢复 | 部署脚本先加固；后台/观察分别取证 |
| 集中测试 | 尚未开始，遵守本次用户顺序 |

7 天运行观察、成熟留存样本与正式发布的实际状态单列；完成实现不代替真实经过时间或发布结果。

### A–D 实施检查点：协议与业务观测（未测试）

- A：新增独立持久汇总计数，区分事件、字节和请求；Java SDK client-report 记录落本地，Cocoa 传输计数与旧私有缓存清理已写入。Cocoa 的关闭/初始化/清理移到工作队列，业务锁不包围 SDK 初始化或磁盘扫描。公共 breadcrumbs 改为 reporter 级 64 × 32 有界池。新增 Analytics 代际门，跨关闭/重开不补发旧操作结果。
- B：新增容错 DiagnosticContext、两侧能力声明、HistoryComplete/HistoryApplied；owner 入口按原 sink key 包装，仅匹配的回执结束本次观测。冷打开、热重连和观察会话均加入完成入口。App 的主会话/分屏分离观测，真实布局后记录结果，15 秒诊断期限不重发或取消业务。
- B：wire 静态专项评审未发现旧 schema 破坏，提出 LAN 能力声明并发竞态；已改为按接收顺序先应用 ClientCaps，App 以匹配 SessionLive 回显确认本次协商。评审之后又新增 PromptProgress，需要最终增量复审；所有兼容测试尚未运行。
- B：新增 PromptProgress 的有界里程碑队列，观测原有 prompt 消费账本、首个真实文本/工具输出、终态和意外退出；不向账本新增重试或改变消费判断。客户端 prompt_response_result/turn_result 与 Sentry 采样独立，旧端覆盖未知；查看价值要求实际可见内容，不以后台收到输出直接替代。
- B：六后端的历史读取质量补齐中。Claude/Codex/Kimi 行式解析和 OpenCode/ZCode SQLite 记录复用现有读取统计；DSH 压缩完整前缀与未完成尾部保持正常降级语义。合法窗口裁剪和源读取质量分别记录。
- C：先接生命周期：后台暂停观测不报业务失败，连接恢复只在真实 ready 后收口。审批、文件、后台任务、协作、推送、Git、升级及五功能曝光/复用仍需继续接入。
- D：relay 部署改为先准备并校验文件，再由服务端 systemd 独立任务切换、启动和失败恢复；SSH 结果未知只读回状态。尚未执行脚本演练或部署。后台查询/报表配置、原生 spans/fatal、符号、实际包回执及运行观察仍未完成。

本检查点没有运行任何新测试、构建或实际部署。下一步完成 C 与原生 spans/符号、D 查询材料和后台配置，写好集中测试矩阵后再运行；不得引用上批 3,640 项回归作为这些新改动的验证。

### A–D 实施检查点：C 来源、手工 spans 与云端配置（未测试）

- A/B：Java/Cocoa 写入受控手工 transaction/span，四类操作低比例采样，关闭代际与有限预算独立检查。持久计数覆盖 SDK 丢弃类别及请求失败；原生自动 fatal 仍关闭，不能用这批手工 spans 代替 native crash 迁移。
- B：连接与配对加入操作阶段，relay Attached/PeerPresence 增加容错可选 socket 编号，客户端区分本端与对端编号。relay 只关联连接，不接收业务 trace。App/daemon 的历史统计继续复用已有读取，PromptProgress 观测 ACK、排队、实际消费、首输出和终态；文件分片只在所有被接受分片具有相同有效上下文时保留诊断关联。
- B：第二轮 wire 静态评审未发现新旧 schema 破坏；指出的文件分片上下文、待 ACK/退出证据缺口已补实现。提示真正写入的 generation 与未消费等待重投分开记录；缺 ACK 不推断 Agent 未执行。所有新兼容与业务测试仍未运行。
- C：审批裁决回调返回、gate resolve 只说明适配器结果未知，不伪造 Agent 应用成功。文件读/导出 owner 上下文、文本/图片真实布局、分屏提示输出布局已接；HTML/文档没有 renderer 回执时保持 unknown。后台 workflow/job/scheduler 使用原有状态来源，派发与执行终态分开；历史终态不补成新使用。
- C：存储读写、Git 执行、上传落盘、推送提供商和升级阶段继续接入。升级切换后的有界本地 receipt 只在新进程同版本成功认证 relay 后记录重启证据；不以下载完冒充重启成功。App/Fleet/desktop 生命周期接入，后台诊断等待不新增前台超时错误；拆分 pane 的观测跟随实际布局。
- D：Sentry 已保存并回读 [四 widget 看板](https://pairlet.sentry.io/dashboard/10014172/)，仅样本查询，未启用告警。GA4 540841272 已登记并回读十个新维度 analytics_schema/app_platform/app_environment/internal_traffic/usage_mode/app_version/result/coverage/feature/backend，以及 duration_ms 毫秒指标；原有七维度保留。五个探索报表和受控旅程仍待完成。
- D：新增 [queries](queries.md)、[RELEASE](RELEASE.md)、[PRODUCT-REVIEW](PRODUCT-REVIEW.md)，以及单次独立 HTTP 健康探测、真实回执 P95 汇总和 iOS archive UUID/可选符号上传脚本。脚本尚未执行；未创建长期监控、通知或公开发版。

当前剩余：来源分支和取消/关闭审计、原生 fatal 安全迁移实现及平台验证、最终测试夹具、GA4 五视角保存/回执；之后集中构建测试、实机与云端验收。七天观察须实际经过时间。本检查点没有新增测试、构建、设备安装、daemon 重启或 relay 部署。

### A–D 实施检查点：未知结果、源头补充与首次价值（未测试）

- PromptOutcomeTracker 增加实际 consumed 后的独立响应期限与一次性 prompt_response_recovered，不依赖新版 Agent 已跳过的旧看门狗；后台 waiting、未知终态与真正失败分开。同 promptId 换 convo 增诊断 attempt，旧回执不匹配新操作。
- 增加非错误 Outcome.UNKNOWN。原本用 CANCELLED 表示未知会被 reporter 丢弃，现未知结果按成功同档采样进入 RESULT；Java/Cocoa 都拒绝将 UNKNOWN 生成成功的手工 transaction。真实取消仍按原规则不采集。
- SessionOpenDiagnostics 的缺应用回执由现有 diagnostics worker 过期回收并记录 unknown；同 key 替换收尾旧 Receipt，旧发送失败仅清理自己的对象。静态专项复审指出的问题已改，新增并发、缺回执、owner/legacy/guest、端能力、旧消息及字段兼容用例，均未运行。
- 补充项目扫描源失败、重连 outbox 写入丢失、图片解码降级、Android/iOS 推送注册失败、relay SQLite 异常、review/handoff 发送部分失败及周期 reconcile 失败。原拒绝/权限选择/重试/回退保持业务原语义；升级跳过校验显式记 unknown/fallback，不声称 checksum verified。
- relay 恢复脚本只有旧文件恢复、新服务启动/健康及 Caddy reload 均成功才写 rolled_back；新增临时目录和服务命令替身用例。新增健康/P95/GA4 debug 校验工具、持久计数和手工 SDK trace 用例，未执行。
- GA4 已写入核心结果、连续恢复、功能采用、配对入口四个视图的条件；留存选择器尚没有新 value 事件，保留明确标记的未配置草稿。核对官方群组语义后新增 first_value_observed：独立有界后台队列和原子布尔标记，环境/角色/内部流量隔离，升级不重新纳入，Android/iOS 身份重置同步清理，desktop 保留既有身份。新事件只代表首次观测，不能当新安装或保证供应商已接收。
- iOS native fatal 仍有实际 SDK 写入前隐私缺口：锁定的 Cocoa 8.58.2 在 beforeSend 之前把 crashReason / NSException userInfo 写入磁盘。仅修改上传过滤不能满足方案；保留 Crashlytics 和明确未迁移状态。Android fatal 也未迁移，不能用 handled 事件替代。

本检查点仍未运行新测试、构建或部署。需完成报表保存回读和实现审计，再进行集中验证；原生 fatal、安全符号、实际平台回执及真实七天观察逐项保留未完成状态。

首次价值事件的实现补充：`FirstValueObservation` 在独立 worker 上处理最多 8 个待认领项，只存固定布尔标记。已有标记或损坏标记不产生新 cohort；关闭/重开前的生产者通过同意代际失效，身份重置先清理后认领；SDK 发送失败不重试、不重新发放首次标记。已写入跨重启、版本/功能变化、Demo 排除、角色分支、身份重置、旧生产者和磁盘失败用例，尚未运行。

### A–D 集中验证开始

- GA4 重新打开已保存探索，核对采用视图的事件/平台/功能/角色行与事件数/用户总数；核对配对漏斗四个精确事件及 schema、production、internal、角色过滤条件。四个视图配置已保存，当前查询窗口 2026-08-13 至 09-09，无新版结果；留存草稿仍待新事件可选，未把空报表记为零故障。
- 最终源头审计把 daemon PromptDiagnostics 和 BackgroundExecutionDiagnostics 的未知终态、handled/unobserved 改为 UNKNOWN/INCOMPLETE，避免与 App 端口径相反；补充错上下文、重复终态、真实失败对照用例。
- 现开始一次集中构建与测试：公共预算/计数和 SDK 出口、protocol 兼容、daemon/relay/mobile 回归、Python 工具和部署恢复替身，之后做各平台构建与隔离 Native/Swift 测试。此前 A 的测试不复用为本批证据。
- 这不是 A–D 全体验收：native fatal 写入前隐私限制、平台符号/真实包云端回执、GA4 留存配置与成熟样本、实际七天观察仍保留未完成状态；测试发现的问题随修复追加针对性验证。

### A–D 集中验证结果与剩余范围

- 最终 JVM 回归 3,677 项、0 失败、1 项既有跳过；iOS Native 公共/协议/App 分别 22/276/193 项通过；Cocoa 实际 SDK 出口 5 项通过；Python 工具及 relay 恢复替身 10 项通过。Android debug APK、desktop 编译、iOS Arm64 Kotlin 和 Swift 模拟器测试包构建通过。精确命令范围和日志见 [ACCEPTANCE](ACCEPTANCE.md) 首节，不重复相加计数。
- Cocoa 实验推翻了“后台 start 返回即可采集”的假设：锁定 SDK 会异步在主线程准备 hub。现启动/关闭串行至主线程，私有缓存扫除和预算仍在 worker，SDK 就绪及同一 generation 才开放采集。上述早期“初始化移到工作队列”的描述被本次实测实现取代；SDK 自身初始化可能有内部 I/O，不宣称其全部离开主线程。初始化前撤回和预留期间关闭均已有实际 SDK 用例。
- Cocoa transaction 在 beforeSend 的 Event.context 不包含完整 root trace，改从公开 serialize() 只取 trace/span ID 对照安全记录；最终重建白名单，不转发原始字典。真实 SDK 的 error/log/manual trace 哨兵及 UNKNOWN 排除通过。
- Java 503 出口实验发现日志批次丢失未进入原计数，新增 SDK_LOG_BATCH_LOST；保留事件条数、批次数、字节数各自单位。503、拒绝连接和新建 client 不回放旧内存记录通过；完整 DNS/离线/强杀/重启矩阵仍待验收。
- 修复编译所需 imports、nullable Java overrides、跨模块 smart cast 与公共 coroutine test 依赖；修正旧端 SessionLive 的测试预期为 UNKNOWN。Demo draft 与两处 LAN cleanup 的测试改成受控 scheduler/真实 Job 完成，消除短固定延迟，不改变业务重连或权限逻辑。
- GA4 四个视图保存回读完成；发现桌面 MP 实际未配置，已创建桌面数据流 15754516140 / G-X04707FM0W 并关闭增强型衡量，凭据与真实回执仍待接续。Sentry 看板及现有移动数据流保留，未开告警、通知、长期监控或公开发布。

目前还剩五组：① 实际设备/服务新包和云端证据；② SDK 全故障矩阵、多后端/来源分支与受控旅程验收；③ 原生 fatal 写入前隐私及符号；④ 桌面 GA4 配置/回执与留存视图；⑤ 七天运行和成熟样本。Pandaa 最新不可达，Android 换用 VCE-AL00 待确认。本轮没有新设备安装、daemon 重启或 relay 部署。A–D 整体仍未完成。

桌面 MP 凭据的下一步有明确前置条件：Google 在创建密钥前展示《用户数据收集确认书》，需要账号方确认已具备隐私披露和最终用户授权。已向用户请求确认，未代为接受、未创建密钥；数据流创建与真正开始收集分开记录。

### GA4 确认后接入（2026-09-10）

用户明确“继续上面 GA4，我确认”。已通过 Dia 9222 接受 Google 数据收集确认书并创建桌面本机验证密钥，保存到 gitignored 的 0600 本地配置；不再等待该确认。官方严格校验发现旧 UUID client_id 不符合数字.数字格式，新增确定性无损转换，保持本地安装种子和首次价值标记，26 项 Telemetry 定向回归通过；本机身份的 debug 校验通过。标准脚本已更新并启动新的 desktop 开发包，旧包完整备份，精确哈希见 ACCEPTANCE。

配置 HTTP 探针返回 204，但 DebugView/Realtime 暂无可归属于新桌面流的回执；没有将探针记为真实 App 业务成功。进一步使用隔离本地测试页做 Google tag 与 MP 对照，事件统一使用 observability_validation + staging/internal，不能用来补首次价值、留存或生产结果。当前原生 App UI 控制返回 cgWindowNotFound，进程/包验证不冒充实际可见旅程。

独立推进结果：daemon/relay installDist 构建通过，Pandaa 最新不可达；当前任务是 daemon 后代，故部署安排必须把 detached 更新放到最后。本检查点没有重启 daemon 或部署 relay，也没有重复运行已通过的全量回归。

隔离 tag 对照得到明确网络分类：Dia 拦截官方 `/gtag/js`，错误为 ERR_BLOCKED_BY_CONTENT_BLOCKER；同脚本独立本机 HTTP 下载 200。该证据不能解释桌面原生 MP 的上报结果。此前要求用户解除拦截的确认已撤回，无需用户在 Dia 操作，不再将可选实验作为验收阻塞。未更改拦截设置；本任务临时页已关闭，59527 端口已确认无监听。Google 声明和密钥创建已完成，继续直接核验实际桌面发送链路和后台回执。

15:51Z 续验：新增仅 development/staging 可显式启用的本机 MP 调试开关，固定分类记录发送状态和 HTTP 数字码，避免原有静默网络失败无法定位；不会输出身份、内容、密钥、URL 或异常文本。30 项 Telemetry 定向回归通过，标准脚本已安装并以单个桌面进程启用临时调试。取得真实 App 连续 3 次 HTTP 204，首次补齐实际发送链路的接口回执；DebugView 仍显示 0 调试设备，未声称后台收录/产品视角已验收。用户无需再次授权或操作 Dia。实际包与日志证据见 ACCEPTANCE 最新检查点。

### 2026-09-11 继续验收

GA4 已回读并比对密钥、数据流与安装包配置，排除不一致；唯一数据过滤器为测试状态。隔离官方 tag 与同身份 MP 对照也得到 HTTP 回执，但 DebugView/Realtime 尚无可确认新样本，没有确定缺报根因。无需用户关闭 Dia 拦截。relay 已通过标准新部署脚本更新到实际进程并核验哈希/健康/拒绝握手；观察到真实持久 logs=500 当日额度用完，更新保留限额，新计数能区分 BUDGET_REJECTED 与 SOURCE_SUPPRESSED。详细证据见 ACCEPTANCE。

补充 JVM 生命周期实验发现读超时未触发 SDK item-loss 回调。增加保留原 SDK 行为的提交结果计数，计量 envelope 提交失败，避免冒充日志条数。新增读超时、隔离 DNS、实际 TLS 失败、正常退出、强杀/新进程重启 5 项用例；Java Sentry 模块共 18 项通过，Android 编译和服务分发包构建通过。桌面已安装包含修复的 SDK jar；relay 最终修复包正在标准部署，本机 daemon 保留最后 detached 更新，尚不能将进行中的安装记成完成。

00:27 CST 续记：最终 relay 修复包部署完成，实际 PID 546725，源站/公网健康通过；desktop 实际 PID 15073，安装 SDK 的全部条目内容与最终分发包一致。daemon 备份及独立 postcheck 已准备，最后执行 detached 更新后以日志/新进程/全部 jar 校验为准。GA4、真机和真实观察剩余项保留，不再以临时 Dia 对照实验请求人工确认。

00:55 CST 续记：daemon detached 更新及独立检查完成，PID 16820、单实例、relay 已连接、全部 70 个 jar 一致；Sentry 读到更新后的 8 条实际 EP-08 日志，展开字段与随机连接编号已核实。GA4 实时第二页出现 observability_validation，并核实 source=mp_after_tag_probe，关闭“所有新对照都无后台回执”的旧状态；实际 App 接收仍未证实。发送三组有界身份对照等待比较，没有变更安装身份。relay 恢复替身新增 5 个故障分支，11 项全部通过；不等同于生产 SSH 断连。Pandaa 当前不可达，desktop UI 控制仍失败；完整剩余范围不缩减，下一检查点是 GA4 对照来源回读及实际平台旅程。

00:58 CST 续记：GA4 app_launch=1 的参数逐层核实为 edition=desktop、app_environment=staging、app_version=1.9.8，首次取得本轮实际桌面启动事件回执。“实际 desktop 无任何后台事件”已解除；没有凭该事件确认具体启动进程、收录延迟或核心操作成功。核心结果、正式探索及留存继续独立验收。

01:16 CST 续记：用户改用当前插线手机。USB iPhone 12 已连通开发通道，构建/签名/设备 profile/Pairlet staging 配置核实后安装并启动 1.9.8 (19)，PID 1886 后续仍存在；iOS 不再等待 Pandaa 可达。华为 VCE-AL00 Android 10 满足 minSdk，ADB 已授权，新 APK 构建通过，安装请求停在手机系统安装界面，等待用户完成提示。未清设备数据、未发布 OTA、未重启服务。iOS 云端及业务验收与 Android 实际安装/启动继续独立取证，见 ACCEPTANCE 首节。

01:23 CST 续记：用户明确使用 ADB/Appium 操作。已用 ADB 读取并完成华为两层安装提示，安装后打开 App；实际 UI 出现首次使用说明页，进程 7286 存活，手机 APK 与本次构建 SHA 一致，版本 1.9.8/30。Android 安装不再等待人工处理。两台当前 USB 手机均可继续验收；本次没有创建 Appium 会话，没有把本地启动/计数文件视为云端成功。
