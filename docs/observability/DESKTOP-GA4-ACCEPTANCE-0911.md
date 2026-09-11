# 桌面端 GA4 入口批次 4 抽样验收（2026-09-11）

本文是 [DESKTOP-GA4-INGRESS.md](DESKTOP-GA4-INGRESS.md) §10「批次 4 验收」的抽样执行记录。只做验证与记录，**未改动任何产品代码**；过程中发现的问题只登记、不修复。

- 执行分支：`feat/desktop-ga4-ingress`，worktree `/Users/lidapeng/Desktop/Project/app/cc-pocket-worktrees/desktop-ga4-ingress`，HEAD `3c0b8687`（提交日期统一重写后的 SHA，树内容与验收时记录的 `14a96a99` 完全相同）。
- 环境：macOS 26（Darwin 25.5.0），JDK `/opt/homebrew/opt/openjdk@17`。
- 所有真实上报均为 **staging** 环境，落 GA4 属性 `540841272` 的 Web 数据流 `G-X04707FM0W`（streamId `15754516140`）。production 流未被触碰。

## 1. 范围与边界

### 覆盖

- 未配对首启的事件预期清单与真实上报（1 次）。
- 发布门禁 `scripts/observability-release-config.py desktop --verify` 的正向通过与反向拒绝。
- 四类传输故障抽样：不可达主机、429、204、非法入口值。
- 三层回执分别核对：入口收到／上游 2xx／GA4 已处理。

### 不覆盖（本次明确不做）

- 配对后旅程（会话打开、发提示、断网重连）——需要人工操作 GUI，步骤见第 6 节。**（补记：已于 2026-09-11 17:28—17:31 由用户亲手补做，结果见第 7 节。）**
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

## 7. 配对后旅程实测（2026-09-11 用户亲手）

§6.1 列为「未覆盖、需人工」的配对后旅程，由用户于 **17:28:41—17:31:58（北京时间）** 亲手走完一遍。本节只做核对与记录，未改动任何产品代码，也未改动服务器配置。

### 7.1 旅程步骤与实际口径

用的是本 worktree 打出的 staging 包，独立 home（`-Duser.home=/tmp/pairlet-journey-home-0911`）、全新未配对身份、`CCPOCKET_GA4_DEBUG=1`，stderr 落 `/tmp/pairlet-journey.log`。

| # | 步骤 | 时间（北京） |
|---|---|---|
| 1 | 启动 staging 包，停 10 秒 | 17:28:41 |
| 2 | 用 6 位码配对本机 daemon，等到已连接 | 17:29 |
| 3 | 选项目目录、新建会话、等就绪 | 17:30 |
| 4 | 发第一条提示，等回复结束 | 17:30 |
| 5 | 关 Wi-Fi 约 20 秒再开，等自动重连 | 17:30—17:31 |
| 6 | 再发一条提示，等回复结束 | 17:31 |
| 7 | （可能）设置 → 关于 → 关闭「共享使用与诊断数据」，5 秒后重开 | 无证据 |
| 8 | 退出 App | 17:31:58 |

### 7.2 第一层：客户端传输日志

`/tmp/pairlet-journey.log` 共 60 行，其中 27 组探针记录。**分类统计**：

| 分类 | 次数 |
|---|---|
| `attempt` | 27 |
| `http_2xx status=202` | 24 |
| `io_failed` | 3 |
| `no_content status=204` | 0 |
| `http_other status=0` | 0 |
| `[telemetry]` 告警 | 0 |
| 其他非预期分类 | 0 |

**分类序列**（前 40 行的原始顺序，`A`＝`attempt`）：

```text
A → 202 → A → 202 → A → 202 → A → 202 → A → 202 → A → 202 → A → 202 →
A → 202 → A → 202 → A → 202 → A → 202 → A → 202 → A → 202 → A → 202 →
A → 202 → A → 202 → A → 202 → A → 202 → A → 202 →              ← 日志第 6—48 行（21 组）
A → io_failed → A → io_failed → A → io_failed →                ← 第 49—54 行（断网窗口）
A → 202 → A → 202 → A → 202                                    ← 第 55—60 行（恢复后）
```

三次 `io_failed` 位于日志第 50／52／54 行，**恰好连续、恰好 3 次、恰好夹在 21 次 202 与 3 次 202 之间**，与第 5 步「关 Wi-Fi 约 20 秒」的位置完全吻合。全程没有 `[telemetry]` 告警，也没有出现 204／`http_other`／`timeout`／`dns_failed`／`tls_failed` 等其他分类。

