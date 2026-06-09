package dev.ccpocket.app

// Desktop JVMs have no LAN-access permission gate.
actual suspend fun ensureLocalNetworkAccess(url: String): Boolean = true
