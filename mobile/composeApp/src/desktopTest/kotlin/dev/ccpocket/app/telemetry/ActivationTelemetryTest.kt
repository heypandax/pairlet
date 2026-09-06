package dev.ccpocket.app.telemetry

import dev.ccpocket.app.data.DemoData
import dev.ccpocket.app.data.PocketRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Activation-funnel attribution (issue #278). Both facts pinned here are invisible in the app's own state, so
 * only the telemetry seam can hold them:
 *
 *  - a CAMERA pairing must report source=qr. A scanned QR carries the same `code=` payload a typed code does,
 *    so the origin exists nowhere but the entry point — regress it and every scan silently reads as "code".
 *  - a payload rejected BEFORE any network (unparseable link / unroutable URI) must still report
 *    pair_failed(reason=parse). Untracked, those two branches made a failed attempt look like no attempt.
 *
 * [telemetryTap] observes only what track() already receives (enum event + enum-keyed params), and every
 * assertion below reads a parameter that is a fixed category — never a code, link or message.
 */
class ActivationTelemetryTest {
    private val seen = mutableListOf<Pair<TelEvent, Map<TelKey, Any>>>()

    @BeforeTest fun tap() {
        seen.clear()
        telemetryTap = { e, p -> synchronized(seen) { seen += e to p } }
    }

    @AfterTest fun untap() { telemetryTap = null }

    private fun repo() = PocketRepository(CoroutineScope(Dispatchers.Unconfined))

    /** Unconfined runs doPair's body inline up to its first suspension, and pair_started fires BEFORE the
     *  network call — so the attempt's source is observable without a relay. */
    @Test fun scannedPairCodeReportsQrNotCode() {
        repo().handleIncomingLink("ccpocket://pair?code=123456", fromScan = true)
        val started = synchronized(seen) { seen.first { it.first == TelEvent.PairStarted } }
        assertEquals("qr", started.second[TelKey.Source])
    }

    /** …and the typed-code path is unchanged: same call, no scan origin, still source=code. */
    @Test fun typedCodeStillReportsCode() {
        repo().pairWithCode("123456")
        val started = synchronized(seen) { seen.first { it.first == TelEvent.PairStarted } }
        assertEquals("code", started.second[TelKey.Source])
    }

    @Test fun unparseablePairLinkReportsParseFailure() {
        repo().pair("ccpocket://pair?nothing=here")
        assertTrue(
            synchronized(seen) { seen.any { it.first == TelEvent.PairFailed && it.second[TelKey.Reason] == "parse" } },
            "expected pair_failed(reason=parse), saw $seen",
        )
    }

    @Test fun unroutableUriReportsParseFailure() {
        repo().handleIncomingLink("ccpocket://whatever?x=1")
        assertTrue(
            synchronized(seen) { seen.any { it.first == TelEvent.PairFailed && it.second[TelKey.Reason] == "parse" } },
            "expected pair_failed(reason=parse), saw $seen",
        )
    }

    // ══ where a failure came FROM (issue #342) ═════════════════════════════════════════════════════

    /**
     * Every pair_failed must name its origin. pair_started and paired have always carried one, so without
     * it on the failure the two halves of the same funnel step could not be divided: a route with a high
     * start count and a high failure count was indistinguishable from two unrelated routes.
     *
     * Both PRE-network rejects are covered here because they are the ones with no network context at all to
     * fall back on — the entry point is the only origin that exists.
     */
    @Test fun preNetworkRejectsCarryTheirOrigin() {
        val scanned = mutableListOf<Pair<TelEvent, Map<TelKey, Any>>>()
        repo().pair("ccpocket://pair?nothing=here", fromScan = true)
        synchronized(seen) { scanned += seen }
        val fromCamera = scanned.first { it.first == TelEvent.PairFailed }
        assertEquals("qr-link", fromCamera.second[TelKey.Source], "a scanned reject is not a pasted one")

        synchronized(seen) { seen.clear() }
        repo().handleIncomingLink("ccpocket://whatever?x=1")
        val pasted = synchronized(seen) { seen.first { it.first == TelEvent.PairFailed } }
        assertEquals("link", pasted.second[TelKey.Source])
    }

    /**
     * The attempt ordinal has to be the SAME on the start and on that start's outcome, and has to advance
     * per try. Pinned on pair_started because it is the one event of the pair that fires without a relay —
     * and because the increment used to happen AFTER the track(), which would have shipped every first
     * attempt as ordinal 0 and every retry one behind its own start.
     */
    @Test fun everyPairingAttemptIsNumberedFromOne() {
        val repo = repo()
        repo.pairWithCode("123456")
        repo.pairWithCode("123456")
        val ordinals = synchronized(seen) {
            seen.filter { it.first == TelEvent.PairStarted }.map { it.second[TelKey.Attempt] }
        }
        assertEquals(listOf<Any>(1, 2), ordinals, "attempts number 1, 2, … within one app run")
    }

    // ══ the demo branch (issue #342) ═══════════════════════════════════════════════════════════════

    /**
     * Entering the demo is an activation event of its own — it reaches a working session with no computer
     * and no pairing, so nothing else in the funnel marks it.
     *
     * Fired on the TRANSITION only: the demo's own routes re-enter it, and counting those would inflate one
     * walkthrough into several.
     */
    @Test fun enteringTheDemoIsRecordedOnceNotPerCall() {
        val repo = repo()
        repo.enterDemo()
        repo.enterDemo()
        val entries = synchronized(seen) { seen.count { it.first == TelEvent.DemoEntered } }
        assertEquals(1, entries, "re-entering an already-open demo is not a second walkthrough")
    }

    /**
     * The demo drives the REAL state machine, so its session open and its prompt fire the same two events a
     * paired session does. [TelKey.Demo] is the only thing separating them: without it, browsing the sample
     * data counts as activation. This walks the actual demo path (enterDemo → openSession → sendPrompt)
     * rather than asserting demoTag() in isolation, because the risk is a call site that skips the tag, not
     * the tag itself.
     */
    @Test fun aDemoSessionsOpenAndPromptAreTaggedAsDemo() {
        val repo = repo()
        repo.enterDemo()
        // Explicit because the preview flag (`ccpPreview`, a JVM-wide system property another desktopTest
        // class sets in a companion init) decides whether enterDemo reveals the project list itself or
        // leaves it to the opener's finish callback. Calling it unconditionally drives the same handle()
        // path in both modes and is a no-op for telemetry in the first, which already fired Connected.
        repo.finishDemoConnect()
        repo.openSession(DemoData.LIVE_DIR, resumeId = DemoData.LIVE_SESSION_ID)
        assertNotNull(repo.convoId.value, "the demo answers OpenSession locally — no relay involved")
        repo.sendPrompt("hello")

        val opened = synchronized(seen) { seen.first { it.first == TelEvent.SessionOpened } }
        assertEquals(1, opened.second[TelKey.Demo], "a demo open must not count as a real session_opened")
        val sent = synchronized(seen) { seen.first { it.first == TelEvent.PromptSent } }
        assertEquals(1, sent.second[TelKey.Demo], "a demo prompt must not count as a real prompt_sent")
        // …and the demo's own Connected — the sample project list arriving through handle() — is tagged too
        val connected = synchronized(seen) { seen.first { it.first == TelEvent.Connected } }
        assertEquals(1, connected.second[TelKey.Demo], "the demo's Ready state is not a real connection")
    }
}
