package dev.ccpocket.app.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pairing failure classifier: exception → [PairFailure] → funnel string (issue #278 batch 2).
 *
 * Two separate guarantees live here, and they pull in opposite directions:
 *
 *  - the CATEGORY has to be right, because the pairing screen now picks which actionable card to show from
 *    it — telling a user with a stale code to check their VPN sends them to fix the wrong thing; and
 *  - the STRING has to be unchanged, because `pairFailReason` was refactored to derive it from the category
 *    rather than compute it alongside. `ActivationTelemetryTest` pins the two branches that reach a live
 *    repository; this file pins the mapping itself, including the branches a test cannot reach without a
 *    relay (a real redeem rejection, a real transport failure).
 */
class PairFailureTest {

    /** Stand-ins whose CLASS NAMES are the ones the Ktor engines actually throw. */
    private class UnknownHostException(message: String?) : Exception(message)
    private class SocketTimeoutException(message: String?) : Exception(message)
    private class HttpRequestTimeoutException(message: String?) : Exception(message)

    private fun wire(t: Throwable) = classifyPairFailure(t).wireReason(t)

    // ══ the categories ═════════════════════════════════════════════════════════════════════════════

    @Test
    fun aRefusedCodeIsItsOwnCategory() {
        // the message Pairing.resolveCode raises when the relay will not exchange the six digits
        val t = IllegalStateException("invalid or expired code")
        assertEquals(PairFailure.CODE, classifyPairFailure(t))
        assertEquals("code", wire(t))
    }

    @Test
    fun aRejectedRedeemIsItsOwnCategory() {
        val t = IllegalStateException("pairing failed: {\"error\":\"nope\"}")
        assertEquals(PairFailure.REDEEM, classifyPairFailure(t))
        assertEquals("redeem", wire(t))
    }

    @Test
    fun transportFailuresClassifyAsNetworkAcrossEngineVocabularies() {
        // OkHttp/CIO name it in the TYPE…
        for (t in listOf(
            UnknownHostException("relay.invalid"),
            SocketTimeoutException(null),
            HttpRequestTimeoutException("Request timeout has expired"),
        )) {
            assertEquals(PairFailure.NETWORK, classifyPairFailure(t), "${t::class.simpleName} is a transport failure")
        }
        // …Darwin names it in the MESSAGE, under a generic type. Both channels must be read, or the same
        // lost connection classifies differently per platform — which is precisely the drift the funnel's
        // conn_failed numbers already suffered from once.
        for (message in listOf(
            "Could not connect to the server.",
            "A server with the specified hostname could not be found.",
            "The Internet connection appears to be offline.",
            "The network connection was lost.",
        )) {
            assertEquals(
                PairFailure.NETWORK, classifyPairFailure(IllegalStateException(message)),
                "Darwin phrasing not recognised as transport: $message",
            )
        }
    }

    @Test
    fun anUnrecognisedFailureStaysUnrecognisedRatherThanBecomingNetwork() {
        // blaming the user's network for something we did not recognise is a guess dressed as a diagnosis
        val t = IllegalArgumentException("boom")
        assertEquals(PairFailure.OTHER, classifyPairFailure(t))
    }

    @Test
    fun aServiceAnswerBeatsATransportGuess() {
        // a relay that answers "invalid or expired code" over a flaky link is a CODE problem: the answer
        // arrived, so the transport plainly worked
        val t = UnknownHostException("invalid or expired code")
        assertEquals(PairFailure.CODE, classifyPairFailure(t))
        assertEquals("code", wire(t))
    }

    // ══ the wire strings (unchanged by construction — this is the proof) ════════════════════════════

    @Test
    fun theFunnelStringsAreExactlyTheOnesAlreadyRecorded() {
        assertEquals("parse", PairFailure.PARSE.wireReason(null))
        assertEquals("code", PairFailure.CODE.wireReason(null))
        assertEquals("redeem", PairFailure.REDEEM.wireReason(null))
        // NETWORK and OTHER were previously indistinguishable and BOTH fell back to the exception's class
        // name. Preserving that is what makes splitting them a no-op on the wire.
        assertEquals("UnknownHostException", PairFailure.NETWORK.wireReason(UnknownHostException(null)))
        assertEquals("IllegalStateException", PairFailure.OTHER.wireReason(IllegalStateException("x")))
        assertEquals("error", PairFailure.OTHER.wireReason(null))
    }

    /**
     * The privacy rule that made this a class rather than a message in the first place: a redeem failure's
     * text carries the relay's raw response body, and a transport failure's text can carry a hostname.
     * Neither may reach the funnel.
     */
    @Test
    fun noWireStringEverCarriesTheExceptionMessage() {
        val secret = "sk-live-should-never-leave-the-device"
        for (t in listOf<Throwable>(
            IllegalStateException("pairing failed: $secret"),
            IllegalStateException("invalid or expired code · $secret"),
            UnknownHostException(secret),
            IllegalArgumentException(secret),
        )) {
            val reason = wire(t)
            assertFalse(reason.contains(secret), "the funnel reason leaked the message: $reason")
            assertTrue(reason.isNotBlank())
        }
    }

    @Test
    fun everyCategoryProducesANonBlankReason() {
        PairFailure.entries.forEach {
            assertTrue(it.wireReason(null).isNotBlank(), "$it must name itself on the wire")
        }
    }
}