**关键推论**：`TelemetryDelivery.kt:26` 对发送异常是 `catch (_: Exception) { /* No retry or recursive diagnostics. */ }`，即**失败即丢、不重投、不排队补发**。因此这 3 个事件被永久丢弃，它们正是描述这次断网的那几个事件（见 §7.5 的 F9）。

另外 `Telemetry.desktop.kt` 的 `sendToIngress` 是**一包一请求**（`AnalyticsPacket` 单条成体），所以「202 次数 ＝ 成功投递的事件条数」这一换算成立，这是下面三层对账能逐条对齐的前提。

### 7.3 第二层：relay 计数

只读查询（`journalctl -u cc-pocket-relay`，每 5 分钟一行累计值）：

```text
Sep 11 17:27:30  analytics accepted=3  dropped:no_stream:development=1 received=5  register=3 registered=3 rejected:unauthorized=1 upstream:2xx=3  upstream_ms:lt100=2  upstream_ms:lt500=1
Sep 11 17:32:30  analytics accepted=27 dropped:no_stream:development=1 received=29 register=4 registered=4 rejected:unauthorized=1 upstream:2xx=27 upstream_ms:lt100=24 upstream_ms:lt500=3
Sep 11 17:37:30  analytics accepted=27 dropped:no_stream:development=1 received=29 register=4 registered=4 rejected:unauthorized=1 upstream:2xx=27 upstream_ms:lt100=24 upstream_ms:lt500=3
```

取 17:27:30 → 17:32:30 的增量：

| 计数器 | 增量 | 对照 |
|---|---|---|
| `received` | ＋24 | ＝ 客户端 24 次 202 |
| `accepted` | ＋24 | ＝ 客户端 24 次 202 |
| `upstream:2xx` | ＋24 | ＝ 客户端 24 次 202 |
| `register` / `registered` | ＋1 | 新的 staging 匿名身份首次注册流，各 1 次 |
| `upstream_ms:lt100` / `lt500` | ＋22 / ＋2 | 上游耗时分布 |
| `rejected:unauthorized` / `dropped:no_stream:development` | ＋0 | 无越权、无环境未映射 |

任务要求核对的「增量 ≥ 本次 202 次数」**成立，且是精确相等（24 ＝ 24）**：客户端报成功的每一次上报，入口都收到、都接受、上游都回 2xx，一条不多一条不少。三次 `io_failed` 如预期**没有**在 relay 留下任何痕迹——它们根本没出网。

17:37:30 那行仍是 27，说明 17:32 之后**再没有经 relay 的桌面上报**，这一点在 §7.4 归因噪声时要用到。

### 7.4 第三层：GA4 Realtime

查询时刻 17:37:34，属性 `540841272`，窗口「最近 29 分钟」。

**A. 只按 `eventName`（全属性）**：24 个事件名、全部流混在一起，无法直接归因。**改用 `streamName` ＋ `platform` 拆分**后，桌面流 `Pairlet Desktop · Measurement Protocol`（`platform=web`，即 `streamId=15754516140`）可以单独切出来，另两条 `com.panda.ccpocket`（Android／iOS）是手机 App 流，与本次无关。

**按 `eventName` × `customEvent:app_environment` 的查询按预期报错**，原文照录（复现 §5.3 的 F6）：

```text
Field customEvent:app_environment is not a valid dimension. For a list of valid dimensions and
metrics, see https://developers.google.com/analytics/devguides/reporting/data/v1/realtime-api-schema
```

退回只按 `eventName`（并按 `streamName` 过滤）。另外尝试用 `appVersion` 维度按 §6.1 建议的「`2.0.0` 干净归因钥匙」切分，**同样不可用**：Web／Measurement Protocol 流在 Realtime 下 `appVersion` 全为空串（见 §7.5 的 F10）。

**最终归因口径**：桌面流 ＋ `minutesAgo` 分钟桶。旅程占 17:29／17:30／17:31 三个桶（查询时刻的 8m／7m／6m ago）：

