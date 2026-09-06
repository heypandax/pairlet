package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.StreamPiece
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #226 — returning from a chat is a navigation boundary, not an invitation to reattach it.
 *
 * Issue #349 extends the same rule to the route one level up: the phone's screen is DERIVED from
 * `sessionsDir`, so a `Sessions` reply that lands after BACK used to drag the user straight back into the
 * project's session list. The list-route cases live in the second half of this file.
 */
class BackNavigationSessionGuardTest {
    /** [startDir] null starts the repo on the DIRECTORY route (nothing listed), which is where every #349
     *  case begins — the phone is on the project list and the user taps into a project. */
    private class Harness(startDir: String? = "/w/proj") {
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
            paired.value = PairedDaemon(
                relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
            )
            sessionsDir.value = startDir
            onSendForTest = { sent += it }
        }

        fun bind(convo: String = "convo-a", sid: String = "sid-a") {
            assertTrue(repo.openSession("/w/proj", resumeId = sid))
            repo.receiveForTest(SessionLive(convo, "/w/proj", sid, executing = false))
            repo.receiveForTest(
                ConvoHistory(
                    convo,
                    listOf(HistoryMessage(ChatRole.USER, "question"), HistoryMessage(ChatRole.ASSISTANT, "answer")),
                    lastSeq = 2,
                    firstSeq = 1,
                ),
            )
        }
    }

    @Test
    fun lateReannounceAfterBackCannotReopenAnEmptyChat() {
        val h = Harness()
        h.bind()

        h.repo.backToBrowse()
        assertNull(h.repo.convoId.value)
        assertEquals("/w/proj", h.repo.sessionsDir.value)
        assertTrue(h.repo.messages.isEmpty(), "the chat transcript is intentionally cleared on back")

        // This is the incident: the just-left session re-announces after CloseSession/ListSessions raced it.
        h.repo.receiveForTest(SessionLive("convo-a2", "/w/proj", "sid-a", executing = true))
        h.repo.receiveForTest(AssistantChunk("convo-a2", 3, StreamPiece.Text("late output")))

        assertNull(h.repo.convoId.value, "a late SessionLive must not route the user back into chat")
        assertTrue(h.repo.messages.isEmpty(), "late frames must not manufacture an empty/partial chat view")
        assertFalse(h.repo.streaming.value)
    }

    @Test
    fun explicitOpenAfterBackLowersTheFence() {
        val h = Harness()
        h.bind()
        h.repo.backToBrowse()
        h.sent.clear()

        assertTrue(h.repo.openSession("/w/proj", resumeId = "sid-a"))
        assertEquals(1, h.sent.filterIsInstance<OpenSession>().size)
        h.repo.receiveForTest(SessionLive("convo-a3", "/w/proj", "sid-a", executing = false))

        assertEquals("convo-a3", h.repo.convoId.value, "a fresh user action may reopen the session normally")
    }

    @Test
    fun allProjectsFromInsideAChatTearsItDownAndDoesNotWedgeReopen() {
        // Regression: backToDirectories() (the switcher sheet's "All projects") raised the #226 fence but
        // left convoId set, so the router kept showing the chat while acceptsSessionLive rejected every
        // reattach — the chat froze and died on reconnect. Leaving a chat here must null convoId like BACK.
        val h = Harness()
        h.bind()
        assertEquals("convo-a", h.repo.convoId.value)

        h.repo.backToDirectories()
        assertNull(h.repo.convoId.value, "leaving a chat via All-projects must unbind the conversation")
        assertNull(h.repo.sessionsDir.value, "All-projects drops all the way to the directory list")
        assertTrue(h.repo.messages.isEmpty(), "the chat transcript is cleared on leave")

        // The fence must not outlive the navigation: an explicit reopen still works (no permanent wedge).
        assertTrue(h.repo.openSession("/w/proj", resumeId = "sid-a"))
        h.repo.receiveForTest(SessionLive("convo-a4", "/w/proj", "sid-a", executing = false))
        assertEquals("convo-a4", h.repo.convoId.value, "reopening after All-projects must bind normally")
    }

    @Test
    fun backingOutOfAnInflightOpenRejectsItsLateAnswer() {
        val h = Harness()
        h.bind()

        assertTrue(h.repo.openSession("/w/proj", resumeId = "sid-b"))
        assertTrue(h.repo.opening.value)
        h.repo.backToBrowse()

        assertFalse(h.repo.opening.value)
        h.repo.receiveForTest(SessionLive("convo-b", "/w/proj", "sid-b", executing = false))
        assertNull(h.repo.convoId.value, "BACK must win even when the daemon finishes opening afterward")
    }

    // ── Issue #349: the SESSIONS LIST route obeys the same navigation boundary ──────────────────────────

    private companion object {
        const val P = "/w/proj"
        const val Q = "/w/other"

        fun summary(id: String, cwd: String) = SessionSummary(
            sessionId = id, title = id, firstPrompt = "", messageCount = 1, cwd = cwd,
            lastModified = 0L, gitBranch = null, agent = AgentKind.CLAUDE,
        )

        fun sessionsOf(dir: String, vararg ids: String) = Sessions(dir, ids.map { summary(it, dir) })
    }

    @Test
    fun lateSessionsReplyAfterBackDoesNotReEnterTheList() {
        // The incident: tap a project → BACK before the daemon answers → the reply wrote sessionsDir
        // unconditionally, and the derived router put the user right back on the list they just left.
        val h = Harness(startDir = null)
        h.repo.listSessions(P)
        h.repo.backToDirectories()

        h.repo.receiveForTest(sessionsOf(P, "s1", "s2"))

        assertNull(h.repo.sessionsDir.value, "a reply that lands after BACK must not re-enter the session list")
        assertTrue(h.repo.sessions.isEmpty(), "…and must not leave its rows behind either")
    }

    @Test
    fun doubleTapThenBackDropsBothReplies() {
        // The row's list is fire-and-forget, so an impatient double tap puts TWO ListSessions on the wire.
        // Backing out must beat both — dropping only the first would bounce on the second.
        val h = Harness(startDir = null)
        h.repo.listSessions(P)
        h.repo.listSessions(P)
        assertEquals(2, h.sent.filterIsInstance<ListSessions>().size, "the double tap really did send two")

        h.repo.backToDirectories()
        h.repo.receiveForTest(sessionsOf(P, "s1"))
        h.repo.receiveForTest(sessionsOf(P, "s1"))

        assertNull(h.repo.sessionsDir.value, "both answers to the abandoned browse are background state")
        assertTrue(h.repo.sessions.isEmpty())
    }

    @Test
    fun refreshOfTheListOnScreenStillApplies() {
        // The fence must NOT break the in-place refreshes: a group rename, an archive, pull-to-refresh, the
        // 12s poll's completion-edge re-list and reconnect restore are all answered by re-pushing Sessions.
        val h = Harness(startDir = null)
        h.repo.listSessions(P)
        h.repo.receiveForTest(sessionsOf(P, "s1"))
        assertEquals(P, h.repo.sessionsDir.value)

        h.repo.receiveForTest(sessionsOf(P, "s1", "s2"))

        assertEquals(P, h.repo.sessionsDir.value, "a refresh of the dir being looked at stays on it")
        assertEquals(listOf("s1", "s2"), h.repo.sessions.map { it.sessionId }, "…and its rows update in place")
    }

    @Test
    fun sessionsForADifferentProjectWhileBrowsingIsDropped() {
        val h = Harness(startDir = null)
        h.repo.listSessions(P)
        h.repo.receiveForTest(sessionsOf(P, "s1"))

        h.repo.receiveForTest(sessionsOf(Q, "q1", "q2"))

        assertEquals(P, h.repo.sessionsDir.value, "another project's list must never repoint the route")
        assertEquals(listOf("s1"), h.repo.sessions.map { it.sessionId }, "…nor swap the rows underneath")
    }

    @Test
    fun backToBrowseReListIsAcceptedButItsLateSecondReplyIsNot() {
        // chat → BACK lands on the list and re-pulls it (wanted). A quick second BACK to All-projects then
        // retires that intent, so the SAME dir's next reply — the one still in flight — must be dropped.
        val h = Harness(startDir = null)
        h.repo.listSessions(P)
        h.repo.receiveForTest(sessionsOf(P, "sid-a"))
        h.bind()
        assertEquals("convo-a", h.repo.convoId.value)

        h.repo.backToBrowse()
        h.repo.receiveForTest(sessionsOf(P, "sid-a", "sid-b"))
        assertEquals(P, h.repo.sessionsDir.value, "the re-list that BACK itself asked for is wanted")
        assertEquals(listOf("sid-a", "sid-b"), h.repo.sessions.map { it.sessionId })

        h.repo.backToDirectories()
        h.repo.receiveForTest(sessionsOf(P, "sid-a", "sid-b", "sid-c"))

        assertNull(h.repo.sessionsDir.value, "the second BACK wins over a reply still on the wire")
        assertTrue(h.repo.sessions.isEmpty())
    }

    @Test
    fun coldClientStillAcceptsAnUnsolicitedList() {
        // The bootstrap seam #226 keeps for the chat route: a client bound to nothing, that has not backed
        // out of anything, has nothing to protect. Removing this would strand cold-start and preview paths.
        val h = Harness(startDir = null)
        h.repo.receiveForTest(sessionsOf(P, "s1"))
        assertEquals(P, h.repo.sessionsDir.value)
    }
}
