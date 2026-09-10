# Pairlet 事件与指标字典

状态：A–D 连续实现中，2026-09-10。结果事件已加入工作区，尚未执行本轮编译、测试或实际包验收；下文触发定义描述实现意图，最新逐项证据见 IMPLEMENTATION。

## 1. 数据分工与结果来源

- Firebase Analytics / GA4：用户主动行为、业务结果、激活、采用和复用。保留现有 Telemetry API；结果摘要不继承 Sentry 的 1% 成功日志采样。
- Sentry：固定错误分类、安全栈、阶段、耗时和连接证据。App、daemon、relay 独立上传；服务事件不计为用户操作，relay 不读取业务 trace。
- 成功由业务状态确认，两个 SDK 只接收同一份结果，不能各自根据“已排队上传”判断业务成功。诊断上报失败也不能改变业务结果或自动重试业务请求。

## 2. 本批公共维度

适用于三端通过 `Telemetry.track` 明确发送的自定义事件；不宣称 Firebase 自动事件也带这些字段。以 `analytics_schema=1` 筛选新口径，历史无字段的事件保留旧口径。

| 字段 | 值与来源 | 使用边界 |
|---|---|---|
| `analytics_schema` | 当前 1；平台出口固定写入 | 不等于 Sentry 的 diag_schema，不用于推定结果事件已接入 |
| `app_platform` | ios / android / desktop | 由平台出口决定，业务参数不能覆盖 |
| `app_environment` | development / staging / production / unknown | 复用显式 Sentry 环境配置；缺失/非法配置按 unknown，不猜成 production |
| `internal_traffic` | 1 / 0 / unknown | development/staging 和 Android debuggable 标 1；显式 production 默认按发布流量标 0，desktop 可用 CCPOCKET_ANALYTICS_INTERNAL 覆盖为 1/0；缺少环境配置为 unknown。这是构建流量声明，不识别具体人的身份 |
| `usage_mode` | own / shared / demo / real / unknown | demo=1 或 demo_entered 为 demo；新结果和已取得绑定角色的配对/连接入口声明 own/shared；旧已核查 Demo 标记的意图事件仍可为 real；未取得角色的配对尝试不猜 own，共享入口分支从 paired 起 |
| `demo` | 保留原来的 1 / 缺省语义 | 不追溯补改旧事件，不把全局 app_launch 无 demo 标记等同真实会话使用 |
| `tool` | 内置工具允许表；自定义/MCP 名称为 other | 允许 Bash、Read、Write、Edit、MultiEdit、Glob、Grep、Task、WebFetch、WebSearch、NotebookEdit、AskUserQuestion、ExitPlanMode、EnterPlanMode、TodoWrite；不发送自定义原名 |

`source/transport/resume/decision/phase/reason/attempt/link/retried/version/entry_point/help_task/target/value` 沿用原业务含义。后续事件优先使用固定枚举；现有 reason 仍可能是异常类名，补结果事件时归一为固定原因码，不扩展为异常 message。不新增项目名、会话名、路径、内容、账号或跨设备身份维度。

正式分析限定明确 production，并排除已知内部流量和 Demo；内部身份 unknown 的样本单列覆盖，不声称已精确剔除所有内测安装。旧埋点、自动事件和新自定义事件不能不加区分地合并漏斗。

## 3. 现有事件的真实语义

主要入口：`mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/PocketRepository.kt`；引导与帮助事件来自对应 UI。平台转换在 Telemetry 的 actual 实现中。

| 事件 | 触发与含义 | 不能证明 |
|---|---|---|
| app_launch | App 进入主界面生命周期埋点 | 新安装、已连接或有效使用 |
| onboarding_shown / onboarding_cta | 引导呈现 / 具体引导控件操作 | 已读懂说明或完成电脑安装 |
| demo_entered | 进入演示分支 | 已配对或真实使用 |
| pair_started / paired / pair_failed | 配对尝试 / 当前流程成功 / 当前流程失败 | 后续连接或会话能够使用；多次尝试不是多个人 |
| connected / disconnected | 当前逻辑的连接就绪 / 主动断开入口 | 所有断开均已覆盖；历史首屏可见 |
| conn_phase / conn_failed | 连接阶段变化 / 一次连接尝试失败 | 用户操作最终失败；直连回退成功不计两次失败 |
| session_opened | 发出 OpenSession 之前记录打开意图 | SessionLive、历史完成或实际布局成功 |
| session_open_timeout | 打开确认超时提示出现，含 link/retried | 完整历史加载超时或根因已确认 |
| prompt_sent | 发提示或运行终端命令前的意图 | 已写到 Agent、已收到响应或任务完成；当前还混含终端命令 |
| prompt_turn_stalled / prompt_turn_queued | 看门狗无输出提示 / 已知排队 | 长任务失败；排队不是内部错误 |
| prompt_resent | 用户手动重新发送 | 重新发送已经成功 |
| approval_shown / approval_decided | 审批呈现入口 / 用户裁决意图 | 裁决已经发送并应用 |
| help_opened / help_support_opened / help_task_opened / help_guide_opened / help_direct_action | 帮助入口、任务与指南等固定操作 | 问题解决或用户满意 |

