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
import dev.ccpocket.protocol.PromptAck
import dev.ccpocket.protocol.SessionSummary
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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The promptId idempotency contract (issue #66) that the client's #104 fix depends on: a client RESEND
 * carrying a promptId the LIVE conversation already delivered is re-ACKED, never re-run. This is exactly
 * why the phone's "no response — resend" recovery (PocketRepository.resendStalledPrompt) mints a FRESH id:
 * a same-id resend into the same Conversation would land here and become a bare re-ack — no new turn — so
 * it could never recover a swallowed prompt. Pins that behavior so a promptSeenBefore refactor can't
 * silently turn resends into double-runs (or into no-ops the client wrongly relies on).
 */
class ConversationPromptDedupTest {

    /** Records every prompt the agent is actually handed; stays alive so the pump doesn't fire process_exited. */
    private class RecordingBackend : AgentBackend {
        val prompts = CopyOnWriteArrayList<String>()
        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec) = ProcessBuilder("sh", "-c", "sleep 30")
        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = StreamParser.parse(line)
        override suspend fun sendPrompt(text: String, images: List<ImageData>) { prompts.add(text) }
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

    @Test
    fun aResendWithTheSamePromptIdIsReAckedButNeverReRunsTheTurn() {
        if (System.getProperty("os.name").lowercase().contains("win")) return // stub runs via sh/sleep
        val dir = Files.createTempDirectory("ccp-dedup")
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = RecordingBackend()
        val convo = Conversation(
            convoId = "cDedup", initialWorkdir = dir, initialMode = PermissionMode.DEFAULT,
            initialSink = { f -> frames.add(f) }, parentScope = scope, backend = backend,
        )
        runBlocking {
            try {
                convo.open(resumeId = null, model = null) // brand-new session: lazy — the first prompt launches
                convo.sendPrompt("hello", promptId = "p1")           // delivered → runs the turn + acks
                convo.sendPrompt("hello", promptId = "p1")           // resend after a "lost" ack → dedup path
                withTimeout(5_000) { while (frames.count { it is PromptAck } < 2) delay(20) }
                delay(150) // settle — catch a second backend.sendPrompt a broken dedup would produce
            } finally {
                convo.close()
                scope.cancel()
            }
        }
        assertEquals(
            1, backend.prompts.size,
            "a repeated promptId must reach the agent EXACTLY once — a same-id resend can't re-run the turn (#66)",
        )
        assertEquals(
            2, frames.count { it is PromptAck },
            "each send is still acked, so a client that lost the first ack is safe to resend",
        )
    }
}
