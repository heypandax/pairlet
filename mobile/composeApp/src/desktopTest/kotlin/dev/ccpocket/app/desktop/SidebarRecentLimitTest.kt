package dev.ccpocket.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.rewind_group_rewound
import dev.ccpocket.app.resources.sidebar_recent_empty
import dev.ccpocket.app.resources.sidebar_recent_show_all
import dev.ccpocket.app.resources.sidebar_recent_show_less
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.SessionSummary
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Issue #373 — the desktop RECENT list shows its first five projects and keeps the rest behind "Show all".
 *
 * Every case renders the real [Sidebar] and asks the RECENT LazyColumn itself where a row sits (its IndexForKey
 * semantics, -1 when the list does not emit the row at all), so "hidden" means the list does not hold the row —
 * not merely that it is scrolled away. Most cases use a model with a FIXED group order on purpose: the live model
 * moves an opened project to the top, which would hide exactly the cases the fold has to get right. The go-back case
 * drives the live [RepoDesktopModel] over a demo repository instead — a session navigation leaves the order alone
 * there too. Offscreen desktop rendering only; none of this stands in for acceptance on a real machine.
 */
@OptIn(ExperimentalTestApi::class)
class SidebarRecentLimitTest {

    /**
     * RECENT of [sessionGroups], over the seed for everything else the sidebar draws: no session pins, a plain
     * nullable selection any row (or the test) can set — including the blink an open produces — and a
     * project-pin reveal that, unlike the live model, never reorders.
     */
    private class RecentModel(
        override val sessionGroups: List<DkSessionGroup>,
        override val customGroups: List<DkGroup> = emptyList(),
        override val canEditGroups: Boolean = false,
        override val runningRows: List<DkRunningRow> = emptyList(),
        val seed: SeedDesktopModel = SeedDesktopModel(),
    ) : DesktopModel by seed {
        override val pins: List<DkPin> = emptyList()
        var selected by mutableStateOf<String?>(null)
        override val selectedSessionId: String? get() = selected
        override fun selectSession(s: DkSession) { selected = s.sessionId }
        private var reveal by mutableStateOf<DkProjectListReveal?>(null)
        override val projectListReveal: DkProjectListReveal? get() = reveal
        override fun openProjectPin(p: DkProjectPin) {
            reveal = DkProjectListReveal(p.path, (reveal?.generation ?: 0L) + 1)
        }
    }

    /**
     * Two machines behind one sidebar, each with its own RECENT, whose listings land only when the test lands them —
     * the shape of a hot switch (the promoted satellite arrives with its RECENT already loaded) racing a reveal that
     * still waits on a listing. Its requests name the machine that made them, as the live model's do.
     */
    private class TwoMachineModel(val seed: SeedDesktopModel = SeedDesktopModel()) : DesktopModel by seed {
        val lists = mutableStateMapOf<String, List<DkSessionGroup>>()
        override val sessionGroups: List<DkSessionGroup> get() = activeComputer?.accountId?.let { lists[it] }.orEmpty()
        override val pins: List<DkPin> = emptyList()
        override val runningRows: List<DkRunningRow> = emptyList()
        override val selectedSessionId: String? = null
        private var reveal by mutableStateOf<DkProjectListReveal?>(null)
        override val projectListReveal: DkProjectListReveal? get() = reveal

        /** What a pin publishes: reveal [path] — or [sessionId] in it — in the list of [accountId]. */
        fun request(path: String, sessionId: String? = null, accountId: String? = activeComputer?.accountId) {
            reveal = DkProjectListReveal(path, (reveal?.generation ?: 0L) + 1, sessionId, accountId)
        }
    }

    /** Project [i] at `~/p/p<i>`, holding [sessions] rows titled `p<i> · s<k>`. */
    private fun project(i: Int, sessions: Int = 2) = DkSessionGroup(
        "~/p/p$i", "p$i", current = false,
        sessions = List(sessions) { k -> DkSession("p$i-s$k", "~/p/p$i", "p$i · s$k") },
    )

    private fun projects(n: Int, sessions: Int = 2) = (1..n).map { project(it, sessions) }

    /** The sidebar in a fixed 640dp column, so how much RECENT shows before it scrolls doesn't depend on the host. */
    private fun ComposeUiTest.showSidebar(model: DesktopModel) {
        setContent { PocketTheme { Box(Modifier.height(640.dp)) { Sidebar(model) } } }
        waitForIdle()
    }

