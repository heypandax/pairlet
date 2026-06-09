package dev.ccpocket.app

// Android gates network access with the install-time INTERNET permission; nothing to prompt for.
actual suspend fun ensureLocalNetworkAccess(url: String): Boolean = true
