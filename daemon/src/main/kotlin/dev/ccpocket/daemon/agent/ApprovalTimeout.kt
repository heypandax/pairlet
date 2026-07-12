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
}
