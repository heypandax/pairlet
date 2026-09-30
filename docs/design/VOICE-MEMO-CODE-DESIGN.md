# 语音备忘转任务：代码设计 v0.2

> 配套 [总体方案](VOICE-MEMO-TO-TASK.md) 与 [UI brief](claude-design-handoff/voice-memo-tasks/DESIGN_BRIEF.md)。  
> 代码核对基线：ab72a86b；本文件是实现契约，代码片段为拟新增接口，不表示已经写入产品或编译通过。  
> 用户要求：设计包括代码设计；本轮完成设计，之后由 Fable 并发实现。审核意见及采纳记录保留在总体方案第 17–18 节。

## 1. 冻结的实现边界

- 手机 Compose 入口，桌面隐藏；新功能默认关闭。聊天原有 90 秒录音和听写行为保持。
- 备忘独立于聊天 VoiceState/convoId；处理可脱离目标会话，执行必须人工确认。
- 手机是持久化权威；daemon 内存重组、整段重传、内存结果 TTL。无分块磁盘账本、无云同步。
- 备忘仅走独立 whisper-cli，复用已有发现/转换/清洗，不占用 WhisperServer。
- 摘要使用 ClaudeRuntime 的无工具一次性 CLI，不传 --model，不提供模型选择。
- 派发进入目标聊天；一条待办一条消息，ACK 后才投递下一条。多条可能合入同一回合，不增加执行调度器。
- 待办五态：draft/sending/delivered/unknown/failed；不展示逐条执行中、完成。
- 消息/协议名字在本稿固定；Fable 若发现需要改变 wire、权限、恢复语义，应先同步文档，再让各工作包使用同一契约。

## 2. 文件布局与所有权

下列路径均相对仓库根；“新增”表示计划文件，“修改”只改接入所需部分。避免在 PocketRepository 中继续堆叠整套备忘状态机。

| 工作包 | 文件/目录 | 内容 |
|---|---|---|
| A 契约/集成 | 新增 protocol/src/commonMain/kotlin/dev/ccpocket/protocol/VoiceMemo.kt；修改 Messages.kt | 五类消息、结果与限制、校验；DaemonInfo/ClientCaps 可选字段 |
| B daemon | 新增 daemon/src/main/kotlin/dev/ccpocket/daemon/memo/ | VoiceMemoService、MemoUploadBuffer、MemoJobRegistry、MemoCliTranscriber、MemoSummarizer、MemoProcessRunner |
| C 手机数据 | 新增 mobile/composeApp/src/commonMain/kotlin/dev/ccpocket/app/memo/ | Models、Reducer、Repository、Store、DispatchCoordinator、SessionGateway 接口 |
| C 平台存储 | 新增各 iosMain/androidMain/desktopMain 的 app/memo/MemoFiles.*.kt | 私有文件和原子写；桌面实现供编译/测试，不开放产品入口 |
| D 手机 UI | 新增 commonMain 的 app/ui/memo/ | MemoListScreen、MemoRecordingScreen、MemoDetailScreen、MemoTargetSheet、MemoDispatchNotice；展示状态不运行网络副作用 |
| E 发送/连接 | 修改 app/net/PinOutbound.kt、RelayE2EConnection.kt、DirectE2EConnection.kt | 为备忘扩展单连接有效的临时发送入口；保持普通消息、pin 同步既有行为 |
| A 集成专属 | 修改 PocketRepository.kt、ui/App.kt、ui/Settings.kt、ui/SettingsIA.kt、字符串资源 | 依赖装配、路由、实验开关、麦克风互斥与聊天发送接缝 |
| A 集成专属 | 修改 DaemonCore.kt、server/RequestRouter.kt、server/WsConnection.kt、relay/DeviceSessions.kt | 服务构造、owner/能力门、LAN/relay 能力公告和入口 |

WhisperTranscriber 只暴露/提取可复用的发现、参数和清洗方法；旧 transcribe 默认路径不变。MemoProcessRunner 优先保持备忘专用，避免本次顺便重写全项目进程管理。

并发时 B/C/D/E 各自只修改归属文件和对应测试；涉及集成专属文件提交接口片段，由 A 合入。所有实施 Agent 都需知道其他 Agent 同时工作，不覆盖或回滚别人的修改。接口冻结先于页面/服务集成；此安排是后续交接，不在本轮启动实现。

## 3. Wire DTO 与能力公告

### 3.1 现有帧尾部追加字段

~~~kotlin
// ClientCaps 的尾部新增，缺省关闭。
val supportsVoiceMemo: Boolean = false

// DaemonInfo 的尾部新增。
val voiceMemoVersion: Int = 0
val voiceMemoAgents: List<String> = emptyList()
val voiceMemoStatus: String = "unknown"
~~~

version=1 表示本稿契约；agents 首版最多含 "claude"。status 词表：ready / whisper_missing / model_missing / converter_missing / agent_unavailable / unsupported_platform / unknown。客户端对未知值统一显示“当前无法使用，请更新或检查电脑配置”，不猜为 ready。只有 version=1、status=ready、agents 包含 claude、owner 与 E2E 就绪、手机端且实验开关开，才允许开始录音。

公告做本地文件/二进制/启动方式检查，不为能力探测调用付费模型或下载文件；ready 表示本地前置条件齐备，不保证账号登录/额度。账号等错误由实际摘要阶段返回。重连重发公告；电脑设置改变后可通过现有刷新/重连重新检查，不新增配置管理协议。

### 3.2 五种消息

下面使用 String 词表避免新枚举使旧解码器硬失败；新增消息的必需 ID 不给空串默认值。共有字段可选化不得放松入口校验。

~~~kotlin
@Serializable
@SerialName("pocket/memo.start")
data class VoiceMemoStart(
    val memoId: String,
    val attemptId: String,
    val inputKind: String,                    // audio | transcript
    val agent: String = "claude",
    val model: String? = null,                // v1 必须为 null
    val locale: String? = null,               // UI 语言提示；不指定时从原文判断
    val mediaType: String? = null,
    val durationMs: Long? = null,
    val byteLength: Long? = null,
    val sha256: String,                       // 本次输入的 SHA-256
    val chunkCount: Int? = null,
    val transcript: String? = null,
) : ToDaemon

@Serializable
@SerialName("pocket/memo.audio")
data class VoiceMemoAudio(
    val memoId: String,
    val attemptId: String,
    val index: Int,
    val base64: String,
) : ToDaemon

@Serializable
@SerialName("pocket/memo.get")
data class VoiceMemoGet(val memoId: String, val attemptId: String) : ToDaemon

@Serializable
@SerialName("pocket/memo.cancel")
data class VoiceMemoCancel(val memoId: String, val attemptId: String) : ToDaemon

@Serializable
@SerialName("pocket/memo.state")
data class VoiceMemoState(
    val memoId: String,
    val attemptId: String,
    val revision: Long,                       // 已知作业从 1 单调增加；unknown 响应为 0
    val stage: String,
    val transcript: String? = null,
    val result: VoiceMemoResult? = null,
    val metrics: VoiceMemoMetrics = VoiceMemoMetrics(),
    val errorCode: String? = null,
    val retryable: Boolean = false,
    val expiresAtMs: Long? = null,            // 墙上时间只用于缓存期限展示
) : ToPhone

@Serializable
data class VoiceMemoResult(
    val schemaVersion: Int = 1,
    val title: String,
    val summary: String,
    val todos: List<VoiceMemoTodoSuggestion>,
    val language: String,
)

@Serializable
data class VoiceMemoTodoSuggestion(val text: String, val suggestedTarget: String? = null)

@Serializable
data class VoiceMemoMetrics(
    val audioDurationMs: Long? = null,
    val queueMs: Long? = null,
    val transcribeMs: Long? = null,
    val summarizeMs: Long? = null,
    val coldStart: Boolean? = null,
)
~~~

模型的 JSON schema 使用 schema_version/suggested_target，daemon 显式解析映射到上述 wire DTO，避免外部模型命名风格改变协议。JSON schema 的 root 与 todo 均 additionalProperties=false；列出的字段全部 required，suggested_target 允许 string 或 null，schema_version 必须 const=1，todos 允许空数组。todoId 由手机在首次接受本 attempt 结果时生成并保存，重复状态不能再次生成列表。

