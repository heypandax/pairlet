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
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.CompleteHandoff
import dev.ccpocket.protocol.CreateHandoff
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HandoffBrief
import dev.ccpocket.protocol.HandoffCreated
import dev.ccpocket.protocol.HandoffResult
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.HandoffUpdated
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
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
 * The Session Handoff ROUTER wiring (SESSION-HANDOFF.md §9.2): pocket/handoff.* frames dispatch to
 * [HandoffRegistry] with the TRANSPORT device identity, the drive gate refuses non-controllers with
 * a `handoff_*`-coded PocketError, transitions fan out as [HandoffUpdated] to attached clients, and
 * a session carrying a non-terminal handoff survives the idle reaper. Follows the
 * RequestRouterOpenSessionTest fixture: a stub backend, a lazy open (#61), no processes.
 */
class HandoffRouterWiringTest {

    /** Never launches: a plain (non-takeOver) open is lazy (#61), so no process member is reached. */
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

    /** One daemon core's worth of wiring: registry + router + a temp-store handoff service with an
     *  injectable clock (the SchedulerService test pattern), installed exactly as DaemonCore does. */
    private class Fixture(scope: CoroutineScope) {
        var now: Long = System.currentTimeMillis()
        private val tmp = Files.createTempDirectory("ccp-handoff-wiring").toFile()
        val workdir: String = Files.createTempDirectory("ccp-handoff-wd").toString()
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
            registry.handoffs = handoffs // the DaemonCore install
        }

        /** Route [frame] as [deviceId] and record what came back on that device's sink. */
        suspend fun route(frame: Frame, deviceId: String, into: MutableList<Frame>) =
            router.handle(frame, { into += it }, deviceId = deviceId)

        /** Open the session so the drive gate has a live convo to key on (lazy — no process spawns). */
        suspend fun openSession(deviceId: String, into: MutableList<Frame>): String {
            route(OpenSession(workdir, resumeId = SESSION_ID), deviceId, into)
            return withTimeout(5_000) {
                while (into.none { it is SessionLive }) delay(20)
                into.filterIsInstance<SessionLive>().first().convoId
            }
        }

        suspend fun create(deviceId: String, into: MutableList<Frame>, expiresInSec: Long = 3600): dev.ccpocket.protocol.SessionHandoff {
            route(
                CreateHandoff(workdir, SESSION_ID, HandoffBrief(request = "review the relay ACK path"), expiresInSec = expiresInSec),
                deviceId, into,
            )
            val created = into.filterIsInstance<HandoffCreated>().first()
            assertTrue(created.ok, "create must succeed: ${created.error}")
            return assertNotNull(created.handoff)
        }

        companion object { const val SESSION_ID = "11111111-2222-3333-4444-555555555555" }
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

    /**
     * §6: v1 accepts ONE authorization combination. A raw client asking for the defined-but-unimplemented
     * CONTINUE / CONTINUE_SCOPED gets a machine-readable `handoff_not_supported` on the wire (not a
     * silent downgrade to REVIEW, and not the UNKNOWN "update the daemon" code) — and no entity is
     * created, so nothing can be accepted later either.
     */
    @Test
    fun the_wire_refuses_an_unimplemented_kind_or_access_with_handoff_not_supported() = runBlocking {
        val fx = Fixture(CoroutineScope(Dispatchers.Default))
        val a = frames()
        fx.openSession("dev-a", a)
        fx.route(
            CreateHandoff(
                fx.workdir, Fixture.SESSION_ID, HandoffBrief(request = "carry this forward"),
                kind = dev.ccpocket.protocol.HandoffKind.CONTINUE,
                access = dev.ccpocket.protocol.HandoffAccess.CONTINUE_SCOPED,
                allowedRoots = listOf(fx.workdir),
            ),
            "dev-a", a,
        )
        val refused = a.filterIsInstance<HandoffCreated>().last()
        assertTrue(!refused.ok)
        assertEquals("handoff_not_supported", refused.code, "the refusal must be machine-readable: ${refused.error}")
        assertNull(refused.handoff)
        assertTrue(fx.handoffs.registry.list().isEmpty(), "nothing may be persisted for a combination we can't enforce")

        // the implemented combination is unaffected (fresh list: the fixture's create() reads the FIRST
        // HandoffCreated, which is still the refusal above)
        assertEquals(HandoffStatus.WAITING, fx.create("dev-a", frames()).status)
    }

    @Test
    fun waiting_locks_every_device_out_of_the_session() = runBlocking {
        val fx = Fixture(CoroutineScope(Dispatchers.Default))
        val a = frames(); val b = frames()
        val convoId = fx.openSession("dev-a", a)

        val h = fx.create("dev-a", a)
        assertEquals(HandoffStatus.WAITING, h.status)
        assertEquals("dev-a", h.initiatorDeviceId, "the initiator identity is the transport's, not a frame field")

        // §5.3 invariant 2: WAITING refuses EVERYONE — the initiator included
        fx.route(SendPrompt(convoId, "one more thing"), "dev-a", a)
        assertTrue("handoff_waiting_locked" in errorCodes(a), "initiator prompt must be refused while WAITING: ${errorCodes(a)}")
        fx.route(SendPrompt(convoId, "let me in"), "dev-b", b)
        assertTrue("handoff_waiting_locked" in errorCodes(b), "any other device is refused too: ${errorCodes(b)}")
        fx.route(CancelTurn(convoId), "dev-a", a)
        assertEquals(2, errorCodes(a).count { it == "handoff_waiting_locked" }, "CancelTurn rides the same gate")
    }

    @Test
    fun only_the_accepting_controller_may_drive_in_progress() = runBlocking {
        val fx = Fixture(CoroutineScope(Dispatchers.Default))
        val a = frames(); val b = frames(); val c = frames()
        val convoId = fx.openSession("dev-a", a)
        val h = fx.create("dev-a", a)

        fx.route(AcceptHandoff(h.id), "dev-b", b)
        val accepted = b.filterIsInstance<HandoffUpdated>().first().handoff
        assertEquals(HandoffStatus.IN_PROGRESS, accepted.status)
        assertEquals("dev-b", accepted.recipientDeviceId)

        // the CAS: a second device's accept observes IN_PROGRESS and is refused
        fx.route(AcceptHandoff(h.id), "dev-c", c)
        assertTrue("handoff_not_waiting" in errorCodes(c), "a losing accept is refused: ${errorCodes(c)}")

        // non-controller SendPrompt refused; the lease-holding recipient is allowed through the gate
        fx.route(SendPrompt(convoId, "my session!"), "dev-a", a)
        assertTrue("handoff_not_controller" in errorCodes(a), "the initiator is not the controller: ${errorCodes(a)}")
        assertNull(fx.registry.driveDenied(convoId, "dev-b"), "the controller drives")
    }

    @Test
    fun create_accept_return_complete_runs_through_the_router() = runBlocking {
        val fx = Fixture(CoroutineScope(Dispatchers.Default))
        val a = frames(); val b = frames()
        val convoId = fx.openSession("dev-a", a)
        val h = fx.create("dev-a", a)

        fx.route(AcceptHandoff(h.id), "dev-b", b)
        assertEquals(HandoffStatus.IN_PROGRESS, b.filterIsInstance<HandoffUpdated>().last().handoff.status)

        fx.route(ReturnHandoff(h.id, HandoffResult(summary = "LGTM", verdict = "approve")), "dev-b", b)
        val returned = b.filterIsInstance<HandoffUpdated>().last().handoff
        assertEquals(HandoffStatus.RETURNED, returned.status)
        assertEquals("dev-b", returned.result?.returnedByDeviceId, "the return stamp is daemon truth, not the client's")

        // control is back with the initiator's side: the recipient is refused, the initiator drives
        fx.route(SendPrompt(convoId, "one more fix"), "dev-b", b)
        assertTrue("handoff_returned_to_initiator" in errorCodes(b), "recipient loses drive on return: ${errorCodes(b)}")
        assertNull(fx.registry.driveDenied(convoId, "dev-a"))

        fx.route(CompleteHandoff(h.id), "dev-a", a)
        assertEquals(HandoffStatus.COMPLETED, a.filterIsInstance<HandoffUpdated>().last().handoff.status)
        assertNull(fx.registry.driveDenied(convoId, "dev-b"), "a terminal handoff frees the session for everyone")
    }

    @Test
    fun sweep_expiry_fans_out_to_every_attached_client() = runBlocking {
        val fx = Fixture(CoroutineScope(Dispatchers.Default))
        val a = frames(); val b = frames()
        // the DeviceSessions/WsConnection attach: stable-keyed sinks registered as fan-out targets
        fx.handoffs.attach(KeyedSink("dev:a", OutboundSink { a += it }))
        fx.handoffs.attach(KeyedSink("dev:b", OutboundSink { b += it }))

        val h = fx.create("dev-a", frames(), expiresInSec = 60)
        fx.now += 61_000 // outrun the WAITING deadline
        fx.handoffs.reconcile(fx.now) // what the periodic sweep loop runs

        for ((who, seen) in listOf("a" to a, "b" to b)) {
            val expired = seen.filterIsInstance<HandoffUpdated>().map { it.handoff }
                .firstOrNull { it.id == h.id && it.status == HandoffStatus.EXPIRED }
            assertNotNull(expired, "client $who must learn the expiry from the sweep fan-out")
        }
    }

    @Test
    fun a_session_with_a_live_handoff_survives_the_idle_reaper() = runBlocking {
        val fx = Fixture(CoroutineScope(Dispatchers.Default))
        val a = frames()
        val convoId = fx.openSession("dev-a", a)
        val h = fx.create("dev-a", a)

        fx.registry.reapIdle(idleMs = -1) // "everything idle is reapable" — except handoff-protected
        assertNotNull(fx.registry.modeOf(convoId), "a WAITING-handoff session must not be reaped")

        fx.route(CancelHandoff(h.id), "dev-a", a) // → CANCELLED (terminal): protection lifts
        fx.registry.reapIdle(idleMs = -1)
        assertNull(fx.registry.modeOf(convoId), "a terminal handoff frees the session for the reaper")
    }
}
