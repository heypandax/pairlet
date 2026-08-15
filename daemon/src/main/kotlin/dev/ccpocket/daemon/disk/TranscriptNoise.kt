package dev.ccpocket.daemon.disk

/**
 * Recognizes harness-injected NOISE in a user turn — plumbing, not conversation — so it can be
 * dropped from what the phone replays — every backend's replay path ([TranscriptReplay] for claude,
 * ZCodeTranscriptReplay for zcode) shares this one judgement — and, for the shapes it is safe to erase
 * on disk, from desktop-resume transcripts ([TranscriptPatcher]). Three shapes:
 *
 *  - standalone injected block(s) — `<task-notification>` (background-shell lifecycle notices) and
 *    `<system-reminder>` (the CLI's own nudges: TodoWrite reminders, skill listings, …). Issue #253:
 *    a turn that is nothing but reminder blocks used to render under a "你" header as if the user
 *    had typed it.
 *  - the bare "Continue from where you left off." resume nudge the harness injects on continuation.
 *  - skill/command injections (issue #126): the SKILL.md payload the CLI writes on a Skill load
 *    ("Base directory for this skill: …") and slash-command wrapper records (`<command-name>` /
 *    `<command-message>` / `<local-command-stdout>`). These normally carry root-level isMeta:true
 *    (filtered upstream) — the fingerprints here are the fallback for isMeta-less variants.
 *
 * A turn is only noise when nothing but plumbing remains — a `<task-notification>` or a
 * `<system-reminder>` PREPENDED to real text keeps the turn (the CLI routinely prepends reminders to
 * genuine input, so stripping by prefix would eat what the user actually wrote), and the injection
 * fingerprints only match at the very OPENING of the text, so genuine input (even one quoting these
 * phrases mid-message) is never eaten.
 */
object TranscriptNoise {
    const val TN_OPEN = "<task-notification>"
    private const val TN_CLOSE = "</task-notification>"
    const val SR_OPEN = "<system-reminder>"
    private const val SR_CLOSE = "</system-reminder>"
    private const val RESUME_PROMPT = "Continue from where you left off."

    /** open/close pairs whose standalone blocks are pure harness plumbing (issue #253). */
    private val TASK_NOTIFICATION = listOf(TN_OPEN to TN_CLOSE)
    private val SYSTEM_REMINDER = listOf(SR_OPEN to SR_CLOSE)
    private val INJECTED_BLOCKS = TASK_NOTIFICATION + SYSTEM_REMINDER

    /** The fixed opening the CLI prepends to a skill load's SKILL.md injection (issue #126). */
    const val SKILL_INJECTION_PREFIX = "Base directory for this skill:"

    /** Openings of the CLI's slash-command wrapper records — written as user rows, never typed. */
    val COMMAND_WRAPPER_TAGS = listOf("<command-name>", "<command-message>", "<local-command-stdout>")

    /** True when a user turn's text is pure plumbing: injected block(s) (task-notification and/or
     *  system-reminder), the resume nudge, or a skill/command injection payload. Backend-agnostic —
     *  every replay path (claude, zcode) routes its user rows through this one judgement. */
    fun isNoiseUserText(text: String?): Boolean {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return false
        return s == RESUME_PROMPT || isPureBlocks(s, INJECTED_BLOCKS) || isInjectedHarnessText(s)
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
    fun isPureTaskNotification(text: String?): Boolean = isPureBlocks(text, TASK_NOTIFICATION)

    /** True when the user turn is nothing but one or more `<system-reminder>` blocks — a CLI nudge
     *  (TodoWrite reminder, skill listing, …) that was never typed by the user (issue #253). */
    fun isPureSystemReminder(text: String?): Boolean = isPureBlocks(text, SYSTEM_REMINDER)

    /** True when [text] consists solely of complete blocks drawn from [tags] (open/close pairs) with
     *  nothing but whitespace between or after them. An unterminated block keeps the turn: without a
     *  closing tag we cannot tell where the injection ends and real input begins. */
    private fun isPureBlocks(text: String?, tags: List<Pair<String, String>>): Boolean {
        var s = (text ?: return false).trim()
        var matched = false
        while (true) {
            val tag = tags.firstOrNull { s.startsWith(it.first) } ?: break
            val end = s.indexOf(tag.second)
            if (end < 0) return false // unterminated — keep the turn to be safe
            s = s.substring(end + tag.second.length).trim()
            matched = true
        }
        return matched && s.isEmpty()
    }
}
