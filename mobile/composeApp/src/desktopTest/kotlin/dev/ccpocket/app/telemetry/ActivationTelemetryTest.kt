package dev.ccpocket.app.telemetry

import dev.ccpocket.app.data.PocketRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
