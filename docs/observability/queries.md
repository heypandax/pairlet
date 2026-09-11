# Pairlet 查询与证据手册

配置检查点：2026-09-11。**当前已保存五个正式视角与两个 staging 验证页，实际分流查询通过，生产样本与成熟留存仍待观察**；详见 [本轮配置和汇总](evidence/2026-09-11-ga4-sampling.json)。区分保存配置、已接收样本与已验证业务旅程；现有新 daemon 日志、Android Errors/Logs、iPhone 12 Logs 和 desktop GA4 staging 启动/连接恢复结果回执，逐组件边界见 [ACCEPTANCE](ACCEPTANCE.md)。字段的业务含义以 [EVENT-CATALOG](EVENT-CATALOG.md) 为准。

历史查询补充：GA4 报告时区实测 GMT+08:00，Dia 为 America/Los_Angeles，必须把测试 UTC 时间换算成报告日期后再选范围。合并前 Android 1.9.8 开发包已经包含新上报；查询 **2026-09-11** 已见会话结果 5 条、文件查看结果 2 条、有效使用 4 条，详情见 ACCEPTANCE 最新节。使用内置“平台”与“应用版本”先核对入库样本；新自定义维度的值选择器不完整时，不把过滤后的空表当成事件未收到，也不直接认定参数链路已通过。原“手机后台尚未确认”的段落属于此前检查点。

## 入口与已经保存的看板

最新 Android 修复包抽样：在下方 GA4 DebugView 中，9 月 10 日 19:38:07（Dia 时区）的 `session_open_result` 已核实 success / complete / android / 2.0.0 / staging / 1501ms；19:40:55 的 `file_view_result` 为 success / complete / 1410ms，并有 `value_reached` 的 feature=file_view。DebugView 的事件节点可能将时间戳附在事件名后，自动化应按名称文本节点及相邻时间共同选择，避免误点同名旧样本。正式探索与留存仍独立验收；详见 ACCEPTANCE 首节。

