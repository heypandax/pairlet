package dev.ccpocket.relay.push

import dev.ccpocket.relay.store.RelayStore

/**
 * Fan-out target the relay calls when an offline phone needs waking (a turn finished and no device
 * socket is live). Implementations resolve the account's registered tokens and deliver the alert.
 */
interface PushService {
    suspend fun notify(account: String, title: String, body: String, route: NotifyRoute? = null)
}

/** Default no-op provider — logs intent. Used when no APNs/FCM credentials are configured. */
class LoggingPushService : PushService {
    override suspend fun notify(account: String, title: String, body: String, route: NotifyRoute?) {
        println("[push] account=$account offline — would notify: \"$title — $body\" route=$route")
    }
}

/**
 * Looks up the account's registered push tokens and dispatches each to the [PushSender] for its
 * platform. Sends sequentially (a personal/small-team account has a handful of devices); a failing
 * send is logged and skipped, never propagated.
 */
class StorePushService(
    private val store: RelayStore,
    private val senders: Map<String, PushSender>,
    private val log: (String) -> Unit = ::println,
) : PushService {
    override suspend fun notify(account: String, title: String, body: String, route: NotifyRoute?) {
        val targets = store.pushTargets(account)
        if (targets.isEmpty()) return
        for (t in targets) {
            val sender = senders[t.platform]
            if (sender == null) { log("[push] no sender for platform=${t.platform} (device=${t.deviceId.take(8)}…)"); continue }
            runCatching { sender.send(t.token, title, body, route) }
                .onFailure { log("[push] send failed platform=${t.platform}: ${it.message}") }
        }
    }
}