    /** Where the RECENT list holds [key], by its own answer; -1 = the list does not emit that row. */
    private fun ComposeUiTest.rowIndex(key: String): Int =
        onNodeWithTag("sidebar-list").fetchSemanticsNode().config[SemanticsProperties.IndexForKey](key)

    /** A row inside RECENT reading [text] — not its RUNNING or PINNED twin outside the list. */
    private fun ComposeUiTest.listRow(text: String): SemanticsNodeInteraction =
        onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag("sidebar-list"))).onFirst()

    /** The fold's label, scrolled into view first: expanding pushes it below everything it brought in. */
    private fun ComposeUiTest.foldLabel(): String {
        onNodeWithTag("sidebar-list").performScrollToKey("recent-limit")
        waitForIdle()
        return onNodeWithTag("recent-limit").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
    }

    private fun ComposeUiTest.toggleFold() {
        onNodeWithTag("sidebar-list").performScrollToKey("recent-limit")
        waitForIdle()
        onNodeWithTag("recent-limit").performClick()
        waitForIdle()
    }

    /** A session's row in PINNED — the node reading [title] outside the RECENT list. */
    private fun ComposeUiTest.pinRow(title: String): SemanticsNodeInteraction =
        onAllNodes(hasText(title) and !hasAnyAncestor(hasTestTag("sidebar-list"))).onFirst()

    /**
     * The live [RepoDesktopModel] over a demo-mode [PocketRepository] — the #102/#199 model tests' harness. Its listings
     * loop back synchronously, so every step walks the model's real pin, selection, listing and RECENT code.
     */
    private fun demoModel(): Pair<PocketRepository, RepoDesktopModel> {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.paired.value = PairedDaemon(relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred")
        repo.enterDemo()
        return repo to RepoDesktopModel(repo, scope, store = FakeDesktopStore())
    }

    /**
     * The demo lists every folder with the same four session ids, so a listing of [dir] also gets [TARGET] — a session
     * only [dir] holds, as its own daemon would list it — and "the group holding S" can only ever mean [dir].
     */
    private fun PocketRepository.listTarget(dir: String) {
        assertEquals(dir, sessionsDir.value, "the listing on screen is $dir's")
        if (sessions.none { it.sessionId == TARGET }) {
            sessions.add(0, SessionSummary(TARGET, TARGET_TITLE, firstPrompt = "", messageCount = 1, cwd = dir, lastModified = 0L))
        }
    }

    @Test
    fun noProjectsShowTheEmptyLineAndNoFold() = runComposeUiTest {
        showSidebar(RecentModel(emptyList()))

        assertPresent(str(Res.string.sidebar_recent_empty))
        onAllNodes(hasTestTag("sidebar-list")).assertCountEquals(0)
        onAllNodes(hasTestTag("recent-limit")).assertCountEquals(0)
    }

    @Test
    fun fiveProjectsAllShowWithNoFold() = runComposeUiTest {
        showSidebar(RecentModel(projects(5)))

        val headers = (1..5).map { rowIndex("h:~/p/p$it") }
        assertTrue(headers.all { it >= 0 }, "all five projects are in the list: $headers")
        assertEquals(headers.sorted(), headers, "in the model's own order")
        assertEquals(-1, rowIndex("recent-limit"), "five projects need no fold")
        assertFalse(present(str(Res.string.sidebar_recent_show_all)))
    }

    @Test
    fun aSixthProjectWaitsBehindShowAllAndShowLessFoldsItAgain() = runComposeUiTest {
        showSidebar(RecentModel(projects(6)))

        assertTrue((1..5).all { rowIndex("h:~/p/p$it") >= 0 })
        assertEquals(-1, rowIndex("h:~/p/p6"), "the sixth project is not in the list at all")
        assertEquals(rowIndex("s:~/p/p5:p5-s1") + 1, rowIndex("recent-limit"), "the fold closes the project rows")
        assertEquals(str(Res.string.sidebar_recent_show_all), foldLabel())

        toggleFold()
        val headers = (1..6).map { rowIndex("h:~/p/p$it") }
        assertTrue(headers.all { it >= 0 }, "Show all brings the sixth in: $headers")
        assertEquals(headers.sorted(), headers, "…after the fifth: expanding never re-sorts")
        assertEquals(str(Res.string.sidebar_recent_show_less), foldLabel())

        toggleFold()
        assertEquals(-1, rowIndex("h:~/p/p6"), "Show less folds it away again")
        assertEquals(str(Res.string.sidebar_recent_show_all), foldLabel())
    }

    @Test
    fun beyondSixTheWholeRestComesInInItsOwnOrder() = runComposeUiTest {
        showSidebar(RecentModel(projects(8)))
        assertEquals(listOf(-1, -1, -1), (6..8).map { rowIndex("h:~/p/p$it") }, "everything past the fifth waits")

        toggleFold()
        val headers = (1..8).map { rowIndex("h:~/p/p$it") }
        assertTrue(headers.all { it >= 0 }, "Show all is all of them, not one more page: $headers")
        assertEquals(headers.sorted(), headers)
        assertEquals(rowIndex("s:~/p/p8:p8-s1") + 1, rowIndex("recent-limit"), "Show less sits after the last project")
    }

    @Test
    fun openingAPinnedProjectPastTheFoldLiftsItUnfoldsItAndScrollsToIt() = runComposeUiTest {
        val model = RecentModel(projects(8, sessions = 3))
        model.seed.pinProject("~/p/p8", "p8")
        showSidebar(model)
        onNodeWithTag("project-pin:~/p/p8").assertIsDisplayed() // PINNED stays reachable while RECENT is folded
        assertEquals(-1, rowIndex("h:~/p/p8"))

        onNodeWithTag("project-pin:~/p/p8").performClick()
        waitForIdle()
        assertTrue(rowIndex("h:~/p/p8") > rowIndex("h:~/p/p7"), "p8 comes in where it always was — last, not moved up")
        listRow("p8").assertIsDisplayed() // …and the reveal scrolled its header into view
        assertEquals(str(Res.string.sidebar_recent_show_less), foldLabel())

        // Fold p8's own sessions, then the list: the same pin has to undo both.
        onNodeWithTag("sidebar-list").performScrollToKey("h:~/p/p8")
        waitForIdle()
        listRow("p8").performClick()
        waitForIdle()
        assertEquals(-1, rowIndex("s:~/p/p8:p8-s0"), "precondition: p8's sessions are folded")
        toggleFold()
        assertEquals(-1, rowIndex("h:~/p/p8"), "precondition: and the list is folded again")

        onNodeWithTag("project-pin:~/p/p8").performClick()
        waitForIdle()
        assertTrue(rowIndex("s:~/p/p8:p8-s0") >= 0, "the pin opens the project's session list, not just its header")
    }

    @Test
    fun aSessionSelectedPastTheFoldIsRevealedAndShowLessHoldsUntilTheNextNavigation() = runComposeUiTest {
        val seed = SeedDesktopModel()
        val p7 = project(7, sessions = 3).let { g ->
            g.copy(sessions = g.sessions.map { if (it.sessionId == "p7-s1") it.copy(running = true) else it })
        }
        val running = DkRunningRow(seed.machines.first(), DkProject(p7.path, p7.name, running = true), p7.sessions[1])
        val model = RecentModel(projects(6, sessions = 3) + p7 + project(8, sessions = 3), runningRows = listOf(running), seed = seed)
        showSidebar(model)
        // a live turn does not force RECENT open: RUNNING is its own way in
        onNodeWithTag("running:p7-s1").assertIsDisplayed()
        assertEquals(-1, rowIndex("h:~/p/p7"))

        onNodeWithTag("running:p7-s1").performClick()
        waitForIdle()
        assertEquals("p7-s1", model.selectedSessionId)
        assertTrue(rowIndex("s:~/p/p7:p7-s1") >= 0, "selecting a session past the fold lifts it")
        listRow("p7 · s1").assertIsDisplayed()

        toggleFold()
        assertEquals(-1, rowIndex("h:~/p/p7"), "Show less wins over the reveal that opened the list")
        assertEquals("p7-s1", model.selectedSessionId, "…and leaves the selection alone")
        // an open settling blinks the id away and back — that is no navigation, so nothing reopens
        model.selected = null
        waitForIdle()
        model.selected = "p7-s1"
        waitForIdle()
        assertEquals(-1, rowIndex("h:~/p/p7"), "the same selection coming back must not reopen the list")

        model.selectSession(model.sessionGroups[5].sessions[2]) // the NEXT navigation: p6 · s2
        waitForIdle()
        assertTrue(rowIndex("s:~/p/p6:p6-s2") >= 0)
        listRow("p6 · s2").assertIsDisplayed()
    }

    @Test
    fun theRevealCountsNewGroupAndCustomSectionsAndRewoundStaysInReach() = runComposeUiTest {
        val current = DkSessionGroup(
            "~/p/p1", "p1", current = true,
            sessions = listOf(
                DkSession("p1-a0", "~/p/p1", "p1 · a0", group = "g-a"),
                DkSession("p1-a1", "~/p/p1", "p1 · a1", group = "g-a"),
                DkSession("p1-b0", "~/p/p1", "p1 · b0", group = "g-b"),
                DkSession("p1-c0", "~/p/p1", "p1 · c0", group = "g-c"),
                DkSession("p1-u0", "~/p/p1", "p1 · u0"),
            ),
        )
        // past the fold: p6 holds a rewind, whose original leaves the list for the rewound bucket
        val p6 = DkSessionGroup(
            "~/p/p6", "p6", current = false,
            sessions = listOf(
                DkSession("p6-branch", "~/p/p6", "p6 · branch", rewindOf = "p6-orig"),
                DkSession("p6-orig", "~/p/p6", "p6 · original"),
            ),
        )
        val model = RecentModel(
            listOf(current) + (2..5).map { project(it, sessions = 3) } + p6 + project(7, sessions = 30),
            customGroups = listOf(DkGroup("g-a", "A", 0), DkGroup("g-b", "B", 1), DkGroup("g-c", "C", 2)),
            canEditGroups = true,
        )
        showSidebar(model)

        // the current project's own rows: header, "+ New group", then each section with its sessions
        assertEquals(1, rowIndex("ng:~/p/p1"))
        assertEquals(2, rowIndex("gh:~/p/p1:g-a"))
        assertEquals(5, rowIndex("gh:~/p/p1:g-b"))
        assertEquals(rowIndex("s:~/p/p5:p5-s2") + 1, rowIndex("recent-limit"))
        // the rewound bucket follows the fold and still gathers the folded-away p6
        assertEquals(rowIndex("recent-limit") + 1, rowIndex("rewound-header"))
        onNodeWithTag("sidebar-list").performScrollToKey("rewound-header")
        waitForIdle()
        listRow(str(Res.string.rewind_group_rewound, 1)).performClick()
        waitForIdle()
        assertTrue(rowIndex("rw:p6-orig") >= 0, "p6's rewound original stays one click away while p6 is folded")
        assertEquals(-1, rowIndex("h:~/p/p6"))

        // Revealing p7 · s3 has to count every row above it (the rows a hand-counted index skipped), or the
        // scroll lands short: with enough list below it, the row it asked for sits exactly at the top.
        model.selected = "p7-s3"
        waitForIdle()
        val list = onNodeWithTag("sidebar-list").fetchSemanticsNode().boundsInRoot
        val row = listRow("p7 · s3").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(row.top - list.top) < 1f, "the revealed row is scrolled to the list top: row ${row.top}, list ${list.top}")

        // a rewound original's own row is the bucket's, so that is where its reveal lands
        model.selected = "p6-orig"
        waitForIdle()
        listRow("p6 · original").assertIsDisplayed()
    }

    @Test
    fun switchingComputersStartsTheListFoldedAgain() = runComposeUiTest {
        val model = RecentModel(projects(6))
        showSidebar(model)
        toggleFold()
        assertTrue(rowIndex("h:~/p/p6") >= 0)

        model.selectComputer(model.computers[1])
        waitForIdle()
        assertEquals(-1, rowIndex("h:~/p/p6"), "another machine's RECENT starts from the default")
        assertEquals(str(Res.string.sidebar_recent_show_all), foldLabel())
    }

    /**
     * The review's path, on the live model. S sits in P, past the fold; once revealed, the user folds the list away.
     * Re-listing another project and then P blinks the selection to null and back to S with no navigation behind it,
     * so the fold stays. Browsing Q by its project pin clears the selection too — the chat stays on S, the list is Q's —
     * and going back by S's pin selects the SAME id again: that is a navigation, and it must reveal. So must a pin or a
     * session entry that changes nothing the selection could show, S being the selection already.
     */
    @Test
    fun goingBackToASessionRevealsItAgainWhileARelistingBlinkLeavesTheFoldAlone() = runComposeUiTest {
        val (repo, m) = demoModel()
        // RECENT keeps a machine's six most recent projects: P, then five more, leaves P sixth — past the fold
        m.openProject(DkProject(P, "p"))
        repo.listTarget(P)
        (1..5).forEach { m.openProject(DkProject("/demo/q$it", "q$it")) }
        m.pin(m.sessionGroups.first { it.path == P }.sessions.first { it.sessionId == TARGET })
        showSidebar(m)
        assertEquals(-1, rowIndex("h:$P"), "precondition: P waits behind Show all")

        pinRow(TARGET_TITLE).performClick()
        repo.listTarget(P) // the pin made P the listed project
        waitForIdle()
        assertEquals(TARGET, m.selectedSessionId)
        assertTrue(rowIndex("s:$P:$TARGET") >= 0, "S's pin reveals S")
        toggleFold()
        assertEquals(-1, rowIndex("h:$P"), "precondition: folded away again")

        val published = m.projectListReveal
        m.refresh(m.sessionGroups.first { it.path == "/demo/q3" })
        waitForIdle()
        assertEquals(null, m.selectedSessionId, "precondition: the listing moved off S's project")
        m.refresh(m.sessionGroups.first { it.path == P })
        repo.listTarget(P)
        waitForIdle()
        assertEquals(TARGET, m.selectedSessionId)
        assertEquals(published, m.projectListReveal, "a refresh is no navigation: it publishes no reveal")
        assertEquals(-1, rowIndex("h:$P"), "the same selection blinking back must not undo Show less")

        m.openProjectPin(DkProjectPin("/demo/q3", "q3"))
        waitForIdle()
        assertEquals("/demo/q3", repo.sessionsDir.value)
        assertEquals(null, m.selectedSessionId, "S stays open, but nothing in Q's list is selected")
        assertEquals(-1, rowIndex("h:$P"))

        m.jumpPin(0) // ⌘1, S's pin: P is listed again where it was, and S is the selection again
        repo.listTarget(P)
        waitForIdle()
        assertEquals(TARGET, m.selectedSessionId)
        val back = requireNotNull(m.projectListReveal)
        assertEquals(TARGET to "acct-test", back.sessionId to back.accountId, "the pin asks this machine's RECENT for S")
        assertTrue(rowIndex("s:$P:$TARGET") >= 0, "going back to S lifts the fold again")
        listRow(TARGET_TITLE).assertIsDisplayed()

        // S is the selection already: its pin and its session entry (the palette's, RUNNING's) change nothing the
        // selection effect could see, and each still reveals it
        toggleFold()
        pinRow(TARGET_TITLE).performClick()
        waitForIdle()
        assertTrue(rowIndex("s:$P:$TARGET") >= 0, "a click on the selected session's pin reveals it")
        toggleFold()
        m.selectSession(m.sessionGroups.first { it.path == P }.sessions.first { it.sessionId == TARGET })
        waitForIdle()
        assertTrue(rowIndex("s:$P:$TARGET") >= 0, "…and so does selecting it again")
        assertEquals(TARGET, m.selectedSessionId)
    }

    /**
     * A reveal belongs to the machine that asked for it. On A, a project pin waits for A's listing of p9; the user
     * switches to B, whose RECENT already holds the same folder past its fold — B must stay folded, and A's request
     * must not come back when the user does. A request made FOR B from A (another machine's session pin) is B's own,
     * even when it reaches the sidebar before the switch does: A's list is left alone, and B's reveal waits for B's
     * listing, then unfolds and scrolls there.
     */
    @Test
    fun aRevealWaitingWhenTheMachineChangesLeavesTheNextOneFoldedAndIsNotReplayed() = runComposeUiTest {
        val model = TwoMachineModel()
        val a = model.computers[0]
        val b = model.computers[1]
        model.lists[a.accountId] = projects(3) // A has not listed p9 yet
        model.lists[b.accountId] = projects(5) + project(9) // B's list is loaded, the same folder sixth
        showSidebar(model)

        model.request("~/p/p9")
        waitForIdle()
        model.selectComputer(b) // before A's listing lands
        waitForIdle()
        assertEquals(-1, rowIndex("h:~/p/p9"), "A's pending reveal must not unfold B's list")
        assertEquals(str(Res.string.sidebar_recent_show_all), foldLabel())

        model.lists[a.accountId] = projects(5) + project(9) // A's listing lands while B is on screen
        waitForIdle()
        model.selectComputer(a)
        waitForIdle()
        assertEquals(-1, rowIndex("h:~/p/p9"), "…nor replay on A when the user comes back")
        assertEquals(str(Res.string.sidebar_recent_show_all), foldLabel())

        // another machine's session pin, from A: its request names B and reaches the sidebar before the switch lands,
        // while A holds the same folder — and that very session — past its own fold
        model.lists[b.accountId] = projects(5) // B has not listed p9 this time
        model.request("~/p/p9", sessionId = "p9-s1", accountId = b.accountId)
        waitForIdle()
        assertEquals(-1, rowIndex("h:~/p/p9"), "a request for B's list leaves A's alone")
        model.selectComputer(b)
        waitForIdle()
        assertEquals(-1, rowIndex("h:~/p/p9"))
        model.lists[b.accountId] = projects(5) + project(9) // B's listing lands
        waitForIdle()
        assertTrue(rowIndex("s:~/p/p9:p9-s1") >= 0, "B's own request waits for B's listing, then unfolds it")
        listRow("p9 · s1").assertIsDisplayed()
    }

    private companion object {
        /** The go-back case's project past the fold, and the one session only that project holds. */
        const val P = "/demo/p"
        const val TARGET = "p-target"
        const val TARGET_TITLE = "p · the session to go back to"
    }

    /**
     * The fold in both shipped languages, drawn by the real sidebar, with the label on the tightCenter line box.
     * Each state is also written to build/screenshots — an offscreen 2x desktop render, for reading, not device proof.
     */
    @Test
    fun theFoldReadsInChineseAndEnglishOnATightCenteredLine() {
        foldInLocale(Locale.SIMPLIFIED_CHINESE, "zh", showAll = "展开显示", showLess = "收起")
        foldInLocale(Locale.US, "en", showAll = "Show all", showLess = "Show less")
    }

    private fun foldInLocale(locale: Locale, tag: String, showAll: String, showLess: String) {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            // if the locale did not take, the literal checks below would prove the other language
            assertEquals(showAll, str(Res.string.sidebar_recent_show_all))
            assertEquals(showLess, str(Res.string.sidebar_recent_show_less))
            runDesktopComposeUiTest(width = 520, height = 1800) {
                val seed = SeedDesktopModel()
                val p6 = project(6, sessions = 1).let { g -> g.copy(sessions = g.sessions.map { it.copy(running = true) }) }
                val model = RecentModel(
                    projects(5, sessions = 1) + p6 + project(7, sessions = 1),
                    runningRows = listOf(DkRunningRow(seed.machines.first(), DkProject(p6.path, p6.name, running = true), p6.sessions[0])),
                    seed = seed,
                )
                seed.pinProject("~/p/p7", "p7")
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2f)) {
                        PocketTheme { Box(Modifier.height(900.dp).testTag("recent-shot")) { Sidebar(model) } }
                    }
                }
                waitForIdle()

                assertEquals(showAll, foldLabel())
                // at 11sp the tightCenter line box is fixed; a bare fontSize would size it from the font's metrics
                val label = onNode(hasText(showAll) and hasAnyAncestor(hasTestTag("recent-limit")), useUnmergedTree = true)
                    .fetchSemanticsNode()
                assertEquals((tightCenter(11.sp).lineHeight.value * 2f).toInt(), label.size.height, "the label sits on tightCenter's line box")
                shot("sidebar-recent-limit-$tag-folded.png")

                onNodeWithTag("recent-limit").performClick()
                waitForIdle()
                assertEquals(showLess, foldLabel())
                assertTrue(rowIndex("h:~/p/p7") >= 0)
                shot("sidebar-recent-limit-$tag-expanded.png")
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun ComposeUiTest.shot(name: String) {
        val image = onNodeWithTag("recent-shot").captureToImage()
        File("build/screenshots/$name").apply {
            parentFile.mkdirs()
            writeBytes(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
