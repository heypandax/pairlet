package dev.ccpocket.daemon.disk

/**
 * Recognizes harness-injected NOISE in a user turn — plumbing, not conversation — so it can be
 * dropped both from what the phone replays ([TranscriptReplay]) and from desktop-resume transcripts
 * ([TranscriptPatcher]). Two shapes:
 *
 *  - standalone `<task-notification>` block(s): background-shell lifecycle notices.
 *  - the bare "Continue from where you left off." resume nudge the harness injects on continuation.
 *
 * A turn is only noise when nothing but plumbing remains — a `<task-notification>` (or a
 * `<system-reminder>`) PREPENDED to real text keeps the turn, so genuine input is never eaten.
 */
object TranscriptNoise {
    const val TN_OPEN = "<task-notification>"
    private const val TN_CLOSE = "</task-notification>"
    private const val RESUME_PROMPT = "Continue from where you left off."

    /** True when a user turn's text is pure plumbing: task-notification block(s) or the resume nudge. */
    fun isNoiseUserText(text: String?): Boolean {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return false
        return s == RESUME_PROMPT || isPureTaskNotification(s)
    }

    /** True when the user turn is nothing but one or more `<task-notification>` blocks (no real text). */
    fun isPureTaskNotification(text: String?): Boolean {
        var s = (text ?: return false).trim()
        if (!s.startsWith(TN_OPEN)) return false
        while (s.startsWith(TN_OPEN)) {
            val end = s.indexOf(TN_CLOSE)
            if (end < 0) return false // unterminated — keep the turn to be safe
            s = s.substring(end + TN_CLOSE.length).trim()
        }
        return s.isEmpty()
    }
}
