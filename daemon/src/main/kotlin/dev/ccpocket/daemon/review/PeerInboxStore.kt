package dev.ccpocket.daemon.review

import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.isTerminal
import kotlinx.serialization.Serializable
import java.io.File

/**
 * One mirrored ReviewRequest on the RECIPIENT's machine (REVIEW-REQUEST.md §9 item 4).
 *
 * EXPLICITLY NON-AUTHORITATIVE. [request] is the last row the sender's daemon sent, byte for byte; this
 * daemon never edits its status, never invents a transition, and overwrites the whole thing whenever a
 * higher [ReviewRequest.revision] arrives. What the recipient WANTS to happen lives in the outbox, not
 * here — which is why a queued acknowledge shows up as "pending" rather than as an acknowledged row
 * that might never be confirmed.
 *
 * Keyed by ([linkId], [ReviewRequest.id]): two peers may legitimately mint the same request id, and
 * neither may overwrite the other's row.
 */
@Serializable
data class MirrorRow(
    val linkId: String,
    val request: ReviewRequest,
    val mirroredAt: Long,
)

/**
 * One recipient-side mutation that has not yet been confirmed by the sender's authoritative reply
 * (§9 item 6). Persisted BEFORE it is sent, so a daemon restart, a dead relay or a sleeping laptop
 * cannot silently swallow "I declined this" or a finished review result.
 *
 * [frameJson] is the exact [ToDaemon] frame to (re)send — storing the frame rather than a re-derived
 * intent means a retry after an upgrade replays what the user actually asked for. [expect] is the status
 * the sender's row must reach for this item to be considered settled; see [PeerInboxStore.satisfied].
 */
@Serializable
data class OutboxItem(
    val id: String,
    val linkId: String,
    val requestId: String,
    val frameJson: String,
    val expect: ReviewStatus,
    val idempotencyKey: String,
    val queuedAt: Long,
    val attempts: Int = 0,
) {
    /** Decode back to the frame to send. Null when a build change made it unreadable — the caller drops
     *  it with a log rather than retrying an undecodable item forever. */
    fun frame(): ToDaemon? = runCatching { PocketJson.decodeFromString(ToDaemon.serializer(), frameJson) }.getOrNull()
}

/**
 * Persistence for the recipient's mirror + outbox: `~/.cc-pocket/review-inbox.json` (0600, atomic).
 *
 * Both collections are bounded, with the same asymmetry as [ReviewStore]: mirrored TERMINAL rows are
 * pruned oldest-first (history, cheap to lose), while the OUTBOX fails closed when full. Dropping a
 * pending "here is my review" to make room for another one would be the single worst thing this file
 * could do.
 */
class PeerInboxStore private constructor(private val path: File?) {

    enum class MirrorResult { STORED, UNCHANGED, FULL, PERSIST_FAILED }
    enum class EnqueueResult { STORED, FULL, PERSIST_FAILED }

    @Serializable
    private data class Stored(
        val v: Int = 1,
        val rows: List<MirrorRow> = emptyList(),
        val outbox: List<OutboxItem> = emptyList(),
    )

    private val lock = Any()
    private var state: Stored = Stored()

    fun rows(): List<MirrorRow> = synchronized(lock) { state.rows }

    fun rowsOf(linkId: String): List<MirrorRow> = synchronized(lock) { state.rows.filter { it.linkId == linkId } }

    fun row(linkId: String, requestId: String): MirrorRow? =
        synchronized(lock) { state.rows.firstOrNull { it.linkId == linkId && it.request.id == requestId } }

    /** Every mirrored row carrying [requestId], across peers — how the CLI resolves a bare id. More than
     *  one means the id is ambiguous and the caller must ask which peer. */
    fun byRequestId(requestId: String): List<MirrorRow> =
        synchronized(lock) { state.rows.filter { it.request.id == requestId } }

    /** Persist the sender's row before publishing it in memory. [MirrorResult.UNCHANGED] means the
     * current row was already durable; [MirrorResult.PERSIST_FAILED] must never be ACKed. */
    fun mirror(linkId: String, request: ReviewRequest, now: Long): MirrorResult = synchronized(lock) {
        val existing = state.rows.firstOrNull { it.linkId == linkId && it.request.id == request.id }
        if (existing != null && request.revision <= existing.request.revision) return@synchronized MirrorResult.UNCHANGED
        val row = MirrorRow(linkId, request, now)
        val next = prune(
            state.copy(
            rows = state.rows.filterNot { it.linkId == linkId && it.request.id == request.id } + row,
            ),
        )
        // PER LINK, not global: a peer owns its own rows' authoritative status, so a global cap lets one
        // colleague who simply never advances 24 requests block every other colleague's — their rows are
        // refused, never ACKed, and they sit on QUEUED forever with only a daemon log line to explain it.
        // One peer's misbehaviour must cost only that peer's inbox.
        if (next.rows.count { it.linkId == linkId && !it.request.status.isTerminal } > MAX_ACTIVE_ROWS) {
            return@synchronized MirrorResult.FULL
        }
        if (commit(next)) MirrorResult.STORED else MirrorResult.PERSIST_FAILED
    }

