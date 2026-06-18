package dev.ccpocket.relay.push

/** Sends one alert to a single device token over a vendor channel (APNs/FCM/…). */
interface PushSender {
    /** True if the gateway accepted it. A rejected (invalid/expired) token returns false. Never throws. */
    suspend fun send(token: String, title: String, body: String): Boolean
}
