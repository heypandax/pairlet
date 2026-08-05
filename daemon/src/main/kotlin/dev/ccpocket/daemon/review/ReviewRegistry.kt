package dev.ccpocket.daemon.review

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.ArtifactRef
import dev.ccpocket.protocol.DEFAULT_REVIEW_EXPIRES_SEC
import dev.ccpocket.protocol.ReviewBrief
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewResult
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.isTerminal
import java.security.SecureRandom
import java.util.Base64

/**
 * The daemon-local authority on ReviewRequest state (REVIEW-REQUEST.md §8) — the ONLY place a
 * [ReviewStatus] transition is decided, on the SENDER's machine. The recipient's mirror is a follower
 * and never invents a transition this registry did not confirm.
 *
 * Invariants:
 *  1. [ReviewRequest.revision] starts at 1 and strictly increases on every REAL transition. A duplicate
 *     idempotency key, or a mutation whose target status the row already holds, returns the applied row
 *     with its revision UNCHANGED — so a retry can neither duplicate a result nor bump a version the
 *     recipient would then treat as news;
 *  2. the transition table in [canTransition] is exhaustive and terminal states are absorbing. Nothing
 *     ever leaves CLOSED / DECLINED / CANCELLED / EXPIRED, and nothing ever leaves UNKNOWN either — a
 *     status this build cannot read stays locked rather than being guessed at;
 *  3. every recipient-plane mutation is bound to the recipient device the request is ADDRESSED to. The
 *     caller passes the TRANSPORT-authenticated deviceId; a payload field is never an identity;
 *  4. identity and time on a returned [ReviewResult] are stamped here, overwriting whatever the
 *     recipient's draft claimed.
 *
 * Expiry is clock-driven and pull-based, exactly like [dev.ccpocket.daemon.handoff.HandoffRegistry]:
 * callers run [sweep] periodically and reads settle it first, so no gate ever honours a request the
 * clock has already outrun. The clock and the id source are injected for tests.
 */
