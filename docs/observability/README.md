# 诊断开发与本地验证

当前执行入口：[提交收尾清单](CLOSEOUT.md)。主体实现已在 main；本轮修复、测试代码和脱敏验收记录整理待提交。用户于 2026-09-11 明确额外验证暂缓，待上线后观察；本次不再装机或补跑设备/云端验收，正常发行 CI 门禁保留。

已有证据见 [ACCEPTANCE](ACCEPTANCE.md)：Android 生命周期问题已修复并有真机成功回执；iOS 正常使用、断网恢复及采集出口有代表性证据；98 条手机核心/价值事件在 GA4 普通聚合中已核对。整数 analytics_schema 导致筛选遗漏已修复为 v1，5 项测试通过，新包新值尚未云端验收。

[产品分析方案](PRODUCT-INSIGHTS.md) 保留首次价值、核心完成、连续稳定、功能采用/复用、留存/版本五个视角。旧计划和实施过程分别见 [FOLLOW-UP-PLAN](FOLLOW-UP-PLAN.md)、[IMPLEMENTATION](IMPLEMENTATION.md)，不把历史待验状态重新当成当前提交门禁。

GitHub 打包与配置见 [RELEASE](RELEASE.md#github-actions-配置与维护)。Sentry 五个公开 DSN Variables、iOS 符号上传 Secret 及既有 Firebase 配置 Secrets 已核对存在；正式与预览工作流已在 main 接入。Crashlytics 保持原职责，本批不迁移、不新增 fatal 抽验。

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

不采集异常 message/cause、prompt、会话正文、路径、token、密钥、account/device/session 标识、HTTP URL 或原始 stderr。原始栈只保留 `dev.ccpocket` 的安全符号；JVM 另有源码 basename 与行号，iOS 保留 Kotlin 函数名，并在运行时确实提供位置时保留源码 basename/行号；没有源信息的帧仍只到函数。没有匹配栈时保留类型。原生符号完整性仍须真实包验证。

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
