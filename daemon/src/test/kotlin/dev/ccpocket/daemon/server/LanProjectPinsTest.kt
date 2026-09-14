package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.pins.MemoryProjectPinStore
import dev.ccpocket.daemon.pins.PinStoreState
import dev.ccpocket.daemon.pins.ProjectPinService
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.LanHello
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ProjectPinErrors
import dev.ccpocket.protocol.ProjectPinOp
import dev.ccpocket.protocol.ProjectPinsState
import dev.ccpocket.protocol.SyncProjectPins
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.websocket.Frame as WsFrame

/**
 * The DIRECT (LAN) transport's project-pin plane (issue #362), through real Noise-gated sockets: owner sockets
 * get pushes with their own subscription while a legacy sibling gets none; a socket's subscription moves only with
 * its own accepted fetches, never with a stale batch; a refusal reaches the socket that asked without registering
 * it; a device revoked while its socket is idle is cut at the next pin frame — including one already queued behind
 * a stalled write — without having to send anything; the plaintext `--local` socket can never subscribe; and a
 * hung-up socket leaves no slot behind.
 */
class LanProjectPinsTest {

    private class FakeWsSession(override val coroutineContext: CoroutineContext, sentCapacity: Int) : WebSocketSession {
        val inbound = Channel<WsFrame>(Channel.UNLIMITED)
        val sent = Channel<WsFrame>(sentCapacity)

        override val incoming: ReceiveChannel<WsFrame> get() = inbound
        override val outgoing: SendChannel<WsFrame> get() = sent
        override val extensions: List<WebSocketExtension<*>> get() = emptyList()
        override var masking: Boolean = false
        override var maxFrameSize: Long = Long.MAX_VALUE

        override suspend fun send(frame: WsFrame) { sent.send(frame) }
        override suspend fun flush() {}

        @Deprecated("Use cancel() instead.", replaceWith = ReplaceWith("cancel()", "kotlinx.coroutines.cancel"))
        override fun terminate() { inbound.close() }

        fun hangUp() { inbound.close() }
    }

    private class Conn(val ws: FakeWsSession, val job: Job, private val caught: AtomicReference<Throwable?>) {
        val failure: Throwable? get() = caught.get()
    }

    private class Fixture(val scope: CoroutineScope) {
        private val tmp = Files.createTempDirectory("ccp-lan-pins").toFile()
        val identity: Identity = Identity.loadOrCreate(tmp.resolve("identity.json"))
        /** The allow-list the gate consults per lookup — a fixture, never the developer's devices.json. */
        val allowed = ConcurrentHashMap<String, ByteArray>()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pins = ProjectPinService(MemoryProjectPinStore(PinStoreState(incarnation = INC)), serviceScope)
        val registry = SessionRegistry(scope, backends = emptyMap())
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
            archiveFile = tmp.resolve("session-archive.json"),
            projectPins = pins,
        )

        fun device(id: String): E2ECrypto.KeyPair = E2ECrypto.generateKeyPair().also { allowed[id] = it.publicRaw }

        fun connect(gated: Boolean, sentCapacity: Int = Channel.UNLIMITED): Conn {
            val ws = FakeWsSession(scope.coroutineContext, sentCapacity)
            val gate = if (gated) LanE2E(identity = identity, lanUrl = { null }, pairedDevices = { HashMap(allowed) }) else null
            val caught = AtomicReference<Throwable?>(null)
            val job = scope.launch {
                try {
                    WsConnection(ws, router, registry, e2e = gate).serve()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    caught.set(t)
                }
            }
            return Conn(ws, job, caught)
        }

