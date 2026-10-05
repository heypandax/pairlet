package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.DirectConnect
import dev.ccpocket.daemon.DirectConnectMode
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
import dev.ccpocket.protocol.DaemonInfo
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.websocket.Frame as WsFrame

/**
 * `config --direct-connect lan` widens who can REACH the direct listener (0.0.0.0), not who gets THROUGH
 * it. The gate ([LanE2E] + [WsConnection]) carries no bind address at all — the only thing a mode changes
 * on this path is the address advertised in [DaemonInfo.lanUrl]. Wired like the other Lan*Test fixtures
 * (in-memory socket, injected allow-list), so nothing binds a real port or reads the real devices.json:
 * a real 0.0.0.0 bind in a test would open a port on the developer's network and can trigger the macOS
 * firewall prompt.
 */
class LanDirectConnectGateTest {

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
        override fun replayHistory(workdir: String, sessionId: String) = emptyList<HistoryMessage>()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    private class Fixture(val scope: CoroutineScope, mode: DirectConnectMode) {
        private val tmp = Files.createTempDirectory("ccp-direct-connect").toFile()
        val identity: Identity = Identity.loadOrCreate(tmp.resolve("identity.json"))
        val device: E2ECrypto.KeyPair = E2ECrypto.generateKeyPair()
        val deviceId = "devPaired"

        /** Exactly what RunCmd hands the gate for this mode (a fixed fake LAN IP instead of the host's). */
        val advertised: String? = DirectConnect.advertisedUrl(
            DirectConnect.resolveBind(flag = null, pref = mode).bind, 8765,
        ) { "192.168.7.20" }

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

        fun connect(): Pair<FakeWsSession, kotlinx.coroutines.Job> {
            val ws = FakeWsSession(scope.coroutineContext)
            val gate = LanE2E(
                identity = identity,
                lanUrl = { advertised },
                pairedDevices = { mapOf(deviceId to device.publicRaw) },
            )
            return ws to scope.launch { WsConnection(ws, router, registry, e2e = gate).serve() }
        }
    }

    private fun hello(deviceId: String): WsFrame =
        WsFrame.Text(PocketJson.encodeToString(Envelope(id = "h", ts = 0, body = LanHello(deviceId))))

    /** A well-formed hello + Noise initiator message from a key that is NOT on the allow-list. */
    private fun assertUnpairedRefused(mode: DirectConnectMode) = runBlocking {
        val f = Fixture(this, mode)
        val (ws, job) = f.connect()
        val stranger = E2ECrypto.generateKeyPair()
        val initiator = E2ESession.initiator(stranger.privateRaw, stranger.publicRaw, f.identity.e2ePubRaw, ByteArray(0))
        ws.inbound.send(hello("devStranger"))
        ws.inbound.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, initiator.ephPublic)))
        withTimeout(5_000) { job.join() } // the gate closed the connection on its own
        assertNull(withTimeoutOrNull(200) { ws.sent.receive() }, "$mode: an unpaired device must be told nothing — no handshake reply, no DaemonInfo")
    }

    @Test
    fun an_unpaired_device_is_refused_at_the_handshake_in_lan_mode() = assertUnpairedRefused(DirectConnectMode.LAN)

    @Test
    fun an_unpaired_device_is_refused_the_same_way_in_local_mode() = assertUnpairedRefused(DirectConnectMode.LOCAL)

    @Test
    fun a_paired_device_in_lan_mode_is_told_the_lan_address() = runBlocking {
        val f = Fixture(this, DirectConnectMode.LAN)
        val (ws, job) = f.connect()
        val initiator = E2ESession.initiator(f.device.privateRaw, f.device.publicRaw, f.identity.e2ePubRaw, ByteArray(0))
        ws.inbound.send(hello(f.deviceId))
        ws.inbound.send(WsFrame.Binary(true, Wire.payload(Wire.HANDSHAKE, initiator.ephPublic)))

        val msg2 = assertNotNull(withTimeout(5_000) { ws.sent.receive() } as? WsFrame.Binary)
        assertEquals(Wire.HANDSHAKE, Wire.payloadType(msg2.data))
        val session = initiator.finish(Wire.payloadBody(msg2.data))
        val sealed = assertNotNull(withTimeout(5_000) { ws.sent.receive() } as? WsFrame.Binary)
        val plain = assertNotNull(session.open(Wire.payloadBody(sealed.data)))
        val info = assertNotNull(PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body as? DaemonInfo)

        val lanUrl = assertNotNull(info.lanUrl, "lan mode must advertise an address")
        assertEquals("ws://192.168.7.20:8765/v1/ws", lanUrl)
        assertFalse(InetAddress.getByName(URI(lanUrl).host).isLoopbackAddress, lanUrl)

        ws.hangUp()
        withTimeout(5_000) { job.join() }
    }

    @Test
    fun local_and_off_never_advertise_a_lan_address() = runBlocking {
        val local = assertNotNull(Fixture(this, DirectConnectMode.LOCAL).advertised)
        assertTrue(InetAddress.getByName(URI(local).host).isLoopbackAddress, local) // unchanged: the same-machine App's address
        assertNull(Fixture(this, DirectConnectMode.OFF).advertised)
    }
}
