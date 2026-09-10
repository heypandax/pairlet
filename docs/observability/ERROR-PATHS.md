# 核心出错路径与开发任务

状态：开发中，首批入口与本地验证见 [实施进度](IMPLEMENTATION.md)；2026-09-09。方案核查基线：`c6bf15ab`，开发基线：`6ca60173`。[实施主方案](../design/OBSERVABILITY.md) 的字段、隐私、预算和协议边界继续适用。

本文将原先“四类 Analytics 失败”扩展为 **30 类核心路径、10 个开发任务**。路径编号是设计范围，不是已覆盖数量；每条路径内仍须逐个验证故障分支和正常对照。正文中的错误码为拟新增诊断码，不代表代码或 Sentry 已存在。

启动后的任务总入口为 [后续计划](FOLLOW-UP-PLAN.md)：技术范围沿用本目录，另将 [产品分析稿](PRODUCT-INSIGHTS.md) 的五视角、结果事件、事件字典、GA4 视图与 Sentry 查询纳入 A–D 交付。执行顺序与完整完成标准以该总计划为准；只完成本目录的技术捕获点不能视为产品分析已交付。

## 1. 当前代码暴露出的诊断缺口

这些证据说明应该在哪里补诊断，不等于已经认定它们是线上反馈的原因，也不应简单删除现有容错。

| 当前入口 | 已核查行为 | 要补的区分 |
|---|---|---|
| [RelayE2EConnection][relay-e2e] 收包、[DirectE2EConnection][direct-e2e] 收包、[DeviceSessions.transport][device-sessions] | 部分 `decodeFromString` 失败后 `getOrNull()` 丢弃 | 未知消息兼容降级、已知消息损坏、连接尚有效但业务消息无法应用 |
| [TranscriptScanner.scan/summarize][scanner] | 文件摘要失败跳过，无法解析的行继续处理 | 合法无可展示会话、部分扫描失败、整个项目读取异常；结果为空不等于读取成功 |
| [SessionArchive.load/persist][archive] | 读取失败回退为空；写失败只记本地 warn | 文件不存在、存储损坏、写入失败、成功使用后备结果 |
| [FileChunkAssembler.add][file-assembler] | 身份或序号不连续时 reset，部分输入返回 null | 合法旧请求分片、当前传输缺片、仍在等待、真正完成 |
| [AgentProcess.launchPumps][agent-process] | stdout 读取异常可被按关闭处理；stderr 也有容错 | 正在主动退出的管道关闭、进程仍存活却丢失读写通路 |
| [Conversation 的 fan-out sink][conversation] | 向各订阅者发送使用独立 `runCatching` | 一个已离线订阅者失败、目标请求者发送失败、所有订阅者发送失败 |
| [SchedulerService.runLoop/checkDue][scheduler] | tick 失败记本地日志；fire 异常转结果文本；错过窗口单独跳过 | 调度循环故障、任务派发失败、预期错过窗口、派发成功但执行未完成 |
| [CodeHighlight.highlightCodeOrNull][highlight] | 超长、未知语言、半段代码及解析失败均可回退为普通文本 | 正常展示降级与导致内容完全不可用的异常，不把每次高亮回退报成 issue |

## 2. 每条路径统一交付什么

每条路径增加固定 `error_path=EP-xx`，复用 `operation/stage/code/outcome`。局部读取失败但业务有结果时使用 `result_quality=complete/partial/fallback/unknown`，并记录有界 `failed_count/total_count`；不要通过伪造 success/failure 或直接上传异常字符串表达降级。

一次操作应能回答：是否开始、最后成功阶段、在哪个边界失败、是否返回给调用方、是否恢复。无异常但无响应也要覆盖；“未知终态”与“已经确认超时”分开。仅使用已有业务超时或单独定义的诊断期限，后台挂起、人工审批等待、正常排队不能共用一个固定无响应阈值。

记录分工：

