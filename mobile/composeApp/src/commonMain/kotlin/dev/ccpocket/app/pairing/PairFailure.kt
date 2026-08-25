package dev.ccpocket.app.pairing

/**
 * Why a pairing attempt did not complete — as a CLASS, never as the exception's text.
 *
 * This is the single source of truth for two readers that used to disagree:
 *
 *  - the funnel's `pair_failed(reason=…)` parameter (issue #278), which must stay a fixed category because a
 *    redeem failure's message carries the relay's raw response body, and that has no business leaving the
 *    device; and
 *  - the pairing screen's status region, which needs the same class to decide WHICH actionable card to show.
 *    Before this existed the UI had only one status sentence, so every failure — a stale code, a blocked
 *    network, a scanned URL that was never a pairing link — offered the user the same nothing.
 *
 * The two readers are wired together by [classifyPairFailure] → [PairFailure.wireReason]: the wire string is
 * DERIVED from the class rather than computed alongside it, so a new UI case can never silently rename a
 * telemetry category (`ActivationTelemetryTest` and `PairFailureTest` pin both halves).
 */
enum class PairFailure {
    /** The payload was rejected before any network: not a pairing link / an unroutable `ccpocket://` URI. */
    PARSE,

    /** The relay refused the six digits — mistyped, or the computer has stopped showing that code. */
    CODE,

    /** The redeem itself was rejected. Same user action as [CODE]: go get a fresh code. */
    REDEEM,

    /** The phone could not reach the pairing service at all — DNS, timeout, TLS, VPN/proxy. */
    NETWORK,

    /** Anything unclassified. Deliberately NOT folded into [NETWORK]: blaming the user's network for a
     *  failure we did not recognise is a guess dressed as a diagnosis. */
    OTHER,
}

/**
 * The funnel category for this class.
 *
 * PARSE/CODE/REDEEM keep the exact strings the funnel has recorded since #278 — changing one silently splits
 * a metric across two names. NETWORK and OTHER keep the historical fallback (the exception's *class* name,
 * never its message), which is why [throwable] is needed: those two were previously indistinguishable and
 * both landed on that fallback, so preserving it is what makes this refactor a no-op on the wire.
 */
fun PairFailure.wireReason(throwable: Throwable?): String = when (this) {
    PairFailure.PARSE -> "parse"
    PairFailure.CODE -> "code"
    PairFailure.REDEEM -> "redeem"
    PairFailure.NETWORK, PairFailure.OTHER -> throwable?.let { it::class.simpleName } ?: "error"
}

/**
 * Substrings that mark a transport failure across all three Ktor engines (Darwin, OkHttp, CIO).
 *
 * Matched against the exception's CLASS NAME and its message, because the engines disagree on which of the
 * two carries the fact: Darwin reports a `POSIXException`-flavoured message under a generic type, OkHttp
 * throws `UnknownHostException`/`SocketTimeoutException`, CIO throws Ktor's own timeout types. Matching only
 * one of the two channels would classify the same lost connection differently per platform.
 */
private val NETWORK_MARKERS = listOf(
    "unresolvedaddress", "unknownhost", "sockettimeout", "connecttimeout", "requesttimeout",
    "connectexception", "socketexception", "ioexception", "sslexception", "sslhandshake",
    "network is unreachable", "network is down", "connection refused", "connection reset",
    "software caused connection abort", "no route to host", "timed out", "timeout",
    "could not connect", "failed to connect", "handshake",
    // Darwin's own phrasings, which arrive as prose under a generic exception type
    "hostname could not be found", "internet connection appears to be offline",
    "network connection was lost", "not connected to the internet",
)

/**
 * Classify [t] thrown by a pairing attempt.
 *
 * Message matching comes FIRST and is exact-substring on the two strings the pairing code itself raises
 * ([Pairing.resolveCode] / [Pairing.redeem]), so a service answer always beats a transport guess: a relay
 * that replies "invalid or expired code" over a flaky link is a code problem, and telling that user to check
 * their VPN sends them to fix the wrong thing.
 */
fun classifyPairFailure(t: Throwable): PairFailure {
    val message = t.message
    if (message != null) {
        if (message.contains("invalid or expired code")) return PairFailure.CODE
        if (message.contains("pairing failed")) return PairFailure.REDEEM
    }
    val haystack = ((t::class.simpleName ?: "") + " " + (message ?: "")).lowercase()
    if (NETWORK_MARKERS.any { it in haystack }) return PairFailure.NETWORK
    return PairFailure.OTHER
}
