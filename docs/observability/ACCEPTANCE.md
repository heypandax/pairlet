# Pairlet 观测体系验收记录

本页记录当前证据，任务范围和完成标准以 [FOLLOW-UP-PLAN](FOLLOW-UP-PLAN.md) 为准。首批历史云端回执见 [PAIRLET](PAIRLET.md)，不能替代后续改动的验收。

## 2026-09-11：main 合并后的继续收尾

基线是已合并的 main `08f01dad`（PR #369），本轮工作分支 `codex/observability-followup`。保留原工作区 `deploy/Caddyfile` 的未提交改动。本节新增证据不代替下方历史验收，也不表示 A–D 全部通过。

### 发行配置

已增加构建前配置注入、构建后 APK/jar/App Info.plist 验证，以及 iOS archive/dSYM UUID 核对和 Sentry 处理成功后再上传商店的门禁。五个组件的公共 DSN 已写入 GitHub 仓库变量；`Pairlet GitHub release symbols` 的 `org:ci` token 已保存为 Actions secret，Sentry CLI 实际认证通过。没有将令牌或 GA4 MP 私钥写入代码/应用。

- Python 14 项相关用例通过，其中新增 6 项覆盖错 DSN、错误环境/组件、重复资源、私有 GA4 配置混入和 iOS plist 对照，4 项覆盖空占位库、有代码/混合架构及未知格式的符号门禁。
- 提交前脚本全集 29 项通过（16.8 秒，含既有 relay 恢复替身）；日志 `/tmp/pairlet-followup-all-script-tests.log`。未重复运行无运行时代码变化的全部 Kotlin 测试。
- `:daemon:jar :relay:jar :mobile:composeApp:desktopJar :mobile:composeApp:assembleDebug` 成功；四个实际产物的 Sentry 配置回读符合各组件/staging。首次命令缺 `ANDROID_HOME` 在依赖解析阶段失败，补齐已知 SDK 后成功。
- `actionlint 1.7.12` 对两个变更工作流通过；仅忽略已有 self-hosted `harmony` 标签不在其内置标签表的提示。品牌兼容检查、`git diff --check` 通过。
- iOS 未签名 Release archive 成功（2.0.0/19、staging、运行代码基于 `08f01dad`），实际 Info.plist 的组件 DSN/环境回读通过。App 与 dSYM 的 arm64 UUID 同为 `FA554F1E-DF0D-3316-9B7D-C5E406F1569D`；App SHA-256 `ac6ea7e61584398d70dd88981c76b83d1d0acd8e564782b3e7189e34b01be268`，DWARF SHA-256 `b020b513c9554251de623d0a10c5424d59bb989073f67d7a8ec3920349a028df`。
- Sentry CLI `--wait` 返回 processing complete / OK；后台 Debug Files API 独立回读到上述 UUID、`cc-pocket/arm64`、file id `1198351926`，创建于 `2026-09-11T00:40:25.053599Z`。上传只包含本次 App dSYM，没有源码 bundle。证据 `/tmp/pairlet-followup-symbol-upload.log`、`/tmp/pairlet-followup-ios-artifact.json`、`/tmp/pairlet-followup-ios-archive.log`。**这证明符号已入库，不代表真实 native crash 已符号化。**
- 修复两个发行缺口：`project.yml` 补齐与本地工程一致的 Sentry 8.58.2，并显式生成共享归档 scheme；隔离目录的 XcodeGen 实际输出已核对。符号检查识别 Xcode 的无代码 framework 占位库（仅空 `__text`、无符号表、部署标记 100.0，所有架构均须满足），不按 SDK 名称豁免；本次五个嵌入库都由静态库生成占位文件，代码符号归入 App dSYM。有代码的动态库仍要求匹配 dSYM。
- 未上传 App Store、未发公开版本、未替换实际运行的 daemon/relay。归档来自已提交 Xcode 工程；重新生成工程已核对依赖和 scheme，但未另跑完整签名发行流水线。

### 两种超时的新增定位证据

按发生时间关联本机 daemon 日志（旧日志没有 trace id，因此是时间窗对应，不能当作跨端 trace 的严格关联）：

- layout 样本对应新建 Codex：01:35:45.863 收到 OpenSession，01:35:45.885 已发送早期/seeded live，01:35:46.683 收到手机 HistoryApplied，随后 01:35:46.757 收到首条提示。支持“空新会话已经完成历史应用阶段”，没有历史过大的证据；尚未证明是 UI 布局延迟还是回执漏记。
- attach 样本对应既有 Claude：02:06:04.214 首次 hot reattach，并应用调用方模式；02:06:11.913 收到重试；02:06:34.072 才收到 HistoryApplied。daemon 同时继续处理其他设备请求，不能据此归为服务完全卡死；具体停在模式广播、传输或客户端接收哪个环节仍未知。
- 本轮 Android 1.9.8/30 对照：08:29:42.855 新建空 Claude，08:29:43.594 收到 HistoryApplied；保持空页面超过 30 秒后离开。08:30:25.711 再打开原 Claude 历史，08:30:26.664 收到 HistoryApplied。这次走 cold resume，不能替代原 hot reattach 的复现。
- 08:30:41 后台回读两条原问题均仍为 1 次、unresolved；本轮对照没有复现原故障，但没有产品代码修复，不关闭问题。

### GA4 与原生栈

手机在非 Firebase Debug 模式（`debug.firebase.analytics.app=.none.`）又完成一次已有会话与 Markdown 文件查看。当前 GA4 实时出现 `connection_recovery_result=3`，参数表仍无数据，不能认定属于这台手机或恢复成功。`file_view_result` / `value_reached` 的手机后台回执尚未补齐；留存选择器在包含当天的范围仍无 `first_value_observed` / `value_reached`，第五视角继续待配置。临时日期已恢复为原“过去 28 天”（8 月 13 日至 9 月 9 日）；四个已保存视角和正式过滤条件未改。

