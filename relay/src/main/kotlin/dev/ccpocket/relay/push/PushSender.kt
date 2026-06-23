package dev.ccpocket.relay.push

/**
 * Deep-link routing data carried alongside the visible alert: which session a tapped notification
 * should open. Delivered as APNs custom keys / FCM data (never shown to the user).
 */
data class NotifyRoute(val workdir: String, val sessionId: String)

/** Sends one alert to a single device token over a vendor channel (APNs/FCM/…). */
interface PushSender {
    /** True if the gateway accepted it. A rejected (invalid/expired) token returns false. Never throws.
     *  [route] (when present) is attached as silent routing data so a tap can open the right session. */
    suspend fun send(token: String, title: String, body: String, route: NotifyRoute? = null): Boolean
}
