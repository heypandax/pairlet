package dev.ccpocket.daemon.conversation

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.StreamPiece
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #220: the owner's manually-entered Full Control存续策略. Default = never expires; an opt-in
 * expiry duration re-arms the old auto-revert AND surfaces a visible in-session notice; a restricted
 * origin still can never reach Full Control regardless of the expiry setting (the M5 source ceiling).
 */
class ConversationFullControlExpiryTest {

    private class InertBackend : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec): ProcessBuilder = ProcessBuilder("true")
        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = emptyList()
        override suspend fun sendPrompt(text: String, images: List<ImageData>) {}
        override suspend fun interrupt() {}
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) {}
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = false
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = Path.of(workdir)
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
        override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = emptyList()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    private fun noticeEmitted(fs: List<Frame>) = fs.any {
        it is AssistantChunk && (it.piece as? StreamPiece.Text)?.text == Conversation.FULL_CONTROL_EXPIRED_NOTICE
    }

    @Test
    fun default_full_control_never_expires() = runBlocking {
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation(
            convoId = "cFc", initialWorkdir = Files.createTempDirectory("ccp-fc"),
            initialMode = PermissionMode.DEFAULT,
            initialSink = { f -> synchronized(frames) { frames.add(f) } },
            parentScope = scope, backend = InertBackend(),
            fullControlExpiryMs = { 0L }, // #220 default: no expiry
        )
        try {
            convo.switchMode(PermissionMode.BYPASS_PERMISSIONS)
            // long enough that a stray old-1h-style clock scaled to a test value would have fired
            delay(300)
            assertEquals(PermissionMode.BYPASS_PERMISSIONS, convo.currentMode(), "Full Control must persist with no expiry")
            assertTrue(!noticeEmitted(synchronized(frames) { frames.toList() }), "no expiry ⇒ no fallback notice")
        } finally {
            convo.close(); scope.cancel()
        }
    }

    @Test
    fun opt_in_expiry_reverts_with_a_perceptible_notice() = runBlocking {
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation(
            convoId = "cFc", initialWorkdir = Files.createTempDirectory("ccp-fc"),
            initialMode = PermissionMode.DEFAULT,
            initialSink = { f -> synchronized(frames) { frames.add(f) } },
            parentScope = scope, backend = InertBackend(),
            fullControlExpiryMs = { 60L }, // opt-in short clock
        )
        try {
            convo.switchMode(PermissionMode.BYPASS_PERMISSIONS)
            withTimeout(5_000) { while (convo.currentMode() != PermissionMode.DEFAULT) delay(20) }
            assertEquals(PermissionMode.DEFAULT, convo.currentMode(), "an opt-in expiry must revert to default")
            assertTrue(noticeEmitted(synchronized(frames) { frames.toList() }), "the revert must surface a visible notice")
        } finally {
            convo.close(); scope.cancel()
        }
    }

    @Test
    fun restricted_origin_never_reaches_full_control() = runBlocking {
        // M5 source ceiling regression: a bridge/guest origin can never enter BYPASS, no matter the expiry pref
        val frames = ArrayList<Frame>()
        val scope = CoroutineScope(Dispatchers.Default)
        val convo = Conversation(
            convoId = "cFc", initialWorkdir = Files.createTempDirectory("ccp-fc"),
            initialMode = PermissionMode.DEFAULT,
            initialSink = { f -> synchronized(frames) { frames.add(f) } },
            parentScope = scope, backend = InertBackend(),
            origin = "feishu:bridge-1", // restricted origin
            fullControlExpiryMs = { 0L },
        )
        try {
            convo.switchMode(PermissionMode.BYPASS_PERMISSIONS)
            delay(100)
            assertEquals(PermissionMode.DEFAULT, convo.currentMode(), "restricted origin must be refused Full Control")
        } finally {
            convo.close(); scope.cancel()
        }
    }
}
