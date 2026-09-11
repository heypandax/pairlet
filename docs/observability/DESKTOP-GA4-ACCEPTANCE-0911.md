# 桌面端 GA4 入口批次 4 抽样验收（2026-09-11）

本文是 [DESKTOP-GA4-INGRESS.md](DESKTOP-GA4-INGRESS.md) §10「批次 4 验收」的抽样执行记录。只做验证与记录，**未改动任何产品代码**；过程中发现的问题只登记、不修复。

- 执行分支：`feat/desktop-ga4-ingress`，worktree `/Users/lidapeng/Desktop/Project/app/cc-pocket-worktrees/desktop-ga4-ingress`，HEAD `14a96a99`。
- 环境：macOS 26（Darwin 25.5.0），JDK `/opt/homebrew/opt/openjdk@17`。
- 所有真实上报均为 **staging** 环境，落 GA4 属性 `540841272` 的 Web 数据流 `G-X04707FM0W`（streamId `15754516140`）。production 流未被触碰。

## 1. 范围与边界

### 覆盖

- 未配对首启的事件预期清单与真实上报（1 次）。
- 发布门禁 `scripts/observability-release-config.py desktop --verify` 的正向通过与反向拒绝。
- 四类传输故障抽样：不可达主机、429、204、非法入口值。
- 三层回执分别核对：入口收到／上游 2xx／GA4 已处理。

### 不覆盖（本次明确不做）

- 配对后旅程（会话打开、发提示、断网重连）——需要人工操作 GUI，步骤见第 6 节。
- 关闭采集、在飞取消、重开不重放的**活体**验证（仅有单测覆盖，见 4.5）。
- 服务端侧的未知字段／超大请求／错误凭据注入测试（属 relay 侧验收，本次未做）。
- GA4 普通聚合中的最终核对——我的事件在验收窗口内尚未进入标准报表，见 5.3。

### 两个与任务前提不一致的事实（先说清楚，后面结论依赖它们）

1. **桌面端 SecureStore 不是系统 keychain**，而是 `~/.cc-pocket-app/store.properties`（`SecureStore.desktop.kt`）。因此「与日常 App 同一身份」成立，但机制是同一个属性文件，不是 keychain。
2. 由于是同一个文件，两个 App 实例并存会**互相覆盖写**该文件。为不破坏用户日常 App 的状态，本次所有运行都指向一份**拷贝**（`/tmp/ga4-store-unpaired.properties`，通过 `-Dccpocket.secureStore.file=` 注入），用户的 `~/.cc-pocket-app/store.properties` 全程只读。

用户的日常桌面 App 全程在运行，**未关闭**：

```text
lidapeng  15073  /Applications/CC Pocket.app/Contents/MacOS/CC Pocket
```

本次验收只做分析链路的观察，两个实例并存只影响 relay 连接抢占，不影响本文结论。

## 2. 事件预期清单（未配对首启）

grep `Telemetry.track` 全部调用点后，未配对首启路径上**桌面端**实际只有一个触发点：

| 事件 | 触发点 | 关键参数（`TelemetryMetadata.prepare` 统一补齐） |
|---|---|---|
| `app_launch` | `desktopMain/.../Main.kt:212`（`DesktopApp` 的 `LaunchedEffect(Unit)`） | `analytics_schema=v1`、`app_version`、`app_platform=desktop`、`app_environment=staging`、`internal_traffic=1`、`usage_mode=unknown`；传输层再补 `edition=desktop`、`app_version`，`session_id` 走 body 顶层 |

**`onboarding_shown` 在桌面端不会触发**（发现 F1）。`TelEvent.OnboardingShown` 只在 `commonMain/.../OnboardingScreen.kt:167` 发出，而 `OnboardingScreen` 唯一的调用方是 `PairingScreen.kt:114`，`PairingScreen` 又只被 `commonMain/.../App.kt:451-452` 使用——那是**移动端**的根组合。桌面未配对时走的是 `desktopMain/.../desktop/ConnectPanel.kt` 的 `PairingForm`，这条路径上没有任何 `Telemetry.track`。

所以桌面未配对首启的预期是「**恰好 1 个事件：`app_launch`**」，而不是任务给的「至少 `app_launch` + `onboarding_shown`」。

## 3. 包与门禁

### 3.1 staging 包的配置

