package dev.ccpocket.app.push

/**
 * iOS push registration. The actual APNs calls — reading the authorization status, requesting it,
 * `registerForRemoteNotifications` and opening the system settings page — live in Swift (wired via
 * [registrar], [authorizationReader] and [settingsOpener] from iOSApp.swift, mirroring the telemetry-sink
 * bridge), because those UIKit/UserNotifications symbols aren't exposed uniformly across Kotlin/Native
 * targets. [start] only parks the callback (and replays a token that already arrived); deciding *when* to
 * register is the common-code coordinator's job, which drives [requestToken] and [readAuthorization].
 */
actual object PushController {
    private var cb: ((PushToken) -> Unit)? = null
    private var last: PushToken? = null

    /**
     * Set from Swift at launch. `prompt = true` → `requestAuthorization(.alert, .sound, .badge)` and, once
     * granted, `registerForRemoteNotifications`; `prompt = false` → register only when the OS already
     * reports authorized/provisional/ephemeral, so a silent recovery pass can never raise a system prompt.
     */
    var registrar: ((Boolean) -> Unit)? = null

    /**
     * Set from Swift at launch. Reads `UNUserNotificationCenter.getNotificationSettings` and answers on the
     * main queue with the raw `UNAuthorizationStatus` value. A raw Int crosses the bridge rather than a
     * Kotlin enum because the Obj-C export of an enum is awkward to construct from Swift, and the mapping
     * belongs on one side only — [readAuthorization] below owns it.
     */
    var authorizationReader: (((Int) -> Unit) -> Unit)? = null

    /** Set from Swift at launch: opens the system notification settings page for this app. */
    var settingsOpener: (() -> Unit)? = null

    /** The latest ask's failure sink. APNs reports a failure without saying which ask it answers
     *  ([registrationFailed] carries only a category), so it is attributed to the most recent ask. */
    private var onFailed: ((PushRegistrationFailure) -> Unit)? = null

    actual fun start(onToken: (PushToken) -> Unit) {
        cb = onToken
        last?.let { onToken(it) } // replay a token that arrived before start()
    }

    actual fun requestToken(prompt: Boolean, onFailed: (PushRegistrationFailure) -> Unit) {
        this.onFailed = onFailed // before invoking: the Swift side may refuse synchronously
        registrar?.invoke(prompt)
    }

    actual fun readAuthorization(cb: (PushAuthorization) -> Unit) {
        // No Swift reader wired (unit tests, or a host that never called setPushAuthorizationReader):
        // answer UNKNOWN rather than dropping the callback, so a caller awaiting it can never hang.
        val reader = authorizationReader ?: return cb(PushAuthorization.UNKNOWN)
        reader { raw ->
            // UNAuthorizationStatus raw values, fixed by the SDK:
            // notDetermined = 0, denied = 1, authorized = 2, provisional = 3, ephemeral = 4.
            cb(
                when (raw) {
                    0 -> PushAuthorization.NOT_DETERMINED
                    1 -> PushAuthorization.DENIED
                    2 -> PushAuthorization.AUTHORIZED
                    3 -> PushAuthorization.PROVISIONAL
                    4 -> PushAuthorization.EPHEMERAL
                    else -> PushAuthorization.UNKNOWN // a status added by a future iOS — not a denial
                }
            )
        }
    }

    actual fun openNotificationSettings() {
        settingsOpener?.invoke()
    }

    /** Called from Swift when APNs delivers (or refreshes) the device token. */
    fun deliver(token: PushToken) {
        last = token
        cb?.invoke(token)
    }

    /**
     * Called from Swift when authorization or APNs registration fails. Swift passes a fixed category
     * (0 unknown, 1 network, 2 denied, 3 unsupported) — never an `NSError`, its `domain`, its `code` or its
     * `localizedDescription`. Those are free-form vendor strings that would reach telemetry verbatim, so the
     * allow-listed (domain, code) → category mapping stays on the Swift side and only the verdict crosses.
     * Recording the diagnostic is the common-code coordinator's job (it alone knows the attempt and stage),
     * so this reports the boundary and nothing else.
     */
    fun registrationFailed(category: Int) {
        onFailed?.invoke(
            when (category) {
                1 -> PushRegistrationFailure.NETWORK
                2 -> PushRegistrationFailure.DENIED
                3 -> PushRegistrationFailure.UNSUPPORTED
                else -> PushRegistrationFailure.UNKNOWN
            }
        )
    }
}