- 捕获处保留脱敏原始栈和稳定原因码；调用方记录结果。通过同一操作上下文关联，同组件同错误只选择一个边界创建主 issue，其他层补阶段，不层层重新包装/上报。
- 没有可靠请求上下文时降级到连接/进程；在解码之前拿不到业务类型就记 `unknown`，不能再次解析敏感 payload 来猜所属任务。
- 正常拒绝/重试/回退用计数或有限日志；真正异常和持续不可恢复的业务失败才占错误额度。新增路径仍共用主方案的配额，不能每新增一条就增加每日预算。
- 每个任务检查涉及的 `catch`、`runCatching`、`getOrNull`、`getOrDefault`、`trySend`、超时和异步子协程出口，逐处判定是否需要证据；不得全仓机械替换成 `captureException`。
- 增加观测不自动重发 prompt、批准操作、修复文件、重启服务或改变旧消息的幂等规则。发现业务缺陷时明确记录，并用独立修复与回归验证处理。

下面每行包含“故障注入 → 应出现的证据；正常对照 → 不应出现的误报”。这些是开发验收任务，不是本次已执行的测试。

## 3. 核心出错路径目录

### 3.1 启动、链路、协议

| 编号 | 入口与出错分支 | 最小证据 / 建议 code | 故障注入与正常对照 |
|---|---|---|---|
| EP-01 | [DaemonCore][daemon-core]、[daemon Main][daemon-main]、[ServiceInstaller][service]：配置读取、监听端口、服务启动/重复实例冲突 | 启动阶段、安装类型、端口类别、实例状态；`daemon_boot_failed`、`listener_bind_failed`；SDK 未初始化时保留安全本地启动记录，下次成功启动仅在允许采集时补报 | 测试进程占用临时端口/配置损坏，指出启动阶段；正常重复执行幂等安装不是新故障，不能为测试再起生产 daemon |
| EP-02 | 进程入口、[DesktopCrashGuard][desktop-crash]、[Conversation][conversation] 子协程：未捕获异常、后台工作协程意外结束、非正常退出 | component、固定 coroutine_role、退出阶段、原始安全栈、是否主动停止；`async_worker_failed`、`previous_exit_unclean` | 注入子协程异常，父流程仍可记录其失败；取消/退出不报 error。强杀后的标记只证明退出不洁，不推断 OOM |
| EP-03 | [RelayE2EConnection][relay-e2e]、[DirectE2EConnection][direct-e2e]、[RelayClient][relay-client]：DNS、TLS、连接超时、直连失败与回退 | transport、dial/attach 阶段、固定网络原因、attempt、恢复结果；`transport_connect_failed` | 模拟 DNS/TLS/拒绝连接，区分原因；直连失败但 relay 成功只记恢复，不将一次逻辑操作算两次失败；不上传 URL/IP |
| EP-04 | [Pairing][pairing]、[RequestRouter][router]、[RelayServer][relay-server]：无效/过期/已撤销凭据、限权拒绝、配对持久化失败 | 阶段、允许范围内的 refusal_code、请求耗时、最终结果；`pair_redeem_failed`、`credential_rejected` | 模拟正常过期与服务端内部错误，前者是拒绝日志，后者保留栈；不记录票据、公钥或账号 |
| EP-05 | E2E 收发适配边界、[DeviceSessions][device-sessions]：握手/确认超时、连续 open 失败、过时连接数据 | handshake/key_confirmation/decrypt 阶段、连续失败次数、连接代际判定、恢复次数；`e2e_handshake_timeout`、`e2e_receive_unusable` | 模拟确认未到或持续不可解密，报告最后阶段；单个重复/旧帧按原防重放规则处理，不自动断言密钥损坏或安全攻击 |
| EP-06 | App 两类 E2E 连接、[DeviceSessions.transport][device-sessions]、[WsConnection][ws]：Envelope/Frame 解码失败或兼容丢弃 | sender/receiver 版本、plane、schema 类别、已有字节数、固定解析码；`protocol_decode_failed`、`protocol_unsupported` | 分别注入已知类型的损坏结构、未知新类型；前者可形成故障，后者按兼容降级；未解码成功前只做连接级关联，禁止上传原 JSON |
| EP-07 | [PocketRepository.send][repository]、[ChannelDrain][drain]、连接 writer：outbox 堵塞、关闭、写失败、回退/重连迁移 | queued/socket_written、允许表内 message_kind、队列长度/等待时间、去重/迁移计数；`outbox_write_failed`、`outbox_wait_timeout` | 模拟写端阻塞/队列关闭，明确是未写出；重连列表去重符合旧规则，prompt/审批/上传不被当成幂等请求丢弃，不改变业务重试 |
| EP-08 | [RelayServer][relay-server]、[Broker][broker]：心跳超时、superseded、限流、转发失败/对端离线 | 两侧连接编号、关闭主动方与固定原因、连接存活时间、发送失败计数；`relay_forward_failed`、`relay_replaced_repeatedly` | 用隔离连接模拟连续替换/限流；一次正常替换只记日志。detached 只是结束现象；连接关联不能声称对应某条业务密文 |

