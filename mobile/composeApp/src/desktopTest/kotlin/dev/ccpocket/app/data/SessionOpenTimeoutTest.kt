package dev.ccpocket.app.data

import dev.ccpocket.observability.*
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.assertNotNull
import kotlin.test.assertNull

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.HistoryComplete
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
 * Since SLOW-LINK-RESILIENCE 3.2 a Ready link branches once more, on whether ANYTHING came down it after the
 * request went out. A link carrying the daemon's other answers is the #340 world above, unchanged. A link that
 * stayed silent is not replayed into — on a link that delivers in order, a replay can only queue behind the
 * frame already in flight — and, still unanswered at 20s, the open names the LINK.
 *
 * Time is driven by [TestCoroutineScheduler]; the repo runs with no transport, so [PocketRepository
 * .onSendForTest] records what WOULD go on the wire — which is exactly the assertion — and
 * [PocketRepository.downlinkFramesOverride] stands in for the two transports' decrypted-frame counters.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionOpenTimeoutTest {
    private val diagnostics = mutableListOf<DiagnosticRecord>()
    @BeforeTest fun installDiagnostics() {
        Diagnostics.install(DiagnosticReporter(Component.DESKTOP, Environment.STAGING, "test", DiagnosticSink { diagnostics.add(it) }, successSamplePercent = 100))
    }
    @AfterTest fun closeDiagnostics() { Diagnostics.install(null) }

    private class Harness {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val sent = mutableListOf<Frame>()

        /** Transport frames decrypted so far, as the two legs count them (SLOW-LINK-RESILIENCE 3.2). */
        var downlink = 0L

        /** A Ready link in the foreground is BUSY: the daemon answers the app's approval poll every few seconds,
         *  so a deadline that looks always finds frames newer than its request. That is the world the #340
         *  cases were written in, so it stays the default; [silentLink] switches it off, leaving [downlink]. */
        private var busy = true

        val repo = PocketRepository(scope).apply {
            paired.value = PairedDaemon(
                relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
            )
            onSendForTest = { sent += it }
            downlinkFramesOverride = { downlink + if (busy) scheduler.currentTime / POLL_ANSWER_EVERY_MS else 0L }
        }

        fun opens() = sent.filterIsInstance<OpenSession>()

        /** A link the repo believes is usable — the precondition for the auto-resend branch. */
        fun ready() = repo.apply { phase.value = ConnPhase.Ready }

        /** From here on nothing comes down the link unless a test moves [downlink] by hand. */
        fun silentLink() { busy = false }

        /** Run every worker that is due, then let [ms] of the deadline elapse and settle again. */
        fun elapse(ms: Long) {
            scheduler.runCurrent()
            scheduler.advanceTimeBy(ms)
            scheduler.runCurrent()
        }
    }

    @Test
    fun lateVisibleHistoryRecoversOnceWithoutRewritingTheLayoutTimeout() {
        val h = Harness()
        try {
            h.ready()
            h.repo.receiveForTest(DaemonInfo(supportedAgents = listOf("claude"), supportsDiagnostics = true))
            h.repo.openSession("/w/proj", resumeId = "sid-a")
            h.elapse(1)
            val context = assertNotNull(h.opens().single().diagnostic)
            h.repo.receiveForTest(SessionLive("convo-a", "/w/proj", "sid-a", diagnostic = context))
            h.repo.receiveForTest(HistoryComplete("convo-a", context, quality = "not_required"))
            val token = assertNotNull(h.repo.historyLayoutToken.value)

            // Server completion alone cannot claim that the foreground UI displayed the history.
            h.elapse(15_000)
            val timeout = diagnostics.single { it.path == ErrorPath.SESSION_OPEN }
            assertEquals(Outcome.TIMEOUT, timeout.outcome)
            assertEquals(Stage.LAYOUT, timeout.stage)

            h.repo.onHistoryLaidOut(token, hasVisibleContent = true)
            h.repo.onHistoryLaidOut(token, hasVisibleContent = true)
            assertNull(h.repo.historyLayoutToken.value)
            val records = diagnostics.filter { it.path == ErrorPath.SESSION_OPEN }
            assertEquals(listOf(Outcome.TIMEOUT, Outcome.RECOVERED), records.map { it.outcome })
            assertEquals(timeout.traceId, records.last().traceId)
            assertEquals(1, h.opens().size, "layout recovery must not restart the agent")
        } finally {
            h.scope.cancel()
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
            val record = diagnostics.single { it.path == ErrorPath.SESSION_OPEN }
            // A legacy Live receipt confirms binding, but carries no history/layout completion proof.
            assertEquals(Outcome.UNKNOWN, record.outcome)
            assertEquals(ResultQuality.UNKNOWN, record.metrics.resultQuality)
            assertEquals(1, record.attempt)
            assertNotNull(record.traceId)
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
            val record = diagnostics.single { it.path == ErrorPath.SESSION_OPEN }
            assertEquals(Outcome.TIMEOUT, record.outcome)
            assertEquals(Stage.ATTACH, record.stage)
            assertEquals(1, record.attempt)
            assertNotNull(record.traceId)
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
            assertEquals(Stage.CONNECT, diagnostics.single { it.path == ErrorPath.SESSION_OPEN }.stage)
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

    // ── SLOW-LINK-RESILIENCE 3.2: a Ready link that has gone silent ──────────────────────────────────

    /** (6) Nothing came down the link after the request: no replay at the first deadline — it could only queue
     *  behind the frame already in flight, and make the daemon send the same window twice — and, still
     *  unanswered at 20s in all, the open blames the LINK without having spent its auto-resend. */
    @Test
    fun aSilentLinkIsNeverReplayedIntoAndNamesTheLinkAtTwentySeconds() {
        val h = Harness()
        try {
            h.ready()
            h.silentLink()
            h.downlink = 41 // what the link carried BEFORE the request proves nothing about this one
            assertTrue(h.repo.openSession("/w/proj", resumeId = "sid-a"))
            h.elapse(SESSION_OPEN_TIMEOUT_MS)

            assertEquals(1, h.opens().size, "no OpenSession may be replayed into a silent link")
            assertTrue(h.repo.opening.value, "the answer may still be in flight: the spinner keeps running")
            assertFalse(h.repo.openTimedOut.value, "…and nothing is reported at the first deadline")

            h.elapse(SESSION_OPEN_SILENT_LINK_TIMEOUT_MS - 1)
            assertFalse(h.repo.openTimedOut.value, "20s in all, not a moment less")
            h.elapse(1)

            assertTrue(h.repo.openTimedOut.value)
            assertEquals(OpenFailure.LINK, h.repo.openTimedOutReason.value, "a silent link is the link's failure, not the computer's")
            assertEquals(1, h.opens().size, "still never replayed")
            val record = diagnostics.single { it.path == ErrorPath.SESSION_OPEN }
            assertEquals(Outcome.TIMEOUT, record.outcome)
            assertEquals(Stage.CONNECT, record.stage)
            assertEquals(0, record.attempt, "retried = false: the auto-resend was never spent")
            assertFalse(h.repo.opening.value)
            assertFalse(h.repo.switchingSession.value, "#165: a switch that never landed releases the router")
        } finally {
            h.scope.cancel()
        }
    }

    /** (7) The case the silent budget exists for: the answer was only stuck behind a big frame, and lands
     *  before 20s. The session opens as if nothing happened — and the daemon was asked exactly once. */
    @Test
    fun aSilentLinkThatDeliversLateStillOpensWithoutAReplay() {
        val h = Harness()
        try {
            h.ready()
            h.silentLink()
            assertTrue(h.repo.openSession("/w/proj", resumeId = "sid-a"))
            h.elapse(SESSION_OPEN_TIMEOUT_MS)
            h.elapse(7_000) // 15s in: the frame in flight lands, and the answer queued behind it with it

            h.repo.receiveForTest(SessionLive("convo-a", "/w/proj", "sid-a", executing = false))
            h.elapse(SESSION_OPEN_SILENT_LINK_TIMEOUT_MS)

            assertEquals("convo-a", h.repo.convoId.value, "the late answer opens the session")
            assertFalse(h.repo.openTimedOut.value, "an answer inside the budget never shows the banner")
            assertFalse(h.repo.opening.value)
            assertEquals(1, h.opens().size, "and the daemon was never asked twice")
        } finally {
            h.scope.cancel()
        }
    }

    /** (8) The silent-link check comes before the brand-new rule: a new open on a silent link was never
     *  replayable anyway (see (4)), and it now waits the same 20s and names the LINK instead of blaming the
     *  computer at 8s. */
    @Test
    fun aBrandNewOpenOnASilentLinkWaitsTheSameBudgetAndNamesTheLink() {
        val h = Harness()
        try {
            h.ready()
            h.silentLink()
            assertTrue(h.repo.openSession("/w/proj")) // brand new — no resumeId
            h.elapse(SESSION_OPEN_TIMEOUT_MS)
            assertFalse(h.repo.openTimedOut.value, "not decided at the first deadline on a silent link")

            h.elapse(SESSION_OPEN_SILENT_LINK_TIMEOUT_MS)
            assertTrue(h.repo.openTimedOut.value)
            assertEquals(OpenFailure.LINK, h.repo.openTimedOutReason.value)
            assertEquals(1, h.opens().size, "a new open is never replayed, silent link or not")
        } finally {
            h.scope.cancel()
        }
    }

    /** (9) The other side of the line: ONE frame of anything after the request is enough to show the link is
     *  carrying downlink, and then the #340 path runs exactly as before — one replay of the same request, 4s
     *  more, then the computer is named, with the auto-resend spent. */
    @Test
    fun oneDownlinkFrameAfterTheRequestKeepsTheReplayPathExactlyAsBefore() {
        val h = Harness()
        try {
            h.ready()
            h.silentLink()
            assertTrue(h.repo.openSession("/w/proj", resumeId = "sid-a"))
            h.elapse(1) // the open is on the wire, its baseline taken
            h.downlink++ // the daemon answers something else — a poll, a list — just not this open
            h.elapse(SESSION_OPEN_TIMEOUT_MS - 1)

            assertEquals(2, h.opens().size, "a link carrying downlink gets the #340 replay")
            assertEquals(h.opens()[0], h.opens()[1], "…of the SAME request")
            assertTrue(h.repo.opening.value)
            assertFalse(h.repo.openTimedOut.value)

            h.elapse(SESSION_OPEN_RETRY_TIMEOUT_MS)
            assertTrue(h.repo.openTimedOut.value)
            assertEquals(OpenFailure.COMPUTER, h.repo.openTimedOutReason.value)
            assertEquals(2, h.opens().size, "one resend, and only one")
            val record = diagnostics.single { it.path == ErrorPath.SESSION_OPEN }
            assertEquals(Stage.ATTACH, record.stage)
            assertEquals(1, record.attempt, "retried = true")
        } finally {
            h.scope.cancel()
        }
    }

    private companion object {
        /** The foreground's approval poll cadence (App.kt) — one answer per poll on a busy link. */
        const val POLL_ANSWER_EVERY_MS = 3_000L
    }
}
