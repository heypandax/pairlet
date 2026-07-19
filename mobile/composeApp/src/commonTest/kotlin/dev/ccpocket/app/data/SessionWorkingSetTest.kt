package dev.ccpocket.app.data

import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DirectoryEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * issue #165 — the cross-project switcher's data rules, all pure: the MRU (order + cap + the "previous
 * session is always the first row" guarantee the jump-back gesture rests on), running/recent dedupe,
 * the finished-while-away signal and its clearing, and the otherCount the top-bar chip counts with.
 */
class SessionWorkingSetTest {

    private fun entry(sid: String, at: Long, dir: String = "/w/$sid", title: String = "T-$sid") =
        WorkingSetEntry(dirKey = dir, sessionId = sid, title = title, project = projectLabelOf(dir), at = at)

    private fun dir(
        path: String,
        vararg live: ActiveSession,
        open: Boolean = true,
        busy: Boolean = false,
    ) = DirectoryEntry(
        path = path, name = path.substringAfterLast('/'), isDir = true,
        open = open, busy = busy, activeSessions = live.toList(),
        activeSessionId = live.firstOrNull()?.sessionId, activeSessionTitle = live.firstOrNull()?.title,
    )

    // ── MRU ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun mruKeepsNewestFirstAndDedupesOnReopen() {
        var l = emptyList<WorkingSetEntry>()
        l = mruTouch(l, entry("a", 1))
        l = mruTouch(l, entry("b", 2))
        l = mruTouch(l, entry("c", 3))
        assertEquals(listOf("c", "b", "a"), l.map { it.sessionId })

