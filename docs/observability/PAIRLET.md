# Pairlet 云端配置及回执

验证日期：2026-09-09 至 2026-09-10（America/Los_Angeles）。本页分别记录合成探针、实际部署与云端回执；已部署 Pandaa iPhone、本机桌面端/daemon 和线上 relay，不代表完整方案验收或全平台正式发版。

## 组织与项目

- 组织：[Pairlet](https://pairlet.sentry.io/)，slug `pairlet`，美国区。用户已明确同意条款、创建组织及删除旧组织。
- 当前方案为 Developer；后台未配置付款方式，未启用 Business 试用。
- 旧 `ibelove` 已提交删除，访问旧组织设置页显示 **Deletion Scheduled**。Sentry 尚在等待后台清理，不能表述为全部数据已清除。

| 项目 | 项目 ID | 当前证据 |
|---|---|---|
| pairlet-ios | 4512060701343744 | Pandaa USB 安装/启动通过；实际 iOS staging EP-03 日志已收到 |
| pairlet-android | 4512060708749312 | DSN 资源与编译通过；真实设备上传待验收 |
| pairlet-desktop | 4512060699049984 | 独立 JVM 探针 Errors/Logs 已收到；实际 App 已更新并打开已有会话 |
| pairlet-daemon | 4512060686139392 | 独立 JVM 探针 Errors/Logs 已收到；常驻 daemon 已更新、独立采集开关开启，真实进程新事件尚待确认 |
| pairlet-relay | 4512060691185664 | 线上服务已部署；实际 production EP-08 日志已收到 |

DSN 放在各组件被 Git 忽略的 `cc-pocket-sentry.properties` 与 `iosApp/Sentry.local.xcconfig`。项目当前命令、包名及配置键保留兼容；Sentry 名称统一为 Pairlet。

## 已验证的合成云端样本

所有样本都为 `environment=staging`、`error_path=EP-30`、`code=smoke_test`、`release=pairlet-diagnostic-smoke@1`；没有业务会话内容。错误具有原始 `SentrySmoke.kt` 捕获栈；日志通过行展开核对 `diag_event_id`。

| 组件 | 错误 | 错误 diag_event_id | 日志 diag_event_id |
|---|---|---|---|
| desktop | [PAIRLET-DESKTOP-1](https://pairlet.sentry.io/issues/7723451583/) | 738698da8baa4c46bb3cefe842c29c3d | 76ff576b2c3748818d158580f7c721c1 |
| daemon | [PAIRLET-DAEMON-1](https://pairlet.sentry.io/issues/7723452838/) | 7138f9c534ee4f5dbbdfabfd9dd2efe2 | 72ba1743a77e4eaba996f46f878fd6b0 |
| relay | [PAIRLET-RELAY-1](https://pairlet.sentry.io/issues/7723452955/) | cd4962e2cc7d48c3807b5447ea1406fa | 7d0d29fcac1e4a82b227a53f878be4e4 |

查看 [Issues](https://pairlet.sentry.io/issues/) 或 [desktop Logs](https://pairlet.sentry.io/explore/logs/?project=4512060699049984)。Logs 可切换对应项目，以 `diag_event_id` 搜索；新组织选择 All Projects 时曾显示未上报 Android 项目的安装引导，选择已验证的单个项目后可以查询。

第一轮只有错误得到后台确认；延长短命探针的 flush 等待后，错误与日志均已逐项查到。不能用这个结果推断生产应用退出或断网时不会丢日志。

`coverage=client_only` 表示当前组件观测。后台自动生成的 Trace ID/Trace Preview 不等于已实现跨端 spans；三条独立探针也不是一次端到端业务操作。

## 2026-09-10 实际部署与回执

用户授权实际手机、daemon/relay 部署，并明确允许中断当时的 Kimi 会话以更新 daemon。以下是本轮运行快照，PID 不能作为后续运行状态的永久依据。

- **桌面端**：`scripts/update-local-desktop.sh` 成功；安装至 `/Applications/CC Pocket.app`，版本 `1.9.8`，PID `15647`。实际安装 jar 为 `composeApp-desktop-0.0.1-SNAPSHOT-2b29af58961cefdeeddd8b8e8441fc5.jar`，其中 DSN 指向 pairlet-desktop，环境 staging。UI 已连接 MacBook-Bro，刷新列表并打开已有「daemon 怎么更新版本」会话，历史内容可见；未发送新提示词。此正常打开验证不能替代实际 App 的新错误上传回执。
- **本机 daemon**：确认当前会话不在 daemon 进程谱系内，且重启前存活子会话属于用户批准的集合，再执行 `FORCE=1 bash scripts/update-local-daemon.sh`。更新成功，PID `21365`，单实例、8799 监听和 relay socket 均通过。实际安装 jar SHA-256 为 `c5eeb4ad5029cbc46377a838601b1e8e0cc76c9aaa8f10e39ef2e63c7ac86181`，DSN 指向 pairlet-daemon，环境 staging。通过短命 `diagnostics --sharing on` 配置命令开启独立采集；确认 `enabled=true` 与运行中的偏好监听线程。它不启动第二个 daemon。Sentry Logs 当前仍只见此前 EP-30 合成样本，尚不能声称常驻进程的新错误/日志已收到。
- **线上 relay**：`scripts/redeploy-relay.sh` 成功，systemd PID `539563`，启动时间 `2026-09-10 07:12:04 UTC`；源站及公网健康检查通过。独立 drop-in `50-sentry-observability.conf` 配置采集开启、环境 production、版本 `1.9.8-sentry-dev-6ca60173`，并核对实际 `/proc/$pid/environ`。单次 WebSocket 客户端向 `/v1/device` 发送 `{}`，实际收到 `1008 / expected_hello`，没有启动测试 daemon。
- **Pandaa iPhone**：补齐共享 `iosApp.xcscheme`，解决新增 DiagnosticTests 方案后主方案未被发现的问题。使用本机 Sentry xcconfig 构建 generic iOS Debug 包成功，包版本 `1.9.8`、`com.panda.ccpocket`，DSN 指向 pairlet-ios、环境 staging；`codesign --verify --deep --strict` 通过。产物时间为 `2026-09-10 00:13:23 PDT`。解锁后 CoreDevice、Xcode、ios-deploy 仍显示设备不可连接；安装返回 CoreDevice 1011，配对连接尝试返回 4000。尚未安装、拉起或验证真机云端回执；等待手机 Wi-Fi IP 以继续定位无线通道。

实际 relay 拒绝日志已在 [Sentry 后台](https://pairlet.sentry.io/explore/logs/trace/16ae3b48f996498cb50433a12d75206f/?project=4512060691185664&source=logs&timestamp=1789024584) 展开核对：

| 字段 | 实际值 |
|---|---|
| timestamp | 2026-09-10 07:16:24.720 UTC |
| diag_event_id | `16ae3b48f996498cb50433a12d75206f` |
| component / environment | `relay` / `production` |
| release | `cc-pocket-relay@1.9.8-sentry-dev-6ca60173` |
| error_path / code / stage | `EP-08` / `unsupported` / `connect` |
| severity / operation | `info` / `relay` |
| suppressed_count | `136`（采集限频计数，不是受影响用户数） |

同一列表也收到实际服务的 `connection_closed`、`superseded`、`rate_limited` 日志。它们说明运行路径已被观测，不能仅凭类别推断某用户打不开会话的根因。展开样本未见用户身份、IP、业务消息或握手材料。

回滚材料：本机旧 daemon 分发包及 plist 在 `~/Library/Application Support/cc-pocket/deploy-backups/pairlet-observability-20260910/`；线上旧分发包、systemd unit、Caddyfile 在 `/opt/cc-pocket-relay-backups/pairlet-observability-20260910/`。本次额外创建的 Sentry systemd drop-in 须在完整回滚时一并处理。

**00:29 PDT 无线安装续查**：用户再次表示手机已准备好。通过 mDNS 找到 `Pandaa.local` 的局域网地址，62078 端口可达；因此无需继续等待手机 IP。开发域名 `Pandaa.coredevice.local` 返回代理虚拟地址，这是一条网络线索，尚未证实为根因。直接安装仍返回 CoreDevice 1011，使用局域网主机名连接也未被 CoreDevice 识别；刷新 Mac 的 CoreDeviceService 后，Xcode 仍显示 Disconnected。未更改配对记录或代理设置。下一步需要设备的开发通道恢复可达；可用 USB 连接一次，让 Xcode 重新识别，再继续安装。

## 2026-09-10 00:34 PDT Pandaa USB 安装与真实日志

用户插线后，CoreDevice 状态变为 `connected`。执行 `PANDAA_NO_FIR=1 XCODE_XCCONFIG_FILE="$PWD/iosApp/Sentry.local.xcconfig" JAVA_HOME=/opt/homebrew/opt/openjdk@17 bash scripts/install-pandaa.sh 24645275-94DF-5933-A570-22A9C5FE9AF9`，构建、新鲜度校验、USB 安装与启动全部成功；没有发布 OTA。

- 安装时间 `00:34:31 PDT`，启动时间 `00:34:37 PDT`。设备侧 apps 查询确认 `com.panda.ccpocket`、版本 `1.9.8`、build `19`；稍后进程查询确认已安装目录中的 App 进程 PID `9234` 仍存在。
- 新构建二进制时间 `00:34:30 PDT`、SHA-256 `3a118bcb3ad6b2bd8a0bbdcdba5d2d53e88c51ba8952e91f84329a7067fec7a5`；签名校验通过，Info.plist 中的 DSN 指向 pairlet-ios，环境 staging。
- 启动后 Sentry iOS Logs 首次查询返回 5 条真实日志：2 条 `EP-03:fallback_used`、3 条 `EP-03:connection_closed`。这不是合成探针，也不代表 5 次独立用户故障。
- 已展开核对 [真机连接回退日志](https://pairlet.sentry.io/explore/logs/trace/538ebc26316842d8b3318a06a97d307f/?project=4512060701343744&source=logs&timestamp=1789025678)：`diag_event_id=538ebc26316842d8b3318a06a97d307f`，时间 `07:34:38.786 UTC`，`component=ios`、`environment=staging`、`release=cc-pocket-ios@1.9.8`、`stage=connect`、`transport=direct`、`exception_type=DarwinHttpRequestException`、`code=fallback_used`、`suppressed_count=0`、`user_agent.original=sentry.cocoa/8.58.2`。
- 样本包含固定分类与版本信息，未见用户身份、IP、正文或握手材料。它说明直连失败后的回退路径已上报；不能仅凭这条日志判定最终会话打开失败。手机错误栈符号化及全部业务路径仍待验证。

手机安装阻塞已解除；此前无线失败记录作为历史证据保留，不能继续当作当前安装状态。

## 云端隐私设置

组织级已启用 Require Data Scrubber、Require Using Default Scrubbers、Prevent Storing of IP Addresses。新增规则：

```text
[Remove] [Anything] from [user]
[Remove] [Anything] from [$user.geo.**]
```

仅设置 SDK `sendDefaultPii=false` 或禁止 IP 存储仍可能出现 Sentry 接收端推断的国家信息。这是本轮实际发现，精确地理字段规则依据 [Sentry 上游问题说明](https://github.com/getsentry/sentry/issues/92201) 配置。

规则后的 relay 样本 `cd4962e2cc7d48c3807b5447ea1406fa` 已在后台确认没有 User/Geography 上下文，诊断字段和栈仍完整。规则只作用于新事件，最初几条合成 staging 事件仍可能保留国家信息；没有上传真实业务内容。

## 后续验收

Android 真机安装与上传、iOS 错误栈及符号、实际 desktop/daemon 新事件、关闭/离线、预算持久化、未接入核心路径及正式发布，继续按 [实施进度](IMPLEMENTATION.md) 和 [错误路径](ERROR-PATHS.md) 推进。