**Android 真实源码位置已验到**：[PAIRLET-ANDROID-5](https://pairlet.sentry.io/issues/7725205901/?project=4512060708749312) 的最新事件 `91bd45eb2b4b4147beb17ee36df8feda`，发生于 2026-09-10 23:36:54.726Z，`cc-pocket-android@1.9.8`、staging/connect。后台 frame 是 `PocketRepository$startConnectWatchdog$1.invokeSuspend`、`PocketRepository.kt:2486`，对照实际安装版本源码正是 `onTransportDown(ConnectWedgedException())`。这是应用真实连接错误，非独立 smoke；仅确认 handled 栈链路，不代表原生 fatal 迁移或网络根因解决。

iOS 原事件 `32ba3a2eb1e040919bc92775dd9206d7` API 回读确认有 `PocketRepository.startConnectWatchdog` / `ConnectWedgedException` 函数，但 filename/lineNo 均为空。目前 K/N 安全栈仅保留函数，不包含用于地址符号化的 native frame/image 元数据；**上传 dSYM 不能自动补齐这些历史事件的行号**。自动 fatal 与安全 native 元数据方案仍独立保留待办。

## 2026-09-11：夜间代表性抽样

按 [NIGHT-PLAN](NIGHT-PLAN.md) 收缩执行粒度，继续使用已安装的 Android 1.9.8/30、iPhone 12 1.9.8/19 与实际 daemon。下面是新增证据；没有把抽样替代完整发布验收。

| 样本 | 实际结果 | 验证边界 |
|---|---|---|
| Android 配对与两种 Agent | 华为 VCE-AL00 通过界面输入配对码后项目在线；Codex 返回 `PAIRLET_NIGHT_OK`，界面完成 12 秒；Claude 返回 `PAIRLET_CLAUDE_OK`，完成 8 秒 | 两次提示均要求不读写文件、不调用工具；不是全部后端覆盖或上传时延 |
| Android Sentry | 新包 EP-08 日志和两条操作异常已收到，见下方编号 | 异常回执成功不代表异常原因已解决；显式操作超时本身不附 Throwable 栈 |
| iPhone 12 | Xcode 启动 WDA 成功；实际配对、项目在线，并打开 Android 创建的 Codex 会话，完整显示其提示与结果；新版 EP-08 日志已收到 | 原 Appium 预装 WDA 路径因 RemoteXPC 隧道失败；改用当前个人开发团队签名的 WDA，经现有 Xcode 设备通道完成，不再需要用户解锁或管理员密码 |
| 两端采集开关与重启 | 均验证关闭、终止 App 后重启、设置仍关闭、诊断预算没有新增，随后恢复开启 | iOS 预算保持 `logs=1 / bytes=450 / admitted=1`；Android 原预算也未重置。没有全链路抓包，不扩展为所有缓存和在途 HTTP 验收 |
| Android 离线与恢复 | Wi-Fi/数据暂关后显示“连接已断开，正在重连”；恢复原网络设置后提示消失，测试会话内容保留 | 仅影响该测试手机；无 daemon/relay 重启。证据 `/tmp/pairlet-night-android-offline.json` |
| Android 文件查看 | 真实打开 `examples/feishu-bridge/README.md` 并看到 Markdown 正文；Firebase SDK 上传记录包含 `file_view_result: success / complete / 4549ms` 和 `value_reached: file_view` | 字段为 staging、android、internal=1；文件结果带 `_dbg=1`，属于调试验收样本。GMS 上传 204 不等于 GA4 后台回执 |
| Android 审批应用 | Claude 请求 Write 指定临时文件；手机实际显示请求并点击“允许一次”，文件为预期 19 字节，界面随后显示完成 | 校验内容与 SHA-256 后仅删除该测试文件；没有会话级/永久授权。证据 `/tmp/pairlet-night-android-approval-{request,complete}.*`、`approval-file-result.json`；审批产品结果的后台回执尚未核实 |
| 已有较长会话 | 当前 Codex 长会话打开后显示历史与实时输出，界面上下文约 49k；另一既有 Claude 会话起初空白、稍后显示已有内容 | 没有向这两条既有会话发送消息。49k 是界面上下文估计，不是历史帧大小；另一会话产生下述 12 秒 attach 超时，不能标作无异常通过 |
| GA4 桌面结果 | 实时报告曾显示 `connection_recovery_result=2`，逐层回读 `app_platform=desktop`、`app_environment=staging` | 未确认这两条的 result/reason，不推断全部恢复成功；实时窗滚动后参数不可查，不反复制造探针 |
| GA4 留存 | 保存的五个标签仍存在；当前选择器没有 `first_value_observed` / `value_reached`，第五个继续标待配置 | 草稿仍为 first-touch/any-event/每周，不能用其已有旧数据冒充有效使用留存；正式过滤条件未被清除 |
| 布局回归 | 新增三条 desktop Compose 用例全部通过：视口不变时收到历史、隐藏视口延迟布局、实际 ChatScreen 新会话空历史完成 | `/tmp/pairlet-night-layout-tests.log`，XML 3/0 失败/0 跳过；不能代替 Android 上那条超时的根因验证，没有产品代码修复 |

### 可以直接查到的新记录

- Android 日志：`diag_event_id=ce016d96c1dc45299d988b77a9c11012`，17:32:14.138Z，EP-08/ok，release=1.9.8，stage=connect，transport=relay。本端连接 `23906f28801943c8bba8100f0bf98f0b`、对端 `d4e8c949beb04a63be8b59bbe5c4f3e2`。
- [Android 布局超时](https://pairlet.sentry.io/issues/7724619605/?project=4512060708749312)：事件 `2fdb22134f3e4d96a5f5ca0cacf017ec`，`diag_trace_id=d0ce472f8eff40468cdaefa106b024d2`，EP-10、layout、15021ms；步骤到 apply=889ms、后续 attach=2199ms。期间实际 Codex 任务最终完成，尚未确定是早期可见性延迟还是诊断误报。后续 Claude/恢复样本未见第二条同类事件；**此问题仍待复现和定位，未关闭**。
- [Android 连接异常](https://pairlet.sentry.io/issues/7724622744/?project=4512060708749312)：事件 `8309d4cedd914cc39a7354245cd04eb0`，EP-03/unavailable、connect、2084ms，随后实际连接恢复；不因这一次恢复就推断全部异常可自愈。
- [Android 已有会话 attach 超时](https://pairlet.sentry.io/issues/7724675302/?project=4512060708749312)：事件 `86f4fe7b9efc4a46aec25fcfa75365fc`，trace `5a3e5390353446b9ba4b9c31b34d5f74`，EP-10/attach、12012ms、attempt=1。该检查窗内已有 Claude 会话先空白后显示；超时已捕获，耗时根因尚未定位。它与先前 layout 超时是两个阶段，不合并成同一问题。
- iOS 新版日志：`diag_event_id=0557d66e911a4695a137e5f5a7cfeec6`，17:54:30.630Z，EP-08/ok，component=ios、staging、release=1.9.8、Cocoa 8.58.2；本端连接 `684c7730798148d580e99dedb1526ade`，对端与上述 daemon 连接一致。采用此次配对时间窗、最终包版本和连接关系核对；同页更早的旧 iOS EP-03 日志不归到这台新设备。
- [iOS 安全栈抽查](https://pairlet.sentry.io/issues/7724537144/?project=4512060701343744)：既有事件 `32ba3a2eb1e040919bc92775dd9206d7` 展示 `ConnectWedgedException` 及 `PocketRepository.startConnectWatchdog` 的 Kotlin 函数，但文件/行号是 `<unknown>`。只能确认函数级安全栈可查，**源码行号/原生符号未通过**；时间早于本次 iPhone 12 配对，不归为本机本轮注入的错误。

主要证据在 `/tmp/pairlet-night-android-{codex-complete,claude-complete,sentry-log,layout-timeout,connection-error,consent,restart,offline,file-view}.*`、`/tmp/pairlet-night-ios-{codex-history,sentry-log,consent}.*`、`/tmp/pairlet-night-ga4-desktop-recovery-{platform,environment}.json`、`/tmp/pairlet-night-ga4-cohort-available-events.json`。临时文件可能被系统清理，云端编号和结论在此保留。Firebase 上传记录只提取固定产品字段到 `/tmp/pairlet-night-android-firebase-delivery.json`，原始本机调试日志权限 0600，不上传到诊断平台。

发布检查仍发现发行流程没有完成 Sentry/GA4 配置注入与原生符号验收；本机 Debug 配置不是发行配置。Cocoa fatal 写入前隐私限制、完整平台矩阵、真实 SSH 断连演练、足量 P95、真实七天和成熟 D1/D7 继续保留未完成。没有提高 relay 当日已耗尽的日志额度，没有公开发版或新增付费能力。

Firebase 专项最后可证实阶段：GMS bundle 的应用 ID 与本包 Firebase 配置相符，实际文件结果的固定维度完整，看到 SDK 上传 204；DebugView 在约 02:02 CST 仍为 0 调试设备。依据 [Firebase 官方调试步骤](https://firebase.google.com/docs/analytics/debugview)，临时启用指定 Android 包的调试模式，结束时以 `.none.` 关闭，并清除本次临时 FA 日志级别。调试事件不能补正式产品统计，尚未据此声称手机结果云端收到。

02:09 CST 收尾回读：Realtime 事件列表两页仍未出现本批手机的新核心结果；不再发送额外配置探针。已结束本次 WDA 会话和 Xcode runner，卸载仅本次安装的 `com.panda.pairlet.night.wda.xctrunner` 并重新拉起 CC Pocket；原有 Appium 4725 未接管。Android 网络、采集开关已恢复，Firebase 调试关闭；iOS 采集开关已恢复。daemon 独立核验仍为单实例 PID 16820。恢复证据 `/tmp/pairlet-night-cleanup.json`。本批没有新增产品运行代码、daemon/relay 部署或对外发版；新增三条回归通过，`git diff --check` 通过。

## 2026-09-11 01:23 CST：ADB 完成 Android 安装与界面核验

用户明确可使用 ADB/Appium 操作设备。通过 ADB 的实际 UI 层级读取华为系统安装提示，依次点击两层“继续安装”；随后界面显示“安装成功”，点击“打开”。未修改系统安装安全设置，未重装或清除其他应用。

- 华为 VCE-AL00 上 `com.panda.ccpocket` 已安装，版本 **1.9.8 / versionCode 30**，进程 **7286**，前台为 MainActivity。手机 base.apk SHA-256 `9e698479ffd06bcc69be30bfda46dc4c9c850794dd2c3eafb7e434489ca5f31e` 与本机新构建一致。
- 初始截图为过渡空白，第一次 UI dump 返回 null root；随后重新读取，实际出现“开始之前”、加密/数据去向说明、“同意并继续”的首次使用页面。以此次可见界面和后续仍存活的进程确认启动，未把最初的 PID 或空白截图单独作为启动通过。
- 该进程的 AndroidRuntime 检查未发现 FATAL EXCEPTION；这里只覆盖本次启动，不代表全部业务稳定。沙盒诊断计数文件已建立，当前为空；没有将文件存在或 APK 中 DSN 配置视为 Sentry 云端回执。
- 证据：`/tmp/pairlet-android-install-artifact.json`（已更新成功状态）、`/tmp/pairlet-android-app-window.xml`、`/tmp/pairlet-android-installed-onboarding.png`、`/tmp/pairlet-android-launch-runtime.log`。Appium 4725 的 status 返回 ready，但本次实际操作使用 ADB，没有创建或接管 Appium 会话。

下节 Android“等待手机安装提示”已解除，无需用户手动完成该步骤。当前 USB iPhone 12 和 Android 均已安装并启动；后续分别补云端回执、配对/业务旅程、关闭/缓存及符号验证。

## 2026-09-11 01:16 CST：改用当前 USB 手机

用户说明另有已插线的 iOS 与 Android 手机，继续按当前设备验证。本次明确选择 USB iPhone 12 与华为 VCE-AL00；局域网可见的 iPhone 11 未安装，Pandaa 不可达不再阻塞 iOS 安装。

- **iPhone 12 可用于后续验收**：实际 iOS 26.6.1、USB wired、已配对、开发者模式开启、锁状态 passcodeRequired=false；查询详情后 tunnel=connected、DDI 服务可用。开始时没有安装 com.panda.ccpocket。使用同锁定依赖缓存完成 generic Debug 构建；最终以 `XCODE_XCCONFIG_FILE=iosApp/Sentry.local.xcconfig` 注入配置，核实包内 `CCPocketSentryDSN` 指向 pairlet-ios、`CCPocketSentryEnvironment=staging`，签名有效且 profile 含该设备。
- 最终安装/启动 **CC Pocket 1.9.8 (19)** 成功，进程 `1886`；启动约 30 秒后独立进程查询仍存在，设备应用列表回读版本一致。初次通道验证安装的包未带 Sentry 配置，未启动；已在首次启动前替换为上述配置完整的最终包。可执行文件 SHA-256 `3e0db053cbfa8ae67491daf2c1afb55e2102256e484dc8bed6c1bf0d5b3142b2`，Debug 代码 dylib SHA-256 `845c2d020ff41fb43f5d13cf121e9e699b18924dfca51c3de83c47139f21768e`。
- iOS 证据：`/tmp/pairlet-ios12-details.json`、`/tmp/pairlet-ios12-lock-state.json`、`/tmp/pairlet-abcd-ios12-sentry-build.log`、`/tmp/pairlet-ios12-install-artifact.json`、`/tmp/pairlet-ios12-sentry-install.json`、`/tmp/pairlet-ios12-launch.json`、`/tmp/pairlet-ios12-app-after.json`、`/tmp/pairlet-ios12-processes.json`。CoreDevice 虽打印 No provider was found 警告，实际隧道、安装和启动均成功；不以警告单独判定失败。
- **Android 连接/构建条件满足，安装待手机操作**：华为 VCE-AL00，Android 10/API 29，高于 minSdk 26，USB ADB 已授权；此前未装 CC Pocket。当前源码 `assembleDebug` 成功，APK 的 Sentry 配置为 pairlet-android/staging，SHA-256 `9e698479ffd06bcc69be30bfda46dc4c9c850794dd2c3eafb7e434489ca5f31e`。`adb install -r` 已进入手机系统 PackageInstallerActivity，尚未收到完成结果、package list 仍无该包，已请用户在手机完成安装提示；不能标安装或启动通过。证据 `/tmp/pairlet-abcd-android-device-build.log`、`/tmp/pairlet-android-install-artifact.json`。

本次不更改手机原有账号、配对数据或全局采集偏好，不发布 OTA、不重启 daemon/relay。iOS 仅完成真实安装与启动，尚未补齐该设备的 Sentry/Firebase 云端回执、业务旅程、开关/缓存及符号验收；Android 安装完成后继续启动和上报验证。

## 2026-09-11 00:58 CST：daemon 云端日志与 desktop GA4 启动回执

- detached 更新已完成。独立 postcheck 于 `2026-09-10T16:31:38Z` 核实新 daemon PID `16820`、单实例、443 已连接，全部 70 个 jar 哈希匹配；00:50 CST 再读仍为该单实例。最终 SDK jar 为 `3036019743ec46cbfb4985d8c6c8113f0f1effdf06e1a3dc91bc339ada0f5886`。更新日志 `/tmp/pairlet-abcd-daemon-update.log` 已记录全部步骤完成，检查文件 `/tmp/pairlet-abcd-daemon-postcheck.json` 的 `new_runtime_verified=true`。下节“待回读”现已关闭。
- **实际新 daemon 的 Logs 已在 Sentry 收到**。刷新 [daemon Logs](https://pairlet.sentry.io/explore/logs/?project=4512060686139392&statsPeriod=1h) 后看到重启后 16:33:33Z 至 16:47:14Z 的 8 条 `EP-08:ok`。展开最新一条，核实 `component=daemon`、`environment=staging`、`release=1.9.8`、`diag_schema=1`、`stage=connect`、`transport=relay`、本端/对端随机连接编号及 `suppressed_count=2`。示例 `diag_event_id=7180c3ace6cc40c282f243a43ce1e33d`。证据 `/tmp/pairlet-abcd-daemon-cloud-receipt.json` / `.png`。只验收新日志回执，不扩展为 Errors、业务旅程、跨端 spans 或上传 P95 通过；Sentry 图表当时显示 Estimated from 0，采用实际日志表和展开字段取证。
- **GA4 首次确认到本轮对照事件**。实时报告事件表第二页有 `observability_validation=1`，逐层展开 `source` 后值为 `mp_after_tag_probe`，对应 15:59:40Z 的隔离 MP 请求；首次记录该来源的后台观察时间为 16:53:39Z。证据 `/tmp/pairlet-ga4-post-tag-cloud-receipt.json` / `.png`。证明已配置流的这条 MP 对照事件可查；不是实际 App 事件，也不是首次出现的精确时刻或发送延迟测量。
- **实际桌面启动事件也已收到**。继续展开实时表中的 `app_launch=1`，16:57:21Z 核实 `edition=desktop`，随后核实 `app_environment=staging`、`app_version=1.9.8`；参数表包含新版 analytics_schema/app_platform。独立配置探针均使用 observability_validation，没有用探针补写 app_launch。证据 `/tmp/pairlet-ga4-desktop-cloud-edition.json` / `.png`、`/tmp/pairlet-ga4-desktop-cloud-environment.json`、`/tmp/pairlet-ga4-desktop-cloud-version.json`。因此“实际 desktop 始终无后台事件”已解除；未由此推断对应哪次进程启动、精确上传时延或所有请求都已收录。会话/提示等核心结果、正式探索和留存仍须逐项验收。
- 为区分身份格式与已有 tag 记录影响，16:54:27Z–29Z 各发送一次固定 staging/internal/unknown `observability_validation`：新短数字身份、实际安装身份、已收到对照的身份。来源分别为 `mp_fresh_numeric`、`mp_app_identity`、`mp_known_control`；均使用数字秒 session 与数字 engagement 参数，HTTP 204，后台比较仍待回读。私有实验记录 `/tmp/pairlet-ga4-identity-comparison.json`，未修改 App 身份、首次价值标记或采集偏好。不能据上一条对照就断言缺报由纯 MP、身份长度或会话参数引起。
- 17:00Z 完整刷新 GA4 后，对照事件仍为 1，未确认上述三组新来源；保留待观察，不追加重复发送，也不以此撤销已取得的 desktop 启动回执。Sentry 同次读到 11 条新 daemon 日志，最新发生时间 16:55:55Z，补证实际运行仍持续产生云端记录。
- 实时报表随窗口滚动变化：17:01Z 再展开同名 app_launch 时，仅显示 firebase_event_origin / ga_session_id / ga_session_number；不能把这个新窗口中的同名事件沿用为 desktop。上述 desktop 回执以已保存的 edition/environment/version 逐层证据为准，后续观察须重新检查平台参数。
- desktop PID `15073` 和已安装配置对应的 Sentry 项目已回读；最近 1 小时未找到新 Logs，24 小时列表仅有此前记录，不将源头计数冒充云端回执。Pandaa 再查仍 unavailable；桌面 UI 自动化仍报 `cgWindowNotFound`，真实界面旅程未补验。
- relay 恢复替身新增上传损坏、上传缺文件、新服务健康失败、替换期间真实 HUP 信号、恢复复制失败 5 项。`python3 -m unittest discover -s scripts/tests -p 'test_relay_apply.py' -v` **11 项全通过**，日志 `/tmp/pairlet-abcd-relay-recovery-expanded.log`。检查旧服务状态、旧文件/Caddy 恢复、备份保留及失败状态；只操作临时目录和服务替身。HUP 实验不冒充真实 SSH/systemd 断连演练，本轮没有再次部署服务。

**仍未完成**：desktop GA4 核心操作结果的完整回执与五视角/留存验收、desktop Sentry/各手机新包云端证据、原生 fatal 隐私及符号、全部平台生命周期/多后端受控旅程、真实部署断连、P95 和真实观察期。无需用户再确认 Dia 拦截设置。

## 2026-09-11 00:27 CST：SDK 读超时漏计修复

补充实际 SDK 故障实验发现读超时没有触发原有 item-loss 回调。新增公开 SubmissionResult 观察器，保留 SDK 原传输/限流/关闭/已有 typed hint；用 TRANSPORT_FAILED 记录失败的 envelope 提交数，不能冒充错误条数、日志条数或实际 HTTP 请求数。未新建 uploader、重试或原始内容日志。

- Java Sentry 模块 **18 项通过**，0 失败、0 跳过。新增读超时、隔离 resolver 的 DNS 失败、实际 TLS 握手失败、子 JVM 正常退出、强杀/新 JVM 重启 5 项；强杀后预算保留且不回放旧内存事件，哨兵内容未落入捕获输出/临时文件。首次读超时用例失败后修复，最终日志 `/tmp/pairlet-abcd-sdk-lifecycle-final.log`。Android 编译和 daemon/relay installDist 同次通过，不把它作为全部平台故障矩阵完成。
- desktop 标准脚本已更新，实际进程 `15073`。安装的 Sentry jar 为 `observability-sentry-jvm-0.0.1-SNAPSHOT-1f679886728d642e907261f68f0427c.jar`，SHA-256 `bf2798186abd6a8273b73bb37a7ffaf73f8900eac78845cf3d47b13f1e906f80`；包含 CountedSentryTransport，与服务分发 jar 的全部条目内容哈希一致。打包 ZIP 元数据不同，未将两个归档的 SHA 混为相同。标准重启已结束前一次临时 GA4 debug 环境变量。
- relay 再次通过标准部署安装最终修复包，实际进程 `546725`，启动 `2026-09-11 00:27:27 CST`，active、ExecMainStatus=0，源站和公网健康通过。最终 Sentry jar SHA-256 `3036019743ec46cbfb4985d8c6c8113f0f1effdf06e1a3dc91bc339ada0f5886`；公共 jar 仍为下节 `20a5f7ff…`。部署目录 `/tmp/cc-pocket-relay-deploy-20260910T162256Z-14813/`，结果 ok。日志 `/tmp/pairlet-abcd-relay-transport-fix-deploy.log`、`/tmp/pairlet-abcd-relay-final-identity.log`。此前已用完的日志预算继续保留。
- daemon 旧实际包及 plist 已备份到 `~/Library/Application Support/cc-pocket/deploy-backups/pairlet-abcd-20260910T161400Z/`，含 SHA 清单。独立检查脚本已准备，会核对重启后的新 PID、单实例、443 socket 和全部 70 个 jar；结果路径 `/tmp/pairlet-abcd-daemon-postcheck.json`，更新日志路径 `/tmp/pairlet-abcd-daemon-update.log`。目前按最后执行的 detached 更新安排记录，实际结果须回读，不能提前标完成。

**剩余状态**：GA4 DebugView 仍为 0 调试设备，后台正式回执/留存视图未通过；Pandaa 不可达。Cocoa/Android 完整真实生命周期与符号、多后端受控旅程、每组件上传 P95、7 天运行和成熟留存样本均未被本增量关闭。没有等待用户对 Dia 作人工确认。

## 2026-09-11 00:08 CST：relay 更新与真实限额证据

- 部署前协议专项只读复查为 COMPATIBLE，无阻断项；九类可空 diagnostic 字段与 capability 保持默认值，HistoryComplete/HistoryApplied/PromptProgress/ApprovalProgress 按连接能力发送。已有兼容代理测试通过不冒充新旧发行包真机联调。
- daemon/relay installDist 再核验为 up-to-date，日志 `/tmp/pairlet-abcd-deploy-ready-build.log`。标准 `redeploy-relay.sh` 上传 27,282,359 字节归档期间旧服务保持 active；服务端独立 systemd job 完成校验、备份、切换、启动和健康检查，结果 `ok`。实际新进程 `546182`，启动 `2026-09-11 00:08:10 CST`，ActiveState=active、ExecMainStatus=0，源站和公网健康通过。
- 线上公共 jar SHA-256 `20a5f7ff4e5b204857cb9802b5ee4c992ee7aa4e93d438f0e01fa7b19a2b7db8`，Java Sentry jar `6cab46ee1aa8dbf27e0eae517bc3b319b90da8ba29233899c983bf04dca7f0ba`，与本机新 installDist 一致。回滚备份 `/opt/cc-pocket-relay/.deploy-backups/20260910T160809Z-546143`；部署结果目录 `/tmp/cc-pocket-relay-deploy-20260910T160337Z-16027/`。证据 `/tmp/pairlet-abcd-relay-deploy.log`、`/tmp/pairlet-abcd-relay-after-deploy.log`。
- `2026-09-10T16:08:51Z` 向实际 `/v1/device` 发单条无身份 `{}`，得到预期 WebSocket 1008 / expected_hello，证据 `/tmp/pairlet-abcd-relay-handshake-check.json`。未启动额外 daemon，也未将握手拒绝当作会话业务验收。
- **已确认新 relay 日志未继续上传的本地原因**：真实运行用户目录 `/var/lib/cc-pocket-relay`，部署前持久预算为 day=20706、logs=500、errors=1、bytes=185911、admitted=501。每日非错误日志额度已用满，重启后原额度保留。新包计数文件实际出现 SOURCE_SUBMITTED=15、BUDGET_REJECTED=15、SOURCE_SUPPRESSED=275，明确区分源头接受、限额拒绝和源头抑制；这些不同层级的数字不能相加作为缺报条数。未提高生产额度或删除预算文件，因此不能声称本批新 relay Logs 已在 Sentry 收到。
- Pandaa 最新仍 unavailable；当前任务为本机唯一 daemon 的后代，没有其他直接子会话。daemon 更新须最后通过 detached 脚本执行，尚未把排期写成已安装。

GA4 续验：重新逐字比对后台可见密钥与本机配置，且已安装 jar 内配置一致；数据流编号匹配，现有 Internal Traffic 过滤器处于测试状态。隔离无用户账号的官方 tag 对照取得 `/gtag/js` HTTP 200 与 `/g/collect` HTTP 204；同一合成身份的 MP 对照也返回 Google Golfe2 HTTP 204。事件仅为 staging/internal 的 observability_validation，不计业务成功。DebugView 和实时列表仍未确认到新事件，不能据此确定为纯 MP 限制或账号配置错误。隔离测试进程/临时 profile 已清理；有界 headed 测试未能启动调试端口，未修改 Dia 的拦截设置。证据 `/tmp/pairlet-ga4-headless-result.json`、`/tmp/pairlet-ga4-post-tag-probe.json`。Google 明确 [验证服务器不检查 API secret](https://developers.google.com/analytics/devguides/collection/protocol/ga4/validating-events)，故本次补做了实际配置比对；后台回执仍待完成，不需要用户再次确认。

## 2026-09-10：GA4 确认、桌面接入与校验

**15:51Z 增量检查点**：为直接核验实际 App，新增显式本机 `CCPOCKET_GA4_DEBUG=1` 开关，仅 development/staging 生效。向本机 stderr 输出固定发送状态/HTTP 数字码，不含事件名、请求正文、响应正文、身份、URL 或异常文本；保留取消语义，不重试，不递归上报。开启时实际 MP 请求附带 debug_mode。Telemetry 定向回归现为 30 项、0 失败、0 跳过，日志 `/tmp/pairlet-ga4-transport-tests.log`。

标准脚本已安装此次增量；退出旧桌面实例后，以该开关启动单个桌面进程 PID `13270`。实际 jar `composeApp-desktop-0.0.1-SNAPSHOT-f178317b97332bd8ebd2bfbfecca35f8.jar`，SHA-256 `2ae0b67db99c979d456cb2a8647b6336d2ed0943d42009db95ae3bcebadad9f3`。`/tmp/pairlet-ga4-desktop-transport.log` 权限 0600，已从真实 App 取得连续 3 次 attempt → HTTP 204；这次不是独立配置探针。随后 DebugView 仍为 0 调试设备，尚不能确认 GA4 后台收录或报表展示。当前缺口是后台回执，不是等待用户确认。调试环境变量仅限本次进程，正常重新启动 App 后失效；没有修改全局环境或 Dia 隐私设置。

- 用户明确“继续上面 GA4，我确认”后，通过已运行的 Dia `127.0.0.1:9222` 接受《用户数据收集确认书》，创建并回读 `Pairlet Desktop · local validation` MP 密钥。流 `15754516140`、Measurement ID `G-X04707FM0W`。密钥保存为 gitignored 的本地 `ga4.properties`，权限 0600，未输出值、未提交 Git，也未公开发布该开发包。
- Google `/debug/mp/collect` + `ENFORCE_RECOMMENDATIONS` 实测拒绝原 UUID client_id，返回 `client_id / VALUE_INVALID`，要求数字.数字。修复后保留本地随机 UUID 种子，完整两个 64 位分别转无符号十进制；已有数字身份保留，损坏值停发而不重建身份，首次价值标记保留。已用本机身份通过严格校验，见 `/tmp/pairlet-ga4-debug-validation.log`；debug 请求不会进报表。
- Telemetry 定向回归 26 项、0 失败、0 跳过，包含 3 项 client_id 格式/边界用例，见 `/tmp/pairlet-ga4-client-id-tests.log`。这是本轮格式修复的增量验证，不将先前 3,677 项全量回归说成修复后的重跑。
- desktop 已按标准脚本更新并启动，独立核验 PID `4245`，实际 jar `composeApp-desktop-0.0.1-SNAPSHOT-ac582df671b7fcec7dace619083398a.jar`，SHA-256 `7caa5204f711cca4ea42dcfc5423e7a0ada5ff6e701ecb4e65091909775d1ba7`，包含 GA4 资源和 Ga4ClientId 修复。日志 `/tmp/pairlet-ga4-desktop-final-update.log`。旧完整 App 备份在 `~/Library/Application Support/cc-pocket/deploy-backups/pairlet-ga4-20260910T151409Z/`。
- 新桌面配置为 staging，内部标记由运行时写入 `internal_traffic=1`；未修改用户原来的采集偏好。Native UI 控制仍返回 `cgWindowNotFound`，不能以进程存在代替界面/业务旅程验收。
- 2026-09-10T15:22:11Z 单次 `observability_validation` 配置探针返回 HTTP 204，字段为 staging/internal/unknown，来源 configuration_probe；它是独立 HTTP 探针，不是 App 的业务事件。记录 `/tmp/pairlet-ga4-configuration-probe.json`。当时刷新 DebugView 仍显示 0 调试设备，Realtime 的数据流筛选仍只列出原 iOS/Android 流，尚无本批实际桌面事件后台回执，不能标为云端通过。
- GA4 官方说明纯 MP 只保证部分报表能力；[HTTP 返回码不证明事件处理](https://developers.google.com/analytics/devguides/collection/protocol/ga4/reference)，[纯 MP 限制](https://developers.google.com/analytics/devguides/collection/protocol/ga4#full_server-to-server)。密钥当前仅用于本机受控开发；[官方不建议把 MP secret 暴露在发布客户端](https://developers.google.com/analytics/devguides/collection/protocol/ga4/sending-events#api_secret)，对外发行前须另验收凭据交付方式，不因 Git 忽略就视为发布安全。
- 为等待云端回执时继续推进，daemon/relay 最新 installDist 已构建通过，见 `/tmp/pairlet-abcd-current-distributions.log`；未部署或重启。Pandaa 最新仍 unavailable。当前任务确认是 daemon 后代，若后续更新需按 detached 脚本最后执行，不能直接中途 bootout。

Google 声明已确认，不再保留“等待用户确认”作为当前阻塞。还需区分实际 App 请求、配置探针、后台首次可查、五视角结果/留存可用性；下节“未更新 desktop / 未创建密钥”属于本检查点之前的历史状态。

**隔离 tag 对照已结束，无需用户操作**：本机临时页使用官方 Google tag、固定 staging/internal 配置事件，未修改或植入官网。Dia 对 `/gtag/js` 返回 `net::ERR_BLOCKED_BY_CONTENT_BLOCKER`；独立 HTTP 下载为 200，证据 `/tmp/pairlet-ga4-tag-probe-result.json`。这仅说明浏览器对照被拦截，不能解释 desktop 原生 MP 的上报结果。此前要求用户解除拦截的确认已撤回，不再作为验收阻塞；未更改 Dia 拦截设置。已关闭本任务的临时页并确认 59527 端口无服务监听，临时证据保留在 `/tmp/pairlet-ga4-isolated-probe/`，不含 MP secret。后续直接核验实际桌面发送链路和 GA4 后台回执。

## 2026-09-10：A–D 主体实现集中验证

本节记录集中测试时的历史检查点，后续 desktop 更新见上节；下面 A 批安装、进程与云端记录属于更早代码。当时按“先实现、后集中测试”推进，尚未更新实际 desktop/Pandaa/daemon/relay，不能据本地通过声称 A–D 整体验收。

| 检查 | 实测结果 | 证据与边界 |
|---|---|---|
| 最终 JVM 回归 | 3,677 项，0 失败，1 项既有跳过 | 公共 23、Java Sentry 13、protocol 279、daemon 1,887、relay 54、desktop 1,421；`/tmp/pairlet-abcd-final-regression.log`，`BUILD SUCCESSFUL in 5m 49s`；XML 逐模块汇总 |
| iOS Native 公共/协议/App | 22 / 276 / 193 项，均无失败 | 公共在最终回归重跑；协议见 `/tmp/pairlet-abcd-platform-tests.log`，App 见 `/tmp/pairlet-abcd-followup-tests.log`；与 JVM 有共享用例，不相加作为独立业务场景数 |
| Cocoa 实际 SDK 出口 | 5 项通过 | `/tmp/pairlet-abcd-cocoa-privacy-final.log`、`/tmp/pairlet-abcd-cocoa-privacy-final.xcresult`；Errors/Logs 哨兵、手工 trace 与父关联、UNKNOWN 不产生成功 trace、关闭期间迟到及初始化前关闭 |
| 工具与 relay 恢复替身 | 10 项通过 | `/tmp/pairlet-abcd-tools-tests.log`；临时文件和服务替身，不是线上部署故障演练 |
| 构建 | desktop 编译、Android debug APK、iOS Arm64 Kotlin 与模拟器 Swift 测试包通过 | 最终回归含 `assembleDebug` 与 `compileKotlinIosArm64`；尚未做本轮真机签名归档、安装及真实符号化 |
| 外部单次 relay health | HTTP 200，2,963 ms | 2026-09-10T14:29:36Z 单次检查；`diagnostic_delivery=unknown`，不是持续监控、E2E 或用户操作验证 |

测试使用 `CCPOCKET_TEST_MODE=1` / `ccpocket.test=true`，Java 接收端为 localhost 或替身，Cocoa 使用 URLProtocol 捕获；没有向 Sentry/GA4 发送本批业务测试。日志位于临时目录，可能被系统清理。

集中验证暴露并修复了实际问题：Cocoa 后台 start 返回不等于 SDK 就绪；初始化和关闭现串行回主线程、私有缓存准备在 worker，所有阶段以 generation 防止晚到重开。手工 transaction 的 root trace 从公开 `serialize()` 取仅限 trace/span ID，重建后的安全字段才可上传，不转发原字典。真实 Java SDK HTTP 503 丢失日志只给出不透明 envelope，新增独立 `SDK_LOG_BATCH_LOST`，不将批次数冒充条数。另修复编译依赖/跨模块属性问题，旧端打开结果按 UNKNOWN 验证；两处异步测试用实际任务完成替代短固定等待，业务断言保持不变。

### 云端配置与剩余验收

- Sentry 四 widget 看板已保存；GA4 五视角中四个完成配置回读，留存草稿因新价值事件尚不可选而未配置。入口和过滤条件见 [queries](queries.md)。配置存在不等于新业务结果已收到。
- 桌面此前没有有效 MP 配置；已创建并回读桌面数据流 `15754516140` / `G-X04707FM0W`，增强型衡量关闭。API secret、本机注入、官方 debug 校验与真实上报仍待完成。
- GA4 创建密钥前要求账号方确认《用户数据收集确认书》，声明隐私披露和最终用户授权均已具备。已请求用户确认，尚未接受声明或创建密钥；不是技术校验失败。
- 真实设备/部署：最新查询 Pandaa unavailable；Android USB 可见 VCE-AL00，换用该设备的确认尚待用户回复。本轮未安装手机、更新桌面/daemon 或部署 relay。
- 完整 SDK 断网/DNS/退出/强杀/重启和缓存矩阵、六后端适用分支及核心受控旅程、所有组件 Errors/Logs/安全栈的云端回执、真实 relay 部署恢复、上传 P95 尚未整体验收。已有 429/503/拒绝连接/队列/关闭等本地证据不能替代全矩阵。
- iOS native fatal 在 beforeSend 前落盘敏感字段的限制尚未解决；Android fatal 亦未迁移。继续保留 Crashlytics，符号上传和实际故障源码定位待验收。
- 七天运行观察、D1/D7 成熟样本和告警阈值基线仍待真实新版运行；不使用合成事件补产品结论。

本轮 Android debug APK 路径为 `mobile/composeApp/build/outputs/apk/debug/composeApp-debug.apk`，SHA-256 `ca64bf657896ddda2dcb2b68070fcdca419f54658b41a3facc62aa94bd18fb9b`。这是已构建但未安装的包身份。

## 2026-09-10：A 的采集基础增量

本增量交付持久诊断额度、两套设置页的共享开关、desktop Analytics 有界发送与关闭控制、三端公共维度和 [事件字典](EVENT-CATALOG.md)。完整 A 与 B–D 仍进行中，尚未接入会话实际完成等新结果事件或创建新后台报表。

### 本地验证

| 检查 | 当前结果 | 证据与边界 |
|---|---|---|
| 全量 `scripts/check-all.sh` | 3,640 项，0 失败，1 项既有跳过 | protocol 273、daemon 1,879、relay 54、desktop 1,407、公共 JVM 17、Java Sentry 10；`/tmp/pairlet-a-regression.log`。这是中途完整回归快照，后续修改另跑相关增量 |
| 最终公共预算及文件测试 | JVM 17、iOS Native 16 项通过 | 覆盖重建、跨日/回拨、损坏/过大文件、存储失败、锁竞争及日志不能占满错误预留字节；`/tmp/pairlet-a-final-budget.log` |
| 最终 Java Sentry 测试 | 10 项通过 | 真实 SDK 出口、429、有限队列、重建 SDK 额度保留、存储失败无发送、关闭期间迟到预留；`/tmp/pairlet-a-final-verify.log` |
| 最终 desktop Telemetry 测试 | 16 项通过 | 包含两个实际设置页面的隔离偏好测试、有界队列/在途取消/旧代际丢弃、公共维度及工具名过滤；同上 |
| 最终 Cocoa SDK 测试 | 2 项通过 | 实际 Cocoa SDK 请求由 URLProtocol 捕获，检查最后出口脱敏和关闭期间迟到记录；`/tmp/pairlet-a-cocoa-final-20260910.xcresult`、`/tmp/pairlet-a-cocoa-final.log` |
| 构建 | desktop、Android Debug、iOS Arm64、daemon/relay installDist 通过 | 安装包与真实上报另行核对，编译不代表设备/云端通过 |

Gradle 使用 `ccpocket.test=true`，Cocoa 使用 DiagnosticTests 的 `CCPOCKET_TEST_MODE=1`；上述测试不上传真实供应商。测试数量存在包含关系，不能将全量与增量简单相加。日志和 xcresult 位于本机临时目录，可能被系统清理。

### 实际部署

| 组件 | 本增量状态 | 验证边界 |
|---|---|---|
| desktop | 已更新并启动，PID 46174 | 已在实际“设置 → 关于”看到共享开关及完整说明；保留用户原有开启状态。安装 jar 为 `composeApp-desktop-0.0.1-SNAPSHOT-21f4cfe16d9b15934bed2f3ebdb604f.jar`，SHA-256 `db7addaa77e2a2f45b2f28a99bc618c2644cbfef3a4257db67a28c81daa790bb`；实际 `desktop-staging.json` 已产生 4 次预留。这不是本批 GA4/Sentry 云端新回执 |
| iOS / Pandaa | 已安装并启动 `1.9.8 (19)`，PID 11205 | 标准安装脚本的 SwiftPM 克隆遇 GitHub TLS 错误，改用同锁定版本缓存完成 generic iOS 签名构建，校验签名/5 分钟内新鲜度后通过 devicectl 安装；手机解锁后启动成功，设备查询回读版本一致。沙盒 `ios-staging.json` 有 2 次预留、816 字节，证明实际新入口已执行，不代表云端已收到。无 OTA 发布 |
| daemon | 已更新并启动，PID 52869 | `update-local-daemon-detached.sh` 识别当前为非 daemon 后代并调用标准更新；活跃会话已结束，无 FORCE 跳过。脚本及独立 ps/lsof 核验均为 1 实例、4 条 443 已建立连接。安装的两份观测 jar 与最新 installDist SHA-256 一致 |
| relay | 新包运行中，PID 543454 | 完整 stop/start 后 active、ExecMainStatus=0；服务器启动记录为 2026-09-10 18:39:58 CST。两份观测 jar 的 SHA-256 与本批分发包一致，源站/公网 healthz 均为 ok，Caddy 配置有效并已 reload。部署中断与恢复见下文，不把原脚本退出误记为成功 |
| Android | Debug 编译通过 | 本批没有 Android 真机安装或云端回执 |

真机缓存构建日志：`/tmp/pairlet-a-pandaa-cached-build.log`；App 可执行文件 SHA-256 为 `3e69644e320e2d9cb1bf521f9fbb66b6b253f44e773e7dec34e777c133082a57`。设备查询/启动与预算回读分别保存在 `/tmp/pairlet-a-pandaa-app.json`、`/tmp/pairlet-a-pandaa-launch.json`、`/tmp/pairlet-a-ios-budget.json`。本机旧桌面包和 daemon 分发包保存在 `~/Library/Application Support/cc-pocket/deploy-backups/pairlet-observability-a-20260910/`。

daemon/relay 新分发包公共 jar 的 SHA-256：`observability-jvm` 为 `63998d0e9b80f4827c10a8a5a508b23fa520fc7bfb8faeb6bf4be26626cb8720`，`observability-sentry-jvm` 为 `07863e61391fd644b066553e3c7671c861c5e36eb9eafd812041b7f658e5eb0d`。这些身份用于核对本次未提升产品版本号的开发安装；不代表完成正式发布。

**relay 部署故障与恢复**：备份的首次 SSH 连接被拒绝，间隔重试后成功。标准 `redeploy-relay.sh` 随后完成新包/Caddyfile 上传与替换，但第 4 步 SSH 认证拒绝，服务一度为 failed；原部署脚本失败。使用单次远端会话核对新包 SHA-256、完成权限修正、reset-failed/start、源站健康及 Caddy 校验/reload 后恢复，公网再次为 ok。未回滚到旧包，最终运行的是本批新包。daemon 日志在 18:40:21 记录 attach replay complete，在 18:40:42 记录收到 Pong；独立核验仍只有 PID 52869 一份 daemon、2 条 443 已建立连接。没有运行会另起 daemon 的旧生产 smoke。

远端旧包备份为 `/opt/cc-pocket-relay-backups/observability-a-20260910/dist.tgz`，SHA-256 `5eceb942433f2b151205096319fdc16dc0e8654c4cfed0de351104f4d5a73b79`，同目录有 unit/Caddyfile。原脚本日志保存在 `/tmp/pairlet-a-relay-deploy.log`。这次故障暴露了切换与启动分属不同 SSH 调用的中断窗口，已纳入 OBS-10 的部署恢复加固任务；本批只完成恢复，尚未修改或验收新的部署脚本。

### 尚未通过的验收项

- SDK 的离线、DNS、正常退出、强杀、重启、缓存清理等完整实验矩阵；当前仅部分出口及 429/队列/关闭已有实验。
- 全部健康计数的跨重启汇总；持久预算计数不等于 Reporter、SDK 和网络各层的完整丢弃数。
- 原生符号及所有实际运行组件的受控错误/日志云端证据，关闭后的最终缓存/网络核验和服务独立失联验证。
- GA4 正式字段/查询、desktop Measurement Protocol 报表能力、内部流量覆盖；当前统一维度已经实现，不能据此认为报表已经配置或结果事件已经存在。
- B 的会话打开/历史首次展示/提示响应/恢复闭环，C 的其余核心路径与功能采用，D 的五视角视图、发布和成熟样本观察。

完整机制和限制见 [RELIABILITY](RELIABILITY.md)。下一开发顺序仍是 A 的必要公共契约与开关基础之后推进 B，独立的平台和符号补验交错完成。
