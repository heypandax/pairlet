package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.handoff.HandoffRegistry
import dev.ccpocket.daemon.handoff.HandoffService
import dev.ccpocket.daemon.handoff.HandoffStore
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.review.ReviewRegistry
import dev.ccpocket.daemon.review.ReviewService
import dev.ccpocket.daemon.review.ReviewStore
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.HandoffUpdated
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.LanHello
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.ReviewUpdated
import dev.ccpocket.protocol.SessionHandoff
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.websocket.Frame as WsFrame

/**
 * The DIRECT (LAN / loopback) transport's owner fan-out (REVIEW-REQUEST.md §5.1).
 *
 * The desktop app on the daemon's own machine — and any phone on the same network — arrives HERE, not
 * over the relay. [dev.ccpocket.daemon.relay.DeviceSessions] attaches an owner's sink to BOTH the handoff
 * and the review services; this transport used to attach only the first. The symptom was quiet and
 * confusing rather than broken: commands answered normally, a colleague's response landed in the daemon's
 * ledger, and the Review Center simply never showed it until something forced a re-list.
 *
 * So what is under test is the SYMMETRY, in both directions: a live LAN owner sees `ReviewUpdated`
 * pushes, and the sink dies with its own socket — not with a sibling's.
 */
class LanOwnerFanOutTest {

    // ---- a WebSocketSession backed by two plain channels ------------------

    private class FakeWsSession(override val coroutineContext: CoroutineContext) : WebSocketSession {
        val inbound = Channel<WsFrame>(Channel.UNLIMITED)
        val sent = Channel<WsFrame>(Channel.UNLIMITED)

        override val incoming: ReceiveChannel<WsFrame> get() = inbound
        override val outgoing: SendChannel<WsFrame> get() = sent
        override val extensions: List<WebSocketExtension<*>> get() = emptyList()
        override var masking: Boolean = false
        override var maxFrameSize: Long = Long.MAX_VALUE

        override suspend fun send(frame: WsFrame) { sent.send(frame) }
        override suspend fun flush() {}

        @Deprecated("Use cancel() instead.", replaceWith = ReplaceWith("cancel()", "kotlinx.coroutines.cancel"))
        override fun terminate() { inbound.close() }

        /** The client hanging up: the read pump's `for (frame in incoming)` completes and `finally` runs. */
        fun hangUp() { inbound.close() }
    }

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

    /** One gated direct socket and the E2E session its paired device completed. */
    private class Conn(val ws: FakeWsSession, val job: Job, val session: E2ESession)

