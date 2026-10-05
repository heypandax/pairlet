package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
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
import kotlin.test.assertTrue

/**
 * Pairing security phase 0: an INTERACTIVE pairing ticket armed by `pairlet pair` lives only
 * min(relay TTL, 120s) + 10s = 130s here. Before that it anchors the announced device exactly as before;
 * after it, a late announce — or one a compromised relay forges days later from a ticket the owner never
 * used — finds nothing to anchor on: no allow-list row, no working first-contact handshake.
 *
 * Real Noise handshakes from the device side; the assertions are what the store holds and what routes.
 */
class DeviceSessionsArmedTicketExpiryTest {

    private val dir = createTempDirectory("ccp-ds-armed-expiry").toFile()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private class Harness(dir: File) {
        var now = 1_000_000L
        val store = File(dir, "devices.json")
        val identity = Identity.loadOrCreate(File(dir, "identity.json"))
        val bridges = BridgeRegistry(File(dir, "bridges.json"))
        val outbound = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)
        val sessions = DeviceSessions(
            core = DaemonCore(emptyMap()),
            identity = identity,
            store = store,
            bridges = bridges,
            clock = { now },
        ) { deviceId, payload -> outbound.trySend(deviceId to payload) }

        fun allowListed(): Set<String> = PairedDevices.load(store).keys
    }

    private suspend fun handshake(h: Harness, deviceId: String, keys: E2ECrypto.KeyPair, psk: String?): E2ESession? {
        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, h.identity.e2ePubRaw, psk = (psk ?: "").encodeToByteArray())
        h.sessions.onFrame(deviceId, Wire.payload(Wire.HANDSHAKE, init.ephPublic))
        val resp = h.outbound.tryReceive().getOrNull() ?: return null
        assertEquals(Wire.HANDSHAKE, Wire.payloadType(resp.second))
        return init.finish(Wire.payloadBody(resp.second))
    }

    private suspend fun send(h: Harness, deviceId: String, session: E2ESession, body: Frame) {
        val env = Envelope("0", 0L, body = body)
        h.sessions.onFrame(deviceId, Wire.payload(Wire.TRANSPORT, session.seal(PocketJson.encodeToString(env).encodeToByteArray())))
    }

    private inline fun <reified T> decode(session: E2ESession, framed: ByteArray): T {
        assertEquals(Wire.TRANSPORT, Wire.payloadType(framed))
        val plain = session.open(Wire.payloadBody(framed)) ?: throw AssertionError("frame did not decrypt")
        return PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body as T
    }

    @Test
    fun an_announce_inside_the_window_pairs_exactly_as_before() = runBlocking<Unit> {
        val h = Harness(dir)
        h.sessions.onMintedTicket("ticket-in", ttlSec = 120)
        h.now += 129_000 // the relay's 120s plus most of the delivery slack
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired("devIn", b64.encodeToString(keys.publicRaw))
        assertEquals(setOf("devIn"), h.allowListed())

        val phone = handshake(h, "devIn", keys, "ticket-in")!!
        assertTrue(decode<Frame>(phone, h.outbound.receive().second) is DaemonInfo)
        send(h, "devIn", phone, SendPrompt("c-1", "hi"))
        assertEquals("c-1", decode<SessionGone>(phone, h.outbound.receive().second).convoId)
    }

    @Test
    fun a_late_announce_after_expiry_is_never_allow_listed_and_cannot_handshake_with_the_ticket() = runBlocking<Unit> {
        val h = Harness(dir)
        h.sessions.onMintedTicket("ticket-late", ttlSec = 120)
        h.now += 130_001
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired("devLate", b64.encodeToString(keys.publicRaw))
        assertEquals(emptySet(), h.allowListed(), "an expired owner ticket must not anchor anything")

        // the device holds the (expired) ticket; the daemon no longer does, so nothing it sends decrypts
        val late = handshake(h, "devLate", keys, "ticket-late")!!
        assertTrue(h.outbound.tryReceive().isFailure, "no DaemonInfo for an unanchored device")
        send(h, "devLate", late, SendPrompt("c-1", "hi"))
        assertTrue(h.outbound.tryReceive().isFailure, "no frame routes for it")
        assertEquals(emptySet(), h.allowListed())
    }

    @Test
    fun a_relay_announced_ttl_cannot_stretch_the_local_window() = runBlocking<Unit> {
        val h = Harness(dir)
        h.sessions.onMintedTicket("ticket-long", ttlSec = 86_400) // a relay claiming a one-day ticket
        h.now += 130_001
        h.sessions.onDevicePaired("devLong", b64.encodeToString(E2ECrypto.generateKeyPair().publicRaw))
        assertEquals(emptySet(), h.allowListed(), "the local cap (120s + 10s) wins over the relay's TTL")
    }

    @Test
    fun an_expired_ticket_is_skipped_but_a_fresh_one_behind_it_still_anchors() = runBlocking<Unit> {
        val h = Harness(dir)
        h.sessions.onMintedTicket("ticket-old")
        h.now += 200_000
        h.sessions.onMintedTicket("ticket-new")
        h.now += 1_000
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired("devNew", b64.encodeToString(keys.publicRaw))
        assertEquals(setOf("devNew"), h.allowListed())
        // the new ticket was the one bound: a second announce finds the old one gone, not still armed
        h.sessions.onDevicePaired("devGhost", b64.encodeToString(E2ECrypto.generateKeyPair().publicRaw))
        assertEquals(setOf("devNew"), h.allowListed(), "the expired ticket must not anchor a second device")
        val phone = handshake(h, "devNew", keys, "ticket-new")!!
        assertTrue(decode<Frame>(phone, h.outbound.receive().second) is DaemonInfo)
    }

    @Test
    fun expiry_never_touches_a_device_that_already_paired() = runBlocking<Unit> {
        val h = Harness(dir)
        h.sessions.onMintedTicket("ticket-a")
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired("devA", b64.encodeToString(keys.publicRaw))
        val first = handshake(h, "devA", keys, "ticket-a")!!
        assertTrue(decode<Frame>(first, h.outbound.receive().second) is DaemonInfo)
        send(h, "devA", first, SendPrompt("c-1", "hi"))
        assertEquals("c-1", decode<SessionGone>(first, h.outbound.receive().second).convoId)

        h.now += 7 * 24 * 3_600_000L // a week later: the ticket is long expired
        h.sessions.onDevicePaired("devA", b64.encodeToString(keys.publicRaw)) // attach replay of the known key
        assertEquals(setOf("devA"), h.allowListed())
        val again = handshake(h, "devA", keys, null)!! // reconnects use the static keys, no PSK
        assertTrue(decode<Frame>(again, h.outbound.receive().second) is DaemonInfo)
        send(h, "devA", again, SendPrompt("c-2", "hi"))
        assertEquals("c-2", decode<SessionGone>(again, h.outbound.receive().second).convoId)
        assertFalse(h.sessions.firstContactPending("devA"))
    }

    @Test
    fun a_headless_ticket_without_its_intent_never_anchors_full_power() = runBlocking<Unit> {
        // bridge / execution tickets are armed headless and anchor only through their recorded intent. With no
        // intent (lapsed, or never recorded) a late or relay-forged announce pops the ticket and must find nothing
        // to anchor on — it used to be written into devices.json (pre-release review 2026-10-05, HIGH-1).
        val h = Harness(dir)
        h.sessions.onMintedTicket("ticket-headless", headless = true)
        h.now += 10 * 60_000L
        h.sessions.onDevicePaired("devH", b64.encodeToString(E2ECrypto.generateKeyPair().publicRaw))
        assertEquals(emptySet<String>(), h.allowListed(), "a headless-armed ticket must not anchor a full-power device")
    }

    @Test
    fun a_headless_ticket_without_its_intent_does_not_anchor_right_away_either() = runBlocking<Unit> {
        val h = Harness(dir)
        h.sessions.onMintedTicket("ticket-headless", headless = true)
        h.sessions.onDevicePaired("devH", b64.encodeToString(E2ECrypto.generateKeyPair().publicRaw))
        assertEquals(emptySet<String>(), h.allowListed())
    }
}