### 3.2 项目、会话、历史与界面

| 编号 | 入口与出错分支 | 最小证据 / 建议 code | 故障注入与正常对照 |
|---|---|---|---|
| EP-09 | [DirectoryService][directories]、[TranscriptScanner][scanner]、[RequestRouter.emitSessions][router]、App 列表请求：目录不可读、摘要失败、返回丢失、部分结果 | directories/sessions、scanned/failed/returned 数量、耗时、result_quality、返回是否已应用；`project_scan_failed`、`session_list_partial` | 一个 fixture 文件损坏与整个目录不可读分别验证；合法空目录和被权限/能力过滤的条目不记成扫描失败，部分失败不伪装“全无会话” |
| EP-10 | [SessionRegistry][registry]、[Conversation][conversation]、[RequestRouter][router]：创建/恢复/观察绑定、工作目录无效、backend 不可用、恢复目标不存在 | open_kind、backend、peer_version、binding 阶段、固定拒绝码；`session_bind_failed`、`session_resume_rejected` | 新建失败、恢复不存在分别定位；已知不支持后端为明确业务拒绝。诊断不得给新建增加自动重试，防止重复创建 |
| EP-11 | [TranscriptReplay][replay]、[ReplaySlice][replay-slice]、[ObserveSession][observe]：历史读取失败、损坏行、live 后读取异常 | read/parse、观测文件字节/行数、跳过原因计数、是否部分成功；`history_read_failed`、`history_parse_partial` | 不可读文件、损坏中间行、live 后失败分别验证；写入中的不完整尾行和空历史按既有语义处理，不一律报错 |
| EP-12 | [ReplaySlice][replay-slice]、[Conversation][conversation]、[ChatTranscript][transcript]：首屏预算/截断、分页游标、完成标记缺失 | requested/replayed 数量、budget_hit、completion_kind、是否尚有历史、阶段耗时；`history_page_failed`、`open_confirm_timeout` | 大历史触发预算、分页缺回复/完成标记分别验证；预算内合法截断、游标追平、新会话不报丢失；游标值不上传 |
| EP-13 | [DeviceSessions][device-sessions]、[SessionFilesService][session-files]、[Conversation][conversation] 发送：编码过大、加密/写入异常、fan-out 部分失败 | 复用现有实际 encoded_bytes、frame_limit、目标数/失败数、encode/seal/write 阶段；`payload_encode_failed`、`payload_send_failed` | 构造高转义/多字节的大历史、目标写入失败；能区别编码超限与网络断开，单个失联订阅者不算所有客户端失败；不重复序列化或在加密锁内上传 |
| EP-14 | [PocketRepository][repository]、[SplitPanes][panes]、[ChatTranscript][transcript]：路由归属、历史 merge、状态应用失败、旧 attempt 回包 | dispatch/merge/applied、有效代际判定、已有消息数/增量数、归属匹配结果；`history_apply_failed`、`reply_unmatched` | merge 抛错、双分屏与迟到回复分别验证；旧机器/已关闭 pane 的丢弃是正常，不把原始 convoId/pane 身份上传，也不能合并到当前任务 |
| EP-15 | [CodeHighlight][highlight]、[ImageDecode][image-decode]、文件/历史视图：内容解析、图片解码、资源预算与首次展示失败 | content_kind、大小分桶、parser/layout 阶段、fallback_used、前后台；`content_decode_failed`、`view_apply_failed` | 破损图片/解析器异常保留安全现场；半段代码、未知语言、超长高亮回退仅计数。history_applied 不等于像素可见；首次布局必须用真实布局回调，不用全局 catch 吞掉 Compose 崩溃 |

