package dev.ccpocket.app

/**
 * Ensures the OS allows traffic to [url] before a socket is opened, prompting the user if needed.
 *
 * On iOS 14+ the first connection to a LAN host trips the Local Network permission dialog and the
 * in-flight socket fails while it is up. This suspends through the dialog and only resumes once the
 * user has answered. Returns false when access is denied (the OS would refuse the connection).
 * Android and desktop have no such runtime gate and return true immediately.
 */
expect suspend fun ensureLocalNetworkAccess(url: String): Boolean
