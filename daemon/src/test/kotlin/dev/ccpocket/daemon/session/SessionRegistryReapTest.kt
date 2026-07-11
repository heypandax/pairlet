package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.ToolEvent
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
 * The idle reaper vs a turn IN FLIGHT (issue #105). `lastActivityMs` only moves when a stream-json line
 * arrives, and the CLI is routinely silent for minutes mid-turn (quiet long-running tool, one long
 * generation, API-retry backoff). The old predicate reaped on the stale clock alone once the phone went
 * offline — killing the process tree mid-task: "submitted a task, quit the app, came back to find only
 * step 1 done". A busy conversation must survive the reaper on ANY clock; a dead process clears
 * `executing` in the pump, so sparing executing conversations cannot leak dead ones.
 */
class SessionRegistryReapTest {

    /** Replays [script] on stdout through the REAL Conversation pump, then [thenExit] or stays alive. */
    private class ScriptedBackend(private val script: Path, private val thenExit: Boolean) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec): ProcessBuilder =
            if (thenExit) ProcessBuilder("sh", "-c", "cat '${script.absolutePathString()}'")
            // a real claude idles on stdin after its output — `sleep` keeps the process (and stdout) open
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

    private val init = """{"type":"system","subtype":"init","session_id":"s-reap","cwd":"/tmp","model":"claude-sonnet-5"}"""
    private val toolUse =
        """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"./gradlew build"}}]}}"""
    private val result =
        """{"type":"result","subtype":"success","is_error":false,"result":"done","usage":{"input_tokens":1,"output_tokens":1}}"""

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    /** Open + first prompt against [backend]; waits until [until] sees the collected frames. */
    private fun harness(
        backend: ScriptedBackend,
        until: (List<Frame>) -> Boolean,
        body: suspend (SessionRegistry, convoId: String) -> Unit,
    ) = runBlocking {
        val dir = Files.createTempDirectory("ccp-reap")
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { backend }))
        try {
            val convoId = registry.open(OpenSession(workdir = dir.toString()), { f -> synchronized(frames) { frames.add(f) } })
            assertTrue(registry.sendPrompt(SendPrompt(convoId = convoId, text = "run the task")))
            withTimeout(10_000) {
                while (!until(synchronized(frames) { frames.toList() })) delay(20)
            }
            body(registry, convoId)
        } finally {
            registry.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun executing_turn_with_stale_activity_clock_is_never_reaped() {
        if (isWindows()) return // stubs run via sh/cat
        val script = Files.createTempDirectory("ccp-reap-fx").resolve("stream.jsonl")
            .apply { writeText(listOf(init, toolUse).joinToString("\n") + "\n") } // NO result: the turn is mid-flight
        harness(ScriptedBackend(script, thenExit = false), until = { fs -> fs.any { it is ToolEvent } }) { registry, convoId ->
            delay(600) // stdout now silent well past idleMs — the long quiet tool run
            assertEquals(0, registry.reapIdle(idleMs = 200), "an executing turn must survive the reaper on any clock")
            assertTrue(registry.sendPrompt(SendPrompt(convoId = convoId, text = "still there?")), "conversation must still be alive")
        }
    }

    @Test
    fun idle_after_turn_end_is_still_reaped() {
        if (isWindows()) return
        val script = Files.createTempDirectory("ccp-reap-fx").resolve("stream.jsonl")
            .apply { writeText(listOf(init, toolUse, result).joinToString("\n") + "\n") } // turn completes
        harness(ScriptedBackend(script, thenExit = false), until = { fs -> fs.any { it is TurnDone } }) { registry, convoId ->
            delay(600)
            assertEquals(1, registry.reapIdle(idleMs = 200), "a genuinely idle conversation must still be reclaimed")
            assertFalse(registry.sendPrompt(SendPrompt(convoId = convoId, text = "gone?")), "reaped conversation must be gone")
        }
    }

    @Test
    fun process_death_mid_turn_clears_executing_so_the_reaper_can_reclaim() {
        if (isWindows()) return
        // the no-leak guarantee that makes sparing `executing` safe: a dead process can't hold its conversation
        val script = Files.createTempDirectory("ccp-reap-fx").resolve("stream.jsonl")
            .apply { writeText(listOf(init, toolUse).joinToString("\n") + "\n") } // mid-turn…
        harness(ScriptedBackend(script, thenExit = true), until = { fs -> fs.any { it is PocketError } }) { registry, convoId ->
            delay(600)
            assertEquals(1, registry.reapIdle(idleMs = 200), "a dead mid-turn conversation must not leak")
            assertFalse(registry.sendPrompt(SendPrompt(convoId = convoId, text = "gone?")))
        }
    }
}
