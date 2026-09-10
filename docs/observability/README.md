# 诊断开发与本地验证

后续任务入口：[Pairlet 日志系统后续计划](FOLLOW-UP-PLAN.md)。A–D 主体已集中回归，当前补实际包、云端和平台专项验收；最新进度见 [IMPLEMENTATION](IMPLEMENTATION.md)，当前证据见 [ACCEPTANCE](ACCEPTANCE.md)。

当前执行入口：[夜间收尾计划](NIGHT-PLAN.md)。按用户最新要求，用五组任务做代表性抽样，复用已有证据，发现问题再扩大验证。

2026-09-11：OBS-01–10 / EP-01–30 与五个产品分析视角统一纳入总计划。desktop/daemon/relay 最终开发包已部署；实际新 daemon 的 Sentry 日志和 desktop GA4 staging 启动事件已有云端回执。完整核心结果、真机、符号和观察期仍未整体验收。

产品分析入口：[用户使用与问题分析方案](PRODUCT-INSIGHTS.md)。按首次价值、核心完成、连续稳定、功能采用/复用、留存/版本五个视角组织；Firebase/GA4 负责产品口径，Sentry 负责技术原因。结果埋点已有实现，GA4 四个探索配置已保存；留存配置、业务结果回执和成熟样本仍待验收。

实现依据：[方案](../design/OBSERVABILITY.md)、[路径清单](ERROR-PATHS.md)、[进度与剩余工作](IMPLEMENTATION.md)。跨端诊断上下文、历史完成/应用/布局信号已实现并通过本地回归，真实混合版本旅程继续补验。历史 iOS/relay 及独立 JVM 探针回执不能替代当前包验收；自动 fatal 切换、符号、其余平台证据和完整发布验收仍待完成。

