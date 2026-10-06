package dev.ccpocket.app.net

/**
 * Bounded TCP connect to [host]:[port]; closes immediately without writing a byte (#404, spec 4.2 step 3).
 * Why: the idle re-path must learn whether the direct address answers *before* it tears down a
 * healthy relay link — tearing first is the first domino of #145. Any failure returns false.
 */
expect suspend fun tcpReachable(host: String, port: Int, timeoutMs: Long): Boolean

/** Port of a direct URL; scheme default when absent (ws/http 80, wss/https 443). Null when unparseable. */
internal fun portOf(url: String): Int? {
    val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
    val authority = url.substringAfter("://", missingDelimiterValue = "").substringBefore('/').substringBefore('?')
        .substringAfterLast('@')
    if (authority.isEmpty()) return null
    val afterHost = if (authority.startsWith("[")) authority.substringAfter(']', "") else authority.substringAfter(':', "")
    val explicit = afterHost.removePrefix(":")
    if (explicit.isNotEmpty()) return explicit.toIntOrNull()?.takeIf { it in 1..65535 }
    return when (scheme) {
        "ws", "http" -> 80
        "wss", "https" -> 443
        else -> null
    }
}
