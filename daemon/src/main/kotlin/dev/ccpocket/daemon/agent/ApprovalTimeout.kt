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
    val ms: Long =
        System.getenv("CC_POCKET_ASK_TIMEOUT_SEC")?.trim()?.toLongOrNull()?.coerceIn(30, 86_400)?.times(1000)
            ?: 600_000L
}
