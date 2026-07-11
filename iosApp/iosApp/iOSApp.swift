import SwiftUI
import ComposeApp
import FirebaseCore
import FirebaseAnalytics
import FirebaseCrashlytics
import UIKit
import UserNotifications

/// Hosts the APNs callbacks SwiftUI's `App` can't receive directly. The first-time authorization *prompt*
/// is Kotlin-driven (PushController.registrar, after pairing) so it never fires at cold start; but once the
/// user has granted it, we re-`registerForRemoteNotifications` on every launch/foreground here to re-read a
/// rotated device token (issue #114). Either way the token lands below and is handed back over the same
/// bridge pattern as telemetry.
class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        // Apple issues a NEW device token on reinstall/restore/(sometimes) OS-or-app update and only hands
        // it back when we call registerForRemoteNotifications — so we must call it every launch, not just
        // once at pairing. Without this, a rotated token was never re-read and the relay kept pushing to a
        // dead one (→ 410 Unregistered forever). See issue #114.
        refreshPushRegistrationIfAuthorized()
        return true
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        // also catch a token that rotated while we were backgrounded but not fully relaunched
        refreshPushRegistrationIfAuthorized()
    }

    /// Re-fetch the current APNs token when notifications are ALREADY authorized. Gated on the granted
    /// status so this never shows a prompt — the first-time prompt still follows pairing, driven from
    /// Kotlin's PushController.registrar. The refreshed token lands in didRegister… below and flows to the
    /// relay via the existing setPushToken bridge (idempotent: the phone dedupes, the relay upserts).
    private func refreshPushRegistrationIfAuthorized() {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral:
                DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() }
            default:
                break // not yet granted — leave the prompt to the post-pairing registrar
            }
        }
    }

    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        #if DEBUG
        let platform = "apns_sandbox" // development APNs → relay sends via api.sandbox.push.apple.com
        #else
        let platform = "apns"
        #endif
        MainViewControllerKt.setPushToken(platform: platform, token: hex)
    }

    func application(_ application: UIApplication,
                     didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // transient — Kotlin re-attempts registration on the next foreground/connect
    }

    // surface the alert even when the app is in the foreground
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    // a tapped task-complete notification carries `wd`/`sid` custom keys → deep-link into that session
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        if let wd = info["wd"] as? String, let sid = info["sid"] as? String {
            MainViewControllerKt.handlePushOpen(workdir: wd, sessionId: sid)
        }
        completionHandler()
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    init() {
        // Firebase stays in Swift (the only place that imports it); the shared Kotlin Telemetry
        // calls back through the sink registered below — mirroring cc-dashboard's single seam.
        FirebaseApp.configure()
        Analytics.setAnalyticsCollectionEnabled(true) // plist ships IS_ANALYTICS_ENABLED=false; opt in here
        MainViewControllerKt.setTelemetrySink(
            onEvent: { event, params in
                Analytics.logEvent(event, parameters: params)
            },
            onError: { message, phase in
                let info: [String: Any] = [NSLocalizedDescriptionKey: message, "phase": phase ?? ""]
                Crashlytics.crashlytics().record(error: NSError(domain: "ccpocket", code: 0, userInfo: info))
            }
        )
        // Push registration lives in Swift (UIKit symbols aren't uniform across Kotlin/Native targets).
        // Kotlin's PushController calls this when registration starts (after pairing), so the prompt
        // follows pairing rather than firing at cold launch.
        MainViewControllerKt.setPushRegistrar {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
                guard granted else { return }
                DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() }
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.all)
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    MainViewControllerKt.handleDeepLink(url: url.absoluteString)
                }
        }
    }
}
