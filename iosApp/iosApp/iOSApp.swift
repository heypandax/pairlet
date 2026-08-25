import SwiftUI
import ComposeApp
import FirebaseCore
import FirebaseAnalytics
import FirebaseCrashlytics
import UIKit
import UserNotifications
import os

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
        // Which APNs environment issued this token decides which endpoint the relay must send to
        // (sandbox vs production). It is a property of the ENTITLEMENT (aps-environment), NOT of the
        // compile configuration — so `#if DEBUG` is the wrong axis and silently mis-tags a fir/ad-hoc
        // build (Release-compiled but development-entitled): a sandbox token gets labelled "apns",
        // the relay tries api.push.apple.com, APNs answers 410, the relay prunes the token, and the
        // phone goes permanently silent. Read the entitlement at runtime instead.
        let platform = Self.apnsIsSandbox() ? "apns_sandbox" : "apns"
        MainViewControllerKt.setPushToken(platform: platform, token: hex)
    }

    /// True when this build's aps-environment is `development` (→ sandbox APNs). Determined from the
    /// embedded provisioning profile: App Store builds have NO `embedded.mobileprovision`, so their
    /// absence IS the "production" signal; Debug / Ad-hoc / fir builds carry one naming the environment.
    private static func apnsIsSandbox() -> Bool {
        // .isoLatin1, NOT .ascii: the profile is a CMS/DER container with bytes >127, so an .ascii decode
        // returns nil for the whole file — which silently fell through to "production", labelled a sandbox
        // token "apns", and earned a BadDeviceToken from APNs. isoLatin1 maps every byte 1:1 and never fails.
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .isoLatin1)
        else { return false } // no profile → App Store → production
        // the profile is CMS-wrapped; a substring scan of its plist body is the pragmatic, dependency-free read
        guard let range = text.range(of: "aps-environment") else { return false }
        let after = text[range.upperBound...].prefix(64)
        return after.contains("development")
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

    // a tapped task-complete notification carries `wd`/`sid` custom keys → deep-link into that session;
    // a Handoff OFFER notification carries only `hid` (§3.4) → open that offer in the incoming doorway
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        if let hid = info["hid"] as? String {
            MainViewControllerKt.handlePushOpenHandoff(handoffId: hid)
        } else if let wd = info["wd"] as? String, let sid = info["sid"] as? String {
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
        // calls back through the sink registered below — mirroring cc-dashboard's single seam. A clean
        // clone intentionally ships a placeholder plist for local builds; Firebase Installations aborts
        // the process when that placeholder API key is passed to configure, so telemetry must degrade to
        // its existing no-op sink until a real Firebase configuration is supplied.
        if Self.configureFirebaseIfUsable() {
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
        }
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

    /// Every rejection below degrades telemetry to the no-op sink, so each one must leave a breadcrumb in
    /// the device log: the old unconditional `FirebaseApp.configure()` crashed loudly on a bad plist and
    /// TestFlight caught it within minutes, whereas a silent guard would let a build with a corrupted
    /// GOOGLE_SERVICE_INFO_PLIST secret (or a future Google key format) pass QA with Analytics AND
    /// Crashlytics dead all the way to production. The *verdicts* are unchanged — only observability is added.
    private static let firebaseLog = Logger(subsystem: "com.panda.ccpocket", category: "firebase")

    private static func configureFirebaseIfUsable() -> Bool {
        guard let path = Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") else {
            // routine in a clean clone / local build that never copied a plist in — informational, not a fault
            firebaseLog.info("Firebase plist absent — telemetry disabled")
            return false
        }
        guard let options = FirebaseOptions(contentsOfFile: path) else {
            firebaseLog.error("Firebase plist present but unparseable — telemetry disabled")
            return false
        }
        guard let apiKey = options.apiKey else {
            firebaseLog.error("Firebase plist carries no apiKey — telemetry disabled")
            return false
        }
        // A real Google API key is 39 chars, "A"-prefixed, alphanumerics plus `-_`; the placeholder shipped
        // for local builds is 43 and is meant to land here. Never log the key itself — length and the first
        // four characters are enough to tell "placeholder" from "mangled secret" apart in a CI/TestFlight log.
        guard apiKey.count == 39,
              apiKey.hasPrefix("A"),
              apiKey.unicodeScalars.allSatisfy({
                  CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_")).contains($0)
              })
        else {
            firebaseLog.error(
                "Firebase apiKey shape rejected (len=\(apiKey.count, privacy: .public), prefix=\(String(apiKey.prefix(4)), privacy: .public)) — telemetry disabled"
            )
            return false
        }
        guard let projectID = options.projectID,
              !projectID.isEmpty,
              !options.googleAppID.isEmpty
        else {
            firebaseLog.error("Firebase options missing projectID/googleAppID — telemetry disabled")
            return false
        }

        FirebaseApp.configure(options: options)
        return true
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