    fun outbox(): List<OutboxItem> = synchronized(lock) { state.outbox }

    fun outboxOf(linkId: String): List<OutboxItem> = synchronized(lock) { state.outbox.filter { it.linkId == linkId } }

    fun pendingFor(linkId: String, requestId: String): List<OutboxItem> =
        synchronized(lock) { state.outbox.filter { it.linkId == linkId && it.requestId == requestId } }

    /** Queue a mutation. Returns false when the outbox is FULL — the caller must refuse the user's
     *  action loudly rather than accept work it has no room to remember. */
    fun enqueue(item: OutboxItem): EnqueueResult = synchronized(lock) {
        if (state.outbox.size >= MAX_OUTBOX) return@synchronized EnqueueResult.FULL
        if (commit(state.copy(outbox = state.outbox + item))) EnqueueResult.STORED else EnqueueResult.PERSIST_FAILED
    }

    /**
     * Queue a mutation whose id is DERIVED rather than random, so re-queueing the same intent is a
     * no-op instead of a second item. Used by the delivery ACK, which one replay, restart or listing
     * after another would otherwise pile up — each one a frame the sender has to fold back together.
     */
    fun enqueueOnce(item: OutboxItem): EnqueueResult = synchronized(lock) {
        if (state.outbox.any { it.id == item.id }) return@synchronized EnqueueResult.STORED
        enqueue(item)
    }

    fun bumpAttempts(ids: Set<String>): Boolean = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized true
        commit(state.copy(outbox = state.outbox.map { if (it.id in ids) it.copy(attempts = it.attempts + 1) else it }))
    }

    fun dropOutbox(ids: Set<String>): Boolean = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized true
        val kept = state.outbox.filterNot { it.id in ids }
        if (kept.size == state.outbox.size) return@synchronized true
        commit(state.copy(outbox = kept))
    }

    /** Everything belonging to a severed link stops syncing but its HISTORY stays: only the outbox is
     *  cleared (those mutations can never be delivered now), the mirrored rows are kept. */
    fun onLinkRemoved(linkId: String): Boolean = synchronized(lock) {
        val kept = state.outbox.filterNot { it.linkId == linkId }
        if (kept.size == state.outbox.size) return@synchronized true
        commit(state.copy(outbox = kept))
    }

    private fun prune(value: Stored): Stored {
        val terminal = value.rows.filter { it.request.status.isTerminal }.sortedBy { it.mirroredAt }
        val excess = terminal.size - MAX_MIRRORED_HISTORY
        if (excess <= 0) return value
        val drop = terminal.take(excess).map { it.linkId to it.request.id }.toSet()
        return value.copy(rows = value.rows.filterNot { (it.linkId to it.request.id) in drop })
    }

    private fun commit(next: Stored): Boolean {
        val durable = path?.let { ReviewFiles.write(it, PocketJson.encodeToString(Stored.serializer(), next)) } ?: true
        if (durable) state = next
        return durable
    }

    companion object {
        fun defaultPath(): File = ReviewFiles.path("review-inbox.json")

        const val MAX_MIRRORED_HISTORY = 200

        /** Open work is never evicted. A peer that exceeds this cap — counted PER LINK — remains
         * un-ACKed and visible on its own sender as QUEUED instead of growing this file without bound. */
        const val MAX_ACTIVE_ROWS = 24

        /** Unconfirmed recipient mutations we are willing to remember. Reached → the local API refuses
         *  a new one (`review_outbox_full`) instead of forgetting an older, still-undelivered result. */
        const val MAX_OUTBOX = 100

        /**
         * Has the sender's authoritative row settled an outbox item? True once the row reached the
         * status the item was aiming for, PASSED it (a later transition implies the earlier one landed),
         * or went terminal in a way that makes the mutation moot (a cancelled/expired request will never
         * accept our acknowledge, so retrying it forever is pointless).
         *
         * An UNKNOWN status never settles anything: this build cannot tell what it means, so the item
         * stays queued rather than being quietly dropped.
         */
        fun satisfied(expect: ReviewStatus, actual: ReviewStatus): Boolean {
            if (actual == ReviewStatus.UNKNOWN || expect == ReviewStatus.UNKNOWN) return false
            if (actual.isTerminal) return true
            return rank(actual) >= rank(expect)
        }

        /** Lifecycle order along the happy path. Terminal states are handled separately in [satisfied]. */
        private fun rank(s: ReviewStatus): Int = when (s) {
            ReviewStatus.QUEUED -> 0
            ReviewStatus.DELIVERED -> 1
            ReviewStatus.ACKNOWLEDGED -> 2
            ReviewStatus.IN_PROGRESS -> 3
            ReviewStatus.RESPONDED -> 4
            ReviewStatus.CLOSED -> 5
            else -> 9
        }

        fun load(path: File = defaultPath()): PeerInboxStore = PeerInboxStore(path).apply {
            ReviewFiles.read(path) { PocketJson.decodeFromString(Stored.serializer(), it) }?.let { state = it }
        }

        fun inMemory(): PeerInboxStore = PeerInboxStore(null)
    }
}