客户端同时校验状态载荷：ready 要有合法 result 和 transcript，degraded 要有 transcript 与明确错误；非法快照保留最后可用数据并报告 incompatible_response，不能把 null 解读为删除已有内容。

state 阶段：receiving / queued / transcribing / summarizing / ready / degraded / failed / cancelled / unknown。queued 只表示电脑处理槽位，不是离线自动任务队列；unknown 只回复 get/cancel 等已请求的未知 ID，不覆盖已在手机持久保存的结果。

### 3.3 校验与限制

| 输入 | 规则 |
|---|---|
| ID | memoId/attemptId 为规范 UUID；不直接拼接用户路径。服务器 key 为认证 deviceId + 两个 ID |
| audio start | transcript=null；mediaType=audio/mp4；durationMs 在 (0, 181500]（允许停止回调误差）；byteLength 在 (0, 8 MiB]；chunkCount=ceil(byteLength/128 KiB)；sha256 为 64 位小写 hex |
| transcript start | transcript 非空且 ≤20,000 Unicode code point、UTF-8 ≤80 KiB；sha256 是 UTF-8 的 hash；mediaType/byteLength/chunkCount 必须 null；durationMs 可保留原录音时长，但不参与音频验证 |
| model/agent/locale | model=null；agent 必须属于公告且 v1 仅 claude；locale 可空，非空限 35 个 ASCII 字母/数字/连字符，仅作语言提示；拒绝未知参数组合，不悄悄回落 |
| audio chunk | 每块独立 Base64；限制编码长度后才解码；非末块恰好 128 KiB，末块恰好剩余字节；index 有界且总计不超 byteLength |
| 解码后音频 | 转换成已知 16 kHz、mono、16-bit PCM 后检查 WAVE 格式及帧数，实际时长不得超过 181.5 秒；预算包含转换；超限报错，不截断冒充成功 |
| 结果 | 标题 1–80 字、摘要 1–1,200 字、0–20 个 todo、每项 1–1,000 字、目标线索 ≤120 字；language 为 1–35 个 ASCII 字母/数字/连字符；不渲染 HTML；模型 stdout 总计 ≤64 KiB，字段上限不豁免总字节限制 |

录音 UI 在 180 秒停止，181.5 秒只容纳原生 stop 和音频封装延迟，不显示更长可录时长。字数校验统一用 Unicode code point 计数，另以 UTF-8 字节硬限制控制内存；测试包含 emoji，避免 JVM 与 iOS 对 surrogate 处理不同。

全部常量集中到 protocol 的 VoiceMemoLimits，手机与 daemon 共用 v1 固定限制，不再另加可漂移的限制公告。Whisper 清洗后的转写同样满足 20,000 code point / 80 KiB；超限报告 transcript_too_long，保留手机音频，不截断后调用摘要。

错误码固定为机器可读字符串，UI 通过本地化映射：unsupported、not_ready、busy、invalid_input、input_conflict、upload_timeout、audio_invalid、audio_too_long、transcribe_failed、transcribe_timeout、empty_transcript、transcript_too_long、agent_unavailable、summary_failed、summary_timeout、invalid_result、cancelled、unknown_job。不要把 CLI 原始 stderr 放进用户错误或日志。服务边界将异常转换为固定错误码，不向现有 transport 的异常日志抛出带正文、命令参数或模型输出的异常 message。

### 3.4 兼容与路由

DaemonInfo/ClientCaps 的新字段有默认值；旧端忽略字段，新端见缺省即关闭。所有 memo 帧必须经过当前连接 supportsVoiceMemo 门，结果回原设备。分块与最大合法 state 都要实测序列化/E2E 包装后的 frame fitting；不能只测裸 Base64 长度。连接实际帧上限不足时在开始前拒绝，不把已生成结果静默丢弃。RequestRouter 入口同时要求三元 owner 判断通过、非空已认证 deviceId；BridgeCaps/GuestCaps/CollaboratorCaps/ExecutionCaps 保持默认拒绝，并更新其 exhaustive 测试。

start/audio 要求处理能力当前可用；get/cancel 只要求身份、协议版本与客户端能力，不因模型文件后来缺失而阻断查询/取消。非法 ID 不写 registry；按现有错误通道返回不含原始载荷的拒绝信息。

LAN 与 relay 都必须改：公告、caps holder 更新、allowedForCaps、认证身份传入。不能只改 relay handler 导致 LAN 功能无权限或绕过权限。未知新阶段不导致断连；客户端保留原数据，显示版本不兼容，不用空列表替换结果。

## 4. 手机数据层接口与状态机

### 4.1 接口

~~~kotlin
interface VoiceMemoStore {
    suspend fun list(scope: MemoScope, offset: Int, limit: Int): MemoRead<List<MemoHeader>>
    suspend fun read(scope: MemoScope, memoId: String): MemoRead<MemoDocument>
    suspend fun writeAudio(scope: MemoScope, memoId: String, bytes: ByteArray): MemoWrite<AudioRef>
    suspend fun commit(document: MemoDocument, expectedRevision: Long?): MemoWrite<Unit>
    suspend fun markDeleted(scope: MemoScope, memoId: String): MemoWrite<Unit>
    suspend fun purgeDeleted(scope: MemoScope, memoId: String): MemoWrite<Unit>
}

interface VoiceMemoRepository {
    val state: StateFlow<MemoUiState>
    fun accept(action: MemoAction)              // 单 actor 串行处理，UI 不 launch 业务协程
}

data class MemoScope(val bindingId: String, val deviceId: String)
data class MemoTarget(val bindingId: String, val sessionId: String, val workdir: String, val agent: AgentKind)

interface MemoSessionGateway {
    suspend fun enter(target: MemoTarget, operationId: String): EnterResult
    suspend fun sendConfirmed(request: ConfirmedMemoPrompt): MemoSubmitResult
    val receipts: Flow<MemoPromptReceipt>
}
~~~

MemoScope 的 bindingId 由现有可信配对记录导出，不用易变的主机名或普通路径。本地文件名对 scope 编码成内部 token。MemoRead 区分 Found/Missing/Unreadable/UnsupportedVersion；MemoWrite 区分 Durable/NotWritten/Indeterminate。读失败不能当空库，写结果不明确不能继续发送。

MemoUiState 拆分 capture、processing、document、selection、dispatch 五部分；普通页面路由不充当业务状态。MemoDocument 记录 schemaVersion、revision、attemptId、原文和结果、编辑修订、音频引用、tombstone、逐项发送快照；不持久化协程 Job、绝对时钟的单调标记或 UI 组件状态。

### 4.2 持久领域模型

~~~kotlin
// 本地格式也带 schemaVersion；未知版本只读报错，不用默认值覆盖原文件。
data class MemoDocument(
    val schemaVersion: Int,
    val scope: MemoScope,
    val memoId: String,
    val createdAtMs: Long,                    // 墙上时间只作列表展示
    val updatedAtMs: Long,
    val revision: Long,                       // 本地每次提交 +1
    val editRevision: Long,                   // 仅用户内容修改 +1
    val processing: MemoAttempt?,
    val content: MemoContent,                 // title/summary/transcript/language/audioRef
    val todos: List<MemoTodo>,
    val dispatches: List<MemoDispatchRecord>,  // 含历史记录；不由重新整理覆盖
    val deleted: Boolean,
)
data class MemoAttempt(
    val attemptId: String,
    val inputHash: String,
    val baseEditRevision: Long,
    val remoteRevision: Long,                 // 和本地 revision 分开
    val stage: String,
    val timings: MemoTimings,                 // 本 attempt 的各阶段耗时；缺测值为 null
)
data class MemoDispatchRecord(
    val batchId: String,
    val todoId: String,
    val promptId: String,
    val confirmedText: String,
    val target: MemoTarget,
    val convoId: String?,                     // 精确 SessionLive 后补齐并落盘
    val state: String,                       // draft/sending/delivered/unknown/failed
    val updatedAtMs: Long,
    val errorCode: String? = null,
)
~~~