### 3.3 Agent、执行与审批

| 编号 | 入口与出错分支 | 最小证据 / 建议 code | 故障注入与正常对照 |
|---|---|---|---|
| EP-16 | 各 AgentLauncher、[AgentProcess][agent-process]、[Conversation][conversation]：可执行文件缺失、启动失败、启动后无输出、恢复会话锁冲突 | agent/version、spawn/bootstrap/init 阶段、saw_stdout、退出码、已知拒绝类别；`agent_spawn_failed`、`agent_bootstrap_failed` | fixture launcher 缺失/退出/存活无输出；用户未安装、不支持平台和明确登录拒绝分开。已有本地/E2E stderrDiagnostic 不可原样上传 Sentry；未知错误只用类型/退出码 |
| EP-17 | [PocketRepository][repository]、[Conversation][conversation]、[AgentProcess][agent-process]、Backend：prompt 入队/写入、ACK、首个 turn 输出 | queued/written/acked/turn_started、当前是否排队/审批中、attempt、既有截止时间；`prompt_delivery_failed`、`prompt_start_timeout` | 模拟 stdin 关闭、ACK 丢失、ACK 后无 turn；三种阶段区分；长任务排队与正在等审批不报吞消息，也不因监控自动重发 |
| EP-18 | [CodexBackend][codex]、[ClaudeBackend][claude]、[OpenCodeBackend][opencode] 等解析器与 [AgentProcess][agent-process] pumps：RPC/流事件解析、schema 漂移、reader/writer 意外关闭 | agent/version、允许表内 method/event_kind、pending_count、pump_role、退出状态；`agent_protocol_failed`、`agent_io_failed` | 注入未知事件、已知事件损坏、活进程 I/O 抛错、关闭期间管道异常；兼容未知事件与取消不报内部错误；只报本项目适配栈，不报 CLI 正文 |
| EP-19 | [Conversation][conversation]、Backend：执行中退出、未收到终态、终态冲突、中断/重启恢复 | turn_phase、last_progress_age、process_alive、exit_code、interrupt_requested、recovery；`turn_ended_unexpectedly`、`turn_terminal_missing` | 模拟进程死于 turn 中、正常结束、中断、受控 relaunch；有明确退出而缺终态才能下强结论。持续流式输出/长工具执行不因耗时长被判失败 |
| EP-20 | [ApprovalCoordinator][approval]、[PermissionBridge][permission]、App 审批卡：请求投递、卡片呈现、用户裁决、裁决送达/应用、超时/撤销 | 来源、submitted/emitted/visible/verdict_received/outcome_applied、等待类别、既有 deadline/lease、固定结果；`approval_delivery_failed`、`approval_apply_failed` | 判定回调抛错、重复/迟到 verdict、撤销与重连恢复待审批；用户拒绝/不回答/正常超时是业务结果，不自动报 crash；诊断不能延长租约或改变批准权限 |

### 3.4 文件、持久化、后台与协作