worktree 的 `mobile/composeApp/src/desktopMain/resources/` 起始状态**只有** `app-icon.png`、`font/`、`ga4.properties.template`——既没有 `cc-pocket-sentry.properties`，也没有 `ga4.properties`（两者都被 gitignore，且 worktree 不共享主检出的未跟踪文件）。因此任务里「把 `ga4.properties` 移到 `/tmp` 再移回」这一步**在本 worktree 无对象可移**；主检出 `/Users/lidapeng/Desktop/Project/app/cc-pocket` 的该文件全程未被移动，只在第 3.3 节被**拷贝**使用。

两个资源都用门禁脚本自己的 stage 路径生成（手抄的开发版 sentry 文件带注释行，与 `render()` 的产物不一致，会被 verify 判失败——这本身证明 verify 是逐字节比对的）：

```console
$ PAIRLET_SENTRY_DSN=<公开 DSN> PAIRLET_ANALYTICS_ENDPOINT=https://relay.pairlet.org \
    python3 scripts/observability-release-config.py desktop --environment staging
Sentry public config staged: desktop/staging; analytics ingress staged; user consent is unchanged
exit=0
```

生成结果：

```text
cc-pocket-analytics.properties   endpoint=https://relay.pairlet.org
cc-pocket-sentry.properties      environment=staging
                                 dsn.desktop=https://<redacted>@o4512060682141696.ingest.us.sentry.io/4512060699049984
```

### 3.2 构建与正向门禁

```console
$ JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :mobile:composeApp:createDistributable -q \
    -Pcompose.desktop.packaging.checkJdkVendor=false
build_exit=0

$ PAIRLET_SENTRY_DSN=<公开 DSN> PAIRLET_ANALYTICS_ENDPOINT=https://relay.pairlet.org \
    python3 scripts/observability-release-config.py desktop --environment staging \
    --verify mobile/composeApp/build/compose/binaries/main/app
Sentry public artifact verified: desktop/staging; analytics ingress verified; user consent is unchanged
verify_exit=0
```

`-Pcompose.desktop.packaging.checkJdkVendor=false` 是本机环境所迫（发现 F7）：本机只有 Homebrew 的 JDK，`:mobile:composeApp:checkRuntime` 直接拒绝打包（`Homebrew's JDK distribution may cause issues with packaging`）。该参数只从命令行传入，**未写进 `gradle.properties`**。CI 用 setup-java 的发行版，不受影响。

jar 内容自证（`composeApp-desktop-*.jar`）：

```console
$ unzip -l "…/CC Pocket.app/Contents/app/composeApp-desktop-….jar" | grep '\.properties'
       35  09-11-2026 16:49   cc-pocket-analytics.properties
      128  09-11-2026 16:49   cc-pocket-sentry.properties
      761  09-11-2026 16:49   ga4.properties.template

$ # 扫描 app-image 里全部 jar，确认没有任何 ga4.properties
(scan done)        ← 零命中

$ unzip -p "…composeApp-desktop-….jar" cc-pocket-analytics.properties
endpoint=https://relay.pairlet.org
```

注意 `ga4.properties.template` 是模板（含 `REPLACE…` 占位），`clean()` 会把 `REPLACE` 开头的值当空处理，且门禁只拒绝 `ga4.properties` 本体，模板进包是既有行为。

### 3.3 反证：带 `ga4.properties` 打包必须被拒

把主检出的 `ga4.properties`**拷贝**进 worktree 资源目录（`trap` 保证退出时删除），重新打包后 verify：

```console
$ # 资源目录存在 ga4.properties，build_exit=0
$ PAIRLET_SENTRY_DSN=… PAIRLET_ANALYTICS_ENDPOINT=https://relay.pairlet.org \
    python3 scripts/observability-release-config.py desktop --environment staging \
    --verify mobile/composeApp/build/compose/binaries/main/app
Sentry config check failed: verify public DSN, analytics endpoint, target, environment, and packaged resources
verify_exit=1

$ unzip -l "…composeApp-desktop-….jar" | grep '\.properties'
       35  09-11-2026 16:59   cc-pocket-analytics.properties
      128  09-11-2026 16:59   cc-pocket-sentry.properties
       62  09-11-2026 16:59   ga4.properties          ← 确实进了包
      761  09-11-2026 16:59   ga4.properties.template
```

随后删除该文件、重新打包并复验，恢复到干净包（后续所有运行都用这个包）：

