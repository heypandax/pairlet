package dev.ccpocket.app

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.ComposeUIViewController
import dev.ccpocket.app.push.PushController
import dev.ccpocket.app.push.PushToken
import dev.ccpocket.app.telemetry.TelemetrySink
import dev.ccpocket.app.ui.App

@Suppress("unused", "FunctionName")
fun MainViewController() = ComposeUIViewController {
    val scope = rememberCoroutineScope()
    App(scope)
}

/** Called from iOSApp.swift `.onOpenURL` when a ccpocket:// link opens the app. */
@Suppress("unused")
fun handleDeepLink(url: String) = DeepLink.handle(url)

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
