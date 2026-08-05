package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
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

/**
 * Re-opening a STILL-LIVE conversation must apply the caller's permission mode (issue #50's promise,
 * previously implemented only on the cold-resume path). The gap became user-visible with M5's Full
 * Control auto-expiry (approval design §17.5): a conversation alive past the TTL falls back to
 * DEFAULT, and a reattach that ignores OpenSession.mode then keeps that fallback forever — every
 * re-open of a long-lived session reads as "my Settings default (Full Auto) is ignored".
 *
 * The busy exception: peeking at a conversation mid-turn must NOT yank its mode (and with it the
 * grants + autonomy the running task is executing under) — same idle-only spirit as the reaper.
 */
class SessionRegistryReattachModeTest {

    /** Replays [script] on stdout through the REAL Conversation pump; `sleep` keeps the process alive. */
    private class ScriptedBackend(private val script: Path) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec): ProcessBuilder =
            ProcessBuilder("sh", "-c", "cat '${script.absolutePathString()}'; sleep 30")
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

    private val init = """{"type":"system","subtype":"init","session_id":"s-remode","cwd":"/tmp","model":"claude-sonnet-5"}"""
    private val toolUse =
        """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"./gradlew build"}}]}}"""
    private val result =
        """{"type":"result","subtype":"success","is_error":false,"result":"done","usage":{"input_tokens":1,"output_tokens":1}}"""

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    private fun withRegistry(
        backend: AgentBackend,
        body: suspend (SessionRegistry, dir: Path, frames: MutableList<Frame>) -> Unit,
    ) = runBlocking {
        val dir = Files.createTempDirectory("ccp-remode")
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { backend }))
        try {
            body(registry, dir, frames)
        } finally {
            registry.closeAll()
            scope.cancel()
        }
    }

    private suspend fun awaitFrame(frames: MutableList<Frame>, match: (Frame) -> Boolean) = withTimeout(10_000) {
        while (synchronized(frames) { frames.none(match) }) delay(20)
    }

    @Test
    fun reattach_applies_the_callers_mode_to_an_idle_conversation() {
        if (isWindows()) return // stubs run via sh/cat
        // the M5 shape: the convo's live mode (DEFAULT, as after a Full Control expiry) differs from
        // the mode the re-open carries (the phone's persisted Settings default)
        val script = Files.createTempDirectory("ccp-remode-fx").resolve("stream.jsonl")
            .apply { writeText(listOf(init, toolUse, result).joinToString("\n") + "\n") } // turn completes → idle
        withRegistry(ScriptedBackend(script)) { registry, dir, frames ->
            val sink = OutboundSink { f -> synchronized(frames) { frames.add(f) } }
            val convoId = registry.open(OpenSession(workdir = dir.toString(), mode = PermissionMode.DEFAULT), sink)
            registry.sendPrompt(SendPrompt(convoId = convoId, text = "run"))
            awaitFrame(frames) { it is TurnDone }
            val again = registry.open(
                OpenSession(workdir = dir.toString(), resumeId = "s-remode", mode = PermissionMode.BYPASS_PERMISSIONS),
                sink,
            )
            assertEquals(convoId, again, "a live session must reattach, not fork a second conversation")
            assertEquals(PermissionMode.BYPASS_PERMISSIONS, registry.modeOf(convoId), "an idle reattach must apply the caller's mode")
        }
    }

    @Test
    fun reattach_leaves_a_busy_conversations_mode_untouched() {
        if (isWindows()) return
        val script = Files.createTempDirectory("ccp-remode-fx").resolve("stream.jsonl")
            .apply { writeText(listOf(init, toolUse).joinToString("\n") + "\n") } // NO result: mid-turn
        withRegistry(ScriptedBackend(script)) { registry, dir, frames ->
            val sink = OutboundSink { f -> synchronized(frames) { frames.add(f) } }
            val convoId = registry.open(OpenSession(workdir = dir.toString(), mode = PermissionMode.DEFAULT), sink)
            registry.sendPrompt(SendPrompt(convoId = convoId, text = "run"))
            awaitFrame(frames) { it is ToolEvent }
            val again = registry.open(
                OpenSession(workdir = dir.toString(), resumeId = "s-remode", mode = PermissionMode.BYPASS_PERMISSIONS),
                sink,
            )
            assertEquals(convoId, again)
            assertEquals(
                PermissionMode.DEFAULT, registry.modeOf(convoId),
                "peeking at a running task must not change the mode it executes under",
            )
        }
    }

    @Test
    fun reattach_applies_the_native_auto_mode_to_a_lazy_conversation() {
        if (isWindows()) return
        // no prompt → no process (the lazy open, issue #61): the pre-first-turn reattach path
        val script = Files.createTempDirectory("ccp-remode-fx").resolve("stream.jsonl")
            .apply { writeText(init + "\n") }
        withRegistry(ScriptedBackend(script)) { registry, dir, frames ->
            val sink = OutboundSink { f -> synchronized(frames) { frames.add(f) } }
            val convoId = registry.open(OpenSession(workdir = dir.toString(), mode = PermissionMode.DEFAULT), sink)
            val again = registry.open(
                OpenSession(
                    workdir = dir.toString(), resumeId = convoId,
                    mode = PermissionMode.DEFAULT, permissionMode = CLAUDE_PERMISSION_MODE_AUTO,
                ),
                sink,
            )
            assertEquals(convoId, again)
            // the first open's announce is async, so frame ORDER is racy — wait for the reattach's
            // announce by content instead (times out = the native mode was dropped)
            awaitFrame(frames) { it is SessionLive && it.permissionMode == CLAUDE_PERMISSION_MODE_AUTO }
        }
    }
}
