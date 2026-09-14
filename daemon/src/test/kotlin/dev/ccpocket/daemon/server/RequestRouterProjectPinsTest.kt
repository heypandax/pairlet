package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.bridge.BridgeCaps
import dev.ccpocket.daemon.bridge.GuestCaps
import dev.ccpocket.daemon.bridge.GuestScope
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.conversation.KeyedSink
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.handoff.CollaboratorCaps
import dev.ccpocket.daemon.handoff.CollaboratorScope
import dev.ccpocket.daemon.pins.MemoryProjectPinStore
import dev.ccpocket.daemon.pins.PinStoreRead
import dev.ccpocket.daemon.pins.PinStoreState
import dev.ccpocket.daemon.pins.PinStoreWrite
import dev.ccpocket.daemon.pins.ProjectPinConnection
import dev.ccpocket.daemon.pins.ProjectPinService
import dev.ccpocket.daemon.pins.ProjectPinStore
import dev.ccpocket.daemon.pins.StoredPin
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ProjectPinErrors
import dev.ccpocket.protocol.ProjectPinOp
import dev.ccpocket.protocol.ProjectPinResolution
import dev.ccpocket.protocol.ProjectPinsSnapshot
import dev.ccpocket.protocol.ProjectPinsState
import dev.ccpocket.protocol.SyncProjectPins
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The router's pin-sync door (issue #362): three-way owner admission, transport identity and the transport's own
 * connection context as the only authority, subscription registration by accepted fetches alone, dissemination of a
 * durable commit that never waits on the requester, per-connection capability gating in both directions, and
 * default-deny for every restricted credential class.
 */
class RequestRouterProjectPinsTest {

