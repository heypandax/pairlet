package dev.ccpocket.app.data

/**
 * Issue #251 — "Unknown error" then a zombie app.
 *
 * `org.jetbrains.compose.resources.getString` is a SUSPEND lookup that reads the packaged resource
 * bundle at call time. Every caller in this repo lives in a detached `scope.launch { … }` (usually
 * behind a `delay(8000)` timeout), which means a lookup that fails — a resource bundle the packaged
 * build cannot read, a locale table that never loaded, a ClassLoader that cannot see the resource
 * root — throws on a coroutine with no handler. On desktop that escape becomes an AWT-level error
 * dialog ("Unknown error", one OK button), the window is torn down, and the AWT tray thread keeps
 * the JVM alive as a zombie. The user sees a crash whose text names neither cause nor place.
 *
 * These lookups are decoration on an error path that already knows what it wants to say. So: never
 * let one escape. Fall back to the English literal and tag it, so a user can read the code back to
 * us and we can find the same code in the desktop crash log.
 */
internal const val ERR_STRING_LOOKUP = "CCP-STR-01"

/**
 * Run a localized-string lookup that must not be able to kill its coroutine.
 *
 * Catches [Throwable], not [Exception]: a missing resource root surfaces as `NoClassDefFoundError` /
 * `ExceptionInInitializerError` in packaged builds, and those are exactly the ones worth surviving.
 *
 * @param fallback the English literal to show instead — keep it in sync with `strings.xml` by eye;
 *   it is a degraded-mode string, not a second translation source.
 */
internal suspend fun safeString(fallback: String, load: suspend () -> String): String =
    runCatching { load() }.getOrElse { t -> stringFallbackWithCode(fallback, t) }

/** The degraded-mode text: the fallback plus a short code the user can report verbatim. */
internal fun stringFallbackWithCode(fallback: String, t: Throwable): String {
    val tag = "[$ERR_STRING_LOOKUP ${t::class.simpleName ?: "Throwable"}]"
    return if (fallback.isBlank()) tag else "$fallback $tag"
}
