package dev.ccpocket.app

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.ui.uikit.OnFocusBehavior
import dev.ccpocket.app.push.PushController
import dev.ccpocket.app.push.PushDismissal
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

/** Called from iOSApp.swift `willPresent` (foreground only): false = don't show the banner, because the chat
 *  is showing the very session this non-approval push is about (issue #382). */
@Suppress("unused")
fun shouldPresentPush(sessionId: String?, kind: String?): Boolean =
    dev.ccpocket.app.push.ForegroundSession.shouldPresent(sessionId, kind)

/** Called from iOSApp.swift when a Handoff OFFER notification is tapped (§3.4). Such an alert carries NO
 *  session routing at all — only the opaque `hid` custom key — so this selects the offer in the incoming
 *  doorway, which still runs the ordinary confirm → accept flow. */
@Suppress("unused")
fun handlePushOpenHandoff(handoffId: String) = PushRoute.openHandoff(handoffId)

/** Called from iOSApp.swift's didRegisterForRemoteNotificationsWithDeviceToken with the hex APNs token
 *  ("apns" on release builds, "apns_sandbox" on debug — picks the relay's APNs host). */
@Suppress("unused")
fun setPushToken(platform: String, token: String) = PushController.deliver(PushToken(platform, token))

/** Called from iOSApp.swift at launch to wire the Swift-side APNs registration (UNUserNotificationCenter
 *  authorization + UIApplication.registerForRemoteNotifications). The Boolean is `prompt`: true may raise
 *  the system authorization alert, false registers only when the OS already grants notifications — that is
 *  how a silent recovery pass re-reads a rotated token without ever prompting an existing user. */
@Suppress("unused")
fun setPushRegistrar(register: (Boolean) -> Unit) { PushController.registrar = register }

/** Called from iOSApp.swift at launch to wire the system-permission read. Swift answers with the raw
 *  `UNAuthorizationStatus` value on the main queue; the Int keeps the bridge primitive and leaves the
 *  raw → enum mapping in one place (PushController.readAuthorization). */
@Suppress("unused")
fun setPushAuthorizationReader(read: ((Int) -> Unit) -> Unit) { PushController.authorizationReader = read }

/** Called from iOSApp.swift at launch to wire the "open system notification settings" action — the only
 *  recovery an app has once the user denied notifications, since it may not re-prompt. */
@Suppress("unused")
fun setPushSettingsOpener(open: () -> Unit) { PushController.settingsOpener = open }

/** Called from iOSApp.swift at launch: removes this session's delivered notifications (issue #389). */
@Suppress("unused")
fun setPushDismisser(dismiss: (String) -> Unit) { PushDismissal.dismisser = dismiss }

/** Called from iOSApp.swift right after `FirebaseApp.configure()` to wire the analytics sink. */
@Suppress("unused")
fun setTelemetrySink(
    onEvent: (String, Map<String, Any>) -> Unit,
) {
    TelemetrySink.onEvent = onEvent
}

/** Public Swift seams keep observability model classes out of the Compose framework export. */
fun diagnosticSharingEnabled(): Boolean = dev.ccpocket.app.telemetry.Telemetry.isEnabled()
fun admitDiagnosticRecord(json: String): Boolean = dev.ccpocket.app.telemetry.DiagnosticBridge.admit(json)
fun setDiagnosticSink(environment: String, onRecord: (String) -> Boolean, onEnabled: (Boolean) -> Unit) =
    dev.ccpocket.app.telemetry.DiagnosticBridge.register(environment, onRecord, onEnabled)
fun setTelemetryCollectionSink(onEnabled: (Boolean) -> Unit) {
    TelemetrySink.onEnabled = onEnabled
    onEnabled(TelemetrySink.enabled)
}
/** Native callbacks pass fixed counter categories only, never HTTP data or diagnostic contents. */
fun incrementDiagnosticCounter(name: String) = dev.ccpocket.app.telemetry.DiagnosticBridge.incrementCounter(name)
fun flushDiagnosticCounters() = dev.ccpocket.app.telemetry.DiagnosticBridge.flushCounters()

fun admitDiagnosticSpans(): Boolean = dev.ccpocket.app.telemetry.DiagnosticBridge.admitSpans()
