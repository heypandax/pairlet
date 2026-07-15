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
import dev.ccpocket.protocol.PermissionAsk
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #138 push triggers, at the Conversation level:
 *  - an OWNER session's permission ask fires the AskPushHook (origin=null) with the watcher flag —
 *    previously only bridge-origin conversations did;
 *  - a burst of asks coalesces to ONE hook attempt, but a suppressed attempt (hook returned false)
 *    does NOT spend the window;
 *  - a GUEST conversation (pathScope set) never fires it;
 *  - an error-terminated turn hands the PushHook its error text (usage-limit wording included);
 *  - an unexpected agent-process death fires the PushHook with the exit summary.
 */
class ConversationPushTest {

    private val init = """{"type":"system","subtype":"init","session_id":"s-push","cwd":"/tmp","model":"claude-sonnet-5"}"""
    private fun controlRequest(id: String) =
        """{"type":"control_request","request_id":"$id","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"make test"}}}"""
    private val cleanResult =
        """{"type":"result","subtype":"success","is_error":false,"result":"done","usage":{"input_tokens":1,"output_tokens":1}}"""
    private val limitResult =
        """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"Claude AI usage limit reached|1720000000"}"""

    /** Plays [stages] one script file per prompt: each `read` gates the next stage on a sendPrompt. */
    private class ScriptedBackend(
        private val stages: List<Path>, private val thenExit: Boolean, private val dyingStderr: String? = null,
    ) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        private var io: AgentIo? = null
        override fun processBuilder(spec: AgentSpec): ProcessBuilder {
            val cats = stages.joinToString("; ") { "read go; cat '${it.absolutePathString()}'" }
            val die = dyingStderr?.let { "; echo '$it' >&2" } ?: "" // last stderr line before an unexpected exit
            return ProcessBuilder("sh", "-c", if (thenExit) "$cats$die" else "$cats; sleep 30")
        }
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

    private data class AskCall(val origin: String?, val tool: String, val watched: Boolean)
    private data class TurnCall(val finalText: String?, val error: String?)

