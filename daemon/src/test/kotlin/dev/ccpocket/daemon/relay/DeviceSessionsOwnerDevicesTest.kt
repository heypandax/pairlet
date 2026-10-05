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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pairing security phase 0, the daemon side of `pairlet pair` (wait for the outcome) and `pairlet devices`
 * (list, revoke): which device an interactive pairing actually anchored, what the owner can list, and that a
 * revoke cuts the device at once and keeps it out even if the relay announces it again before it has
 * processed the revoke.
 */
class DeviceSessionsOwnerDevicesTest {

    private val dir = createTempDirectory("ccp-ds-owner-devices").toFile()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private class Harness(dir: File) {
        var now = 5_000_000L
        val store = File(dir, "devices.json")
        val identity = Identity.loadOrCreate(File(dir, "identity.json"))
        val bridges = BridgeRegistry(File(dir, "bridges.json"))
        val outbound = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)
        val sessions = DeviceSessions(
            core = DaemonCore(emptyMap()), identity = identity, store = store, bridges = bridges, clock = { now },
        ) { deviceId, payload -> outbound.trySend(deviceId to payload) }

        fun allowListed(): Set<String> = PairedDevices.load(store).keys
    }

    private suspend fun handshake(h: Harness, deviceId: String, keys: E2ECrypto.KeyPair, psk: String?): E2ESession? {
        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, h.identity.e2ePubRaw, psk = (psk ?: "").encodeToByteArray())
        h.sessions.onFrame(deviceId, Wire.payload(Wire.HANDSHAKE, init.ephPublic))
        val resp = h.outbound.tryReceive().getOrNull() ?: return null
        return init.finish(Wire.payloadBody(resp.second))
    }

    private suspend fun send(h: Harness, deviceId: String, session: E2ESession, body: Frame) {
        val env = Envelope("0", 0L, body = body)
        h.sessions.onFrame(deviceId, Wire.payload(Wire.TRANSPORT, session.seal(PocketJson.encodeToString(env).encodeToByteArray())))
    }

    private inline fun <reified T> decode(session: E2ESession, framed: ByteArray): T {
        val plain = session.open(Wire.payloadBody(framed)) ?: throw AssertionError("frame did not decrypt")
        return PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body as T
    }

    @Test
    fun an_interactive_pairing_reports_the_device_it_anchored() = runBlocking<Unit> {
        val h = Harness(dir)
        val pairingId = assertNotNull(h.sessions.onMintedTicket("ticket-1"), "an interactive mint gets a pairing id")
        assertIs<OwnerPairingWatch.Outcome.Pending>(h.sessions.awaitOwnerPairing(pairingId, 10))

        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired("devP", b64.encodeToString(keys.publicRaw))
        val outcome = assertIs<OwnerPairingWatch.Outcome.Paired>(h.sessions.awaitOwnerPairing(pairingId, 10))
        assertEquals("devP", outcome.deviceId)
        assertContentEquals(keys.publicRaw, outcome.pub, "the CLI fingerprints exactly the key that was allow-listed")

        val listed = h.sessions.ownerDevices().single()
        assertEquals("devP", listed.deviceId)
        assertContentEquals(keys.publicRaw, listed.pub)
        assertEquals(h.now, listed.pairedAt)
        assertTrue(listed.firstContactPending)
    }

    @Test
    fun restricted_mints_get_no_pairing_id_and_are_never_listed() = runBlocking<Unit> {
        val h = Harness(dir)
        assertNull(h.sessions.onMintedTicket("bridge-ticket", headless = true))
        assertTrue(h.bridges.recordIntent("bridge-ticket", BridgeSpec("bot", emptyList()), ttlMs = 240_000))
        h.sessions.onDevicePaired("devBot", b64.encodeToString(E2ECrypto.generateKeyPair().publicRaw))
        assertEquals(emptyList(), h.sessions.ownerDevices().map { it.deviceId }, "a provisional bridge is not an owner device")
        assertFalse(h.sessions.revokeOwnerDevice("devBot"), "`devices revoke` only revokes full-power devices")
    }

    @Test
    fun a_pairing_nobody_completes_expires() = runBlocking<Unit> {
        val h = Harness(dir)
        val pairingId = h.sessions.onMintedTicket("ticket-x")!!
        h.now += 130_001
        assertIs<OwnerPairingWatch.Outcome.Expired>(h.sessions.awaitOwnerPairing(pairingId, 10))
        h.sessions.onDevicePaired("devLate", b64.encodeToString(E2ECrypto.generateKeyPair().publicRaw))
        assertIs<OwnerPairingWatch.Outcome.Expired>(h.sessions.awaitOwnerPairing(pairingId, 10), "a late announce changes nothing")
        assertEquals(emptySet(), h.allowListed())
        assertIs<OwnerPairingWatch.Outcome.Unknown>(h.sessions.awaitOwnerPairing("never-minted", 10))
    }

    @Test
    fun a_revoke_cuts_the_device_now_and_a_replayed_announce_cannot_bring_it_back() = runBlocking<Unit> {
        val h = Harness(dir)
        h.sessions.onMintedTicket("ticket-a")
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired("devA", b64.encodeToString(keys.publicRaw))
        val phone = handshake(h, "devA", keys, "ticket-a")!!
        assertTrue(decode<Frame>(phone, h.outbound.receive().second) is DaemonInfo)
        send(h, "devA", phone, SendPrompt("c-1", "hi"))
        assertEquals("c-1", decode<SessionGone>(phone, h.outbound.receive().second).convoId)

        val epoch = PairedDevices.epoch
        assertTrue(h.sessions.revokeOwnerDevice("devA"))
        assertEquals(emptySet(), h.allowListed(), "out of devices.json at once")
        assertTrue(PairedDevices.epoch > epoch, "the allow-list epoch moved — that is what closes a live direct-LAN socket")
        send(h, "devA", phone, SendPrompt("c-2", "hi"))
        assertTrue(h.outbound.tryReceive().isFailure, "its live relay session is gone: nothing routes")
        assertEquals(emptyList(), h.sessions.ownerDevices())

        // the relay has not processed the revoke yet (queued across a reconnect) and replays the device while
        // an owner ticket happens to be armed: it must not be re-anchored on it
        h.sessions.onMintedTicket("ticket-b")
        h.sessions.onDevicePaired("devA", b64.encodeToString(keys.publicRaw))
        assertEquals(emptySet(), h.allowListed())
        assertNull(handshake(h, "devA", keys, null), "the revoked key is not a handshake peer any more")
        assertFalse(h.sessions.revokeOwnerDevice("devA"), "nothing left to revoke")
    }
}
