# 桌面端 GA4 分析入口契约（v1）

日期：2026-09-11。状态：**批次 1 定案**，是 [需求](DESKTOP-GA4-REQUIREMENTS.md) 的实现契约；relay 与桌面端实现以本文为准，改动契约先改本文再改代码。

## 1. 宿主与路由

- 入口跑在 **relay 同一进程**（Ktor，`RelayServer`），路径前缀 `/v1/analytics/`，与 `/v1/pair/*`、`/v1/daemon`、`/v1/device` 平级。Caddy 现有 catch-all 反代即可到达，不新增 systemd 单元。
- 选同进程而非独立服务的理由：relay 机器只有 1.8 GB 内存，多一个 JVM 是显著成本；隔离靠下面三条硬约束而不是靠进程边界。
  1. 上游 GA4 转发用独立的有界并发（信号量 8），拿不到许可立刻回 `503 saturated`，不排队、不持久化。
  2. 限流键独立命名空间（`analytics-register:*`／`analytics-collect:*`），与配对／认证限流互不影响。
  3. 单次请求体 ≤ 8 KB（Caddy `request_body` 与 relay 双重上限），事件 ≤ 10 条／请求。
- 入口**不触碰** E2E 帧、Broker、账号或设备表；不读任何会话正文。它只是一个无状态 HTTP→MP 的窄转发器。
- 版本：入口版本在路径 `/v1/` 与请求体 `"v":1`；产品事件 schema 仍是 `analytics_schema=v1`，两者独立演进。

## 2. 配对前接入控制：匿名安装令牌

未配对的桌面用户也要覆盖（引导漏斗），所以不能复用配对凭据。方案是**无状态 HMAC 安装令牌**：

```text
POST /v1/analytics/register
{"v":1,"install_id":"<GA4 client_id 形态：[0-9]{1,20}\.[0-9]{1,20}>"}
→ 200 {"token":"<opaque>","expires_in":86400}
```

- 令牌明文结构 `1.<install_id>.<exp_epoch_seconds>.<hmac_sha256_hex_32>`，整体 base64url；服务端只校验 HMAC、有效期与 install_id 一致性，**不存任何注册记录**。
- HMAC 密钥来自 `CCPOCKET_ANALYTICS_TOKEN_KEY`（hex，≥ 32 字节）；未配置时每次启动随机生成，relay 重启后旧令牌全部 `401`，客户端重新注册即可。密钥可轮换，无需更新客户端。
- 这**不是**用户认证，也不防伪：安装 ID 可重置。它提供的是：（a）`collect` 的按安装限流键由服务端验签而非客户端自报；（b）新身份的产生速率被 `register` 的按 IP 锁定限流封顶；（c）随时可整体作废。
- 明确禁止复用：E2E 私钥、配对 ticket／PSK、设备凭据、Sentry token、GA4 secret。

## 3. 上报请求与响应

```text
POST /v1/analytics/collect
Authorization: Bearer <token>
Content-Type: application/json
{
  "v": 1,
  "client_id": "<须等于令牌内 install_id>",
  "session_id": "<[0-9]{1,20}，进程级会话桶键>",
  "events": [ { "name": "<事件名>", "params": { "<key>": <string|integer> } } ]   // 1..10 条
}
```

| 状态 | body | 含义 | 客户端动作 |
|---|---|---|---|
| 202 | `{"accepted":n}` | 上游 MP 已返回 2xx（**只是传输回执，不是 GA4 已处理**） | 无 |
| 204 | 空 | 入口关闭，或该环境没有映射数据流，事件被计数后丢弃 | `register` 收到 204 → 本进程休眠 1 小时不再尝试 |
| 400 | `{"error":"<code>"}` | `bad_request`／`invalid_event`／`invalid_param`／`too_many_events`／`platform_mismatch` | 丢弃该批，不重试 |
| 401 | `{"error":"unauthorized"}` | 令牌缺失／过期／验签失败／install_id 不匹配 | 重新 `register` 一次；再失败则休眠 10 分钟 |
| 413 | `{"error":"too_large"}` | 请求体 > 8192 字节 | 丢弃 |
| 429 | `{"error":"rate_limited"}` | 任一限流命中 | 丢弃，休眠 10 分钟 |
| 502 | `{"error":"upstream_failed"}` | GA4 超时／5xx／网络错 | 丢弃 |
| 503 | `{"error":"saturated"}` | 上游并发满 | 丢弃 |

