package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.pins.MemoryProjectPinStore
import dev.ccpocket.daemon.pins.PinStoreState
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The relay leg hands every paired device's frames over ONE reader, one at a time. A project listing reads
 * transcripts from disk, and produced inline it held every other frame from every device behind it. End to end
 * through real Noise sessions: a listing whose scan is stuck returns the reader at once, another device is still
 * answered, and the same device's next listing waits its turn instead of overtaking.
 */
class DeviceSessionsListingLaneTest {

    private val dir = createTempDirectory("ccp-ds-lane").toFile()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** Its scan of a directory named `slow` hangs until [release] — a cold read of a large project. */
    private class GatedBackend(private val release: CountDownLatch) : AgentBackend {
        override val kind = AgentKind.CLAUDE
        override fun listSessions(workdir: String): List<SessionSummary> {
            if (workdir.endsWith("slow")) release.await(SCAN_HOLD_MS, TimeUnit.MILLISECONDS)
            return emptyList()
        }
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

    private inner class Harness {
        val release = CountDownLatch(1)
        val slow: String = File(dir, "slow").apply { mkdirs() }.canonicalPath
        val fast: String = File(dir, "fast").apply { mkdirs() }.canonicalPath
        val identity = Identity.loadOrCreate(File(dir, "identity.json"))
        val core = DaemonCore(
            mapOf(AgentKind.CLAUDE to AgentBackendFactory { GatedBackend(release) }),
            projectPinStore = MemoryProjectPinStore(PinStoreState(incarnation = "inc-0123456789abcdef")),
            managedSessionRoot = File(dir, "managed"),
        )
        private val outbound = ConcurrentHashMap<String, Channel<ByteArray>>()
        fun channel(deviceId: String): Channel<ByteArray> = outbound.computeIfAbsent(deviceId) { Channel(Channel.UNLIMITED) }
        val sessions = DeviceSessions(
            core = core, identity = identity, store = File(dir, "devices.json"), bridges = BridgeRegistry(File(dir, "bridges.json")),
        ) { deviceId, payload -> channel(deviceId).trySend(payload) }
    }

    private fun open(session: E2ESession, framed: ByteArray): Frame? {
        if (Wire.payloadType(framed) != Wire.TRANSPORT) return null
        val plain = session.open(Wire.payloadBody(framed)) ?: return null
        return PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body
    }

    /** An owner device, paired and handshaken, its first transport frame already sent. */
    private suspend fun owner(h: Harness, deviceId: String): E2ESession {
        h.sessions.onMintedTicket("ticket-$deviceId")
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired(deviceId, b64.encodeToString(keys.publicRaw))
        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, h.identity.e2ePubRaw, psk = "ticket-$deviceId".encodeToByteArray())
        h.sessions.onFrame(deviceId, Wire.payload(Wire.HANDSHAKE, init.ephPublic))
        val resp = withTimeout(5_000) { h.channel(deviceId).receive() }
        assertEquals(Wire.HANDSHAKE, Wire.payloadType(resp))
        val session = init.finish(Wire.payloadBody(resp))
        assertNotNull(open(session, withTimeout(5_000) { h.channel(deviceId).receive() }) as? DaemonInfo)
        send(h, deviceId, session, ClientCaps())
        return session
    }

    private suspend fun send(h: Harness, deviceId: String, session: E2ESession, body: Frame) {
        val env = Envelope("0", 0L, body = body)
        h.sessions.onFrame(deviceId, Wire.payload(Wire.TRANSPORT, session.seal(PocketJson.encodeToString(env).encodeToByteArray())))
    }

    /** Everything [deviceId] is sent until the line stays quiet for [ms]. */
    private suspend fun drain(h: Harness, deviceId: String, session: E2ESession, ms: Long = 300): List<Frame> {
        val out = ArrayList<Frame>()
        while (true) {
            val next = withTimeoutOrNull(ms) { h.channel(deviceId).receive() } ?: return out
            open(session, next)?.let { out += it }
        }
    }

    /** The next session list sent to [deviceId]; any other frame on the way is skipped. */
    private suspend fun nextSessions(h: Harness, deviceId: String, session: E2ESession): Sessions = withTimeout(5_000) {
        var found: Sessions? = null
        while (found == null) found = open(session, h.channel(deviceId).receive()) as? Sessions
        found
    }

    @Test
    fun a_stuck_listing_frees_the_reader_and_other_devices_and_keeps_its_own_order() = runBlocking {
        val h = Harness()
        try {
            val a = owner(h, "devA")
            val b = owner(h, "devB")

            // onFrame is the reader's hand-over: produced inline, this call would return only when the scan did
            val handedOver = measureTimeMillis {
                send(h, "devA", a, ListSessions(h.slow))
                send(h, "devA", a, ListSessions(h.fast)) // waits behind A's own slow listing
                send(h, "devB", b, ListSessions(h.fast))
            }
            assertTrue(handedOver < SCAN_HOLD_MS / 2, "the reader got its loop back while the scan was stuck (${handedOver}ms)")

            assertEquals(h.fast, nextSessions(h, "devB", b).workdir, "another device is answered meanwhile")
            assertTrue(
                drain(h, "devA", a).none { it is Sessions },
                "A's second listing does not overtake the first: the client would be left on the older snapshot",
            )

            h.release.countDown()
            assertEquals(h.slow, nextSessions(h, "devA", a).workdir)
            assertEquals(h.fast, nextSessions(h, "devA", a).workdir)
        } finally {
            h.release.countDown()
        }
    }

    private companion object {
        /** How long the stuck scan holds if nothing releases it — far above anything the assertions wait for. */
        const val SCAN_HOLD_MS = 20_000L
    }
}
