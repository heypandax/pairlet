package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.AuthService
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
import dev.ccpocket.protocol.CancelHandoff
import dev.ccpocket.protocol.CreateHandoff
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HandoffBrief
import dev.ccpocket.protocol.HandoffCreated
import dev.ccpocket.protocol.HandoffListing
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.HandoffUpdated
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListHandoffs
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.ReturnHandoff
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The COLLABORATOR half of the handoff wiring (SESSION-HANDOFF.md §4.1–§4.3): recipient binding on
 * create/accept, the collaborator's zero-baseline (caps + guard fail closed outside a grant), the
 * IN_PROGRESS-only session grant, the own-offer router filter, and the recipient-filtered fan-out.
 * Follows the HandoffRouterWiringTest fixture: stub backend, lazy opens, no processes.
 */
class CollaboratorRouterWiringTest {

    private class StubBackend : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun listSessions(workdir: String) = emptyList<dev.ccpocket.protocol.SessionSummary>()
        override fun processBuilder(spec: AgentSpec) = throw UnsupportedOperationException()
        override suspend fun attach(io: AgentIo, spec: AgentSpec) = throw UnsupportedOperationException()
        override suspend fun parse(line: String): Nothing = throw UnsupportedOperationException()
        override suspend fun sendPrompt(text: String, images: List<ImageData>) = throw UnsupportedOperationException()
        override suspend fun interrupt() = throw UnsupportedOperationException()
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) = throw UnsupportedOperationException()
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = false
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = throw UnsupportedOperationException()
        override fun replayHistory(workdir: String, sessionId: String) = emptyList<HistoryMessage>()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    private class Fixture(scope: CoroutineScope) {
        var now: Long = System.currentTimeMillis()
        private val tmp = Files.createTempDirectory("ccp-collab-wiring").toFile()
        val workdir: String = Files.createTempDirectory("ccp-collab-wd").toString()
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { StubBackend() }))
        val handoffs = HandoffService(HandoffRegistry(HandoffStore.load(tmp.resolve("handoffs.json")), clock = { now }))
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

        /** Route [frame] as an OWNER device. */
        suspend fun route(frame: Frame, deviceId: String, into: MutableList<Frame>) =
            router.handle(frame, { into += it }, deviceId = deviceId)

        /** Route [frame] the way DeviceSessions does for a COLLABORATOR credential: through the
         *  CollaboratorCaps type gate + CollaboratorGuard vet, then the router with a collabScope. */
        suspend fun routeAsCollaborator(frame: Frame, deviceId: String, into: MutableList<Frame>) {
            if (!CollaboratorCaps.ingressAllowed(frame)) {
                into += PocketError("collaborator_forbidden", "not permitted for a collaborator link")
                return
            }
            val guard = handoffs.collaboratorGuard(deviceId)
            when (val v = guard.vet(frame, now)) {
                is CollaboratorGuard.Verdict.Deny -> into += PocketError(v.code, v.message)
                is CollaboratorGuard.Verdict.Allow -> router.handle(
                    v.frame, { into += it }, deviceId = deviceId,
                    collabScope = CollaboratorScope(deviceId, v.pathScope),
                ) { convoId -> guard.noteOpened(convoId) }
            }
        }

        suspend fun openSession(deviceId: String, into: MutableList<Frame>): String {
            route(OpenSession(workdir, resumeId = SESSION_ID), deviceId, into)
            return withTimeout(5_000) {
                while (into.none { it is SessionLive }) delay(20)
                into.filterIsInstance<SessionLive>().first().convoId
            }
        }

        suspend fun createBound(deviceId: String, recipient: String?, into: MutableList<Frame>): dev.ccpocket.protocol.SessionHandoff {
            route(
                CreateHandoff(
                    workdir, SESSION_ID, HandoffBrief(request = "review the ACK path"),
                    recipientDeviceId = recipient,
                ),
                deviceId, into,
            )
            val created = into.filterIsInstance<HandoffCreated>().last()
            assertTrue(created.ok, "create must succeed: ${created.error}")
            return assertNotNull(created.handoff)
        }

        companion object { const val SESSION_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" }
    }

    /**
     * One client's received frames. COPY-ON-WRITE, deliberately not `Collections.synchronizedList`:
     * every assertion and await ITERATES these lists (`none` / `filterIsInstance`) while daemon
     * coroutines are still appending to them, and a synchronized list only locks each single call —
     * the iteration itself raced and threw ConcurrentModificationException. A COW list iterates an
     * immutable snapshot, so the race is gone by construction (no lock, and above all no sleep).
     */
    private fun frames(): MutableList<Frame> = CopyOnWriteArrayList()

    private fun errorCodes(seen: List<Frame>) = seen.filterIsInstance<PocketError>().map { it.code }

    // ---- recipient binding -------------------------------------------------

    @Test
    fun a_bound_handoff_is_acceptable_only_by_the_named_device() = runBlocking {
        val fx = Fixture(CoroutineScope(Dispatchers.Default))
        val a = frames(); val stranger = frames(); val frank = frames()
        fx.openSession("owner", a)
        val h = fx.createBound("owner", recipient = "dev-frank", into = a)
        assertEquals("dev-frank", h.recipientDeviceId, "the binding is stored on the entity")

        // another full-power device is refused — the binding, not the credential kind, gates accept
        fx.route(AcceptHandoff(h.id), "dev-stranger", stranger)
        assertTrue("handoff_not_allowed" in errorCodes(stranger), "a foreign accept is refused: ${errorCodes(stranger)}")

        fx.routeAsCollaborator(AcceptHandoff(h.id), "dev-frank", frank)
        val accepted = frank.filterIsInstance<HandoffUpdated>().first().handoff
        assertEquals(HandoffStatus.IN_PROGRESS, accepted.status)
        assertEquals("dev-frank", accepted.recipientDeviceId)
    }

    @Test
    fun a_collaborator_may_not_touch_an_unbound_or_foreign_handoff() = runBlocking {
        val fx = Fixture(CoroutineScope(Dispatchers.Default))
        val a = frames(); val frank = frames()
        fx.openSession("owner", a)
        val open = fx.createBound("owner", recipient = null, into = a) // old open-invite behaviour

        fx.routeAsCollaborator(AcceptHandoff(open.id), "dev-frank", frank)
        assertTrue("handoff_not_allowed" in errorCodes(frank), "an unbound offer is not a collaborator's: ${errorCodes(frank)}")
        assertEquals(HandoffStatus.WAITING, fx.handoffs.registry.byId(open.id)?.status)

        // owner-side transitions are never a collaborator's, bound or not
        frank.clear()
        fx.routeAsCollaborator(CancelHandoff(open.id), "dev-frank", frank)
        assertTrue("collaborator_forbidden" in errorCodes(frank), "cancel is initiator-plane: ${errorCodes(frank)}")
    }

    // ---- the zero baseline + the IN_PROGRESS grant -------------------------

    @Test
    fun zero_baseline_no_discovery_and_no_session_without_a_grant() = runBlocking {
        val fx = Fixture(CoroutineScope(Dispatchers.Default))
        val frank = frames()

        // discovery surfaces are refused at the TYPE gate
        fx.routeAsCollaborator(ListDirectories(), "dev-frank", frank)
        assertTrue("collaborator_forbidden" in errorCodes(frank), "directory listing is denied: ${errorCodes(frank)}")

        // opening any session — even naming a real resumable id — is refused without a grant
        frank.clear()
        fx.routeAsCollaborator(OpenSession(fx.workdir, resumeId = Fixture.SESSION_ID), "dev-frank", frank)
        assertTrue("handoff_grant_required" in errorCodes(frank), "no grant, no session: ${errorCodes(frank)}")
        assertTrue(frank.filterIsInstance<SessionLive>().isEmpty())
    }

    @Test
    fun the_grant_opens_the_source_session_and_dies_on_return() = runBlocking {
        val fx = Fixture(CoroutineScope(Dispatchers.Default))
        val a = frames(); val frank = frames()
        fx.openSession("owner", a)
        val h = fx.createBound("owner", recipient = "dev-frank", into = a)
        fx.routeAsCollaborator(AcceptHandoff(h.id), "dev-frank", frank)
        assertEquals(HandoffStatus.IN_PROGRESS, frank.filterIsInstance<HandoffUpdated>().last().handoff.status)

        // IN_PROGRESS: the recipient opens the SOURCE session (workdir is forced to the handoff's)
        fx.routeAsCollaborator(OpenSession("/somewhere/else", resumeId = Fixture.SESSION_ID), "dev-frank", frank)
        val convoId = withTimeout(5_000) {
            while (frank.none { it is SessionLive }) delay(20)
            frank.filterIsInstance<SessionLive>().first().convoId
        }
        // ...and may drive it — both the guard and the lease gate agree (asserted at the gate, like the
        // owner wiring test: a real SendPrompt would spawn the stub backend's process)
        assertTrue(
            fx.handoffs.collaboratorGuard("dev-frank").vet(SendPrompt(convoId, "running the review"), fx.now)
                is CollaboratorGuard.Verdict.Allow,
            "the lease-holding recipient passes the grant gate",
        )
        assertNull(fx.registry.driveDenied(convoId, "dev-frank"), "…and the controller-lease gate")
        frank.clear()

        // RETURN: the grant ends the same instant — every session frame denies NOW
        fx.routeAsCollaborator(ReturnHandoff(h.id, null), "dev-frank", frank)
        assertEquals(HandoffStatus.RETURNED, frank.filterIsInstance<HandoffUpdated>().last().handoff.status)
        frank.clear()
        fx.routeAsCollaborator(SendPrompt(convoId, "one more thing"), "dev-frank", frank)
        assertTrue("handoff_grant_inactive" in errorCodes(frank), "a returned grant drives nothing: ${errorCodes(frank)}")
        frank.clear()
        fx.routeAsCollaborator(OpenSession(fx.workdir, resumeId = Fixture.SESSION_ID), "dev-frank", frank)
        assertTrue("handoff_grant_required" in errorCodes(frank), "re-opening after return is refused: ${errorCodes(frank)}")
    }

    @Test
    fun a_terminal_handoff_never_revives_the_grant() = runBlocking {
        val fx = Fixture(CoroutineScope(Dispatchers.Default))
        val a = frames(); val frank = frames()
        fx.openSession("owner", a)
        val h = fx.createBound("owner", recipient = "dev-frank", into = a)
        fx.routeAsCollaborator(AcceptHandoff(h.id), "dev-frank", frank)
        fx.routeAsCollaborator(OpenSession(fx.workdir, resumeId = Fixture.SESSION_ID), "dev-frank", frank)
        // the hot→cold rebuild (crypto MUST-FIX) mints the collaborator its OWN convo — the owner's
        // wall-less convo is closed — so the grant assertions key on the collaborator's convoId
        val convoId = withTimeout(5_000) {
            while (frank.none { it is SessionLive }) delay(20)
            frank.filterIsInstance<SessionLive>().first().convoId
        }
        fx.routeAsCollaborator(ReturnHandoff(h.id, null), "dev-frank", frank)
        fx.route(dev.ccpocket.protocol.CompleteHandoff(h.id), "owner", a)
        assertEquals(HandoffStatus.COMPLETED, a.filterIsInstance<HandoffUpdated>().last().handoff.status)

        // COMPLETED frees the session for the OWNER (HandoffGuard allows everyone)…
        assertNull(fx.registry.driveDenied(convoId, "owner"))
        // …but the collaborator's guard still fails closed: terminal ≠ grant
        frank.clear()
        fx.routeAsCollaborator(SendPrompt(convoId, "me too?"), "dev-frank", frank)
        assertTrue("handoff_grant_inactive" in errorCodes(frank), "a terminal handoff grants nothing: ${errorCodes(frank)}")
    }

    // ---- listing + fan-out filtering ---------------------------------------

    @Test
    fun a_collaborator_lists_only_its_own_handoffs() = runBlocking {
        val fx = Fixture(CoroutineScope(Dispatchers.Default))
        val a = frames(); val frank = frames()
        fx.openSession("owner", a)
        fx.createBound("owner", recipient = "dev-frank", into = a)
        // a second handoff on another session, bound to someone else
        val other = fx.handoffs.registry.create(
            sourceSessionId = "other-session", workdir = fx.workdir, agent = AgentKind.CLAUDE,
            initiatorDeviceId = "owner", kind = dev.ccpocket.protocol.HandoffKind.REVIEW,
            access = dev.ccpocket.protocol.HandoffAccess.REVIEW_READ_ONLY,
            brief = HandoffBrief(request = "for Alex"), recipientDeviceId = "dev-alex",
        )
        assertTrue(other is HandoffRegistry.HandoffOutcome.Ok)

        fx.routeAsCollaborator(ListHandoffs(), "dev-frank", frank)
        val listing = frank.filterIsInstance<HandoffListing>().single()
        assertEquals(1, listing.items.size, "only the offers addressed to dev-frank are visible")
        assertEquals("dev-frank", listing.items.single().recipientDeviceId)

        // the owner still sees everything
        fx.route(ListHandoffs(), "owner", a)
        assertEquals(2, a.filterIsInstance<HandoffListing>().single().items.size)
    }

    @Test
    fun fan_out_reaches_a_collaborator_sink_only_for_its_own_handoffs() = runBlocking {
        val fx = Fixture(CoroutineScope(Dispatchers.Default))
        val owner = frames(); val frank = frames(); val alex = frames()
        fx.handoffs.attach(KeyedSink("dev:owner", OutboundSink { owner += it }))
        fx.handoffs.attach(KeyedSink("dev:frank", OutboundSink { frank += it }), recipientDeviceId = "dev-frank")
        fx.handoffs.attach(KeyedSink("dev:alex", OutboundSink { alex += it }), recipientDeviceId = "dev-alex")

        val a = frames()
        fx.openSession("owner", a)
        val h = fx.createBound("owner", recipient = "dev-frank", into = a)

        // the offer reaches its recipient (the §4.2 delivery) and the owner — never a third party
        assertTrue(frank.filterIsInstance<HandoffUpdated>().any { it.handoff.id == h.id }, "the bound recipient hears its offer")
        assertTrue(owner.filterIsInstance<HandoffUpdated>().any { it.handoff.id == h.id }, "owner devices hear everything")
        assertTrue(alex.filterIsInstance<HandoffUpdated>().none { it.handoff.id == h.id }, "another collaborator hears NOTHING")

        // expiry sweep transitions obey the same filter
        fx.now += 25 * 3600_000L
        fx.handoffs.reconcile(fx.now)
        assertTrue(frank.filterIsInstance<HandoffUpdated>().any { it.handoff.status == HandoffStatus.EXPIRED })
        assertTrue(alex.filterIsInstance<HandoffUpdated>().none { it.handoff.id == h.id })
    }
}
