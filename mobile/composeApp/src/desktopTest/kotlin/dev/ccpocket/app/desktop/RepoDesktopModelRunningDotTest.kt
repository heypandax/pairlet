package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.data.DemoData
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListDirectories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The RECENT rows' "running" pulse. Before this, a non-current group rendered [DkSession.running] straight
 * out of the visit snapshot frozen when the user left that project, and the current group's rows carried
 * `SessionSummary.live` — the daemon's "transcript touched within 20s" reading AT LISTING TIME. Both keep a
 * finished session's dot alight forever and neither can see a session that started running afterwards, so
 * the dots said the opposite of the truth. [DirectoryEntry.activeSessions] now decides them.
 *
 * Demo-mode harness (as in [RepoDesktopModelRefreshTest]): outbound frames loop back as sample replies
 * synchronously under Unconfined, so no daemon and no transport are involved. Unlike the older model tests
 * this one CANCELS its scope per test: a model's init collectors outlive the test otherwise (the composer's
 * 400ms debounced persist, the RECENT refill sweep) and keep writing to a process-wide store on timers —
 * enough extra background churn to tip the already timing-sensitive [RepoDesktopModelStopTurnTest] over
 * (issue #185's family). Everything asserted below settles synchronously before the cancel.
 */
class RepoDesktopModelRunningDotTest {

    private fun withDemoModel(block: (PocketRepository, RepoDesktopModel) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = PocketRepository(scope)
            // demo mode never sets a binding, but RECENT visits are keyed per account — fake one
            repo.paired.value = PairedDaemon(
                relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
            )
            repo.enterDemo()
            // FakeDesktopStore: never read or write the developer's real store file from tests (issue #102)
            block(repo, RepoDesktopModel(repo, scope, store = FakeDesktopStore()))
        } finally {
            scope.cancel()
        }
    }

    /** Visit A (whose demo listing marks the live session running), then B — leaving A a frozen snapshot. */
    private fun withTwoGroups(block: (PocketRepository, RepoDesktopModel, DirectoryEntry, DirectoryEntry) -> Unit) =
        withDemoModel { repo, m ->
            val (a, b) = DemoData.dirs()
            m.openProject(DkProject(a.path, a.name))
            m.openProject(DkProject(b.path, b.name))
            block(repo, m, a, b)
        }

    private fun RepoDesktopModel.group(path: String) = sessionGroups.first { it.path == path }

    /** Always assert per ROW, never "no row in this group is running": the demo listing hands EVERY
     *  directory the same four session ids, so one id marked live lights up in both groups here. Real
     *  session ids are unique per session, which is exactly what the daemon's index is keyed by. */
    private fun DkSessionGroup.running(sessionId: String) = sessions.first { it.sessionId == sessionId }.running

    @Test
    fun aStaleSnapshotDotGoesOutAndALiveSessionLightsUp() = withTwoGroups { repo, m, a, b ->
        // baseline: A's snapshot froze the live session as running, and nothing in B is
        assertTrue(m.group(a.path).running(DemoData.LIVE_SESSION_ID), "precondition: the frozen dot is on")
        assertFalse(m.group(b.path).running("demo-s2"))

        // the daemon now answers with a modern list: A holds nothing alive, B has a session mid-turn
        repo.directories.clear()
        repo.directories += DirectoryEntry(path = a.path, name = a.name, isDir = true, hasSessions = true)
        repo.directories += DirectoryEntry(
            path = b.path, name = b.name, isDir = true, hasSessions = true,
            open = true, executing = true, activeSessionId = "demo-s2",
            activeSessions = listOf(ActiveSession(sessionId = "demo-s2", executing = true)),
        )

        // not in activeSessions ⇒ not running, however alive the frozen snapshot still claims it is
        assertFalse(m.group(a.path).running(DemoData.LIVE_SESSION_ID), "a frozen snapshot must not keep a dot alight")
        // in activeSessions and mid-turn ⇒ running, even though this row's listing said live=false
        assertTrue(m.group(b.path).running("demo-s2"))
        assertFalse(m.group(b.path).running("demo-s3"))
    }

    /** Background work with no turn in flight still counts as active — the same `executing || busy` rule
     *  the project rows and the phone's ACTIVE section use. */
    @Test
    fun backgroundWorkAloneKeepsARowActive() = withTwoGroups { repo, m, a, _ ->
        repo.directories.clear()
        repo.directories += DirectoryEntry(
            path = a.path, name = a.name, isDir = true, hasSessions = true, busy = true,
            activeSessions = listOf(ActiveSession(sessionId = "demo-s3", executing = false, busy = true)),
        )
        assertTrue(m.group(a.path).running("demo-s3"))
        assertFalse(m.group(a.path).running(DemoData.LIVE_SESSION_ID))
    }

    /**
     * Backward compatibility: a daemon predating `activeSessions` (< v1.3.1) reports a live session through
     * the legacy scalars ALONE. Reading its empty array as "nothing is running" would put out every dot on
     * the sidebar, so the absence must be treated as "no answer" and today's behavior kept.
     */
    @Test
    fun anOldDaemonsEmptyArrayNeverExtinguishesTheDots() = withTwoGroups { repo, m, a, b ->
        repo.directories.clear()
        repo.directories += DirectoryEntry(
            path = a.path, name = a.name, isDir = true, hasSessions = true,
            open = true, executing = true, activeSessionId = DemoData.LIVE_SESSION_ID, // scalars only
        )
        repo.directories += DirectoryEntry(path = b.path, name = b.name, isDir = true, hasSessions = true)

        assertTrue(m.group(a.path).running(DemoData.LIVE_SESSION_ID), "an old daemon's dots stay as they were")
    }

    /** An unpulled list (fresh link, just-switched machine) says nothing either — blanking every dot on it
     *  would make a machine switch look like every session had died. */
    @Test
    fun anEmptyDirectoryListIsNotEvidenceOfAnythingBeingIdle() = withTwoGroups { repo, m, a, _ ->
        repo.directories.clear()
        assertTrue(m.group(a.path).running(DemoData.LIVE_SESSION_ID))
    }

    /**
     * The OPEN chat is the one row the project list can be behind on: `streaming` is a live push that sees a
     * turn start before any pull could report it. It must win over the list's silence — and stop just as
     * promptly when the turn ends.
     */
    @Test
    fun theOpenChatsRowFollowsTheLiveStreamNotTheProjectList() = withTwoGroups { repo, m, a, _ ->
        repo.directories.clear()
        repo.directories += DirectoryEntry(
            path = a.path, name = a.name, isDir = true, hasSessions = true,
            open = true, activeSessions = listOf(ActiveSession(sessionId = "demo-s4")), // some OTHER session
        )
        repo.convoId.value = "convo-1"
        repo.sessionKey.value = "demo-s3" // the open chat, absent from the list above

        repo.streaming.value = true
        assertTrue(m.group(a.path).running("demo-s3"), "a turn the list hasn't caught up with is still running")

        repo.streaming.value = false
        assertFalse(m.group(a.path).running("demo-s3"), "and the dot goes out the moment the turn does")
    }

    /**
     * The CURRENT project's rows need the same correction, one layer earlier: [DesktopModel.liveSession]
     * resolves them out of `sessions` before it ever consults a group, and that is the lookup the sidebar's
     * pin row (and `runningVisible`) goes through. Fixing only the groups left a pin in the OPEN project
     * reading the stale listing-time `live` while every other project's pin was already right.
     */
    @Test
    fun theCurrentProjectsRowsAreCorrectedBeforeLiveSessionEverLooksAtAGroup() = withDemoModel { repo, m ->
        val a = DemoData.dirs()[0]
        m.openProject(DkProject(a.path, a.name)) // A is the live-listed project — its rows come from `sessions`
        // the demo listing's own verdict, which the pin row used to inherit wholesale
        assertTrue(m.liveSession(DemoData.LIVE_SESSION_ID)?.running == true)
        assertFalse(m.liveSession("demo-s2")?.running == true)

        // the daemon says the exact opposite: the old one finished, another is mid-turn
        repo.directories.clear()
        repo.directories += DirectoryEntry(
            path = a.path, name = a.name, isDir = true, hasSessions = true,
            open = true, executing = true, activeSessionId = "demo-s2",
            activeSessions = listOf(ActiveSession(sessionId = "demo-s2", executing = true)),
        )

        assertFalse(m.liveSession(DemoData.LIVE_SESSION_ID)?.running == true, "the pin row must stop pulsing too")
        assertTrue(m.liveSession("demo-s2")?.running == true, "and light up for what is actually running")
        // the group layer re-applies over these same rows — idempotent, so both views agree
        assertEquals(
            m.sessions.map { it.sessionId to it.running },
            m.group(a.path).sessions.map { it.sessionId to it.running },
        )
    }

    /**
     * The boundary the upstream override must not break. A brand-new session has not persisted its first
     * turn, so it is in NO listing and — until the next directory pull — in no `activeSessions` either;
     * [RepoDesktopModel] synthesizes its row (#42). Reading "absent from the index" as "not running" there
     * would put the dot out on the one session that is certainly alive. It is the open chat by definition,
     * so it rides the openId branch, which decides on `streaming` alone.
     */
    @Test
    fun aBrandNewSessionsSynthesizedRowKeepsItsDotWhileTheDaemonHasNeverHeardOfIt() = withDemoModel { repo, m ->
        val a = DemoData.dirs()[0]
        m.openProject(DkProject(a.path, a.name))
        // a modern list that knows this project and some OTHER live session, but not the new one
        repo.directories.clear()
        repo.directories += DirectoryEntry(
            path = a.path, name = a.name, isDir = true, hasSessions = true,
            open = true, activeSessions = listOf(ActiveSession(sessionId = "demo-s4", executing = true)),
        )
        // the open chat: a freshly minted id the listing above cannot contain yet
        repo.workdir.value = a.path
        repo.convoId.value = "convo-new"
        repo.sessionKey.value = "brand-new-sid"
        repo.streaming.value = true

        val synth = m.sessions.first()
        assertEquals("brand-new-sid", synth.sessionId, "precondition: the synthesized row is on top")
        assertTrue(synth.running, "a session with no listing and no directory row is still running")

        repo.streaming.value = false
        assertFalse(m.sessions.first().running, "and it stops with the turn, not with the next pull")
    }

    /**
     * The poll behind all of the above ([RepoDesktopModel.syncDirectories], driven by the shell while the
     * window is on screen) must stay silent on anything but a Ready link: a write into a dead transport only
     * feeds the reconnect ladder from outside its backoff, and a reconnect re-syncs the list by itself.
     */
    @Test
    fun theDirectoryPollOnlyWritesToAReadyLink() = withDemoModel { repo, m ->
        val sent = mutableListOf<Frame>()
        repo.onSendForTest = { sent += it }

        repo.phase.value = ConnPhase.Reconnecting
        m.syncDirectories()
        assertEquals(0, sent.count { it is ListDirectories })

        repo.phase.value = ConnPhase.Ready
        m.syncDirectories()
        assertEquals(1, sent.count { it is ListDirectories })
    }
}
