package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.BackgroundJobs
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.JobStatus
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The unprompted-continuation grace (issue #105 residual). Two probed CLI behaviors (2.1.206) start a
 * new turn with NO sendPrompt to arm `executing`: plan mode keeps working after its premature `result`
 * (issue #55's research → AskUserQuestion flow), and a completed background task starts a follow-up
 * turn. Right after the shield-clearing line (that result / the task's terminal event) the conversation
 * looks idle — executing false, no jobs, no pending ask — and only the activity clock kept the reaper
 * away, though the continuation's first API call can be stdout-silent past the 90s idle window (retry
 * backoff). isBusy() must hold through a bounded grace at those trigger points — and ONLY there: a
 * default-mode turn end grants nothing, a dead process voids the grace, and the grace itself expires.
 */
class ConversationContinuationGraceTest {

    private val init = """{"type":"system","subtype":"init","session_id":"s-grace","cwd":"/tmp","model":"claude-sonnet-5"}"""
    private val result =
        """{"type":"result","subtype":"success","is_error":false,"result":"plan drafted","usage":{"input_tokens":1,"output_tokens":1}}"""
    private val bgToolUse =
        """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"bg1","name":"Bash","input":{"command":"sleep 20 && make","run_in_background":true}}]}}"""
    private val taskStarted =
        """{"type":"system","subtype":"task_started","task_id":"bzt1","tool_use_id":"bg1","description":"make","task_type":"local_bash"}"""
    private val taskCompleted =
        """{"type":"system","subtype":"task_notification","task_id":"bzt1","tool_use_id":"bg1","status":"completed","output_file":"/tmp/o","summary":"done"}"""

    private class ScriptedBackend(private val script: Path, private val thenExit: Boolean) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec): ProcessBuilder =
            if (thenExit) ProcessBuilder("sh", "-c", "cat '${script.absolutePathString()}'")
            else ProcessBuilder("sh", "-c", "cat '${script.absolutePathString()}'; sleep 30")
        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = StreamParser.parse(line)
        override suspend fun sendPrompt(text: String, images: List<ImageData>) {}
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
        lines: List<String>,
        mode: PermissionMode,
        graceMs: Long,
        thenExit: Boolean = false,
        until: (List<Frame>) -> Boolean,
        body: suspend (Conversation) -> Unit,
    ) = runBlocking {
        val script = Files.createTempDirectory("ccp-grace-fx").resolve("stream.jsonl")
            .apply { writeText(lines.joinToString("\n") + "\n") }
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation(
            convoId = "cGrace", initialWorkdir = Files.createTempDirectory("ccp-grace"), initialMode = mode,
            initialSink = { f -> synchronized(frames) { frames.add(f) } },
            parentScope = scope, backend = ScriptedBackend(script, thenExit),
            continuationGraceMs = graceMs,
        )
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("go") // lazy start: launches the scripted process
            withTimeout(10_000) {
                while (!until(synchronized(frames) { frames.toList() })) delay(20)
            }
            body(convo)
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    private fun turnDone(fs: List<Frame>) = fs.any { it is TurnDone }

    @Test
    fun plan_mode_result_arms_a_bounded_grace() {
        if (isWindows()) return // stubs run via sh/cat
        harness(listOf(init, result), PermissionMode.PLAN, graceMs = 1_500, until = ::turnDone) { convo ->
            // the premature result cleared `executing`, no ask/jobs — the grace alone must hold isBusy
            assertFalse(convo.isExecuting(), "result must clear executing")
            assertTrue(convo.isBusy(), "plan-mode turn end must expect the unprompted continuation")
            delay(2_500) // …but the hold is bounded: no continuation came, the grace lapses
            assertFalse(convo.isBusy(), "an expired grace must release the conversation")
        }
    }

    @Test
    fun default_mode_result_grants_no_grace() {
        if (isWindows()) return
        // only plan mode has the premature-result behavior — a normal turn end stays promptly reapable
        harness(listOf(init, result), PermissionMode.DEFAULT, graceMs = 60_000, until = ::turnDone) { convo ->
            assertFalse(convo.isBusy(), "a default-mode turn end must not hold the conversation")
        }
    }

    @Test
    fun background_task_completion_arms_the_grace() {
        if (isWindows()) return
        // the settled job releases hasBackgroundWork, but the CLI's follow-up turn (probed 2.1.206:
        // a fresh init lands 0.1s after task_notification) is still coming — grace bridges the hand-off.
        // Wire order as probed: task_started at launch, the turn's result, the completion notification.
        val lines = listOf(init, bgToolUse, taskStarted, result, taskCompleted)
        val settled = { fs: List<Frame> ->
            fs.filterIsInstance<BackgroundJobs>().any { b -> b.jobs.any { it.status == JobStatus.DONE } }
        }
        harness(lines, PermissionMode.DEFAULT, graceMs = 60_000, until = settled) { convo ->
            assertFalse(convo.isExecuting())
            assertFalse(convo.hasBackgroundWork(), "the completed job must not be what holds the session")
            assertTrue(convo.isBusy(), "a just-completed bg task must hold the session for the follow-up turn")
        }
    }

    @Test
    fun dead_process_voids_the_grace() {
        if (isWindows()) return
        // no process = no continuation can ever arrive; a fresh grace must not shield a corpse
        harness(listOf(init, result), PermissionMode.PLAN, graceMs = 60_000, thenExit = true,
            until = { fs -> turnDone(fs) && fs.any { it is PocketError } }) { convo ->
            assertFalse(convo.isBusy(), "grace must be void once the agent process is dead")
        }
    }
}
