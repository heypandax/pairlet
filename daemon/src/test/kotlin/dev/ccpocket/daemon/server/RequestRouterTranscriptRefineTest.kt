package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.bridge.BridgeCaps
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.execution.ExecutionCaps
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.FakeTranscriptRefiner
import dev.ccpocket.daemon.transcribe.REFINE_CORRECTED
import dev.ccpocket.daemon.transcribe.REFINE_EDITS
import dev.ccpocket.daemon.transcribe.REFINE_TEXT
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.daemon.transcribe.TranscriptRefineService
import dev.ccpocket.daemon.transcribe.TranscriptRefiners
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.LanHello
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.TranscriptRefine
import dev.ccpocket.protocol.TranscriptRefined
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.websocket.Frame as WsFrame

/**
 * The router's door for voice input v2's refine: an OWNER connection that declared it can read the answer gets one
 * [TranscriptRefined] back for its [TranscriptRefine] — and nobody else gets anything, nor starts a model. A
 * cancelled capture is dropped in silence. The LAN case runs the whole path through a real Noise-gated socket,
 * from the handshake's [DaemonInfo] advertisement to the sealed answer. No CLI is started anywhere.
 */
class RequestRouterTranscriptRefineTest {

    private val tmp = Files.createTempDirectory("ccp-router-refine").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refiner = FakeTranscriptRefiner()
    // no conversation is live in this registry: the refine falls back to the phone's agent hint
    private val registry = SessionRegistry(scope, backends = emptyMap())
    private val service = TranscriptRefineService(
        scope, TranscriptRefiners(listOf(refiner)), agentOf = registry::agentOf, glossaryOf = { emptyList() },
    )

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tmp.deleteRecursively()
    }

    private fun router(refine: TranscriptRefineService? = service) = RequestRouter(
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
        transcriptRefine = refine,
    )

    /** One connection: its sink drops what its own declared caps do not allow, like both transports do. */
    private class Conn(val caps: RequestRouter.ClientCapsHolder? = RequestRouter.ClientCapsHolder()) : OutboundSink {
        val frames = mutableListOf<Frame>()
        override suspend fun emit(frame: Frame) {
            if (!RequestRouter.allowedForCaps(frame, caps)) return
            synchronized(frames) { frames += frame }
        }
        fun refined() = synchronized(frames) { frames.filterIsInstance<TranscriptRefined>() }
        suspend fun awaitRefined(): TranscriptRefined = withTimeout(5_000) {
            while (true) {
                refined().firstOrNull()?.let { return@withTimeout it }
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    private suspend fun declared(router: RequestRouter): Conn {
        val conn = Conn()
        router.handle(ClientCaps(supportsTranscriptRefine = true), conn, caps = conn.caps, deviceId = "dev-a")
        return conn
    }

    private fun request(capture: String = "cap-1") = TranscriptRefine("c-1", capture, REFINE_TEXT, locale = "zh-Hans", agentHint = "claude")

    @Test
    fun a_declared_owner_connection_gets_its_checked_refine_back(): Unit = runBlocking {
        val router = router()
        val conn = declared(router)
        val other = declared(router)
        router.handle(request(), conn, caps = conn.caps, deviceId = "dev-a")
        val r = conn.awaitRefined()
        assertTrue(r.ok)
        assertEquals(REFINE_CORRECTED, r.text)
        assertEquals(REFINE_EDITS, r.edits)
        assertEquals("claude", r.agent)
        assertEquals("cap-1", r.captureId)
        assertTrue(other.frames.isEmpty(), "the answer goes to the asking connection only")
        assertEquals("zh-Hans", refiner.lastLocale)
    }

    @Test
    fun an_undeclared_connection_starts_nothing_and_hears_nothing(): Unit = runBlocking {
        val router = router()
        val undeclared = Conn()
        router.handle(request(), undeclared, caps = undeclared.caps)
        val legacy = Conn(caps = null) // a legacy ingress passes no holder at all
        router.handle(request(capture = "cap-2"), legacy, caps = null)
        delay(200)
        assertEquals(0, refiner.calls.get(), "no model runs for a connection that could not read the answer")
        assertTrue(undeclared.frames.isEmpty() && legacy.frames.isEmpty())
    }

    @Test
    fun a_restricted_credential_starts_nothing(): Unit = runBlocking {
        val router = router()
        val conn = declared(router)
        router.handle(request(), conn, origin = "bridge-cred", caps = conn.caps)
        delay(200)
        assertEquals(0, refiner.calls.get())
        assertTrue(conn.frames.isEmpty())
        // and the transports refuse the type before it gets here: both restricted whitelists default-deny it
        assertFalse(BridgeCaps.ingressAllowed(request()))
        assertFalse(ExecutionCaps.ingressAllowed(request()))
        assertFalse(BridgeCaps.egressAllowed(TranscriptRefined("c-1", "cap-1", ok = true)))
    }

    @Test
    fun audio_cancel_drops_the_running_refine_without_an_answer(): Unit = runBlocking {
        refiner.behavior = { awaitCancellation() }
        val router = router()
        val conn = declared(router)
        router.handle(request(), conn, caps = conn.caps, deviceId = "dev-a")
        withTimeout(5_000) { while (refiner.calls.get() == 0) delay(10) }
        router.handle(AudioCancel("c-1", "cap-1"), conn, caps = conn.caps, deviceId = "dev-a")
        withTimeout(5_000) { while (refiner.cancelled.get() == 0) delay(10) }
        assertFalse(service.isRefining())
        delay(200)
        assertTrue(conn.frames.isEmpty(), "a cancelled capture is never answered")
    }

    @Test
    fun the_answer_is_gated_on_the_connections_own_declaration() {
        val reply = TranscriptRefined("c-1", "cap-1", ok = true, text = "x")
        assertTrue(RequestRouter.allowedForCaps(reply, RequestRouter.ClientCapsHolder().apply { supportsTranscriptRefine = true }))
        assertFalse(RequestRouter.allowedForCaps(reply, RequestRouter.ClientCapsHolder()))
        assertFalse(RequestRouter.allowedForCaps(reply, null))
    }

    @Test
    fun the_advertisement_follows_what_can_launch_here() {
        assertEquals(listOf("claude"), router().transcriptRefineAgentWires())
        refiner.available = false
        assertTrue(router().transcriptRefineAgentWires().isEmpty())
        assertTrue(router(refine = null).transcriptRefineAgentWires().isEmpty(), "not wired: nothing is advertised")
    }

    @Test
    fun an_unwired_router_drops_the_request(): Unit = runBlocking {
        val router = router(refine = null)
        val conn = declared(router)
        router.handle(request(), conn, caps = conn.caps, deviceId = "dev-a")
        delay(200)
        assertTrue(conn.frames.isEmpty())
    }

    // ── the whole path over the direct (LAN) transport ─────────────────────────────────────────

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
    }

    private fun envelopeText(body: Frame) = PocketJson.encodeToString(Envelope("c", 0, body = body))

    private suspend fun FakeWsSession.sendSealed(session: E2ESession, body: Frame) {
        inbound.send(WsFrame.Binary(true, Wire.payload(Wire.TRANSPORT, session.seal(envelopeText(body).encodeToByteArray()))))
    }

    private suspend fun FakeWsSession.receiveSealed(session: E2ESession, timeoutMs: Long = 5_000): Frame? {
        val frame = withTimeoutOrNull(timeoutMs) { sent.receive() } as? WsFrame.Binary ?: return null
        val plain = assertNotNull(session.open(Wire.payloadBody(frame.data)))
        return PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body
    }

    @Test
    fun over_a_lan_socket_the_handshake_advertises_the_refiner_and_the_refine_comes_back_sealed(): Unit = runBlocking {
        val identity = Identity.loadOrCreate(tmp.resolve("identity.json"))
        val keys = E2ECrypto.generateKeyPair()
        val allowed = ConcurrentHashMap<String, ByteArray>().apply { put("devA", keys.publicRaw) }
        val router = router()
        val ws = FakeWsSession(coroutineContext)
        val gate = LanE2E(identity = identity, lanUrl = { null }, pairedDevices = { HashMap(allowed) })
        val serving = launch { WsConnection(ws, router, registry, e2e = gate).serve() }
        try {
            val initiator = E2ESession.initiator(keys.privateRaw, keys.publicRaw, identity.e2ePubRaw, ByteArray(0))
            ws.inbound.send(WsFrame.Text(envelopeText(LanHello("devA"))))
            ws.inbound.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, initiator.ephPublic)))
            val reply = assertNotNull(withTimeout(5_000) { ws.sent.receive() } as? WsFrame.Binary)
            assertEquals(Wire.HANDSHAKE, Wire.payloadType(reply.data))
            val session = initiator.finish(Wire.payloadBody(reply.data))

            val info = ws.receiveSealed(session) as DaemonInfo
            assertEquals(listOf("claude"), info.transcriptRefineAgents)

            // before the declaration: dropped in silence, no model started
            ws.sendSealed(session, request(capture = "early"))
            assertNull(ws.receiveSealed(session, timeoutMs = 300))
            assertEquals(0, refiner.calls.get())

            ws.sendSealed(session, ClientCaps(supportsTranscriptRefine = true))
            ws.sendSealed(session, request())
            val refined = ws.receiveSealed(session) as TranscriptRefined
            assertTrue(refined.ok)
            assertEquals("cap-1", refined.captureId)
            assertEquals(REFINE_CORRECTED, refined.text)
            assertEquals("claude", refined.agent)
        } finally {
            serving.cancel()
        }
    }

    @Test
    fun over_a_lan_socket_a_cancel_sent_right_behind_its_refine_still_finds_it(): Unit = runBlocking {
        // the refine is registered in receive order, so the AudioCancel read after it cannot miss it — a miss would
        // leave this never-answering refiner running for nobody
        refiner.behavior = { awaitCancellation() }
        val identity = Identity.loadOrCreate(tmp.resolve("identity.json"))
        val keys = E2ECrypto.generateKeyPair()
        val allowed = ConcurrentHashMap<String, ByteArray>().apply { put("devA", keys.publicRaw) }
        val ws = FakeWsSession(coroutineContext)
        val gate = LanE2E(identity = identity, lanUrl = { null }, pairedDevices = { HashMap(allowed) })
        val serving = launch { WsConnection(ws, router(), registry, e2e = gate).serve() }
        try {
            val initiator = E2ESession.initiator(keys.privateRaw, keys.publicRaw, identity.e2ePubRaw, ByteArray(0))
            ws.inbound.send(WsFrame.Text(envelopeText(LanHello("devA"))))
            ws.inbound.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, initiator.ephPublic)))
            val reply = assertNotNull(withTimeout(5_000) { ws.sent.receive() } as? WsFrame.Binary)
            val session = initiator.finish(Wire.payloadBody(reply.data))
            assertIsInfo(ws.receiveSealed(session))

            ws.sendSealed(session, ClientCaps(supportsTranscriptRefine = true))
            ws.sendSealed(session, request())
            ws.sendSealed(session, AudioCancel("c-1", "cap-1"))
            withTimeout(5_000) { while (service.isRefining()) delay(10) }
            assertNull(ws.receiveSealed(session, timeoutMs = 300), "a cancelled capture is never answered")
        } finally {
            serving.cancel()
        }
    }

    private fun assertIsInfo(frame: Frame?) = assertTrue(frame is DaemonInfo, "expected DaemonInfo, got $frame")
}
