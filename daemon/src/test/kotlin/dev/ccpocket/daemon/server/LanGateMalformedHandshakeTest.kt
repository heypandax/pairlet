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
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.LanHello
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
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
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import io.ktor.websocket.Frame as WsFrame

/**
 * The DIRECT (LAN) gate's behaviour on malformed handshake input — the relay path's twin of this
 * hardening landed in `fix(daemon): isolate malformed device handshakes`, and this transport was left
 * out of it.
 *
 * Two holes, both reachable by anything that can open a socket to the LAN listener, both BEFORE
 * authentication finishes:
 *  - a zero-byte BINARY frame has no type byte, so reading one indexes past the end;
 *  - the initiator ephemeral is attacker-chosen bytes, and a short / off-curve P-256 point makes the
 *    crypto provider throw.
 *
 * Neither is a memory-safety problem — it is about WHERE the exception lands. The gate runs inside the
 * connection's own `serve()`, so an escape turns "one peer sent junk" into a thrown coroutine instead
 * of a closed socket. What is asserted here is therefore not just "rejected" but "rejected QUIETLY":
 * `serve()` returns normally, and nothing is written back to a peer that never authenticated.
 */
class LanGateMalformedHandshakeTest {

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

    private class Fixture(val scope: CoroutineScope) {
        private val tmp = Files.createTempDirectory("ccp-lan-gate").toFile()

        /** A real daemon identity (temp file — never the developer's ~/.cc-pocket). */
        val identity: Identity = Identity.loadOrCreate(tmp.resolve("identity.json"))

        /** The one allow-listed device, with a genuine P-256 static key so the ONLY malformed input in
         *  the hostile cases below is the thing under test. */
        val device: E2ECrypto.KeyPair = E2ECrypto.generateKeyPair()
        val deviceId = "devAllowed"

        val registry = SessionRegistry(scope, backends = mapOf(AgentKind.CLAUDE to AgentBackendFactory { StubBackend() }))
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
        )

        /** One gated socket. [failure] is what the malformed-input tests actually read: caught HERE rather
         *  than left to blow up the enclosing scope, so a regression reports "the gate threw X" instead of
         *  an anonymous coroutine failure. */
        class Conn(
            val ws: FakeWsSession,
            val job: kotlinx.coroutines.Job,
            private val caught: java.util.concurrent.atomic.AtomicReference<Throwable?>,
        ) {
            val failure: Throwable? get() = caught.get()
        }

        /** Wired exactly as `DaemonServer` wires the direct listener — but with the allow-list injected,
         *  so the test never reads or writes the developer's real devices.json. */
        fun connect(): Conn {
            val ws = FakeWsSession(scope.coroutineContext)
            val gate = LanE2E(
                identity = identity,
                lanUrl = { "ws://127.0.0.1:8799" },
                pairedDevices = { mapOf(deviceId to device.publicRaw) },
            )
            val caught = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
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
    }

    private fun hello(deviceId: String): WsFrame =
        WsFrame.Text(PocketJson.encodeToString(Envelope(id = "h", ts = 0, body = LanHello(deviceId))))

    /** `serve()` finished on its own, without throwing — the whole point of the guards. */
    private suspend fun assertClosedQuietly(conn: Fixture.Conn) {
        withTimeout(5_000) { conn.job.join() }
        assertNull(conn.failure, "the gate must swallow malformed input, not throw: ${conn.failure}")
        assertNull(
            withTimeoutOrNull(200) { conn.ws.sent.receive() },
            "a peer that never completed the handshake must be told nothing",
        )
    }

    @Test
    fun an_empty_binary_frame_at_the_gate_is_refused_instead_of_indexing_past_the_end() = runBlocking {
        val conn = Fixture(this).connect()

        conn.ws.inbound.send(hello("devAllowed"))
        conn.ws.inbound.send(WsFrame.Binary(true, ByteArray(0))) // no type byte to read
        conn.ws.hangUp()

        assertClosedQuietly(conn)
    }

    @Test
    fun a_garbage_ephemeral_at_the_gate_is_refused_instead_of_escaping_as_a_crypto_exception() = runBlocking {
        val f = Fixture(this)
        val conn = f.connect()

        // well-formed framing, allow-listed device, hostile payload: five bytes where a 65-byte
        // uncompressed P-256 point belongs. The allow-list can't catch this — the static key is real;
        // it is the EPHEMERAL the peer controls.
        conn.ws.inbound.send(hello(f.deviceId))
        conn.ws.inbound.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, byteArrayOf(1, 2, 3, 4, 5))))
        conn.ws.hangUp()

        assertClosedQuietly(conn)
    }

    @Test
    fun an_on_curve_but_wrong_length_ephemeral_is_refused_too() = runBlocking {
        val f = Fixture(this)
        val conn = f.connect()

        // a real point with its last byte lopped off — decodes far enough to reach the provider, then throws
        val truncated = E2ECrypto.generateKeyPair().publicRaw.let { it.copyOfRange(0, it.size - 1) }
        conn.ws.inbound.send(hello(f.deviceId))
        conn.ws.inbound.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, truncated)))
        conn.ws.hangUp()

        assertClosedQuietly(conn)
    }

    /**
     * The guards must reject only what is malformed. Without this, "return null on anything odd" could
     * quietly become "return null on everything" and no other test in the suite would notice.
     */
    @Test
    fun a_well_formed_handshake_from_an_allow_listed_device_still_completes() = runBlocking {
        val f = Fixture(this)
        val conn = f.connect()

        val initiator = E2ESession.initiator(
            f.device.privateRaw, f.device.publicRaw, f.identity.e2ePubRaw, ByteArray(0),
        )
        conn.ws.inbound.send(hello(f.deviceId))
        conn.ws.inbound.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, initiator.ephPublic)))

        val msg2 = assertNotNull(
            withTimeout(5_000) { conn.ws.sent.receive() } as? WsFrame.Binary,
            "the daemon must answer a valid handshake with its responder ephemeral",
        )
        assertEquals(Wire.HANDSHAKE, Wire.payloadType(msg2.data))

        // and the session is real: the daemon's queued DaemonInfo decrypts under the derived keys
        val session = initiator.finish(Wire.payloadBody(msg2.data))
        val info = assertNotNull(withTimeout(5_000) { conn.ws.sent.receive() } as? WsFrame.Binary)
        assertEquals(Wire.TRANSPORT, Wire.payloadType(info.data))
        assertNotNull(session.open(Wire.payloadBody(info.data)), "the established session must decrypt")

        conn.ws.hangUp()
        withTimeout(5_000) { conn.job.join() }
        assertNull(conn.failure, "a healthy connection must end cleanly: ${conn.failure}")
    }
}