        l = mruTouch(l, entry("a", 4, title = "renamed")) // reopening moves it to the head, no duplicate
        assertEquals(listOf("a", "c", "b"), l.map { it.sessionId })
        assertEquals(1, l.count { it.sessionId == "a" })
        assertEquals("renamed", l.first().title, "a re-touch refreshes the labels")
    }

    @Test
    fun mruIsCappedAndDropsTheOldest() {
        var l = emptyList<WorkingSetEntry>()
        repeat(WORKING_SET_MAX + 3) { i -> l = mruTouch(l, entry("s$i", i.toLong())) }
        assertEquals(WORKING_SET_MAX, l.size)
        assertEquals("s${WORKING_SET_MAX + 2}", l.first().sessionId)
        assertTrue(l.none { it.sessionId == "s0" }, "the oldest entries fall off the end")
    }

    @Test
    fun previousSessionIsAlwaysTheFirstRecentRow() {
        // open a → b → c: from inside c, "the one before" is b, and it must lead `recent`
        var l = emptyList<WorkingSetEntry>()
        listOf("a", "b", "c").forEachIndexed { i, s -> l = mruTouch(l, entry(s, i.toLong())) }
        val ws = buildWorkingSet(running = emptyList(), mru = l, currentSessionId = "c")
        assertEquals("b", ws.recent.first().sessionId)
        assertEquals(listOf("b", "a"), ws.recent.map { it.sessionId })
        assertEquals("c", ws.current?.sessionId)
        assertTrue(ws.current?.current == true)
    }

    // ── running / recent ──────────────────────────────────────────────────────────────────────────

    @Test
    fun runningRowsFollowTheHomeScreenActiveRule() {
        val dirs = listOf(
            dir("/w/proj-a", ActiveSession("s1", "Fix parser", executing = true, agent = AgentKind.CLAUDE)),
            dir("/w/proj-b", ActiveSession("s2", "Docs", busy = true), ActiveSession("s3", "Tests")),
            dir("/w/idle", open = false),                                     // not live at all → absent
            DirectoryEntry(path = "/w/legacy", name = "legacy", isDir = true, // older daemon: legacy fields only
                open = true, activeSessionId = "s9", activeSessionTitle = "Old"),
        )
        val r = runningSessions(dirs)
        assertEquals(listOf("s1", "s2", "s3", "s9"), r.map { it.sessionId })
        assertEquals("proj-b", r.first { it.sessionId == "s2" }.project)
        assertTrue(r.first { it.sessionId == "s1" }.executing)
        assertNull(r.first { it.sessionId == "s9" }.agent, "an older daemon's live row has no known backend")
    }

    @Test
    fun runningWinsOverRecentForTheSameSession() {
        val mru = listOf(entry("s1", 9, dir = "/w/proj-a"), entry("s5", 8, dir = "/w/proj-e"))
        val dirs = listOf(dir("/w/proj-a", ActiveSession("s1", "Fix parser", executing = true)))
        val ws = buildWorkingSet(runningSessions(dirs), mru, currentSessionId = null)

        assertEquals(listOf("s1"), ws.running.map { it.sessionId })
        assertEquals(listOf("s5"), ws.recent.map { it.sessionId }, "a running session must not repeat under recent")
        assertEquals(2, ws.otherCount)
        assertEquals("T-s1", ws.running.first().title, "the title the user saw beats the daemon's derived one")
    }

    @Test
    fun otherCountAndListsExcludeTheCurrentSession() {
        val mru = listOf(entry("cur", 9, dir = "/w/proj-a"), entry("s2", 8), entry("s3", 7))
        val dirs = listOf(dir("/w/proj-a", ActiveSession("cur", "Current", executing = true), ActiveSession("s4", "Other")))
        val ws = buildWorkingSet(runningSessions(dirs), mru, currentSessionId = "cur", currentDirKey = "/w/proj-a", currentTitle = "Current")

        assertTrue(ws.running.none { it.sessionId == "cur" })
        assertTrue(ws.recent.none { it.sessionId == "cur" })
        assertEquals(3, ws.otherCount, "s4 (running) + s2 + s3 (recent) — never the session on screen")
        assertEquals("Current", ws.current?.title)
        assertTrue(ws.current?.running == true, "the current session still reports its live state")
    }

    @Test
    fun currentSessionIsSynthesizedWhenItIsNeitherRunningNorRemembered() {
        val ws = buildWorkingSet(emptyList(), emptyList(), currentSessionId = "brand-new", currentDirKey = "/w/proj-z")
        assertEquals("proj-z", ws.current?.project)
        assertEquals(0, ws.otherCount)
        assertFalse(ws.attention)
    }

    // ── attention (finished while away) ────────────────────────────────────────────────────────────

    @Test
    fun sessionThatFinishedWhileAwayIsMarkedUnseenUntilOpened() {
        val was = setOf("s1", "s2")
        val unseen = markFinishedAway(prevWorking = was, nowWorking = setOf("s1"), currentSessionId = "cur", unseen = emptySet())
        assertEquals(setOf("s2"), unseen)

        val mru = listOf(entry("s2", 5))
        val ws = buildWorkingSet(emptyList(), mru, currentSessionId = "cur", unseen = unseen)
        assertTrue(ws.recent.single().unseen)
        assertTrue(ws.attention, "a session that finished while you were elsewhere lights the chip badge")

        // opening it clears the mark (the repo removes the id) — the badge goes out
        val after = buildWorkingSet(emptyList(), mru, currentSessionId = "cur", unseen = unseen - "s2")
        assertFalse(after.recent.single().unseen)
        assertFalse(after.attention)
    }

    @Test
    fun theSessionOnScreenIsNeverMarkedAndStillRunningOnesAreLeftAlone() {
        val unseen = markFinishedAway(setOf("cur", "s1"), nowWorking = setOf("s1"), currentSessionId = "cur", unseen = emptySet())
        assertTrue(unseen.isEmpty(), "you watched the current session finish; a still-running one hasn't finished")
    }

    @Test
    fun approvalsSeamLightsAttentionForAnotherSession() {
        // today this arrives empty (asks are bound to the connection that opened the convo); when the
        // daemon broadcasts them account-wide the same badge lights with no other change
        val ws = buildWorkingSet(emptyList(), listOf(entry("s2", 1)), currentSessionId = "cur", approvals = setOf("s2"))
        assertTrue(ws.attention)
        assertFalse(buildWorkingSet(emptyList(), emptyList(), currentSessionId = "cur", approvals = setOf("cur")).attention)
    }

    // ── persistence ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun encodeDecodeRoundTripsAndSurvivesGarbage() {
        val list = listOf(
            WorkingSetEntry("/w/proj a", "s1", "Fix\tthe\nparser", "proj a", 1700L, AgentKind.CODEX),
            WorkingSetEntry("C:\\dev\\app", "s2", "Windows session", "app", 1800L, null),
        )
        val back = decodeWorkingSet(encodeWorkingSet(list))
        assertEquals(2, back.size)
        assertEquals("Fix the parser", back[0].title, "tabs/newlines in a prompt-derived title are flattened, not lost")
        assertEquals(AgentKind.CODEX, back[0].agent)
        assertEquals("C:\\dev\\app", back[1].dirKey)
        assertNull(back[1].agent)

        assertTrue(decodeWorkingSet(null).isEmpty())
        assertTrue(decodeWorkingSet("garbage\nalso\tgarbage").isEmpty(), "a malformed store must not crash the app")
    }

    @Test
    fun projectLabelHandlesBothSeparatorsAndTrailingSlashes() {
        assertEquals("cc-pocket", projectLabelOf("/Users/x/proj/cc-pocket"))
        assertEquals("cc-pocket", projectLabelOf("/Users/x/proj/cc-pocket/"))
        assertEquals("app", projectLabelOf("C:\\dev\\app"))
        assertEquals("/", projectLabelOf("/"))
    }
}
