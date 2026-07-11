package dev.ccpocket.daemon.bridge

import dev.ccpocket.protocol.AccessTier
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** GUEST folder-share credential persistence, kind-split, downgrade isolation, expiry + session ledger (issue #115). */
class BridgeRegistryGuestTest {

    private val dir = createTempDirectory("ccp-guest-reg").toFile()
    private val bridges = File(dir, "bridges.json")
    private val guests = File(dir, "guests.json")
    private val ledger = File(dir, "guest-sessions.json")
    private fun reg() = BridgeRegistry(bridges, guests, ledger)
    private fun pub(seed: String) = seed.encodeToByteArray().copyOf(32)
    private fun guestSpec(exp: Long = FUTURE) = BridgeSpec.guest("alex", dir.path, AccessTier.COLLABORATE, exp)

    @Test
    fun a_guest_binds_to_guests_json_only_never_bridges_json() {
        val r = reg()
        assertTrue(r.recordIntent("t", guestSpec(), ttlMs = 120_000))
        r.holdProvisional("g", pub("G"))
        val spec = r.finalize("g", "t".encodeToByteArray())
        assertNotNull(spec); assertEquals(CredentialKind.GUEST, spec.kind)
        assertTrue(r.isGuest("g"))
        assertFalse(r.isBridge("g"))
        assertTrue(r.isRestricted("g"))
        // persisted to guests.json; bridges.json holds NOTHING (downgrade isolation)
        assertTrue(guests.exists())
        assertTrue(BridgeStore.load(bridges).isEmpty(), "a guest key must never appear in bridges.json")
        assertEquals(setOf("g"), GuestStore.load(guests).keys)
        // reloads with the same kind
        assertTrue(reg().isGuest("g"))
    }

    @Test
    fun a_pre_115_daemon_reading_only_bridges_json_never_sees_a_guest_key() {
        // bind a guest, then simulate a DOWNGRADED daemon that only knows bridges.json
        val r = reg()
        r.recordIntent("t", guestSpec(), ttlMs = 120_000)
        r.holdProvisional("g", pub("G")); r.finalize("g", "t".encodeToByteArray())
        // a bridge-only registry (guests.json pointed elsewhere / unknown) does not load the guest → fail closed
        val downgraded = BridgeRegistry(bridges, File(dir, "nonexistent-guests.json"), File(dir, "nl.json"))
        assertFalse(downgraded.isGuest("g"))
        assertFalse(downgraded.isBridge("g"))
        assertFalse(downgraded.isRestricted("g")) // an unknown device → its handshake is refused
    }

    @Test
    fun bridge_and_guest_credentials_coexist_in_their_own_files() {
        val r = reg()
        r.recordIntent("tb", BridgeSpec("feishu", listOf(dir.path)), ttlMs = 120_000)
        r.holdProvisional("b", pub("B")); r.finalize("b", "tb".encodeToByteArray())
        r.recordIntent("tg", guestSpec(), ttlMs = 120_000)
        r.holdProvisional("g", pub("G")); r.finalize("g", "tg".encodeToByteArray())
        assertTrue(r.isBridge("b")); assertTrue(r.isGuest("g"))
        // list()/guests() partition by kind
        assertEquals(listOf("b"), r.list().map { it.first })
        assertEquals(listOf("g"), r.guests().map { it.first })
        // each in its own file
        assertEquals(setOf("b"), BridgeStore.load(bridges).keys)
        assertEquals(setOf("g"), GuestStore.load(guests).keys)
    }

    @Test
    fun expired_guest_ids_are_reported_for_the_reaper() {
        val r = reg()
        r.recordIntent("t", guestSpec(exp = 1_000), ttlMs = 120_000, now = 0)
        r.holdProvisional("g", pub("G")); r.finalize("g", "t".encodeToByteArray(), now = 0)
        assertTrue(r.expiredGuestIds(now = 500).isEmpty())
        assertEquals(listOf("g"), r.expiredGuestIds(now = 2_000))
    }

    @Test
    fun guest_session_ledger_persists_and_seeds_a_fresh_guard() {
        val r = reg()
        r.recordIntent("t", guestSpec(), ttlMs = 120_000)
        r.holdProvisional("g", pub("G")); r.finalize("g", "t".encodeToByteArray())
        r.noteGuestSession("g", "sid-1")
        r.noteGuestSession("g", "sid-2")
        assertEquals(setOf("sid-1", "sid-2"), r.guestSessionIds("g"))
        assertTrue(ledger.exists())
        // a FRESH registry (daemon restart) restores the ledger and seeds the guest's guard with it, so
        // "list only my sessions" + resume-own survive the restart
        val r2 = reg()
        assertEquals(setOf("sid-1", "sid-2"), r2.guestSessionIds("g"))
        val guard = r2.startGuestGuard("g"); assertNotNull(guard)
        assertEquals(setOf("sid-1", "sid-2"), guard.ownedSessionIds())
    }

    @Test
    fun revoking_a_guest_drops_its_credential_and_session_ledger() {
        val r = reg()
        r.recordIntent("t", guestSpec(), ttlMs = 120_000)
        r.holdProvisional("g", pub("G")); r.finalize("g", "t".encodeToByteArray())
        r.noteGuestSession("g", "sid-1")
        r.remove("g")
        assertFalse(r.isGuest("g"))
        assertTrue(r.guestSessionIds("g").isEmpty())
        // deletion persisted across a reload
        val r2 = reg()
        assertFalse(r2.isGuest("g"))
        assertTrue(r2.guestSessionIds("g").isEmpty())
    }

    private companion object { const val FUTURE = Long.MAX_VALUE }
}