原事件名称和触发点保持原语义；上表列既有事件，新结果事件见下节。`prompt_sent` 的终端分支不进入 Agent 响应分母。

## 4. 后续结果事件契约

下列结果入口已加入 B/C 工作区；集中验证和正式报表回执仍待完成。一个操作生成随机诊断上下文，不拿业务会话 ID 或账户 ID 作为观测编号。

| 事件 | 唯一结果来源与阶段 | 去重与覆盖 |
|---|---|---|
| session_open_result | 本次打开实际历史应用/首次展示，或明确失败、超时、取消、未知 | 一次尝试一个主结果；SessionLive 仅绑定；新旧双方能力决定 coverage，旧端没有完成信号不直接报错 |
| session_open_recovered | 同一失败/超时尝试后来达到完成标准 | 独立一次恢复，不覆盖原超时，不重发创建请求 |
| prompt_response_result | 同一提示首次真实 Agent 响应，或明确失败、超时、取消/未知 | ACK、排队、工具事件、首个响应按后端定义；终端命令单独分流，重试有独立尝试 |
| prompt_response_recovered | 同一失败/超时提示后来收到首个真实输出 | 最多一次恢复，不修改主结果或重发提示；正常后台 waiting 不计失败恢复 |
| turn_result | 对应 turn 的终态 | 协议完成不等于 AI 正确解决需求；没有终态且无退出证据为 unknown |
| approval_apply_result | 对应裁决被 Agent 实际应用或确认失败 | 点击/发送/应用分别观察，用户拒绝是正常业务结果，等待不算内部故障 |
| connection_recovery_result | 本轮恢复操作达到可继续使用的状态，或失败/取消/未知 | 直连→relay 是同一恢复；后台挂起、切电脑、旧代际回包不能污染本次结果 |
| feature_exposed / feature_used | 固定五功能实际可用且呈现 / 用户主动使用 | 按实际曝光会话去重，不按 Compose 重组计数；完成复用对应业务结果 |
| value_reached | 用户主动操作成功看到内容、收到真实 Agent 响应或完成已应用审批 | 每种成功动作一次；排除 demo/internal、后台心跳、自动重连、无人查看的后台任务 |
| first_value_observed | 首次观测到 value_reached 后，独立持久标记认领成功 | 每个 Analytics 身份/环境/own 或 shared 分支一次；升级、重开 App、换功能不重置。是本版开始后的首次观测，不宣称首次安装或历史首次使用 |

Analytics 结果字段为 result、reason、coverage、duration_ms 与固定 platform/backend/usage_mode 维度；result 取 success/failure/timeout/cancelled/unknown/waiting。Sentry 分别使用 operation/stage/outcome/code/result_quality，不将两套字段名混用。恢复另记，不修改主结果。操作类型、阶段和后端词表沿用当前实际能力，不接受任意 CLI 输出作为维度。

同一范围内的全部有效结果样本构成成功率分母；取消/未知/结果缺失分别展示。整体尝试完成率要先确认同一尝试群组及观察窗的结果覆盖。Sentry 采样错误数不能作为全部 Analytics 尝试的失败分子。

## 5. 五视角的指标定义

| 视角 | 主要指标 | 分母/窗口与限制 |
|---|---|---|
| 首次价值 | 配对/连接至首次有效使用的到达与转化、耗时 | 自有电脑、共享、Demo 分支分开；新事件首次观测不等于新安装 |
| 核心完成 | 成功/失败/超时/取消/未知、耗时与恢复 | 同操作/平台/能力版本、同尝试群组；缺报和排队独立显示 |
| 连续稳定 | 恢复成功、耗时、恢复后再次失败、受影响安装数 | 用户主动关闭与不可恢复断开分开；服务日志不进入用户数 |
| 功能采用/复用 | 可用/曝光→尝试→完成→复用 | 会话查看/续接、提示、审批、文件查看、后台/定时任务；不可用平台/后端不进入采用分母 |
| 留存/版本 | 每周有效使用安装数，首次有效使用后的 D1/D7/周复用 | 只用成熟群组；写明报表时区、窗口、采集与版本覆盖；版本/失败群组比较是相关性，不自动推定因果 |

没有账号或跨端身份拼接。各平台按参与采集的安装级伪匿名口径分析，不能将手机与桌面相加称为去重真人；重装、重置 Analytics 数据和关闭采集都会影响覆盖。

