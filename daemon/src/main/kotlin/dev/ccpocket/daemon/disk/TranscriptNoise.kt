package dev.ccpocket.daemon.disk

/**
 * Recognizes harness-injected NOISE in a user turn — plumbing, not conversation — so it can be
 * dropped both from what the phone replays ([TranscriptReplay]) and from desktop-resume transcripts
 * ([TranscriptPatcher]). Three shapes:
 *
 *  - standalone `<task-notification>` block(s): background-shell lifecycle notices.
 *  - the bare "Continue from where you left off." resume nudge the harness injects on continuation.
 *  - skill/command injections (issue #126): the SKILL.md payload the CLI writes on a Skill load
 *    ("Base directory for this skill: …") and slash-command wrapper records (`<command-name>` /
 *    `<command-message>` / `<local-command-stdout>`). These normally carry root-level isMeta:true
 *    (filtered upstream) — the fingerprints here are the fallback for isMeta-less variants.
 *
 * A turn is only noise when nothing but plumbing remains — a `<task-notification>` (or a
 * `<system-reminder>`) PREPENDED to real text keeps the turn, and the injection fingerprints only
 * match at the very OPENING of the text, so genuine input (even one quoting these phrases
 * mid-message) is never eaten.
 */
object TranscriptNoise {
    const val TN_OPEN = "<task-notification>"
    private const val TN_CLOSE = "</task-notification>"
    private const val RESUME_PROMPT = "Continue from where you left off."

    /** The fixed opening the CLI prepends to a skill load's SKILL.md injection (issue #126). */
    const val SKILL_INJECTION_PREFIX = "Base directory for this skill:"

    /** Openings of the CLI's slash-command wrapper records — written as user rows, never typed. */
    val COMMAND_WRAPPER_TAGS = listOf("<command-name>", "<command-message>", "<local-command-stdout>")

    /** True when a user turn's text is pure plumbing: task-notification block(s), the resume nudge,
     *  or a skill/command injection payload. */
    fun isNoiseUserText(text: String?): Boolean {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return false
        return s == RESUME_PROMPT || isPureTaskNotification(s) || isInjectedHarnessText(s)
    }

    /** Fingerprint fallback for harness injections that should carry isMeta:true but may not on older
     *  CLIs (issue #126): the SKILL.md payload and slash-command wrapper records. Deliberately
     *  conservative — only an OPENING match counts, so a user genuinely mentioning these phrases
     *  mid-message is never eaten. */
    fun isInjectedHarnessText(text: String?): Boolean {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return false
        return s.startsWith(SKILL_INJECTION_PREFIX) || COMMAND_WRAPPER_TAGS.any(s::startsWith)
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
