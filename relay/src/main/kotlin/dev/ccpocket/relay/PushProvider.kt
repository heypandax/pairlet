package dev.ccpocket.relay

/**
 * Fan-out target for background notifications. When a device is offline and a permission ask
 * arrives, the relay calls this. The payload deliberately carries NO prompt content (privacy):
 * a real implementation sends only "Claude needs permission" + an account/convo hint to APNs/FCM.
 *
 * Real APNs (iOS) / FCM (Android) implementations need the user's credentials + registered device
 * tokens — plug them in here; the broker stays unchanged.
 */
interface PushProvider {
    suspend fun notifyPermission(account: String)
}

/** Default no-op provider (logs intent). Swap for an APNs/FCM provider when credentials exist. */
class LoggingPushProvider : PushProvider {
    override suspend fun notifyPermission(account: String) {
        println("[push] account=$account is offline — would APNs/FCM notify: \"Claude needs permission\" (no prompt content)")
    }
}
