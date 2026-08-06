package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeSpec
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
 * The authority boundary of a `DevicePaired` announce: a key becomes a FULL-POWER owner only when a
 * ticket this daemon minted was still armed HERE when the announce landed.
 *
 * Without that anchor the announce is unauthorized in the way that matters: the mint's intent — "this
 * link is a REVIEW collaborator" / "a bridge" / "a guest" — lives in memory beside the PSK, so a daemon
 * restart between minting a restricted invite and its redemption loses both. Promoting the announced key
 * then (an empty PSK substituted for the missing one, the pub written into devices.json) silently made
 * the colleague a full owner: past CollaboratorPurpose, past every restricted capability gate, and into
 * the allow-list the direct-LAN gate trusts.
 *
 * These drive real Noise handshakes from the device side and assert the boundary through observable
 * behaviour — what the store holds and what the daemon will route — not through internal flags.
 */
class DeviceSessionsUnanchoredPairTest {

    private val dir = createTempDirectory("ccp-ds-unanchored").toFile()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private class Harness(dir: File) {
        val store = File(dir, "devices.json")
        val identity = Identity.loadOrCreate(File(dir, "identity.json"))
        val bridges = BridgeRegistry(File(dir, "bridges.json"))
        val outbound = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)
        val sessions = DeviceSessions(
            core = DaemonCore(emptyMap()),
            identity = identity,
            store = store,
            bridges = bridges,
        ) { deviceId, payload -> outbound.trySend(deviceId to payload) }

        fun allowListed(): Set<String> = PairedDevices.load(store).keys
    }

    /** Initiator (device) side of one handshake: send msg1, finish on the daemon's msg2. Null when the
     *  daemon refused to answer at all (unknown device). */
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
        val plain = session.open(Wire.payloadBody(framed))
            ?: throw AssertionError("frame did not decrypt under the expected session")
        return PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body as T
    }

    @Test
    fun an_announce_with_no_armed_ticket_never_reaches_the_owner_allow_list() = runBlocking {
        val h = Harness(dir)
        val keys = E2ECrypto.generateKeyPair()

        // no onMintedTicket: the daemon restarted after minting the invite, so the PSK and the intent
        // that classified it are both gone by the time the relay announces the redeemed key
        h.sessions.onDevicePaired("devU", b64.encodeToString(keys.publicRaw))

        assertFalse("devU" in h.allowListed(), "an unanchored key must not be allow-listed as an owner")
        assertEquals(emptySet(), h.allowListed(), "…and nothing may be persisted for it at all")
        assertTrue(h.sessions.firstContactPending("devU"), "the LAN gate stays shut on it too")
    }

    @Test
    fun an_unanchored_device_cannot_route_an_owner_frame() = runBlocking {
        val h = Harness(dir)
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired("devV", b64.encodeToString(keys.publicRaw))

        // it handshakes with an EMPTY PSK — the shape that used to line up exactly with the empty PSK the
        // daemon substituted for the one it lost, producing a working session for a full-power device
        val phone = handshake(h, "devV", keys, null)!!
        assertTrue(h.outbound.tryReceive().isFailure, "an unanchored device learns nothing — not even DaemonInfo")

        send(h, "devV", phone, SendPrompt("ghost-1", "hi"))
        assertTrue(h.outbound.tryReceive().isFailure, "no owner frame may route or seal for an unanchored device")
        assertEquals(emptySet(), h.allowListed(), "and the refusal must not have promoted it either")

        // the provisional hold is dropped on refusal: reconnecting is now an unknown device outright
        assertEquals(null, handshake(h, "devV", keys, null), "the refused key must not survive as a handshake peer")
    }

    /**
     * The issue #207 race shape: two overlapping mints both passed the pre-mint `intentPending()` check,
     * so ticket-a carries the recorded restricted intent while ticket-b sits on top of the LIFO PSK stack
     * with no intent of its own (its `recordIntent` was refused). The announce for the colleague who
     * redeemed ticket-a then pops ticket-b — an armed, non-headless-looking ticket that CANNOT be this
     * pairing's, because a pairing with an unredeemed restricted intent outstanding is never an ordinary
     * interactive one. Anchoring it would write the colleague's key into devices.json as a full owner.
     */
    @Test
    fun an_announce_with_a_restricted_intent_outstanding_never_reaches_the_owner_allow_list() = runBlocking {
        val h = Harness(dir)
        val keys = E2ECrypto.generateKeyPair()

        h.sessions.onMintedTicket("ticket-a", headless = true)
        assertTrue(h.bridges.recordIntent("ticket-a", BridgeSpec("frank", emptyList()), ttlMs = 240_000))
        h.sessions.onMintedTicket("ticket-b", headless = true) // the overlapped mint: armed, intentless

        h.sessions.onDevicePaired("devEve", b64.encodeToString(keys.publicRaw))
        assertFalse("devEve" in h.allowListed(), "a mis-armed announce must not be allow-listed as an owner")
        assertEquals(emptySet(), h.allowListed(), "…and nothing may be persisted for it at all")

        // even a handshake under the armed (but intentless) ticket stays fail-closed: no DaemonInfo,
        // its first frame finds no intent to finalize and no allow-list row — refused, never promoted
        val eve = handshake(h, "devEve", keys, "ticket-b")!!
        assertTrue(h.outbound.tryReceive().isFailure, "a provisional candidate learns nothing — not even DaemonInfo")
        send(h, "devEve", eve, SendPrompt("ghost-1", "hi"))
        assertTrue(h.outbound.tryReceive().isFailure, "no owner frame may route for a mis-armed device")
        assertEquals(emptySet(), h.allowListed(), "and the refusal must not have promoted it either")
    }

    @Test
    fun a_ticket_backed_owner_pairing_still_allow_lists_and_routes() = runBlocking {
        val h = Harness(dir)
        h.sessions.onMintedTicket("ticket-w")
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired("devW", b64.encodeToString(keys.publicRaw))
        assertEquals(setOf("devW"), h.allowListed(), "the ordinary interactive pairing is unchanged")

        val phone = handshake(h, "devW", keys, "ticket-w")!!
        assertTrue(decode<Frame>(phone, h.outbound.receive().second) is DaemonInfo)
        send(h, "devW", phone, SendPrompt("ghost-1", "hi"))
        assertEquals("ghost-1", decode<SessionGone>(phone, h.outbound.receive().second).convoId)
        assertFalse(h.sessions.firstContactPending("devW"), "first contact completed — the LAN gate opens")

        // the relay's attach replay re-announces that same key with nothing armed: a KNOWN device must
        // ride through untouched (re-arming a PSK here would lock its next connect out — issue #161)
        h.sessions.onDevicePaired("devW", b64.encodeToString(keys.publicRaw))
        assertEquals(setOf("devW"), h.allowListed())
        assertFalse(h.sessions.firstContactPending("devW"))
        send(h, "devW", phone, SendPrompt("ghost-2", "hi"))
        assertEquals("ghost-2", decode<SessionGone>(phone, h.outbound.receive().second).convoId)
    }
}
