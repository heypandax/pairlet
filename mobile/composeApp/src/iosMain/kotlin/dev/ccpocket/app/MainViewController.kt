package dev.ccpocket.app

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.ui.uikit.OnFocusBehavior
import dev.ccpocket.app.push.PushController
import dev.ccpocket.app.push.PushToken
import dev.ccpocket.app.telemetry.TelemetrySink
import dev.ccpocket.app.ui.App

@Suppress("unused", "FunctionName")
fun MainViewController() = ComposeUIViewController(configure = {
    // The app owns keyboard avoidance (root imePadding + the chat's bottom re-pin scroller). The default
    // FocusableAboveKeyboard ALSO lifts the whole UIKit view when the composer focuses while covered —
    // double compensation that bounced the chat up-then-down on first open (race-dependent, hence "偶现").
    onFocusBehavior = OnFocusBehavior.DoNothing
}) {
    val scope = rememberCoroutineScope()
    App(scope)
}

/** Called from iOSApp.swift `.onOpenURL` when a ccpocket:// link opens the app. */
@Suppress("unused")
fun handleDeepLink(url: String) = DeepLink.handle(url)

/** Called from iOSApp.swift when a task-complete notification is tapped — the `wd`/`sid` custom keys
 *  from the APNs payload route straight into that session. */
@Suppress("unused")
fun handlePushOpen(workdir: String, sessionId: String) = PushRoute.open(workdir, sessionId)

/** Called from iOSApp.swift's didRegisterForRemoteNotificationsWithDeviceToken with the hex APNs token
 *  ("apns" on release builds, "apns_sandbox" on debug — picks the relay's APNs host). */
@Suppress("unused")
fun setPushToken(platform: String, token: String) = PushController.deliver(PushToken(platform, token))

/** Called from iOSApp.swift at launch to wire the Swift-side APNs registration (UNUserNotificationCenter
 *  authorization + UIApplication.registerForRemoteNotifications); invoked when push registration starts. */
@Suppress("unused")
fun setPushRegistrar(register: () -> Unit) { PushController.registrar = register }

/** Called from iOSApp.swift right after `FirebaseApp.configure()` to wire the analytics sink. */
@Suppress("unused")
fun setTelemetrySink(
    onEvent: (String, Map<String, Any>) -> Unit,
    onError: (String, String?) -> Unit,
) {
    TelemetrySink.onEvent = onEvent
    TelemetrySink.onError = onError
}
