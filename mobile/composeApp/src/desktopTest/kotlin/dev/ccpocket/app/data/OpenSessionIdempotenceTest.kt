package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #235 — opening a session must be idempotent per target.
 *
 * Incident shape: the desktop sidebar gives no instant feedback, so a user whose open is slow clicks the
 * row again. Every click ran the full [PocketRepository.openSession] body — CloseSession on the current
 * conversation, then a fresh OpenSession — so the second click tore down the session the first had just
 * landed and rebuilt it as a new conversation (observed in the daemon log ~600ms after the first landed).
 * The transcript blanked, the composer reset, and it read as "clicking does nothing / it crashed".
 *
 * The guard has to be SYNCHRONOUS and it has to live in the repo: both clicks arrive before any coroutine
 * runs, and the same entry point is behind the desktop sidebar, the pins, the command palette and the
 * phone's session list — a per-surface disable would leave the others open.
 *
 * Every test drives the repo with no transport: [PocketRepository.onSendForTest] records what WOULD go on
 * the wire, which is exactly the assertion (`send` itself is a no-op with no connection).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenSessionIdempotenceTest {

    private class Harness {
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
            paired.value = PairedDaemon(
                relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
            )
            onSendForTest = { sent += it }
        }

        fun opens() = sent.filterIsInstance<OpenSession>()
        fun closes() = sent.filterIsInstance<CloseSession>()

        /** Land the daemon's answer for an in-flight open, transcript and all — as a real open resolves. */
        fun land(convo: String, sid: String?, wd: String) {
            repo.receiveForTest(SessionLive(convo, wd, sid, executing = false))
            repo.receiveForTest(
                ConvoHistory(
                    convo,
                    listOf(HistoryMessage(ChatRole.USER, "q"), HistoryMessage(ChatRole.ASSISTANT, "a")),
                    lastSeq = 2,
                    firstSeq = 1,
                ),
            )
        }
    }

    /** (1) The double-click itself: two clicks on the same row before the daemon answers = ONE OpenSession. */
    @Test
    fun aSecondClickOnTheSameInFlightTargetIsRefused() {
        val h = Harness()
        assertTrue(h.repo.openSession("/w/proj", resumeId = "sid-a"), "the first click must be sent")
        assertFalse(h.repo.openSession("/w/proj", resumeId = "sid-a"), "the second click must be refused")
        assertFalse(h.repo.openSession("/w/proj", resumeId = "sid-a"), "…and so must a third")

        assertEquals(1, h.opens().size, "one target, one OpenSession")
        assertTrue(h.repo.opening.value, "the first open is still in flight")
    }

    /** …and the in-flight refusal is keyed on the TARGET, not on the whole request: a second click is a
     *  duplicate however that surface spells the workdir or whatever title the list carries by then. */
    @Test
    fun theInFlightRefusalIsKeyedOnTheTargetNotTheRequestShape() {
        val h = Harness()
        assertTrue(h.repo.openSession("/Users/tester/proj", resumeId = "sid-a", title = "Refactor auth"))
        assertFalse(
            h.repo.openSession("~/proj/", resumeId = "sid-a", title = "Refactor auth module"),
            "same session + same directory = the same in-flight open",
        )
        assertEquals(1, h.opens().size)
    }

    /** (2) The destructive half: re-clicking the session that is ALREADY open must send nothing at all —
     *  no CloseSession, no OpenSession — and must not disturb the transcript on screen. */
    @Test
    fun reclickingTheOpenSessionSendsNothingAndKeepsTheTranscript() {
        val h = Harness()
        h.repo.openSession("/w/proj", resumeId = "sid-a")
        h.land("convo-a", "sid-a", "/w/proj")
        val transcript = h.repo.messages.toList()
        val epoch = h.repo.composerEpoch.value
        h.sent.clear()

        assertFalse(h.repo.openSession("/w/proj", resumeId = "sid-a"), "the open session's own row is a no-op")

        assertTrue(h.sent.isEmpty(), "nothing may go on the wire: ${h.sent}")
        assertEquals("convo-a", h.repo.convoId.value, "the live conversation must survive")
        assertEquals(transcript, h.repo.messages.toList(), "the transcript must not be cleared")
        assertEquals(epoch, h.repo.composerEpoch.value, "the composer must not be re-keyed out from under the user")
        assertFalse(h.repo.opening.value, "and no phantom open may be shown")
    }

    /** The same no-op holds when the click spells the workdir differently — the sidebar row carries the
     *  daemon's absolute cwd, a pin or a palette entry can carry the tilde'd (or trailing-slash) form. */
    @Test
    fun theAlreadyOpenGuardIsWorkdirShapeTolerant() {
        val h = Harness()
        h.repo.openSession("/Users/tester/proj", resumeId = "sid-a")
        h.land("convo-a", "sid-a", "/Users/tester/proj")
        h.sent.clear()

        assertFalse(h.repo.openSession("~/proj/", resumeId = "sid-a"), "same directory, different spelling")
        assertTrue(h.sent.isEmpty(), "nothing may go on the wire: ${h.sent}")

        // …but a same-id row of a genuinely DIFFERENT directory is a real request
        assertTrue(h.repo.openSession("/Users/tester/other", resumeId = "sid-a"))
        assertEquals(1, h.opens().size)
    }

    /** (3) Switching is untouched: a different target while the first is still in flight still goes out,
     *  latest-wins, and the loser's late answer is still refused by the #219 identity guard. */
    @Test
    fun aDifferentTargetStillOpensWhileTheFirstIsInFlight() {
        val h = Harness()
        h.repo.openSession("/w/proj", resumeId = "sid-a")
        assertTrue(h.repo.openSession("/w/proj", resumeId = "sid-b"), "switching to another session must fire")

        assertEquals(listOf("sid-a", "sid-b"), h.opens().map { it.resumeId })

        // the abandoned target answers late — it must not hijack the view from the target we now want
        h.repo.receiveForTest(SessionLive("convo-a", "/w/proj", "sid-a", executing = false))
        assertEquals(null, h.repo.convoId.value, "the stale open's answer must not land")

        h.land("convo-b", "sid-b", "/w/proj")
        assertEquals("convo-b", h.repo.convoId.value, "the target the user actually wants lands")
    }

    /** The claim is synchronous but the state-clearing worker is launched. If the previous conversation
     *  re-announces in that gap, it must not clear the new target's claim or its opening feedback. */
    @Test
    fun thePreviousChatCannotAnswerANewClaimBeforeItsWorkerRuns() {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        try {
            val repo = PocketRepository(scope).apply {
                convoId.value = "convo-a"
                sessionKey.value = "sid-a"
                workdir.value = "/w/proj"
            }

            assertTrue(repo.openSession("/w/proj", resumeId = "sid-b"))
            // The StandardTestDispatcher deliberately keeps runOpen queued here.
            repo.receiveForTest(SessionLive("convo-a", "/w/proj", "sid-a", executing = false))

            assertEquals("convo-a", repo.convoId.value, "the previous view remains until the switch worker runs")
            assertTrue(repo.opening.value, "the new target must still own the opening transition")
            assertFalse(repo.openSession("/w/proj", resumeId = "sid-b"), "its synchronous claim must survive")
        } finally {
            scope.cancel()
        }
    }

    /** A brand-new session has no id to match on — it must never fold into "already open" on workdir alone,
     *  or ⌘N in the project you are already sitting in would stop working forever. */
    @Test
    fun brandNewSessionsAreNeverRefusedOnWorkdirAlone() {
        val h = Harness()
        h.repo.openSession("/w/proj") // brand new
        h.land("convo-new", "sid-new", "/w/proj")

        assertTrue(h.repo.openSession("/w/proj"), "a SECOND new session in the same project is a real request")
        assertEquals(2, h.opens().size)
        assertTrue(h.opens().all { it.resumeId == null })
    }

    /** …though two brand-new opens fired in the same frame are still the double-click, and still collapse. */
    @Test
    fun aDoubleClickOnNewSessionStillSendsOne() {
        val h = Harness()
        assertTrue(h.repo.openSession("/w/proj"))
        assertFalse(h.repo.openSession("/w/proj"), "the in-flight new-session claim covers the second click")
        assertEquals(1, h.opens().size)
    }

    /** A refused open (the daemon says no) releases the claim, so the row stays clickable — the guard must
     *  never be able to strand a session behind a permanent no-op. */
    @Test
    fun aRefusedOpenReleasesTheClaim() {
        val h = Harness()
        h.repo.openSession("/w/proj", resumeId = "sid-a")
        h.repo.receiveForTest(dev.ccpocket.protocol.PocketError("bad_workdir", "no such directory"))
        assertFalse(h.repo.opening.value)

        assertTrue(h.repo.openSession("/w/proj", resumeId = "sid-a"), "the same target must be retryable after a failure")
        assertEquals(2, h.opens().size)
    }

    /** Disconnecting drops the claim with the machine — otherwise the row a user was opening when the link
     *  died would refuse every click after the reconnect. */
    @Test
    fun disconnectReleasesTheClaim() {
        val h = Harness()
        h.repo.openSession("/w/proj", resumeId = "sid-a")
        h.repo.disconnect()

        assertTrue(h.repo.openSession("/w/proj", resumeId = "sid-a"))
        assertEquals(2, h.opens().size)
    }

    /** The desktop failure pane's retry replays the SAME request — same session, same workdir — rather
     *  than re-deriving one from whatever the UI is pointing at now. */
    @Test
    fun retryReplaysTheExactRequestThatFailed() {
        val h = Harness()
        h.repo.openSession("/w/proj", resumeId = "sid-a", title = "Refactor auth")
        h.repo.receiveForTest(dev.ccpocket.protocol.PocketError("open_failed", "nope"))
        h.sent.clear()

        assertTrue(h.repo.retryOpen(), "the failed open must be replayable")
        assertEquals(1, h.opens().size)
        assertEquals("/w/proj", h.opens().single().workdir)
        assertEquals("sid-a", h.opens().single().resumeId)
        assertEquals("Refactor auth", h.repo.chatTitle.value, "the retry keeps naming the same session")
    }

    /** Disconnect is a hard ownership boundary. If the open worker has only been queued when the user
     *  leaves the computer, it must not wake later, repopulate the cleared session state, and enqueue an
     *  OpenSession into the reusable transport outbox for whichever computer connects next. */
    @Test
    fun disconnectCancelsAnOpenWhoseWorkerHasNotRunYet() {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        try {
            val sent = mutableListOf<Frame>()
            val repo = PocketRepository(scope).apply { onSendForTest = { sent += it } }

            assertTrue(repo.openSession("/w/machine-a", resumeId = "sid-a"))
            repo.disconnect()
            scheduler.runCurrent() // deliberately give every pre-disconnect queued worker a chance to leak

            assertTrue(sent.none { it is OpenSession }, "a disconnected open must never reach a later link: $sent")
            assertFalse(repo.opening.value)
            assertFalse(repo.switchingSession.value)
            assertFalse(repo.openTimedOut.value)
            assertNull(repo.sessionKey.value, "the abandoned target must not resurrect after disconnect")
        } finally {
            scope.cancel()
        }
    }

    /** Once an OpenSession is already on the wire, disconnect still wins ownership of the view. The old
     *  link may have queued its SessionLive just before teardown; that answer cannot reopen chat on the
     *  connect screen, and no stale opening/timeout affordance may carry into another computer. */
    @Test
    fun disconnectRetiresADispatchedOpenAndRejectsItsLateAnswer() {
        val h = Harness()
        assertTrue(h.repo.openSession("/w/machine-a"))
        assertTrue(h.repo.opening.value)

        h.repo.disconnect()
        assertFalse(h.repo.opening.value)
        assertFalse(h.repo.switchingSession.value)
        assertFalse(h.repo.openTimedOut.value)

        h.repo.receiveForTest(SessionLive("late-convo", "/w/machine-a", "late-sid", executing = false))
        assertNull(h.repo.convoId.value, "the disconnected link's answer must remain background state")
    }

    /** A timed-out open is per-link feedback. Disconnecting (including a cold machine switch) must not
     *  show the old computer's "didn't respond" banner over the next computer's connect screen. */
    @Test
    fun disconnectClearsOpenTimeoutFeedback() {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        try {
            val repo = PocketRepository(scope)
            assertTrue(repo.openSession("/w/machine-a", resumeId = "sid-a"))
            scheduler.runCurrent()
            scheduler.advanceTimeBy(8_000)
            scheduler.runCurrent()
            assertTrue(repo.openTimedOut.value)

            repo.disconnect()

            assertFalse(repo.openTimedOut.value)
            assertFalse(repo.opening.value)
        } finally {
            scope.cancel()
        }
    }

    /** Hot fleet switching has the same boundary as a cold disconnect: the outgoing primary becomes a
     *  headless satellite, so a queued UI open must die instead of opening a ghost chat on that satellite. */
    @Test
    fun demotionCancelsAnOpenWhoseWorkerHasNotRunYet() {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        try {
            val sent = mutableListOf<Frame>()
            val repo = PocketRepository(scope).apply { onSendForTest = { sent += it } }

            assertTrue(repo.openSession("/w/machine-a", resumeId = "sid-a"))
            repo.demoteToSatellite()
            scheduler.runCurrent()

            assertTrue(sent.none { it is OpenSession }, "a demoted repo must not open an unseen ghost chat: $sent")
            assertNull(repo.sessionKey.value)
            assertNull(repo.convoId.value)
            assertFalse(repo.opening.value)
        } finally {
            scope.cancel()
        }
    }

    /** The switcher wrapper must preserve the repo's idempotence too. A repeated tap arrives after the first
     *  worker has nulled convoId; recomputing the hold from convoId alone used to drop switchingSession and
     *  bounce the router back to the list while the one legitimate OpenSession was still in flight. */
    @Test
    fun repeatedSwitcherTapKeepsTheInflightChatRouteHeld() {
        val h = Harness()
        h.repo.convoId.value = "convo-a"
        h.repo.sessionKey.value = "sid-a"
        h.repo.workdir.value = "/w/a"
        val target = SessionSwitcherItem(
            dirKey = "/w/b",
            sessionId = "sid-b",
            title = "target",
            project = "b",
            running = true,
            executing = false,
            current = false,
            unseen = false,
        )

        h.repo.switchToSession(target)
        assertTrue(h.repo.switchingSession.value)
        h.repo.switchToSession(target)

        assertEquals(1, h.opens().size, "the duplicate stays wire-idempotent")
        assertTrue(h.repo.switchingSession.value, "the duplicate must not release the first tap's route hold")
    }
}
