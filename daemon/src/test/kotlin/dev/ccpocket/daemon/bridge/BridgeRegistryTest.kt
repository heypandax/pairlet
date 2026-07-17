package dev.ccpocket.daemon.bridge

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ticket→bridge binding anchor + persistence (issue #91 §3, reviewer S1). */
class BridgeRegistryTest {

    private val dir = createTempDirectory("ccp-bridge-reg").toFile()
    private val store = File(dir, "bridges.json")
    private fun spec() = BridgeSpec("feishu", listOf(dir.path))
    private fun pub(seed: String) = seed.encodeToByteArray().copyOf(32)

    @Test
    fun intent_binds_only_when_the_confirmed_psk_matches() {
        val r = BridgeRegistry(store)
        assertTrue(r.recordIntent("ticket-A", spec(), ttlMs = 120_000))
        // a device that armed a DIFFERENT ticket (wrong PSK would never have decrypted anyway) never binds
        r.holdProvisional("devWrong", pub("W"))
        assertNull(r.finalize("devWrong", "ticket-B".encodeToByteArray()))
        assertFalse(r.isBridge("devWrong"))
        // the device that confirmed the EXACT ticket becomes the bridge, persisted
        r.holdProvisional("devA", pub("A"))
        val spec = r.finalize("devA", "ticket-A".encodeToByteArray())
        assertNotNull(spec); assertEquals("feishu", spec.name)
        assertTrue(r.isBridge("devA"))
        assertTrue(store.exists())
        // intent consumed — a replay of the same ticket does not re-bind a second device
        assertNull(r.finalize("devA2", "ticket-A".encodeToByteArray()))
    }

    @Test
    fun mint_serialization_refuses_a_second_pending_intent() {
        val r = BridgeRegistry(store)
        assertTrue(r.recordIntent("t1", spec(), ttlMs = 120_000))
        assertFalse(r.recordIntent("t2", spec(), ttlMs = 120_000)) // one at a time
        assertTrue(r.intentPending())
        // once the first is consumed, a new intent is accepted
        r.holdProvisional("d", pub("A")); r.finalize("d", "t1".encodeToByteArray())
        assertFalse(r.intentPending())
        assertTrue(r.recordIntent("t3", spec(), ttlMs = 120_000))
    }

    @Test
    fun expired_intent_never_binds() {
        val r = BridgeRegistry(store)
        assertTrue(r.recordIntent("t", spec(), ttlMs = 1, now = 0))
        r.holdProvisional("d", pub("A"))
        assertNull(r.finalize("d", "t".encodeToByteArray(), now = 10_000)) // TTL elapsed
        assertFalse(r.isBridge("d"))
    }

    @Test
    fun finalize_without_a_held_pub_fails_closed() {
        // daemon restarted mid-pairing: the provisional pub map is memory-only, so a confirm with no
        // held key must NOT register a bridge (the owner re-runs pair --headless)
        val r = BridgeRegistry(store)
        r.recordIntent("t", spec(), ttlMs = 120_000)
        assertNull(r.finalize("d", "t".encodeToByteArray())) // no holdProvisional first
        assertFalse(r.isBridge("d"))
    }

    @Test
    fun revoke_removes_the_bridge_and_reload_sees_the_deletion() {
        val r = BridgeRegistry(store)
        r.recordIntent("t", spec(), ttlMs = 120_000)
        r.holdProvisional("d", pub("A")); r.finalize("d", "t".encodeToByteArray())
        assertTrue(BridgeRegistry(store).isBridge("d")) // persisted + reloads
        r.remove("d")
        assertFalse(r.isBridge("d"))
        assertFalse(BridgeRegistry(store).isBridge("d")) // deletion persisted
    }

    @Test
    fun persist_preserves_each_credentials_original_createdAt() {
        val r = BridgeRegistry(store)
        r.recordIntent("t1", spec(), ttlMs = 120_000, now = 1_000)
        r.holdProvisional("d1", pub("A")); r.finalize("d1", "t1".encodeToByteArray(), now = 1_000)
        // a later, unrelated persist (binding a second bridge) must not restamp d1's bind time
        r.recordIntent("t2", spec(), ttlMs = 120_000, now = 2_000)
        r.holdProvisional("d2", pub("B")); r.finalize("d2", "t2".encodeToByteArray(), now = 2_000)
        assertEquals(1_000L, BridgeStore.load(store)["d1"]?.createdAt)
        assertEquals(2_000L, BridgeStore.load(store)["d2"]?.createdAt)
        // and the bind time survives a reload → re-persist cycle (revoking an unrelated key)
        val r2 = BridgeRegistry(store)
        r2.remove("d2")
        assertEquals(1_000L, BridgeStore.load(store)["d1"]?.createdAt)
    }

    @Test
    fun grace_window_keeps_a_late_first_frame_bindable_not_mis_promoted() {
        // issue #91 LOW (clock skew / pairing latency): with a graced bindable TTL (ticket 120s + grace),
        // a device whose first frame lands LATE (near the ticket edge) still classifies as a bridge —
        // onDevicePaired's looksHeadless stays true, so the key is never mis-promoted into devices.json.
        val r = BridgeRegistry(store)
        val graced = 120_000L + 120_000L // ticket TTL + grace, mirrors PairLoopback
        r.recordIntent("t", spec(), ttlMs = graced, now = 0)
        assertTrue(r.looksHeadless("t".encodeToByteArray(), now = 119_000))  // late, but within grace
        r.holdProvisional("d", pub("A"))
        assertNotNull(r.finalize("d", "t".encodeToByteArray(), now = 119_000)) // still binds
        assertTrue(r.isBridge("d"))

        // past the grace, a fresh intent no longer binds (DeviceSessions then fails closed)
        val r2 = BridgeRegistry(File(dir, "b2.json"))
        r2.recordIntent("t2", spec(), ttlMs = graced, now = 0)
        r2.holdProvisional("d2", pub("B"))
        assertNull(r2.finalize("d2", "t2".encodeToByteArray(), now = graced + 1_000))
        assertFalse(r2.isBridge("d2"))
    }

    @Test
    fun looksHeadless_is_exact_and_non_consuming() {
        val r = BridgeRegistry(store)
        r.recordIntent("t", spec(), ttlMs = 120_000)
        assertTrue(r.looksHeadless("t".encodeToByteArray()))
        assertFalse(r.looksHeadless("other".encodeToByteArray()))
        assertTrue(r.looksHeadless("t".encodeToByteArray())) // still there — non-consuming
    }
}
