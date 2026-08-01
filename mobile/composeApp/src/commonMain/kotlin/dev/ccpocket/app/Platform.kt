package dev.ccpocket.app

/** Default daemon URL differs per platform (the Android emulator reaches the host at 10.0.2.2). */
expect fun defaultDaemonUrl(): String

/** Wall-clock epoch millis — for "2h ago" style relative times (kotlinx-datetime isn't a dependency). */
expect fun epochMillis(): Long

/** Local wall-clock parts of one instant — the minimal seam for absolute schedule times ("23:30",
 *  "Thu 16") without pulling in kotlinx-datetime. [isoDayOfWeek] is 1=Monday … 7=Sunday. */
data class LocalClock(
    val year: Int, val monthOfYear: Int, val dayOfMonth: Int,
    val hour: Int, val minute: Int, val second: Int,
    val isoDayOfWeek: Int,
)

/** [epochMs] rendered in the device's current time zone. */
expect fun localClock(epochMs: Long): LocalClock

/** Coarse OS/device label safe to show in the public-support context preview. */
expect fun supportPlatformLabel(): String

/**
 * How THIS build of the app gets a newer version (issue #200) — what the update nudge is allowed to
 * offer. Deliberately NOT a self-updater on mobile: [IOS_STORE] is a store constraint (the app can only
 * point at its listing), and [ANDROID_DOWNLOAD] reuses the existing release page rather than opening a
 * second distribution channel. The desktop app has its own in-app updater (Settings ▸ About).
 */
enum class AppUpdateRoute { ANDROID_DOWNLOAD, IOS_STORE, DESKTOP_IN_APP }

expect fun appUpdateRoute(): AppUpdateRoute

/**
 * Preview/recording mode for producing App Store preview videos (see marketing/preview). When on, the
 * no-pairing demo gains a connecting → end-to-end-encrypted opener, hides the "demo mode" banner, and
 * uses a destructive sample command so the permission card shows the danger styling. Dormant otherwise.
 * Enabled per-launch only (iOS: launch arg `-ccpPreview YES`); never affects normal users.
 */
expect fun isPreviewMode(): Boolean
