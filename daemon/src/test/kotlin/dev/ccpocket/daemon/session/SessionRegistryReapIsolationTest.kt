package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.daemon.conversation.Conversation
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SendPrompt
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
 * `reapIdle` removes every stale conversation from the registry FIRST and closes them afterwards — so once
 * removed, nothing else will ever close one. A close that threw used to abort the loop: every conversation
 * after it stayed out of the registry with its agent process still running, an orphan nobody owns.
 */
class SessionRegistryReapIsolationTest {

    /** Replays a finished turn, then idles on an open stdout like a real CLI between turns. */
    private class IdleBackend(private val script: Path) : AgentBackend {
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
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
        override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = emptyList()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    private fun script(sid: String): Path = Files.createTempDirectory("ccp-reap-iso").resolve("stream.jsonl").apply {
        writeText(
            listOf(
                """{"type":"system","subtype":"init","session_id":"$sid","cwd":"/tmp","model":"claude-sonnet-5"}""",
                """{"type":"result","subtype":"success","is_error":false,"result":"done","usage":{"input_tokens":1,"output_tokens":1}}""",
            ).joinToString("\n") + "\n",
        )
    }

    @Test
    fun one_failing_close_does_not_orphan_the_other_reaped_sessions() = runBlocking {
        if (System.getProperty("os.name").lowercase().contains("win")) return@runBlocking // sh/cat stubs
        val scripts = ArrayDeque(listOf(script("s-iso-1"), script("s-iso-2"), script("s-iso-3")))
        val scope = CoroutineScope(Dispatchers.Default)
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { IdleBackend(scripts.removeFirst()) }))
        val seen = mutableListOf<Conversation>()
        try {
            repeat(3) {
                val frames = ArrayList<Frame>()
                val dir = Files.createTempDirectory("ccp-reap-iso-wd")
                val convoId = registry.open(OpenSession(workdir = dir.toString()), OutboundSink { f -> synchronized(frames) { frames.add(f) } })
                assertTrue(registry.sendPrompt(SendPrompt(convoId = convoId, text = "go")))
                withTimeout(10_000) { while (synchronized(frames) { frames.none { it is TurnDone } }) delay(20) }
            }
            delay(300)
            // the FIRST close the reaper attempts blows up
            registry.beforeReapClose = { c ->
                seen += c
                if (seen.size == 1) error("close blew up")
            }
            val reaped = runCatching { registry.reapIdle(idleMs = 100) }
            assertTrue(reaped.isSuccess, "one failing close must not abort the reaper: ${reaped.exceptionOrNull()}")
            assertEquals(3, reaped.getOrThrow())
            assertEquals(3, seen.size, "every removed conversation must get its close attempt")
            seen.drop(1).forEach { assertFalse(it.hasLiveProcess(), "${it.convoId} was removed but its process left running") }
        } finally {
            seen.forEach { runCatching { it.close() } } // the one whose close "failed" is ours to clean up
            registry.closeAll()
            scope.cancel()
        }
    }
}
