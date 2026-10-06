package dev.ccpocket.relay.push

import dev.ccpocket.observability.*

import dev.ccpocket.relay.store.PushTarget
import dev.ccpocket.relay.store.RelayStore
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fan-out target the relay calls when an offline phone needs waking (a turn finished and no device
 * socket is live). Implementations resolve the account's registered tokens and deliver the alert.
 */
interface PushService {
    suspend fun notify(account: String, title: String, body: String, route: NotifyRoute? = null)

    /**
     * §3.4 TARGETED wake: deliver to [deviceId] ALONE — how a Collaborator Link inbox, deliberately absent
     * from [RelayStore.pushTargets], is reachable. Silently does nothing when the device is not [account]'s,
     * is revoked, or holds no token; the account check is enforced in the store, never here.
     */
    suspend fun notifyDevice(
        account: String,
        deviceId: String,
        title: String,
        body: String,
        route: NotifyRoute? = null,
    )
}

/** Default no-op provider — logs intent. Used when no APNs/FCM credentials are configured. */
class LoggingPushService : PushService {
    override suspend fun notify(account: String, title: String, body: String, route: NotifyRoute?) {
        println("[push] account=$account offline — would notify: \"$title — $body\" route=$route")
    }

    override suspend fun notifyDevice(account: String, deviceId: String, title: String, body: String, route: NotifyRoute?) {
        println("[push] account=$account device=${deviceId.take(8)}… offline — would notify: \"$title — $body\" route=$route")
    }
}

/**
 * Looks up the account's registered push tokens and dispatches each to the [PushSender] for its
 * platform. Sends sequentially (a personal/small-team account has a handful of devices). A permanently
 * dead token (APNs 410 / FCM 404) is pruned from the store so we stop hammering it; a transient failure
 * (thrown I/O, 429/5xx, timeout) is re-attempted [transientRetries] times, [retryDelayMs] apart, and then left
 * in place — nothing re-sends a lost turn-end or approval alert later, so one "Connection reset" used to lose
 * it outright. A fan-out where *no* device accepts is escalated to a WARN with a running streak — the
 * "silently 410-rotted for a month" state now shows up loudly in the logs. Every accepted delivery logs one
 * content-free line (platform, device/token prefixes, route kind + session-id prefix — never title or body),
 * so "did the relay even send it" is answerable without inferring from the absence of a failure.
 */
