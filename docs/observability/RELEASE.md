# Pairlet 观测发布与验收

**收尾范围确认（2026-09-11）**：用户要求整理待提交内容，其他验证暂缓至上线后观察。本批只整理代码、现有证据和 CI 配置，不再装机、跑补充测试、重新操作手机或回读云端页面。现有 CI 的正常构建、配置校验及符号上传步骤保留。未验证事项仍如实记录，但不再作为本次提交的前置条件。提交范围与 GitHub 配置见 [收尾清单](CLOSEOUT.md)。

2026-09-10 实施与验收稿。集中本地回归、SDK 出口及脚本替身测试已通过，详见 [ACCEPTANCE](ACCEPTANCE.md)；本页不构成实际部署、正式发版或自动 fatal 切换回执。

## 官方包配置门禁（2026-09-11 增量）

`release.yml` 的 Android、macOS/Windows desktop、三平台 daemon 构建均调用公共 action `observability-config`：构建前从仓库变量生成 **production** 配置，发布资产前再读取真实 APK / app 内的 jar，要求恰好一份正确组件配置。缺 DSN、错组件/环境、重复配置或夹带本机 `ga4.properties` 都会失败，避免新发行包无诊断能力或将 MP 私钥打包。

- 仓库变量：`PAIRLET_SENTRY_DSN_ANDROID`、`PAIRLET_SENTRY_DSN_DESKTOP`、`PAIRLET_SENTRY_DSN_DAEMON`、`PAIRLET_SENTRY_DSN_IOS`；另已配置 `PAIRLET_SENTRY_DSN_RELAY` 供 relay 构建复用。值是公开的写入 DSN，授权令牌不能放这里。
- iOS 仓库 secret：`PAIRLET_SENTRY_AUTH_TOKEN`，来自组织 token `Pairlet GitHub release symbols`，仅 `org:ci`。只在上传步骤注入进程环境；不进入 App、日志、仓库或 artifact。缺失会在 archive 前明确失败。
- iOS 通过 `-xcconfig iosApp/Observability.generated.xcconfig` 注入，归档后读取处理过的 App Info.plist 对照 DSN/环境，再核对并上传本次 archive 的 dSYM。上传处理未成功时不进入 App Store Connect 上传步骤。CLI 固定 3.7.0、校验官方 asset SHA-256；`project.yml` 同时固定 Cocoa 8.58.2 与归档 scheme，避免 CI 重建工程丢失依赖。
- `scripts/observability-release-config.py` 同时支持本地 staging 和 relay；拒绝覆盖已有配置。relay 当前部署方式未改动，仓库变量也不会自动改写正在运行的服务。

这些门禁只保证官方构建配置与符号上传流程，仍尊重已有采集关闭偏好。桌面生产 GA4 还需服务端接收/转发边界，不能将本机 Measurement Protocol secret 复制到公开客户端。本机测试凭据保持本机用途。没有触发公开发行、App Store 上传、fatal 切换或服务重启。

