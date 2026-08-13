package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.approval.ApprovalCoordinator
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CancelTurn
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
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

    /** A single ordered stream that exposes the phantom-result boundary behind issue #233:
     *  A finishes on paper, B is handed off, then A emits one late tool request before B's replay. */
    private class GrantBoundaryBackend : AgentBackend {
        val releaseLateA = CompletableDeferred<Unit>()
        val responses = CopyOnWriteArrayList<Pair<String, Boolean>>()

        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec): ProcessBuilder = ProcessBuilder(
            "sh", "-c",
            "printf '%s\\n' replay-a phantom-result late-a replay-b control-b; sleep 30",
        )
        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = when (line) {
            "replay-a" -> listOf(AgentEvent.UserReplay("request A"))
            "phantom-result" -> listOf(AgentEvent.TurnResult("early", null, false))
            "late-a" -> {
                releaseLateA.await()
                listOf(AgentEvent.ControlRequest("late-a", "mcp__remote__do", buildJsonObject {}))
            }
            "replay-b" -> listOf(AgentEvent.UserReplay("request B"))
            "control-b" -> listOf(AgentEvent.ControlRequest("control-b", "mcp__remote__do", buildJsonObject {}))
            else -> emptyList()
        }
        override suspend fun sendPrompt(text: String, images: List<ImageData>) {}
        override suspend fun interrupt() {}
        override suspend fun respondPermission(
            askId: String,
            allow: Boolean,
            remember: Boolean,
            originalInput: JsonObject?,
            updatedInput: String?,
            denyMessage: String?,
        ) {
            responses += askId to allow
        }
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = true
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = Path.of(workdir)
        override fun listSessions(workdir: String): List<SessionSummary> = emptyList()
        override fun replayHistory(workdir: String, sessionId: String): List<HistoryMessage> = emptyList()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    private class PendingGrantCancelBackend : AgentBackend {
        val releaseSecondResult = CompletableDeferred<Unit>()
        val releaseReplayB = CompletableDeferred<Unit>()
        val responses = CopyOnWriteArrayList<Pair<String, Boolean>>()

        override val kind = AgentKind.CLAUDE
        override fun processBuilder(spec: AgentSpec): ProcessBuilder = ProcessBuilder(
            "sh", "-c",
            "printf '%s\\n' replay-a result-a result-after-b replay-b control-b; sleep 30",
        )
        override suspend fun attach(io: AgentIo, spec: AgentSpec) {}
        override suspend fun parse(line: String): List<AgentEvent> = when (line) {
            "replay-a" -> listOf(AgentEvent.UserReplay("request A"))
            "result-a" -> listOf(AgentEvent.TurnResult("early A", null, false))
            "result-after-b" -> {
                releaseSecondResult.await()
                listOf(AgentEvent.TurnResult("late A", null, false))
            }
            "replay-b" -> {
                releaseReplayB.await()
                listOf(AgentEvent.UserReplay("request B"))
            }
            "control-b" -> listOf(AgentEvent.ControlRequest("control-b", "mcp__remote__do", buildJsonObject {}))
            else -> emptyList()
        }
        override suspend fun sendPrompt(text: String, images: List<ImageData>) {}
        override suspend fun interrupt() {}
        override suspend fun respondPermission(
            askId: String,
            allow: Boolean,
            remember: Boolean,
            originalInput: JsonObject?,
            updatedInput: String?,
            denyMessage: String?,
        ) {
            responses += askId to allow
        }
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
        val approvals = ApprovalCoordinator(scope)
        val registry = SessionRegistry(
            scope,
            backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { backend }),
            approvals = approvals,
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

            approvals.onVerdict(PermissionVerdict(convoId, ask.askId, Decision.ALLOW, remember = true))
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

    /** issue #198/#233 compatibility: a pre-trusted chat's request needs NO permit (nothing was tapped) but is
     *  still refused on a guest credential and emits no request-level card. PermissionBridge retains this
     *  durable mode's legacy closed tool ceiling (see PermissionBridgeTest). */
    @Test
    fun a_trusted_chats_request_runs_without_a_permit_but_never_on_a_guest_share() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = LazyBackend()
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { backend }))
        val workdir = Files.createTempDirectory("ccp-bridge-trusted").toString()
        val frames = mutableListOf<Frame>()
        try {
            val convoId = registry.open(
                OpenSession(workdir),
                { frame -> synchronized(frames) { frames += frame } },
                origin = "feishu-bot",
            )
            assertTrue(
                registry.sendTrustedBridgePrompt(SendPrompt(convoId, "run tests")),
                "the owner's standing per-chat grant IS the authorization — no permit to mint",
            )
            withTimeout(5_000) {
                while (backend.launchedSpec == null) delay(10)
            }
            assertTrue(
                synchronized(frames) { frames.filterIsInstance<PermissionAsk>() }.isEmpty(),
                "a trusted request must not push a per-request card",
            )

            // a GUEST folder share is a different credential kind: it approves its own asks and must never
            // reach the bridge grant path at all
            val guestConvo = registry.open(
                OpenSession(workdir),
                { },
                origin = "guest-share",
                pathScope = listOf(workdir),
            )
            assertFalse(registry.sendTrustedBridgePrompt(SendPrompt(guestConvo, "run tests")))
        } finally {
            registry.closeAll()
            scope.cancel()
        }
    }

    /** issue #233: only the in-process Feishu engine exchanges an owner-confirmed mode + Guardian pass for
     *  this broad one-turn grant. The registry still rejects a guest/session-share credential. */
    @Test
    fun an_explicit_full_auto_request_runs_without_a_permit_but_never_on_a_guest_share() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = LazyBackend()
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { backend }))
        val workdir = Files.createTempDirectory("ccp-bridge-full-auto").toString()
        try {
            val convoId = registry.open(OpenSession(workdir), { }, origin = "feishu-bot")
            assertTrue(
                registry.sendReviewedFullAutoBridgePrompt(
                    SendPrompt(convoId, "run the reviewed task"),
                    reviewId = "review-233",
                ),
            )
            withTimeout(5_000) {
                while (backend.launchedSpec == null) delay(10)
            }

            val guestConvo = registry.open(
                OpenSession(workdir),
                { },
                origin = "guest-share",
                pathScope = listOf(workdir),
            )
            assertFalse(
                registry.sendReviewedFullAutoBridgePrompt(
                    SendPrompt(guestConvo, "must not run"),
                    reviewId = "review-guest",
                ),
            )
        } finally {
            registry.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun a_staged_full_auto_grant_cannot_authorize_a_late_request_from_the_previous_turn() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = GrantBoundaryBackend()
        val approvals = ApprovalCoordinator(scope)
        val registry = SessionRegistry(
            scope,
            backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { backend }),
            approvals = approvals,
        )
        val workdir = Files.createTempDirectory("ccp-bridge-grant-boundary").toString()
        val frames = CopyOnWriteArrayList<Frame>()
        try {
            val convoId = registry.open(OpenSession(workdir), { frames += it }, origin = "feishu-bot")
            assertTrue(
                registry.sendReviewedFullAutoBridgePrompt(
                    SendPrompt(convoId, "request A"),
                    reviewId = "review-a",
                ),
            )
            withTimeout(5_000) {
                while (frames.none { it is dev.ccpocket.protocol.TurnDone }) delay(10)
            }

            // B's full-auto grant is now STAGED, but A's continuation wins the stdout race. The late
            // request must ask; it cannot borrow B's authority before B's exact UserReplay arrives.
            assertTrue(
                registry.sendReviewedFullAutoBridgePrompt(
                    SendPrompt(convoId, "request B"),
                    reviewId = "review-b",
                ),
            )
            backend.releaseLateA.complete(Unit)
            val ask = withTimeout(5_000) {
                while (true) {
                    frames.filterIsInstance<PermissionAsk>().lastOrNull()?.let { return@withTimeout it }
                    delay(10)
                }
                error("unreachable")
            }
            assertEquals("late-a", ask.askId)
            assertTrue(
                backend.responses.none { it.first == "late-a" && it.second },
                "the previous turn must not auto-run under B's pending grant: ${backend.responses}",
            )

            approvals.onVerdict(PermissionVerdict(convoId, ask.askId, Decision.DENY))
            withTimeout(5_000) {
                while (backend.responses.none { it.first == "control-b" }) delay(10)
            }
            assertEquals(
                mapOf("late-a" to false, "control-b" to true),
                backend.responses.toMap(),
                "B may auto-run only after its matching replay activates the staged grant",
            )
        } finally {
            registry.closeAll()
            scope.cancel()
        }
    }

    @Test
    fun cancel_revokes_a_pending_grant_even_after_a_phantom_result_clears_executing() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = PendingGrantCancelBackend()
        val approvals = ApprovalCoordinator(scope)
        val registry = SessionRegistry(
            scope,
            backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { backend }),
            approvals = approvals,
        )
        val workdir = Files.createTempDirectory("ccp-bridge-pending-cancel").toString()
        val frames = CopyOnWriteArrayList<Frame>()
        try {
            val convoId = registry.open(OpenSession(workdir), { frames += it }, origin = "feishu-bot")
            assertTrue(registry.sendReviewedFullAutoBridgePrompt(SendPrompt(convoId, "request A"), "review-a"))
            withTimeout(5_000) { while (frames.filterIsInstance<dev.ccpocket.protocol.TurnDone>().size < 1) delay(10) }

            assertTrue(registry.sendReviewedFullAutoBridgePrompt(SendPrompt(convoId, "request B"), "review-b"))
            backend.releaseSecondResult.complete(Unit)
            withTimeout(5_000) { while (frames.filterIsInstance<dev.ccpocket.protocol.TurnDone>().size < 2) delay(10) }

            // `executing` is false because A emitted another phantom result, but B's grant is still pending
            // its exact replay. Stop must revoke that lease even though there is no backend turn to interrupt.
            registry.cancelTurn(CancelTurn(convoId))
            backend.releaseReplayB.complete(Unit)
            val ask = withTimeout(5_000) {
                while (true) {
                    frames.filterIsInstance<PermissionAsk>().lastOrNull()?.let { return@withTimeout it }
                    delay(10)
                }
                error("unreachable")
            }
            assertEquals("control-b", ask.askId)
            assertTrue(backend.responses.none { it.first == "control-b" && it.second })
            approvals.onVerdict(PermissionVerdict(convoId, ask.askId, Decision.DENY))
            withTimeout(5_000) { while (backend.responses.none { it.first == "control-b" }) delay(10) }
            assertEquals("control-b" to false, backend.responses.last())
        } finally {
            registry.closeAll()
            scope.cancel()
        }
    }

    /** The owner's dedicated bridge identity is standing, but its execution authority is not: only the
     *  in-process owner entry point may mint OWNER_BYPASS for one turn. A normal bridge or guest is refused. */
    @Test
    fun owner_bypass_entrypoint_only_runs_on_the_dedicated_owner_conversation() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val backend = LazyBackend()
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { backend }))
        val workdir = Files.createTempDirectory("ccp-bridge-owner-bypass").toString()
        try {
            val ownerConvo = registry.open(
                OpenSession(workdir),
                { },
                origin = "feishu-bot",
                ownerBypass = true,
            )
            assertTrue(registry.sendOwnerBypassBridgePrompt(SendPrompt(ownerConvo, "run tests")))
            withTimeout(5_000) {
                while (backend.launchedSpec == null) delay(10)
            }

            val ordinaryBridge = registry.open(OpenSession(workdir), { }, origin = "another-bridge")
            assertFalse(registry.sendOwnerBypassBridgePrompt(SendPrompt(ordinaryBridge, "must not run")))
            val guest = registry.open(
                OpenSession(workdir),
                { },
                origin = "guest-share",
                pathScope = listOf(workdir),
            )
            assertFalse(registry.sendOwnerBypassBridgePrompt(SendPrompt(guest, "must not run")))
        } finally {
            registry.closeAll()
            scope.cancel()
        }
    }
}