```console
rebuild_exit=0
Sentry public artifact verified: desktop/staging; analytics ingress verified; user consent is unchanged
reverify_exit=0
```

打包产物版本号是 dev 默认值：`CFBundleShortVersionString = 2.0.0`（未传 `-PappVersion`）。这个值同时成了 GA4 侧的归因钥匙，见 5.3。

## 4. 未配对首启与故障抽样

运行方式统一为：`CCPOCKET_GA4_DEBUG=1`，`JAVA_TOOL_OPTIONS="-Dccpocket.secureStore.file=/tmp/ga4-store-unpaired.properties"`，stderr 重定向到文件，后台运行后按时长 kill。store 拷贝里删掉了 `paired_daemons`（制造未配对），但**保留了原样的 `ga4_client_id`**：

```console
$ diff <(grep '^ga4_client_id=' ~/.cc-pocket-app/store.properties) \
       <(grep '^ga4_client_id=' /tmp/ga4-store-unpaired.properties) && echo same
client_id seed identical to daily App
```

这就是「升级前后匿名身份连续」的机制证据：种子只从 `SecureStore` 读一次，没有就随机生成并写回（`Telemetry.desktop.kt` 的 `clientId` lazy），换包不换种子；`telemetry_enabled` 键在 store 里不存在，走默认 on。

### 4.1 未配对首启（真实上报，staging，40 秒）

```text
$ CCPOCKET_GA4_DEBUG=1 … "CC Pocket"   # 16:51:08 → 16:51:52
alive: 73249 SN
Picked up JAVA_TOOL_OPTIONS: -Dccpocket.secureStore.file=/tmp/ga4-store-unpaired.properties
SLF4J(W): No SLF4J providers were found.
[ga4-transport] attempt
[ga4-transport] http_2xx status=202
```

`202` 对应契约 §3 的「上游 MP 已返回 2xx（只是传输回执，不是 GA4 已处理）」，客户端不需要任何动作。全程只有一次 `attempt`，与第 2 节推导的「桌面未配对只发 `app_launch`」完全一致；`onboarding_shown` 如预期**没有**出现。

### 4.2 不可达主机（不发真实事件，30 秒）

```text
$ CCPOCKET_ANALYTICS_ENDPOINT=https://10.255.255.1 …   # 16:53:22 起
alive: 97857 SN
[ga4-transport] attempt
[ga4-transport] io_failed
```

分类是 `io_failed` 而不是 `timeout`——`10.255.255.1` 在本机网络下是快速失败（`IOException`），不是黑洞超时。两者都在预期集合内（`Ga4TransportProbe` 的 `timeout` 分支要求 `SocketTimeoutException`／`HttpRequestTimeoutException`）。App 进程存活，无崩溃。

### 4.3 入口回 429（30 秒）

本地 `http://127.0.0.1:18429` 对所有 POST 回 429：

```text
$ CCPOCKET_ANALYTICS_ENDPOINT=http://127.0.0.1:18429 …   # 16:54:56 起
alive: 15239 SN
[ga4-transport] attempt
[ga4-transport] rate_limited status=429

本地 server 请求日志（req#1 是我起服后的 curl 自检）：
req#1 POST /v1/analytics/register
req#2 POST /v1/analytics/register
total_requests=2
```

即 App 只打了 **1 次** `register`，拿不到令牌就停手，**没有**继续打 `collect`；`AnalyticsIngressClient.ensureToken` 在非 200 非 204 分支 `sleepFor(BACKOFF_MS)`，进入 10 分钟休眠。符合契约 §3「429 → 丢弃，休眠 10 分钟」。

### 4.4 入口回 204（30 秒）

```text
$ CCPOCKET_ANALYTICS_ENDPOINT=http://127.0.0.1:18204 …   # 16:55:41 起
alive: 20960 SN
[ga4-transport] attempt
[ga4-transport] http_2xx status=204

本地 server 请求日志：
req#1 POST /v1/analytics/register
total_requests=1
```

行为正确：1 次 `register` 后进入 1 小时休眠（`CLOSED_MS`），不再触网，符合契约 §3「`register` 收到 204 → 本进程休眠 1 小时」。

但**探针分类是 `http_2xx status=204`，不是预期的 `http_other status=204`**（发现 F2）。`Ga4TransportProbe` 把 `200..299` 全归为 `http_2xx`，于是「入口关闭、事件被丢弃」和「上游已接收」落进同一个诊断桶，只能靠后面的 `status=` 数字区分。这是诊断可读性问题，不影响传输行为。

