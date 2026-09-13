package dev.ccpocket.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.data.FleetCoordinator
import dev.ccpocket.app.data.FleetRuntime
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.Pairing
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Issue #373 — a RUNNING project row is an explicit navigation, so it reveals its project in RECENT the way a session
 * row or a pin does.
 *
 * The project row is RUNNING's fallback when it can name no running session in that project: the daemon reports a
 * conversation that is alive but idle as an open project whose session is not executing. A click sends the
 * [FleetCoordinator] to the project's live session — and when that is the session already selected, nothing changes that
 * the sidebar's selection effect could see, so a project folded past RECENT's "Show all" stayed folded. These cases walk
 * the live [RepoDesktopModel], the real coordinator and a click on the real [Sidebar]. Only the transport is stood in for:
 * every outbound frame is answered through the repository's own frame handler and never reaches a socket. Offscreen
 * desktop rendering — none of this stands in for acceptance on a real machine.
 */
@OptIn(ExperimentalTestApi::class)
class RunningProjectRevealTest {

    private lateinit var scope: CoroutineScope
    private val savedFleet = FleetRuntime.coordinator
    private var savedActive: String? = null

    // a machine switch persists the active account, like a cold switch does — put back what the test store held
    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        savedActive = Pairing.activeAccount()
    }

    @AfterTest
    fun tearDown() {
        FleetRuntime.coordinator = savedFleet
        Pairing.setActive(savedActive)
        scope.cancel()
    }

    /**
     * The review's path. P sits sixth in RECENT with one session, S, whose conversation is alive and idle — so RUNNING
     * shows P as a project row. S is selected (and revealed) through its session entry, then the user folds the list.
     * A click on RUNNING's P comes back to S — no new open, no new selection — and must still lift the fold and bring P
     * into view. From there the user's own "Show less" holds through refreshes and a directory push, and the next click
     * on the row reveals P again.
     */
    @Test
    fun anIdleRunningProjectRowRevealsItsFoldedProjectOnEveryClick() = runComposeUiTest {
        val repo = PocketRepository(scope)
        repo.paired.value = binding(HERE)
        repo.pairedList.clear(); repo.pairedList.add(binding(HERE))
        val sent = repo.answerAsDaemon()
        val fleet = FleetCoordinator(scope, repo)
        FleetRuntime.coordinator = fleet
        repo.receiveForTest(Directories(listOf(openIdle(P, S))))
        val model = RepoDesktopModel(repo, scope, fleet, FakeDesktopStore())
        // RECENT puts the most recently opened project first: P, then five more, leaves P sixth — past the fold
        model.openProject(DkProject(P, "p"))
        (1..5).forEach { model.openProject(DkProject("/work/q$it", "q$it")) }
        assertEquals(5, model.sessionGroups.indexOfFirst { it.path == P })
        showSidebar(model)
        assertEquals(-1, rowIndex("h:$P"), "precondition: P waits behind Show all")

        model.selectSession(model.sessionGroups.first { it.path == P }.sessions.single())
        waitForIdle()
        assertEquals(S, model.selectedSessionId)
        assertEquals("convo:$S", repo.convoId.value)
        assertTrue(rowIndex("h:$P") >= 0, "precondition: selecting S revealed P")
        val row = model.runningRows.single()
        assertEquals(P, row.project.path)
        assertNull(row.session, "precondition: the idle conversation leaves RUNNING a project row for P, not a session row")
        toggleFold()
        assertEquals(-1, rowIndex("h:$P"), "precondition: the user folded P away")
        scrollListToTop()

        val selected = assertNotNull(model.projectListReveal)
        val opens = sent.count { it is OpenSession }
        onNodeWithTag("running:$P").performClick()
        waitForIdle()
        assertEquals(S, model.selectedSessionId, "the live session is S, already open and selected")
        assertEquals(opens, sent.count { it is OpenSession }, "…so the coordinator does not reopen it")
        val clicked = assertNotNull(model.projectListReveal)
        assertTrue(clicked.generation > selected.generation, "the row publishes a reveal of its own, though the selection did not change")
        assertEquals(DkProjectListReveal(P, clicked.generation, sessionId = null, accountId = HERE), clicked, "…for P itself, in this machine's RECENT")
        assertEquals(5, model.sessionGroups.indexOfFirst { it.path == P }, "the click does not reorder RECENT")
        assertTrue(rowIndex("h:$P") >= 0, "the fold lifts")
        listRow("p").assertIsDisplayed() // …and the list scrolls down to P
        listRow(S_TITLE).assertIsDisplayed()

        // Show less holds from here: a refresh that moves the listing away and back, and a directory push, are no navigation
        toggleFold()
        assertEquals(-1, rowIndex("h:$P"))
        model.refresh(model.sessionGroups.first { it.path == "/work/q3" })
        waitForIdle()
        assertNull(model.selectedSessionId, "precondition: the listing moved off P")
        model.refresh(model.sessionGroups.first { it.path == P })
        repo.receiveForTest(Directories(listOf(openIdle(P, S))))
        waitForIdle()
        assertEquals(S, model.selectedSessionId)
        assertEquals(clicked, model.projectListReveal, "refreshes publish no reveal")
        assertEquals(-1, rowIndex("h:$P"), "…and S coming back into the selection does not undo Show less")

        scrollListToTop()
        onNodeWithTag("running:$P").performClick()
        waitForIdle()
        val again = assertNotNull(model.projectListReveal)
        assertTrue(again.generation > clicked.generation, "the next click is the next navigation")
        assertTrue(rowIndex("h:$P") >= 0, "…and reveals P again")
        listRow("p").assertIsDisplayed()
    }

    /**
     * A RUNNING row can name another computer. Its reveal is for THAT computer's RECENT — the sidebar takes a request up
     * only on the machine it names — though the click comes from the machine on screen. The coordinator promotes the
     * target's link, lists the project there and opens its live session.
     */
    @Test
    fun aRunningProjectOnAnotherComputerAsksForThatComputersRecent() = runComposeUiTest {
        val primary = PocketRepository(scope)
        primary.paired.value = binding(HERE)
        primary.pairedList.clear(); primary.pairedList.addAll(listOf(binding(HERE), binding(OTHER)))
        primary.answerAsDaemon()
        // The other machine's link as the coordinator keeps it: Ready, holding its own project listing. Its readiness is
        // set by hand, like FleetSwitchTest's hot satellite — a handshake is exactly what this harness never runs.
        val other = PocketRepository(scope, pinnedTo = binding(OTHER))
        other.sessionActive.value = true
        other.phase.value = ConnPhase.Ready
        other.answerAsDaemon()
        other.receiveForTest(Directories(listOf(openIdle(R, S_R))))
        val fleet = FleetCoordinator(scope, primary)
        fleet.promoteHotSatellites = true // as the desktop shell runs it
        primary.onBeforeSwitch = fleet::retireSatellite // what start() wires, without starting its collectors
        fleet.satellites[OTHER] = other
        FleetRuntime.coordinator = fleet
        val model = RepoDesktopModel(primary, scope, fleet, FakeDesktopStore())
        showSidebar(model)
        val row = model.runningRows.single()
        assertEquals(OTHER, row.machine.computer.accountId, "precondition: the row is the other machine's")
        assertNull(row.session, "…a project row: another machine's link lists no sessions")

        onNodeWithTag("running:$R").performClick()
        waitForIdle()
        assertSame(other, fleet.primary, "the click switched to the other machine")
        assertEquals(S_R, model.selectedSessionId, "…and opened the project's live session there")
        val published = assertNotNull(model.projectListReveal, "the row publishes a reveal")
        assertEquals(
            DkProjectListReveal(R, published.generation, sessionId = null, accountId = OTHER), published,
            "…for the other machine's RECENT, not the one the click came from",
        )
        assertTrue(rowIndex("h:$R") >= 0)
        listRow("r").assertIsDisplayed()
    }

    private fun binding(accountId: String) = PairedDaemon(
        // a closed loopback port that nothing here dials — see answerAsDaemon
        relay = "wss://127.0.0.1:9", accountId = accountId, daemonPub = "pk-$accountId", deviceId = "dev", credential = "c-$accountId",
    )

    /** [path] as the daemon lists a project whose conversation [sessionId] is alive with no turn executing. */
    private fun openIdle(path: String, sessionId: String) = DirectoryEntry(
        path, path.substringAfterLast('/'), isDir = true, open = true,
        activeSessionId = sessionId, activeSessions = listOf(ActiveSession(sessionId, executing = false)),
    )

    /** Each project's listing: P and R hold one session each, every other project enough rows to overflow the column. */
    private fun sessionsIn(dir: String): List<SessionSummary> = when (dir) {
        P -> listOf(summary(S, S_TITLE, dir))
        R -> listOf(summary(S_R, "r · idle on the other machine", dir))
        else -> List(8) { summary("$dir#$it", "${dir.substringAfterLast('/')} · $it", dir) }
    }

    private fun summary(id: String, title: String, cwd: String) =
        SessionSummary(id, title, firstPrompt = "", messageCount = 1, cwd = cwd, lastModified = 1L)

    /**
     * Stands in for this link's daemon: answers listings and opens through the repository's own frame handler, as a reply
     * off the wire would land, and records what was sent. The throw then ends the send before any transport is touched.
     */
    private fun PocketRepository.answerAsDaemon(): List<Frame> {
        val sent = mutableListOf<Frame>()
        onSendForTest = { frame ->
            sent += frame
            when (frame) {
                is ListSessions -> receiveForTest(Sessions(frame.workdir, sessionsIn(frame.workdir)))
                is OpenSession -> receiveForTest(SessionLive(
                    "convo:${frame.resumeId}", frame.workdir, frame.resumeId, executing = false,
                    title = sessionsIn(frame.workdir).first { it.sessionId == frame.resumeId }.title, agent = AgentKind.CLAUDE,
                ))
                else -> Unit
            }
            throw CancellationException("no transport in this test")
        }
        return sent
    }

    /** The sidebar in a fixed 640dp column, so how much RECENT shows before it scrolls doesn't depend on the host. */
    private fun ComposeUiTest.showSidebar(model: DesktopModel) {
        setContent { PocketTheme { Box(Modifier.height(640.dp)) { Sidebar(model) } } }
        waitForIdle()
    }

    /** Where the RECENT list holds [key], by its own answer; -1 = the list does not emit that row. */
    private fun ComposeUiTest.rowIndex(key: String): Int =
        onNodeWithTag("sidebar-list").fetchSemanticsNode().config[SemanticsProperties.IndexForKey](key)

    /** A row inside RECENT reading [text] — not its RUNNING twin outside the list. */
    private fun ComposeUiTest.listRow(text: String): SemanticsNodeInteraction =
        onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag("sidebar-list"))).onFirst()

    /** "Show all" / "Show less", scrolled into view first: expanding pushes it below everything it brought in. */
    private fun ComposeUiTest.toggleFold() {
        onNodeWithTag("sidebar-list").performScrollToKey("recent-limit")
        waitForIdle()
        onNodeWithTag("recent-limit").performClick()
        waitForIdle()
    }

    /** RECENT browsed from the top, so reaching a project past the fold takes a scroll. */
    private fun ComposeUiTest.scrollListToTop() {
        onNodeWithTag("sidebar-list").performScrollToIndex(0)
        waitForIdle()
    }

    private companion object {
        const val HERE = "acct-running-here"
        const val OTHER = "acct-running-other"
        /** This machine's project past RECENT's fold, and its one session — alive, idle. */
        const val P = "/work/p"
        const val S = "p-idle"
        const val S_TITLE = "p · the idle conversation"
        /** The other machine's RUNNING project, and its one session. */
        const val R = "/work/r"
        const val S_R = "r-idle"
    }
}
