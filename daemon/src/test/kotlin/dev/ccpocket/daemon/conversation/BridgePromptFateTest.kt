package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #285 的回归门禁：桥接请求的「轮次归属」凭证链。恢复带遗留后台任务的长会话时，CLI 会先跑一个
 * 零轮结算（孤儿 task_notification ＋ 空文本 result）——那个终态帧与新请求同 convoId 却不属于它。
 * [Conversation.promptFate] 是归属门的唯一凭证来源：账本里没等到消费凭证（UserReplay / 本地命令回执）
 * 的请求必须保持 PENDING，此时（a）终态帧不得结算它，（b）[Conversation.isBusy] 必须扣住进程——
 * closeIfIdle 在这一刻放行就是把 CLI 队列里的真实请求连坐杀掉（「(无回复)」＋进程被关的现场）。
 *
 * 授权模式覆盖：完全信任（trust 开）走 [Conversation.sendTrustedBridgePrompt]，逐请求审批（trust 关）
 * 与智能审核在 handOff 之后与信任路径共用同一条 sendPromptInternal→账本→消费链，凭证行为相同。
 */
class BridgePromptFateTest {

    private val init = """{"type":"system","subtype":"init","session_id":"s-fate","cwd":"/tmp","model":"claude-sonnet-5"}"""

    // 冷恢复时遗留任务的孤儿通知：task_id 在本进程的流里从未 task_started 过
    private val leftoverNotification =
        """{"type":"system","subtype":"task_notification","task_id":"stale-1","tool_use_id":"stale-tu","status":"completed","output_file":"/tmp/o","summary":"done"}"""

    // 零轮结算的空文本成功终态——#285 现场里冒充新请求结果的那一帧
    private val settlementResult =
        """{"type":"result","subtype":"success","is_error":false,"result":"","usage":{"input_tokens":1,"output_tokens":0}}"""

    private fun replay(text: String) = """{"type":"user","message":{"content":"$text"}}"""
    private fun result(text: String) =
        """{"type":"result","subtype":"success","is_error":false,"result":"$text","usage":{"input_tokens":1,"output_tokens":1}}"""