class StorePushService(
    private val store: RelayStore,
    private val senders: Map<String, PushSender>,
    private val now: () -> Long = System::currentTimeMillis,
    private val transientRetries: Int = 1,
    private val retryDelayMs: Long = 500L,
    private val log: (String) -> Unit = ::println,
) : PushService {
    /** Consecutive fully-failed fan-outs across the relay — a coarse "push is 100% down" smoke alarm. */
    private val consecutiveFullFailures = AtomicInteger(0)

    override suspend fun notify(account: String, title: String, body: String, route: NotifyRoute?) {
        val targets = store.pushTargets(account)
        if (targets.isEmpty()) {
            Diagnostics.push(Stage.DISPATCH, ErrorCode.NO_TOKEN)
            // the silent dead-end that hid a whole class of "my phone never buzzes": the relay got the
            // NotifyPush and had NOWHERE to send it (no registered token — the app never registered, or
            // every token was pruned after a 410). Now it says so.
            log("[push] account=${account.take(8)}… has NO registered tokens — dropping \"$title\"")
            return
        }
        fanOut(account, targets, title, body, route)
    }

    override suspend fun notifyDevice(account: String, deviceId: String, title: String, body: String, route: NotifyRoute?) {
        val target = store.pushTargetFor(account, deviceId)
        if (target == null) {
            Diagnostics.push(Stage.DISPATCH, ErrorCode.NO_TOKEN)
            // the recipient turned notifications off, never registered, was revoked — or the deviceId simply
            // is not this account's. Nothing to do; the offer is still waiting on the daemon for the next pull.
            log("[push] account=${account.take(8)}… device=${deviceId.take(8)}… has NO registered token — dropping \"$title\"")
            return
        }
        fanOut(account, listOf(target), title, body, route)
    }

    /** Deliver to each [targets] entry, pruning permanently-dead tokens and tracking the failure streak. */
    private suspend fun fanOut(
        account: String,
        targets: List<PushTarget>,
        title: String,
        body: String,
        route: NotifyRoute?,
    ) {
        var accepted = 0
        var pruned = 0
        for (t in targets) {
            val sender = senders[t.platform]
            if (sender == null) { log("[push] no sender for platform=${t.platform} (device=${t.deviceId.take(8)}…)"); continue }
            val result = sendWithRetry(sender, t, title, body, route)
            when (result) {
                SendResult.ACCEPTED -> {
                    accepted++
                    log(
                        "[push] sent platform=${t.platform} device=${t.deviceId.take(8)}… token=${t.token.take(6)}… " +
                            "kind=${route?.kind ?: "-"} sid=${route?.sessionId?.take(8) ?: "-"}",
                    )
                }
                SendResult.INVALID_TOKEN -> {
                    if (store.clearPushToken(t.deviceId, t.platform, t.token, now())) pruned++
                    log("[push] dropped invalid token device=${t.deviceId.take(8)}… platform=${t.platform}")
                }
                SendResult.FAILED -> {}
            }
        }
        Diagnostics.report(ErrorPath.PUSH, Stage.DISPATCH,
            if (accepted == targets.size) ErrorCode.OK else if (accepted > 0) ErrorCode.PARTIAL_RESULT else ErrorCode.SEND_FAILED,
            metrics = SafeMetrics(totalCount = targets.size.toLong(), returnedCount = accepted.toLong(),
                failedCount = (targets.size - accepted).toLong(), resultQuality = if (accepted == targets.size) ResultQuality.COMPLETE else ResultQuality.PARTIAL))
        // Provider acceptance is the only observed delivery fact; no claim about phone display.
        if (pruned > 0) Diagnostics.report(ErrorPath.PUSH, Stage.RECONCILE, ErrorCode.EXPIRED,
            metrics = SafeMetrics(totalCount = pruned.toLong()))
        if (accepted == 0) {
            val streak = consecutiveFullFailures.incrementAndGet()
            log("[push] WARN account=${account.take(8)}… all ${targets.size} send(s) failed (pruned=$pruned, consecutive=$streak)")
        } else {
            consecutiveFullFailures.set(0)
        }
    }

    /** One attempt. Thrown I/O is a transient [SendResult.FAILED]: a sender never throws for a gateway rejection. */
    private suspend fun sendOnce(sender: PushSender, t: PushTarget, title: String, body: String, route: NotifyRoute?): SendResult =
        runCatching { sender.send(t.token, title, body, route) }
            .getOrElse { Diagnostics.report(ErrorPath.PUSH, Stage.DISPATCH, ErrorCode.SEND_FAILED, it); log("[push] send failed platform=${t.platform}: ${it.message}"); SendResult.FAILED }

    /**
     * [sendOnce], re-attempted up to [transientRetries] times after a transient outcome — thrown I/O such as
     * "Connection reset", or a FAILED gateway answer (429/5xx/timeout). [SendResult.INVALID_TOKEN] is final and
     * never retried. Bounded and sequential: a provider that is down costs one short delay per target, not a queue.
     */
    private suspend fun sendWithRetry(sender: PushSender, t: PushTarget, title: String, body: String, route: NotifyRoute?): SendResult {
        var result = sendOnce(sender, t, title, body, route)
        var attempt = 1
        while (result == SendResult.FAILED && attempt <= transientRetries) {
            attempt++
            delay(retryDelayMs)
            log("[push] retry attempt=$attempt platform=${t.platform} device=${t.deviceId.take(8)}…")
            result = sendOnce(sender, t, title, body, route)
        }
        return result
    }
}
