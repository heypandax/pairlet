package dev.ccpocket.relay.push

import dev.ccpocket.relay.store.RelayStore
import java.util.concurrent.atomic.AtomicInteger

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
 * platform. Sends sequentially (a personal/small-team account has a handful of devices). A permanently
 * dead token (APNs 410 / FCM 404) is pruned from the store so we stop hammering it; a transient failure
 * is left in place to retry next turn. A fan-out where *no* device accepts is escalated to a WARN with a
 * running streak — the "silently 410-rotted for a month" state now shows up loudly in the logs.
 */
class StorePushService(
    private val store: RelayStore,
    private val senders: Map<String, PushSender>,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = ::println,
) : PushService {
    /** Consecutive fully-failed fan-outs across the relay — a coarse "push is 100% down" smoke alarm. */
    private val consecutiveFullFailures = AtomicInteger(0)

    override suspend fun notify(account: String, title: String, body: String, route: NotifyRoute?) {
        val targets = store.pushTargets(account)
        if (targets.isEmpty()) return
        var accepted = 0
        var pruned = 0
        for (t in targets) {
            val sender = senders[t.platform]
            if (sender == null) { log("[push] no sender for platform=${t.platform} (device=${t.deviceId.take(8)}…)"); continue }
            val result = runCatching { sender.send(t.token, title, body, route) }
                .getOrElse { log("[push] send failed platform=${t.platform}: ${it.message}"); SendResult.FAILED }
            when (result) {
                SendResult.ACCEPTED -> accepted++
                SendResult.INVALID_TOKEN -> {
                    if (store.clearPushToken(t.deviceId, t.platform, t.token, now())) pruned++
                    log("[push] dropped invalid token device=${t.deviceId.take(8)}… platform=${t.platform}")
                }
                SendResult.FAILED -> {}
            }
        }
        if (accepted == 0) {
            val streak = consecutiveFullFailures.incrementAndGet()
            log("[push] WARN account=${account.take(8)}… all ${targets.size} send(s) failed (pruned=$pruned, consecutive=$streak)")
        } else {
            consecutiveFullFailures.set(0)
        }
    }
}
