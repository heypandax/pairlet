package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.CreateShare
import dev.ccpocket.protocol.PairTicket
import dev.ccpocket.protocol.RevokeShare
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The OWNER folder-share control plane (issue #115): mint / list / revoke. */
class ShareServiceTest {

    private val dir = createTempDirectory("ccp-share-svc").toFile()
    private val shared = File(dir, "repo").apply { mkdirs() }.canonicalFile
    private val registry = BridgeRegistry(File(dir, "bridges.json"), File(dir, "guests.json"), File(dir, "gs.json"))
    private val revoked = mutableListOf<String>()
    private var interactivePending = false

    private fun service(now: Long = 1_000_000) = ShareService(
        accountId = "acct", daemonPubB64 = "daemonpub", relayWsBase = "wss://relay",
        ownerLabel = { "Pandas-Mac" },
        registry = registry,
        mintTicket = { _ -> PairTicket("tkt-abc", 120, "482913") },
        interactivePairingPending = { interactivePending },
        revokeCredential = { id -> revoked += id },
        liveSessions = { listOf(shared.path to ActiveSession("sid-guest", origin = "alex", executing = true)) },
        now = { now },
    )

    private fun bindGuest(deviceId: String = "g", root: String = shared.path, exp: Long = Long.MAX_VALUE) {
        registry.recordIntent("t-$deviceId", BridgeSpec.guest("alex", root, AccessTier.COLLABORATE, exp), ttlMs = 120_000)
        registry.holdProvisional(deviceId, deviceId.encodeToByteArray().copyOf(32))
        registry.finalize(deviceId, "t-$deviceId".encodeToByteArray())
    }

    @Test
    fun create_mints_a_scoped_invite_and_records_a_guest_intent() = runBlocking {
        val res = service().create(CreateShare(shared.path, tier = AccessTier.COLLABORATE, expiresInSec = 7 * 24 * 3600))
        assertTrue(res.ok)
        val inv = assertNotNull(res.invite)
        assertEquals("repo", inv.folderName)             // basename only, never the absolute path
        assertEquals(AccessTier.COLLABORATE, inv.tier)
        assertEquals("tkt-abc", inv.ticket)
        assertEquals("daemonpub", inv.daemonPub)
        assertEquals("Pandas-Mac", inv.ownerLabel)
        assertTrue(inv.expiresAt > 1_000_000)            // in the future
        assertEquals(120, inv.ttlSec)
        assertTrue(registry.intentPending())             // a guest intent was recorded for the ticket
    }

    @Test
    fun create_rejects_a_missing_folder_and_a_pending_phone_pairing() = runBlocking {
        assertFalse(service().create(CreateShare(File(dir, "does-not-exist").path)).ok)
        interactivePending = true
        val r = service().create(CreateShare(shared.path))
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("phone pairing"))
    }

    @Test
    fun an_unknown_future_tier_falls_back_to_the_safest() = runBlocking {
        val res = service().create(CreateShare(shared.path, tier = AccessTier.UNKNOWN))
        assertEquals(AccessTier.REVIEW, res.invite!!.tier) // never grant an unrecognized (possibly wider) tier
    }

    @Test
    fun list_reports_shares_with_live_activity() = runBlocking {
        bindGuest()
        val listing = service().list()
        val row = listing.items.single()
        assertEquals("g", row.deviceId)
        assertEquals(shared.path, row.path)
        assertEquals(AccessTier.COLLABORATE, row.tier)
        assertTrue(row.online)             // the live guest session under the root (origin=alex) counts
        assertEquals(1, row.activeSessions)
        assertFalse(row.expired)
    }

    @Test
    fun revoke_cuts_a_guest_and_refuses_a_non_guest() = runBlocking {
        bindGuest()
        val ok = service().revoke(RevokeShare("g").deviceId)
        assertTrue(ok.ok)
        assertEquals(listOf("g"), revoked) // revokeCredential (local prune + relay RevokeDevice) fired
        // an unknown / non-guest deviceId is refused
        val bad = service().revoke("not-a-share")
        assertFalse(bad.ok)
        assertNotNull(bad.error)
    }

    @Test
    fun list_flags_an_expired_share() = runBlocking {
        bindGuest(deviceId = "g2", exp = 500_000) // already past at now=1_000_000
        val row = service().list().items.single { it.deviceId == "g2" }
        assertTrue(row.expired)
    }

    @Test
    fun create_clamps_the_share_lifetime_to_sane_bounds() = runBlocking {
        // a sub-floor lifetime (typo, or an attacker minimizing the audit window) is raised to the 5-min
        // floor; an absurd one is capped at the 90-day ceiling — a share can neither be effectively
        // instant nor "never expires"
        val floored = service().create(CreateShare(shared.path, expiresInSec = 1)).invite!!
        assertEquals(1_000_000 + 5 * 60 * 1000L, floored.expiresAt) // 5-min floor, not 1 second
        val capped = service().create(CreateShare(shared.path, expiresInSec = 100L * 365 * 24 * 3600)).invite!!
        assertEquals(1_000_000 + 90L * 24 * 3600 * 1000, capped.expiresAt) // 90-day ceiling, not 100 years
    }

    @Test
    fun create_rejects_sharing_a_file_rather_than_a_folder() = runBlocking {
        val file = File(dir, "notes.txt").apply { writeText("secret") }
        val res = service().create(CreateShare(file.path))
        assertFalse(res.ok) // only a directory can be a share root — a lone file is refused
        assertNull(res.invite)
    }
}
