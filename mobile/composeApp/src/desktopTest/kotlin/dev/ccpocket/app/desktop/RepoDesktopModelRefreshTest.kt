package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.DemoData
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RepoDesktopModel.refresh] semantics on a demo-mode repo ([PocketRepository.enterDemo] loops outbound
 * frames back as sample replies, so no daemon is needed; Unconfined makes the round-trip synchronous).
 * Guards the sidebar-refresh invariants: re-listing never reorders RECENT, refreshing a non-current
 * group repoints the live list to it, and the outgoing current group keeps its rows as a snapshot.
 */
class RepoDesktopModelRefreshTest {

    private fun demoModel(): Pair<PocketRepository, RepoDesktopModel> {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        // demo mode never sets a binding, but RECENT visits are keyed per account — fake one
        repo.paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        repo.enterDemo()
        // FakeDesktopStore: never read or write the developer's real store file from tests (issue #102)
        return repo to RepoDesktopModel(repo, scope, FakeDesktopStore())
    }

    @Test
    fun refreshRelistsTheCurrentGroupInPlace() {
        val (repo, model) = demoModel()
        val dir = DemoData.dirs()[0]
        model.openProject(DkProject(dir.path, dir.name))
        assertEquals(dir.path, repo.sessionsDir.value)
        val before = model.sessions.map { it.sessionId }
        assertTrue(before.isNotEmpty())

        model.refresh() // ⌘R — no group argument targets the current one

        assertEquals(dir.path, repo.sessionsDir.value)
        assertEquals(before, model.sessions.map { it.sessionId })
    }

    @Test
    fun refreshingANonCurrentGroupRepointsWithoutReordering() {
        val (repo, model) = demoModel()
        val (a, b) = DemoData.dirs()
        model.openProject(DkProject(a.path, a.name))
        model.openProject(DkProject(b.path, b.name))
        assertEquals(listOf(b.path, a.path), model.sessionGroups.map { it.path })
        assertEquals(b.path, model.sessionGroups.first { it.current }.path)

        model.refresh(model.sessionGroups.first { it.path == a.path })

        assertEquals(a.path, repo.sessionsDir.value) // A is the live-listed group now
        assertEquals(listOf(b.path, a.path), model.sessionGroups.map { it.path }) // no reorder
        assertEquals(a.path, model.sessionGroups.first { it.current }.path)
        assertTrue(model.sessionGroups.first { it.path == b.path }.sessions.isNotEmpty()) // B kept its snapshot
    }
}
