package dev.ccpocket.app.push

/**
 * iOS tray clearing (issue #389). `UNUserNotificationCenter.getDeliveredNotifications` /
 * `removeDeliveredNotifications` live in Swift (wired via [dismisser] from iOSApp.swift, mirroring
 * [PushController.settingsOpener]), because those UserNotifications symbols aren't exposed uniformly across
 * Kotlin/Native targets. Swift matches delivered alerts by the payload's `sid` key.
 */
actual object PushDismissal {
    /** Set from Swift at launch: removes every delivered notification whose `sid` equals the argument. */
    var dismisser: ((String) -> Unit)? = null

    actual fun dismiss(sessionId: String) {
        dismisser?.invoke(sessionId) // not wired (unit tests, older host) → nothing to clear
    }
}
