package dev.ccpocket.daemon.agent

/**
 * How long the daemon waits for the user's phone verdict on a permission ask / AskUserQuestion / quick-shell
 * command before it auto-denies AND withdraws the card (issue #100).
 *
 * The old 30s was far too short for a product whose whole premise is "you're not at your computer": the agent
 * CLI blocks *indefinitely* on the control_response — a locked phone can't approve a `Write` in 30s any more
 * than it can answer a question, which is exactly why AskUserQuestion already waited 600s. So this window is
 * purely the daemon's own policy, not a CLI constraint. It is therefore unified with that question window and
 * made configurable via `CC_POCKET_ASK_TIMEOUT_SEC` (clamped 30s..24h) for people who are away even longer.
 *
 * Deliberately NOT infinite: an unanswered ask keeps its conversation off the idle-reaper (`hasPending`) and
 * leaves the CLI turn blocked, so it must eventually resolve — a timeout that emits an HONEST deny ("not a
 * user rejection") plus an `AskWithdrawn` is the graceful bound.
 *
 * Issue #201 lets the owner opt out of being denied on their behalf — but it does NOT make the wait infinite,
 * because every reason above still holds. Instead [noAutoDeny] turns the single window into a chain of
 * [NO_AUTO_DENY_WINDOW_MS] leases renewed at most [NO_AUTO_DENY_MAX_RENEWALS] times: each renewal re-emits the
 * card (so the phone gets a fresh push roughly daily), no single lease outlives the coordinator's 24h ceiling,
 * and the whole chain still resolves — the graceful bound simply moves from 10 minutes to 7 days. Bridge and
 * guest asks never participate: their approver isn't the session's owner (see [bridgeMs]).
 */
object ApprovalTimeout {
    /**
     * Floor for BRIDGE-origin sessions (issue #91, restored by #32 after the #100 unification dropped it).
     * A bridge ask's approver is NOT looking at the session: they arrive via push → tap → reattach, and that
     * physical arrival chain needs ~120s regardless of how impatient the owner's `CC_POCKET_ASK_TIMEOUT_SEC`
     * preference is (its clamp floor is 30s — fine for an interactive session where the phone is already in
     * hand, fatal for a bridge ask that would auto-deny before the owner can possibly reach the card).
     */
    const val BRIDGE_MIN_MS = 120_000L

    val ms: Long = fromEnv(System.getenv("CC_POCKET_ASK_TIMEOUT_SEC"))

    /** Pure parse of `CC_POCKET_ASK_TIMEOUT_SEC` (seconds, clamped 30s..24h; default/garbage -> 600s). */
    fun fromEnv(raw: String?): Long =
        raw?.trim()?.toLongOrNull()?.coerceIn(30, 86_400)?.times(1000) ?: 600_000L

    /** Verdict/question window for a bridge-origin session: [ms] but never below [BRIDGE_MIN_MS]. */
    fun bridgeMs(baseMs: Long = ms): Long = baseMs.coerceAtLeast(BRIDGE_MIN_MS)

    // ── issue #201: "wait for my decision" ──────────────────────────────────────────────────────────
    // Runtime preference (persisted in DaemonPrefs, synced at boot and on every SetApprovalPrefs), NOT an
    // env knob: the whole point is that a phone can flip it. Read per-ask, so flipping it bites the NEXT
    // card without relaunching anything. [ms] and its env parsing above stay untouched — this is a second,
    // orthogonal mode, not a wider clamp.
    @Volatile
    var noAutoDeny: Boolean = false

    /**
     * One lease of the no-auto-deny chain. Deliberately EQUAL to the coordinator's absolute ceiling: a
     * renewal restarts that ceiling, so making the lease any longer would just be silently truncated.
     */
    const val NO_AUTO_DENY_WINDOW_MS = 24 * 60 * 60 * 1000L

    /** How many times a no-auto-deny ask renews before it finally times out — 6 renewals ⇒ a 7-day floor. */
    const val NO_AUTO_DENY_MAX_RENEWALS = 6

    // ── issue #220: Full Control expiry duration ──────────────────────────────────────────────────────
    // Runtime mirror of DaemonPrefs.fullControlExpiryMs (persisted, synced at boot and on every
    // SetApprovalPrefs), read by each Conversation when it (re)arms its Full Control expiry clock. 0 = never
    // expires — the owner's manually-entered Full Control persists until they leave it or the session ends.
    // A positive value re-arms the old safety net at the chosen duration, with a perceptible revert. Read at
    // arm time (a mode switch), so flipping it takes effect on the next switch without relaunching anything.
    @Volatile
    var fullControlExpiryMs: Long = 0L
}