| 编号 | 入口与出错分支 | 最小证据 / 建议 code | 故障注入与正常对照 |
|---|---|---|---|
| EP-21 | [SessionFilesService][session-files]、[FileExportService][exports]、[FileChunkAssembler][file-assembler]：读取/授权、下载分片、缺尾片/乱序、接收后解码 | file_kind、declared/received_bytes、expected/received_chunks、read/transfer/assemble、result_quality；`file_read_failed`、`file_transfer_incomplete` | 当前请求缺片/断连、文件消失、读取 I/O 失败；主动关闭预览、旧读取分片、权限拒绝不当作内部异常；不记录文件名/路径/内容 |
| EP-22 | [FileInboxService][inbox]、[UploadReassembler][upload]：上传 Base64 错误、预算拒绝、乱序缓存满、临时文件/最终落盘失败 | upload/decode/assemble/write/commit、字节数、分片数、fixed reject_code；`upload_decode_failed`、`upload_commit_failed` | 错误 Base64、断片、模拟写满、最终 rename 失败；合法重复片不重复提交/上报，用户取消不报错，磁盘满使用测试文件系统/注入器 |
| EP-23 | [SessionArchive][archive]、[ScheduleStore][schedule-store]、[ReviewStore][review-store]、[SqliteRelayStore][relay-store] 等存储边界 | store_kind、read/parse/write/commit、schema_version、fallback_used、缓存来源；`state_read_failed`、`state_commit_failed` | 数据损坏、只读/模拟空间不足、原子替换失败；首次无文件与空数据正常。区分“UI 已变”和“持久化完成”，测试不覆盖真实用户数据 |
| EP-24 | [SchedulerService][scheduler]、[BackgroundJobRegistry][jobs]、[WorkflowTracker][workflows]：tick、派发失败、任务启动/进度/终态、重启后的状态恢复 | scheduler/workflow/background_job、due/start/dispatch/terminal、delay、result_source、固定状态；`schedule_dispatch_failed`、`background_terminal_missing` | 注入 fire 抛错、dispatch 后失败、正常晚唤醒错过窗口、重启恢复；fire 返回成功只证明派发完成，不能标成任务执行成功；未观测到终态标 unknown，不能猜任务已挂 |
| EP-25 | [PeerInboxClient][peer-inbox]、[ReviewService][review]、[HandoffService][handoff]：落 outbox、连接/发送、ACK、重试/过期、撤销/召回 | operation、persisted/sent/transport_ack/business_ack/applied、attempt、deadline、授权状态类别；`peer_delivery_failed`、`handoff_transition_failed` | 丢 ACK、收到后未保存、断网重试、重复消息、撤销/召回时失败；offline/retry_pending 不等于最终失败；发送成功不等于对方已接受评审/交接 |
| EP-26 | [GitService][git]、请求结果消费：仓库解析、git 子进程、worktree 操作、结果应用 | 允许表内 verb、phase、exit_code、耗时、是否冲突/取消；`git_process_failed`、`worktree_action_failed` | 临时仓库内模拟锁冲突、命令启动失败、已存在 worktree、合并冲突；正常 conflict 为业务结果，禁止上传命令参数、分支名和 diff，也不自动解决冲突 |
| EP-27 | [UpdateChecker][update-check]、[UpdateService][update]、[ServiceInstaller][service]：检查版本、下载、校验、解包、切换、服务重启 | install_kind、from/to release、download/verify/extract/switch/restart、verification_result、启动版本；`update_verify_failed`、`update_restart_unconfirmed` | fixture 工件校验不匹配、解包/切换失败；区分 verified/skipped/failed，不能把跳过校验记成功验证；安装完不等于新进程健康，下一启动关联只用安全随机操作编号 |
| EP-28 | [PushController][push-controller]、[PushService][push]、[PushSender][push-sender]：系统权限、注册、目标缺失、发送、失效 token 清理 | platform、permission_state、target_count、provider_result、pruned_count、失败连续次数；`push_send_failed`、`push_registration_failed` | 模拟永久失效与临时发送失败；用户关通知/无 token 为覆盖限制，provider accepted 不能标设备已展示；不上传 token/title/body |

### 3.5 生命周期与诊断系统自身