### 4.5 非法入口值（30 秒）

```text
$ CCPOCKET_ANALYTICS_ENDPOINT='https://relay.pairlet.org/v1/extra' …   # 16:56:26 起
alive: 31779 SN
[telemetry] analytics endpoint is not a bare scheme+host — ignoring it
[ga4-transport] attempt
[ga4-transport] http_2xx status=202
```

按代码实际行为记录（发现 F3）：非法 env 值被拒绝并告警后，`route` 的解析**继续回落到资源入口**（`Telemetry.desktop.kt` 里 `ingressEndpoint(env) ?: ingressEndpoint(resource)`），于是仍然走 `https://relay.pairlet.org` 并真实上报成功。结论不是「desktop analytics disabled」，而是：

- 契约 §8 的优先级「env > 资源」成立，且**非法 env 不会毒化整条链路**，只是退回资源值；
- 更重要的是**不会绕回直连 MP**——因为一旦解析出入口就 `return@lazy Ingress(...)`，直连分支根本不可达；即使入口全部失效，直连分支也要求本机存在 `ga4.properties`（本包内没有）且环境非 production。这一条与门禁（第 3.3 节）构成双重保险。

### 4.6 休眠后续帧与开关类抽样（未活体覆盖）

桌面未配对首启一个进程只产生 **1 个事件**，所以「429／204 之后同进程内的后续事件只出现 `http_other status=0`」这条**无法在本次场景里活体触发**——没有第二个事件可发。只有单测覆盖，本次跑过为绿：

```console
$ ./gradlew :mobile:composeApp:desktopTest \
    --tests '*AnalyticsIngressClientTest*' --tests '*Ga4TransportProbeTest*' \
    --tests '*AnalyticsCatalogAlignmentTest*'
BUILD SUCCESSFUL
```

其中相关用例：`aClosedIngressSilencesTheProcessForAnHour`、`rateLimitingBacksOffForTenMinutes`、`anExpiredTokenIsReplacedOnceAndThenGivesUp`、`resetDropsBothTheTokenAndTheDormancy`。**单测不等于活体验收**，本条按「未覆盖」计。

## 5. 三层回执

三层分别核对，任何一层通过都不能推断下一层。

### 5.1 入口收到 ＋ 5.2 上游 2xx（relay 计数）

relay 每 5 分钟打一行累计计数（只读取 journal，未在服务器上做其他操作）：

```text
Sep 11 16:42:30  analytics ingress: on (streams: [production, staging])
Sep 11 16:47:30  analytics accepted=1 dropped:no_stream:development=1 received=3 register=1 registered=1 rejected:unauthorized=1 upstream:2xx=1 upstream_ms:lt500=1
Sep 11 16:52:30  analytics accepted=2 dropped:no_stream:development=1 received=4 register=2 registered=2 rejected:unauthorized=1 upstream:2xx=2 upstream_ms:lt100=1 upstream_ms:lt500=1
Sep 11 16:57:30  analytics accepted=3 dropped:no_stream:development=1 received=5 register=3 registered=3 rejected:unauthorized=1 upstream:2xx=3 upstream_ms:lt100=2 upstream_ms:lt500=1
```

- 16:47:30 是我开工前的基线。
- 16:52:30 覆盖 4.1 的运行：`register` ＋1、`received` ＋1、`accepted` ＋1、`upstream:2xx` ＋1。
- 16:57:30 覆盖 4.5 的运行：同样各 ＋1。

两次真实上报在入口侧和上游侧都精确对上，无多余请求。4.2／4.3／4.4 三个故障用例指向本地或黑洞地址，如预期**没有**在 relay 计数上留下任何痕迹。

### 5.3 GA4 已处理

**Realtime（通过）**。Realtime API 不支持 `customEvent:*` 维度（`Field customEvent:app_environment is not a valid dimension`，发现 F6），所以改用 `streamId` ＋ `minutesAgo` 归因。查询时刻 `17:01:14`：

```text
minutesAgo=10 streamId=15754516140 count=1     ← 16:51，对应 4.1
minutesAgo= 5 streamId=15754516140 count=1     ← 16:56，对应 4.5
```

