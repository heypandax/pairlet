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
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Audit 2026-10-04 (conversation M6): the stdout pump had no per-event isolation. One exception while
 * handling one line — a parser bug, a CME on a pump-shared map, a throwing hook — failed the pump coroutine
 * silently: the death branch never ran, stdout stopped being read, and the session sat "executing" forever.
 * Claude's parser is the only one of the four not wrapped in its own runCatching.
 */
class ConversationPumpIsolationTest {

    private val init = """{"type":"system","subtype":"init","session_id":"s-pump","cwd":"/tmp","model":"claude-sonnet-5"}"""
    private val poison = """{"type":"poison"}"""
    private val result = """{"type":"result","subtype":"success","is_error":false,"result":"done"}"""

    /** Plays [lines] when the prompt arrives, then either idles or exits ([exitAfter]). [parse] throws on the
     *  poison line; [onProcessEnded] throws when [endThrows]. */
    private class ScriptedBackend(
        lines: Path,
        exitAfter: Boolean,
        private val endThrows: Boolean = false,
    ) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        private var io: AgentIo? = null
        private val script = "read go; cat '${lines.absolutePathString()}'; " + if (exitAfter) "exit 3" else "sleep 30"
        override fun processBuilder(spec: AgentSpec): ProcessBuilder = ProcessBuilder("sh", "-c", script)
        override suspend fun attach(io: AgentIo, spec: AgentSpec) { this.io = io }
        override suspend fun parse(line: String): List<AgentEvent> {
            if ("poison" in line) throw IllegalStateException("parser bug")
            return StreamParser.parse(line)
        }
        override suspend fun sendPrompt(text: String, images: List<ImageData>) { io?.writeLine("go") }
        override suspend fun interrupt() {}
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) {}
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = true
        @Volatile private var thrown = false
        override suspend fun onProcessEnded(sessionId: String?) {
            // once: the death branch's cleanup fails; a later close() must still be able to stop the session
            if (endThrows && !thrown) { thrown = true; throw IllegalStateException("cleanup bug") }
        }
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
        override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = emptyList()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    private fun run(
        lines: List<String>,
        exitAfter: Boolean,
        endThrows: Boolean = false,
        until: (List<Frame>) -> Boolean,
        body: (Conversation, List<Frame>) -> Unit,
    ) = runBlocking {
        val file = Files.createTempDirectory("ccp-pump-fx").resolve("out.jsonl")
            .apply { writeText(lines.joinToString("\n", postfix = "\n")) }
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation(
            convoId = "cPump", initialWorkdir = Files.createTempDirectory("ccp-pump"),
            initialMode = PermissionMode.DEFAULT,
            initialSink = { f -> synchronized(frames) { frames.add(f) } },
            parentScope = scope, backend = ScriptedBackend(file, exitAfter, endThrows),
        )
        try {
            convo.open(resumeId = null, model = null)
            convo.sendPrompt("go")
            withTimeoutOrNull(10_000) { while (!until(synchronized(frames) { frames.toList() })) delay(20) }
            body(convo, synchronized(frames) { frames.toList() })
        } finally {
            convo.close()
            scope.cancel()
        }
    }

    @Test
    fun a_line_that_fails_to_parse_does_not_stop_the_pump() {
        if (isWindows()) return // the stub agent runs via sh/cat
        run(listOf(init, poison, result), exitAfter = false, until = { f -> f.any { it is TurnDone } }) { convo, frames ->
            assertNotNull(frames.filterIsInstance<TurnDone>().singleOrNull(), "the result after the bad line must still end the turn")
            assertFalse(convo.isExecuting(), "the turn must not stay executing")
        }
    }

    @Test
    fun a_pump_that_dies_anyway_leaves_a_terminal_state() {
        if (isWindows()) return
        run(listOf(init), exitAfter = true, endThrows = true, until = { f -> f.any { it is PocketError } }) { convo, frames ->
            assertTrue(frames.any { it is PocketError }, "the user must hear that the session stopped: $frames")
            assertFalse(convo.isExecuting(), "the turn must not stay executing")
        }
    }
}
