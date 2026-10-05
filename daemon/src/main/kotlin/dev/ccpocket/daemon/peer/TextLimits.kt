package dev.ccpocket.daemon.peer

/**
 * The text-field bounds shared beyond ReviewRequest: a label or one-line field is REJECTED when it is
 * too long or carries control characters, never truncated. Split verbatim out of `ReviewLimits` so
 * #367 remote execution can keep using them once the review code is gone.
 */
object TextLimits {
    const val MAX_LABEL = 120

    /** A machine-readable refusal, or null when [value] is acceptable. */
    fun text(value: String?, max: Int, field: String): String? = when {
        value == null -> null
        value.length > max -> "$field is too long (${value.length} > $max)"
        value.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } ->
            "$field contains unsafe control characters"
        else -> null
    }

    /** Fields rendered on one terminal line must not carry line breaks, tabs or ANSI controls. */
    fun singleLine(value: String?, max: Int, field: String): String? {
        text(value, max, field)?.let { return it }
        return if (value?.any(Char::isISOControl) == true) "$field must be a single line" else null
    }
}