| 编号 | 入口与出错分支 | 最小证据 / 建议 code | 故障注入与正常对照 |
|---|---|---|---|
| EP-29 | [AppLifecycle][lifecycle]、[PocketRepository][repository]、[SplitPanes][panes]：后台/唤醒、换电脑、重连恢复、恢复任务异常或重复收集 | foreground/background、generation_match、resume/reconnect/resubscribe 阶段、活动任务计数；`foreground_restore_failed` | 后台长时间挂起、切电脑后旧响应、新连接恢复；后台暂停不计算业务超时，旧任务按既有取消规则退出；记录缺失只能称恢复未证实 |
| EP-30 | 新 Diagnostics/Sentry sink、各平台初始化：配置缺失、SDK 失败、脱敏丢弃、限频、429、离线/队列满、关闭采集、缺符号 | config_state、transport_state、sampling/suppressed/dropped、cache_usage、release、symbol_state；`diagnostics_unavailable` 等仅本地固定码 | fake transport 注入 429/异常、队列满、开关切换，主业务仍成功；失败只记本地有界健康计数，恢复时有限汇总，禁止调用失败的 sink 递归报错；缺云记录不能当作零业务故障 |

## 4. 纳入当前开发计划的任务

这些任务属于本期 P0–P3，包含审批、文件、存储和后台任务的关键失败路径，不再以“以后其他模块复用”代替交付。P1 先做端内阶段/错误；P2 再补可靠跨端上下文和其余路径。没有新协议时保留 client_only，不拖延已有捕获点的接入。

| 任务 | 责任范围 / 路径 | 批次与依赖 | 明确交付 | 当前状态 |
|---|---|---|---|---|
| OBS-01 公共诊断与 SDK | observability、平台 sink；EP-30 | P0→P1；起点 | schema 中加入 error_path/result_quality；隐私、预算、scope 并发、SDK 实际 envelope 与云端证据 | 开发中，详见 [实施进度](IMPLEMENTATION.md) |
| OBS-02 启动与传输边界 | daemon Main/Core、service、连接、relay；EP-01–08 | P1，依赖 OBS-01；P2 增强连接关联 | 异步失败/网络/鉴权/E2E/解码/outbox/relay 的分类和正常对照；安全/协议变更专项评审 | 开发中，详见 [实施进度](IMPLEMENTATION.md) |
| OBS-03 项目和会话完整路径 | App data、router、registry、disk history；EP-09–14 | P1 端内证据→P2 跨端与完成标记，依赖 OBS-01/02 | 从列表扫描到历史应用；部分结果、空结果、首屏/分页、fan-out 和分屏；复用会话场景附录 | 开发中，详见 [实施进度](IMPLEMENTATION.md) |
| OBS-04 Agent 运行边界 | AgentProcess、全部已支持 Backend/Launcher、Conversation；EP-16–19 | P1，依赖 OBS-01；P2 关联请求 | 启动、stdin/ACK、RPC/pumps、turn 终态的测试；逐后端记录支持矩阵，不能以 Codex 通过代表全部后端 | 开发中，详见 [实施进度](IMPLEMENTATION.md) |
| OBS-05 审批闭环 | ApprovalCoordinator、PermissionBridge、App approval；EP-20 | P2，依赖 OBS-01/02/04 | 送达/呈现/裁决/应用分阶段，等待语义与幂等/撤销不变，专项安全验收 | 已接主要来源，集中验证中；未支持的结果明确为 unknown，见 [实施进度](IMPLEMENTATION.md) |
| OBS-06 内容、文件与前后台 | App media/UI/data、文件服务；EP-15、21、22、29 | P2，依赖 OBS-01/03 | 展示回退/读取/上传下载分片/落盘/恢复的证据；正常回退/取消不形成错误风暴 | 已接主要来源，集中验证中；未支持的结果明确为 unknown，见 [实施进度](IMPLEMENTATION.md) |
| OBS-07 存储与后台执行 | daemon/relay stores、schedule、background/workflow；EP-23、24 | P2，依赖 OBS-01/04 | 读失败回退/写失败/调度派发/执行终态区分，隔离存储故障与重启恢复测试 | 已接主要来源，集中验证中；未支持的结果明确为 unknown，见 [实施进度](IMPLEMENTATION.md) |
| OBS-08 协作与推送 | review/handoff、peer transport、App/relay push；EP-25、28 | P2，依赖 OBS-01/02/07 | 本地持久化、传输 ACK、业务 ACK 分开；撤销/失效 token/重试与手机关闭场景 | 已接主要来源，集中验证中；未支持的结果明确为 unknown，见 [实施进度](IMPLEMENTATION.md) |
| OBS-09 Git 与升级 | GitService、update、service；EP-26、27 | P2，依赖 OBS-01/02 | 操作/冲突/退出码、升级阶段与重启后版本证据；临时仓库/工件/服务替身验收 | 已接主要来源，集中验证中；未支持的结果明确为 unknown，见 [实施进度](IMPLEMENTATION.md) |
| OBS-10 发布和排障验收 | 文档、查询、发布配置；全 EP-01–30 | P3，依赖 OBS-01–09 | 每条路径真实证据与正常对照索引、平台缺口、符号、配额/迟到/丢弃、7 天运行观察、回滚 | 进行中：首批实际部署及 iOS/relay 日志有回执，完整验收未完成，见 [实施进度](IMPLEMENTATION.md) |

