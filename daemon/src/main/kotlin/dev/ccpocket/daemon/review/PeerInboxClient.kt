package dev.ccpocket.daemon.review

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListReviewRequests
import dev.ccpocket.protocol.MarkReviewDelivered
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ReviewListing
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.ReviewUpdated
import dev.ccpocket.protocol.ToDaemon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * ONE inbound peer link's connection, on the RECIPIENT's machine (REVIEW-REQUEST.md §9): we hold a
 * restricted credential in the peer's account, so we connect to THEIR daemon as a device and act as a
 * review inbox. Nothing else rides this link — no session, no directory, no prompt.
 *
 * The two rules that make the offline story work:
 *
 *  1. **PERSIST BEFORE ACK.** A request is written to the local mirror atomically and only then is
 *     `review.delivered` sent. A relay write that never reached our disk therefore cannot read as
 *     delivered on the sender's side, which is exactly what [ReviewStatus.QUEUED] means (§8).
 *  2. **THE OUTBOX IS THE TRUTH ABOUT OUR INTENT.** Our acknowledge/decline/response is persisted
 *     before it is sent and is only dropped once the SENDER's authoritative row proves it landed. A
 *     dead relay, a closed laptop or a daemon restart therefore lose nothing; a duplicate send is
 *     harmless because every item carries an idempotency key the sender remembers.
 *
 * Reconnects are the ordinary exponential ladder with equal jitter, and each connection begins with an
 * explicit [ListReviewRequests] — replay, not a live push, is what makes an offline recipient converge
 * (§10: "不依赖在线瞬时推送").
 */
