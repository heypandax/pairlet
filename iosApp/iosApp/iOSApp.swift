import SwiftUI
import ComposeApp
import FirebaseCore
import FirebaseAnalytics
import FirebaseCrashlytics

@main
struct iOSApp: App {
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