客户端**永不重试同一批**（沿用「允许丢失」语义），因此不存在重复事件问题；未来若加重试须先在本文定义幂等键与退避。

## 4. 字段白名单与校验

单一事实源：`observability` 模块 `AnalyticsCatalog`（commonMain），relay 校验与桌面端枚举都对它做测试对齐，新增 `TelEvent`／`TelKey` 时测试会红。

- **事件名**：必须 ∈ `AnalyticsCatalog.events`（即 `TelEvent.id` 全集）。
- **参数键**：必须 ∈ `AnalyticsCatalog.params`（`TelKey.id` 全集）∪ 传输键 `edition`、`app_version`、`session_id`。
- **服务端自置、客户端值被覆盖或拒绝**：`engagement_time_msec` 固定 `"100"`（兼容值，不是活跃时长）；`debug_mode` 客户端**禁止**发送（出现即 `invalid_param`），只有服务端 `CCPOCKET_ANALYTICS_DEBUG=1` 且事件环境为 staging 时由服务端加上。
- **值规则**：字符串 ≤ 64 字符且匹配 `[A-Za-z0-9_.:+/ -]*`；数字必须为整数且 |n| ≤ 10^12；布尔／对象／数组／null 一律拒绝（客户端已把布尔编码为 1／0）。`app_version` 匹配 `[A-Za-z0-9][A-Za-z0-9_.+-]{0,95}`。
- **必填与固定值**：`app_platform` 必须为 `desktop`（本入口只服务桌面；其他值 `platform_mismatch`）；`edition` 必须为 `desktop`；`analytics_schema` 必须为 `v1`；`app_environment` ∈ {production, staging, development, unknown}。
- 不接受任何自由文本键；prompt、路径、会话名、账号、业务会话 ID、诊断 trace ID 没有对应键，无法通过校验。不新增设备指纹字段。

## 5. 环境路由与数据流凭据

- 服务端配置每个环境一条数据流：`CCPOCKET_ANALYTICS_STREAM_PRODUCTION=G-XXXX:<api_secret>`、`CCPOCKET_ANALYTICS_STREAM_STAGING=G-YYYY:<api_secret>`。事件按其 `app_environment` 选流；`development`、`unknown` 或未配置的环境**丢弃并计数**（响应 204），正式流永远收不到开发探针。
- 客户端自报环境只决定落哪条流，是统计字段不是鉴权；防污染靠限流、`internal_traffic` 维度与报表口径，不靠环境字段。
- `CCPOCKET_ANALYTICS_ENABLED=true` 是总开关；关闭或一条流都没配时两个端点均回 204。开关与流配置在 relay 启动时读取，改动后 `systemctl restart cc-pocket-relay` 生效（连接会短暂断开并自动重连）。关闭后 relay、Sentry、桌面核心功能不受影响。
- secret 只存在于 `/etc/cc-pocket-relay/analytics.env`（systemd `EnvironmentFile`），不进仓库、构建日志、artifact，不进客户端。

## 6. 配额、超时与保存期限

| 项 | 值 |
|---|---|
| `register` 按 IP | 10 次／分钟，超限进入递增锁定（复用 `RateLimiter` lockout） |
| `register` 全局 | 600 次／分钟 |
| `collect` 按安装（验签后的 install_id） | 120 次／分钟 |
| `collect` 按 IP | 300 次／分钟（NAT 后多安装） |
| `collect` 全局 | 3000 次／分钟 |
| 上游并发 | 8 在飞；超出 503 |
| 上游超时 | 连接 3 s，整请求 5 s；JDK `java.net.http.HttpClient`，不新增依赖 |
| 令牌有效期 | 24 h |
| 客户端队列 | 32 条、串行一条在飞、请求超时 10 s（沿用 `TelemetryDelivery`） |
| IP 保存 | 只在内存限流桶，空闲 1 h 被 sweep；不写日志、不落盘 |
| 原始事件 | 服务端不持久化；转发完成即丢 |