class PeerInboxClient(
    private var link: PeerLink,
    private val links: PeerLinkStore,
    private val store: PeerInboxStore,
    private val transport: PeerTransport,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Injected so tests don't sleep the production ladder. */
    private val backoffMs: (attempt: Int) -> Long = ::defaultBackoff,
    /** How often an OPEN connection re-sends whatever the outbox still holds. Injected for the same
     *  reason as [backoffMs]: a test proves the retry without waiting for it. */
    private val resendIntervalMs: Long = RESEND_INTERVAL_MS,
) {
    private val log = logger("PeerInbox")

    /** Conflated: many local mutations between two flushes still mean "flush once, now". */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var authenticatedThisRun = false

    /** A local mutation was queued — flush it immediately if a channel is live (otherwise the next
     *  connect's opening flush picks it up). Never suspends the caller on the network. */
    fun kick() { wake.trySend(Unit) }

    /**
     * Run until cancelled. Returns early — permanently — once the link has no secret left: `remove`
     * deletes the key material, so there is nothing to reconnect with and retrying would be a busy loop
     * against a credential that no longer exists.
     */
    suspend fun run() {
        var attempt = 0
        while (coroutineContext.isActive) {
            val current = links.byId(link.id)
            if (current == null || current.removed) {
                log.info("peer link \"${link.label}\" removed — inbox stopped")
                return
            }
            link = current
            // A pinned key this build cannot parse can never complete a handshake, so dialling it is a
            // busy loop by construction. Joining validates the key ([validDaemonPub]); this catches a
            // row that predates that check or was hand-edited, and stops rather than retrying forever.
            if (!validDaemonPub(link.peerDaemonPub)) {
                log.warn("peer link \"${link.label}\" pins an unusable daemon key — inbox stopped; remove and re-join the link")
                return
            }
            // persist the attempt BEFORE dialling: [PeerHandshake.psk] alternates on this counter, and an
            // attempt whose outcome we never learn still has to move it
            val secret = links.beginHandshake(link.id) ?: run {
                log.warn("peer link \"${link.label}\" has no credential — inbox stopped")
                return
            }
            try {
                connectOnce(secret)
                attempt = 0 // a connection that lived is a healthy one; start the ladder over
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Class only: a peer-originated exception message can quote request content (§11.4).
                log.warn("peer \"${link.label}\" inbox link lost (${t::class.simpleName})")
            }
            delay(backoffMs(attempt++))
        }
    }

    private suspend fun connectOnce(secret: PeerLinkSecret) {
        var flusher: Job? = null
        var resender: Job? = null
        authenticatedThisRun = false
        try {
            transport.dial(
                link, secret,
                object : PeerSession {
                    override suspend fun onOpen(channel: PeerChannel) {
                        // replay first (§9 item 5): the sender kept everything while we were away
                        channel.send(ListReviewRequests())
                        flush(channel)
                        flusher = scope.launch {
                            for (ignored in wake) runCatching { flush(channel) }
                        }
                        // A LIVE socket is not a delivered frame. Losing one flush (a relay hiccup, a
                        // frame dropped between two healthy endpoints) would otherwise leave the item
                        // waiting for the next local action or the next reconnect — which, for the
                        // delivery ACK, means the colleague reads QUEUED indefinitely while our inbox
                        // shows the request. Re-flush on a slow timer for as long as we are connected;
                        // it is a no-op whenever the outbox is empty, which is the normal case.
                        resender = scope.launch {
                            while (true) {
                                delay(resendIntervalMs)
                                runCatching { flush(channel) }
                            }
                        }
                    }

                    override suspend fun onFrame(channel: PeerChannel, frame: Frame) = absorb(channel, frame)
                },
            )
        } finally {
            flusher?.cancel()
            resender?.cancel()
        }
    }

    // ---- inbound ----------------------------------------------------------

    private suspend fun absorb(channel: PeerChannel, frame: Frame) {
        // the first frame we could decrypt proves the credential AND the static key work: the one-time
        // ticket has done its job and must not stay redeemable on disk (§11.4)
        if (!authenticatedThisRun) {
            authenticatedThisRun = links.clearTicket(link.id)
            if (!authenticatedThisRun) {
                log.warn("peer \"${link.label}\" authenticated, but its one-time ticket could not be cleared")
            }
        }
        when (frame) {
            is ReviewListing -> frame.items.forEach { mirror(channel, it) }
            is ReviewUpdated -> mirror(channel, frame.request)
            // code only: a refusal message can echo the request the peer is refusing about
            is PocketError -> log.warn("peer \"${link.label}\" refused a review mutation (${frame.code})")
            else -> {}
        }
    }

    /**
     * The persist-then-ACK path. Everything the peer sends is untrusted input: it is bounds-checked and
     * address-checked BEFORE it touches disk, and a row that fails either is dropped (never ACKed), so
     * the sender keeps it QUEUED and a human eventually notices rather than a malformed request quietly
     * becoming part of our state.
     */
    private suspend fun mirror(channel: PeerChannel, request: ReviewRequest) {
        ReviewLimits.request(request)?.let {
            log.warn("peer \"${link.label}\" sent a request this daemon refuses to store: $it")
            return
        }
        if (request.recipientDeviceId != link.deviceId) {
            log.warn("peer \"${link.label}\" sent a request addressed to another device — dropped")
            return
        }
        val mirrored = store.mirror(link.id, request, clock()) // ATOMIC PERSIST — happens before any ACK
        when (mirrored) {
            PeerInboxStore.MirrorResult.FULL -> {
                log.warn("peer \"${link.label}\" sent more open requests than this inbox can store — left unacknowledged")
                return
            }
            PeerInboxStore.MirrorResult.PERSIST_FAILED -> {
                log.warn("peer \"${link.label}\" review could not be persisted — left unacknowledged")
                return
            }
            PeerInboxStore.MirrorResult.STORED, PeerInboxStore.MirrorResult.UNCHANGED -> Unit
        }
        // From this point on use only the row we know is durable. An older replay must not cause an
        // ACK or outbox reconciliation based on a stale incoming status.
        val durable = store.row(link.id, request.id)?.request ?: return
        // reconcile our outbox against the authoritative row: anything the sender has already applied
        // (or made moot by going terminal) stops being retried
        val settled = store.pendingFor(link.id, request.id)
            .filter { PeerInboxStore.satisfied(it.expect, durable.status) }
        if (!store.dropOutbox(settled.mapTo(HashSet()) { it.id })) {
            log.warn("peer \"${link.label}\": confirmed review actions could not be cleared from the outbox")
        }
        if (durable.status == ReviewStatus.QUEUED) enqueueDeliveryAck(channel, durable.id)
        if (mirrored == PeerInboxStore.MirrorResult.STORED) {
            log.info("peer \"${link.label}\": review ${durable.id} → ${durable.status.name.lowercase()}")
        }
    }

    /**
     * "I have this on disk" as DURABLE INTENT rather than a best-effort frame (§8: a relay write is not
     * delivery, and neither is one hopeful send).
     *
     * It goes through the same outbox as an acknowledge or a returned result, and for the same reason:
     * the ACK is the ONLY thing that moves the colleague's row off QUEUED, so losing it strands them on
     * "they haven't got it yet" while our inbox already shows the request — a disagreement no human can
     * see the cause of. Persisted here, it is retried on the live socket, on every reconnect and across
     * restarts, and is dropped only when the sender's own row reads DELIVERED or later
     * ([PeerInboxStore.satisfied], via the reconciliation above).
     *
     * The item id and the idempotency key are both DERIVED, not random: re-mirroring the same request
     * (a replay, a restart, a second listing) must reuse the one pending ACK, and every retry of it must
     * collapse into a single transition on the sender's side.
     */
    private suspend fun enqueueDeliveryAck(channel: PeerChannel, requestId: String) {
        val item = OutboxItem(
            id = deliveryAckId(link.id, requestId),
            linkId = link.id,
            requestId = requestId,
            frameJson = PocketJson.encodeToString(
                ToDaemon.serializer(),
                MarkReviewDelivered(requestId, idempotencyKey = DELIVERY_ACK_KEY),
            ),
            expect = ReviewStatus.DELIVERED,
            idempotencyKey = DELIVERY_ACK_KEY,
            queuedAt = clock(),
        )
        when (store.enqueueOnce(item)) {
            PeerInboxStore.EnqueueResult.STORED -> kick()
            // The outbox is full of the user's own unsent reviews, or the disk refused. Neither is worth
            // dropping one of those for, so say so and fall back to a single hopeful send: it is exactly
            // what this path did before it was made durable, and the periodic re-mirror retries it.
            PeerInboxStore.EnqueueResult.FULL, PeerInboxStore.EnqueueResult.PERSIST_FAILED -> {
                log.warn("peer \"${link.label}\": delivery ACK could not be queued durably — sending it best-effort")
                runCatching { channel.send(MarkReviewDelivered(requestId, idempotencyKey = DELIVERY_ACK_KEY)) }
            }
        }
    }

    // ---- outbound ---------------------------------------------------------

    /** (Re)send every unconfirmed local mutation for this link, oldest first. A send that throws means
     *  the channel is going down — stop and let the reconnect retry from persisted state. */
    private suspend fun flush(channel: PeerChannel) {
        val items = store.outboxOf(link.id).sortedBy { it.queuedAt }
        if (items.isEmpty()) return
        val undecodable = HashSet<String>()
        val attempted = HashSet<String>()
        for (item in items) {
            val frame = item.frame()
            if (frame == null) {
                // a build change made this item unreadable; retrying it forever would wedge the queue
                undecodable += item.id
                continue
            }
            if (item.attempts == RETRY_WARN_AT) {
                log.warn("peer \"${link.label}\": review ${item.requestId} still unconfirmed after ${item.attempts} attempts")
            }
            try {
                channel.send(frame)
                attempted += item.id
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                break
            }
        }
        if (undecodable.isNotEmpty()) {
            if (store.dropOutbox(undecodable)) {
                log.warn("peer \"${link.label}\": dropped ${undecodable.size} undecodable outbox item(s)")
            } else {
                log.warn("peer \"${link.label}\": could not persist removal of undecodable outbox item(s)")
            }
        }
        if (!store.bumpAttempts(attempted)) {
            log.warn("peer \"${link.label}\": could not persist outbox retry counters")
        }
    }

    companion object {
        /** Equal jitter over a 1s→30s ladder — the same shape [dev.ccpocket.daemon.relay.RelayClient]
         *  uses, so a fleet of inbox links does not reconnect in lockstep. */
        fun defaultBackoff(attempt: Int): Long {
            val base = (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)
            return base / 2 + Random.nextLong(base / 2 + 1)
        }

        /** Say something once an item looks stuck. Not a drop threshold: the user's review must survive
         *  an arbitrarily long peer outage. */
        const val RETRY_WARN_AT = 10

        /** How often an open connection re-sends its unconfirmed outbox. Slow on purpose: this is the
         *  belt to the reconnect flush's braces, not the primary delivery path. */
        const val RESEND_INTERVAL_MS = 30_000L

        /** The delivery ACK's idempotency key. CONSTANT rather than derived from the id: the sender
         *  scopes applied keys per request ([ReviewStore.wasApplied]), so one word is already unique
         *  where it is used, it satisfies [ReviewLimits.opaqueId], and it cannot outgrow the id cap. */
        const val DELIVERY_ACK_KEY = "delivered"

        /** The outbox id for a link's delivery ACK. Derived so re-mirroring the same request reuses the
         *  one pending item instead of queueing another. */
        fun deliveryAckId(linkId: String, requestId: String): String = "dlv/$linkId/$requestId"
    }
}