- [Sentry：Pairlet 核心诊断 · 样本与证据](https://pairlet.sentry.io/dashboard/10014172/)，四个 widget 已保存并回读。默认跨项目、跨环境、最近 24 小时；排查前选择发生问题的平台项目、环境与时间窗。
- [Sentry Issues](https://pairlet.sentry.io/issues/) 查询 Errors；[Sentry Logs](https://pairlet.sentry.io/explore/logs/?project=4512060691185664) 查询 Logs。首次进入可能需要选择已上报的具体项目。
- [GA4 管理](https://analytics.google.com/analytics/web/#/a392220252p540841272/admin)：现有 Firebase 项目 `cc-pocket-1b3ea`，账号 `392220252`，媒体资源 `540841272`；不另建数据孤岛。
- [GA4 产品观察探索](https://analytics.google.com/analytics/web/?authuser=3&hl=zh-CN#/analysis/a392220252p540841272/edit/33G_k3j-Q4mpRDgdoZt6HA)：五个正式标签与两个验证标签已保存并回读。日常分析用前五个，查看效果先选“验证 · 结果与价值（staging）”：已见 34 条历史桌面事件和完整结果/覆盖/平台/版本字段。留存已改为首次有效使用到再次有效使用；正式群组暂无符合条件的样本，staging 只有 1 个 day 0 身份，不能解释为正式留存或零故障。
- 看历史手机样本：[Android 布局超时](https://pairlet.sentry.io/issues/7724619605/?project=4512060708749312)、[Android 连接异常](https://pairlet.sentry.io/issues/7724622744/?project=4512060708749312)、[Android Logs](https://pairlet.sentry.io/explore/logs/?project=4512060708749312&statsPeriod=24h)、[iOS Logs](https://pairlet.sentry.io/explore/logs/?project=4512060701343744&statsPeriod=24h)。历史日志选 staging / 1.9.8；Android 日志编号 ce016d96c1dc45299d988b77a9c11012，iOS 编号 0557d66e911a4695a137e5f5a7cfeec6。新布局错误也包含 2.0.0。已定位并修复 Android 多根实例/生命周期串用，不据此自动关闭所有历史布局错误。
- 较长会话历史样本：[attach 超时](https://pairlet.sentry.io/issues/7724675302/?project=4512060708749312)，12012ms，trace `5a3e5390353446b9ba4b9c31b34d5f74`。最新回读仍为旧 1.9.8 的一条上报，修复包未复现；现为非阻塞观察项，新上报或实际复现才继续分析，不声称已经证明旧根因。

桌面 MP 配置检查发现此前仅有 iOS/Android 数据流，且本机未配置有效 MP 凭据。现已创建并回读 `Pairlet Desktop · Measurement Protocol` 网站类型数据流 `15754516140`，Measurement ID 为 `G-X04707FM0W`，增强型衡量关闭。用户确认后已创建 `Pairlet Desktop · local validation` 密钥，保存至 gitignored 的本机 `ga4.properties`（0600），并安装包含新配置和 client_id 修复的桌面开发包；未向官网安装网页代码。密钥值不写入文档或 Git，当前是本机开发接入，不代表公开发行配置已验收。

确认回执：2026-09-10 用户明确“继续上面 GA4，我确认”，随后在 Dia 9222 的现有 GA4 页提交《用户数据收集确认书》；页面创建入口解除禁用，密钥创建后表格回读成功。这项前置条件已解除，不再等待重复确认。

官方 `/debug/mp/collect` 的严格校验发现 UUID 形式的 client_id 返回 VALUE_INVALID，明确要求数字.数字。现保留本地安装 UUID，将完整两个 64 位数转换为无符号十进制；已有数字格式保留，损坏值不生成新身份，首次价值标记不清空。转换后以本机安装标识校验通过；这是 wire 格式修复，不声称 GA4 会把曾经使用不同 wire 字符串的旧数据自动合并。

实时查看：[GA4 DebugView](https://analytics.google.com/analytics/web/?authuser=3&hl=zh-CN#/a392220252p540841272/admin/debugview/overview) 与 [实时概览](https://analytics.google.com/analytics/web/?authuser=3&hl=zh-CN#/a392220252p540841272/realtime/overview)。受控 `observability_validation` 探针标记 staging/internal/unknown，属于配置验证，不计业务成功或首次价值。HTTP 204 与 debug 校验通过不能代替后台实际收到；当前回执状态见 ACCEPTANCE 最新节。纯 MP 的报表能力按 [官方限制](https://developers.google.com/analytics/devguides/collection/protocol/ga4#full_server-to-server) 实测，不预设完整用户/留存支持。

桌面开发包的实际发送可通过进程环境变量 `CCPOCKET_GA4_DEBUG=1` 核验：只在 development/staging 配置生效，给请求附加 debug_mode，并向 stderr 输出固定状态与 HTTP 数字码。请将 stderr 重定向到私有本机文件；不记录请求/响应正文、身份或异常原文。关闭本次进程后正常启动即可恢复默认。当前已取得真实桌面 HTTP 204，但后台回执仍须单独核对，无需在 Dia 关闭内容拦截。

| 已保存 widget | 数据集 / 聚合 | 查询 | 能解释的范围 |
|---|---|---|---|
| 会话打开异常样本（非总体失败率） | Errors / count() | `error_path:EP-10 !code:smoke_test` | 被接收的会话操作异常 |
| Agent 执行异常样本 | Errors / count() | `error_path:[EP-16,EP-17,EP-18,EP-19] !code:smoke_test` | 启动、提示、协议、终态的异常样本 |
| Relay 连接日志样本 | Logs / count(logs) | `component:relay error_path:EP-08` | 连接、替换、关闭等样本；不是操作失败率 |
| 已接收诊断日志量（非本地丢弃量） | Logs / count(logs) | `has:diag_schema` | 后台收到的诊断日志；本地 suppressed/dropped 另读健康汇总 |

查询语法已由当前 Sentry 编辑器接受并保存。新字段的实际生产数据仍须在新包验收时逐项展开核对。未创建通知、定时任务或告警接收者。

## 最近五分钟打不开会话

1. 先记录发生时间、时区、平台、App/daemon 版本与现象。将 Sentry 时间窗设为最近五分钟；随后扩大至一小时检查迟到。发生时间和后台首次可查询时间是两列证据。
2. 错误样本查询 `error_path:EP-10`；Logs 补查 `error_path:[EP-09,EP-10,EP-11,EP-12,EP-13,EP-14,EP-15]`。选中正确环境，排除 `code:smoke_test`。
3. 用户在“诊断共享”设置复制最近诊断编号后，按 `diag_event_id:<32位编号>` 查询 Errors 和 Logs。此按钮只证明本地生成/接受记录，不保证 Sentry 已接收。
4. 从具体事件取得 `diag_trace_id`，在 App 与 daemon 项目分别查 `diag_trace_id:<编号>`。按 occurred timestamp 与步骤耗时排列 receive/read/encode/write/apply/layout。同一操作两端各一条不是两次失败。
5. 连接证据用 `connection_id`、`peer_connection_id`。relay 编号每个 socket 随机生成，重连更换；客户端的 Attached / PeerPresence 记录将本端与对端区分。relay 不收到业务 trace，不做精确操作归因。
6. 写出“最后已证实阶段 / 缺失阶段 / 可能原因 / 验证动作”。缺 layout 不自动说明历史过大，缺日志不证明没有问题。旧双方未协商、关闭采集、采样、限频、离线、强杀都可能缺证据。

新原生手工 spans 只覆盖四类操作并做低比例采样。`diag_trace_id` 的日志关联可独立使用；仅见 Sentry 自动 Trace Preview 不证明跨端 spans 已成立。

## GA4 的五个视角

App 的整数事件参数不会解析为事件自定义维度，见 [Google 官方说明](https://support.google.com/analytics/answer/14239696?hl=en)。公共出口已从整数 `1` 修复为字母数字字符串 `v1`；历史空 schema 不能靠等待回填。现有八页中的“核对 · 手机历史（不筛 schema）”独立保留受限 staging 手机样本，不放宽正式生产/留存的 schema 约束。查询日期需包含媒体资源 UTC+08 的事件日期；浏览器本地日期较早时，探索页面可能截断结束日期，必须核对实际发送的日期范围。

新结果报表统一筛选 `analytics_schema IN ("1", "v1")`、已验收版本、`app_environment=production`、`internal_traffic=0`，再分别看 `app_platform` 和 `usage_mode`。staging/internal/demo 单独做验收页。旧 `real`、缺参数及 `unknown` 单列，不能推成自有电脑。GA4 用户指标按参与采集的安装身份解释，不称去重真人。

| 视角 / 查询口径 | 条件和维度 | 分母与观察窗 | 解释限制 |
|---|---|---|---|
| 首次价值 | pair_started → paired → connected → value_reached；分别按 own/shared 分支；demo 独立 | 同一安装、同一预定观察窗完成各步骤；事件量漏斗与安装漏斗分别标注 | pair_started 未取得绑定角色时为未知入口；角色分支从 paired 起。既有安装的首次观测不是新安装 |
| 核心结果 | session_open_result、prompt_response_result、turn_result、approval_apply_result、file_view_result、background_task_result；行 event_name/result/coverage | 每一种结果事件分别计数；有结论比例 `(success+failure+timeout)/全部结果`，故障比例 `(failure+timeout)/(success+failure+timeout)`，须分 coverage | waiting/unknown/cancelled 不进入失败分子。缺少终态的意图不凭空补为 success；Sentry 样本不加入分子 |
| 连续使用 | connection_recovery_result、session_open_recovered、prompt_response_recovered；result/reason/platform/backend | 主结果和恢复次数分别列；duration_ms 表示该次观测耗时 | 回退成功不算两次故障。后台等待与前台超时分开 |
| 功能采用 / 复用 | feature_exposed、feature_used、value_reached；feature × platform × usage_mode | 曝光安装、尝试安装、达成价值安装分别去重；周复用要求两个不同日期的有效使用 | 曝光是实际呈现入口的进程内去重事件，不能对事件量直接求安装转化。审批/后台缺少真实应用证据时不补 value |
| 留存 / 版本 | first_value_observed 为纳入条件，value_reached 为返回；app_version/platform 固定切片 | D1/D7 使用同一媒体资源时区的自然日，周有效安装按自然周；D7 只用已经过七天的群组 | 是新观测群组，不是安装群组；desktop MP 的 first-touch 不作为安装时间；版本与失败相关不证明因果；无成熟样本写待观察 |

已经存在 reason/source/transport/phase 等维度。本轮登记 analytics_schema、app_platform、app_environment、internal_traffic、usage_mode、app_version、result、coverage、feature、backend；duration_ms 为毫秒事件指标。未登记业务 ID、诊断 ID、路径或 prompt。GA4 新维度通常需 [24–48 小时](https://support.google.com/analytics/answer/14240153?hl=en) 才能在报表中使用，不能追溯把旧缺参事件补齐。保存五个探索视图及受控旅程回执仍须继续取证。

duration_ms 必须筛选同类结果后再计算。平均操作耗时用总耗时除以该类结果数，不能用固定 engagement_time 表示活跃时长，也不能将累计耗时直接叫 P95。需要分位数时使用真实逐样本数据；不要付费开通 BigQuery 来掩盖当前口径缺口。

留存配置已完成：纳入/返回分别为 first_value_observed/value_reached，每日、标准；通过事件细分同时限制 production、internal=0、schema IN (1,v1)、own/shared。自由表格的统一过滤器不会进入群组查询，不可仅写 unifiedFilters 就认为过滤生效；多条件应在界面显示“并且”，不能是“或”。实际正式群组无符合条件的样本，staging 首日有 1 个身份；未成熟日期不计算留存率。GA4 会将符合条件的用户分配到所有适用群组，且按设备身份计算，参见 [官方群组说明](https://support.google.com/analytics/answer/9670133?hl=en)。

## 告警配置草案（未启用）

Errors 先按项目/production/新 issue 合并，持续异常按稳定指纹和时间窗聚合，接收者待具体启用时记录。阈值在首个真实七天基线之后制定，不从合成 smoke 次数推导。relay 健康独立于 Sentry 采集健康；见 [RELEASE](RELEASE.md)。告警状态恢复须与故障触发一起验收。