首次有效使用留存以 `first_value_observed` 纳入、`value_reached` 返回。普通 value_reached 会把同一安装计入多个日期群组，不能代替首次价值群组。首次标记只保存最多 12 个固定环境/内部流量/角色布尔值，后台有界队列认领、原子写入后才发送；磁盘失败或损坏不产生新的首次事件。Android/iOS 在关闭并重置 Analytics 身份时清空独立标记；desktop 现有 client_id 不变，因此关闭/重开不重置标记。标记落盘不保证供应商收到，杀进程或队列/网络丢弃会造成群组覆盖不足，不能补造遗漏用户。测试和真实上报仍待完成。

## 6. 发送、关闭与验收

- desktop：自有队列最多 32 条、同时一条请求；开关切换会清空队列并取消当前请求。关闭→重开不发送旧代际事件；发送失败不重试、不递归上报。网络已经接收的内容不保证撤回。
- Android/iOS：沿用原生 Firebase SDK 发送；关闭时停采集，调用 Analytics 数据重置与 Crashlytics 未发送报告删除 API。实际网络/缓存删除与原生初始化次序仍须实际包验收，不能只凭 API 调用判定全部边界通过。
- 桌面固定 `engagement_time_msec=100` 仍只保留兼容，不能计算真实使用时长；MP 纯上报的标准报表能力须实际校验。client_id 来源是本地随机安装 UUID，现按官方严格校验要求确定性转换为两个无符号十进制数；本地种子和首次价值标记保留，不进行跨端合并。旧 wire 字符串如曾被供应商接收，也不能假定会自动并入新字符串。
- 手机设置入口为“设置 → 支持与关于 → 共享使用与诊断数据”，原生桌面端为“设置 → 关于”。两处复用同一开关逻辑，同时控制当前 App Analytics 与诊断；daemon 单独设置，预算计数文件在关闭时保留，不含身份/事件内容。
- 测试观察 tap 只看原业务参数，不是供应商实际 payload 的证明；新维度测试使用统一转换器，正式验收还须核对真实后台字段与可查询视图。

每批记录：事件/场景、实际入口、版本和平台、开始/结果/恢复的预期数、正常/取消/迟到/重试对照、DebugView 接收、正式查询、Sentry 分类及关联范围。GA4 和 Sentry 回执及视图尚待本批实际包验证；没有数据的留存结论标为待观察。


## 2026-09-10 ABCD 实现补充

统一增加 `app_version`（由 App 编译常量决定、出口限制字符集，业务参数不能覆盖）。结果事件的 `usage_mode` 为 own/shared/demo；旧意图事件的 real 不能反推出具体所有权。

| 新入口 | 实际结果来源 | 覆盖限制 |
|---|---|---|
| SessionOpenObservation | 匹配 SessionLive → HistoryComplete → 合并 → 非零真实布局 | 无完成能力的旧端为 unknown；收到 Live 不等于已展示。15 秒只记录诊断，不重发业务请求 |
| PromptOutcomeTracker | PromptProgress 的消费、首输出和终态；原业务 receipt watchdog；实际消费后的独立响应期限 | 缺 ACK 超时不证明 Agent 未执行；已 ACK 排队或等待恢复不直接算失败；人工重发的新 promptId 是新尝试；同 promptId 换 convo 只增加诊断 attempt；后台挂起记 waiting |
| ApprovalOutcomeTracker | 现有裁决回调返回或抛错 | 当前后端没有 Agent 应用 ACK，adapter_returned / gate_resolved 为 unknown/partial，不派生 approval 有效使用 |
| FileViewObservation | 内容/分片合并后，当前可见渲染面真实布局 | 文本、图片和有效 diff 可证实查看；HTML 缺浏览器加载回执、二进制导出卡不是已查看文档，均 unknown/partial；混合诊断 context 的分片降级 partial |
| BackgroundOutcomeTracker | 本连接观察过 RUNNING，再收到 Workflow/Job 终态 | source=workflow/agent_job 分开，耗时从首次观测起算，coverage=partial；终态历史重放不重复记新完成；调度派发和提供商接收均不派生用户价值 |
| ProductFeatures | 当前可用入口实际呈现、用户主动操作 | feature_exposed 每进程/feature/usage_mode 去重；feature_used 按操作。复用从安装与观察窗计算，不靠本地累计次数 |

value_reached 目前由会话内容布局、提示输出实际可见、文件可渲染内容布局产生。后台收到数据、自动恢复、纯 ACK、审批裁决返回或成功派发都不能替代它。文件失败卡本身不算有效使用。

结果分布必须同时展示 success/failure/timeout/cancelled/waiting/unknown。完成率仅在定义明确的可判定结果内计算，并同时列该分母占所有主结果的比例。不要通过剔除 unknown 后只显示一个看似很高的成功率。每个 operation 的 ProductOutcome 只发一个主结果；session_open_recovered 是独立恢复事件。
