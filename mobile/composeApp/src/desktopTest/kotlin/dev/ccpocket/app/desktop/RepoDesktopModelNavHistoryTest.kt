package dev.ccpocket.app.desktop

import androidx.compose.runtime.snapshots.Snapshot
import dev.ccpocket.app.data.DemoData
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Session navigation history (the sidebar control row's ‹ › / ⌘[ ⌘]) on a demo-mode repo: browser
 * semantics — visits recorded off the sessionKey echo, back/forward reopen neighbors without
 * re-recording, and a fresh navigation from mid-stack truncates the forward branch.
 *
 * Snapshot notifications are pumped by hand: there is no composition here, so
 * [Snapshot.sendApplyNotifications] plays the UI's apply pump. It is pumped in a POLL rather than once
 * (same shape the RECENT sweep uses — see [RepoDesktopModelRecentTest]) because the delivery is only
 * synchronous while this test owns the snapshot machinery. Run after any composable test in the same
 * JVM — the screenshot generator, the permission-timeout UI test — Compose's global snapshot manager is
 * live on another thread, and whichever pump gets there first decides where the recorder's `collect`
 * resumes. A single hand pump then records the visit a beat later, on that other thread, and the
 * assertion below it read the history before it landed. Polling asserts the same facts without
 * depending on which pump won; a recorder that genuinely stops recording still fails, it just takes
 * [SETTLE_MS] to say so.
 */
class RepoDesktopModelNavHistoryTest {

    /** Pump the apply notifications and wait for [cond] — see the class doc for why this is a loop. */
    private fun settle(cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + SETTLE_MS
        while (System.currentTimeMillis() < end) {
            Snapshot.sendApplyNotifications()
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    private fun demoModel(): Pair<PocketRepository, RepoDesktopModel> {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        repo.enterDemo()
        return repo to RepoDesktopModel(repo, scope, store = FakeDesktopStore())
    }

    /** Select the [i]th session of the current project and let the recorder's snapshotFlow — which rides
     *  the composition's apply pump in production — see it. [until] is what proves the visit landed. */
    private fun RepoDesktopModel.visit(i: Int, until: (DkSession) -> Boolean): DkSession {
        val s = sessions[i]
        selectSession(s)
        settle { until(s) }
        return s
    }

    @Test
    fun backAndForwardReopenNeighbors() {
        val (repo, m) = demoModel()
        val dir = DemoData.dirs().first()
        m.openProject(DkProject(dir.path, dir.name))
        settle { repo.workdir.value != null }

        assertFalse(m.canGoBack) // nothing visited yet — or only the first visit below
        val s1 = m.visit(0) { repo.sessionKey.value == it.sessionId }
        assertFalse(m.canGoBack) // one entry — nothing behind the cursor
        assertFalse(m.canGoForward)
        val s2 = m.visit(1) { m.canGoBack } // a second entry is exactly what puts one behind the cursor
        assertTrue(m.canGoBack)
        assertFalse(m.canGoForward)

        m.goBack()
        settle { m.canGoForward }
        assertEquals(s1.sessionId, repo.sessionKey.value) // reopened the previous session…
        assertFalse(m.canGoBack)
        assertTrue(m.canGoForward) // …and the branch forward survived (no re-record, no truncation)

        m.goForward()
        settle { m.canGoBack }
        assertEquals(s2.sessionId, repo.sessionKey.value)
        assertTrue(m.canGoBack)
        assertFalse(m.canGoForward)
    }

    @Test
    fun freshNavigationFromMidStackTruncatesForwardBranch() {
        val (repo, m) = demoModel()
        val dir = DemoData.dirs().first()
        m.openProject(DkProject(dir.path, dir.name))
        settle { repo.workdir.value != null }

        val s1 = m.visit(0) { repo.sessionKey.value == it.sessionId }
        m.visit(1) { m.canGoBack }
        m.goBack()
        settle { m.canGoForward }
        assertTrue(m.canGoForward)

        // a fresh visit while the cursor sits mid-stack. Settling on sessionKey alone would race the
        // recorder, so wait for the truncation itself — the very thing asserted on the next line.
        val s3 = m.visit(2) { !m.canGoForward && m.canGoBack }
        assertFalse(m.canGoForward) // the old forward branch (s2) is gone — browser semantics
        assertTrue(m.canGoBack)
        assertEquals(s3.sessionId, repo.sessionKey.value)

        m.goBack()
        settle { repo.sessionKey.value == s1.sessionId }
        assertEquals(s1.sessionId, repo.sessionKey.value) // behind s3 sits s1, not the truncated s2
    }

    @Test
    fun crossProjectNavigationRepointsTheListedDir() {
        // g.current — and the #158/#202/#119 right-click verbs — reads repo.sessionsDir. A session
        // click or a ⌘[ step into ANOTHER project must bring the listing along (the switchToSession
        // convention), or the project the user is demonstrably in offers only "Open in split".
        val (repo, m) = demoModel()
        val dirs = DemoData.dirs()
        val (a, b) = dirs[0] to dirs[1]
        m.openProject(DkProject(a.path, a.name))
        settle { repo.sessionsDir.value == a.path }
        val sA = m.visit(0) { repo.sessionKey.value == it.sessionId }

        m.openProject(DkProject(b.path, b.name))
        settle { repo.sessionsDir.value == b.path }
        m.visit(1) { m.canGoBack } // a visit in b, so the back step below crosses projects

        m.goBack() // lands in sA — project a's session
        settle { repo.sessionKey.value == sA.sessionId }
        assertEquals(a.path, repo.sessionsDir.value) // the listing followed the navigation

        // …and a plain session CLICK into another project repoints too (same latent gap, same fix)
        m.openProject(DkProject(b.path, b.name))
        settle { repo.sessionsDir.value == b.path }
        m.selectSession(sA)
        settle { repo.sessionsDir.value == a.path }
        assertEquals(a.path, repo.sessionsDir.value)
    }

    private companion object {
        /** How long a pumped visit is given to land before the assertion calls it a regression. */
        const val SETTLE_MS = 2_000L
    }
}
