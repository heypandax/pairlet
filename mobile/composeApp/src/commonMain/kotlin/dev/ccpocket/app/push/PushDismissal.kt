package dev.ccpocket.app.push

/** Issue #389: clear this session's already-delivered notifications from the tray. Called when the user is
 *  demonstrably looking at the session (chat opened, prompt sent, ask answered), so a stale "turn complete" or
 *  "needs approval" alert does not keep sitting on the lock screen. Best-effort and idempotent; never throws. */
expect object PushDismissal {
    fun dismiss(sessionId: String)
}
