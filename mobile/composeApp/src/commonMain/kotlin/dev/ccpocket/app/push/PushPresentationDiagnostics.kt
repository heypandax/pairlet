package dev.ccpocket.app.push

import dev.ccpocket.observability.*

/** OS presentation settings and foreground decisions, never notification payloads or session IDs. */
object PushPresentationDiagnostics {
    fun settings(alert: Int, lockScreen: Int, center: Int, sound: Int) {
        Diagnostics.push(Stage.PUSH_PRESENTATION, ErrorCode.SETTINGS_OBSERVED, metrics = SafeMetrics(
            notificationAlert = setting(alert), notificationLockScreen = setting(lockScreen),
            notificationCenter = setting(center), notificationSound = setting(sound),
        ))
    }

    fun foreground(present: Boolean) {
        // This observes the options returned to iOS, not whether a person saw the banner.
        Diagnostics.push(Stage.PUSH_PRESENTATION,
            if (present) ErrorCode.FOREGROUND_PRESENTED else ErrorCode.FOREGROUND_SUPPRESSED)
    }

    fun opened() { Diagnostics.push(Stage.PUSH_PRESENTATION, ErrorCode.OPENED) }

    private fun setting(raw: Int) = when (raw) {
        0 -> NotificationSetting.UNSUPPORTED
        1 -> NotificationSetting.DISABLED
        2 -> NotificationSetting.ENABLED
        else -> NotificationSetting.UNKNOWN
    }
}