```text
 8m ago（17:29）：app_launch×1, conn_phase×3, connected×1, onboarding_shown×1,
                  pair_failed×1, pair_started×2, paired×1                              = 10
 7m ago（17:30）：feature_exposed×2, feature_used×2, first_value_observed×1,
                  prompt_response_result×1, prompt_sent×1, session_open_result×1,
                  session_opened×1, turn_result×1, value_reached×1                     = 11
 6m ago（17:31）：feature_used×1, prompt_sent×1, turn_result×1                          =  3
                                                                             合计       = 24
```

**24 条，与客户端 24 次 202、relay ＋24 精确相等。**

关于同流噪声（重要口径说明）：桌面流在 17:32 之后以及 17:08—17:25 之间仍有事件（`feature_used`／`value_reached`／`session_opened` 等），但同期 relay 计数**完全没动**。原因是用户日常在跑的 production 桌面 App 仍是**直连 GA4 MP**（本分支的「改走 relay 入口」尚未合 main 发版），它和 staging 包共用同一条 Web 流。所以桌面流里同时混着日常 App 的直连流量与本次 staging 的过 relay 流量；三个旅程分钟桶之所以可以干净归因，靠的是 ①总数与 relay 增量精确相等、②配对类事件（`app_launch`／`onboarding_shown`／`pair_started`／`paired`／`pair_failed`／`connected`）只有全新未配对身份才可能发出。

### 7.5 逐事件「见／未见」

| 事件 | 预期 | 结论 | 计数与分钟桶 |
|---|---|---|---|
| `app_launch` | 步骤 1 | **见** | 1（17:29） |
| `onboarding_shown` | 步骤 1—2（本批次新增） | **见** | 1（17:29） |
| `pair_started` | 步骤 2 | **见** | 2（17:29） |
| `paired` | 步骤 2 | **见** | 1（17:29） |
| `connected` | 步骤 2 | **见** | 1（17:29） |
| `conn_phase` | 步骤 2 | **见** | 3（17:29） |
| `session_opened` | 步骤 3 | **见** | 1（17:30） |
| `session_open_result` | 步骤 3 | **见** | 1（17:30） |
| `prompt_sent` | 步骤 4、6 | **见** | 2（17:30、17:31 各 1） |
| `prompt_response_result` | 步骤 4、6 | **见（只 1 条）** | 1（17:30） |
| `turn_result` | 步骤 4、6 | **见** | 2（17:30、17:31 各 1） |
| `value_reached` | 步骤 4 | **见** | 1（17:30） |
| `first_value_observed` | 步骤 4 | **见** | 1（17:30） |
| `feature_exposed` | 非清单内 | **见** | 2（17:30） |
| `feature_used` | 非清单内 | **见** | 3（17:30×2、17:31×1） |
| `disconnected` | 步骤 5 | **未见** | 0 |
| `conn_failed` | 步骤 5 | **未见** | 0 |
| `connection_recovery_result` | 步骤 5 | **未见** | 0 |

**三个「未见」有唯一自洽解释**：缺的正好 3 个，丢的也正好 3 个（`io_failed`×3），而且位置就在断网窗口。`disconnected`（`PocketRepository.kt:2698`）、`conn_failed`（`:2515`）、`connection_recovery_result`（`:2502`）这三个事件**只会在网络断掉/刚恢复的那几百毫秒内发出**，而那正是上报链路唯一不通的时刻——加上 `TelemetryDelivery` 不重投，它们必然丢失。这不是埋点没打，是传输层设计把「最需要被观测的那一刻」的数据丢掉了。

两条需要记一笔但不影响结论的观察：

- `pair_failed×1`：17:29 桶里出现了 1 条配对失败。与 `pair_started×2` 一致，合理解释是第一次 6 位码输错或已过期、第二次成功。**待用户确认**是否确实试了两次；若不是，需要单独查。
- `prompt_response_result` 只有 1 条而 `prompt_sent`／`turn_result` 各 2 条：第二条提示的 `turn_result` 在 17:31 桶里在（回复确实结束了），但没有配对的 `prompt_response_result`。17:32 桶里有 1 条 `prompt_response_result`，但那个时刻 relay 计数已停在 27 不再增长，所以它是日常 App 的噪声、不是我们的。这条差异**待复核**（可能是两个事件的触发条件本就不对称，也可能是第 7／8 步在它发出前把进程关了）。

### 7.6 Dia 浏览器截图（B 项，未完成）

用户要求「在 Dia 看效果」。Dia 主进程在跑（pid 44123），但**没有开 CDP 端口**：

