package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRegistryBridgeApprovalRouteTest {
    private class LazyBackend : AgentBackend {
        @Volatile var launchedSpec: AgentSpec? = null
        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec): ProcessBuilder {
            launchedSpec = spec
            return ProcessBuilder("sh", "-c", "sleep 30")
        }
        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = emptyList()
        override suspend fun sendPrompt(text: String, images: List<ImageData>) {}
        override suspend fun interrupt() {}
        override suspend fun respondPermission(
            askId: String,
            allow: Boolean,
            remember: Boolean,
            originalInput: JsonObject?,
            updatedInput: String?,
            denyMessage: String?,
        ) {}
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = true
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = Path.of(workdir)
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
        override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = emptyList()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    @Test
    fun pre_first_turn_push_anchor_reattaches_and_resurfaces_the_exact_request() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = LazyBackend()
        val registry = SessionRegistry(
            scope,
            backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { backend }),
        )
        val workdir = Files.createTempDirectory("ccp-bridge-request").toString()
        val bridgeFrames = mutableListOf<Frame>()
        val ownerFrames = mutableListOf<Frame>()
        try {
            val convoId = registry.open(
                OpenSession(workdir),
                { frame -> synchronized(bridgeFrames) { bridgeFrames += frame } },
                origin = "feishu-bot",
            )
            assertFalse(
                registry.sendApprovedBridgePrompt(SendPrompt(convoId, "must not run")),
                "the full-access path requires a freshly approved one-shot permit",
            )
            val approved = async { registry.approveBridgeRequest(convoId, "sender: ou_1\n\nrun tests") }
            val ask = withTimeout(5_000) {
                while (true) {
                    synchronized(bridgeFrames) { bridgeFrames.filterIsInstance<PermissionAsk>().lastOrNull() }
                        ?.let { return@withTimeout it }
                    delay(10)
                }
                error("unreachable")
            }
            val row = registry.pendingApprovals().single()
            assertEquals(ask, row.ask)
            assertEquals(workdir, row.workdir)
            assertEquals("feishu-bot", row.origin)
            assertNull(row.sessionId, "pre-first-turn approvals legitimately have no transcript id yet")
            assertTrue(requireNotNull(row.expiresAt) > System.currentTimeMillis())

            // The first request has no transcript/session id yet. Its push routes with convoId; opening that
            // anchor must reattach the existing conversation and replay the pending approval card.
            val reopened = registry.open(
                OpenSession(workdir, resumeId = convoId),
                { frame -> synchronized(ownerFrames) { ownerFrames += frame } },
            )
            assertEquals(convoId, reopened)
            val live = synchronized(ownerFrames) { ownerFrames.filterIsInstance<SessionLive>().last() }
            assertNull(live.sessionId)
            assertEquals(ask, synchronized(ownerFrames) { ownerFrames.filterIsInstance<PermissionAsk>().last() })

            registry.verdict(PermissionVerdict(convoId, ask.askId, Decision.ALLOW, remember = true))
            assertTrue(approved.await())
            assertTrue(registry.pendingApprovals().isEmpty())
            assertNull(backend.launchedSpec, "the request text must not reach an agent before approval")
            assertTrue(registry.sendApprovedBridgePrompt(SendPrompt(convoId, "run tests")))
            val launched = withTimeout(5_000) {
                while (backend.launchedSpec == null) delay(10)
                assertNotNull(backend.launchedSpec)
            }
            assertTrue(
                launched.appendSystemPrompt.orEmpty().contains("approval never authorizes disclosing sensitive data"),
                "bridge launches must carry the sensitive-output system boundary",
            )
            assertEquals(1, registry.liveCountOf(listOf(convoId)))
        } finally {
            registry.closeAll()
            scope.cancel()
        }
    }
}
