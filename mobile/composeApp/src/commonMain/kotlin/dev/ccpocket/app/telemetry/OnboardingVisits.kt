package dev.ccpocket.app.telemetry

import dev.ccpocket.app.secure.SecureStore

/**
 * Which showing of the install guide this is for the install (issue #342): `first`, `return` (the second)
 * or `return_many`. `onboarding_shown` alone could not tell someone who saw the guide once and left from
 * someone who went to their computer and came back — the two call for different fixes.
 *
 * Only a small counter is kept on the device; nothing identifying is stored or sent.
 */
object OnboardingVisits {
    private const val KEY = "tel_onboarding_visits"

    /** Count this showing and name it. Never throws: a storage failure reads as a first visit. */
    fun next(read: () -> String? = { SecureStore.getString(KEY) }, write: (String) -> Unit = { SecureStore.putString(KEY, it) }): String {
        val seen = runCatching { read()?.toIntOrNull() }.getOrNull()?.coerceIn(0, MAX) ?: 0
        val now = (seen + 1).coerceAtMost(MAX)
        runCatching { write(now.toString()) }
        return label(now)
    }

    internal fun label(visit: Int): String = when {
        visit <= 1 -> "first"
        visit == 2 -> "return"
        else -> "return_many"
    }

    private const val MAX = 3 // the label saturates there; no reason to keep counting
}