云端使用 [Pairlet](https://pairlet.sentry.io/) 组织（美国区），项目名统一为 `pairlet-ios`、`pairlet-android`、`pairlet-desktop`、`pairlet-daemon`、`pairlet-relay`。已有代码中的命令、配置键及包名仍沿用当前仓库；改名时按用途逐项处理，公开命令可增加兼容入口，现有配置键和内部包名不做全量替换。计划中的设备过渡显示名 `CC Pairlet` 不改变这些 Sentry 组织或项目名称。

## 配置与开启

没有 DSN 时保持 no-op。DSN 是项目公开写入地址，不需要管理 token。Java SDK 8.41.0 和 Cocoa SDK 8.58.2 均已固定版本；当前只发送显式构造的结构化记录。

| 组件 | DSN 配置 | 采集开关 |
|---|---|---|
| Android | Java classpath `cc-pocket-sentry.properties` 的 `dsn.android` | App 原有持久遥测设置，初始化前读取 |
| Desktop | `CCPOCKET_SENTRY_DSN_DESKTOP`，或同名资源的 `dsn.desktop` | App 原有持久遥测设置 |
| iOS | Xcode 构建设置 `CCPOCKET_SENTRY_DSN_IOS` 写入 Info.plist；调试可用同名启动环境变量 | App 持久设置同步到 Swift Sentry 与 Firebase |
| daemon | `CCPOCKET_SENTRY_DSN_DAEMON`，或同名资源的 `dsn.daemon` | 独立配置，默认关闭；`cc-pocket-daemon diagnostics --sharing on` 开启，`off` 关闭 |
| relay | `CCPOCKET_SENTRY_DSN_RELAY`，或同名资源的 `dsn.relay` | `CCPOCKET_DIAGNOSTICS_ENABLED=true` 才启用 |

Java 环境通过 `CCPOCKET_SENTRY_ENVIRONMENT=development|staging|production` 或 properties 的 `environment` 指定；iOS 用同名 Xcode 构建设置/启动环境变量。未指定或无效值按 development 处理。relay 另用 `CCPOCKET_RELAY_VERSION` 提供构建版本，否则 release 为 dev。

资源示例（必须把占位符替换为对应组件项目 DSN）：

```properties
environment=staging
dsn.android=REPLACE_WITH_PUBLIC_DSN
dsn.desktop=REPLACE_WITH_PUBLIC_DSN
dsn.daemon=REPLACE_WITH_PUBLIC_DSN
dsn.relay=REPLACE_WITH_PUBLIC_DSN
```

将资源放在实际消费模块的资源目录，如 `daemon/src/main/resources/`、`relay/src/main/resources/`、`mobile/composeApp/src/androidMain/resources/`、`mobile/composeApp/src/desktopMain/resources/`。默认仓库不携带工作项目的配置。launchd/systemd 服务不会自动继承交互 shell 的环境；应使用服务环境或构建资源，再用项目规定的更新脚本安装。**不要为验证另起 daemon。**

daemon 开关只写 `~/.cc-pocket/diagnostics.properties`，当前运行进程每秒检查一次；关闭不读取配对身份。开启开关不会代替 DSN 配置。CLI 的配置状态只检查当前命令环境，不能代替后台 daemon 的真实状态检查。

## 当前字段与证据边界

Errors 和 Logs 都有 `diag_schema`、`diag_event_id`、`error_path`、`operation`、`stage`、`code`、`component`、`coverage`。有操作对象时还带 `diag_trace_id`、`outcome`、耗时、重试次数；错误上下文另外保留该操作自己的最多 32 个阶段。各平台指标统一使用 `result_quality`、`returned_count` 等 snake_case 字段名。

- `coverage=client_only` 当前表示**仅当前组件的观测**，daemon/relay 也使用该值；不表示可穿透 E2E 关联，也不表示已形成 Sentry 性能 spans。
- 当前 `SessionLive` 结束的是打开确认阶段；`result_quality=unknown`，不能据此判断历史完整/界面已渲染。此前的连接超时与打开确认超时分别标记 CONNECT/ATTACH。
- 未知 Frame 类型作为 UNSUPPORTED 日志，已知消息结构损坏作为 DECODE_FAILED 错误；两者都不上传原始 JSON。
- Prompt 迟迟没有回执记为 `stage=ack`；已收到回执但没有 turn 输出记为 `stage=execute`。已知正在排队的等待不报超时错误，两者都沿用业务现有截止时间，不改变重发规则。
- 历史 JSONL 最后一行未写完作为 INCOMPLETE 日志；更早的损坏行、读失败以及部分扫描失败保留错误和 PARTIAL 计数。
- 成功操作按诊断编号进行 1% 采样，失败与恢复独立于成功采样；同路径/阶段/码及安全异常类型/首帧指纹 6 小时最多两个 handled error，按进程还设日上限。`submitted` 是进入自有队列，不是后台收到；SDK 限流/离线/停机仍可能使其丢失。

不采集异常 message/cause、prompt、会话正文、路径、token、密钥、account/device/session 标识、HTTP URL 或原始 stderr。原始栈只保留 `dev.ccpocket` 的安全符号；JVM 另有源码 basename 与行号，iOS 当前只保留 Kotlin 函数名。没有匹配栈时保留类型。原生符号完整性仍须真实包验证。

## 验证命令

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 bash scripts/check-all.sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :mobile:composeApp:compileDebugKotlinAndroid
JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild \
  -project iosApp/iosApp.xcodeproj -scheme DiagnosticTests \
  -destination 'platform=iOS Simulator,id=SIMULATOR_UDID' \
  CODE_SIGNING_ALLOWED=NO test
```

Gradle 测试统一设置 `ccpocket.test=true`，阻止自动 Sentry Runtime 与 desktop GA4 上传；iOS DiagnosticTests scheme 设置 `CCPOCKET_TEST_MODE=1`，跳过宿主的真实 Firebase/Sentry 初始化。SDK 测试只使用显式 loopback/URLProtocol 替身。

`SentryDiagnosticSinkTest` 检查真实 Java SDK envelope、429、有限队列与关闭；`DiagnosticPathsTest` 检查真实 wire 解码和历史部分读取分类；`SessionOpenTimeoutTest` 检查重试/打开确认的实际诊断。iOS `PocketDiagnosticsTests` 使用 URLProtocol 捕获 Cocoa SDK 的实际上传请求，主动污染 SDK scope 来验证最后的字段过滤，不访问 Sentry 云端。

云端已验证 desktop/daemon/relay 的合成 Errors/Logs 查询，以及实际 iOS staging、relay production 日志，回执见 [Pairlet](PAIRLET.md)。仍需验证 Android 真机、iOS 错误栈、实际 desktop/daemon 新事件、符号、关闭/离线、时延与配额。测试里的 `.invalid` 和 loopback 只供测试替身使用，生产 DSN 校验要求 HTTPS。

## 一次性云端测试

显式运行下面的命令，会向 `CCPOCKET_SENTRY_DSN_DESKTOP` 指定的项目发送一条合成错误和一条日志。环境固定为 staging，`code=smoke_test`、`error_path=EP-30`、`release=pairlet-diagnostic-smoke@1`；不启动 daemon、不读取用户会话、不修改持久采集开关。

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :observability-sentry:smoke -PdiagnosticComponent=desktop
```

`diagnosticComponent` 也可为 daemon/relay，对应读取同名组件的环境配置。该命令验证 JVM SDK 到指定项目的上传链路，不代表常驻服务、iOS 或 Android 设备已接入。它输出两个诊断编号；必须在后台确认错误和日志都已收到，不能把本地排队成功视为云端回执。

探针最多调用五次有界 flush，使批量日志有时间发送；生产退出与离线丢失边界仍待独立验收。仅排查这个合成探针时可设置 `CCPOCKET_SMOKE_DEBUG=1`，SDK 调试输出写到本地终端，不开启应用日志转发。后台 Logs 如停在未上报项目的安装引导，先选择具体的已验证项目。

本机各组件的 staging properties 以及 `iosApp/Sentry.local.xcconfig` 已准备，均由 Git 忽略。iOS 构建时使用 `-xcconfig iosApp/Sentry.local.xcconfig`；使用前述 DiagnosticTests scheme 仍会隔离真实云端上传。