最低开发顺序：OBS-01 → OBS-02/03/04 的端内观测 → P2 关联及 OBS-05–09 → OBS-10。只有本地代码/测试已完成但尚无后台事件时标“本地通过，云端待验”，不能直接关闭整条路径。

有限预算的优先级是新崩溃/新异常指纹、核心操作不可完成、持续服务异常、重复拒绝与展示降级；新增路径不能扩大限流键空间到用户 ID/文件名。预算表按固定 error_path/code 做有界汇总，预期高频失败不得挤掉首次有价值的样本。

## 5. 验收记录与完成规则

每个 EP 至少覆盖一个真实故障分支和一个正常/取消/恢复对照；这只是最低门槛，**不代表这一行列出的全部分支已经覆盖**。例如 EP-06 的未知类型与损坏已知类型、EP-21 的缺尾片与合法旧分片都要各自留下用例结果。依赖分支按适用平台/后端展开，未支持的目标明确标 N/A 与原因。

开发时按路径建立如下记录；本次不预填通过状态：

```text
error_path / case_id / operation / code
源码入口与 Git SHA / 平台版本 / Agent 版本
故障构造方法 / 正常对照 / 预期业务结果
预期捕获阶段 / issue 或仅日志 / 预期事件数上界
本地测试命令与结果 / 实际 diag_trace_id / Sentry event 或 log 定位条件
实际脱敏 payload 检查 / 是否存在错误归因、串会话或重试放大
状态：待开发 | 本地通过 | 云端通过 | 不适用 | 未通过
尚未覆盖的分支 / 下一步
```

注入使用假的文件系统/transport/Agent、临时数据与 staging SDK 配置；模拟磁盘满、权限拒绝、错误帧、重复连接不能作用于真实用户文件、生产密钥/relay 或本机常驻 daemon。最终发布包的真实 SDK/符号/网络验证沿用主方案 P0/P3 边界。

验收以“准确解释构造的失败 + 正常对照不误报 + 无敏感字段 + 业务语义不变”为准，不以新增日志行数、catch 数、30 个 ID 齐全或出现一张 Sentry 截图作为完成证明。

### 示例：项目里只有部分会话不见了

fixture 中放三份历史：两份可读、一份读取抛错。期望 EP-09 给出 scanned=3、failed=1、returned=2、result_quality=partial，并有一份安全原因样本；App 仍展示两条已有结果。换成三份均无合法用户消息的文件，returned=0，但 failed=0，不能报“扫描崩溃”。再对一个正常返回的会话注入历史应用异常，应由 EP-14 表示 apply 阶段失败，不能错误归到扫描阶段。

### 示例：任务已派发，但手机一直显示运行中

