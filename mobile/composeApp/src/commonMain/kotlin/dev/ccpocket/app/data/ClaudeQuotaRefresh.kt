package dev.ccpocket.app.data

/** What asked for a quota refresh. Each carries its own admission rule — see [ClaudeQuotaRefreshPolicy]. */
enum class QuotaRefreshTrigger {
    /** The daemon link went (re)ready. Always refreshes: a new machine has a different account. */
    CONNECTED,

    /** The keep-alive tick while the window lives. Refreshes only once [ClaudeQuotaRefreshPolicy.PERIOD_MS] old. */
    PERIODIC,

    /** The window regained focus. Refreshes only if the snapshot is already [ClaudeQuotaRefreshPolicy.FOCUS_STALE_MS] old —
     *  alt-tabbing back and forth must not become a request generator. */
    FOCUSED,

    /** A turn finished somewhere on this machine. DEBOUNCED, never immediate. */
    TURN_DONE,

    /** The user pressed refresh. Always goes out, and is the only trigger that sets `forceRefresh`. */
    MANUAL,
}

/**
 * Decides WHEN to ask the daemon for the Claude subscription allowance. Deliberately a plain object with
 * an injected clock and an injected `fetch` — no coroutines, no Compose, no repository reference — so the
 * whole rule set is unit-testable by advancing a `var` (and so it cannot be tested through Compose's test
 * clock, which has a history of hanging `waitForIdle` when a `delay` loop is fast-forwarded).
 *
 * The driver's only job is to call [event] on real events and [tick] on a timer (any granularity finer
 * than the debounce; the desktop uses ~10s). All timing decisions live here.
 *
 * Five rules, in the order they were specified:
 *  1. CONNECTED → immediately.
 *  2. PERIODIC → every [PERIOD_MS] for as long as the window is alive.
 *  3. FOCUSED → only when the snapshot is older than [FOCUS_STALE_MS].
 *  4. TURN_DONE → [TURN_DEBOUNCE_MS] after the LAST one (a trailing debounce: during a burst of turns
 *     nothing goes out, and one request follows the quiet). Fired by [tick].
 *  5. MANUAL → always, with `forceRefresh` so the daemon's own 60s cache is bypassed too.
 *
 * Failure handling is a NON-rule on purpose: a failed fetch schedules no retry. The bar keeps showing the
 * last good numbers and the next trigger will come along anyway — an auto-retry loop against an offline
 * daemon is how a quiet indicator turns into a request storm.
 *
 * In-flight de-duplication is the other half of that: concurrent triggers collapse into ONE outbound
 * request. [replied] clears the latch; [IN_FLIGHT_TIMEOUT_MS] is the backstop for a reply that never
 * arrives (an old daemon drops the frame entirely and would otherwise wedge the latch forever).
 */
class ClaudeQuotaRefreshPolicy(
    private val now: () -> Long,
    /** Send the request. `forceRefresh` is true only for [QuotaRefreshTrigger.MANUAL]. */
    private val fetch: (forceRefresh: Boolean) -> Unit,
) {
    private var lastRequestAt: Long? = null
    private var inFlightSince: Long? = null
    private var turnDebounceDueAt: Long? = null

    /** Snapshot age basis: the moment the daemon's numbers were actually true, as reported back. Null
     *  until the first reply lands, which makes every age comparison "stale" — the right default. */
    private var snapshotAt: Long? = null

    /** Record the age basis for the staleness rules. Called with [dev.ccpocket.protocol.ClaudeQuota.fetchedAt]
     *  (0/null for a failure reply, which correctly leaves the previous basis in place). */
    fun snapshotFetchedAt(fetchedAt: Long?) {
        if (fetchedAt != null && fetchedAt > 0) snapshotAt = fetchedAt
    }

    /** A reply landed (success OR failure) — the in-flight latch opens either way. */
    fun replied() {
        inFlightSince = null
    }

    /** Reset for a different machine: the previous account's snapshot ages nothing here. */
    fun reset() {
        lastRequestAt = null
        inFlightSince = null
        turnDebounceDueAt = null
        snapshotAt = null
    }

    /** Handle a real event. Returns true when a request actually went out (for tests and logging). */
    fun event(trigger: QuotaRefreshTrigger): Boolean {
        val t = now()
        return when (trigger) {
            QuotaRefreshTrigger.CONNECTED -> request(force = false)
            QuotaRefreshTrigger.MANUAL -> {
                // the one trigger that bypasses BOTH caches: the user is standing there asking again
                // precisely because they doubt what is on screen
                inFlightSince = null
                request(force = true)
            }
            QuotaRefreshTrigger.FOCUSED -> if (ageAtLeast(t, FOCUS_STALE_MS)) request(force = false) else false
            QuotaRefreshTrigger.PERIODIC -> if (ageAtLeast(t, PERIOD_MS)) request(force = false) else false
            QuotaRefreshTrigger.TURN_DONE -> {
                // trailing debounce: each turn pushes the due moment out, so a busy stretch produces one
                // request after it ends rather than one per turn
                turnDebounceDueAt = t + TURN_DEBOUNCE_MS
                false
            }
        }
    }

    /**
     * The timer pump: fires the two time-driven rules — the pending turn-done debounce and the periodic
     * refresh. Safe to call as often as the driver likes; the rules gate themselves.
     */
    fun tick(): Boolean {
        val t = now()
        val due = turnDebounceDueAt
        if (due != null && t >= due) {
            turnDebounceDueAt = null
            if (request(force = false)) return true
            // the request was suppressed (one already in flight) — do NOT re-arm: the in-flight request
            // will bring numbers that already include this turn
            return false
        }
        return event(QuotaRefreshTrigger.PERIODIC)
    }

    /** Age of the freshest thing we have, against [minMs]. No snapshot and no request yet = always stale. */
    private fun ageAtLeast(t: Long, minMs: Long): Boolean {
        // the basis is the LATER of "when the numbers were true" and "when we last asked": a failed fetch
        // must still count as an attempt, or a broken link would re-ask on every single tick
        val basis = maxOf(snapshotAt ?: Long.MIN_VALUE, lastRequestAt ?: Long.MIN_VALUE)
        if (basis == Long.MIN_VALUE) return true
        return t - basis >= minMs
    }

    private fun request(force: Boolean): Boolean {
        val t = now()
        val inFlight = inFlightSince
        // a reply that never came must not wedge the latch shut forever — an old daemon simply drops the
        // unknown frame, so "no reply" is a normal outcome here, not an anomaly
        if (inFlight != null && t - inFlight < IN_FLIGHT_TIMEOUT_MS) return false
        inFlightSince = t
        lastRequestAt = t
        fetch(force)
        return true
    }

    companion object {
        /** Rule 2: the keep-alive cadence while a window is open. */
        const val PERIOD_MS = 5 * 60_000L

        /** Rule 3: how stale the snapshot must be for a re-focus to be worth a request. */
        const val FOCUS_STALE_MS = 2 * 60_000L

        /** Rule 4: quiet period after the last finished turn. */
        const val TURN_DEBOUNCE_MS = 60_000L

        /** Backstop for a request whose reply never arrives (old daemon / dropped link). */
        const val IN_FLIGHT_TIMEOUT_MS = 30_000L
    }
}