`streamId=15754516140` 经 Admin API 确认即 `Pairlet Desktop · Measurement Protocol` / `G-X04707FM0W`（另两条 `15029235360`／`15029242019` 分别是 iOS／Android App 流）。两个事件落在正确的流、正确的分钟桶，各 1 条。

**标准聚合（未覆盖，待复核）**。本次打的包 `app_version=2.0.0`，而当天 `runReport` 里桌面行全部是 `1.9.8`：

```text
app_launch | desktop | 1.9.8 | 1 | staging => 1
feature_used | desktop | 1.9.8 | 1 | staging => 54
…（无任何 desktop | 2.0.0 行）
```

即我的两个事件**尚未进入标准报表**，属正常处理延迟。因此「GA4 已处理入报表」这一层本次**只在 Realtime 层面得到确认**，标准聚合待 24～48 小时后复核。按契约 §10 的口径，Realtime 只用于排查链路，最终核对要看普通聚合，所以本层不算完整通过。

一并记录两条报表侧的口径问题：

- **F4**：上表 `analytics_schema` 列显示为 `1` 而非契约 §4 要求的 `v1`。根因是**同日变更**：`TelemetryMetadata` 里该值原本是整数 `1`，今天 13:14 的 commit `613ceb3a` 才改成字符串 `"v1"`。所以这不是 bug，但**今天的报表里会同时存在 `1` 和 `v1` 两种值**，做 schema 切片时必须把两者都算上，否则会漏掉当天 13:14 之前的全部数据。
- **F5**：`edition` **没有**注册为 GA4 自定义维度（已注册的 17 个里没有它）。契约 §4 把 `edition` 列为传输键、`Telemetry.desktop.kt` 的注释也说「每个事件带 `edition=desktop` 以便报表区分桌面与移动」——但报表里切不了 `edition`，实际只能靠 `app_platform`（已注册且同样等于 `desktop`）。要么补注册，要么把注释与契约的说法改成以 `app_platform` 为准。

## 6. 未覆盖项与手动步骤

### 6.1 配对后旅程（需人工，约 10 分钟）

用本次打好的包（**不要**用 `/Applications` 里的日常 App，那是 production 环境，事件会落到正式流）：

```bash
APP="/Users/lidapeng/Desktop/Project/app/cc-pocket-worktrees/desktop-ga4-ingress/mobile/composeApp/build/compose/binaries/main/app/CC Pocket.app/Contents/MacOS/CC Pocket"
CCPOCKET_GA4_DEBUG=1 "$APP" 2>/tmp/ga4-paired-journey.log
```

注意这条命令**没有**加 `-Dccpocket.secureStore.file=`，会直接读写 `~/.cc-pocket-app/store.properties`。如果日常 App 正在运行，请先退出它再跑，避免两个实例互相覆盖同一个 store 文件。

依次走完：

1. 用本机 daemon 打印的 6 位码完成配对；
2. 打开一个会话；
3. 发一条提示；
4. 等到回复结束；
5. 断网（关 Wi-Fi）约 30 秒，再联网，等它自己恢复连接；
6. 退出 App。

验证方法：

```bash
grep -E '\[telemetry\]|\[ga4-transport\]' /tmp/ga4-paired-journey.log
```

预期能看到多条 `attempt` ＋ `http_2xx status=202`，且断网期间出现 `io_failed`／`timeout`、恢复后重新变回 `202`。随后用第 5 节的 Realtime 查询按事件名核对：

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  https://analyticsdata.googleapis.com/v1beta/properties/540841272:runRealtimeReport \
  -d '{"dimensions":[{"name":"streamId"},{"name":"minutesAgo"},{"name":"eventName"}],
       "metrics":[{"name":"eventCount"}],
       "dimensionFilter":{"filter":{"fieldName":"eventName","inListFilter":{"values":[
         "paired","connected","session_opened","session_open_result","prompt_sent",
         "prompt_response_result","turn_result","connection_recovery_result","first_value_observed"]}}},
       "limit":50}'