    private class Fixture(val scope: CoroutineScope) {
        private val tmp = Files.createTempDirectory("ccp-lan-fanout").toFile()
        val identity: Identity = Identity.loadOrCreate(tmp.resolve("identity.json"))
        /** The allow-list the gate consults per lookup — a fixture, never the developer's devices.json. */
        val allowed = ConcurrentHashMap<String, ByteArray>()
        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { StubBackend() }))
        val handoffs = HandoffService(HandoffRegistry(HandoffStore.load(tmp.resolve("handoffs.json"))))
        val reviews = ReviewService(ReviewRegistry(ReviewStore.load(tmp.resolve("reviews.json"))))
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
            reviews = reviews,
        )

        init { registry.handoffs = handoffs }
    }

    /** One direct socket, served exactly as [DaemonServer] serves it: a freshly paired device opens it
     *  with LanHello + the Noise handshake, and the DaemonInfo the gate queues is consumed here, so the
     *  test starts from the same pump with nothing pending. */
    private suspend fun Fixture.connect(id: String): Conn {
        val keys = E2ECrypto.generateKeyPair().also { allowed[id] = it.publicRaw }
        val ws = FakeWsSession(scope.coroutineContext)
        val gate = LanE2E(identity = identity, lanUrl = { null }, pairedDevices = { HashMap(allowed) })
        val job = scope.launch {
            WsConnection(ws, router, registry, e2e = gate, ownerControls = null, reviews = reviews).serve()
        }
        val initiator = E2ESession.initiator(keys.privateRaw, keys.publicRaw, identity.e2ePubRaw, ByteArray(0))
        ws.inbound.send(WsFrame.Text(PocketJson.encodeToString(Envelope("c", 0, body = LanHello(id)))))
        ws.inbound.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, initiator.ephPublic)))
        val reply = assertNotNull(withTimeout(5_000) { ws.sent.receive() } as? WsFrame.Binary)
        assertEquals(Wire.HANDSHAKE, Wire.payloadType(reply.data))
        val conn = Conn(ws, job, initiator.finish(Wire.payloadBody(reply.data)))
        assertTrue(bodyOf(conn, withTimeout(5_000) { ws.sent.receive() }) is DaemonInfo, "the gate's DaemonInfo comes first")
        return conn
    }

    private fun bodyOf(conn: Conn, frame: WsFrame): dev.ccpocket.protocol.Frame? =
        (frame as? WsFrame.Binary)?.takeIf { it.data.isNotEmpty() && Wire.payloadType(it.data) == Wire.TRANSPORT }?.let {
            val plain = conn.session.open(Wire.payloadBody(it.data)) ?: return null
            runCatching { PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body }.getOrNull()
        }

    /** Broadcast until it lands, so the assertion does not race the connection's attach. Each retry is
     *  the same row, and fan-out is idempotent — what is being waited for is the sink, not the state. */
    private suspend fun awaitPush(conn: Conn, push: suspend () -> Unit): dev.ccpocket.protocol.Frame =
        withTimeout(10_000) {
            while (true) {
                push()
                val f = withTimeoutOrNull(50) { conn.ws.sent.receive() }
                val body = f?.let { bodyOf(conn, it) }
                if (body != null) return@withTimeout body
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    private fun review(id: String, status: ReviewStatus) = ReviewRequest(
        id = id, senderDeviceId = "devA", recipientDeviceId = "devB",
        title = "the LAN push", status = status, revision = 1, createdAt = 1, updatedAt = 1,
    )

    private fun handoff(id: String) = SessionHandoff(
        id = id, sourceSessionId = "sess-1", workdir = "/tmp/wd",
        initiatorDeviceId = "devA", status = HandoffStatus.WAITING,
    )

    @Test
    fun a_lan_owner_receives_live_review_updates_and_stops_the_moment_its_socket_dies() = runBlocking {
        val f = Fixture(this)
        val conn = f.connect("devA")
        val ws = conn.ws

        // 1. live: the push arrives on this socket without the client asking for anything
        val got = awaitPush(conn) { f.reviews.broadcast(listOf(review("rq-1", ReviewStatus.RESPONDED))) }
        val updated = assertNotNull(got as? ReviewUpdated, "a LAN owner must see ReviewUpdated: $got")
        assertEquals("rq-1", updated.request.id)
        assertEquals(ReviewStatus.RESPONDED, updated.request.status)

        // 2. …and the handoff fan-out this sits beside is untouched
        val handoffPush = awaitPush(conn) { f.handoffs.broadcast(listOf(handoff("h-1"))) }
        assertEquals("h-1", assertNotNull(handoffPush as? HandoffUpdated).handoff.id)

        // 3. the socket dies -> the sink goes with it, on BOTH services
        ws.hangUp()
        conn.job.join()
        while (withTimeoutOrNull(20) { ws.sent.receive() } != null) Unit // drain anything already queued

        f.reviews.broadcast(listOf(review("rq-2", ReviewStatus.CLOSED)))
        f.handoffs.broadcast(listOf(handoff("h-2")))
        assertNull(
            withTimeoutOrNull(200) { ws.sent.receive() },
            "a detached connection must receive nothing — a leaked sink is a dead socket held forever",
        )
    }

    /**
     * The detach is per-CONNECTION. A shared key (or a blanket clear) would make one phone walking out of
     * WiFi silently stop the desktop app's live updates — the exact failure the fix is meant to remove,
     * moved one seat over.
     */
    @Test
    fun one_connections_disconnect_does_not_detach_another() = runBlocking {
        val f = Fixture(this)
        val firstConn = f.connect("devA")
        val secondConn = f.connect("devB")
        val first = firstConn.ws
        val second = secondConn.ws

        // both live
        awaitPush(firstConn) { f.reviews.broadcast(listOf(review("rq-1", ReviewStatus.DELIVERED))) }
        awaitPush(secondConn) { f.reviews.broadcast(listOf(review("rq-1", ReviewStatus.DELIVERED))) }

        first.hangUp()
        firstConn.job.join()
        // both sockets saw the warm-up broadcasts above — drain them, so what follows is only new traffic
        while (withTimeoutOrNull(20) { second.sent.receive() } != null) Unit
        while (withTimeoutOrNull(20) { first.sent.receive() } != null) Unit

        val stillLive = awaitPush(secondConn) { f.reviews.broadcast(listOf(review("rq-3", ReviewStatus.RESPONDED))) }
        assertEquals("rq-3", assertNotNull(stillLive as? ReviewUpdated).request.id)

        assertNull(
            withTimeoutOrNull(200) { first.sent.receive() },
            "the connection that hung up stays detached",
        )
        second.hangUp()
    }
}
