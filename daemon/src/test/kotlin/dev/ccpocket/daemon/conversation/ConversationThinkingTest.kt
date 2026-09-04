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
import dev.ccpocket.protocol.StreamPiece
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #345 — the `/thinking on|off|default` slash command, the tri-state it drives, and the
 * launch-baked flag it must reach on the next relaunch.
 *
 * The stub backend mirrors ClaudeBackend's contract exactly where thinking is concerned (supports
 * the toggle, demands a relaunch); the recorded AgentSpecs let the cases assert the flag actually
 * rides the relaunch-then-send path, not just the announced badge.
 */
class ConversationThinkingTest {

    private fun win() = System.getProperty("os.name").lowercase().contains("win")

    /** Claude-shaped stub: records every AgentSpec it is asked to launch. */
    private class RecordingBackend(val thinkingCapable: Boolean = true) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        val specs = CopyOnWriteArrayList<AgentSpec>()
        override fun processBuilder(spec: AgentSpec): ProcessBuilder {
            specs.add(spec)
            return ProcessBuilder("sh", "-c", "sleep 30")
        }
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

        override val supportsThinkingToggle = thinkingCapable
        override fun applyThinking(thinking: Boolean?) = true
    }

    private class Harness(backend: RecordingBackend) {
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val convo = Conversation(
            convoId = "cThink", initialWorkdir = Files.createTempDirectory("ccp-think"),
            initialMode = PermissionMode.DEFAULT,
            initialSink = { f -> frames.add(f) },
            parentScope = scope, backend = backend,
        )
        suspend fun await(cond: (List<Frame>) -> Boolean) =
            withTimeout(10_000) { while (!cond(frames.toList())) delay(20) }
        suspend fun close() {
            convo.close()
            scope.cancel()
        }
    }

    @Test
    fun slash_thinking_off_is_accepted_announced_and_reaches_the_relaunch_spec() {
        if (win()) return
        runBlocking {
            val backend = RecordingBackend()
            val h = Harness(backend)
            try {
                h.convo.open(resumeId = null, model = null)
                h.await { fs -> fs.any { it is SessionLive } }

                h.convo.sendPrompt("/thinking off")
                // the badge flips optimistically — SessionLive re-announces the new state
                h.await { fs -> fs.filterIsInstance<SessionLive>().any { it.thinking == false } }
                // and the reply turn confirms (idle session → in-chat confirmation)
                h.await { fs -> fs.any { it is dev.ccpocket.protocol.TurnDone } }

                // the change is launch-baked: the NEXT prompt relaunches with --thinking disabled in the spec
                h.convo.sendPrompt("hello there")
                h.await { backend.specs.any { it.thinking == false && it.initialPrompt == "hello there" } }
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun slash_thinking_default_clears_off_and_null_rides_the_relaunch() {
        if (win()) return
        runBlocking {
            val backend = RecordingBackend()
            val h = Harness(backend)
            try {
                h.convo.open(resumeId = null, model = null, thinking = false)
                h.await { fs -> fs.filterIsInstance<SessionLive>().any { it.thinking == false } }

                h.convo.sendPrompt("/thinking default")
                h.await { fs -> fs.filterIsInstance<SessionLive>().any { it.thinking == null } }

                h.convo.sendPrompt("again")
                h.await { backend.specs.any { it.thinking == null && it.initialPrompt == "again" } }
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun open_carries_the_initial_thinking_choice_into_the_first_launch() {
        if (win()) return
        runBlocking {
            val backend = RecordingBackend()
            val h = Harness(backend)
            try {
                h.convo.open(resumeId = null, model = null, thinking = true)
                h.convo.sendPrompt("first turn")
                h.await { backend.specs.any { it.thinking == true } }
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun unsupported_backend_never_intercepts_slash_thinking() {
        if (win()) return
        runBlocking {
            val backend = RecordingBackend(thinkingCapable = false)
            val h = Harness(backend)
            try {
                h.convo.open(resumeId = null, model = null)
                h.await { fs -> fs.any { it is SessionLive } }

                // NOT intercepted (capability-gated in tryIntercept): the text goes to the agent as an
                // ordinary prompt — one launch whose spec is the plain spawn, and no switch announcement.
                h.convo.sendPrompt("/thinking off")
                h.await { fs -> backend.specs.isNotEmpty() } // the turn spawned the (stub) agent process

                val lives = h.frames.filterIsInstance<SessionLive>()
                assertTrue(lives.none { it.thinking != null }, "an incapable backend must never announce a thinking state")
                assertTrue(backend.specs.all { it.thinking == null }, "an incapable backend must never receive the flag")
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun slash_thinking_usage_and_bad_arg_reply_without_touching_state() {
        if (win()) return
        runBlocking {
            val backend = RecordingBackend()
            val h = Harness(backend)
            try {
                h.convo.open(resumeId = null, model = null, thinking = false)
                h.await { fs -> fs.filterIsInstance<SessionLive>().any { it.thinking == false } }

                h.convo.sendPrompt("/thinking bogus")
                h.await { fs ->
                    fs.any { it is dev.ccpocket.protocol.AssistantChunk && (it.piece as? StreamPiece.Text)?.text?.contains("Unsupported thinking") == true }
                }
                // state untouched: still off
                assertTrue(h.frames.filterIsInstance<SessionLive>().all { it.thinking == false })

                h.convo.sendPrompt("/thinking")
                h.await { fs ->
                    fs.any { it is dev.ccpocket.protocol.AssistantChunk && (it.piece as? StreamPiece.Text)?.text?.contains("Usage: /thinking") == true }
                }
                assertEquals(false, h.frames.filterIsInstance<SessionLive>().last().thinking, "usage reply must not flip the state")
            } finally {
                h.close()
            }
        }
    }
}