    private val tmp = Files.createTempDirectory("ccp-router-pins").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tmp.deleteRecursively()
    }

    private fun router(pins: ProjectPinService?) = RequestRouter(
        registry = SessionRegistry(scope, backends = emptyMap()),
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
        archiveFile = tmp.resolve("session-archive.json"),
        projectPins = pins,
    )

    private class Collect : OutboundSink {
        val frames = mutableListOf<Frame>()
        override suspend fun emit(frame: Frame) { synchronized(frames) { frames += frame } }
        fun pinStates() = synchronized(frames) { frames.filterIsInstance<ProjectPinsState>() }
    }

    /** A transport context holding nothing but its own facts — what one relay holder or LAN socket answers. */
    private class Conn(var current: Boolean = true, var subscription: String? = null) : ProjectPinConnection {
        var accepts = 0
        override suspend fun isCurrent() = current
        override suspend fun currentSubscription() = subscription.takeIf { current }
        override suspend fun acceptFetch(subscriptionId: String): Boolean {
            if (!current) return false
            accepts++
            subscription = subscriptionId
            return true
        }
    }

    private val inc = "inc-0123456789abcdef"
    private val sub = "sub-0123456789abcdef"
    private val sub2 = "sub2-0123456789abcdef"
    private val stream = "stream-0123456789ab"
    private fun service() = ProjectPinService(MemoryProjectPinStore(PinStoreState(incarnation = inc)), scope)
    private fun declared() = RequestRouter.ClientCapsHolder().apply { supportsProjectPins = true }
    private fun fetch(subscription: String = sub) = SyncProjectPins("r-fetch", subscription, stream)
    private fun pinX(seq: Long = 1, subscription: String = sub) =
        SyncProjectPins("r-pin-$seq", subscription, stream, listOf(ProjectPinOp(seq, "/nonexistent-ccp/x", pinned = true)), expectedIncarnation = inc)

    @Test
    fun only_an_accepted_fetch_registers_and_a_batch_then_commits_under_that_subscription() = runBlocking {
        val r = router(service())
        val sink = Collect()
        val caps = declared()
        val conn = Conn()
        r.handle(fetch(), sink, caps = caps, deviceId = "devA", pinConnection = conn)
        val fetched = sink.pinStates().single()
        assertNull(fetched.error)
        assertNull(fetched.resolutions, "a fetch resolves no paths")
        assertEquals(sub, conn.subscription)

        r.handle(pinX(), sink, caps = caps, deviceId = "devA", pinConnection = conn)
        val reply = sink.pinStates().last()
        assertNull(reply.error)
        assertEquals(sub, reply.subscriptionId)
        assertEquals("r-pin-1", reply.requestId)
        assertEquals(1, reply.ackSeq)
        assertEquals(listOf("/nonexistent-ccp/x"), reply.snapshot?.pins?.map { it.path })
        assertEquals(listOf(ProjectPinResolution(1, "/nonexistent-ccp/x", reply.snapshot!!.pins.single().key)), reply.resolutions)
        assertEquals(1, conn.accepts, "the batch registered nothing")
        assertNull(caps.pinSubscriptionId, "the router never writes a subscription itself — the transport's context does")
    }

    @Test
    fun a_batch_before_any_accepted_fetch_is_stale_and_commits_nothing() = runBlocking {
        val r = router(service())
        val sink = Collect()
        val conn = Conn()
        r.handle(pinX(), sink, caps = declared(), deviceId = "devA", pinConnection = conn)
        val refused = sink.pinStates().single()
        assertEquals(ProjectPinErrors.SUBSCRIPTION_STALE, refused.error)
        assertEquals("r-pin-1", refused.requestId, "the refusal is correlated")
        assertEquals(stream, refused.streamId)
        assertNull(refused.snapshot)
        assertNull(refused.ackSeq)
        assertNull(refused.resolutions)
        assertNull(conn.subscription, "a refusal never registers a subscription")

        val owner = Collect()
        r.handle(fetch(), owner, caps = declared(), deviceId = "devA", pinConnection = Conn())
        assertEquals(0, owner.pinStates().single().ackSeq)
        assertEquals(emptyList(), owner.pinStates().single().snapshot?.pins)
    }

    @Test
    fun a_newer_fetch_moves_the_subscription_and_a_stale_batch_can_neither_commit_nor_move_it_back() = runBlocking {
        val r = router(service())
        val sink = Collect()
        val conn = Conn()
        r.handle(fetch(sub), sink, caps = declared(), deviceId = "devA", pinConnection = conn)
        r.handle(fetch(sub2), sink, caps = declared(), deviceId = "devA", pinConnection = conn)
        assertEquals(sub2, conn.subscription)

        r.handle(pinX(subscription = sub), sink, caps = declared(), deviceId = "devA", pinConnection = conn) // delayed from the older generation
        assertEquals(ProjectPinErrors.SUBSCRIPTION_STALE, sink.pinStates().last().error)
        assertEquals(sub2, conn.subscription, "a stale batch never rolls the subscription back")

        conn.current = false // retired (a newer connection's fetch was accepted, or it closed)
        r.handle(fetch(sub), sink, caps = declared(), deviceId = "devA", pinConnection = conn)
        assertEquals(ProjectPinErrors.SUBSCRIPTION_STALE, sink.pinStates().last().error)
        r.handle(pinX(subscription = sub2), sink, caps = declared(), deviceId = "devA", pinConnection = conn)
        assertEquals(ProjectPinErrors.SUBSCRIPTION_STALE, sink.pinStates().last().error)
        assertEquals(2, conn.accepts)

        val owner = Collect()
        r.handle(fetch(), owner, caps = declared(), deviceId = "devA", pinConnection = Conn())
        assertEquals(emptyList(), owner.pinStates().single().snapshot?.pins, "none of the stale batches committed")
    }

    @Test
    fun a_malformed_gapped_or_failed_request_leaves_the_registered_subscription_alone() = runBlocking {
        val r = router(service())
        val sink = Collect()
        val conn = Conn()
        r.handle(fetch(sub), sink, caps = declared(), deviceId = "devA", pinConnection = conn)

        r.handle(SyncProjectPins("bad request id", sub2, stream), sink, caps = declared(), deviceId = "devA", pinConnection = conn)
        assertEquals(ProjectPinErrors.INVALID_REQUEST, sink.pinStates().last().error)
        r.handle(pinX(seq = 5, subscription = sub), sink, caps = declared(), deviceId = "devA", pinConnection = conn)
        assertEquals(ProjectPinErrors.SEQUENCE_GAP, sink.pinStates().last().error)
        assertEquals(sub, conn.subscription)

        val broken = object : ProjectPinStore {
            override fun read(): PinStoreRead = PinStoreRead.Missing
            override fun write(state: PinStoreState): PinStoreWrite = PinStoreWrite.UnchangedFailure
        }
        router(ProjectPinService(broken, scope)).handle(fetch(sub2), sink, caps = declared(), deviceId = "devA", pinConnection = conn)
        val failed = sink.pinStates().last()
        assertEquals(ProjectPinErrors.STORE_UNAVAILABLE, failed.error)
        assertNull(failed.snapshot)
        assertEquals(sub, conn.subscription, "a fetch the store could not serve registers nothing")
        assertEquals(1, conn.accepts)
    }

    @Test
    fun a_connection_retired_while_its_fetch_waited_is_not_told_it_succeeded_but_its_commit_still_spreads() = runBlocking {
        // the fetch's reconcile commits (a stored identity moved), then the connection turns out to be retired
        val svc = ProjectPinService(
            MemoryProjectPinStore(PinStoreState(incarnation = inc, revision = 1, pins = listOf(StoredPin("/nonexistent-ccp/p", "old-key")))),
            scope,
            keyOf = { "new-key" },
        )
        val toA = Channel<ProjectPinsSnapshot>(Channel.UNLIMITED)
        val toB = Channel<ProjectPinsSnapshot>(Channel.UNLIMITED)
        svc.attach("dev:devA") { toA.send(it) }
        svc.attach("dev:devB") { toB.send(it) }
        val retiredMeanwhile = object : ProjectPinConnection {
            override suspend fun isCurrent() = true
            override suspend fun currentSubscription(): String? = null
            override suspend fun acceptFetch(subscriptionId: String) = false
        }
        val requester = Collect()
        router(svc).handle(fetch(), KeyedSink("dev:devA", requester), caps = declared(), deviceId = "devA", pinConnection = retiredMeanwhile)
        val reply = requester.pinStates().single()
        assertEquals(ProjectPinErrors.SUBSCRIPTION_STALE, reply.error)
        assertNull(reply.snapshot, "never reported as a successful fetch")
        assertEquals(2, withTimeout(5_000) { toB.receive() }.revision)
        assertEquals(2, withTimeout(5_000) { toA.receive() }.revision, "its device's real subscriber is not skipped: that reply carried nothing")
    }

    @Test
    fun a_throwing_or_cancelled_reply_still_offers_the_commit_to_siblings() = runBlocking {
        val svc = service()
        val toB = Channel<ProjectPinsSnapshot>(Channel.UNLIMITED)
        svc.attach("dev:devB") { toB.send(it) }
        val r = router(svc)
        val conn = Conn(subscription = sub)

        val throwing = KeyedSink("dev:devA", OutboundSink { if (it is ProjectPinsState) error("requester socket died") })
        assertTrue(runCatching { r.handle(pinX(1), throwing, caps = declared(), deviceId = "devA", pinConnection = conn) }.isFailure)
        assertEquals(1, withTimeout(5_000) { toB.receive() }.revision, "the durable commit reached the sibling anyway")

        val entered = CompletableDeferred<Unit>()
        val hanging = KeyedSink("dev:devA", OutboundSink { if (it is ProjectPinsState) { entered.complete(Unit); awaitCancellation() } })
        // another project, so this commit really changes the list — re-pinning x would be an idempotent no-op
        val another = SyncProjectPins("r-pin-2", sub, stream, listOf(ProjectPinOp(2, "/nonexistent-ccp/y", pinned = true)), expectedIncarnation = inc)
        val job = scope.launch { r.handle(another, hanging, caps = declared(), deviceId = "devA", pinConnection = conn) }
        withTimeout(5_000) { entered.await() }
        job.cancel()
        assertEquals(2, withTimeout(5_000) { toB.receive() }.revision, "a requester stuck in its reply delays no sibling")
    }

    @Test
    fun the_capability_declaration_is_what_enables_the_holder() = runBlocking {
        val caps = RequestRouter.ClientCapsHolder()
        val r = router(null)
        r.handle(ClientCaps(supportsProjectPins = true), Collect(), caps = caps, deviceId = "devA")
        assertTrue(caps.supportsProjectPins)
        r.handle(ClientCaps(), Collect(), caps = caps, deviceId = "devA") // a rolled-back build re-declares without it
        assertFalse(caps.supportsProjectPins)
    }

    @Test
    fun every_restricted_credential_class_gets_silence_and_never_mutates_or_subscribes() = runBlocking {
        val r = router(service())
        val guest = GuestScope(roots = listOf("/tmp"), ownedSessions = emptySet(), label = "guest", expiresAt = null, tier = AccessTier.REVIEW)
        val callers = listOf<suspend (OutboundSink, RequestRouter.ClientCapsHolder, Conn) -> Unit>(
            { s, c, p -> r.handle(fetch(), s, origin = "feishu-bridge", caps = c, deviceId = "devBridge", pinConnection = p) },
            { s, c, p -> r.handle(pinX(), s, guestScope = guest, caps = c, deviceId = "devGuest", pinConnection = p) },
            // a collaborator arrives with origin == null AND guestScope == null — the vacuous two-term test's blind spot
            { s, c, p -> r.handle(fetch(), s, caps = c, deviceId = "devCollab", collabScope = CollaboratorScope("devCollab"), pinConnection = p) },
        )
        for (call in callers) {
            val sink = Collect()
            val caps = declared()
            val conn = Conn(subscription = sub)
            call(sink, caps, conn)
            assertTrue(sink.frames.isEmpty(), "a restricted caller must get no pin frame at all: ${sink.frames}")
            assertEquals(0, conn.accepts)
        }
        val owner = Collect()
        r.handle(fetch(), owner, caps = declared(), deviceId = "devOwner", pinConnection = Conn())
        assertEquals(emptyList(), owner.pinStates().single().snapshot?.pins, "no restricted request committed anything")
    }

    @Test
    fun an_undeclared_connection_gets_nothing_and_never_becomes_a_subscription() = runBlocking {
        val r = router(service())
        val sink = Collect()
        val conn = Conn()
        r.handle(fetch(), sink, caps = RequestRouter.ClientCapsHolder(), deviceId = "devA", pinConnection = conn)
        r.handle(fetch(), sink, caps = null, deviceId = "devA", pinConnection = conn)
        assertTrue(sink.frames.isEmpty())
        assertEquals(0, conn.accepts)
    }

    @Test
    fun a_caller_without_a_transport_identity_or_context_can_neither_subscribe_nor_mutate() = runBlocking {
        val r = router(service())
        val sink = Collect()
        val conn = Conn(subscription = sub)
        r.handle(pinX(), sink, caps = declared(), deviceId = null, pinConnection = conn) // the plaintext --local socket
        r.handle(pinX(), sink, caps = declared(), deviceId = "devA") // an in-process caller: no connection context
        r.handle(fetch(sub2), sink, caps = declared(), deviceId = "devA")
        assertEquals(List(3) { ProjectPinErrors.UNAVAILABLE }, sink.pinStates().map { it.error })
        assertTrue(sink.pinStates().all { it.snapshot == null })
        assertEquals(0, conn.accepts)
        val owner = Collect()
        r.handle(fetch(), owner, caps = declared(), deviceId = "devOwner", pinConnection = Conn())
        assertEquals(emptyList(), owner.pinStates().single().snapshot?.pins)
    }

    @Test
    fun a_router_without_a_pin_store_answers_unavailable_instead_of_silence() = runBlocking {
        val sink = Collect()
        router(null).handle(fetch(), sink, caps = declared(), deviceId = "devA", pinConnection = Conn())
        val reply = sink.pinStates().single()
        assertEquals(ProjectPinErrors.UNAVAILABLE, reply.error)
        assertNull(reply.snapshot)
    }

    @Test
    fun a_commit_is_offered_to_other_subscribers_but_not_back_to_the_requester() = runBlocking {
        val svc = service()
        val toA = Channel<ProjectPinsSnapshot>(Channel.UNLIMITED)
        val toB = Channel<ProjectPinsSnapshot>(Channel.UNLIMITED)
        svc.attach("dev:devA") { toA.send(it) }
        svc.attach("dev:devB") { toB.send(it) }
        val requester = KeyedSink("dev:devA", Collect())
        router(svc).handle(pinX(), requester, caps = declared(), deviceId = "devA", pinConnection = Conn(subscription = sub))
        assertEquals(1, withTimeout(5_000) { toB.receive() }.revision)
        assertNull(withTimeoutOrNull(300) { toA.receive() }, "the requester already has the snapshot in its reply")
    }

    @Test
    fun pin_frames_pass_the_egress_gate_per_connection_never_globally() {
        val frame = ProjectPinsState("s", snapshot = ProjectPinsSnapshot("i", 1))
        val modern = declared()
        val legacySibling = RequestRouter.ClientCapsHolder()
        assertTrue(RequestRouter.allowedForCaps(frame, modern))
        assertFalse(RequestRouter.allowedForCaps(frame, legacySibling), "one modern client never opts a sibling in")
        assertFalse(RequestRouter.allowedForCaps(frame, null), "a legacy ingress without a holder fails closed")
        modern.supportsProjectPins = false
        assertFalse(RequestRouter.allowedForCaps(frame, modern))
    }

    @Test
    fun restricted_whitelists_deny_both_pin_frames_in_both_directions() {
        val request = pinX()
        val state = ProjectPinsState("s", snapshot = ProjectPinsSnapshot("i", 1))
        assertFalse(GuestCaps.ingressAllowed(request)); assertFalse(GuestCaps.egressAllowed(state))
        assertFalse(BridgeCaps.ingressAllowed(request)); assertFalse(BridgeCaps.egressAllowed(state))
        for (purpose in CollaboratorPurpose.entries) {
            assertFalse(CollaboratorCaps.ingressAllowed(request, purpose), "collaborator ($purpose) ingress")
            assertFalse(CollaboratorCaps.egressAllowed(state, purpose), "collaborator ($purpose) egress")
        }
        assertNotNull(state.snapshot)
    }
}
