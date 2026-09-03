package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
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
import kotlin.test.assertTrue

/**
 * Issue #340 — "couldn't open the session — the computer didn't respond".
 *
 * The banner was decided by a BLIND 8s timer. It knew nothing about whether the request could even have
 * reached the computer, so it read identically for a phone with no link and for a daemon that was merely
 * slow — and it blamed the computer either way. (The daemon half of the fix moves the transcript reads
 * out of that deadline; see ConversationOpenAnnounceOrderTest.)
 *
 * This pins the client half: the deadline now branches on the link, and a RESUME gets one silent replay
 * before anyone is told anything.
 *
 * Time is driven by [TestCoroutineScheduler]; the repo runs with no transport, so [PocketRepository
 * .onSendForTest] records what WOULD go on the wire — which is exactly the assertion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionOpenTimeoutTest {

    private class Harness {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(scope).apply {
            paired.value = PairedDaemon(
                relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
            )
            onSendForTest = { sent += it }
        }

        fun opens() = sent.filterIsInstance<OpenSession>()

        /** A link the repo believes is usable — the precondition for the auto-resend branch. */
        fun ready() = repo.apply { phase.value = ConnPhase.Ready }

        /** Run every worker that is due, then let [ms] of the deadline elapse and settle again. */
        fun elapse(ms: Long) {
            scheduler.runCurrent()
            scheduler.advanceTimeBy(ms)
            scheduler.runCurrent()
        }
    }

    /** (1) The happy repair: a Ready link that simply answered slowly. The open is replayed ONCE with no
     *  visible change, the answer lands inside the second budget, and the user never learns any of it
     *  happened — no banner, and the session opens normally. */
    @Test
    fun aSlowResumeOnAReadyLinkIsSilentlyReplayedAndStillOpens() {
        val h = Harness()
        try {
            h.ready()
            assertTrue(h.repo.openSession("/w/proj", resumeId = "sid-a"))
            h.elapse(SESSION_OPEN_TIMEOUT_MS)

            assertEquals(2, h.opens().size, "the deadline must replay the open exactly once")
            assertEquals(
                h.opens()[0], h.opens()[1],
                "the replay must be the SAME request — never re-derived under different flags",
            )
            assertTrue(h.repo.opening.value, "the resend is silent: the spinner keeps running")
            assertFalse(h.repo.openTimedOut.value, "…and nothing may be reported yet")

            h.repo.receiveForTest(SessionLive("convo-a", "/w/proj", "sid-a", executing = false))
            h.elapse(SESSION_OPEN_RETRY_TIMEOUT_MS)

            assertEquals("convo-a", h.repo.convoId.value, "the answer lands and the session opens")
            assertFalse(h.repo.openTimedOut.value, "a repaired open must never show the failure banner")
            assertFalse(h.repo.opening.value)
            assertEquals(2, h.opens().size, "and the second budget must not fire a third open")
        } finally {
            h.scope.cancel()
        }
    }

    /** (2) Both budgets silent on a link that claims Ready: THIS is the one case that has ever deserved
     *  "the computer didn't respond", and it is now the only one that says it. */
    @Test
    fun aResumeThatSurvivesBothBudgetsBlamesTheComputer() {
        val h = Harness()
        try {
            h.ready()
            assertTrue(h.repo.openSession("/w/proj", resumeId = "sid-a"))
            h.elapse(SESSION_OPEN_TIMEOUT_MS)
            assertFalse(h.repo.openTimedOut.value, "the first deadline alone must no longer be a verdict")

            h.elapse(SESSION_OPEN_RETRY_TIMEOUT_MS)

            assertTrue(h.repo.openTimedOut.value)
            assertEquals(OpenFailure.COMPUTER, h.repo.openTimedOutReason.value)
            assertEquals(2, h.opens().size, "one resend, and only one")
            assertFalse(h.repo.opening.value)
            assertFalse(h.repo.switchingSession.value, "#165: a switch that never landed releases the router")
        } finally {
            h.scope.cancel()
        }
    }

    /** (3) A link that is not Ready fails IMMEDIATELY at the first deadline and names the link. Resending
     *  into a link that cannot carry it is pure noise, and four more seconds of spinner buys nothing. */
    @Test
    fun aLinkThatIsNotReadyFailsAtOnceAndNamesTheLink() {
        val h = Harness()
        try {
            h.repo.phase.value = ConnPhase.Reconnecting
            assertTrue(h.repo.openSession("/w/proj", resumeId = "sid-a"))
            h.elapse(SESSION_OPEN_TIMEOUT_MS)

            assertTrue(h.repo.openTimedOut.value, "a down link is decided at the FIRST deadline")
            assertEquals(OpenFailure.LINK, h.repo.openTimedOutReason.value)
            assertEquals(1, h.opens().size, "nothing may be resent into a link that cannot carry it")
        } finally {
            h.scope.cancel()
        }
    }

    /**
     * (4) The sibling boundary the auto-resend must respect. A brand-new open is NOT idempotent on the
     * daemon: SessionRegistry live-matches an incoming open on its resumeId, so a second `resumeId == null`
     * request skips that block entirely, falls to the cold path, and mints a SECOND Conversation — one
     * session on screen, two agents on the computer (the historic "redundant session / fork" failure, a
     * variant of which was fixed in v1.2.0). #340's boundary is opening an EXISTING session anyway.
     */
    @Test
    fun aBrandNewOpenIsNeverAutoResent() {
        val h = Harness()
        try {
            h.ready()
            assertTrue(h.repo.openSession("/w/proj")) // brand new — no resumeId
            h.elapse(SESSION_OPEN_TIMEOUT_MS)

            assertEquals(1, h.opens().size, "a new open must never be replayed: it would open a second session")
            assertTrue(h.opens().single().resumeId == null)
            assertTrue(h.repo.openTimedOut.value, "it fails straight through at the first deadline instead")
            assertEquals(OpenFailure.COMPUTER, h.repo.openTimedOutReason.value)
        } finally {
            h.scope.cancel()
        }
    }

    /** (5) The auto-resend budget belongs to ONE runOpen. A manual retry (the desktop's failure pane) is a
     *  fresh request and gets a fresh one — otherwise the second attempt would be strictly weaker than the
     *  first, which is the opposite of what pressing Retry should mean. */
    @Test
    fun aManualRetryGetsItsOwnAutoResendBudget() {
        val h = Harness()
        try {
            h.ready()
            assertTrue(h.repo.openSession("/w/proj", resumeId = "sid-a"))
            h.elapse(SESSION_OPEN_TIMEOUT_MS)
            h.elapse(SESSION_OPEN_RETRY_TIMEOUT_MS)
            assertTrue(h.repo.openTimedOut.value)
            h.sent.clear()

            assertTrue(h.repo.retryOpen(), "the failed open must be replayable by hand")
            h.elapse(SESSION_OPEN_TIMEOUT_MS)

            assertEquals(2, h.opens().size, "the manual retry's own deadline resends once more")
            assertEquals("sid-a", h.opens().last().resumeId, "…still replaying the same target")
            assertFalse(h.repo.openTimedOut.value, "and the banner cleared when the retry was asked for")
        } finally {
            h.scope.cancel()
        }
    }
}
