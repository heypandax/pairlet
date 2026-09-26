import SwiftUI
import ComposeApp
import FirebaseCore
import FirebaseAnalytics
import FirebaseCrashlytics
import UIKit
import UserNotifications
import os

/// Hosts the APNs callbacks SwiftUI's `App` can't receive directly, and wires the Swift half of Kotlin's
/// PushController (see `wirePushBridges`). *When* to register is entirely Kotlin's call now — the first-time
/// authorization prompt still follows pairing, and the retry / server-confirmation state machine lives in
/// common code — so this file only executes the primitives: ask, register, read the status, open Settings.
/// Two things still start here on their own: an already-authorized user is re-registered silently at launch
/// and on every return to the foreground, because Apple issues a rotated device token only when we ask for
/// it (issue #114). That foreground hook is the App's `scenePhase` below, NOT `applicationDidBecomeActive` —
/// a SwiftUI Scene app never receives that delegate callback, so relying on it left rotated tokens unread.
/// Either way the token lands below and is handed back over the same bridge pattern as telemetry.
class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        wirePushBridges()
        // Apple issues a NEW device token on reinstall/restore/(sometimes) OS-or-app update and only hands
        // it back when we call registerForRemoteNotifications — so we must call it every launch, not just
        // once at pairing. Without this, a rotated token was never re-read and the relay kept pushing to a
        // dead one (→ 410 Unregistered forever). See issue #114.
        refreshPushRegistrationIfAuthorized()
        return true
    }

    /// Wire the Swift halves of Kotlin's PushController. Deliberately here rather than in `iOSApp.init()`:
    /// SwiftUI documents neither when `@UIApplicationDelegateAdaptor` materialises its delegate nor how many
    /// times it may re-initialise the App struct, whereas `didFinishLaunching` runs exactly once and provably
    /// before the Compose hierarchy exists — i.e. before any Kotlin code can reach these seams. It also keeps
    /// the closures next to the AppDelegate state they drive.
    private func wirePushBridges() {
        // Registration lives in Swift (the UIKit symbols aren't exposed uniformly across Kotlin/Native
        // targets). Kotlin decides *when*: `prompt == true` is the post-pairing first-time ask, `false` is a
        // silent recovery pass that must never raise an alert on an existing user.
        MainViewControllerKt.setPushRegistrar { [weak self] prompt in
            guard prompt.boolValue else {
                self?.refreshPushRegistrationIfAuthorized()
                return
            }
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
                if let error = error {
                    AppDelegate.reportRegistrationFailure(error, authorizationFailure: true)
                    return
                }
                guard granted else {
                    // A user who already granted notifications is re-granted without seeing a second alert,
                    // so `!granted` really is a refusal. Report it as such: the coordinator must stop burning
                    // its retry budget and offer the Settings route instead of re-asking for a prompt iOS
                    // will never show again.
                    PushController.shared.registrationFailed(category: 2)
                    return
                }
                DispatchQueue.main.async {
                    PushController.shared.registrationStarted()
                    UIApplication.shared.registerForRemoteNotifications()
                }
            }
        }

        // Kotlin re-reads the live system status instead of trusting a cached verdict: the user can flip
        // notifications in Settings while the app is suspended, and "App switch on" is not "system granted".
        MainViewControllerKt.setPushAuthorizationReader { callback in
            UNUserNotificationCenter.current().getNotificationSettings { settings in
                // getNotificationSettings answers on an arbitrary queue; hop to main so Kotlin always
                // resumes on the thread its state machine already runs on.
                let raw = Int32(settings.authorizationStatus.rawValue)
                PushController.shared.authorizationObserved(raw: raw)
                Self.recordPresentationSettings(settings)
                DispatchQueue.main.async { callback(KotlinInt(int: raw)) }
            }
        }

        // Once the user has denied notifications the app may not prompt again, so deep-linking into the
        // system page is the only recovery we can offer.
        MainViewControllerKt.setPushSettingsOpener {
            DispatchQueue.main.async { AppDelegate.openNotificationSettingsPage() }
        }

        // issue #389: the user is looking at this session — clear its delivered alerts from the tray
        MainViewControllerKt.setPushDismisser { sid in
            let center = UNUserNotificationCenter.current()
            center.getDeliveredNotifications { delivered in
                let ids = delivered
                    .filter { ($0.request.content.userInfo["sid"] as? String) == sid }
                    .map { $0.request.identifier }
                if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
            }
        }
    }

    /// iOS 16 added a URL that lands directly on THIS app's notification page; our deployment target is 15.0,
    /// so below that only the app's settings root exists. `open` may also refuse the notification URL at
    /// runtime, and a `false` result falls back to the root rather than leaving the user staring at nothing.
    private static func openNotificationSettingsPage() {
        let openAppSettings: () -> Void = {
            guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
        if #available(iOS 16.0, *), let url = URL(string: UIApplication.openNotificationSettingsURLString) {
            UIApplication.shared.open(url, options: [:]) { opened in
                if !opened { openAppSettings() }
            }
        } else {
            openAppSettings()
        }
    }

    /// Re-fetch the current APNs token when notifications are ALREADY authorized. Gated on the granted
    /// status so this never shows a prompt — the first-time prompt still follows pairing, driven from Kotlin
    /// via `requestToken(prompt: true)`. The refreshed token lands in didRegister… below and flows to the
    /// relay via the existing setPushToken bridge (idempotent: the phone dedupes, the relay upserts).
    fileprivate func refreshPushRegistrationIfAuthorized() {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            PushController.shared.authorizationObserved(raw: Int32(settings.authorizationStatus.rawValue))
            Self.recordPresentationSettings(settings)
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral:
                DispatchQueue.main.async {
                    PushController.shared.registrationStarted()
                    UIApplication.shared.registerForRemoteNotifications()
                }
            default:
                break // not yet granted — leave the prompt to the post-pairing registrar
            }
        }
    }

    private static func recordPresentationSettings(_ settings: UNNotificationSettings) {
        PushPresentationDiagnostics.shared.settings(
            alert: Int32(settings.alertSetting.rawValue), lockScreen: Int32(settings.lockScreenSetting.rawValue),
            center: Int32(settings.notificationCenterSetting.rawValue), sound: Int32(settings.soundSetting.rawValue))
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
        Self.reportRegistrationFailure(error, authorizationFailure: false)
    }

    /// Preserve a finite domain label + numeric code, never NSError text or userInfo.
    private static func reportRegistrationFailure(_ error: Error, authorizationFailure: Bool) {
        let ns = error as NSError
        let domain: Int32
        switch ns.domain {
        case NSURLErrorDomain: domain = 0
        case NSCocoaErrorDomain: domain = 1
        case NSPOSIXErrorDomain: domain = 2
        case NSMachErrorDomain: domain = 3
        default: domain = 4
        }
        PushController.shared.nativeRegistrationFailed(
            category: registrationFailureCategory(error), errorDomain: domain,
            errorCode: Int32(clamping: ns.code), authorizationFailure: authorizationFailure)
    }

    private static func registrationFailureCategory(_ error: Error) -> Int32 {
        let ns = error as NSError
        // no route to APNs — worth retrying once connectivity returns
        if ns.domain == NSURLErrorDomain { return 1 }
        // 3010: remote notifications aren't supported on this device (Simulator without a paired Mac)
        if ns.domain == NSCocoaErrorDomain, ns.code == 3010 { return 3 }
        return 0
    }

    // surface the alert even when the app is in the foreground — except a turn push about the session the
    // chat is showing right now (issue #382: daemons push turn ends even while clients are online)
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let info = notification.request.content.userInfo
        let present = MainViewControllerKt.shouldPresentPush(sessionId: info["sid"] as? String, kind: info["kind"] as? String)
        PushPresentationDiagnostics.shared.foreground(present: present)
        if present {
            completionHandler([.banner, .sound])
        } else {
            completionHandler([])
        }
    }

    // a tapped task-complete notification carries `wd`/`sid` custom keys → deep-link into that session;
    // a Handoff OFFER notification carries only `hid` (§3.4) → open that offer in the incoming doorway
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        PushPresentationDiagnostics.shared.opened()
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
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Firebase stays in Swift (the only place that imports it); the shared Kotlin Telemetry
        // calls back through the sink registered below — mirroring cc-dashboard's single seam. A clean
        // clone intentionally ships a placeholder plist for local builds; Firebase Installations aborts
        // the process when that placeholder API key is passed to configure, so telemetry must degrade to
        // its existing no-op sink until a real Firebase configuration is supplied.
        let diagnosticTest = ProcessInfo.processInfo.environment["CCPOCKET_TEST_MODE"] == "1"
        if !diagnosticTest { PocketDiagnostics.shared.register() }
        if !diagnosticTest && Self.configureFirebaseIfUsable() {
            MainViewControllerKt.setTelemetryCollectionSink { enabled in
                Analytics.setAnalyticsCollectionEnabled(enabled.boolValue)
                Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(enabled.boolValue)
                if !enabled.boolValue {
                    Analytics.resetAnalyticsData()
                    Crashlytics.crashlytics().deleteUnsentReports()
                }
            }
            MainViewControllerKt.setTelemetrySink(
                onEvent: { event, params in
                    Analytics.logEvent(event, parameters: params)
                }
            )
        }
        // The push bridges are wired from AppDelegate.didFinishLaunching instead — see `wirePushBridges`.
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
                // The only foreground hook a SwiftUI Scene app actually receives: `applicationDidBecomeActive`
                // is never called here, which is why a token that rotated during a suspend went unread until
                // the next cold launch. Attached exactly once, on the single WindowGroup's content, so one
                // return to the foreground triggers one silent re-registration. `onChange(of:perform:)` is
                // the iOS 14 form — the iOS 17 signature would need gating past our 15.0 deployment target.
                .onChange(of: scenePhase) { phase in
                    if phase == .active { appDelegate.refreshPushRegistrationIfAuthorized() }
                }
        }
    }
}
