package dev.ccpocket.app.push

import dev.ccpocket.observability.*

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
        val register = registrar
        if (register == null) {
            Diagnostics.push(Stage.PUSH_TOKEN, ErrorCode.BRIDGE_MISSING, isError = true)
        } else register(prompt)
    }

    actual fun readAuthorization(cb: (PushAuthorization) -> Unit) {
        // No Swift reader wired (unit tests, or a host that never called setPushAuthorizationReader):
        // answer UNKNOWN rather than dropping the callback, so a caller awaiting it can never hang.
        val reader = authorizationReader ?: run {
            Diagnostics.push(Stage.PUSH_AUTHORIZATION, ErrorCode.BRIDGE_MISSING, isError = true)
            cb(PushAuthorization.UNKNOWN)
            return
        }
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
        Diagnostics.push(Stage.PUSH_TOKEN, when (token.platform) {
            "apns_sandbox" -> ErrorCode.TOKEN_SANDBOX
            "apns" -> ErrorCode.TOKEN_PRODUCTION
            else -> ErrorCode.TOKEN_RECEIVED
        })
        last = token
        cb?.invoke(token)
    }

    /** Native callbacks may occur before a coordinator is waiting; record them at the boundary. */
    fun registrationStarted() { Diagnostics.push(Stage.PUSH_TOKEN, ErrorCode.STARTED) }

    fun authorizationObserved(raw: Int) {
        Diagnostics.push(Stage.PUSH_AUTHORIZATION, when (raw) {
            0 -> ErrorCode.NOT_DETERMINED
            1 -> ErrorCode.PERMISSION_DENIED
            2 -> ErrorCode.AUTHORIZED
            3 -> ErrorCode.PROVISIONAL
            4 -> ErrorCode.EPHEMERAL
            else -> ErrorCode.UNEXPECTED
        })
    }

    fun registrationFailed(category: Int) {
        nativeRegistrationFailed(category, 4, 0, category == 2)
    }

    /** Only a closed domain label and bounded numeric code cross from NSError, never its description. */
    fun nativeRegistrationFailed(category: Int, errorDomain: Int, errorCode: Int, authorizationFailure: Boolean) {
        Diagnostics.push(if (authorizationFailure) Stage.PUSH_AUTHORIZATION else Stage.PUSH_TOKEN,
            when (category) {
                1 -> ErrorCode.NETWORK_FAILED
                2 -> ErrorCode.PERMISSION_DENIED
                3 -> ErrorCode.UNSUPPORTED
                else -> ErrorCode.NATIVE_FAILED
            }, metrics = if (category == 2) SafeMetrics() else SafeMetrics(nativeErrorDomain = when (errorDomain) {
                0 -> NativeErrorDomain.URL
                1 -> NativeErrorDomain.COCOA
                2 -> NativeErrorDomain.POSIX
                3 -> NativeErrorDomain.MACH
                else -> NativeErrorDomain.OTHER
            }, nativeErrorCode = errorCode), isError = category != 2)
        onFailed?.invoke(when (category) {
            1 -> PushRegistrationFailure.NETWORK
            2 -> PushRegistrationFailure.DENIED
            3 -> PushRegistrationFailure.UNSUPPORTED
            else -> PushRegistrationFailure.UNKNOWN
        })
    }
}