MemoTimings 保存音频时长、本端上传/总处理耗时、daemon 返回的排队/转写/整理/冷启动信息；仅合并同 attempt 的指标。挂起/重启无法连续测量时 totalMs=null，显示“未完整测得”。原文保留的录音时长另随 MemoContent 保存，不因只重跑摘要就变成 0。字段单位均为毫秒；跨端不以墙上时间相减。retryable 只提示是否允许用户在条件恢复后重试，不授权自动重跑。

MemoTodo 持久保存 todoId、text、selected、suggestedTarget；送达状态从该项 dispatch record 推导，不保存另一个容易漂移的完成布尔值。单设备 actor 是文档唯一写入口，expectedRevision 不符返回 NotWritten(conflict) 后重读归并；不要用 last-write-wins 覆盖同时到达的 ACK 与编辑。预期最多 100 条本地文档和每文档 20 个当前待办；历史派发记录不计入当前待办上限，但仅保存本地已有记录，不由 daemon 推送无限追加。

### 4.3 事件、副作用与落盘顺序

| 事件 | 状态迁移 | 副作用及顺序 |
|---|---|---|
| StartRecording | idle → preparing → recording | 检查开关/能力/E2E/owner/麦克风 lease；请求权限；recorder.start 成功后才记单调起点 |
| StopRecording/ReachedLimit | recording → saving | stop 原生录音；writeAudio durable；commit 元数据 durable；随后创建 attempt 并上传 |
| RecorderInterrupted | recording → failed | 释放麦克风；现有录音器已丢片段，明确重录，不生成假可恢复文件 |
| AudioSaved | saving → uploading | 准备 immutable start 和分块；所有发送绑定同一电脑与连接代次 |
| RemoteState | 上传/处理中 → 当前阶段/结果 | 过滤绑定、ID、attempt、revision；先持久化转写/结果，再显示 ready 和释放原音频 |
| EditTodo/AddTodo/DeleteTodo | document.editRevision 增长 | 验证本地字数/20 项上限；原子保存；保存完成前禁派发，失败显示尚未保存 |
| RetryProcessing | failed/degraded/unknown → uploading/summarizing | 用户明确操作；保存新 attemptId；优先本地转写，否则音频；memoId 不变 |
| ConfirmDispatch | ready/degraded → dispatch.opening | 校验非空选择/目标/保存完成；保存批次快照与每项 promptId，再导航 |
| AppBackground/LeaveTarget | 暂停未投递项 | 取消未写出的发送 lease；在途项保守归 unknown；已送达不变 |
| DeleteMemo | document → tombstone | 先 durable 删除标记，再发尽力 cancel，再删音频/正文；旧回包不复活 |

reducer 为纯函数，返回 nextState + effects；effect runner 的结果通过事件回投。每个操作使用 generation token，过期的 start/stop/权限回调不能复活新录音。麦克风 lease 在 preparing 即占用，try/finally 在成功、拒绝、异常、取消和系统中断都释放；聊天录音也接同一 lease，其他行为不改变。

首次 StartRecording 前须完成备忘用途披露并本地保存 disclosureVersion 与绑定 scope 的同意；版本未变且用户未撤销不重复打断。设置开关只表示启用功能，不替代数据用途同意。

### 4.4 编辑与远端结果竞争

回包只更新已存在且未删除的文档，不通过回包创建文档；找不到 memoId 或 attempt 不匹配直接忽略，因此清理墓碑后迟到结果也不会复活记录。memoId 永不复用。提交每次 attempt 时保存 baseEditRevision。只有首次有效 ready/degraded 且 editRevision 未变化，才自动初始化本地结果；已经接受的 attempt 再收到相同结果只更新允许的耗时字段。用户已有编辑时后台不得覆盖；用户点“重新整理”前说明会生成新结果，替换草稿须显式确认且不得删除已有派发记录。已送达/待核对项不可原地改字重发，需要复制新项。

### 4.5 生命周期与恢复

P0 禁止离线新录音，录制中的断网仍允许 stop 保存。App 重启：preparing/recording 标为录音中断；saving 检查 durable 文件；uploading/processing 保留绑定和 attempt，连接就绪时只 get；sending 标 unknown；draft/delivered 原样恢复。get 返回 unknown 才提示用户重新处理，不自动 start。查询结果 unknown 的 revision=0 是“当前电脑已无该作业”，只匹配正在等待的 get、当前连接 generation 和 ID；不经过正数 revision 的大小比较，也不擦除已保存原文/结果。不能因上次 revision=4 就忽略本次 unknown。

恢复上传统一为人工动作：重连/前台只 get。若仍 receiving，显示“继续上传”，点击后同 attempt 整段重传；若 failed/unknown，新 attempt 重新处理。App 退后台暂停未写出的上传；已被电脑完整接收的作业继续，回前台查询。前台处理页在首入/重连查询一次，持续等待时最多每 10 秒 get 一次且最多一个在途查询；离页/后台停止轮询，推送仍按归属保存。通信超时展示“等待电脑回应”，不凭本地计时伪造 daemon 失败。

页内离开处理阶段不终止 daemon；进入目标聊天属于派发流程，不能被普通离页钩子误暂停。app 到后台、离开目标聊天或用户手动插入其他消息时暂停剩余项。重复前台事件不得启动重复录音、上传、摘要或派发。

关闭开关隐藏入口、作废活动 lease、停止本机录音及未提交工作，并尽力发送 cancel。已送达任务不撤回；所有本地文档保留，重新开启可读。取消帧使用独立的短期清理 lease，不依赖已经关闭的实验开关，但仍需原 owner/绑定/E2E 能力。

## 5. 本地文件事务与恢复

拟目录为应用私有 Application Support/files 下的 voice-memos/<scope-token>/<memoId>/，包含 document.json、audio.m4a、短期写入临时文件及删除标记。不把任意 workdir 或模型文本作为文件名。

录音提交顺序：audio 临时写并同步 → 原子替换 → document 带音频引用写并同步 → UI 提示已保存。两文件不是一个文件系统事务；重启扫描有音频无元数据时作为待恢复记录/孤儿暂存处理，不自动上传。元数据指向不存在的音频要报告数据不可用。转写已持久化后先原子移除音频引用，再删除音频文件，崩溃最多留下可清理孤儿，不造成有效引用悬空。

发送账本与文档同次原子替换，避免“任务已发送却找不到对应 promptId”。任何 Indeterminate 写入先重读恢复；无法证明发送记录已保存就不发。ACK 收到但落盘失败时，内存保持 delivered、暂停后续发送并提示保存问题；重启后旧 sending 会保守转 unknown，绝不自动重发。

删除先 durable tombstone；清理失败保留 tombstone，下一次启动继续清理，不在列表中复活。tombstone 清理须等待本地所有活动任务取消；已接收回包也经过墓碑检查。文本/图片/日志不自动上传云备份；iOS 设置实际文件保护与 exclude-from-backup 属性，Android 使用私有 noBackupFilesDir 或配置排除该目录，两端须验证，而不是只写注释。

沿用 ProjectPinPersistence 的 Durable/NotWritten/Indeterminate 思路，不直接复用其 pins 目录或序列化格式。平台实际 fsync 能力按现状报告，不承诺未经真机验证的断电保证。desktop 文件实现仅服务共享编译和 JVM 测试。

## 6. daemon 作业与处理服务

### 6.1 服务接口

~~~kotlin
data class MemoOwner(val deviceId: String)      // 仅认证入口构造，不接受帧内自报
data class MemoJobKey(val deviceId: String, val memoId: String, val attemptId: String)

interface MemoTranscriber {
    suspend fun transcribe(input: MemoAudio, deadline: MemoDeadline): MemoTranscribeResult
}
interface MemoSummarizer {
    suspend fun summarize(transcript: String, locale: String?, deadline: MemoDeadline): MemoSummaryResult
}
class VoiceMemoService(/* registry, transcriber, summarizer, clock, scope */) {
    suspend fun handle(owner: MemoOwner, frame: ToDaemon, reply: MemoReplyTarget)
    suspend fun close()
}
~~~

