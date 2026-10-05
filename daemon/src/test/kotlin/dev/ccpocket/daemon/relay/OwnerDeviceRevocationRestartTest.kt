package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.RetiredCredentialStore
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PocketJson
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `pairlet devices revoke` must hold across a daemon restart. The local half of a revoke happens at once, but the
 * relay may not have processed `RevokeDevice` yet (unreachable, or the daemon restarted right after sending it), and
 * until it does it keeps replaying the device's "paired" announce. The revoked id is therefore tombstoned on disk
 * (the retired-credential tombstones, [RetiredCredentialStore]) BEFORE it leaves devices.json, and stays tombstoned
 * until the relay confirms — so no restart, and no crash at any point of the revoke, can let a replay write it back
 * into the full-power allow-list.
 *
 * A "restart" here is a fresh [Harness] over the same directory: the files survive, nothing in memory does.
 */
class OwnerDeviceRevocationRestartTest {

    private val dir = createTempDirectory("ccp-owner-revoke-restart").toFile()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val tombstoneFile = File(dir, RetiredCredentialStore.FILE_NAME)

    @AfterTest
    fun cleanup() { dir.deleteRecursively() }

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
        fun pending(): Set<String> = sessions.pendingRetiredRevocations().map { it.deviceId }.toSet()
    }

    private suspend fun handshake(h: Harness, deviceId: String, keys: E2ECrypto.KeyPair, psk: String?): E2ESession? {
        while (h.outbound.tryReceive().isSuccess) Unit // a DaemonInfo an earlier handshake left queued
        val init = E2ESession.initiator(keys.privateRaw, keys.publicRaw, h.identity.e2ePubRaw, psk = (psk ?: "").encodeToByteArray())
        h.sessions.onFrame(deviceId, Wire.payload(Wire.HANDSHAKE, init.ephPublic))
        val resp = h.outbound.tryReceive().getOrNull() ?: return null
        assertEquals(deviceId, resp.first)
        assertEquals(Wire.HANDSHAKE, Wire.payloadType(resp.second))
        return init.finish(Wire.payloadBody(resp.second))
    }

    private inline fun <reified T> decode(session: E2ESession, framed: ByteArray): T {
        val plain = session.open(Wire.payloadBody(framed)) ?: throw AssertionError("frame did not decrypt")
        return PocketJson.decodeFromString<Envelope>(plain.decodeToString()).body as T
    }

    /** Pair [id] interactively on its own ticket and complete its first contact, so it is a settled owner device. */
    private suspend fun pairOwner(h: Harness, id: String, keys: E2ECrypto.KeyPair = E2ECrypto.generateKeyPair()): E2ECrypto.KeyPair {
        h.sessions.onMintedTicket("ticket-$id")
        h.sessions.onDevicePaired(id, b64.encodeToString(keys.publicRaw))
        assertNotNull(handshake(h, id, keys, "ticket-$id"))
        return keys
    }

    /** The full-power device is truly unusable: not allow-listed, no handshake (with or without [armedTicket]),
     *  not armed, not held provisional — and still KNOWN, so it is never treated as a brand-new device. */
    private suspend fun assertOut(h: Harness, id: String, keys: E2ECrypto.KeyPair, armedTicket: String? = null) {
        assertFalse(id in h.allowListed(), "$id must not be in devices.json")
        assertNull(handshake(h, id, keys, null), "$id must not handshake")
        armedTicket?.let { assertNull(handshake(h, id, keys, it), "$id must not handshake on the armed ticket") }
        assertFalse(h.sessions.firstContactPending(id), "no pairing ticket armed for $id")
        assertFalse(h.bridges.isBridgeCandidate(id), "$id not held provisional")
        assertTrue(h.sessions.isKnownDevice(id), "$id is known while its revoke is pending")
        assertFalse(h.sessions.ownerDevices().any { it.deviceId == id }, "$id not listed")
    }

    @Test
    fun a_revoke_the_relay_never_received_survives_a_restart_and_a_replayed_announce(): Unit = runBlocking {
        var h = Harness(dir)
        val keysA = pairOwner(h, "devA")
        val keysB = pairOwner(h, "devB")
        assertTrue(h.sessions.revokeOwnerDevice("devA"))
        // relay unreachable: the RevokeDevice never arrives, nothing confirms. The daemon restarts.
        h = Harness(dir)
        assertEquals(setOf("devB"), h.allowListed())
        assertEquals(listOf("devB"), h.sessions.ownerDevices().map { it.deviceId })
        assertEquals(1, h.sessions.revocationsPendingCount(), "`pairlet devices` says one revoke is still pending")

        // the relay link comes up and replays the account's devices — and the owner is pairing a new phone right now
        h.sessions.onMintedTicket("ticket-new")
        h.sessions.beginAttachReplay()
        h.sessions.onDevicePaired("devA", b64.encodeToString(keysA.publicRaw))
        h.sessions.onDevicePaired("devB", b64.encodeToString(keysB.publicRaw))
        assertOut(h, "devA", keysA, armedTicket = "ticket-new")
        h.sessions.reconcileReplay(authoritativeEmpty = true)
        assertEquals(setOf("devA"), h.pending(), "after the replay barrier the relay is asked to revoke it again")
        assertOut(h, "devA", keysA, armedTicket = "ticket-new")

        // the armed ticket was left for the phone it belongs to; devB is untouched
        val fresh = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired("devNew", b64.encodeToString(fresh.publicRaw))
        assertEquals(setOf("devB", "devNew"), h.allowListed())
        assertNotNull(handshake(h, "devNew", fresh, "ticket-new"))
        val b = assertNotNull(handshake(h, "devB", keysB, null))
        assertNotNull(b)
    }

    @Test
    fun the_tombstone_goes_once_the_relay_confirms_and_a_later_start_asks_for_nothing(): Unit = runBlocking {
        var h = Harness(dir)
        val keysA = pairOwner(h, "devA")
        val keysB = pairOwner(h, "devB")
        assertTrue(h.sessions.revokeOwnerDevice("devA"))
        assertEquals(setOf("devA"), RetiredCredentialStore.load(tombstoneFile), "tombstoned on disk, ids only")
        assertFalse(tombstoneFile.readText().contains(b64.encodeToString(keysA.publicRaw)), "no key in the tombstone")

        h = Harness(dir)
        assertEquals(setOf("devA"), h.pending())
        h.sessions.beginAttachReplay()
        h.sessions.onDevicePaired("devA", b64.encodeToString(keysA.publicRaw))
        h.sessions.onDevicePaired("devB", b64.encodeToString(keysB.publicRaw))
        h.sessions.reconcileReplay(authoritativeEmpty = true)
        assertEquals(setOf("devA"), h.pending(), "still carried by the relay: the tombstone stays")
        val devicesBefore = h.store.readBytes()
        h.sessions.onRelayDeviceRevoked("devA") // the relay confirms
        assertEquals(emptySet(), h.pending())
        assertEquals(emptySet(), RetiredCredentialStore.load(tombstoneFile))
        assertTrue(devicesBefore.contentEquals(h.store.readBytes()), "the confirmation does not rewrite devices.json")

        h = Harness(dir)
        assertEquals(emptySet(), h.pending(), "nothing is asked again")
        assertFalse(h.sessions.isKnownDevice("devA"))
        assertEquals(setOf("devB"), h.allowListed())
    }

    @Test
    fun an_authoritative_replay_without_the_device_also_clears_its_tombstone(): Unit = runBlocking {
        var h = Harness(dir)
        pairOwner(h, "devA")
        val keysB = pairOwner(h, "devB")
        assertTrue(h.sessions.revokeOwnerDevice("devA"))
        h = Harness(dir)
        h.sessions.beginAttachReplay()
        h.sessions.onDevicePaired("devB", b64.encodeToString(keysB.publicRaw)) // the relay already revoked devA
        h.sessions.reconcileReplay(authoritativeEmpty = true)
        assertEquals(emptySet(), h.pending())
        assertEquals(emptySet(), RetiredCredentialStore.load(tombstoneFile))
        assertEquals(setOf("devB"), h.allowListed())
    }

    @Test
    fun a_crash_after_the_tombstone_but_before_the_allow_list_rewrite_converges(): Unit = runBlocking {
        var h = Harness(dir)
        val keysA = pairOwner(h, "devA")
        val keysB = pairOwner(h, "devB")
        // the exact disk state of that crash: the tombstone is written, devices.json still holds the device
        assertTrue(RetiredCredentialStore.save(setOf("devA"), tombstoneFile))
        assertEquals(setOf("devA", "devB"), h.allowListed())

        h = Harness(dir)
        // the interrupted revoke is finished at startup, before anything can handshake
        assertEquals(setOf("devB"), h.allowListed())
        assertOut(h, "devA", keysA)
        assertEquals(setOf("devA"), h.pending())
        h.sessions.onMintedTicket("ticket-new")
        h.sessions.onDevicePaired("devA", b64.encodeToString(keysA.publicRaw))
        assertOut(h, "devA", keysA, armedTicket = "ticket-new")
        assertNotNull(handshake(h, "devB", keysB, null), "the other owner device is untouched")
        h.sessions.onRelayDeviceRevoked("devA")
        assertEquals(emptySet(), h.pending())
    }

    @Test
    fun a_crash_after_the_allow_list_rewrite_but_before_the_relay_was_told_converges(): Unit = runBlocking {
        var h = Harness(dir)
        val keysA = pairOwner(h, "devA")
        // DeviceSessions does the local half (tombstone, then devices.json); the RevokeDevice is the relay client's
        // next step. Stopping here IS that crash.
        assertTrue(h.sessions.revokeOwnerDevice("devA"))
        assertEquals(emptySet(), h.allowListed())

        h = Harness(dir)
        assertOut(h, "devA", keysA)
        assertEquals(setOf("devA"), h.pending(), "the revoke is sent on the next attach")
        h.sessions.onRelayDeviceRevoked("devA")
        assertEquals(emptySet(), h.pending())
    }

    @Test
    fun the_same_phone_pairing_again_gets_a_new_id_and_is_admitted_while_the_old_id_stays_out(): Unit = runBlocking {
        var h = Harness(dir)
        val phone = pairOwner(h, "devOld")
        assertTrue(h.sessions.revokeOwnerDevice("devOld"))
        h = Harness(dir)

        // the owner scans again with the same phone (same static key). The relay mints a NEW random device id on
        // every redeem; its replay of the old id arrives too, and must neither be admitted nor eat the new ticket.
        val pairingId = assertNotNull(h.sessions.onMintedTicket("ticket-again"))
        h.sessions.onDevicePaired("devOld", b64.encodeToString(phone.publicRaw))
        h.sessions.onDevicePaired("devFresh", b64.encodeToString(phone.publicRaw))
        assertEquals("devFresh", assertIs<OwnerPairingWatch.Outcome.Paired>(h.sessions.awaitOwnerPairing(pairingId, 10)).deviceId)
        assertEquals(setOf("devFresh"), h.allowListed())
        val s = assertNotNull(handshake(h, "devFresh", phone, "ticket-again"), "the re-paired phone handshakes")
        assertNotNull(s)
        assertOut(h, "devOld", phone)
        assertEquals(setOf("devOld"), h.pending())
    }

    @Test
    fun a_revoke_during_the_attach_replay_keeps_its_tombstone_at_the_barrier(): Unit = runBlocking {
        val h = Harness(dir)
        val keysA = pairOwner(h, "devA")
        h.sessions.beginAttachReplay()
        h.sessions.onDevicePaired("devA", b64.encodeToString(keysA.publicRaw)) // replayed: the relay still holds it
        assertTrue(h.sessions.revokeOwnerDevice("devA"))                       // owner revokes mid-replay
        h.sessions.reconcileReplay(authoritativeEmpty = true)
        assertEquals(setOf("devA"), h.pending(), "the replay carried it, so the relay has NOT revoked it yet")
        assertEquals(setOf("devA"), RetiredCredentialStore.load(tombstoneFile))
    }

    @Test
    fun an_unwritable_tombstone_still_cuts_the_device_for_this_run(): Unit = runBlocking {
        val h = Harness(dir)
        val keysA = pairOwner(h, "devA")
        // the tombstone path is a non-empty directory: the atomic replace onto it fails
        File(tombstoneFile, "blocker").apply { parentFile.mkdirs(); writeText("x") }
        assertTrue(h.sessions.revokeOwnerDevice("devA"))
        assertEquals(emptySet(), h.allowListed(), "the local cut is not held hostage by the tombstone write")
        h.sessions.onMintedTicket("ticket-new")
        h.sessions.onDevicePaired("devA", b64.encodeToString(keysA.publicRaw))
        assertOut(h, "devA", keysA, armedTicket = "ticket-new")
        assertEquals(setOf("devA"), h.pending(), "…and the relay is still asked")
    }

    @Test
    fun the_first_contact_daemon_info_still_reaches_an_unrelated_device(): Unit = runBlocking {
        // a tombstoned id next to a phone that pairs normally: the phone's first frame still gets its DaemonInfo
        var h = Harness(dir)
        pairOwner(h, "devA")
        assertTrue(h.sessions.revokeOwnerDevice("devA"))
        h = Harness(dir)
        h.sessions.onMintedTicket("ticket-p")
        val keys = E2ECrypto.generateKeyPair()
        h.sessions.onDevicePaired("devP", b64.encodeToString(keys.publicRaw))
        val s = assertNotNull(handshake(h, "devP", keys, "ticket-p"))
        val env = Envelope("0", 0L, body = dev.ccpocket.protocol.ClientCaps())
        h.sessions.onFrame("devP", Wire.payload(Wire.TRANSPORT, s.seal(PocketJson.encodeToString(env).encodeToByteArray())))
        assertTrue(decode<Frame>(s, h.outbound.receive().second) is DaemonInfo)
    }
}
