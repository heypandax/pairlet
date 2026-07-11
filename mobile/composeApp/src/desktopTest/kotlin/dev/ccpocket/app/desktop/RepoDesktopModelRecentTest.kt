package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.DemoData
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-memory [DesktopStore] so model tests never read or write the developer's real store file. */
internal class FakeDesktopStore : DesktopStore {
    val map = HashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
}

/**
 * RECENT persistence + clear (issue #102) and the issue #97 "new session must not clear RECENT"
 * invariant, on a demo-mode repo (outbound frames loop back as sample replies synchronously under
 * Unconfined — same harness as [RepoDesktopModelRefreshTest]).
 */
class RepoDesktopModelRecentTest {

    private fun demoModel(store: DesktopStore = FakeDesktopStore()): Pair<PocketRepository, RepoDesktopModel> {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        // demo mode never sets a binding, but RECENT visits are keyed per account — fake one
        repo.paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        repo.enterDemo()
        return repo to RepoDesktopModel(repo, scope, store)
    }

    // the persisted store keys — literal on purpose: these tests pin the on-disk format
    private val visitsKey = "desktop_recent_visits"
    private val pinsKey = "desktop_pins"
    private val hiddenKey = "desktop_hidden_sessions"

    /** The refill sweep resumes off a delay timer (not the test thread) — poll for its completion. */
    private fun waitUntil(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    @Test
    fun visitsPersistAcrossRestartAndRefillLazily() {
        val store = FakeDesktopStore()
        val (_, m1) = demoModel(store)
        val (a, b) = DemoData.dirs()
        m1.openProject(DkProject(a.path, a.name))
        m1.openProject(DkProject(b.path, b.name))
        assertTrue(store.map.getValue(visitsKey).contains(a.path)) // keys landed on disk

        // "restart": a fresh repo + model over the SAME store. Demo counts as ready and the app is
        // cold-idle, so the refill sweep re-lists each restored dir (oldest first) off its poll timer.
        val (repo2, m2) = demoModel(store)
        assertEquals(listOf(b.path, a.path), m2.sessionGroups.map { it.path }) // order (recency) restored at once
        assertTrue(
            waitUntil(3_000) { m2.sessionGroups.all { it.sessions.isNotEmpty() } },
            "restored groups should refill via the listing path",
        )
        assertEquals(b.path, repo2.sessionsDir.value) // most-recent group is the live-listed one again
        assertEquals(b.path, m2.sessionGroups.first { it.current }.path)
    }

    @Test
    fun clearRecentForgetsVisitsButKeepsPinsAndHidden() {
        val store = FakeDesktopStore()
        val (_, m) = demoModel(store)
        val (a, b) = DemoData.dirs()
        m.openProject(DkProject(a.path, a.name))
        m.openProject(DkProject(b.path, b.name))
        m.pin(m.sessions.first())
        m.hideSession(m.sessions.last())
        val pinsBefore = store.map.getValue(pinsKey)
        val hiddenBefore = store.map.getValue(hiddenKey)

        m.clearRecent()

        // visits emptied AND persisted empty; the live-listed dir stays as the synthetic current group
        assertEquals("[]", store.map[visitsKey])
        assertEquals(listOf(b.path), m.sessionGroups.map { it.path })
        assertTrue(m.sessionGroups.single().current)
        assertEquals(1, m.pins.size) // pins untouched…
        assertEquals(pinsBefore, store.map[pinsKey]) // …including their persisted form
        assertEquals(hiddenBefore, store.map[hiddenKey]) // hidden rows survive the clear too

        // a restart after clear stays cleared — nothing refills from thin air
        val (_, m3) = demoModel(store)
        assertTrue(m3.sessionGroups.isEmpty())
    }

    // issue #97: creating a new session must never clear the other RECENT groups — cross-directory
    // (the owner's callout: target dir ≠ currently listed dir) and same-directory both.
    @Test
    fun newSessionKeepsEveryRecentGroup() {
        val (_, m) = demoModel()
        val (a, b) = DemoData.dirs()
        m.openProject(DkProject(a.path, a.name))
        m.openProject(DkProject(b.path, b.name)) // current listing = b
        val fresh = "/Users/alex/code/brand-new"

        m.newSession(fresh, AgentKind.CLAUDE, PermissionMode.DEFAULT) // cross-dir: target ∉ {a, b}

        val groups = m.sessionGroups
        assertEquals(listOf(fresh, b.path, a.path), groups.map { it.path })
        assertTrue(groups.first { it.path == b.path }.sessions.isNotEmpty()) // b archived by snapshotCurrent
        assertTrue(groups.first { it.path == a.path }.sessions.isNotEmpty()) // a untouched
        assertTrue(groups.first { it.path == fresh }.current)

        m.newSession(b.path, AgentKind.CLAUDE, PermissionMode.DEFAULT) // same-dir new session

        assertTrue(m.sessionGroups.first { it.path == b.path }.sessions.isNotEmpty()) // live rows kept
        assertTrue(m.sessionGroups.first { it.path == a.path }.sessions.isNotEmpty())
    }
}
