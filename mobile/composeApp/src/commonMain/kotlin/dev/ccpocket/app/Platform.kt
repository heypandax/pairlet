package dev.ccpocket.app

/** Default daemon URL differs per platform (the Android emulator reaches the host at 10.0.2.2). */
expect fun defaultDaemonUrl(): String

/** Wall-clock epoch millis — for "2h ago" style relative times (kotlinx-datetime isn't a dependency). */
expect fun epochMillis(): Long

/**
 * Preview/recording mode for producing App Store preview videos (see marketing/preview). When on, the
 * no-pairing demo gains a connecting → end-to-end-encrypted opener, hides the "demo mode" banner, and
 * uses a destructive sample command so the permission card shows the danger styling. Dormant otherwise.
 * Enabled per-launch only (iOS: launch arg `-ccpPreview YES`); never affects normal users.
 */
expect fun isPreviewMode(): Boolean
