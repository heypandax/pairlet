package dev.ccpocket.app.push

/**
 * iOS push registration. The actual APNs calls — request authorization + registerForRemoteNotifications
 * — live in Swift (wired via [registrar] from iOSApp.swift, mirroring the telemetry-sink bridge),
 * because the UIKit registration symbol isn't exposed uniformly across Kotlin/Native targets. [start]
 * stores the callback and asks Swift to register; the device token returns through [deliver].
 */
actual object PushController {
    private var cb: ((PushToken) -> Unit)? = null
    private var last: PushToken? = null

    /** Set from Swift at launch: requests authorization, then registers for remote notifications. */
    var registrar: (() -> Unit)? = null

    actual fun start(onToken: (PushToken) -> Unit) {
        cb = onToken
        last?.let { onToken(it) } // replay a token that arrived before start()
        registrar?.invoke()
    }

    /** Called from Swift when APNs delivers (or refreshes) the device token. */
    fun deliver(token: PushToken) {
        last = token
        cb?.invoke(token)
    }
}
