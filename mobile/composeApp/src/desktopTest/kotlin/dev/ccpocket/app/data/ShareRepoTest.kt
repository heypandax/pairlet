package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.ShareEnded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The folder-share owner control plane end-to-end over the demo loopback (issue #115): [PocketRepository]
 * routes CreateShare/ListShares/RevokeShare through [demoRespond], which echoes sample replies back into
 * [handle] — so create yields an invite, list populates the management state, and revoke drops the row.
 * Unconfined makes the round-trip synchronous, so no daemon is needed.
 */
class ShareRepoTest {
    private fun demoRepo(): PocketRepository {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.paired.value = PairedDaemon(relay = "wss://t", accountId = "a", daemonPub = "p", deviceId = "d", credential = "c")
        repo.enterDemo()
        return repo
    }

    @Test fun createShareYieldsAnInvite() {
        val repo = demoRepo()
        repo.createShare("/Users/me/project", AccessTier.COLLABORATE, 24 * 3600)
        val created = repo.lastShareCreated.value
        assertNotNull(created)
        assertTrue(created.ok)
        val invite = created.invite
        assertNotNull(invite)
        assertEquals("project", invite.folderName)          // basename only, never the abs path
        assertEquals(AccessTier.COLLABORATE, invite.tier)
    }

    @Test fun listSharesPopulatesManagementState() {
        val repo = demoRepo()
        repo.listShares()
        assertTrue(repo.shares.isNotEmpty())
        assertTrue(repo.sharesLoaded.value)
    }

    @Test fun revokeDropsTheRow() {
        val repo = demoRepo()
        repo.listShares()
        val id = repo.shares.first().deviceId
        val before = repo.shares.size
        repo.revokeShare(id)
        assertEquals(before - 1, repo.shares.size)
        assertTrue(repo.shares.none { it.deviceId == id })
    }

    /** Guest side (#115 follow-up): the daemon's ShareEnded notice lights the precise terminal state and
     *  persists a per-account row (what loadShareEnded reads back at construction — the relaunch path).
     *  Deliberately does NOT exercise unpair here: the desktop SecureStore is the developer's real
     *  properties file and Pairing.remove would edit the real paired list. */
    @Test fun shareEndedLightsTheTerminalAndPersistsPerAccount() {
        val key = PocketRepository.K_SHARE_ENDED_PREFIX + "a"
        val store = dev.ccpocket.app.secure.SecureStore
        val prior = store.getString(key) // restore whatever was there — a real guest binding must survive this test
        try {
            val repo = demoRepo()
            repo.onShareEnded(ShareEnded(ShareEnded.REASON_EXPIRED, ownerLabel = "Pandas-MacBook"))
            assertEquals(ShareEnded.REASON_EXPIRED, repo.shareEnded.value?.reason)
            assertEquals("Pandas-MacBook", repo.shareEnded.value?.ownerLabel)
            // the persisted row is exactly what loadShareEnded parses back at construction
            assertEquals(ShareEnded.REASON_EXPIRED + "\tPandas-MacBook", store.getString(key))
        } finally {
            if (prior == null) store.remove(key) else store.putString(key, prior)
        }
    }
}