        fun close() {
            serviceScope.cancel()
            tmp.deleteRecursively()
        }
    }

    private fun envelopeText(body: Frame) = PocketJson.encodeToString(Envelope("c", 0, body = body))

    /** Hello + Noise as the paired device; consumes the handshake reply and the queued DaemonInfo. */
    private suspend fun Fixture.handshake(conn: Conn, id: String, keys: E2ECrypto.KeyPair): E2ESession {
        val initiator = E2ESession.initiator(keys.privateRaw, keys.publicRaw, identity.e2ePubRaw, ByteArray(0))
        conn.ws.inbound.send(WsFrame.Text(envelopeText(LanHello(id))))
        conn.ws.inbound.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, initiator.ephPublic)))
        val reply = assertNotNull(withTimeout(5_000) { conn.ws.sent.receive() } as? WsFrame.Binary)
        assertEquals(Wire.HANDSHAKE, Wire.payloadType(reply.data))
        val session = initiator.finish(Wire.payloadBody(reply.data))
        val info = receive(conn, session) as DaemonInfo
        assertTrue(info.supportsProjectPins, "the LAN gate advertises pin sync")
        return session
    }

    private suspend fun send(conn: Conn, session: E2ESession, body: Frame) {
        conn.ws.inbound.send(WsFrame.Binary(true, Wire.payload(Wire.TRANSPORT, session.seal(envelopeText(body).encodeToByteArray()))))
    }

    private suspend fun receive(conn: Conn, session: E2ESession): Frame {
        val frame = assertNotNull(withTimeout(5_000) { conn.ws.sent.receive() } as? WsFrame.Binary)
        val plain = assertNotNull(session.open(Wire.payloadBody(frame.data)), "a sealed frame must decrypt")
        return PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body
    }

    private fun stream(id: String) = "stream-$id-0123456789"
    private fun sub(tag: String) = "sub-$tag-0123456789abcdef"

    private suspend fun subscribe(conn: Conn, session: E2ESession, id: String, subscription: String) {
        send(conn, session, ClientCaps(supportsProjectPins = true))
        send(conn, session, SyncProjectPins("fetch", subscription, stream(id)))
        val reply = receive(conn, session) as ProjectPinsState
        assertNull(reply.error)
        assertEquals(subscription, reply.subscriptionId)
    }

    private fun op(seq: Long, subscription: String, id: String, path: String, requestId: String = "op-$seq") =
        SyncProjectPins(requestId, subscription, stream(id), listOf(ProjectPinOp(seq, path, pinned = true)), expectedIncarnation = INC)

    private suspend fun commit(conn: Conn, session: E2ESession, id: String, subscription: String, seq: Long, path: String) {
        send(conn, session, op(seq, subscription, id, path))
        val ack = receive(conn, session) as ProjectPinsState
        assertNull(ack.error)
        assertEquals(seq, ack.ackSeq)
    }

    @Test
    fun lan_owners_receive_pushes_with_their_own_subscription_and_a_legacy_sibling_gets_none() = runBlocking {
        val f = Fixture(this)
        try {
            val keysA = f.device("devA"); val keysB = f.device("devB"); val keysC = f.device("devC")
            val a = f.connect(gated = true); val sa = f.handshake(a, "devA", keysA)
            subscribe(a, sa, "devA", sub("a"))
            val b = f.connect(gated = true); val sb = f.handshake(b, "devB", keysB)
            send(b, sb, ClientCaps(supportsAgents = listOf("opencode"))) // an already-shipped App on the same LAN
            val c = f.connect(gated = true); val sc = f.handshake(c, "devC", keysC)
            subscribe(c, sc, "devC", sub("c"))

            commit(a, sa, "devA", sub("a"), 1, "/nonexistent-ccp/x")

            val push = receive(c, sc) as ProjectPinsState
            assertEquals(sub("c"), push.subscriptionId)
            assertNull(push.requestId)
            assertEquals(listOf("/nonexistent-ccp/x"), push.snapshot?.pins?.map { it.path })
            assertNull(withTimeoutOrNull(300) { b.ws.sent.receive() }, "the legacy sibling gets no pin frame")
            assertNull(withTimeoutOrNull(300) { a.ws.sent.receive() }, "the requester gets its reply, not a second push")
            listOf(a, b, c).forEach { it.ws.hangUp() }
        } finally {
            f.close()
        }
    }

    @Test
    fun a_newer_fetch_moves_the_sockets_subscription_and_a_stale_batch_can_neither_commit_nor_move_it_back() = runBlocking {
        val f = Fixture(this)
        try {
            val keysA = f.device("devA"); val keysC = f.device("devC")
            val a = f.connect(gated = true); val sa = f.handshake(a, "devA", keysA)
            subscribe(a, sa, "devA", sub("a1"))
            send(a, sa, SyncProjectPins("fetch-2", sub("a2"), stream("devA")))
            assertEquals(sub("a2"), (receive(a, sa) as ProjectPinsState).subscriptionId)

            send(a, sa, op(1, sub("a1"), "devA", "/nonexistent-ccp/stale", requestId = "late-op"))
            val refused = receive(a, sa) as ProjectPinsState
            assertEquals(ProjectPinErrors.SUBSCRIPTION_STALE, refused.error, "the refusal still reaches the socket that asked")
            assertEquals("late-op", refused.requestId)
            assertNull(refused.snapshot)

            val c = f.connect(gated = true); val sc = f.handshake(c, "devC", keysC)
            subscribe(c, sc, "devC", sub("c"))
            commit(c, sc, "devC", sub("c"), 1, "/nonexistent-ccp/x")
            val push = receive(a, sa) as ProjectPinsState
            assertEquals(sub("a2"), push.subscriptionId, "the stale batch did not roll the subscription back")
            assertEquals(listOf("/nonexistent-ccp/x"), push.snapshot?.pins?.map { it.path }, "…and committed nothing")
            listOf(a, c).forEach { it.ws.hangUp() }
        } finally {
            f.close()
        }
    }

    @Test
    fun a_batch_before_any_fetch_is_refused_to_its_socket_without_registering_it() = runBlocking {
        val f = Fixture(this)
        try {
            val keysA = f.device("devA"); val keysC = f.device("devC")
            val a = f.connect(gated = true); val sa = f.handshake(a, "devA", keysA)
            send(a, sa, ClientCaps(supportsProjectPins = true))
            send(a, sa, op(1, sub("a"), "devA", "/nonexistent-ccp/early"))
            val refused = receive(a, sa) as ProjectPinsState
            assertEquals(ProjectPinErrors.SUBSCRIPTION_STALE, refused.error)
            assertEquals("op-1", refused.requestId)

            val c = f.connect(gated = true); val sc = f.handshake(c, "devC", keysC)
            subscribe(c, sc, "devC", sub("c"))
            commit(c, sc, "devC", sub("c"), 1, "/nonexistent-ccp/x")
            assertNull(withTimeoutOrNull(300) { a.ws.sent.receive() }, "a refusal registers nothing, so no push follows")
            listOf(a, c).forEach { it.ws.hangUp() }
        } finally {
            f.close()
        }
    }

    @Test
    fun an_idle_revoked_subscriber_gets_no_pin_frame_without_sending_anything() = runBlocking {
        val f = Fixture(this)
        try {
            val keysA = f.device("devA"); val keysC = f.device("devC")
            val a = f.connect(gated = true); val sa = f.handshake(a, "devA", keysA)
            subscribe(a, sa, "devA", sub("a"))
            val c = f.connect(gated = true); val sc = f.handshake(c, "devC", keysC)
            subscribe(c, sc, "devC", sub("c"))

            f.allowed.remove("devC") // revoked on another path; this socket stays silent
            commit(a, sa, "devA", sub("a"), 1, "/nonexistent-ccp/private")
            assertNull(withTimeoutOrNull(500) { c.ws.sent.receive() }, "a revoked device must not learn a new pinned path")
            listOf(a, c).forEach { it.ws.hangUp() }
        } finally {
            f.close()
        }
    }

    @Test
    fun a_pin_frame_already_queued_behind_a_stalled_write_is_dropped_and_the_socket_closed_on_revoke() = runBlocking {
        val f = Fixture(this)
        try {
            val keysA = f.device("devA"); val keysC = f.device("devC")
            val a = f.connect(gated = true); val sa = f.handshake(a, "devA", keysA)
            subscribe(a, sa, "devA", sub("a"))
            // a rendezvous socket: every write blocks until the test reads it, so later frames queue in the outbox
            val c = f.connect(gated = true, sentCapacity = Channel.RENDEZVOUS); val sc = f.handshake(c, "devC", keysC)
            subscribe(c, sc, "devC", sub("c"))

            commit(a, sa, "devA", sub("a"), 1, "/nonexistent-ccp/one") // push #1: sealed, its write now blocks
            commit(a, sa, "devA", sub("a"), 2, "/nonexistent-ccp/two") // push #2: delivered while still allow-listed → queued
            f.allowed.remove("devC")

            val first = receive(c, sc) as ProjectPinsState // sealed before the revoke — legitimately out
            assertEquals(1, first.snapshot?.pins?.size)
            assertNull(withTimeoutOrNull(500) { c.ws.sent.receive() }, "the queued push is re-checked and never sealed")
            withTimeout(5_000) { c.job.join() }
            assertTrue(c.failure?.message.orEmpty().contains("revoked"), "the revoked socket is closed: ${c.failure}")
            a.ws.hangUp()
        } finally {
            f.close()
        }
    }

    @Test
    fun a_plaintext_local_socket_can_neither_subscribe_nor_receive_pins() = runBlocking {
        val f = Fixture(this)
        try {
            val local = f.connect(gated = false)
            local.ws.inbound.send(WsFrame.Text(envelopeText(ClientCaps(supportsProjectPins = true))))
            local.ws.inbound.send(WsFrame.Text(envelopeText(SyncProjectPins("fetch", sub("local"), stream("local")))))
            val reply = assertNotNull(withTimeout(5_000) { local.ws.sent.receive() } as? WsFrame.Text)
            val state = PocketJson.decodeFromString<Envelope>(reply.data.decodeToString()).body as ProjectPinsState
            assertEquals(ProjectPinErrors.UNAVAILABLE, state.error)
            assertNull(state.snapshot)

            val keysA = f.device("devA")
            val a = f.connect(gated = true); val sa = f.handshake(a, "devA", keysA)
            subscribe(a, sa, "devA", sub("a"))
            assertEquals(1, f.pins.subscriberCount(), "only the gated socket holds a pin slot")
            commit(a, sa, "devA", sub("a"), 1, "/nonexistent-ccp/x")
            assertNull(withTimeoutOrNull(300) { local.ws.sent.receive() })
            listOf(local, a).forEach { it.ws.hangUp() }
        } finally {
            f.close()
        }
    }

    @Test
    fun a_hung_up_socket_leaves_no_pin_slot_behind() = runBlocking {
        val f = Fixture(this)
        try {
            val keysA = f.device("devA")
            val a = f.connect(gated = true); val sa = f.handshake(a, "devA", keysA)
            subscribe(a, sa, "devA", sub("a"))
            assertEquals(1, f.pins.subscriberCount())
            a.ws.hangUp()
            withTimeout(5_000) { a.job.join() }
            assertEquals(0, f.pins.subscriberCount())
        } finally {
            f.close()
        }
    }

    private companion object {
        const val INC = "inc-0123456789abcdef"
    }
}
