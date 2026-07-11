package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AccessTier
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
}
