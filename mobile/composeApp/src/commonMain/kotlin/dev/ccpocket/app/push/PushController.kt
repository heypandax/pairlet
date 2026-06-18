package dev.ccpocket.app.push

/**
 * A push token from the platform. [platform] tags which relay-side sender handles it:
 * "apns"/"apns_sandbox" (iOS, by build env) or "fcm" (Android). Future domestic-vendor channels
 * ("xiaomi"/"huawei"/…) reuse the same tag mechanism.
 */
data class PushToken(val platform: String, val token: String)

/**
 * Registers the device for remote push and surfaces the resulting token. Mirrors the [dev.ccpocket.app
 * .telemetry.Telemetry] seam — the platform actual hides APNs/FCM behind this single API so business
 * code never imports them. Desktop is a no-op (not a push target).
 */
expect object PushController {
    /**
     * Begin registration: request notification permission (if needed) and fetch the push token.
     * [onToken] fires once the token is available and again on every refresh. Idempotent — calling it
     * again just replaces the callback (and replays the last token, if one already arrived).
     */
    fun start(onToken: (PushToken) -> Unit)
}