缓存 ready 不代表可向原来的 owner 之外发送；配对撤销时取消该设备作业、清缓存与 reply target。重新配对是新 scope，不能按旧展示名称迁移记录。

MemoReplyTarget 由现有认证 transport 构造，不能由业务帧传入。它绑定当前连接/设备和 capability，发送时重新验证 lease 与撤销状态。作业可跨断线继续，结果缓存在 registry；断线推送失败只记无内容诊断，不取消已完成结果。新连接 get 绑定新 reply target，不能复用过期 LAN sink 或广播给其他设备。

### 6.2 登记、去重、TTL 与并发

所有 registry 修改由一个 Mutex/actor 串行，耗时进程在锁外执行。start 先校验身份和形状，再比较输入 fingerprint；fingerprint 包含 kind、hash、长度、时长、格式、块数及 agent/model/locale。相同 key 同内容返回现有状态；不同内容报 input_conflict；取消/失败/ready 不能由重复 start 重新启动。

receiving 保存固定大小有界块数组；同 index 同内容忽略，不同内容标失败；重传整个文件仍只完成一次。当全部到齐且 hash 正确时，锁内将 phase 改 queued 并设置唯一 job handle，然后锁外启动 worker；重复末块不会创建第二个 worker。

每设备一个活动作业（包括接收），全 daemon 最多两个。CLI 转写槽位一个，等待最多 30 秒；这不是后台离线排程。原始音频内容预算最多 2×8 MiB；完成拼接采用流式写临时文件或转移块的所有权，不再整段复制 ByteArray。Base64 当前帧、解码块与进程管道有单独有界开销；接收块先检查字符串长度再分配。已完成结果缓存最多 100 条、24 小时；存的只有有界转写/结果/指标/状态，不存原始模型 stdout 或音频。

上传空闲 60 秒释放原音频，保留失败终态。终态最早优先清除；活动状态不因结果缓存满被逐出。过期或重启查询 unknown，明确“不知道之前是否处理”，不自动重新调用。cancel 对 ready/degraded/failed 返回既有终态，不抹掉可恢复结果；对活动作业取消，确认进入 cancelled 后释放槽位。cancel 未知 key 可记录有界取消标记，防止乱序 start 复活；每设备和全局标记数受同一缓存上限限制。

snapshot 在锁内复制不可变数据，revision 只在真实状态变化时增加；get 重复读不递增。每次向外发状态在锁外，不允许慢手机堵住其他任务。任务完成提交前检查 job generation 仍是当前尝试，避免取消后的 late completion 覆盖 cancelled。

### 6.3 独立转写进程

新增 MemoCliTranscriber，使用已有 resolveWhisper/resolveModel/convertArgs/buildArgs/cleanTranscript；不调用 WhisperServer.transcribe，不通过设置全局 CC_POCKET_WHISPER_SERVER 影响聊天。通用 initial prompt 例如：“以下是中英混合备忘，英文单词保留英文原文，不音译，例如：检查 mobile build，并补充 README。”不读取项目文件或分支。

流水线：受限临时目录 → 写输入 → 转换到 16 kHz mono PCM → 检查输出格式/帧数/实际时长 → whisper-cli → 清洗 → 删除临时目录。总 deadline=180 秒从转换前开始，每个进程使用 remaining，不叠加转换和识别独立上限。模型解析、取消、转换失败及空转写均有独立错误码。

MemoProcessRunner 接受 argv 列表、cwd、stdin bytes、输出上限与 deadline；不拼 shell 命令、不通过 argv 传原文。用可取消等待与 cancellation handler 管理 Process：超时/取消先终止本进程及后代，短暂宽限后强制终止，在 NonCancellable 的有界清理中确认退出并删除临时文件；启动与登记之间的取消竞态也覆盖。保留 CancellationException，不 catch(Throwable) 转成“识别失败”后继续摘要。

转写进程 stdout/stderr 同时有界读取或丢弃，不能因未 drain 管道而死锁。转换输出限制为录音上限对应 PCM 大小加有界容器开销；超过即终止并报 audio_invalid。只操作本作业进程，禁止按进程名批量杀 whisper 或触碰 daemon 服务。独立 CLI 仍可能占用 CPU/内存，长备忘并发普通聊天转写的回归是发布门槛。

### 6.4 摘要进程

构造时注入与主 ClaudeBackend 同源的 ClaudeRuntime；每次启动读取当前预设，不缓存凭据或模型默认值。沿用已验证的 --print、--output-format json、--json-schema、--tools=、--strict-mcp-config、--safe-mode、--disable-slash-commands、--no-session-persistence、--system-prompt；不传 --model，不带用户项目 cwd。

提示词和模型输出 schema 见总体方案 §8，固化为可测试常量；输入 UTF-8 JSON 的 transcript 字段只是数据。总摘要 deadline 90 秒，stdout 64 KiB，stderr 16 KiB；达到上限停止读取/进程并返回错误，禁止原始 stderr 内容外传。实现可以共享 MemoProcessRunner，但转写文件输出与摘要 stdout 分开解析。

只接受 CLI 成功退出后的 structured_output 且本地校验通过；不从任意 JSON 子串中猜成功，不把错误对象当摘要。无效结果进入 degraded，带原文和 invalid_result；P0 不自动重试修 JSON。无可信摘要就仅显示原文，普通字符串不是可执行代码。

转写成功后立即回包含 transcript 的 summarizing 快照，手机先保存；最终 ready/degraded 快照也包含 transcript，允许丢帧后通过 get 恢复。未拿到 stdout 的摘要错误不证明服务商没处理，重试只由用户触发。

## 7. 派发代码接缝与传输恢复

### 7.1 目标打开与 UI 导航

MemoSessionGateway.enter 保存 operationId 和目标快照，参考现有 awaitOpenedConvo，但额外验证 sessionId/workdir/agent/绑定电脑及非 observing，拒绝自动 fork 后的不同 sessionId。workdir 使用现有目录解析后的规范身份比较，不能把用户展示路径字符串直接等同于可信目录身份。仅目标身份精确对应时取得 LiveTargetLease；已有活动目标匹配可直接复用。

导航到聊天不等于发送成功；按钮按“正在打开会话”显示，超过既有 30 秒打开期限则失败返回备忘，draft 保留。若目标突然被终端占用，需要接管/产生分支，停止此批次，不沿用新的未知会话自动发送。

~~~kotlin
data class ConfirmedMemoPrompt(
    val memoId: String,
    val todoId: String,
    val promptId: String,                      // coordinator 已先落盘
    val text: String,                          // 固定前缀 + 用户确认快照
    val target: LiveTargetLease,
)

sealed interface MemoSubmitResult {
    data object NotSubmitted : MemoSubmitResult  // 已证明未进入 writer
    data object AwaitingReceipt : MemoSubmitResult
    data object Unknown : MemoSubmitResult
}

data class MemoPromptReceipt(val bindingId: String, val convoId: String, val promptId: String)
~~~

LiveTargetLease 绑定当前手机会话、配对、连接 generation 和批次意图；只在当前 owner/目标聊天前台/开关开启时有效。下一项发送前重新检查，不能只在第一项检查一次。

### 7.2 最小修改普通发送主路径

为现有私有发送函数新增可选参数对象，默认完全保留现有行为：

~~~kotlin
data class PromptSubmissionOptions(
    val promptId: String? = null,
    val includeAttachments: Boolean = true,
    val recovery: PromptRecoveryPolicy = PromptRecoveryPolicy.ChatDefault,
    val targetLease: LiveTargetLease? = null,
)
enum class PromptRecoveryPolicy { ChatDefault, MemoConfirmBeforeResend }
~~~

该对象是本地实现接口，不加入 SendPrompt wire。备忘走 MemoConfirmBeforeResend、已保存 ID、includeAttachments=false；复用权限/退化/观察/交接检查和气泡渲染。若校验拒绝，明确回 NotSubmitted，不能把当前 sendPrompt 返回 true（仅启动协程）当 ACK。