class ReviewRegistry(
    private val store: ReviewStore = ReviewStore.load(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = ::randomRequestId,
) {
    private val log = logger("ReviewRegistry")

    /** The answer to every mutation. [changed] is false for an idempotent repeat — the caller still
     *  answers the peer with the authoritative row, but must not fan it out as a new transition. */
    sealed interface Outcome {
        data class Ok(val request: ReviewRequest, val changed: Boolean = true) : Outcome
        data class Refused(val code: String, val message: String) : Outcome
    }

    private fun refuse(code: String, message: String) = Outcome.Refused(code, message)

    // ---- creation (owner plane) -------------------------------------------

    /**
     * Create + send in one step (there is no persisted DRAFT: the CLI/Skill confirms before calling).
     * Identity comes from the caller, which reads it off the authenticated transport. Refuses on any
     * [ReviewLimits] violation, an unbound recipient, or a full active queue — never by truncating.
     */
    @Synchronized
    fun create(
        senderDeviceId: String,
        senderLabel: String?,
        recipientDeviceId: String,
        recipientLabel: String?,
        title: String,
        brief: ReviewBrief,
        artifacts: List<ArtifactRef>,
        dueAt: Long? = null,
        expiresAt: Long? = null,
    ): Outcome {
        val now = clock()
        sweepLocked(now)
        if (recipientDeviceId.isBlank()) return refuse("review_no_recipient", "a review request needs a recipient")
        if (artifacts.isEmpty()) return refuse("review_no_artifact", "a review request needs at least one artifact (--artifact)")
        if (store.activeCount() >= ReviewStore.MAX_ACTIVE) {
            // fail closed rather than evicting: every one of these is work someone is waiting on
            return refuse("review_store_full", "too many open review requests (${ReviewStore.MAX_ACTIVE}) — close some first")
        }
        val request = ReviewRequest(
            id = newId(),
            senderDeviceId = senderDeviceId,
            senderLabel = senderLabel,
            recipientDeviceId = recipientDeviceId,
            recipientLabel = recipientLabel,
            title = title.ifBlank { brief.request.take(ReviewLimits.MAX_TITLE) },
            brief = brief,
            artifacts = artifacts,
            status = ReviewStatus.QUEUED,
            revision = 1,
            createdAt = now,
            updatedAt = now,
            dueAt = dueAt,
            expiresAt = expiresAt ?: (now + DEFAULT_REVIEW_EXPIRES_SEC * 1000),
        )
        ReviewLimits.request(request)?.let { return refuse("review_invalid", it) }
        if (!store.put(request)) return persistFailed()
        if (!store.pruneHistory()) log.warn("review history pruning could not be persisted; the new request remains durable")
        log.info("review ${request.id} QUEUED for ${recipientDeviceId.take(8)}… (${artifacts.size} artifact(s))")
        return Outcome.Ok(request)
    }

    // ---- recipient plane --------------------------------------------------

    /** QUEUED → DELIVERED. Sent by the recipient daemon only AFTER its own atomic persist (§4.3): a
     *  relay write that never reached the recipient's disk must never read as delivered. */
    @Synchronized
    fun markDelivered(id: String, deviceId: String, key: String = ""): Outcome =
        recipientTransition(id, deviceId, key, ReviewStatus.DELIVERED) { it }

    /** DELIVERED → ACKNOWLEDGED ("I'll take this"). */
    @Synchronized
    fun acknowledge(id: String, deviceId: String, key: String = ""): Outcome =
        recipientTransition(id, deviceId, key, ReviewStatus.ACKNOWLEDGED) { it }

    /** DELIVERED/ACKNOWLEDGED → IN_PROGRESS ("reviewing now"). */
    @Synchronized
    fun start(id: String, deviceId: String, key: String = ""): Outcome =
        recipientTransition(id, deviceId, key, ReviewStatus.IN_PROGRESS) { it }

    /** → DECLINED, with the recipient's own reason. */
    @Synchronized
    fun decline(id: String, deviceId: String, reason: String?, key: String = ""): Outcome {
        ReviewLimits.text(reason, ReviewLimits.MAX_TEXT, "reason")?.let { return refuse("review_invalid", it) }
        return recipientTransition(id, deviceId, key, ReviewStatus.DECLINED) { it.copy(declineReason = reason) }
    }

    /**
     * → RESPONDED with the structured result. May skip acknowledge/start entirely — a light review that
     * answers straight from DELIVERED is a first-class path (§8).
     *
     * A REPEAT never overwrites: once a row is RESPONDED the stored result is what the reviewer stands
     * behind, and a retried/replayed frame returns it unchanged. Identity and time are stamped here from
     * the authenticated transport, discarding whatever the draft claimed.
     */
    @Synchronized
    fun respond(id: String, deviceId: String, result: ReviewResult, key: String = ""): Outcome {
        ReviewLimits.result(result)?.let { return refuse("review_invalid", it) }
        val now = clock()
        return recipientTransition(id, deviceId, key, ReviewStatus.RESPONDED) {
            it.copy(result = result.copy(respondedByDeviceId = deviceId, respondedAt = now))
        }
    }

    /**
     * The one path every recipient mutation takes: settle the clock, resolve the row, enforce the
     * recipient binding, apply idempotency, check the transition table, bump the revision, persist.
     *
     * "No such request" and "not addressed to you" deliberately share one refusal: a bound recipient
     * must not be able to probe the sender's ledger for ids it was never given.
     */
    private fun recipientTransition(
        id: String,
        deviceId: String,
        key: String,
        to: ReviewStatus,
        apply: (ReviewRequest) -> ReviewRequest,
    ): Outcome {
        ReviewLimits.opaqueId(id, "request id")?.let { return refuse("review_invalid", it) }
        // a blank key means "this peer does not do idempotency"; a present one must be opaque like the id
        if (key.isNotEmpty()) ReviewLimits.opaqueId(key, "idempotency key")?.let { return refuse("review_invalid", it) }
        val now = clock()
        sweepLocked(now)
        val r = store.byId(id)
        if (r == null || r.recipientDeviceId != deviceId || deviceId.isBlank()) {
            return refuse("review_not_allowed", "no review request with that id is addressed to you")
        }
        if (isOverdue(r, now)) return persistFailed()
        // an already-applied key, or a row that already holds the target status, is a REPEAT: answer with
        // the authoritative row and do not move the revision (invariant 1)
        if (store.wasApplied(id, key)) return Outcome.Ok(r, changed = false)
        if (r.status == to) return Outcome.Ok(r, changed = false)
        if (!canTransition(r.status, to)) {
            return refuse("review_bad_transition", "this request is ${r.status.name.lowercase()} — it cannot become ${to.name.lowercase()}")
        }
        val next = apply(r).copy(status = to, revision = r.revision + 1, updatedAt = now)
        ReviewLimits.request(next)?.let { return refuse("review_invalid", it) }
        if (!store.put(next, key)) return persistFailed()
        log.info("review $id ${r.status.name} → ${to.name} by ${deviceId.take(8)}…")
        return Outcome.Ok(next)
    }

    // ---- owner plane ------------------------------------------------------

    /** Withdraw a request the recipient has not started (QUEUED/DELIVERED/ACKNOWLEDGED → CANCELLED).
     *  Once work or a result exists the sender closes it instead — cancelling then would discard a
     *  colleague's effort behind their back. */
    @Synchronized
    fun cancel(id: String): Outcome = ownerTransition(id, ReviewStatus.CANCELLED)

    /** RESPONDED → CLOSED: the sender acknowledged the result. The terminal happy path. */
    @Synchronized
    fun close(id: String): Outcome = ownerTransition(id, ReviewStatus.CLOSED)

    private fun ownerTransition(id: String, to: ReviewStatus): Outcome {
        ReviewLimits.opaqueId(id, "request id")?.let { return refuse("review_invalid", it) }
        val now = clock()
        sweepLocked(now)
        val r = store.byId(id) ?: return refuse("review_not_found", "no such review request")
        if (isOverdue(r, now)) return persistFailed()
        if (r.status == to) return Outcome.Ok(r, changed = false)
        if (!canTransition(r.status, to)) {
            return refuse("review_bad_transition", "this request is ${r.status.name.lowercase()} — it cannot become ${to.name.lowercase()}")
        }
        val next = r.copy(status = to, revision = r.revision + 1, updatedAt = now)
        if (!store.put(next)) return persistFailed()
        log.info("review $id ${r.status.name} → ${to.name} by the owner")
        return Outcome.Ok(next)
    }

    // ---- reads ------------------------------------------------------------

    @Synchronized
    fun byId(id: String): ReviewRequest? {
        sweepLocked(clock())
        return store.byId(id)
    }

    /**
     * Every request, newest first, optionally filtered. [recipientDeviceId] is the CREDENTIAL filter the
     * router passes for a collaborator caller: it sees only rows addressed to its own device — never the
     * owner's other requests, and never a row that merely mentions it.
     */
    @Suppress("UNUSED_PARAMETER")
    @Synchronized
    fun list(
        status: ReviewStatus? = null,
        recipientDeviceId: String? = null,
        sinceRevision: Long = 0,
    ): List<ReviewRequest> {
        sweepLocked(clock())
        val visible = store.all()
            .filter { recipientDeviceId == null || it.recipientDeviceId == recipientDeviceId }
            .filter { status == null || it.status == status }
            // Revisions are per-request, not a global cursor. M1 therefore always replays the bounded
            // ledger; treating this field as a global high-water mark would hide a newly-created row
            // whose own revision starts at 1. Keep the wire field reserved for a future ledger cursor.
        // A single ReviewListing must stay below the relay's 4 MiB frame cap. Open work goes first so
        // bounded historical replay can never hide something a colleague is still waiting on.
        val ordered = visible.sortedWith(compareBy<ReviewRequest> { it.status.isTerminal }.thenByDescending { it.createdAt })
        var bytes = LISTING_OVERHEAD_BYTES
        return buildList {
            for (request in ordered) {
                val rowBytes = PocketJson.encodeToString(ReviewRequest.serializer(), request).toByteArray(Charsets.UTF_8).size + 1
                if (bytes + rowBytes > MAX_LISTING_BYTES) continue
                add(request)
                bytes += rowBytes
            }
        }
    }

    // ---- expiry -----------------------------------------------------------

    /** Settle everything the clock has outrun. Returns the rows that transitioned, for fan-out. */
    @Synchronized
    fun sweep(now: Long = clock()): List<ReviewRequest> = sweepLocked(now)

    private fun sweepLocked(now: Long): List<ReviewRequest> {
        val changed = mutableListOf<ReviewRequest>()
        for (r in store.all()) {
            val deadline = r.expiresAt ?: continue
            if (deadline > now) continue
            if (!EXPIRABLE.contains(r.status)) continue // RESPONDED keeps its result; terminal/UNKNOWN stay put
            val expired = r.copy(status = ReviewStatus.EXPIRED, revision = r.revision + 1, updatedAt = now)
            if (!store.put(expired)) {
                log.warn("review ${r.id} could not be expired because the ledger was not persisted")
                continue
            }
            log.info("review ${r.id} EXPIRED (was ${r.status.name.lowercase()})")
            changed += expired
        }
        return changed
    }

    private fun isOverdue(r: ReviewRequest, now: Long): Boolean =
        r.status in EXPIRABLE && r.expiresAt?.let { it <= now } == true

    private fun persistFailed(): Outcome.Refused = refuse(
        "review_persist_failed",
        "the review ledger could not be saved — no state was changed; check daemon logs and disk access",
    )

    companion object {
        /** The transition table of §8, in one place. Terminal states and UNKNOWN are absorbing. */
        fun canTransition(from: ReviewStatus, to: ReviewStatus): Boolean {
            if (from.isTerminal || from == ReviewStatus.UNKNOWN || to == ReviewStatus.UNKNOWN) return false
            return when (from) {
                ReviewStatus.QUEUED -> to == ReviewStatus.DELIVERED || to == ReviewStatus.CANCELLED || to == ReviewStatus.EXPIRED
                ReviewStatus.DELIVERED -> to == ReviewStatus.ACKNOWLEDGED || to == ReviewStatus.IN_PROGRESS ||
                    to == ReviewStatus.RESPONDED || to == ReviewStatus.DECLINED ||
                    to == ReviewStatus.CANCELLED || to == ReviewStatus.EXPIRED
                ReviewStatus.ACKNOWLEDGED -> to == ReviewStatus.IN_PROGRESS || to == ReviewStatus.RESPONDED ||
                    to == ReviewStatus.DECLINED || to == ReviewStatus.CANCELLED || to == ReviewStatus.EXPIRED
                // work has started: the sender can no longer withdraw it behind the reviewer's back
                ReviewStatus.IN_PROGRESS -> to == ReviewStatus.RESPONDED || to == ReviewStatus.DECLINED || to == ReviewStatus.EXPIRED
                ReviewStatus.RESPONDED -> to == ReviewStatus.CLOSED
                else -> false
            }
        }

        /** The states an expiry sweep may settle. RESPONDED is excluded on purpose — a result exists,
         *  and expiring it would throw away work the sender still has to read. */
        private val EXPIRABLE = setOf(
            ReviewStatus.QUEUED, ReviewStatus.DELIVERED, ReviewStatus.ACKNOWLEDGED, ReviewStatus.IN_PROGRESS,
        )

        private val RNG = SecureRandom()
        private val B64 = Base64.getUrlEncoder().withoutPadding()

        private const val MAX_LISTING_BYTES = 3_500_000
        private const val LISTING_OVERHEAD_BYTES = 256

        /** `rr_<22 chars of base64url>` — 128 random bits. Random rather than sequential so an id never
         *  leaks how many requests this machine has sent, and never collides across peers. */
        fun randomRequestId(): String = "rr_" + B64.encodeToString(ByteArray(16).also(RNG::nextBytes))
    }
}
