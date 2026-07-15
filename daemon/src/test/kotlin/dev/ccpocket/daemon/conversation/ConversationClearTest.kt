package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Issue #149 — `/clear` starts a FRESH session, so the announces that follow the wipe must not carry
 * the wiped session's context occupancy: the phone seeds its "Context NN%" statusline from
 * SessionLive.contextUsed, and a stale value pinned the badge at the pre-clear % (TurnDone never
 * resets it either — zero-usage frames are deliberately ignored there).
 */
class ConversationClearTest {

    private fun win() = System.getProperty("os.name").lowercase().contains("win")

    /** Resumes always report a prior occupancy; every launch is an idle stub process. */
    private class ResumedBackend : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec): ProcessBuilder = ProcessBuilder("sh", "-c", "sleep 30")
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
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = 80_000L
    }

    @Test
    fun clear_resets_announced_context_usage() {
        if (win()) return
        runBlocking {
            val dir = Files.createTempDirectory("ccp-clear")
            val frames = ArrayList<Frame>()
            val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val convo = Conversation(
                convoId = "cClear", initialWorkdir = dir, initialMode = PermissionMode.DEFAULT,
                initialSink = { f -> synchronized(frames) { frames.add(f) } },
                parentScope = scope, backend = ResumedBackend(),
            )
            try {
                fun snapshot() = synchronized(frames) { frames.toList() }
                suspend fun await(cond: (List<Frame>) -> Boolean) =
                    withTimeout(10_000) { while (!cond(snapshot())) delay(20) }

                convo.open(resumeId = "resumed-sid", model = null) // lazy open — announces, spawns nothing
                await { fs -> fs.any { it is SessionLive } }
                // sanity: the resume announce DOES seed the prior occupancy (the value /clear must drop)
                assertEquals(80_000L, (snapshot().first { it is SessionLive } as SessionLive).contextUsed)

                convo.sendPrompt("/clear")
                await { fs -> fs.any { it is ConvoHistory && it.messages.isEmpty() } }
                await { fs ->
                    fs.indexOfFirst { it is ConvoHistory && it.messages.isEmpty() }
                        .let { wipe -> fs.drop(wipe + 1).any { it is SessionLive } }
                }
                val fs = snapshot()
                val wipe = fs.indexOfFirst { it is ConvoHistory && it.messages.isEmpty() }
                val announce = fs.drop(wipe + 1).first { it is SessionLive } as SessionLive
                assertNull(announce.sessionId) // the fresh session's id backfills on its first init
                assertNull(announce.contextUsed, "post-/clear announce must not carry the wiped session's occupancy")
            } finally {
                convo.close()
                scope.cancel()
            }
        }
    }
}