```

只认 `streamId=15754516140` 的行。24～48 小时后再用 `runReport` 按 `customEvent:app_version=2.0.0` 过滤做最终核对——这个版本号目前只有本次这批包在用，是干净的归因钥匙。

### 6.2 其余未覆盖

- 关闭采集／在飞取消／重开不重放的活体验证（需要在 GUI 设置里切开关）。
- 休眠后续帧只出现 `http_other status=0` 的活体验证（需要同进程内的第二个事件，见 4.6）。
- 服务端侧的未知字段、超大请求（>8 KB）、错误令牌、超额请求注入测试。
- 客户端包与服务端日志中「无 secret／无请求体／无越权字段」的系统性审查（本次只确认了包内无 `ga4.properties`）。

## 7. 结论

| 项 | 结论 | 依据 |
|---|---|---|
| 未配对首启事件清单与预期一致 | **通过（但预期需修正）** | 桌面只发 `app_launch`；`onboarding_shown` 桌面不可达（F1），§2 |
| 官方形态 staging 包能构建并含公开入口资源 | **通过** | §3.2，jar 内 `endpoint=https://relay.pairlet.org` |
| 门禁正向放行 | **通过** | `verify_exit=0`，§3.2 |
| 门禁反向拒绝含 `ga4.properties` 的包 | **通过** | `verify_exit=1`，§3.3 |
| 未配对首启真实上报拿到 202 | **通过** | `[ga4-transport] http_2xx status=202`，§4.1 |
| 升级前后匿名身份连续 | **通过（机制层）** | `ga4_client_id` 种子与日常 App 逐字节相同，§4 引言；跨版本活体对比未做 |
| 不可达主机不影响 App | **通过** | `io_failed`，进程存活，§4.2 |
| 429 → 丢弃并休眠 | **通过** | 1 次 `register`、无 `collect`，§4.3 |
| 204 → 休眠 1 小时 | **通过（分类标签有瑕疵）** | 1 次 `register` 后停手；但探针标成 `http_2xx`（F2），§4.4 |
| 非法入口值不会绕回直连 MP | **通过** | 告警后回落资源入口，直连分支不可达（F3），§4.5 |
| 休眠后续帧出现 `http_other status=0` | **未覆盖** | 场景只产生 1 个事件；仅单测覆盖，§4.6 |
| 入口收到（第一层） | **通过** | relay `received`／`register` 各 ＋1 两次，§5.1 |
| 上游 2xx（第二层） | **通过** | relay `accepted`／`upstream:2xx` 各 ＋1 两次，§5.1 |
| GA4 已处理（第三层） | **未覆盖（Realtime 已见，聚合未见）** | Realtime 两个分钟桶各 1 条且流正确；标准报表无 `2.0.0` 行，§5.3 |
| 配对后旅程 | **未覆盖** | 需人工，步骤见 §6.1 |
| 关闭采集／在飞取消／重开不重放 | **未覆盖** | §6.2 |
| 服务端字段／超限／凭据注入测试 | **未覆盖** | §6.2 |

### 登记的发现（只记录，未修）

| 编号 | 内容 | 性质 |
|---|---|---|
| F1 | 桌面端没有任何引导曝光事件：`onboarding_shown` 只在移动端组合可达，桌面未配对走 `ConnectPanel`／`PairingForm`，全程无埋点。#278 那类「有多少没配对的人看过安装引导」的漏斗分析在桌面做不了 | 埋点缺口 |
| F2 | `Ga4TransportProbe` 把 204 归进 `http_2xx`，与 202 同桶，而两者语义相反（丢弃 vs 已接收） | 诊断可读性 |
| F3 | 非法 `CCPOCKET_ANALYTICS_ENDPOINT` 会静默回落到资源入口而非关闭采集。行为符合 §8 优先级，但文档没写明「非法 env 等于没设 env」 | 文档表述 |
| F4 | GA4 报表里 `analytics_schema` 当天同时存在 `1`（13:14 之前）与 `v1`（`613ceb3a` 之后）两种值，切片会漏数 | 数据口径 |
| F5 | `edition` 未注册为 GA4 自定义维度，报表切不了；契约 §4 与代码注释关于「按 `edition` 区分桌面／移动」的说法与报表现状不符 | 契约与配置不一致 |
| F6 | Realtime API 不支持 `customEvent:*` 维度，只能按 `streamId`＋`minutesAgo` 归因；排查手册里应写明这一限制 | 工具限制 |
| F7 | 本机 Homebrew JDK 会让 `createDistributable` 在 `checkRuntime` 直接失败，需命令行传 `-Pcompose.desktop.packaging.checkJdkVendor=false` | 本机构建环境 |
| F8 | 桌面 `SecureStore` 是 `~/.cc-pocket-app/store.properties` 而非系统 keychain；两个 App 实例会互相覆盖写同一文件 | 事实澄清 |
