package dev.ccpocket.daemon.review

import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.conversation.sinkKey
import dev.ccpocket.daemon.handoff.CollaboratorDirectory
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.ArtifactRef
import dev.ccpocket.protocol.ReviewBrief
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewUpdated
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * The wiring layer between [ReviewRegistry] and the transports, on the SENDER's machine
 * (REVIEW-REQUEST.md §5.1). Mirrors [dev.ccpocket.daemon.handoff.HandoffService]'s shape — attached
 * sinks, push on every transition, a periodic expiry pump on the core scope — with one difference that
 * matters: fan-out to a restricted sink is filtered by the RECIPIENT BINDING, not merely by frame type.
 *
 * A collaborator's sink is registered with the deviceId its credential proved, and only rows addressed
 * to exactly that device are ever sealed toward it. The [dev.ccpocket.daemon.handoff.CollaboratorCaps]
 * egress whitelist is the second gate, never the first: type-allowing pocket/review.updated without this
 * instance filter would broadcast every colleague's brief to every colleague.
 */
class ReviewService(
    val registry: ReviewRegistry = ReviewRegistry(),
) {
    private val log = logger("ReviewService")

    /** The owner's contact ledger, installed by RelayClient alongside the collaborator control plane.
     *  Null until then (and on a LAN-only `serve`): a create then cannot verify the binding is live and
     *  is refused — a request nobody can ever receive is worse than an honest error. */
    @Volatile
    var collaborators: CollaboratorDirectory? = null

    /** One fan-out target: the sink + the collaborator deviceId it is restricted to (null = a full-power
     *  owner device, which sees every row this machine sent). */
    private class Target(val sink: OutboundSink, val recipientDeviceId: String?)

    private val clients = ConcurrentHashMap<Any, Target>()

    fun attach(sink: OutboundSink, recipientDeviceId: String? = null) {
        clients[sinkKey(sink)] = Target(sink, recipientDeviceId)
    }

    fun detach(sink: OutboundSink) { clients.remove(sinkKey(sink)) }

    /** Push [changed] to every attached client its credential may see. Fail closed: a row with no
     *  recipient binding, or one addressed elsewhere, never reaches a restricted sink. */
    suspend fun broadcast(changed: List<ReviewRequest>) {
        if (changed.isEmpty()) return
        for (r in changed) {
            for (t in clients.values) {
                if (t.recipientDeviceId != null && r.recipientDeviceId != t.recipientDeviceId) continue
                runCatching { t.sink.emit(ReviewUpdated(r)) }
            }
        }
    }

    /**
     * The owner-plane create, shared by the local control API and the wire router so both apply ONE set
     * of rules. Resolves the recipient against the contact ledger (a severed link must fail the send,
     * not mint a request nobody can accept), then hands off to the registry for validation + persistence.
     */
    suspend fun send(
        senderDeviceId: String,
        senderLabel: String?,
        recipientDeviceId: String,
        title: String,
        brief: ReviewBrief,
        artifacts: List<ArtifactRef>,
        dueAt: Long? = null,
        expiresAt: Long? = null,
    ): ReviewRegistry.Outcome {
        val contacts = collaborators
            ?: return ReviewRegistry.Outcome.Refused(
                "review_unavailable",
                "the contact ledger isn't up yet — the daemon is still starting, or it's running LAN-only",
            )
        if (!contacts.acceptsReview(recipientDeviceId)) {
            return ReviewRegistry.Outcome.Refused(
                "review_no_recipient",
                "that collaborator link is gone — reconnect before sending a review request",
            )
        }
        val out = registry.create(
            senderDeviceId = senderDeviceId,
            senderLabel = senderLabel,
            recipientDeviceId = recipientDeviceId,
            recipientLabel = contacts.labelOf(recipientDeviceId),
            title = title,
            brief = brief,
            artifacts = artifacts,
            dueAt = dueAt,
            expiresAt = expiresAt,
        )
        if (out is ReviewRegistry.Outcome.Ok) broadcast(listOf(out.request))
        return out
    }

    /** Settle expiry and fan out whatever moved. Safe to call from anywhere. */
    suspend fun reconcile(now: Long = System.currentTimeMillis()) {
        val changed = registry.sweep(now)
        if (changed.isNotEmpty()) log.info("reconcile: ${changed.size} review request(s) expired")
        broadcast(changed)
    }

    /** The periodic expiry pump, run on [dev.ccpocket.daemon.DaemonCore.scope] like the handoff sweep. */
    suspend fun sweepLoop(intervalMs: Long = SWEEP_SCAN_MS) {
        while (true) {
            delay(intervalMs)
            runCatching { reconcile() }
        }
    }

    companion object {
        /** Expiry granularity here is hours/days, so a minute of slack is plenty. */
        const val SWEEP_SCAN_MS = 60_000L
    }
}