## 7. 可观测性边界（服务端）

- 只记计数：`received`、`accepted`、`rejected:<code>`、`dropped:disabled`、`dropped:no_stream:<env>`、`upstream:2xx|4xx|5xx|timeout|error`、`saturated`，以及上游延迟直方桶。每 5 分钟打一行汇总日志。
- 不记录：请求体、Authorization、完整 IP、含 secret 的 MP URL、上游响应体。
- 上报失败**不**产生 Sentry 事件（沿用 `TelemetryDelivery` 的「不递归诊断」原则）；服务端上游错误也只计数。

## 8. 桌面端配置与优先级

- 官方包资源 `cc-pocket-analytics.properties`：`endpoint=https://<入口主机>`（只含 scheme＋host，无路径／查询），客户端拼接 `/v1/analytics/register`、`/v1/analytics/collect`。生产默认入口是 relay 的 HTTPS 源站（当前 `https://pocket.ark-nexus.cc`）。
- 环境变量 `CCPOCKET_ANALYTICS_ENDPOINT` 覆盖资源。
- 优先级：入口（env > 资源）> 直连 MP（仅当**没有**入口配置且环境 ≠ production；即本机私测用 `ga4.properties`／`CCPOCKET_GA4_*`）> 关闭。官方包由门禁保证只含入口资源、不含 `ga4.properties`，所以不可能绕回直连。
- 事件触发点、`TelemetryDelivery` 队列／开关／代际语义、`ga4_client_id` 种子与 `ga4ClientId()` 转换、首次价值标记、`sessionId`：**全部不变**，只换出口。
- 采集关闭：不注册令牌、不发事件、清队列、取消在飞；重开不重放。令牌本身与身份等价于安装种子，关闭时不需要额外清除，但重开会重新注册。

## 9. CI 与发布门禁

| 配置 | 位置 | 进桌面包 |
|---|---|---|
| `PAIRLET_ANALYTICS_ENDPOINT` | Repository Variable，正式包 | 是（`cc-pocket-analytics.properties`） |
| `PAIRLET_ANALYTICS_ENDPOINT_PREVIEW` | Repository Variable，release-preview staging 包 | 是 |
| `CCPOCKET_ANALYTICS_STREAM_*`、`CCPOCKET_ANALYTICS_TOKEN_KEY`、`CCPOCKET_ANALYTICS_ENABLED` | relay 机 `/etc/cc-pocket-relay/analytics.env` | 否 |

- `scripts/observability-release-config.py` 扩展：`desktop` 组件同时 stage／verify 分析入口资源；verify 要求 jar 内**恰好一份** `cc-pocket-analytics.properties`、内容等于 `endpoint=<变量>`、变量匹配 `https://[a-z0-9.-]+`，且仍拒绝 `ga4.properties`。
- 这两个仓库变量**尚未创建**；创建前桌面发版会在门禁失败。这是刻意的：与 DSN 同一姿态，缺配置宁可失败也不静默发无采集的包。

## 10. 批次 4 验收（未做，留待真实部署）

- 服务端部署与 `analytics.env` 提供；relay 重部署（`scripts/redeploy-relay.sh`）。
- 一个真实桌面包走：未配对启动／引导 → 配对 → 会话成功 → 一次失败或恢复 → 首次价值 → 一个功能采用事件；对照 GA4 普通聚合中的 `desktop`／版本／schema／coverage／环境切片。
- 抽样：关闭采集、在飞取消、重开不重放、离线／超时／429、升级前后 `client_id` 连续。
- 分别记录「入口收到」「上游 2xx」「GA4 报表可见」三个层次；MP 2xx 不等于已处理。
