# 诊断可靠性与边界

状态：A–D 集中验证中，2026-09-10。只列实际机制；真实包、云端和 SDK 离线实验分别验收。

## 持久额度

`DiagnosticBudget` 在 SDK worker 交付前同步保存一次预留额度；业务调用仍只入有界队列，文件 I/O 不在业务线程或加密锁内。保存失败不交付本条记录，业务继续。已预留但因关闭/退出/网络失败未送达的额度不退回，因此进程崩溃不能靠重启重新获得已花费额度。

- 以固定 component/environment 分区，不随 release、DSN 或采集开关重置。它是单安装/组件限额，不保证组织全局额度。
- 每日错误最多 App/daemon 10、relay 20；非错误记录最多 500；安全记录序列化总字节 App/daemon 1 MiB、relay 20 MiB；每条最多 16 KiB。该字节数不是 HTTP/最终 SDK envelope 的精确流量，后者还需实测校准。
- 源头和持久额度都按剩余错误条数预留每条 16 KiB，日志不能先花光这部分字节；真实错误使用小于预留的空间时，余量可继续用于日志。相应测试先填满日志可用空间，再验证剩余 10 条最大错误仍能预留。
- 单文件限制 4 KiB，只存 schema、日编号、当日错误/日志/字节计数、累计 admitted/suppressed/dropped；累计计数饱和于 10^12。没有时间线、事件 ID、栈、工具名、项目或身份内容。
- 时间只向前推进日预算；回拨不重置。已损坏、未知格式、非法计数或超大文件被写成当日额度已用完的状态，下一日再恢复；读取/写入失败时拒绝本条诊断。
- JVM/Android 在专用文件锁内重读、写临时文件、force 后原子替换；锁被占用或文件系统不支持原子替换时本条不发送。iOS 单 App 进程用串行锁与 Foundation 原子写。任意手工删除目录、磁盘快照回滚和突然断电的持久性不在此机制保证内。
- JVM 默认 `~/.cc-pocket/diagnostic-budgets/`；Android 必须提供 Context.filesDir 下的 diagnostic-budgets；iOS 使用 App 沙盒 Library/Application Support/cc-pocket/diagnostic-budgets。固定 `<component>-<environment>.json`；关闭不清此类无身份额度计数。

## 计数的含义

持久 admitted 是 SDK 交付前已预留的记录数；持久 suppressed/dropped 是持久预算拒绝的计数。新增 DiagnosticCounters 将 Reporter、队列和 SDK 的有界增量汇总到独立文件；不把这些不同层级相加冒充云端缺失数。定期落盘前被强杀的增量仍可能丢失，完整平台强杀实验待验收。

Java SDK HTTP 失败对日志返回的是不透明 envelope，公开 header 不提供批内日志条数。真实 localhost 503 用例发现原来只处理 error/transaction 会漏计日志批次，现用 `SDK_LOG_BATCH_LOST` 记录批次数，与 `SDK_LOG_LOST` 的条数、`SDK_LOG_BYTES_LOST` 的字节数分开。503、连接拒绝以及新建 client 不回放旧内存记录的用例通过；这不代表所有 DNS/强杀/平台缓存场景已通过。

2026-09-11 增量发现并修复：Java SDK 8.41.0 的读超时未产生原有 item-loss 回调，原计数会漏记。现在在 SDK 原传输外观察公开 `SubmissionResult`，用 `TRANSPORT_FAILED` 记录失败的 envelope 提交数（包括提交被抑制），不称为错误条数、日志条数或已发 HTTP 请求数，不与 SDK item-loss 相加。保留 SDK 的传输、限流、关闭和已有 typed hint，不自建网络发送、不重试或输出 envelope。无法观察已有 typed hint 的情形保持原行为，不能据此假定所有 SDK 路径都有完整丢失计数。

新增 5 项实际 JVM/SDK 用例已通过：本地接收端不回响应触发读超时；独立子 JVM 的 hosts-only resolver 触发 DNS 失败（无外部 DNS 请求）；本地非 TLS 对端触发实际 TLS 握手失败；子 JVM 正常 flush 后退出收到一份安全 envelope；子 JVM 在请求未获确认时被强杀，重启后额度保留、旧内存事件不重放、临时文件无隐私哨兵。最后一种不判断真实远端是否已处理请求。Java Sentry 模块共 18 项通过，Android 编译及 daemon/relay installDist 通过，见 `/tmp/pairlet-abcd-sdk-lifecycle-final.log`。这些不替代 Cocoa/Android 真实设备生命周期和全部缓存矩阵。

源头 Reporter 仍保留原有指纹限频和日限额；二次预算在实际上传入口兜住重启/运行时更换。这些额度不是成功率或用户数，Analytics 结果摘要也不继承诊断采样。

## 队列与关闭

| 出口 | 已实现机制 | 仍需验证的边界 |
|---|---|---|
| JVM Sentry | 16 条自有待发队列；后台预留；关闭先 gate、清队列并关 SDK；预留结束后再次检查 gate | SDK 内部 Errors/Logs 缓冲、实际网络时延、DNS/离线/强杀丢失；已经发送的请求不能撤回 |
| Cocoa Sentry | 16 条队列；预留/私有缓存准备在 worker 且不占配置锁；SDK start/close 串行在主线程，就绪且 generation 匹配才启用；关闭先 gate、取消 URLSession、移除临时 SDK 缓存 | SDK 自身初始化内部 I/O；真实强杀后旧临时目录的清理/上限、退出上传、断网/重启与所有原生 SDK 边界 |
| desktop GA4 | 32 条队列、同时一条请求；关闭/重开丢旧代际并取消在途请求；失败不重试 | 真实 MP 接收及报表能力，已送达不能撤回 |
| Android/iOS Firebase | 关闭 Analytics/Crashlytics，重置 Analytics 数据，删除未发送 Crashlytics 报告 | 真实 SDK 最终请求与缓存、初始化前持久关闭、后台/重开；API 调用不等于整套验收通过 |

持久预算不是离线事件存储，不额外建设 uploader 或无限补传队列。无 DSN、测试隔离或用户关闭仍保持 no-op；单次 staging smoke 是显式独立探针，不代表实际服务有新事件。

2026-09-11 00:08 CST 真实 relay 更新验证：持久预算已为 logs=500、errors=1，更新后保留；新包计数明确记录 BUDGET_REJECTED=15 与 SOURCE_SUPPRESSED=275。Sentry 没有后续日志不能证明服务没有新活动。该日额度按 UTC 日编号 20706 计算，不能用本地午夜、重启或版本变化重置。日志用量和采样是否覆盖全天须在运行观察中评估，当前未提高生产默认额度。完整证据见 ACCEPTANCE 最新节。

## 本批验证项

用例源码：公共 DiagnosticBudgetTest；JVM/Native DiagnosticBudgetFileTest；SentryBudgetTest；desktop TelemetryDeliveryTest、TelemetryMetadataTest。实际运行结果与未通过项在 IMPLEMENTATION 追加。

覆盖重点：重建预算/SDK 后同日额度不重置、时钟回拨、正常跨日、损坏/超大文件、存储失败/竞争锁、实际 SDK 出口受预算控制、文件 I/O 不阻塞调用者、关闭期间晚到预留不进入 SDK、桌面在途取消/队列清空/重开代际及流量标记。

完整 A 验收仍须分别补 SDK 离线/退出/强杀实验、全部健康计数持久性、原生符号与各实际运行包的云端证据。本文件不会把本地通过自动升级为实际包/云端通过。