```text
$ curl -sv http://127.0.0.1:9222/json/version
*   Trying 127.0.0.1:9222...
* connect to 127.0.0.1 port 9222 from 127.0.0.1 port 50031 failed: Connection refused
* Failed to connect to 127.0.0.1 port 9222 after 0 ms: Couldn't connect to server
```

补充核实：`lsof -nP -iTCP -a -p 44123` 对 Dia 主进程**没有任何 LISTEN**，9223／9333／8315 也都不通；`~/.claude/skills/design-run/` 目录在本机不存在，拿不到现成连接代码。按任务约定「若 9222 连不上，记录错误原文并跳过 B」，本项**跳过**，没有尝试重启 Dia（会打断用户正在用的浏览器），也没有输入任何凭据。

截图路径 `/tmp/pairlet-journey-ga4-realtime.png` 与 `/tmp/pairlet-journey-ga4-events.png` **未生成**。需要目视时，请在 Dia 里直接打开：

```text
https://analytics.google.com/analytics/web/#/p540841272/realtime/overview
```

（若之后要让自动化能截图，Dia 需带 `--remote-debugging-port=9222` 启动。）

> **后续更正（18:00 补做）**：9222 后来是通的，本项已在 §7.8 补完，三张截图均已生成。本小节保留原始记录，不要按这里的「未生成」下结论。

### 7.7 本节结论

| 层 | 数字 | 结论 |
|---|---|---|
| 第一层 客户端 | 27 次 attempt ＝ 24×202 ＋ 3×`io_failed` | **通过**，无告警、无非预期分类 |
| 第二层 relay | `received`／`accepted`／`upstream:2xx` 各 ＋24 | **通过**，与第一层精确相等 |
| 第三层 GA4 Realtime | 桌面流旅程三桶合计 24 条、15 个事件名 | **通过**，与前两层精确相等 |

配对后旅程整体判 **部分通过**：配对 → 连接 → 开会话 → 发提示 → 拿回复 → 断网重连 → 再发提示这条完整链路，三层回执逐条对齐，**清单内 13 个事件见到 10 个**；缺的 `disconnected`／`conn_failed`／`connection_recovery_result` 三个是断网期上报丢失所致（F9），属真实缺口而非验收失误。另外本批次新增的桌面 `onboarding_shown` **实测可达**，§5 记录的 F1（「桌面端没有任何引导曝光事件」）**已被本批次修复**。

标准聚合层仍未覆盖：本次包 `app_version=2.0.0`，需 24～48 小时后用 `runReport` 按 `customEvent:app_version=2.0.0` 过滤做最终核对。

第 7 步（关闭采集／重开）**证据不足，仍判未覆盖**：日志末尾是连续 3 次 202 后直接结束，没有出现「一段无 `attempt` 的空窗后重新出现 `attempt`」的形态。这与代码行为一致——关闭采集只是取消在飞请求并清空队列，本身不产生任何探针记录；若用户是在 17:31:30 之后、没有新事件要发的窗口里做的这一步，日志就必然什么都看不到。要活体验证这一项，需要在关闭期间刻意制造一个事件（例如切一次屏），确认它没有被补发。

### 7.8 Dia 目视核对（18:00—18:14 CST 补做）

§7.6 记的「Dia 没开 CDP 9222，B 项跳过」已不成立：18:00 复查时本机 CDP 调试端口 9222 可连，于是把目视核对补上。全程只读，未输入任何凭据，未保存任何 GA4 配置（比较对象只「应用」未「保存」，页面事后已关闭）。

**先说时效，这决定了这一节能证明什么**。旅程发生在 17:29—17:31，GA4 Realtime 只保留最近 30 分钟：

| 目视时刻 | Realtime 覆盖窗口 | 旅程三桶是否还在 |
|---|---|---|
| 18:00（截图 1） | 约 17:30—18:00 | 勉强在窗口末尾，17:29 桶已滑出 |
| 18:05（抄事件名） | 约 17:35—18:05 | 已滑出 |
| 18:14（截图 2／3） | 约 17:44—18:14 | 已滑出 |

所以本节**只能佐证「事件名与量级」与「桌面流可分离」**，无法复核 §7.4 的逐分钟桶归因。§7.4 的 24 条／15 个事件名仍以当时（17:37 查询）的 Realtime API 结果为准。

#### 三张截图

