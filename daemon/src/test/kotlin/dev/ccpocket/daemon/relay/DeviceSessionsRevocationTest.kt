package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.identity.PairedDevices
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The LAN gate trusts devices.json, so revocation MUST prune it — these pin the two prune paths
 *  (immediate DeviceRevoked, attach-replay reconcile) and the empty-replay safety valve. */
class DeviceSessionsRevocationTest {

    private val dir = createTempDirectory("ccp-revoke").toFile()
    private val store = File(dir, "devices.json")
    private fun sessions() = DeviceSessions(
        core = DaemonCore(emptyMap()),
        identity = Identity.loadOrCreate(File(dir, "identity.json")),
        store = store,
    ) { _, _ -> }

    private fun pub(seed: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(seed.encodeToByteArray())

    @Test
    fun deviceRevoked_prunes_key_and_bumps_epoch() = runBlocking {
        val s = sessions()
        s.onDevicePaired("devA", pub("A"))
        s.onDevicePaired("devB", pub("B"))
        val epochBefore = PairedDevices.epoch
        s.onDeviceRevoked("devA")
        assertEquals(setOf("devB"), PairedDevices.load(store).keys)
        assertTrue(PairedDevices.epoch > epochBefore) // live direct sockets re-verify on the next frame
    }

    @Test
    fun attach_replay_reconcile_prunes_devices_the_relay_no_longer_announces() = runBlocking {
        val s = sessions()
        s.onDevicePaired("devA", pub("A"))
        s.onDevicePaired("devB", pub("B"))
        s.beginAttachReplay()
        s.onDevicePaired("devA", pub("A")) // relay re-announces only the non-revoked device
        s.reconcileReplay()
        assertEquals(setOf("devA"), PairedDevices.load(store).keys)
    }

    @Test
    fun empty_replay_prunes_nothing() = runBlocking {
        val s = sessions()
        s.onDevicePaired("devA", pub("A"))
        s.beginAttachReplay()
        s.reconcileReplay() // older/foreign relay with no re-announce — must not brick the binding
        assertEquals(setOf("devA"), PairedDevices.load(store).keys)
    }

    @Test
    fun first_contact_gate_arms_on_fresh_pair_and_clears_on_revoke() = runBlocking {
        val s = sessions()
        s.onDevicePaired("devA", pub("A"))
        assertTrue(s.firstContactPending("devA")) // fresh pair: LAN refused until the relay handshake proves the ticket
        s.onDevicePaired("devA", pub("A"))        // attach replay of a KNOWN key must not re-arm...
        s.onDeviceRevoked("devA")
        assertFalse(s.firstContactPending("devA")) // ...and revoke clears the pending entry outright
    }
}
