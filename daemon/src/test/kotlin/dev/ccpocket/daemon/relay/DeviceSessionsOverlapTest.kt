package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.bridge.BridgeRegistry
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
import kotlin.test.assertTrue

/**
 * Issue #146 — the "僵会话" (zombie session) deafness loop, plus the #145 enabler. The daemon used to
 * keep exactly ONE E2E session per device and wholesale-overwrite it on every handshake; two failure
 * modes fell out of that:
 *  1. reconnect overlap: the relay supersede-kicks the older of two same-device sockets, but the dying
 *     socket's LATE handshake could land after the surviving socket's — the surviving (live) socket was
 *     then holding a session the daemon no longer knew, every frame it sent was dropped, the phone's 6s
 *     list timeout forced yet another relaunch, and the loop fed itself;
 *  2. the daemon's own relay blip cleared ALL device sessions, so even a phone whose socket stayed
 *     perfectly healthy went deaf and had to tear down + re-handshake (the #145 cascade's fuel).
 * These tests drive real Noise handshakes from the device side and assert the DeviceLink semantics:
 * trial-decrypt active→fallback with promote-on-proof, at most two live sessions, and survival across
 * the daemon's own relay disconnects.
 */
class DeviceSessionsOverlapTest {

    private val dir = createTempDirectory("ccp-ds-overlap").toFile()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private class Harness(dir: File) {
        val identity = Identity.loadOrCreate(File(dir, "identity.json"))
        val outbound = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)
        val sessions = DeviceSessions(
            core = DaemonCore(emptyMap()),
            identity = identity,
            store = File(dir, "devices.json"),
            bridges = BridgeRegistry(File(dir, "bridges.json")),
        ) { deviceId, payload -> outbound.trySend(deviceId to payload) }
    }

    /** Run the initiator (device) side of one handshake and return its live session. Consumes the
     *  daemon's HANDSHAKE reply AND the DaemonInfo that follows for a full-power device — asserting the
     *  DaemonInfo is sealed under the NEW session (the newest handshake owns outbound until an inbound
     *  frame proves otherwise). [psk] is the pairing ticket on first contact, null afterwards. */
    private suspend fun handshake(h: Harness, deviceId: String, keys: E2ECrypto.KeyPair, psk: String?): E2ESession {
        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, h.identity.e2ePubRaw, psk = (psk ?: "").encodeToByteArray())
        h.sessions.onFrame(deviceId, Wire.payload(Wire.HANDSHAKE, init.ephPublic))
        val (_, resp) = h.outbound.receive()
        assertEquals(Wire.HANDSHAKE, Wire.payloadType(resp))
        val session = init.finish(Wire.payloadBody(resp))
        assertTrue(decode<Frame>(session, h.outbound.receive().second) is DaemonInfo, "handshake DaemonInfo must seal under the fresh session")
        return session
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

    /** A prompt into an unknown convo makes the router answer SessionGone — a cheap, deterministic
     *  round-trip proving the frame decrypted, routed, AND that the reply sealed under [session]. */
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
    fun a_late_handshake_from_a_dying_socket_must_not_deafen_the_live_one() = runBlocking {
        val h = Harness(dir)
        val keys = pairedDevice(h, "devA", "ticket-a")
        val live = handshake(h, "devA", keys, "ticket-a")
        roundTrip(h, "devA", live, "ghost-1") // first contact confirmed (PSK consumed)

        // the superseded socket's handshake lands LAST — before #146 this wholesale-overwrote the live
        // session and every further frame from the live socket hit "transport before handshake"
        handshake(h, "devA", keys, null)

        // the live socket keeps talking on its own session: the daemon must still hear it AND answer
        // along the same (promoted-back) session — twice, to prove the promotion sticks for outbound
        roundTrip(h, "devA", live, "ghost-2")
        roundTrip(h, "devA", live, "ghost-3")
        assertTrue(h.outbound.tryReceive().isFailure, "no stray frames sealed toward the dead session")
    }

    @Test
    fun sessions_survive_the_daemons_own_relay_reconnect() = runBlocking {
        val h = Harness(dir)
        val keys = pairedDevice(h, "devB", "ticket-b")
        val session = handshake(h, "devB", keys, "ticket-b")
        roundTrip(h, "devB", session, "ghost-1")

        h.sessions.onDisconnect() // the daemon's relay leg blipped — device sessions must NOT die with it (#145)

        roundTrip(h, "devB", session, "ghost-2")
    }

    @Test
    fun at_most_the_two_newest_handshakes_stay_live() = runBlocking {
        val h = Harness(dir)
        val keys = pairedDevice(h, "devC", "ticket-c")
        val s1 = handshake(h, "devC", keys, "ticket-c")
        roundTrip(h, "devC", s1, "ghost-1")
        val s2 = handshake(h, "devC", keys, null)
        val s3 = handshake(h, "devC", keys, null) // s1 is displaced — a device never holds more than two

        send(h, "devC", s1, SendPrompt("ghost-old", "hi"))
        assertTrue(h.outbound.tryReceive().isFailure, "a frame under the displaced session is dropped, never routed")

        roundTrip(h, "devC", s3, "ghost-new")     // the newest session works…
        roundTrip(h, "devC", s2, "ghost-fallback") // …and the fallback still decrypts + promotes
    }
}