用 fake Agent 先返回派发确认，再模拟执行进程退出而无终态。EP-24 记录 dispatch 成功，EP-19 提供进程退出证据，同一操作最后为已知失败或终态未确认，不直接将“派发成功”记为执行完成。对照为正常长任务且持续有进度，不能因固定时长而报卡死。推送 provider accepted 仅供 EP-28 说明通知请求被接收，不能替代任务终态。

## 6. 变更记录

- 2026-09-09：根据用户要求补充实际核心出错路径；核查静默丢弃、部分扫描、存储回退、分片与进程管道等入口，定义 EP-01–30 和 OBS-01–10，并将范围纳入主方案。本次仅设计文档，未实现埋点或执行故障注入；工作区已有 OpenCodeLauncher 改动不属于本次变更。

[relay-e2e]: ../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/net/RelayE2EConnection.kt
[direct-e2e]: ../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/net/DirectE2EConnection.kt
[device-sessions]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/relay/DeviceSessions.kt
[scanner]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/disk/TranscriptScanner.kt
[archive]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/disk/SessionArchive.kt
[file-assembler]: ../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/FileChunkAssembler.kt
[agent-process]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/agent/AgentProcess.kt
[conversation]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/conversation/Conversation.kt
[scheduler]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/schedule/SchedulerService.kt
[highlight]: ../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/ui/CodeHighlight.kt
[daemon-core]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/DaemonCore.kt
[daemon-main]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/Main.kt
[service]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/service/ServiceInstaller.kt
[desktop-crash]: ../../mobile/composeApp/src/desktopMain/kotlin/dev/ccpocket/app/desktop/DesktopCrashGuard.kt
[relay-client]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/relay/RelayClient.kt
[pairing]: ../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/pairing/Pairing.kt
[router]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/server/RequestRouter.kt
[relay-server]: ../../relay/src/main/kotlin/dev/ccpocket/relay/RelayServer.kt
[ws]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/server/WsConnection.kt
[repository]: ../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/PocketRepository.kt
[drain]: ../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/net/ChannelDrain.kt
[broker]: ../../relay/src/main/kotlin/dev/ccpocket/relay/Broker.kt
[directories]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/disk/DirectoryService.kt
[registry]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/session/SessionRegistry.kt
[replay]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/disk/TranscriptReplay.kt
[replay-slice]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/disk/ReplaySlice.kt
[observe]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/conversation/ObserveSession.kt
[transcript]: ../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/ChatTranscript.kt
[session-files]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/disk/SessionFilesService.kt
[panes]: ../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/data/SplitPanes.kt
[image-decode]: ../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/media/ImageDecode.kt
[codex]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/codex/CodexBackend.kt
[claude]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/claude/ClaudeBackend.kt
[opencode]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/opencode/OpenCodeBackend.kt
[approval]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/approval/ApprovalCoordinator.kt
[permission]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/agent/PermissionBridge.kt
[exports]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/disk/FileExportService.kt
[inbox]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/disk/FileInboxService.kt
[upload]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/disk/UploadReassembler.kt
[schedule-store]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/schedule/ScheduleStore.kt
[review-store]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/review/ReviewStore.kt
[relay-store]: ../../relay/src/main/kotlin/dev/ccpocket/relay/store/SqliteRelayStore.kt
[jobs]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/conversation/BackgroundJobRegistry.kt
[workflows]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/conversation/WorkflowTracker.kt
[peer-inbox]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/review/PeerInboxClient.kt
[review]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/review/ReviewService.kt
[handoff]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/handoff/HandoffService.kt
[git]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/git/GitService.kt
[update-check]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/update/UpdateChecker.kt
[update]: ../../daemon/src/main/kotlin/dev/ccpocket/daemon/update/UpdateService.kt
[push-controller]: ../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/push/PushController.kt
[push]: ../../relay/src/main/kotlin/dev/ccpocket/relay/push/PushService.kt
[push-sender]: ../../relay/src/main/kotlin/dev/ccpocket/relay/push/PushSender.kt
[lifecycle]: ../../mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/AppLifecycle.kt