    private class Harness(
        val convo: Conversation,
        val frames: MutableList<Frame>,
        val askCalls: CopyOnWriteArrayList<AskCall>,
        val turnCalls: CopyOnWriteArrayList<TurnCall>,
    )

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    private fun harness(
        stages: List<List<String>>,
        thenExit: Boolean = false,
        dyingStderr: String? = null,
        origin: String? = null,
        pathScope: List<String>? = null,
        headlessSink: Boolean = false, // the sole sink is the scheduler's non-watching black hole (C1)
        askPushResult: () -> Boolean = { true },
        body: suspend Harness.() -> Unit,
    ) = runBlocking {
        val dir = Files.createTempDirectory("ccp-push-fx")
        val files = stages.mapIndexed { i, lines ->
            dir.resolve("stage$i.jsonl").apply { writeText(lines.joinToString("\n") + "\n") }
        }
        val frames = ArrayList<Frame>()
        val askCalls = CopyOnWriteArrayList<AskCall>()
        val turnCalls = CopyOnWriteArrayList<TurnCall>()
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation(
            convoId = "cPush", initialWorkdir = Files.createTempDirectory("ccp-push"),
            initialMode = PermissionMode.DEFAULT,
            initialSink = if (headlessSink) {
                KeyedSink("scheduler", { f -> synchronized(frames) { frames.add(f) } }, watching = false)
            } else {
                OutboundSink { f -> synchronized(frames) { frames.add(f) } }
            },
            parentScope = scope, backend = ScriptedBackend(files, thenExit, dyingStderr),
            pushHookProvider = {
                PushHook { _, _, finalText, error -> turnCalls.add(TurnCall(finalText, error)) }
            },
            origin = origin,
            askPushHookProvider = {
                AskPushHook { _, _, o, tool, watched -> askCalls.add(AskCall(o, tool, watched)); askPushResult() }
            },
            pathScope = pathScope,
        )
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("go") // lazy start: launches the scripted process + plays stage 0
            body(Harness(convo, frames, askCalls, turnCalls))
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    private suspend fun await(what: String, cond: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!cond()) delay(20) }
        } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what")
        }
    }

    private fun Harness.askFrames() = synchronized(frames) { frames.filterIsInstance<PermissionAsk>() }

    @Test
    fun owner_session_ask_fires_the_hook_with_null_origin_and_watched_flag() {
        if (isWindows()) return // stubs run via sh/cat
        harness(stages = listOf(listOf(init, controlRequest("r1")))) {
            await("ask push hook") { askCalls.size == 1 }
            val call = askCalls.single()
            assertNull(call.origin, "an owner session's ask push carries origin=null")
            assertTrue(call.watched, "the initial sink is attached — the ask had a watcher")
            assertTrue(call.tool.isNotBlank())
            assertEquals(1, askFrames().size, "the ask frame itself still fans out to the sink")
        }
    }

    @Test
    fun a_headless_scheduler_sink_does_not_count_as_a_watcher() {
        if (isWindows()) return
        // issue #137/C1: a scheduled task opens the session with a non-watching black-hole sink. It must
        // NOT be counted as "someone is watching" — otherwise the owner ask-push is suppressed while
        // nobody can see or answer the card, and the ask times out to a safe deny (the task silently fails).
        harness(stages = listOf(listOf(init, controlRequest("r1"))), headlessSink = true) {
            await("ask push hook") { askCalls.size == 1 }
            val call = askCalls.single()
            assertNull(call.origin, "still an owner session (origin=null)")
            assertFalse(call.watched, "the headless scheduler sink is a black hole — not a watcher")
        }
    }

    @Test
    fun bridge_ask_still_carries_its_origin() {
        if (isWindows()) return
        harness(stages = listOf(listOf(init, controlRequest("r1"))), origin = "ci-bot") {
            await("ask push hook") { askCalls.size == 1 }
            assertEquals("ci-bot", askCalls.single().origin)
        }
    }

    @Test
    fun an_ask_burst_coalesces_to_one_push() {
        if (isWindows()) return
        // two asks back-to-back in the same stage: the optimistic stamp (pump-thread visible before the
        // second ask parses) must swallow the second attempt — one lock-screen alert covers the batch
        harness(stages = listOf(listOf(init, controlRequest("r1"), controlRequest("r2")))) {
            await("both ask frames") { askFrames().size == 2 }
            delay(300) // grace for a late (wrong) second hook launch to surface
            assertEquals(1, askCalls.size, "a burst of asks must coalesce into one push attempt")
        }
    }

    @Test
    fun a_suppressed_push_does_not_spend_the_coalesce_window() {
        if (isWindows()) return
        // hook says "didn't push" (phone was watching): the stamp rolls back, so the NEXT ask —
        // after the user walked away — must reach the hook again instead of being coalesced away
        harness(
            stages = listOf(listOf(init, controlRequest("r1")), listOf(controlRequest("r2"))),
            askPushResult = { false },
        ) {
            await("first ask attempt") { askCalls.size == 1 }
            delay(300) // let the rollback (in the hook coroutine, right after the call) land
            convo.sendPrompt("again") // plays stage 1 → the second ask
            await("second ask attempt after rollback") { askCalls.size == 2 }
        }
    }

    @Test
    fun guest_conversations_never_fire_the_ask_push() {
        if (isWindows()) return
        harness(
            stages = listOf(listOf(init, controlRequest("r1"))),
            origin = "guest-1", pathScope = listOf("/tmp/shared"),
        ) {
            await("guest still gets the ask frame") { askFrames().size == 1 }
            delay(300)
            assertTrue(askCalls.isEmpty(), "a guest answers its own asks — the owner is not push-nudged")
        }
    }

    @Test
    fun a_clean_turn_pushes_with_no_error() {
        if (isWindows()) return
        harness(stages = listOf(listOf(init, cleanResult))) {
            await("turn push") { turnCalls.size == 1 }
            val call = turnCalls.single()
            assertEquals("done", call.finalText)
            assertNull(call.error)
        }
    }

    @Test
    fun an_error_turn_hands_the_hook_its_error_text() {
        if (isWindows()) return
        // an is_error result whose text is the CLI's usage-limit wording: the hook must see it in
        // [error] so the relay client can word the push as a limit hit (issue #138)
        harness(stages = listOf(listOf(init, limitResult))) {
            await("turn-error push") { turnCalls.size == 1 }
            val call = turnCalls.single()
            assertTrue(call.error?.contains("usage limit reached") == true, "got: ${call.error}")
        }
    }

    @Test
    fun a_death_right_after_an_error_result_is_not_double_pushed() {
        if (isWindows()) return
        // fatal turn error → result, then the CLI exits: the failure was already pushed with the
        // result — the death within the quiet window must not fire a second alert
        harness(stages = listOf(listOf(init, limitResult)), thenExit = true) {
            await("process_exited error") { synchronized(frames) { frames.any { it is PocketError } } }
            await("the turn-error push") { turnCalls.isNotEmpty() }
            delay(300) // grace for a late (wrong) death push to surface
            assertEquals(1, turnCalls.size, "the death rode the turn's own failure push")
            assertTrue(turnCalls.single().error?.contains("usage limit reached") == true)
        }
    }

    @Test
    fun an_unexpected_process_death_pushes_the_exit_summary() {
        if (isWindows()) return
        // the scripted process exits right after init — no TurnResult ever comes; the pump's death
        // branch must fire the hook with the exit summary so a locked phone hears the session died
        harness(stages = listOf(listOf(init)), thenExit = true) {
            await("process_exited error") { synchronized(frames) { frames.any { it is PocketError } } }
            await("death push") { turnCalls.isNotEmpty() }
            val call = turnCalls.first()
            assertNull(call.finalText)
            assertTrue(call.error?.startsWith("agent process ended") == true, "got: ${call.error}")
            // and no TurnDone was fabricated for the dead turn
            assertTrue(synchronized(frames) { frames.none { it is TurnDone } })
        }
    }

    @Test
    fun a_non_usage_limit_death_keeps_stderr_out_of_the_cleartext_push() {
        if (isWindows()) return
        // security review 07-15: raw process stderr (paths / stack traces) must ride ONLY the E2E
        // PocketError, never the NotifyPush body that transits the relay in cleartext. A non-usage-limit
        // death's push reason is genericized; the full stderr still reaches the phone over the E2E frame.
        val secret = "Traceback /Users/panda/.secrets/key.pem line 42"
        harness(stages = listOf(listOf(init)), thenExit = true, dyingStderr = secret) {
            await("process_exited error") { synchronized(frames) { frames.any { it is PocketError } } }
            await("death push") { turnCalls.isNotEmpty() }
            // E2E frame carries the full stderr for debugging
            val e2e = synchronized(frames) { frames.filterIsInstance<PocketError>().first { it.code == "process_exited" } }
            assertTrue(e2e.message.contains(secret), "E2E frame should keep the stderr: ${e2e.message}")
            // the cleartext push must NOT
            val pushed = turnCalls.first().error
            assertFalse(pushed?.contains(secret) == true, "stderr leaked into the push body: $pushed")
            assertTrue(pushed?.startsWith("agent process ended") == true, "got: $pushed")
        }
    }
}
