package dev.ccpocket.app

/** Default daemon URL differs per platform (the Android emulator reaches the host at 10.0.2.2). */
expect fun defaultDaemonUrl(): String

/** Wall-clock epoch millis — for "2h ago" style relative times (kotlinx-datetime isn't a dependency). */
expect fun epochMillis(): Long