每条备忘气泡附本地来源索引或通过 promptId 查账本，显示与备忘一致的 pending/delivered/unknown。不要通过任意 AssistantChunk 清除待办 unknown；普通聊天的 promptEvidence 可以继续控制聊天动画，备忘 delivered 只由对应 ACK 转移。

在 handle 收帧入口（当前 promptOutcomes.acknowledged 附近）通知 MemoDispatchCoordinator，不受当前会话 UI 过滤；必须带连接所属 binding，防止切电脑旧读协程的 late ACK 污染另一台电脑。tracker 保留原有诊断用途，不修改为存储组件。

### 7.3 重连队列必须有真实的临时发送边界

现有 relay/direct 的 ScopedOutbox 位于 PinOutbound.kt；普通 frame 会跨重连保留、旧 writer 交回新连接、LAN fallback 时 drain 重路由。仅在 UI 里“不重发”无法阻止这里自动送出。采用其已有 scoped fence 思路增加通用 transient 分支，**不新建 socket writer 或加密路径**。

~~~kotlin
// 保留 pin 专用 API，其实现可委托共享的 scoped 入队逻辑。
fun tryEnqueueTransient(
    frame: Frame,
    fence: TransientDispatchFence,
    expectedConnection: Int,
    onDisposition: (TransientDisposition) -> Unit,
): TransientEnqueueResult
// 结果：Accepted / Full / Retired；入队 accepted 绝不是送达。
// disposition：Started（可选一次进度通知）以及唯一终态 NotWritten / Written / Indeterminate。
~~~

entry 保存连接 generation、电脑 lease、任务意图 fence 及完成回调。入队前与 writer 编码前都检查；失效且尚未开始 write 回 NotWritten；write 已开始后断网/取消回 Indeterminate；Written 仅表明交给本地 socket，仍需 ACK。Started 只通知即将写入，不是完成；每个 accepted entry 的终态回调恰好一次，不能同时给 NotWritten 和 Written。若没收到终态，coordinator 仍以自己的 deadline 转 unknown，不能无限等待。

prepareFor、drainOrdinary、LAN→relay fallback、writer 被替换路径都丢弃 transient 项并通知，而非重新排队；普通消息和 pin 的既有契约不变。取消 fence 后不能召回已经发出的字节，客户端只能进入 unknown。dispose/close 也要完成尚未回调条目，避免 coordinator 永久等候。

备忘协议帧也使用 transient 入口，避免更换电脑后旧录音/命令重新 flush。上传失败在新连接只 get；恢复传输与新 start 都由用户按 §4.5 明确触发。派发失败只允许用户核对后新提交。无 E2E 的 RelayConnection 明文路径不提供该功能；LAN 与 relay E2E 实现都接同一契约。

### 7.4 所有重试入口的处理表

| 入口 | 普通聊天 | 备忘提交 |
|---|---|---|
| 入队时连接已失效/队列满 | 原策略 | NotSubmitted，保留草稿，停止本批次；不自动等下一连接 |
| socket 写已开始、ACK 未到 | 原 watchdog/reconnect | 收据期限后 unknown；不随重连补发；检查聊天 |
| SessionGone | 原恢复并重发 | 记 unknown/目标丢失，停止剩余；不开新会话补发 |
| promptEvidence/任意流输出 | 原聊天动画 | 不能替代此 prompt 的 ACK，不推进备忘队列 |
| resendStalledPrompt/其他 freshId 重试 | 原人工重发入口 | 对备忘来源转“先核对备忘”操作，不直接生成 freshId |
| 回到前台/App 重启 | 原行为 | sending → unknown；未提交为 draft；不会恢复自动发送循环 |
| 切电脑/LAN fallback/旧 writer | 普通 outbox 原行为 | transient 项不得跨代次重路由 |

实现需要枚举 newPromptId、PromptRetry、SessionLive 自动 resend 和所有 SendPrompt 创建点，而非只改表中一个函数。备忘专属 receipt 等待可沿用 10 秒默认；超时不证明没有收到，且不能顺便取消目标 Agent 回合。这里约束的是手机重复提交和传输重排：daemon 对已经接收的同一 prompt 的既有消费账本/恢复机制仍有效。本功能不承诺 Agent 副作用 exactly-once，也不因备忘页面待核对就撤销 daemon 已接收任务。

### 7.5 批次一致性

coordinator 的每个批次最多一个 awaiting ACK 项：freeze → durable 批次/ID（项目仍是 draft）→ enter → durable 补齐 convoId、将当前项标 sending → send item 1 → ACK 匹配 → durable delivered → item 2。其余项目始终保持 draft，不能在批次开始时全标 sending；打开失败不会把从未提交的项目变成待核对。ACK 持久化失败立即暂停，不能先发下一项再补写。

收到 late ACK 可以把 unknown 改 delivered 并保存，但不能自动续发已暂停的其他项；用户从备忘点击继续未发送项才恢复。已确定 NotSubmitted 的条目显示 failed（未送出）可重试；当前网络可能已接收则必须 unknown。用户确认再次发送未知项时复制成新项、显示重复风险、创建新 ID，旧未知记录保留。

手动在目标聊天发送其他消息时暂停备忘剩余项目，避免全局 activePromptId/watchdog 被多个来源交错覆盖。批次不会锁住整个聊天，暂停和解释比另造会话控制器更小。

## 8. 页面与代码状态映射

| UI | 状态来源 | 允许操作 | 不允许推导 |
|---|---|---|---|
| 实验开关/准备 | local feature flag + capabilities + binding | 开关、现有电脑配置入口 | ready 不等于模型账号一定可用 |
| 列表 | store headers，绑定 scope | 新建、打开、删除 | 本地列表不等于多端同步 |
| 录音 | recorder lease + 单调 elapsed + 原生 levels | stop/cancel | 不显示实时识别或模拟字词 |
| 处理 | remote stage + 本端观测 | cancel、明确失败后 retry | 无分块百分比/ETA |
| 结果 | durable MemoDocument + edit state | 修改/选择/添加/目标确认 | 选中不等于完成；建议目标不自动绑定 |
| 目标确认 | target catalog + rights + selection count | 明确确认 | 多消息不等于多个独立回合 |
| 聊天状态条 | DispatchCoordinator 聚合 | 返回备忘/核对 | 已送达不等于执行中/完成 |

UI 主线程仅收 Flow；文件、base64、hash、进程均在合适 dispatcher。字符串通过现有资源系统双语维护，不硬编码 CLI 名称以外的错误提示。tightCenter/Trim.None、触区和主题规范按 UI handoff 实施。

## 9. 测试设计与完成证据

| 测试组/拟文件 | 核心用例 |
|---|---|
| protocol VoiceMemoWireTest | 五帧 roundtrip；旧 DaemonInfo/ClientCaps 缺省关闭；附加字段；未知阶段；输入互斥、ID/hash/字数/字节/块数边界 |
| daemon MemoUploadBufferTest | 错序、重复同块、冲突块、少块、空块、长度/hash 不符；最后一块并发重复仅启动一次 |
| daemon VoiceMemoServiceTest | 两设备同 memoId 隔离；同 key 输入冲突；TTL/缓存淘汰；cancel-before-start、late completion；重启 unknown；槽位和 busy |
| daemon MemoProcessRunnerTest | 可控子进程模拟正常退出、stdout/stderr 堵塞、超限、启动即取消、超时、子进程树清理；不要用真实付费模型替代单元测试 |
| daemon MemoCliTranscriberTest | 不调用 WhisperServer；无 workdir 提示词；剩余 deadline；WAV 实际时长和格式；空转写；取消不进入摘要 |
| daemon MemoSummarizerTest | argv 无工具/无 MCP/无模型覆盖；runtime 路径/凭据/预设一致；JSON/schema 超限/类型/空 todo；prompt 注入文本仍作为数据 |
| daemon transport 集成 | LAN + relay 公告、caps 与 owner；guest/bridge/collaborator/execution 拒绝；空 deviceId；撤销与重连过期 sink；未知帧后合法帧继续 |
| mobile MemoReducerTest | 权限回调取消竞态、180 秒 stop 一次、互斥 lease、暂停/页面导航、编辑 revision、迟到结果、tombstone |
| mobile MemoStoreTest | 音频/元数据两阶段崩溃；NotWritten/Indeterminate；读失败不是空库；ACK 保存失败停批次；删除重启；损坏隔离 |
| mobile MemoDispatchTest | 当前目标/恢复目标/错误 SessionLive；外部 ID 先保存；单条与三条 ACK；第 2 未知不发第 3；late ACK 不自动续批；不带草稿附件；手动聊天暂停 |
| mobile TransientOutboxTest | 队列满、generation 更换、writer supersede、drain/fallback、fence 失效、write 期间取消；不重新 flush；普通/pin 行为不回归 |
| mobile MemoSendRecoveryTest | SessionGone、watchdog、freshId 重试、重新进入前台；未知不补发；普通聊天原恢复继续 |
| mobile UI 定向 | 0 项/未选目标 disabled，多选提示始终可见，编辑/删除/返回，送达五态与勾选区分，长文/窄屏/字号 |