    /** 分段吐流：每收到一次 sendPrompt（写一行 stdin）放出一段脚本行——多轮追问必须逐段推进，
     *  否则 cat 一次性倒完，第二轮的 replay 会先于第二次 send 被泵吃掉，账本永远配不上。 */
    private class StagedBackend(segments: List<List<String>>, dir: Path) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        private var io: AgentIo? = null
        private val script: Path
        init {
            val file = dir.resolve("stream.jsonl")
            file.writeText(segments.flatten().joinToString("\n") + "\n")
            var from = 1
            val stages = segments.joinToString("; ") { seg ->
                val to = from + seg.size - 1
                "read gate; sed -n '${from},${to}p' '${file.absolutePathString()}'".also { from = to + 1 }
            }
            script = dir.resolve("run.sh").apply { writeText("$stages; sleep 30\n") }
        }
        override fun processBuilder(spec: AgentSpec): ProcessBuilder = ProcessBuilder("sh", script.absolutePathString())
        override suspend fun attach(io: AgentIo, spec: AgentSpec) { this.io = io }
        override suspend fun parse(line: String): List<AgentEvent> = StreamParser.parse(line)
        override suspend fun sendPrompt(text: String, images: List<ImageData>) { io?.writeLine("go") }
        override suspend fun interrupt() {}
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) {}
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = true
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = Path.of(workdir)
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
        override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = emptyList()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    private fun harness(
        segments: List<List<String>>,
        origin: String? = null,
        body: suspend (Conversation, () -> List<Frame>) -> Unit,
    ) = runBlocking {
        val dir = Files.createTempDirectory("ccp-fate")
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation(
            convoId = "cFate", initialWorkdir = Files.createTempDirectory("ccp-fate-wd"),
            initialMode = PermissionMode.DEFAULT,
            initialSink = { f -> synchronized(frames) { frames.add(f) } },
            parentScope = scope, backend = StagedBackend(segments, dir),
            origin = origin,
        )
        try {
            convo.open(resumeId = null, model = null)
            body(convo) { synchronized(frames) { frames.toList() } }
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    private suspend fun awaitTurnDones(n: Int, frames: () -> List<Frame>): List<TurnDone> {
        withTimeout(10_000) { while (frames().count { it is TurnDone } < n) delay(20) }
        return frames().filterIsInstance<TurnDone>()
    }

    @Test
    fun leftover_settlement_leaves_the_request_pending_and_the_process_held() {
        if (isWindows()) return // 脚本后端经 sh 运行
        // 冷恢复现场：init → 孤儿通知 → 空终态。请求的 prompt 尚无 UserReplay。
        harness(listOf(listOf(init, leftoverNotification, settlementResult))) { convo, frames ->
            convo.sendPrompt("修复登录页", promptId = "req-1")
            val done = awaitTurnDones(1, frames)
            assertTrue(done.single().finalText.isNullOrBlank(), "结算轮必须是空文本终态才构成 #285 现场")
            // 归属凭证：终态帧到了，但请求未开始执行——引擎的归属门凭这个 PENDING 拒绝结算/释放。
            // （进程保活由引擎侧负责：槽位安装即 cancelRelease，未消费期间不再武装新的释放任务。）
            assertEquals(PromptFate.PENDING, convo.promptFate("req-1"))
        }
    }

    @Test
    fun the_real_turn_settles_the_request_after_the_settlement_noise() {
        if (isWindows()) return
        // 完整冷恢复：结算噪音之后，CLI 消费请求（UserReplay）并给出真实终态
        val segments = listOf(listOf(init, leftoverNotification, settlementResult, replay("修复登录页"), result("已修好")))
        harness(segments) { convo, frames ->
            convo.sendPrompt("修复登录页", promptId = "req-1")
            val done = awaitTurnDones(2, frames)
            assertEquals(PromptFate.CONSUMED, convo.promptFate("req-1"), "UserReplay 是消费凭证")
            assertEquals("已修好", done.last().finalText, "真实终态必须还在、且可归属给这条请求")
        }
    }

    @Test
    fun consecutive_followups_each_get_their_own_attribution() {
        if (isWindows()) return
        // 连续追问：两轮各自记账、各自消费——第二轮的凭证不因第一轮已结算而丢失
        val segments = listOf(
            listOf(init, replay("第一问"), result("答一")),
            listOf(replay("第二问"), result("答二")),
        )
        harness(segments) { convo, frames ->
            convo.sendPrompt("第一问", promptId = "req-1")
            awaitTurnDones(1, frames)
            assertEquals(PromptFate.CONSUMED, convo.promptFate("req-1"))
            convo.sendPrompt("第二问", promptId = "req-2")
            val done = awaitTurnDones(2, frames)
            assertEquals(PromptFate.CONSUMED, convo.promptFate("req-2"))
            assertEquals("答二", done.last().finalText)
        }
    }

    @Test
    fun trusted_handoff_uses_the_same_consumption_ledger() {
        if (isWindows()) return
        // trust 开：完全信任 hand-off 与逐请求审批共用同一条账本→消费链
        val segments = listOf(listOf(init, replay("发个版"), result("好了")))
        harness(segments, origin = "feishu-test") { convo, frames ->
            assertTrue(convo.sendTrustedBridgePrompt("发个版", promptId = "req-t"), "空闲会话的信任 hand-off 必须成功")
            awaitTurnDones(1, frames)
            assertEquals(PromptFate.CONSUMED, convo.promptFate("req-t"))
        }
    }

    @Test
    fun a_daemon_intercepted_command_counts_as_consumed() = runBlocking {
        // 本地命令（/model 等）没有 UserReplay，但它的回执 TurnDone 属于这条请求——
        // 归属门若把它当遗留帧丢掉，飞书侧的 /model 就永远没有回复
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation(
            convoId = "cCmd", initialWorkdir = Files.createTempDirectory("ccp-fate-cmd"),
            initialMode = PermissionMode.DEFAULT,
            initialSink = { f -> synchronized(frames) { frames.add(f) } },
            parentScope = scope,
            backend = StagedBackend(listOf(listOf(init)), Files.createTempDirectory("ccp-fate-cmd-b")),
        )
        try {
            convo.sendPrompt("/model", promptId = "req-c") // 拦截处理，不起进程
            assertEquals(PromptFate.CONSUMED, convo.promptFate("req-c"))
            assertTrue(synchronized(frames) { frames.any { it is TurnDone } }, "本地命令必须有回执终态")
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    @Test
    fun an_unknown_prompt_is_neither_pending_nor_consumed() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation(
            convoId = "cU", initialWorkdir = Files.createTempDirectory("ccp-fate-u"),
            initialMode = PermissionMode.DEFAULT, initialSink = {},
            parentScope = scope,
            backend = StagedBackend(listOf(listOf(init)), Files.createTempDirectory("ccp-fate-u-b")),
        )
        try {
            assertEquals(PromptFate.UNKNOWN, convo.promptFate("never-sent"))
        } finally {
            convo.close()
            scope.cancel()
        }
    }
}
