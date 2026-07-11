package dev.ccpocket.relay.push

/**
 * Deep-link routing data carried alongside the visible alert: which session a tapped notification
 * should open. Delivered as APNs custom keys / FCM data (never shown to the user).
 */
data class NotifyRoute(val workdir: String, val sessionId: String)

/**
 * Outcome of one send, so the caller can prune permanently-dead tokens while retrying transient ones.
 * A plain boolean conflated "app was uninstalled" (drop the token forever) with "network blip" (keep and
 * retry) — which is exactly how the store filled with 410-Unregistered tokens that were never cleaned.
 */
enum class SendResult {
    /** The gateway accepted the alert (HTTP 200). */
    ACCEPTED,
    /** The token is permanently invalid for this topic (APNs 410 Unregistered / 400 BadDeviceToken,
     *  FCM 404 UNREGISTERED). The caller must drop it so we stop pushing into the void. */
    INVALID_TOKEN,
    /** A transient failure (network reset, 429/5xx, timeout). Keep the token and retry on the next turn. */
    FAILED,
}

/** Sends one alert to a single device token over a vendor channel (APNs/FCM/…). */
interface PushSender {
    /** Delivers one alert. Returns [SendResult.ACCEPTED] if the gateway took it, [SendResult.INVALID_TOKEN]
     *  if the token is permanently dead (caller drops it), or [SendResult.FAILED] for a transient error.
     *  Never throws for a gateway rejection — only genuine I/O may throw, which the caller treats as FAILED.
     *  [route] (when present) is attached as silent routing data so a tap can open the right session. */
    suspend fun send(token: String, title: String, body: String, route: NotifyRoute? = null): SendResult
}