真机必测：iPhone 麦克风和实际 180 秒；停止后杀 App 恢复；来电/后台中断；本地保护/备份排除；真实 E2E 下 60–90 秒中文三事项与中英混合；实际 CLI 配置和模型响应；派发进入目标聊天人工确认 Agent 在处理；录制各阶段耗时。Android 对外交付前执行同平台用例，不因 commonMain 共用就豁免。

关键性能回归：长备忘进行时的现有聊天语音、聊天发送和会话切换；独立 CLI 不应使其稳定复现超时。若竞争仍不可接受，由 Fable 限制资源/调整队列并回归，不能靠关闭日志或延长所有聊天超时掩盖。

实施期定向运行；最终交付/发布按仓库要求完成 check-all.sh 等门禁。设计阶段只核对文档/链接，不运行真实摘要、装机或发布。验收对照总体方案 AC01–AC18，测试结果与未执行项分别报告。

## 10. Fable 并发实施顺序

1. A 冻结 VoiceMemo DTO、校验词表、Store/Gateway/Transient 接口和文件归属；先合入契约与空实现所需编译接缝。此步的空实现不可被能力公告为 ready。
2. B 实现 daemon，C 实现手机数据/存储，D 基于已验收 UI 和受控 state 开发页面，E 实现 transient outbox 与派发接缝。每个包附对应定向测试。
3. A 集成 PocketRepository、App 导航、设置资源、DaemonCore、两条 transport；能力默认关闭，只在全部真实依赖就绪时打开。
4. 打通 M0 单条真链路；再打通 P0 多条/恢复/异常/列表，不以模拟数据验收实际能力。
5. 在协议/安全敏感修改处按仓库要求安排 wire compatibility 与 crypto/permission 审查，再执行最终门禁和授权范围内的设备验收。

代码审查重点不是“新增文件是否齐全”，而是发送 ID 先落盘、unknown 不自动重发、transient 不跨连接、owner 来源可信、取消不影响其他转写、模型隔离和本地数据可恢复。这些条件未满足时，UI 看起来完成也不能交付可发布版本。

## 11. 实现记录（2026-09-30，UTC+8）

本节记录实现时对本稿的具体化和偏离。未列出的部分按前文实现；产品是否可用以测试和设备验证结果为准。

### 11.1 契约的落点

| 契约 | 文件 |
|---|---|
| 五种帧、限制、词表、校验、ID 与 hash | `protocol/src/commonMain/kotlin/dev/ccpocket/protocol/VoiceMemo.kt` |
| 能力字段 | `Messages.kt` 的 `ClientCaps.supportsVoiceMemo`、`DaemonInfo.voiceMemoVersion / voiceMemoAgents / voiceMemoStatus` |
| 手机数据层、界面与宿主之间的接口 | `mobile/.../app/memo/MemoContract.kt` |
| 连接内一次性发送 | `mobile/.../app/net/PinOutbound.kt` 的 `tryEnqueueTransient`、`TransientDisposition` |
| 宿主端口实现 | `mobile/.../app/data/MemoHost.kt`，以及 `PocketRepository.kt` 的 “voice memo → tasks: host seam” 一节 |
| daemon 处理服务 | `daemon/.../memo/` |
| daemon 权限门与公告 | `RequestRouter.voiceMemoRequest`、`allowedForCaps`、`memo/MemoAnnouncement.kt` |

### 11.2 与前文不同的地方

1. **版本语义**。`voiceMemoVersion` 表示电脑支持的最高契约版本，并支持其下所有版本；手机在公告值不小于自身版本时启用。§3.1 的“只有 version=1 才允许”改为“version ≥ 1”。原因：精确相等会让电脑端今后无法升版本号，已发布的手机会整体失去功能。
2. **wire 默认值**。`VoiceMemoStart.sha256`、`VoiceMemoResult` 的全部字段和 `VoiceMemoTodoSuggestion.text` 在 wire 上带默认值，合法性由校验函数判断。原因：必填字段缺失会让整帧解码失败，手机连同转写一起丢失且收不到任何回应。
3. **整理阶段的失败一律是 degraded**。`invalid_result`、`summary_failed`、`summary_timeout`、`agent_unavailable` 都以 `stage=degraded` 返回并带转写。手机进入结果页，显示“未能整理成待办”，用户可以重新整理或手动添加。`failed` 只用于没有转写可保留的情况。
4. **拒绝帧**。对没有登记成作业的请求（`not_ready`、`busy`、`input_conflict`、形状非法），daemon 回 `revision=0`、`stage=failed`。它回答的是这次请求，不属于作业的 revision 序列。ID 不合法的帧不回包。
5. **各类帧的发送条件**。任何 memo 帧都要求本连接公告过契约。`start` / `audio` 还要求准备状态完全就绪；`get` 只要求实验开关开启，电脑转写器缺失时仍可查询已提交的作业；`cancel` 在开关关闭后仍可发出。
6. **`LiveTargetLease` 的组成**。实际为目标、convoId、批次 ID 和连接代次；是否仍然有效由宿主在每次发送前重新判断（聊天在前台、可写、同一连接、开关开启、没有交接限制）。
7. **离开目标聊天时不关闭会话**。备忘刚投递过的聊天在 10 分钟内离开时不发送 `CloseSession`。原因：回执到达与首个输出之间聊天看起来是空闲的，按原规则回收会关掉正在开始的任务。
8. **聊天气泡**。备忘气泡的 `text` 保存实际发送的全文（含固定前缀），另有 `memoTodo` 保存用户确认的待办用于显示。原因：历史回放按发送文本匹配本地气泡。气泡的送达标记由自身的回执状态和批次阶段得出；已证明未写出的气泡会被移除。
9. **目标列表的来源**。见 §11.7 的两级目录。已归档、权限不足、需接管终端这几类在列表里无法预先判断，由进入目标时的校验拦截：打开后不是所选会话、只读或不可写时不发送任何消息并返回备忘。

### 11.3 真实工具探测

2026-09-30（UTC+8）在开发电脑上各执行一次，未经过 daemon 进程：

| 项目 | 结果 |
|---|---|
| 本地前置检查 | whisper-cli、模型、转换器均找到，状态 `ready` |
| 转写 | 15 秒合成中英混合语音，1.4 秒返回，英文术语保留原文 |
| 整理 | 142 字转写，8.6 秒返回，三条待办通过本地校验；`--json-schema` 中的 `const`、联合类型与 `minLength` 被 CLI 接受 |

以上是单次结果，不是性能结论。180 秒录音、冷启动、与聊天语音并发的情况没有测。

### 11.4 审查后修改的地方

协议兼容审查和两轮权限与隔离审查之后改动如下，均已有对应测试或在既有测试下回归。

