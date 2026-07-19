package dev.ccpocket.app.desktop

import java.net.InetAddress

/**
 * This computer's short hostname, for matching a paired daemon against "the machine I'm running on"
 * (issue #163: the native folder chooser only makes sense for a local daemon).
 *
 * Deliberately MIRRORS `daemon/…/Main.kt daemonHostName()` instead of sharing it — the desktop app
 * doesn't depend on the daemon module, and the value has to be computed the same way on both sides or
 * the comparison silently never matches. Keep the two in step: same sources, same order, same
 * `substringBefore('.')`, same "localhost is not a name" rule.
 *
 * Null when no usable name is available; callers must treat that as "not this machine" rather than
 * guessing, so an unknown host falls back to typing a path (which always works).
 */
fun localHostName(): String? =
    (System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        ?: System.getenv("HOSTNAME"))
        ?.substringBefore('.')
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("localhost", ignoreCase = true) }
