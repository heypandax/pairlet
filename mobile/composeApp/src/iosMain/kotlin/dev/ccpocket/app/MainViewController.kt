package dev.ccpocket.app

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.ComposeUIViewController
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

/** Called from iOSApp.swift right after `FirebaseApp.configure()` to wire the analytics sink. */
@Suppress("unused")
fun setTelemetrySink(
    onEvent: (String, Map<String, Any>) -> Unit,
    onError: (String, String?) -> Unit,
) {
    TelemetrySink.onEvent = onEvent
    TelemetrySink.onError = onError
}