`release-preview.yml` 同样使用此 action，但明确传入 `environment: staging`，校验预演 APK、daemon/desktop jar 和 iOS archive；缺失配置不会生成可下载的预演 artifact。iOS 只核对本地 dSYM，不携带符号上传 token、不上传符号。正式工作流省略该参数时仍为 production，staging 包无法通过正式环境门禁。详见 [发布预演](../RELEASE.md#只预演不正式发布)。

## GitHub Actions 配置与维护

2026-09-11 已通过 GitHub API 核对仓库 `heypandax/pairlet`：以下配置都存在，5 个 DSN 符合公开 DSN 格式。正式工作流和脚本已在 main；最新 main `67a610ce` 还包含预览包 staging 注入。此次仅核对配置与源码，没有触发打包、上传、发布或重新校验 token 权限；Secret API 不返回明文，存在不等于本次发布已成功。

| GitHub 类型 | 名称 | 用途 / 注入位置 | 当前状态 |
|---|---|---|---|
| Repository Variable | `PAIRLET_SENTRY_DSN_ANDROID` | release / release-preview 的 Android 包 | 已配置 |
| Repository Variable | `PAIRLET_SENTRY_DSN_DESKTOP` | release / release-preview 的桌面包 | 已配置 |
| Repository Variable | `PAIRLET_SENTRY_DSN_DAEMON` | release / release-preview 的 daemon 包 | 已配置 |
| Repository Variable | `PAIRLET_SENTRY_DSN_IOS` | ios-release / release-preview 的 iOS archive | 已配置 |
| Repository Variable | `PAIRLET_SENTRY_DSN_RELAY` | 保留给 relay 构建；不自动部署服务器 | 已配置 |
| Repository Secret | `PAIRLET_SENTRY_AUTH_TOKEN` | ios-release 的凭据存在性检查及 dSYM 上传步骤；映射为 `SENTRY_AUTH_TOKEN` | 已配置 |
| Repository Secret | `GOOGLE_SERVICES_JSON` | Android 正式构建恢复 Firebase 客户端配置（base64） | 已配置 |
| Repository Secret | `GOOGLE_SERVICE_INFO_PLIST` | iOS 正式构建恢复 Firebase 客户端配置（base64 或原始 plist） | 已配置 |

Sentry DSN 是客户端写入配置，可以进入安装包；符号上传 token 是 CI 凭据，只作为步骤环境变量。当前 token 按既有接入记录为组织 CI token / `org:ci`，用途与 [Sentry CI 权限](https://docs.sentry.io/api/permissions/) 对应；无需把 Sentry 管理员 token、Firebase 服务账号或本机 `.env` 整份复制到 GitHub。

管理入口：仓库 **Settings → Secrets and variables → Actions**，分别使用 [Variables](https://github.com/heypandax/pairlet/settings/variables/actions) 与 [Secrets](https://github.com/heypandax/pairlet/settings/secrets/actions)。现有值无需重填；后续轮换时更新同名项即可，workflow 不写入明文。GitHub 的环境注入方式见 [官方说明](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。

现有 workflow 的两处配置摘录（中间的 archive/校验步骤保持原顺序）：

```yaml
- name: Stage production Sentry config
  uses: ./.github/actions/observability-config
  with:
    component: ios
    dsn: ${{ vars.PAIRLET_SENTRY_DSN_IOS }}

# Archive and verify this build before uploading its symbols.
- name: Verify archive symbols and wait for Sentry processing
  env:
    SENTRY_AUTH_TOKEN: ${{ secrets.PAIRLET_SENTRY_AUTH_TOKEN }}
  run: bash scripts/observability-symbols.sh "$RUNNER_TEMP/CCPocket.xcarchive" --upload
```

`ios-release.yml` 在 archive 前检查 token 非空；archive 后校验配置、上传匹配的 dSYM 并等待处理，成功后才进入 App Store Connect 上传。Android 当前不启用 R8，不需要给它额外增加 Sentry mapping 上传 token。一般 PR 的 `ci.yml` 使用 Firebase 占位配置，不需要 Sentry 上传密钥；统一 `release-preview.yml` 注入 staging DSN、使用 Firebase 占位配置且不上传 iOS 符号，也不需要此 token。历史 `build-windows.yml` 仅作独立编译测试、未注入 Sentry DSN；需要可观测预览包时使用统一 release-preview，不把它的产物当成已配置诊断的发行包。

**桌面 GA4 独立限制**：Measurement Protocol 的 API secret 不是 Sentry DSN。当前正式桌面包不会携带本机 GA4 secret；生产桌面采集仍需安全的服务端接收/转发方案，不能靠把 secret 放进 GitHub 再打包到公开客户端解决。这是后续能力需求，不是等待留存数据后会自动消失的问题；不影响本次 Sentry 配置准备。

## 构建身份和符号

发布记录必须包含 Git SHA、dirty 状态/补丁标识、构建时间、版本、平台、包哈希、实际环境、Sentry 项目和符号标识。沿用当前兼容配置键与包名；Sentry 项目名为 Pairlet。相同版本不同开发包不能仅凭 release 字符串区分，应补实际包哈希。

| 平台 | 当前方式 | 发布核验 |
|---|---|---|
| iOS | Cocoa 8.58.2；Kotlin framework 静态链接 | 从本次 archive 提取 App dSYM，核对 App 与各有代码的嵌入动态 framework 的架构/UUID；无代码占位库见下文。已实测 App 符号入库，Kotlin 原生帧仍需实际故障验收 |
| Android | Sentry Java 8.41.0 安全 handled sink；当前 release 未开启 R8 minify | 实际 1.9.8 handled 错误已在后台核对 `PocketRepository.kt:2486`（见 ACCEPTANCE）；当前不产生混淆 mapping，记录 `not_minified`；以后启用 minify 必须同一构建注入 mapping UUID 并上传对应 mapping，不能上传任意上次文件 |
| desktop / daemon / relay | JVM class 行号、安全 Throwable 栈 | 对受控故障检查实际 jar 哈希、release、函数与行号；独立 smoke 的行号不能替代实际应用包 |

iOS 符号检查入口：

```sh
bash scripts/observability-symbols.sh /absolute/path/Pairlet.xcarchive
# 仅 org:ci 的上传 token 从安全环境注入；不写进命令、仓库、日志或客户端。
bash scripts/observability-symbols.sh /absolute/path/Pairlet.xcarchive --upload
```

脚本只扫描给定 archive 的 dSYMs，不上传源码 bundle。`--wait` 等待服务端符号处理；之后仍须在真实收到的受控错误/崩溃中核对函数/源码位置。官方依据：[debug-files 与 mapping 上传](https://docs.sentry.io/cli/dif/)。Android 未来 mapping UUID 必须随运行包进入事件 debug metadata，当前独立 Java client 不会自动读取 Android SDK manifest 字段。

Xcode 26.2 实测会将已静态链接 framework 的嵌入副本替换为 `/dev/null` 生成的 dylib，占位文件本身没有可符号化代码。脚本仅在每个架构都只有零长度 `__text`、`nsyms=0` 且 `minos=100.0` 时允许缺省 framework dSYM；真实动态库、混合架构有代码或未知输出均继续阻断。App dSYM 始终必须存在且 UUID 完全匹配，不使用供应商名称白名单。静态库符号归入最终 App 的原则见 [Apple DTS 说明](https://developer.apple.com/forums/thread/761589)。如果未来 Xcode 改变占位格式，先检查实际二进制和构建日志再更新识别器，不关闭门禁。

## 既有崩溃采集的范围边界

用户已于 2026-09-11 确认不迁移自动崩溃采集器：Android/iOS 继续由 Crashlytics 负责自动 fatal，Sentry 自动 fatal 保持关闭，仅承接显式安全错误与日志。供应商切换不再是本期验收门槛。

用户随后明确：Crashlytics 链路未改动，本批不制造原生崩溃，不把其回执、缓存和符号重新列为强制验收。以后确实改动崩溃采集或符号发布链路时，再针对改动验证，不能由 Sentry 白名单测试推定 Crashlytics 通过。iOS Sentry 安全 handled 栈的函数定位与条件性 basename/行号保留独立记录；向 Sentry 上传 dSYM 不代表历史事件具备原生地址可供符号化。

历史技术评估保留供未来另行决策：锁定的 Cocoa 8.58.2 在 `Sources/SentryCrash/Recording/SentryCrashReport.c` 的 `writeError` 中将 `crashReason`、NSException name/userInfo 写入崩溃文件，早于上传前过滤；仅靠 `beforeSend` 不能满足原迁移方案的写入前白名单要求。现有 Sentry 桥接也只接受显式安全记录并清理重启缓存，不能直接开启 `enableCrashHandler`。这些限制不再要求本期解决；本期不切换、不双重自动上报，Firebase Analytics/FCM 继续原职责。

## 集中验证顺序

按用户要求先完成主体实现和用例，再集中验证。下列仍是完整验收顺序；1–4 的本地覆盖已完成集中运行，不能将其等同于全部平台实验和真实业务旅程通过，实际执行范围见 ACCEPTANCE：

1. 公共 schema/额度/持久计数/时钟/关闭代际；Java 与 Cocoa 最终 envelope 的白名单、SDK 自动字段及 spans；源失败不递归上报。
2. protocol 新旧 App/daemon/relay 矩阵、坏类型诊断字段降级、能力关闭；并发打开、分屏、换电脑、旧回复、prompt ACK/输出/退出与文件混合分片。
3. 六 Agent 后端、空/大/损坏/分页历史、人工等待与审批、文件/上传、临时 store、调度、协作 ACK、Git 和升级故障/正常对照。
4. 全模块回归及 desktop/Android/iOS 构建。已有 A 批 3,640 项回归不覆盖本轮变更。
5. 安装真实包，逐组件收集 Errors/Logs、安全栈/符号、开关、离线/429/满队列/正常退出/强杀/重启的独立证据。Android 缺真机时保留待验收，不标 N/A。

测试使用临时目录、替身服务器和 staging。不得调用 daemon:run、前台 launcher 或旧生产 relay smoke 来制造第二个本机 daemon。

## 实际部署与恢复

遵循设备同步 skill：desktop → Pandaa → 本机 daemon，relay 单独。执行前重新核对设备可达、活动会话和实际进程谱系。daemon 只使用 `update-local-daemon.sh`；若当前任务由 daemon 驱动，使用 detached 包装。更新后独立检查单实例、8799 与 relay socket，不凭脚本退出码宣称完成。

relay 新脚本将 bin/lib/Caddyfile/sha256 先传至唯一 staging 目录，旧服务继续运行；独立 systemd 服务完成校验、备份、切换、健康与自动恢复。结果文件有 `ok`、`failed`、`rolled_back`、`rollback_failed`。SSH 返回未知只读该次 staging/result 和 systemd 状态，不重复替换。没有测试通过前不执行线上部署。

隔离脚本用例：`python3 -m unittest discover -s scripts/tests -p 'test_observability_tools.py'` 与 `python3 -m unittest discover -s scripts/tests -p 'test_relay_apply.py'`。它们只使用临时目录、服务命令替身和内存回执，不启动 daemon/relay、不访问 Sentry/GA4；夹具的 pass 不能填成真实上传时效或线上恢复通过。

2026-09-11 恢复脚本替身扩至 11 项并全通过：补齐上传哈希损坏/缺文件、新服务健康失败、替换期间真实 HUP 和恢复复制失败，核对旧服务状态、文件/Caddy、完整备份及结果分类。日志 `/tmp/pairlet-abcd-relay-recovery-expanded.log`。真实 SSH 断开及 systemd 服务独立运行仍须单独演练；本地 HUP 只证明脚本收到信号时的恢复路径。

GA4 MP 预校验：从私有配置载入 `GA4_MEASUREMENT_ID` 和 `GA4_API_SECRET` 后，执行 `python3 scripts/observability-ga4-validate.py /path/to/safe-payload.json`；不将 secret 写入 shell 历史。脚本使用官方 `/debug/mp/collect` 和 `ENFORCE_RECOMMENDATIONS`，不将事件导入报表、不输出凭据或服务端回显正文；`validation=accepted` 仍不证明正式 collect、DebugView 或查询回执。测试包另用 staging/internal 受控旅程验证真实发送。

集中故障演练需覆盖传输中止、校验不符、替换失败、新服务启动失败、健康失败、切换时 SSH 断开，以及恢复自身失败。检查旧服务是否持续/恢复、备份完整和 Caddy 状态，不能仅查看返回码。回滚失败保留备份与结果，不继续删除；人工选择准确备份恢复后再次核对包与健康。

已知历史备份仅作为线索，实际使用前重查：[PAIRLET](PAIRLET.md)、[ACCEPTANCE](ACCEPTANCE.md)。诊断预算与采集关闭偏好不随二进制回滚重置。

## 独立失联检查

`python3 scripts/observability-health.py https://pocket.ark-nexus.cc/healthz` 是单次 HTTP 健康检查，不启动长期监控、发送通知或创建外部账号。必须从服务所在主机之外运行，才能提供主机失联时的外部证据。脚本不收正文、不跟随重定向、不带凭据，输出固定网络类别、状态与耗时；200 只证明该健康端点可达，不能证明登录/E2E/用户操作或 Sentry 采集正常。

隔离实验分别让健康端点不可达、诊断接收端不可达，并保留对照；公网状态/反向代理与实际 relay 进程健康分别核对。持续探测可使用已有主机上的外部调用或云服务，但启用/费用/接收者另行记录，本次没有开通监控或通知。

## 时效与七天观察

真实回执 CSV 列：`component,kind,event_id,occurred_at,visible_at,eligible`。kind 为 error/log；时间含 UTC offset；未收到的 visible_at 留空；eligible 只在在线、采集开启、Sentry 可达条件明确满足时为 true。每组件每类至少 20 个；先在 staging 为受控实验安排有限测试预算，不提高生产默认额度。

`python3 scripts/observability-latency.py receipts.csv` 对每组按 nearest-rank 计算 P95，缺报保留在分母并阻止通过；不足样本返回 insufficient_samples。visible_at 必须来自后台首次可查观察，不能填 SDK 返回或 flush 时间。时钟有偏差先校准，不产生负时延样本。该脚本不生成测试数据，也不访问或上传业务内容。

七天观察从已验收实际版本上线后真实经过的时间起算。每日记录参与采集安装覆盖、Events/Logs/Spans 用量、有限本地 suppressed/dropped、迟到/缺报、故障阶段与正常对照。D1/D7 仅纳入成熟群组；产品结论写入 [PRODUCT-REVIEW](PRODUCT-REVIEW.md)。不足七天保留待观察，不将 smoke 或旧版本日志补成新版本七天结论。