| 路径 | 内容 | 截取时刻 |
|---|---|---|
| `/tmp/pairlet-journey-ga4-realtime.png` | 实时概览全页（无比较对象） | 18:00 |
| `/tmp/pairlet-journey-ga4-events.png` | 「按事件名称划分的事件数」卡片，三列并排：所有用户／`app_platform` 比较（失败态）／网站流量 | 18:14 |
| `/tmp/pairlet-journey-ga4-user.png` | 「查看用户概况」单用户事件流 | 18:14 |

#### 页面读数

**18:00 实时概览（截图 1）**：过去 30 分钟活跃用户 8、过去 5 分钟 2；「带来用户首次互动的来源」`(direct)` 6；事件卡片 #1 是 `conn_failed` 56（14.93%）。

这个 `conn_failed` 56 正好印证了任务前提：**事件卡片默认混了所有平台**。§7.5 里桌面流的 `conn_failed` 判「未见（0）」，页面上却是全属性第一名——这 56 条来自手机 Firebase 流，与桌面无关。

**18:05 全属性事件名（共 18 个，翻 3 页抄全，含手机流）**：

```text
session_opened 29   feature_used 25          value_reached 22
user_engagement 21  conn_phase 19            session_open_result 17
app_launch 15       connected 14             conn_failed 11
prompt_sent 10      session_start 8          screen_view 7
background_task_result 6                     prompt_response_result 5
turn_result 5       app_update 1             file_view_result 1
onboarding_shown 1
```

#### 桌面流分离：一次失败、一次成功

**失败路径 —— 自定义维度**。按 `app_platform` 建比较对象是可以建出来的（值域实测只有 `ios`／`desktop`／`android` 三个），条件摘要显示 `app_platform 完全匹配 'desktop'`，但一「应用」，每张卡片这一列都返回：

```text
此比较对象无法应用于实时数据。
```

即 **Realtime 不支持自定义维度比较对象**，这与 §7.4 记录的 `customEvent:app_environment` 在 Realtime API 报「not a valid dimension」是同一个限制的 UI 表现。截图 2 中间那一列保留了这个失败态，留作判例。

**成功路径 —— 内置预设「网站流量」**（定义为 `设备类别 完全匹配 'desktop'`）。这个比较对象在 Realtime 下**可用**，桌面流被干净切出来：18:05 同期只有 9 个事件名：

```text
feature_used 19            value_reached 17          session_open_result 14
session_opened 14          prompt_response_result 4  prompt_sent 4
turn_result 4              background_task_result 3  file_view_result 1
```

对照全属性的 18 个，桌面列里**完全没有** `user_engagement`／`conn_phase`／`app_launch`／`connected`／`conn_failed`／`session_start`／`screen_view`／`app_update`／`onboarding_shown`——这些在该窗口内全部来自手机流。到 18:14 截图那一刻，「所有用户」已涨到 21 个事件名（#1 `feature_used` 20），「网站流量」仍是 9 个（#1 `feature_used` 20），两列的 `feature_used` 相等，说明该窗口的 `feature_used` 100% 是桌面的。

**口径提醒**：「网站流量」用的是**设备类别**而不是**数据流**，与 §7.4 用的 `streamName`＋`platform` 不是同一把尺子。它会把「任何桌面设备上的访问」都算进来，只是本属性里除了桌面 App 的 MP 打点之外没有别的桌面来源，才恰好等价。写结论时不要把这两个口径混用。

#### 与 Realtime API 结果是否一致

**不一致，且这是预期内的**。§7.4 的「24 条、15 个事件名」是 17:29—17:31 三个分钟桶的桌面流切片；本节看到的是 17:35—18:14 滑动窗口的全部流量（含手机流与用户日常直连 GA4 的 production 桌面 App，见 §7.4 的同流噪声说明）。两者窗口不同、口径不同，数字本就不该相等。

可以对上的是**定性关系**：

- §7.4 判「见」的桌面事件里，`session_opened`／`session_open_result`／`prompt_sent`／`prompt_response_result`／`turn_result`／`value_reached`／`feature_used` 七个，在本节的桌面流列里**全部仍在**，说明这些事件名确实从桌面流持续到达 GA4。
- §7.4 判「未见」的 `conn_failed`，在本节桌面流列里**依然是 0**（全属性的 11 条全在手机流），与 F9「断网期上报丢失」的结论不冲突。
- 配对类事件（`pair_started`／`paired`／`pair_failed`／`onboarding_shown`）在本节窗口里桌面列为 0，符合预期——它们只在全新未配对身份首启时发出，18:00 之后没有再做配对。

