package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.claude.StreamParser
import dev.ccpocket.daemon.conversation.KeyedSink
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.server.RequestRouter
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AcceptHandoff
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.CancelHandoff
import dev.ccpocket.protocol.CompleteHandoff
import dev.ccpocket.protocol.CreateHandoff
import dev.ccpocket.protocol.DeclineHandoff
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HandoffAccess
import dev.ccpocket.protocol.HandoffBrief
import dev.ccpocket.protocol.HandoffCreated
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.HandoffUpdated
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.RecallHandoff
import dev.ccpocket.protocol.ReturnHandoff
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionHandoff
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.SwitchMode
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SESSION-HANDOFF.md §5.3 invariant 7 — "Credential 撤销或 Handoff 到终态后，所有关联 sink 与在途权限立即失效".
 *
 * [CollaboratorGuard] already ends the recipient's INBOUND rights the instant the Grant leaves
 * IN_PROGRESS. The half these tests pin is the OUTBOUND one, which no guard can see: the conversation
 * fan-out sink the recipient attached while it held the lease. Left in place it keeps streaming the
 * OWNER's subsequent work — AssistantChunk, ToolEvent, transcript — to a colleague whose grant ended,
 * until that app volunteers a CloseSession. Before the fix, revoking the whole collaborator credential
 * was the only thing that cut it.
 *
 * What every test here checks is a PAIR, because both halves are load-bearing:
 *  - the recipient's view is gone (nothing the conversation pushes reaches it any more), and
 *  - the OWNER's view is untouched — the §3.3 auto-spectate migration that just put the initiator back
 *    inside its own session must survive the hand-back, so "cut the recipient" may never become
 *    "close the conversation".
 *
 * Fixture follows CollaboratorGrantEnforcementTest (stub backend, lazy opens, no processes) except the
 * one end-to-end test that runs a REAL scripted agent so the leaked frames are real AssistantChunks.
 */
/** Everything the CONVERSATION pushes to its fan-out set for [convoId] — the leak channel. Handoff
 *  frames are deliberately excluded: the recipient MUST still be told its grant ended. Top-level so the
 *  fixture below (a nested class) can use it too. */
private fun List<Frame>.convoFrames(convoId: String): List<Frame> = filter {
    when (it) {
        is SessionLive -> it.convoId == convoId
        is AssistantChunk -> it.convoId == convoId
        is ToolEvent -> it.convoId == convoId
        is TurnDone -> it.convoId == convoId
        else -> false
    }
}