| 问题 | 修改 |
|---|---|
| 局域网直连下四种备忘帧被并发处理，首个分块可能先于 start 到达 | `WsConnection` 对备忘帧按接收顺序内联处理，与 relay 路径一致 |
| 被吊销设备仍打开的局域网连接可能收到快照 | 写出前对 `VoiceMemoState` 复核设备仍在允许列表 |
| daemon 被强杀后录音与转写留在系统临时目录 | 生产环境使用 daemon 私有目录 `voice-memo-tmp`，启动时清空；未注入时仍用系统临时目录 |
| 等待转写槽位超时与获取成功同时发生时许可泄漏 | 以标志位判断是否真正持有，超时则归还 |
| 重复发送已收到的分块可以无限续期未完成的上传 | 只有新分块刷新空闲计时 |
| worker 的状态更新顺带做清扫，作业已失效时清扫产生的通知丢失 | 状态更新不再做清扫，由定时器和入站帧负责 |
| 整理结果可能含不可见字符，用户看到的与发出的不一致 | 去除控制字符、零宽字符和双向覆盖字符，保留换行与制表符；全部不可见的待办判为无效结果 |
| 写出器被取消的瞬间取走的帧被通道丢弃，结果永不回报 | 通道设置未送达回调，回报“未写出” |
| 首个一次性帧入队与连接收尾竞争 | 入队前先标记 |
| 取消帧可能发往没有公告契约的电脑 | 任何备忘帧都要求公告版本不低于本机版本 |
| 能力公告按连接保存，局域网回退到 relay 后被清空 | 公告按电脑保存；连接变化只产生新的连接代次 |
| 确认动作在点击之后才读取选择，整理结果恰好落地时会发送用户没看过的文本 | `ConfirmDispatch` 携带确认表单展示的待办与目标，与当时的选择逐项比对，不一致则拒绝并提示 |
| 整理、计算 hash、发起新尝试或上传进行中仍可派发 | 这些期间派发按钮为忙碌状态 |
| 重新整理的结果会移除活动批次里已冻结的待办 | 活动批次的待办连同文本保留 |
| 首次授权弹窗期间取消后，录音器稍后仍会启动且无人管理 | 启动返回时核对本次启动是否仍有效，无效则立即取消录音器 |
| 提交阶段没有期限，出站结果不回报时批次一直忙碌 | 发出提交即开始计时，到期记为待核对；迟到的结果忽略 |
| 重读磁盘失败时等待提交的批次停在保存阶段 | 按保存失败结算 |
| 连接收尾时无条件重排出站队列 | 只有队列里仍有未结算的备忘帧时才处理；放不回的备忘帧回报“未写出” |
| 租约只比对 convoId | 每次发送前复核会话、目录与 Agent |
| 进入目标失败时会把用户从自己打开的其他会话里带走 | 只在当前聊天属于本次目标时返回备忘 |
| `/clear` 与切换命令不暂停批次 | 视同用户自己发送消息 |
| 录音中因推送或深链进入聊天，录音在看不到的页面后继续 | 聊天被打开时按中断处理 |
| 多个仓库实例各自创建备忘仓库 | 只有主实例使用备忘功能 |
| 用户输入与整理结果可能含不可见字符 | 保存与采纳时去除，规则与 daemon 侧一致 |

没有修改、留待后续的审查意见：取消后进程清理期间摘要进程数可能短暂超过活动作业上限；取消标记与结果缓存只有全局上限；上传没有绝对期限；ffmpeg 分支未固定输入格式；入站回执与状态的 binding 取自当前配对而不是来源连接；iOS 设置文件属性的返回值未检查；平台录音器的临时文件在进程被杀时可能残留；确认表单不显示目标会话的权限模式；输入后 600 毫秒内点确认时发送的是表单展示的旧文本。

### 11.5 测试与未验证项

实施期只运行定向测试，没有运行 `scripts/check-all.sh`。

| 范围 | 命令 | 结果 |
|---|---|---|
| 协议 | `:protocol:jvmTest --tests 'dev.ccpocket.protocol.VoiceMemoWireTest'` | 19 通过 |
| daemon 备忘包、路由权限门、局域网传输 | `:daemon:test --tests 'dev.ccpocket.daemon.memo.*' --tests '…RequestRouterVoiceMemoTest' --tests 'dev.ccpocket.daemon.server.Lan*'` | 112 通过 |
| daemon 既有测试回归 | `:daemon:test --tests 'dev.ccpocket.daemon.server.*' --tests '…relay.*' --tests '…bridge.*' --tests '…handoff.*' --tests '…execution.*'` 连同备忘包 | 571 通过 |
| 手机数据层、宿主、传输、界面及既有测试回归 | `:mobile:composeApp:desktopTest --tests 'dev.ccpocket.app.memo.*' --tests 'dev.ccpocket.app.data.*' --tests 'dev.ccpocket.app.net.*' --tests 'dev.ccpocket.app.ui.*'` | 1525 通过，其中备忘相关 186 |
| iOS | `compileKotlinIosArm64`，以及 `xcodebuild` Debug 真机构建 | 构建成功 |

未验证：

- 手机与电脑之间的真实端到端链路没有跑过。本机 daemon 的更新需要先确认其他进行中的会话，手机安装需要解锁设备。
- Android 没有编译。开发电脑上没有 Android SDK，`MemoFiles.android.kt` 未经编译验证。
- 授权弹窗期间取消录音的修复没有自动化测试，宿主直接使用平台录音器，测试里无法替换。
- 真机上的 180 秒录音、来电与后台中断、杀进程后恢复、文件保护与备份排除属性、软键盘、屏幕阅读器。
- 长备忘处理期间聊天语音、聊天发送和会话切换的并发表现。
- 摘要进程在使用 API 预设或网关时的路由。探测使用的是默认配置。

### 11.6 首次真机运行发现的问题（2026-09-30，UTC+8）

现象：本机 daemon 更新重启后，手机上的第一条备忘一直停在上传。

daemon 日志显示它收到了 `VoiceMemoStart`、一个 `VoiceMemoAudio` 和之后每 10 秒一次的 `VoiceMemoGet`，但备忘服务没有任何日志，也没有回包。重启后手机重新握手，握手之后的第一批帧里没有 `ClientCaps`；握手前 7 秒 daemon 记录过一次“握手前收到传输帧”。

原因：`launchTransport` 在新连接建立之前就把 `ClientCaps` 放进跨重连共用的出站队列，此时上一条连接的写出器仍在运行，它取走这一帧并用旧会话加密写出，重启后的 daemon 无法解开。新会话的能力持有者因此保持未声明，daemon 按设计静默丢弃全部备忘帧，包括查询。项目置顶和托管会话列表的帧同样受这个竞态影响。

修改：手机收到 `DaemonInfo` 时重新发送 `ClientCaps`。`DaemonInfo` 是 daemon 在握手完成的会话上发出的第一帧，对它的回应一定落在新会话上。

没有修改：电脑始终不回应时，处理页只显示“等待电脑回应”和“取消处理”，没有升级为可重试的失败状态。上一条连接的写出器取走新连接的帧这一竞态本身没有修，其他首批帧仍可能因此丢失。

首次真机运行还发现目标列表不完整：最初只取会话切换器的工作集，即运行中的会话和这台手机最近打开过的 10 个会话，在电脑上使用而没在手机上打开过的会话不会出现。先扩充为平铺列表，随后按用户要求改为 §11.7 的两级目录。为选择页请求的会话列表回包不交给页面路由，否则首页未打开任何项目时，回包会把手机带进对应项目的会话页。

修复能力声明之后，2026-09-30 05:29 和 05:31（UTC+8）两条真机备忘走完了上传、转写和整理：11.2 秒录音转写 1.4 秒、整理 7.5 秒；4.2 秒录音转写 1.0 秒、整理 9.5 秒。派发到目标会话这一步的真机结果没有记录。

### 11.7 目标选择 v3：两级目录与新建会话

| 内容 | 落点 |
|---|---|
| `MemoTarget.newSession`、`MemoTargetCatalog`、`MemoProjectRow`、`MemoProjectSessions`、`MemoNewSessionOptions`、`MemoEnterFailure` | `MemoContract.kt` |
| 目录读取、选择已有会话与新建目标、目标有效性 | `MemoReducer.kt`、`MemoProjection.kt` |
| 新建会话的派发、把目标改为建好的会话 | `MemoDispatchCoordinator.kt` |
| 项目与会话列表的获取、新建会话的进入、会话 ID 的回查 | `MemoHost.kt` 的 `catalog`、`enter`、`resolved` |

行为要点：