#### 「查看用户概况」：进去了，但拿不到本次旅程的直接证据

右上角「查看用户概况」可以进（URL 落到 `.../realtime/usersnapshot`），截图已存 `/tmp/pairlet-journey-ga4-user.png`。**但没能找到 17:29—17:31 的 web 用户**，原因有三：

1. **时间已滑出**。18:14 时窗口是 17:44—18:14，旅程用户根本不在候选集里。
2. **筛不了平台**。页面自己写着「用户概况只能按地理位置和应用版本缩小范围」——没有按平台／数据流筛选的入口，比较对象也不作用于这个视图。
3. **翻页拿不到新用户**。用「下一位用户」箭头连翻 10 次，稳定落在同一个用户上：过去 30 分钟总计 4 个事件，`app_launch`／`screen_view`／`session_start`／`user_engagement` 各 1，用户属性带 `first_open_time`——这是**手机 Firebase 流**的典型形态，不是桌面 MP 流。

截图里那位用户的事件流时间轴显示 `02:49`—`03:11`「上午」，这是**属性时区（美西）**下的显示，对应北京时间 17:49—18:11。后续任何人比对这张图的时间戳，记得换算，不要当成 CST。

**结论**：本项**未取得**目视级的单用户旅程证据，且以 Realtime 现有能力**无法补取**（窗口已过，且不支持按平台筛用户概况）。要拿到这类单用户证据，只能在旅程发生的 30 分钟内实时截图，或改用 BigQuery Export／`runReport` 的用户级查询。

#### 本节小结

| 项 | 结果 |
|---|---|
| 截图 | 3 张全部生成 |
| 桌面流分离 | **成功**（内置「网站流量」＝设备类别 desktop）；自定义维度 `app_platform` 路径**失败**（Realtime 不支持） |
| 事件名核对 | 全属性 18 个、桌面流 9 个，已全部抄录 |
| 与 §7.4 的 24 条／15 个事件名 | **数字不一致，属预期**（窗口与口径均不同）；定性关系可对上 |
| 单用户旅程目视证据 | **未取得**，且无法补取 |

## 8. 结论

| 项 | 结论 | 依据 |
|---|---|---|
| 未配对首启事件清单与预期一致 | **通过（F1 已被本批次修复）** | §2 当时桌面只发 `app_launch`；本批次新增的桌面 `onboarding_shown` 已在 §7.5 实测可达 |
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
| GA4 已处理（第三层） | **Realtime 通过，标准聚合仍未覆盖** | 旅程 24 条事件在桌面流三个分钟桶内全部见到、15 个事件名，与 relay ＋24 精确相等（§7.4）；标准聚合仍无 `2.0.0` 行，待 24～48 小时后用 `runReport` 复核（§5.3、§7.7） |
| 配对后旅程 | **部分通过** | 三层回执精确对齐（24 ＝ 24 ＝ 24）；缺 `disconnected`／`conn_failed`／`connection_recovery_result` 三个断网期事件，§7 |
| 关闭采集／在飞取消／重开不重放 | **未覆盖（旅程第 7 步佐证不足）** | 日志无「空窗后重新出现 `attempt`」形态；关闭采集本身不产生探针记录，需刻意在关闭期制造事件才可验，§7.7 |
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
| F9 | 断网期间的上报**永久丢失**：`TelemetryDelivery.kt:26` 对发送异常是 `catch (_: Exception)`，不重投也不排队补发。后果是 `disconnected`／`conn_failed`／`connection_recovery_result` 这三个**只在断网那一刻发出**的事件，恰好是唯一发不出去的事件——「连接可靠性」这条漏斗在客户端侧结构性缺数（§7.2、§7.5） | 观测盲区 |
| F10 | Realtime 下 Web／Measurement Protocol 流的 `appVersion` 维度**全为空串**，§6.1 建议的「用 `app_version=2.0.0` 做干净归因钥匙」在 Realtime 层不可用；叠加日常 production 桌面 App 仍直连 MP、与 staging 共用同一条 Web 流，桌面流天然混着两路流量。目前只能靠 `minutesAgo` 分钟桶 ＋ relay 计数交叉归因 | 工具限制 ＋ 归因口径 |