class HandoffTerminalSinkCutTest {

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    /** Opens lazily and never spawns (issue #61): every fan-out assertion below needs an attached
     *  conversation, not a running agent. */
    private open class StubBackend : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun listSessions(workdir: String) = emptyList<SessionSummary>()
        override fun processBuilder(spec: AgentSpec): ProcessBuilder = throw UnsupportedOperationException()
        override suspend fun attach(io: AgentIo, spec: AgentSpec) { throw UnsupportedOperationException() }
        override suspend fun parse(line: String): List<AgentEvent> = throw UnsupportedOperationException()
        override suspend fun sendPrompt(text: String, images: List<ImageData>) { throw UnsupportedOperationException() }
        override suspend fun interrupt() { throw UnsupportedOperationException() }
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) { throw UnsupportedOperationException() }
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = false
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = Path.of(workdir)
        override fun replayHistory(workdir: String, sessionId: String) = emptyList<HistoryMessage>()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    /**
     * A two-turn agent: each prompt releases one scripted block of REAL claude stream-json, parsed by the
     * production [StreamParser]. Turn 1 is the recipient's review (it must see it); turn 2 is the owner's
     * work AFTER control came back (the recipient must never see it).
     */
    private class ScriptedBackend(turn1: Path, turn2: Path) : StubBackend() {
        @Volatile private var io: AgentIo? = null
        private val script =
            "read a; cat '${turn1.absolutePathString()}'; read b; cat '${turn2.absolutePathString()}'; sleep 30"

        override fun processBuilder(spec: AgentSpec): ProcessBuilder = ProcessBuilder("sh", "-c", script)
        override suspend fun attach(io: AgentIo, spec: AgentSpec) { this.io = io }
        override suspend fun parse(line: String): List<AgentEvent> = StreamParser.parse(line)
        override suspend fun sendPrompt(text: String, images: List<ImageData>) { io?.writeLine("go") }
        override suspend fun interrupt() {}
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) {}
    }

    private class Fixture(scope: CoroutineScope, backend: AgentBackend = StubBackend()) {
        var now: Long = System.currentTimeMillis()
        val tmp: File = Files.createTempDirectory("ccp-cut").toFile()
        val workdir: String = Files.createTempDirectory("ccp-cut-wd").toString()
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { backend }))
        val handoffs = newService()
        val router = RequestRouter(
            registry = registry,
            dirs = DirectoryService(),
            transcribe = TranscribeService(scope) { null },
            inbox = FileInboxService { null },
            shell = ShellService(scope),
            exports = FileExportService(scope, { null }),
            scope = scope,
            auth = AuthService(scope, { emptyList() }, { 0 }),
            prefs = DaemonPrefs.load(tmp.resolve("prefs.json")),
            presets = PresetService(PresetStore.load(tmp.resolve("presets.json")), { emptyList() }, { 0 }),
            scheduler = dev.ccpocket.daemon.schedule.SchedulerService(
                dev.ccpocket.daemon.schedule.ScheduleStore.load(tmp.resolve("schedules.json")),
                executor = { null },
            ),
        )

        init {
            registry.handoffs = handoffs
        }

        /** A service over THIS fixture's handoffs.json — a second one models a daemon restart (fresh
         *  in-memory fan-out baseline + guards, same persisted ledger). */
        fun newService() = HandoffService(HandoffRegistry(HandoffStore.load(tmp.resolve("handoffs.json")), clock = { now }))

        /** The relay's stable per-device fan-out identity — what the conversation, the handoff fan-out
         *  and the cut all key on. */
        fun sinkFor(deviceId: String, into: MutableList<Frame>): OutboundSink =
            KeyedSink("dev:$deviceId", OutboundSink { into += it })

        suspend fun route(frame: Frame, deviceId: String, into: MutableList<Frame>) =
            router.handle(frame, sinkFor(deviceId, into), deviceId = deviceId)

        /** The DeviceSessions collaborator ingress, split so a test can reproduce the vet-then-bind race:
         *  caps type gate → guard vet → router with the vetted scope. */
        fun vetAsCollaborator(frame: Frame, deviceId: String, svc: HandoffService = handoffs): CollaboratorGuard.Verdict {
            if (!CollaboratorCaps.ingressAllowed(frame)) {
                return CollaboratorGuard.Verdict.Deny("collaborator_forbidden", "not permitted for a collaborator link")
            }
            return svc.collaboratorGuard(deviceId).vet(frame, now)
        }

        suspend fun routeVetted(
            allow: CollaboratorGuard.Verdict.Allow,
            deviceId: String,
            into: MutableList<Frame>,
            svc: HandoffService = handoffs,
        ) = router.handle(
            allow.frame, sinkFor(deviceId, into), deviceId = deviceId,
            collabScope = CollaboratorScope(deviceId, allow.pathScope, access = allow.access ?: HandoffAccess.REVIEW_READ_ONLY),
        ) { convoId -> svc.collaboratorGuard(deviceId).noteOpened(convoId) }

        suspend fun routeAsCollaborator(
            frame: Frame,
            deviceId: String,
            into: MutableList<Frame>,
            svc: HandoffService = handoffs,
        ) {
            when (val v = vetAsCollaborator(frame, deviceId, svc)) {
                is CollaboratorGuard.Verdict.Deny -> into += PocketError(v.code, v.message)
                is CollaboratorGuard.Verdict.Allow -> routeVetted(v, deviceId, into, svc)
            }
        }

        suspend fun awaitLive(into: List<Frame>, notConvoId: String? = null): String = withTimeout(5_000) {
            var live: SessionLive? = null
            while (live == null) {
                live = into.filterIsInstance<SessionLive>().lastOrNull { it.convoId != notConvoId }
                if (live == null) delay(20)
            }
            live.convoId
        }

        suspend fun openSession(deviceId: String, into: MutableList<Frame>, sessionId: String = SESSION_A): String {
            route(OpenSession(workdir, resumeId = sessionId), deviceId, into)
            return awaitLive(into)
        }

        suspend fun createBound(recipient: String, into: MutableList<Frame>, sessionId: String = SESSION_A): SessionHandoff {
            route(
                CreateHandoff(workdir, sessionId, HandoffBrief(request = "review the cut path"), recipientDeviceId = recipient),
                "owner", into,
            )
            val created = into.filterIsInstance<HandoffCreated>().last()
            assertTrue(created.ok, "create must succeed: ${created.error}")
            return assertNotNull(created.handoff)
        }

        /**
         * The whole §4.2 happy path up to "the recipient is live inside the owner's session": the owner
         * is watching its own session, hands it off, the recipient accepts and opens (hot→cold rebuild),
         * and the owner rides along as the §3.3 auto-migrated spectator. Both sides are handoff fan-out
         * targets too, exactly as DeviceSessions attaches them.
         */
        suspend fun handOver(
            owner: MutableList<Frame>,
            recipient: MutableList<Frame>,
            deviceId: String = FRANK,
            sessionId: String = SESSION_A,
        ): Pair<SessionHandoff, String> {
            handoffs.attach(sinkFor("owner", owner))
            handoffs.attach(sinkFor(deviceId, recipient), recipientDeviceId = deviceId)
            val ownerConvo = openSession("owner", owner, sessionId)
            val h = createBound(deviceId, owner, sessionId)
            routeAsCollaborator(AcceptHandoff(h.id), deviceId, recipient)
            routeAsCollaborator(OpenSession(workdir, resumeId = sessionId), deviceId, recipient)
            val convoId = awaitLive(recipient)
            assertNotEquals(ownerConvo, convoId, "the grant open rebuilds the conversation (§8.3)")
            // the owner was migrated onto the rebuilt convo — it is IN the fan-out set, which is what
            // makes "the recipient is cut but the owner is not" a meaningful assertion at all
            withTimeout(5_000) {
                while (owner.filterIsInstance<SessionLive>().none { it.convoId == convoId }) delay(20)
            }
            return h to convoId
        }

        /** Make the live conversation push one frame to its WHOLE fan-out set — the exact channel the
         *  leak rides — and wait until the owner has it. Returns the owner's post-ping frame count. */
        suspend fun fanOutPing(convoId: String, owner: MutableList<Frame>): Int {
            val before = owner.convoFrames(convoId).size
            registry.switchMode(SwitchMode(convoId, PermissionMode.PLAN))
            withTimeout(5_000) { while (owner.convoFrames(convoId).size <= before) delay(20) }
            return owner.convoFrames(convoId).size
        }

        companion object {
            const val SESSION_A = "aaaaaaaa-bbbb-cccc-dddd-000000000001"
            const val SESSION_B = "aaaaaaaa-bbbb-cccc-dddd-000000000002"
            const val FRANK = "dev-frank"
        }
    }

    /** COPY-ON-WRITE (never `synchronizedList`): every assertion iterates these while daemon coroutines
     *  append to them — a COW list iterates an immutable snapshot, so the race is gone by construction. */
    private fun frames(): MutableList<Frame> = CopyOnWriteArrayList()

    /**
     * The shape every terminal-path test shares: hand over, take the recipient's frame count, run
     * [settle], then prove the pair — the conversation still streams to the OWNER, and not one further
     * frame reached the RECIPIENT. Asserting the owner's ping FIRST is what makes the negative
     * assertion mean something: the frame demonstrably went out while the recipient was still listed.
     */
    private fun terminalCutsTheRecipient(
        expected: HandoffStatus,
        settle: suspend (Fixture, SessionHandoff, String, MutableList<Frame>, MutableList<Frame>) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val fx = Fixture(scope)
        val owner = frames(); val frank = frames()
        try {
            val (h, convoId) = fx.handOver(owner, frank)
            fx.fanOutPing(convoId, owner)
            val frankBefore = frank.convoFrames(convoId).size
            assertTrue(frankBefore > 0, "the recipient really was streaming this conversation before the transition")

            settle(fx, h, convoId, owner, frank)

            assertEquals(
                expected,
                fx.handoffs.registry.byId(h.id)?.status,
                "the settle under test must land the row on $expected",
            )
            // the conversation is ALIVE and still fanning out — to the owner
            val ownerAfter = fx.fanOutPing(convoId, owner)
            assertTrue(ownerAfter > 0, "the owner keeps streaming its own session through the hand-back")
            // …and the recipient got nothing more out of it
            assertEquals(
                frankBefore,
                frank.convoFrames(convoId).size,
                "the recipient must receive NOTHING from this conversation once the grant ended",
            )
            // the recipient was still told the grant ended (the handoff plane is a different fan-out)
            assertTrue(
                frank.filterIsInstance<HandoffUpdated>().any { it.handoff.id == h.id && it.handoff.status == expected },
                "cutting the session view must not cut the news that it happened",
            )
        } finally {
            fx.registry.closeAll()
            scope.cancel()
        }
    }

    // ---- the six terminal paths -------------------------------------------

    /** 1/6 — RETURNED: the recipient hands the session back itself. */
    @Test
    fun returning_the_handoff_cuts_the_recipients_view() =
        terminalCutsTheRecipient(HandoffStatus.RETURNED) { fx, h, _, _, frank ->
            fx.routeAsCollaborator(ReturnHandoff(h.id), Fixture.FRANK, frank)
        }

    /** 2/6 — RECALLED, the idle path: no turn is executing, so control moves in one step. */
    @Test
    fun an_idle_recall_cuts_the_recipients_view() =
        terminalCutsTheRecipient(HandoffStatus.RECALLED) { fx, h, _, owner, _ ->
            fx.route(RecallHandoff(h.id), "owner", owner)
        }

    /** 2b/6 — RECALLED, the §5.4 GRACEFUL path: the router arms the hand-back on the pump and settles it
     *  on the core scope afterwards. That async tail is its own terminal exit and must cut too. */
    @Test
    fun the_graceful_recalls_async_settle_cuts_the_recipients_view() =
        terminalCutsTheRecipient(HandoffStatus.RECALLED) { fx, h, _, _, _ ->
            // exactly what RecallOutcome.Pending + `scope.launch { svc.settleRecall(id) }` do, minus the
            // live turn (a real one is driven end-to-end in HandoffRecallTest)
            fx.handoffs.registry.markRecallPending(h.id, "owner")
            fx.handoffs.settleRecall(h.id)
        }

    /** 3/6 — COMPLETED: the initiator acknowledges a RETURNED result. The view was already cut at
     *  RETURNED, so this asserts the SECOND cut is a silent no-op that keeps the leak closed. */
    @Test
    fun completing_an_acknowledged_handoff_keeps_the_view_cut() =
        terminalCutsTheRecipient(HandoffStatus.COMPLETED) { fx, h, _, owner, frank ->
            fx.routeAsCollaborator(ReturnHandoff(h.id), Fixture.FRANK, frank)
            fx.route(CompleteHandoff(h.id), "owner", owner)
        }

    /** 4/6 — RECALLED by the expiry sweep: the lease outran its TTL and no client asked for anything.
     *  The transition is settled by [HandoffService.reconcile], which must cut on the same pass. */
    @Test
    fun the_expiry_sweep_cuts_the_recipients_view() =
        terminalCutsTheRecipient(HandoffStatus.RECALLED) { fx, _, _, _, _ ->
            fx.now += 3 * 3600_000L // past DEFAULT_LEASE_TTL_MS
            fx.handoffs.reconcile(fx.now)
        }

    /**
     * 5/6 + 6/6 — DECLINED and CANCELLED. Both exit from WAITING, where the recipient has no session
     * access at all ([CollaboratorGuard] admits an open only against an IN_PROGRESS grant), so there is
     * nothing to cut — and running the cut anyway must stay a silent no-op that touches no other view.
     * The owner's own live session, which it may keep using the moment the offer dies, is the thing this
     * proves is not collateral damage.
     */
    @Test
    fun the_waiting_exits_cut_nothing_and_leave_the_owners_session_alone() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val fx = Fixture(scope)
        val owner = frames(); val frank = frames()
        try {
            fx.handoffs.attach(fx.sinkFor("owner", owner))
            fx.handoffs.attach(fx.sinkFor(Fixture.FRANK, frank), recipientDeviceId = Fixture.FRANK)
            val convoId = fx.openSession("owner", owner)

            for ((status, settle) in listOf<Pair<HandoffStatus, suspend (SessionHandoff) -> Unit>>(
                HandoffStatus.DECLINED to { h -> fx.routeAsCollaborator(DeclineHandoff(h.id), Fixture.FRANK, frank) },
                HandoffStatus.CANCELLED to { h -> fx.route(CancelHandoff(h.id), "owner", owner) },
                HandoffStatus.EXPIRED to { _ -> fx.now += 25 * 3600_000L; fx.handoffs.reconcile(fx.now) },
            )) {
                val h = fx.createBound(Fixture.FRANK, owner)
                val frankBefore = frank.convoFrames(convoId).size
                assertEquals(0, frankBefore, "a WAITING offer never gives the recipient a session view")
                settle(h)
                assertEquals(status, fx.handoffs.registry.byId(h.id)?.status, "the $status path must land")
                // the owner's own conversation survived untouched…
                assertTrue(fx.fanOutPing(convoId, owner) > 0, "$status must not disturb the owner's session")
                // …and the recipient still has nothing
                assertEquals(0, frank.convoFrames(convoId).size, "$status must not hand the recipient a view")
            }
        } finally {
            fx.registry.closeAll()
            scope.cancel()
        }
    }

    // ---- not collateral damage --------------------------------------------

    /**
     * The cut is scoped to ONE grant, not to the credential: a contact reviewing two sessions at once
     * loses only the session that was handed back. Cutting by [CollaboratorGuard.ownedConvoIds] instead
     * of [CollaboratorGuard.convosGrantedBy] would blind the still-live review too.
     */
    @Test
    fun a_second_still_live_grant_on_another_session_keeps_streaming() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val fx = Fixture(scope)
        val ownerA = frames(); val ownerB = frames(); val frank = frames()
        try {
            val (hA, convoA) = fx.handOver(ownerA, frank, sessionId = Fixture.SESSION_A)
            val (_, convoB) = fx.handOver(ownerB, frank, sessionId = Fixture.SESSION_B)
            assertNotEquals(convoA, convoB)
            fx.fanOutPing(convoA, ownerA); fx.fanOutPing(convoB, ownerB)
            val frankA = frank.convoFrames(convoA).size
            val frankB = frank.convoFrames(convoB).size
            assertTrue(frankA > 0 && frankB > 0, "the recipient is streaming BOTH reviews")

            fx.routeAsCollaborator(ReturnHandoff(hA.id), Fixture.FRANK, frank)

            fx.fanOutPing(convoA, ownerA); fx.fanOutPing(convoB, ownerB)
            assertEquals(frankA, frank.convoFrames(convoA).size, "the returned session is cut")
            assertTrue(frank.convoFrames(convoB).size > frankB, "the OTHER, still-IN_PROGRESS grant must keep streaming")
        } finally {
            fx.registry.closeAll()
            scope.cancel()
        }
    }

    // ---- no way back in ----------------------------------------------------

    /**
     * §5.3 item 7's other half: a cut recipient reconnecting (fresh sink, same device key) must not be
     * able to buy its way back into the stream. [CollaboratorGuard] refuses the open outright because
     * the Grant is terminal, so the reconnect ends with an error — never a SessionLive.
     */
    @Test
    fun a_cut_recipient_cannot_reopen_the_session_after_the_handoff_ended() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val fx = Fixture(scope)
        val owner = frames(); val frank = frames()
        try {
            val (h, convoId) = fx.handOver(owner, frank)
            fx.route(RecallHandoff(h.id), "owner", owner)

            // the reconnect: a brand-new frame list (a fresh socket) under the same device credential
            val reconnected = frames()
            fx.routeAsCollaborator(OpenSession(fx.workdir, resumeId = Fixture.SESSION_A), Fixture.FRANK, reconnected)
            assertTrue(
                reconnected.filterIsInstance<SessionLive>().isEmpty(),
                "a terminal grant may never re-open the session: ${reconnected.map { it::class.simpleName }}",
            )
            assertEquals(
                listOf("handoff_grant_required"),
                reconnected.filterIsInstance<PocketError>().map { it.code },
            )
            // and the owner's session is still there, still streaming, untouched by the refusal
            assertTrue(fx.fanOutPing(convoId, owner) > 0)
            assertEquals(0, reconnected.convoFrames(convoId).size)
        } finally {
            fx.registry.closeAll()
            scope.cancel()
        }
    }

    // ---- idempotence + the backstop ----------------------------------------

    /** Repeating a terminal transition — the duplicate frame a retrying client sends, a re-broadcast, a
     *  reconcile pass, a device that never had a view at all — must never throw and never re-open one. */
    @Test
    fun repeating_the_terminal_transition_is_idempotent() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val fx = Fixture(scope)
        val owner = frames(); val frank = frames()
        try {
            val (h, convoId) = fx.handOver(owner, frank)
            fx.routeAsCollaborator(ReturnHandoff(h.id), Fixture.FRANK, frank)
            fx.route(CompleteHandoff(h.id), "owner", owner)
            val frankBefore = frank.convoFrames(convoId).size

            val terminal = assertNotNull(fx.handoffs.registry.byId(h.id))
            repeat(3) {
                fx.routeAsCollaborator(ReturnHandoff(h.id), Fixture.FRANK, frank) // already returned
                fx.route(CompleteHandoff(h.id), "owner", owner)                   // already completed
                fx.route(RecallHandoff(h.id), "owner", owner)                     // nothing to recall
                fx.handoffs.broadcast(listOf(terminal))                           // re-announce the same row
                fx.handoffs.settleRecall(h.id)                                    // a late async tail
                fx.handoffs.revokeRecipient(Fixture.FRANK)                        // the credential path too
                fx.handoffs.reconcile(fx.now)
            }
            assertEquals(HandoffStatus.COMPLETED, fx.handoffs.registry.byId(h.id)?.status, "no repeat may move a terminal row")
            assertTrue(fx.fanOutPing(convoId, owner) > 0, "the owner's session survives every repeat")
            assertEquals(frankBefore, frank.convoFrames(convoId).size, "and the recipient stays cut")

            // a device that never opened anything: the cut path must be a no-op for it too
            fx.handoffs.collaboratorGuard("dev-never-here")
            fx.handoffs.revokeRecipient("dev-never-here")
            fx.handoffs.reconcile(fx.now)
        } finally {
            fx.registry.closeAll()
            scope.cancel()
        }
    }

    /**
     * The reconcile BACKSTOP, on the one ordering a diff cannot see: the recipient's OpenSession passed
     * the guard while the Grant was still live and binds its convo just AFTER that transition ran its
     * own cut. Nothing moved since the last broadcast, so [HandoffService.broadcast] is never reached
     * for this row again — only the ledger-wide sweep in [HandoffService.reconcile] can close it. (The
     * same line covers a row the registry normalized to terminal at BOOT, which likewise only seeds the
     * broadcast baseline.)
     */
    @Test
    fun an_open_that_binds_after_the_transition_is_cut_by_the_next_reconcile() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val fx = Fixture(scope)
        val owner = frames(); val frank = frames()
        try {
            val (h, _) = fx.handOver(owner, frank)

            // the racing open is VETTED here — grant live, guard says yes — but not routed yet
            val vetted = fx.vetAsCollaborator(OpenSession(fx.workdir, resumeId = Fixture.SESSION_A), Fixture.FRANK)
            val allow = assertNotNull(vetted as? CollaboratorGuard.Verdict.Allow)

            // …the initiator recalls in the meantime: the transition cuts what is bound RIGHT NOW
            fx.route(RecallHandoff(h.id), "owner", owner)
            assertEquals(HandoffStatus.RECALLED, fx.handoffs.registry.byId(h.id)?.status)

            // …and only then does the in-flight open land and bind its convo to the (dead) grant
            val late = frames()
            fx.routeVetted(allow, Fixture.FRANK, late)
            val lateConvo = fx.awaitLive(late)
            assertTrue(late.convoFrames(lateConvo).isNotEmpty(), "the raced open really did attach a view")

            // the diff has nothing to announce — the row's status has not moved since the recall — so the
            // ledger-wide backstop is the only thing that can cut this view
            fx.handoffs.reconcile(fx.now)
            val frankAfterCut = late.convoFrames(lateConvo).size
            assertTrue(fx.fanOutPing(lateConvo, owner) > 0, "the owner still drives the session")
            assertEquals(frankAfterCut, late.convoFrames(lateConvo).size, "the backstop must cut the raced view")
        } finally {
            fx.registry.closeAll()
            scope.cancel()
        }
    }

    // ---- the real stream ---------------------------------------------------

    /**
     * End-to-end with a REAL agent process: the recipient reviews (turn 1), returns, and then the owner
     * works on (turn 2). The frames are genuine [AssistantChunk]s parsed by the production
     * [StreamParser] off a scripted stream-json process — i.e. exactly the content the vulnerability
     * leaked. The owner receives turn 2 BEFORE the assertion runs, so "the recipient did not" is a
     * statement about a frame that provably went out, not about a race that hadn't happened yet.
     */
    @Test
    fun the_recipient_stops_receiving_assistant_chunks_the_moment_control_returns() = runBlocking {
        if (isWindows()) return@runBlocking // the scripted agent runs via sh/cat
        val scope = CoroutineScope(Dispatchers.Default)
        val dir = Files.createTempDirectory("ccp-cut-stream")
        val turn1 = dir.resolve("t1.jsonl").apply {
            writeText(
                """{"type":"system","subtype":"init","session_id":"${Fixture.SESSION_A}","cwd":"/tmp","model":"claude-sonnet-5"}""" + "\n" +
                    """{"type":"assistant","message":{"model":"claude-sonnet-5","content":[{"type":"text","text":"$REVIEW_TEXT"}]}}""" + "\n" +
                    """{"type":"result","subtype":"success","is_error":false,"result":"$REVIEW_TEXT"}""" + "\n",
            )
        }
        val turn2 = dir.resolve("t2.jsonl").apply {
            writeText(
                """{"type":"assistant","message":{"model":"claude-sonnet-5","content":[{"type":"text","text":"$SECRET_TEXT"}]}}""" + "\n" +
                    """{"type":"result","subtype":"success","is_error":false,"result":"$SECRET_TEXT"}""" + "\n",
            )
        }
        val fx = Fixture(scope, ScriptedBackend(turn1, turn2))
        val owner = frames(); val frank = frames()
        try {
            val (h, convoId) = fx.handOver(owner, frank)

            // turn 1: the recipient's own review — it must see this one
            fx.routeAsCollaborator(SendPrompt(convoId, "review the diff"), Fixture.FRANK, frank)
            withTimeout(15_000) {
                while (frank.filterIsInstance<AssistantChunk>().none { it.text() == REVIEW_TEXT }) delay(20)
            }
            withTimeout(15_000) { while (frank.filterIsInstance<TurnDone>().isEmpty()) delay(20) }

            // control comes back
            fx.routeAsCollaborator(ReturnHandoff(h.id), Fixture.FRANK, frank)
            assertEquals(HandoffStatus.RETURNED, fx.handoffs.registry.byId(h.id)?.status)

            // turn 2: the OWNER's work after the hand-back — never the recipient's business
            fx.route(SendPrompt(convoId, "carry on"), "owner", owner)
            withTimeout(15_000) {
                while (owner.filterIsInstance<AssistantChunk>().none { it.text() == SECRET_TEXT }) delay(20)
            }
            withTimeout(15_000) { while (owner.filterIsInstance<TurnDone>().size < 2) delay(20) }

            assertTrue(
                frank.filterIsInstance<AssistantChunk>().any { it.text() == REVIEW_TEXT },
                "sanity: the recipient really was streaming this session while it held the grant",
            )
            assertTrue(
                frank.filterIsInstance<AssistantChunk>().none { it.text() == SECRET_TEXT },
                "the returned recipient must not receive one byte of the owner's later work",
            )
            assertEquals(1, frank.filterIsInstance<TurnDone>().size, "nor the owner's turn boundaries")
        } finally {
            fx.registry.closeAll()
            scope.cancel()
        }
    }

    private fun AssistantChunk.text(): String? = (piece as? StreamPiece.Text)?.text

    private companion object {
        const val REVIEW_TEXT = "the recipients own review"
        const val SECRET_TEXT = "owner only work after the handoff ended"
    }
}
