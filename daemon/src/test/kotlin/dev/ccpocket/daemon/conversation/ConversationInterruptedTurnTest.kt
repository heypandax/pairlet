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
import dev.ccpocket.protocol.SessionLive
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A user-cancelled turn is not a failed one. The CLI answers an interrupt with a `<synthetic>`
 * placeholder of its own ("No response requested.") — the SAME record it writes when every API call
 * of a turn failed (issue #65). Classifying by the placeholder alone therefore mistook the user's own
 * ■/Esc for an API outage twice over:
 *
 *  1. the turn came back as a red "API request failed — the agent wrote a placeholder…", and
 *  2. it counted toward `failedTurnStreak`, so TWO stops in a row flipped a perfectly healthy session
 *     `degraded` — and the client's degraded gate then swallows the next send whole, which is what the
 *     "I switch sessions, it says interrupted, and then I have to send it three times" report was.
 *
 * Wire shapes below are the real ones (CLI 2.1.212): the interrupt's placeholder carries
 * model `<synthetic>`, and the result that follows it reports `is_error: true`.
 */
class ConversationInterruptedTurnTest {

    private val init = """{"type":"system","subtype":"init","session_id":"s-int","cwd":"/tmp","model":"claude-sonnet-5"}"""
    private val synthetic =
        """{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"No response requested."}]}}"""
    private val errorResult =
        """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"No response requested."}"""

    /**
     * Streams [beforeInterrupt] when the prompt arrives and [afterInterrupt] when the interrupt does —
     * the real ordering, where the placeholder + result exist BECAUSE the turn was cancelled. Repeats
     * for as many prompt/interrupt rounds as the test drives (one `read` per control line).
     */
    private class InterruptScriptedBackend(private val head: Path, private val tail: Path, rounds: Int) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        private var io: AgentIo? = null
        private val script = (1..rounds).joinToString("; ") {
            "read go; cat '${head.absolutePathString()}'; read stop; cat '${tail.absolutePathString()}'"
        } + "; sleep 30"
        override fun processBuilder(spec: AgentSpec): ProcessBuilder = ProcessBuilder("sh", "-c", script)
        override suspend fun attach(io: AgentIo, spec: AgentSpec) { this.io = io }
        override suspend fun parse(line: String): List<AgentEvent> = StreamParser.parse(line)
        override suspend fun sendPrompt(text: String, images: List<ImageData>) { io?.writeLine("go") }
        override suspend fun interrupt() { io?.writeLine("stop") }
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

    /** Drive [rounds] turns, each cancelled mid-flight, and hand the collected frames to [body]. */
    private fun cancelledTurns(rounds: Int, body: (List<Frame>) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("ccp-int-fx")
        val head = dir.resolve("head.jsonl").apply { writeText(init + "\n") }
        val tail = dir.resolve("tail.jsonl").apply { writeText(synthetic + "\n" + errorResult + "\n") }
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation(
            convoId = "cInt", initialWorkdir = Files.createTempDirectory("ccp-int"),
            initialMode = PermissionMode.DEFAULT,
            initialSink = { f -> synchronized(frames) { frames.add(f) } },
            parentScope = scope, backend = InterruptScriptedBackend(head, tail, rounds),
        )
        try {
            convo.open(resumeId = null, model = null)
            repeat(rounds) { round ->
                convo.sendPrompt("go") // lazy start on the first round; a queued send after that
                // cancelTurn is a no-op unless a turn is actually executing — wait for the arm, then stop it
                withTimeout(10_000) { while (!convo.isExecuting()) delay(20) }
                convo.cancelTurn()
                withTimeout(10_000) {
                    while (synchronized(frames) { frames.filterIsInstance<TurnDone>().size } <= round) delay(20)
                }
            }
            body(synchronized(frames) { frames.toList() })
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    @Test
    fun a_cancelled_turns_placeholder_is_not_reported_as_an_api_failure() {
        if (isWindows()) return // the stub agent runs via sh/cat
        cancelledTurns(rounds = 1) { frames ->
            val done = frames.filterIsInstance<TurnDone>().single()
            assertEquals(null, done.error, "the user's own ■/Esc must not surface as a turn error")
        }
    }

    @Test
    fun repeated_cancels_do_not_degrade_the_session() {
        if (isWindows()) return
        // DEGRADED_STREAK is 2 — exactly the count two stops in a row used to reach. A degrade
        // transition announces itself with a fresh SessionLive, so its absence is the assertion.
        cancelledTurns(rounds = 2) { frames ->
            assertEquals(2, frames.filterIsInstance<TurnDone>().size, "both cancelled turns must settle")
            assertTrue(frames.filterIsInstance<TurnDone>().all { it.error == null }, "neither is a failure")
            assertFalse(
                frames.filterIsInstance<SessionLive>().any { it.degraded },
                "cancelling twice must not mark the session degraded (it gates the next send client-side)",
            )
        }
    }
}
