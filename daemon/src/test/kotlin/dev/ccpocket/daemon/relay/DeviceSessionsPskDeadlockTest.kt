package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionGone
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.e2e.E2ESession
import dev.ccpocket.protocol.e2e.Wire
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #161 — the first-contact PSK deadlock. The phone consumes its pairing ticket on its first
 * connect ATTEMPT; the daemon only releases its armed copy on the first successful DECRYPT. Any
 * interruption in between (supersede kick, fleet cross-kick, network blink) used to key the two ends
 * apart forever: every retry handshook "psk 43B" on the daemon and empty on the phone, every frame
 * failed to decrypt, and only a daemon restart cleared it — re-pairing did not.
 *
 * The fix: for a device already in the FULL-POWER allow-list, a PSK-armed handshake also derives an
 * empty-PSK twin off the same responder ephemeral; whichever session the first inbound frame decrypts
 * under wins. These tests drive real Noise handshakes from the device side and assert the recovery,
 * the unchanged ticket-bound path, and that provisional bridge candidates still fail closed.
 */
class DeviceSessionsPskDeadlockTest {

    private val dir = createTempDirectory("ccp-ds-psk").toFile()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private class Harness(dir: File) {
        val identity = Identity.loadOrCreate(File(dir, "identity.json"))
        val bridges = BridgeRegistry(File(dir, "bridges.json"))
        val outbound = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)
        val sessions = DeviceSessions(
            core = DaemonCore(emptyMap()),
            identity = identity,
            store = File(dir, "devices.json"),
            bridges = bridges,
        ) { deviceId, payload -> outbound.trySend(deviceId to payload) }
    }

    /** Initiator (device) side of one handshake: send msg1, finish on the daemon's msg2. Leaves any
     *  post-handshake DaemonInfo in the outbound channel for the caller to assert on. */
    private suspend fun handshakeOnly(h: Harness, deviceId: String, keys: E2ECrypto.KeyPair, psk: String?): E2ESession {
        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, h.identity.e2ePubRaw, psk = (psk ?: "").encodeToByteArray())
        h.sessions.onFrame(deviceId, Wire.payload(Wire.HANDSHAKE, init.ephPublic))
        val (_, resp) = h.outbound.receive()
        assertEquals(Wire.HANDSHAKE, Wire.payloadType(resp))
        return init.finish(Wire.payloadBody(resp))
    }

    private inline fun <reified T> decode(session: E2ESession, framed: ByteArray): T {
        assertEquals(Wire.TRANSPORT, Wire.payloadType(framed))
        val plain = session.open(Wire.payloadBody(framed))
            ?: throw AssertionError("frame did not decrypt under the expected session")
        return PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body as T
    }

    private suspend fun send(h: Harness, deviceId: String, session: E2ESession, body: Frame) {
        val env = Envelope("0", 0L, body = body)
        h.sessions.onFrame(deviceId, Wire.payload(Wire.TRANSPORT, session.seal(PocketJson.encodeToString(env).encodeToByteArray())))
    }

    /** A prompt into an unknown convo answers SessionGone — proves decrypt + routing + reply sealing. */
    private suspend fun roundTrip(h: Harness, deviceId: String, session: E2ESession, convo: String) {
        send(h, deviceId, session, SendPrompt(convo, "hi"))
        assertEquals(convo, decode<SessionGone>(session, h.outbound.receive().second).convoId)
    }

    private suspend fun pairedDevice(h: Harness, deviceId: String, ticket: String): E2ECrypto.KeyPair {
        h.sessions.onMintedTicket(ticket)
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired(deviceId, b64.encodeToString(keys.publicRaw))
        return keys
    }

    @Test
    fun a_device_that_burned_its_ticket_on_an_interrupted_first_attempt_recovers_on_reconnect() = runBlocking {
        val h = Harness(dir)
        val keys = pairedDevice(h, "devE", "ticket-e")

        // ATTEMPT 1 (interrupted): the handshake lands, the phone burns its ticket — but its first
        // transport frame never arrives (supersede kick / network blink killed the socket).
        handshakeOnly(h, "devE", keys, "ticket-e")
        h.outbound.receive() // its DaemonInfo — sealed toward the now-dead attempt, never read
        assertTrue(h.sessions.firstContactPending("devE"), "the armed PSK must survive the dead attempt")

        // ATTEMPT 2: the ticket is gone, the phone handshakes with an EMPTY PSK. Before #161 this was
        // the permanent deadlock: the daemon kept handshaking with the armed PSK, no frame in either
        // direction ever decrypted again, and re-pairing couldn't help.
        val phone = handshakeOnly(h, "devE", keys, null)

        // the post-handshake DaemonInfo still seals under the ticket-bound session — the one frame this
        // recovery path concedes (below the phone's ≥2-consecutive-failures deaf threshold)
        val blind = h.outbound.receive().second
        assertNull(phone.open(Wire.payloadBody(blind)), "pre-recovery DaemonInfo is sealed under the ticket-bound session")

        // the phone's first frame decrypts under the empty-PSK twin: promoted, PSK abandoned, and the
        // daemon re-sends DaemonInfo under the proven session before answering the frame itself
        send(h, "devE", phone, SendPrompt("ghost-1", "hi"))
        assertTrue(decode<Frame>(phone, h.outbound.receive().second) is DaemonInfo, "DaemonInfo must be re-sent under the recovered session")
        assertEquals("ghost-1", decode<SessionGone>(phone, h.outbound.receive().second).convoId)
        assertFalse(h.sessions.firstContactPending("devE"), "first contact completed — the LAN gate opens")

        // and the recovered session is the stable one: further round-trips, no stray frames
        roundTrip(h, "devE", phone, "ghost-2")
        assertTrue(h.outbound.tryReceive().isFailure)
    }

    @Test
    fun a_first_attempt_that_died_before_reaching_the_daemon_recovers_the_same_way() = runBlocking {
        val h = Harness(dir)
        val keys = pairedDevice(h, "devF", "ticket-f")

        // the phone's first attempt died before its handshake ever reached the daemon — the FIRST
        // handshake the daemon sees is already ticket-less (fresh DeviceLink, twin from birth)
        val phone = handshakeOnly(h, "devF", keys, null)
        assertNull(phone.open(Wire.payloadBody(h.outbound.receive().second))) // the conceded DaemonInfo

        send(h, "devF", phone, SendPrompt("ghost-1", "hi"))
        assertTrue(decode<Frame>(phone, h.outbound.receive().second) is DaemonInfo)
        assertEquals("ghost-1", decode<SessionGone>(phone, h.outbound.receive().second).convoId)
        assertFalse(h.sessions.firstContactPending("devF"))
    }

    @Test
    fun the_ticket_bound_path_is_unchanged_when_the_device_still_holds_its_ticket() = runBlocking {
        val h = Harness(dir)
        val keys = pairedDevice(h, "devG", "ticket-g")

        val phone = handshakeOnly(h, "devG", keys, "ticket-g")
        // the twin must NOT steal outbound: the handshake DaemonInfo seals under the ticket session
        assertTrue(decode<Frame>(phone, h.outbound.receive().second) is DaemonInfo)
        roundTrip(h, "devG", phone, "ghost-1")
        assertFalse(h.sessions.firstContactPending("devG"))
        // and no spurious twin-promotion side effects (in particular no duplicate DaemonInfo re-send)
        roundTrip(h, "devG", phone, "ghost-2")
        assertTrue(h.outbound.tryReceive().isFailure)
    }

    @Test
    fun a_provisional_bridge_candidate_without_its_headless_ticket_stays_locked_out() = runBlocking {
        // fail closed (#91/#115 unchanged): a provisional bridge/guest candidate's classification IS the
        // ticket-PSK proof — it gets no empty-PSK twin, so losing the headless ticket means staying out
        // (the owner re-mints), never a silent fallback into any session, let alone a full-power one.
        val h = Harness(dir)
        val ticket = "bridge-ticket-x"
        h.bridges.recordIntent(ticket, BridgeSpec("feishu-bot", listOf(dir.canonicalFile.path)), ttlMs = 120_000)
        h.sessions.onMintedTicket(ticket, headless = true)
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired("devH", b64.encodeToString(keys.publicRaw))

        val adapter = handshakeOnly(h, "devH", keys, null) // ticket lost — handshakes empty
        send(h, "devH", adapter, SendPrompt("ghost-1", "hi"))

        assertTrue(h.outbound.tryReceive().isFailure, "no frame may route or seal for a ticket-less candidate")
        assertFalse(h.bridges.isBridge("devH"), "the bridge classification must never finalize without its ticket")
        assertTrue(h.sessions.firstContactPending("devH"), "the armed PSK stays — no abandon path for candidates")
    }
}