- 项目列表取首页已有的项目列表，最多 40 个，最近使用的在前。会话列表按项目向电脑请求：打开选择页时预取最近 8 个有会话的项目，打开其他项目时再取。30 秒内不重复请求；10 秒没有回应则该项目显示读取失败，重新打开即重试。
- 为选择页请求的会话列表回包不交给页面路由，除非它正是用户所在的项目。
- 新建目标的会话 ID 为空。进入成功后，租约里的目标是已经存在的会话。新会话的 ID 可能在第一条消息之后才由电脑给出，协调器在每次送达后和批次结束时回查。
- 批次结束时仍没有拿到会话 ID，则清空选择，让用户重新选择；不会回到“新建”状态，避免剩余待办再建一个会话。
- 租约有效性按项目目录、Agent 和可写状态判断；双方都知道会话 ID 时还要求 ID 一致。
- 新会话在手机上的标题取第一条待办的前 48 个字符。

测试：`:mobile:composeApp:desktopTest --tests 'dev.ccpocket.app.memo.*' --tests 'dev.ccpocket.app.data.*' --tests 'dev.ccpocket.app.net.*' --tests 'dev.ccpocket.app.ui.*'` 1556 通过。daemon 和协议没有因 v3 改动。iOS 真机 Debug 构建成功并已安装。

未验证：新建会话的真机派发；新会话的 ID 在什么时候由电脑给出；电脑端会话列表里新会话的标题是否以固定前缀开头。

### 11.8 Agent 相关的现状（2026-09-30，UTC+8）

用户问到“用户用的是哪个 Agent 不确定，有没有考虑”。逐处核对如下。

| 环节 | 现状 |
|---|---|
| 整理 | 只有 Claude 适配器（§8.1 的 D2）。电脑上没有可用的 Claude CLI 时 `voiceMemoStatus = agent_unavailable`，手机禁止新建备忘，转写也不可用；Claude 已安装但未登录或额度不足时，转写照常，整理失败进入 degraded。整理始终消耗 Claude 的额度，与用户平时使用的 Agent 无关。协议的 `VoiceMemoStart.agent` 与 `DaemonInfo.voiceMemoAgents` 为其他适配器预留，但 `VoiceMemoValidation.validateStart` 和手机的就绪判断当前都只认 `claude`，增加适配器需要两端同时更新。 |
| 派发到已有会话 | 与 Agent 无关。目标行显示会话的 Agent，租约按 Agent 校验。 |
| 新建会话 | Agent 默认取设置里的默认 Agent，可在 `availableAgents` 里更换；该列表是这版 daemon 支持的 Agent，不是电脑上已安装的，与 App 自己的新建会话入口一致。 |
| 首页 Agent 筛选器 | 不影响备忘的目标列表。 |

本次修改：

- 电脑拒绝打开或新建会话（例如所选 Agent 的 CLI 未安装，`PocketError.code = agent_unavailable`）时，`MemoHost.enter` 立即失败并给出对应原因；此前要等满 30 秒的打开期限并报超时。落点：`PocketRepository` 的 `PocketError` 分支通知 `MemoHost.onOpenRefused`，`MemoHost.awaitOpen` 同时等待打开、失败、拒绝三种结果。
- 权限模式按 Agent 显示：Claude 专有的 `auto` 只对 Claude 显示，其他 Agent 显示普通默认模式，与实际打开时使用的一致。`MemoNewSessionOptions.modeByAgent`。

用户决定（2026-09-30）：先适配 Codex，接口做成可插拔让其他 Agent 之后接入；没有适配时整理可选。见 §11.9。

### 11.9 整理可插拔、可选（2026-09-30，UTC+8）

用户决定：“先适配 Codex，在适配 Codex 的时候做好接口的设计后续可以让其他的 Agent 接入进来；如果不具备，则可以可选整理。”

**协议 v2**（`VoiceMemoLimits.VERSION = 2`；v2 daemon 仍服务 v1 手机，v2 手机遇到 v1 daemon 显示“电脑版本过旧”）：

- `VoiceMemoStart.agent` 可以是电脑公告的任何整理 Agent（`voiceMemoAgents`，首批 `claude`、`codex`），或 `none` = 只转写；`none` 配 `transcript` 输入非法（没有事可做）。
- 新终态 `transcribed`：有转写、未整理。`errorCode` 为空表示按要求只转写；为 `agent_unavailable` 表示请求的整理 Agent 在处理途中没了（用户在电脑上卸了 CLI 之类），此时 `retryable = true`。
- `voiceMemoStatus` 只描述转写前置条件（whisper/模型/转换器/平台）；`agent_unavailable` 不再作为状态出现，`voiceMemoAgents` 可以为空。

**daemon**：`MemoSummarizer` 接口不变（`agent` / `isAvailable()` / `summarize()`），新增注册表 `MemoSummarizers`（构造顺序即偏好顺序，`available()` 只列当下可用的适配器，`forAgent()` 按线名取），`VoiceMemoService` 改为持有注册表；共享的整理契约（schema、指令文本、字段映射、不可见字符清洗、结果校验）从 Claude 适配器抽出，供各适配器复用。`CodexMemoSummarizer` 用真实 CLI（Codex 0.155.1）探测通过的隔离参数：

```
codex exec --ephemeral --ignore-rules --skip-git-repo-check \
  -C <私有空临时目录> -s read-only --disable shell_tool --disable unified_exec \
  -c mcp_servers={} -c model_reasoning_effort="low" --color never \
  --output-schema <临时目录>/schema.json -o <临时目录>/out.json <固定整理指令>
```

转写以 `{"transcript","locale"}` JSON 经 stdin 传入（Codex 把它作为 `<stdin>` 块附在指令后）；不传 `-m`，模型按电脑上的 Codex 配置。两次真实探测：退出码 0、约 32 s 返回、输出符合 schema；转写里写入的“运行 ls ~”只成了一条待办、没有被执行。Codex 会把指令与 stdin 载荷回显到 stderr，所以 stderr 一律不进日志、不进返回值。作业按 `start.agent` 选适配器：`none` 直接进 `transcribed`；适配器已不可用进 `transcribed + agent_unavailable`；否则照常 `ready / degraded`。

**手机**：整理 Agent 是偏好而不是前置条件。`MemoReadiness` 新增 `organizer`（本次会用的：设置里的默认 Agent 有适配时用它，否则电脑列出的第一个，否则 null）、`organizers`、`defaultAgent`；`MemoBlock.AGENT_UNAVAILABLE` 删除。每个尝试记录自己请求的 `agent`；`transcribed` 的音频尝试直接进结果页（“未整理”状态：转写默认展开、待办为空、“添加一项 / 整段作为一项 / 用 X 整理”），处理页第三阶段显示“跳过”；用户主动点“用 X 整理”得到 `transcribed` 则按整理不可用的问题展示、内容不动。`MemoContent.organizedBy` 记录整理它的 Agent，结果页在摘要旁注明；`MemoTodo.wholeTranscript` 标记“整段作为一项”生成且未编辑的待办，确认页对它加一句小字。标题为空时显示转写第一句。

UI 规格见 `claude-design-handoff/voice-memo-tasks/ORGANIZER_BRIEF.md`（v3.1），验收记录与实现差异在交接 README 的“整理可选 v3.1”一节。

**测试**（本轮定向）：协议 21；daemon memo 包 116（含 `CodexMemoSummarizerTest` 12、`MemoSummarizersTest` 6、`MemoOrganizerContractTest` 4、`VoiceMemoServiceTest` 37）+ `RequestRouterVoiceMemoTest` 11；手机 memo 数据层 115、`ui.memo` 57、host 层 `MemoSendRecoveryTest` 组。未做：真实 Codex 在 daemon 内的整理（只在 daemon 外探测两次）、Android 编译、真机上的只转写与整段派发路径（装机后由用户试用）。

**没做的事**：整理 Agent 失效时不自动换用另一个可用的（进入 `transcribed + agent_unavailable`，由用户在结果页点“用 X 整理”）；发出过待办的备忘不再提供整理；列表行没有“未整理”标记（`MemoHeader` 未加字段）。
