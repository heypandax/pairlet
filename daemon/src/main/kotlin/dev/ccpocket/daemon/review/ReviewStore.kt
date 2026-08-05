package dev.ccpocket.daemon.review

import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.isTerminal
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Persistence for the SENDER-AUTHORITATIVE ReviewRequest ledger (REVIEW-REQUEST.md §5.1):
 * `~/.cc-pocket/reviews.json`, beside handoffs.json and collaborators.json.
 *
 * Deliberately its own file, on the same downgrade-safety argument as [dev.ccpocket.daemon.handoff.HandoffStore]:
 * a daemon that predates ReviewRequest never loads it, so the state fails closed instead of being misread.
 *
 * This class owns load/persist and the in-memory snapshot ONLY. Every state-machine decision (which
 * transition is legal, when a revision advances, what an idempotency key means) lives in
 * [ReviewRegistry], its only caller — the same split HandoffStore/HandoffRegistry uses.
 *
 * Two bounded collections, with deliberately different failure modes:
 *  - terminal history is PRUNED oldest-first past [MAX_HISTORY] — losing a closed request costs nothing;
 *  - the non-terminal set is CAPPED and refuses new writes past [MAX_ACTIVE] instead of evicting. Pending
 *    work someone is waiting on must never be deleted to make room for more pending work (§8).
 */
class ReviewStore private constructor(private val path: File?) {

    @Serializable
    private data class Stored(
        val v: Int = 1,
        val requests: List<ReviewRequest> = emptyList(),
        /** requestId -> idempotency keys already applied, oldest first. Bounded per request. */
        val applied: Map<String, List<String>> = emptyMap(),
    )

    private val lock = Any()
    private var state: Stored = Stored()

    fun all(): List<ReviewRequest> = synchronized(lock) { state.requests }

    fun byId(id: String): ReviewRequest? = synchronized(lock) { state.requests.firstOrNull { it.id == id } }

    /** Has this (requestId, idempotencyKey) mutation already been applied? A blank key is never
     *  "already applied" — an older peer that omits it relies on the state machine's own no-op path. */
    fun wasApplied(requestId: String, key: String): Boolean = synchronized(lock) {
        key.isNotBlank() && state.applied[requestId]?.contains(key) == true
    }

    /** Upsert one request by id and, when [key] is non-blank, record it as applied — ONE persist, so a
     *  crash can never leave the transition durable while its idempotency key is not (which would let
     *  a retry apply the same mutation twice). */
    fun put(request: ReviewRequest, key: String = ""): Boolean = synchronized(lock) {
        val known = state.requests.any { it.id == request.id }
        val requests = if (known) state.requests.map { if (it.id == request.id) request else it }
        else state.requests + request
        val applied = if (key.isBlank()) state.applied else {
            val keys = (state.applied[request.id].orEmpty() + key).takeLast(MAX_KEYS_PER_REQUEST)
            state.applied + (request.id to keys)
        }
        commit(state.copy(requests = requests, applied = applied))
    }

    /** Replace the whole ledger in one persist — the prune/normalize path. */
    fun replaceAll(requests: List<ReviewRequest>): Boolean = synchronized(lock) {
        val live = requests.mapTo(HashSet()) { it.id }
        commit(state.copy(requests = requests, applied = state.applied.filterKeys { it in live }))
    }

    /** How many rows are still awaiting someone — the cap [ReviewRegistry] refuses new creates against. */
    fun activeCount(): Int = synchronized(lock) { state.requests.count { !it.status.isTerminal } }

    /** Drop the oldest TERMINAL rows past [MAX_HISTORY]. Non-terminal rows are never pruned. */
    fun pruneHistory(): Boolean = synchronized(lock) {
        val terminal = state.requests.filter { it.status.isTerminal }.sortedBy { it.createdAt }
        val excess = terminal.size - MAX_HISTORY
        if (excess <= 0) return@synchronized true
        val drop = terminal.take(excess).mapTo(HashSet()) { it.id }
        val kept = state.requests.filterNot { it.id in drop }
        commit(state.copy(requests = kept, applied = state.applied.filterKeys { it !in drop }))
    }

    /** Persist the prospective snapshot first and publish it to readers only after that succeeds. */
    private fun commit(next: Stored): Boolean {
        val durable = path?.let { ReviewFiles.write(it, PocketJson.encodeToString(Stored.serializer(), next)) } ?: true
        if (durable) state = next
        return durable
    }

    companion object {
        fun defaultPath(): File = ReviewFiles.path("reviews.json")

        /** Terminal-history cap: enough for a "past requests" list, bounded forever. */
        const val MAX_HISTORY = 200

        /** Hard ceiling on requests still awaiting someone. Reached → create FAILS (never evicts). */
        const val MAX_ACTIVE = 24

        /** Idempotency keys remembered per request. A recipient retries one mutation a handful of
         *  times at most; this is generous and keeps the file bounded. */
        const val MAX_KEYS_PER_REQUEST = 32

        /** Load from [path]; a missing file yields an empty store, a corrupt one is quarantined by
         *  [ReviewFiles.read] and also yields an empty store (never a crash at boot). */
        fun load(path: File = defaultPath()): ReviewStore = ReviewStore(path).apply {
            ReviewFiles.read(path) { PocketJson.decodeFromString(Stored.serializer(), it) }?.let { state = it }
        }

        /** Non-persistent store for unit tests and embedded cores that did not opt into production IO. */
        fun inMemory(): ReviewStore = ReviewStore(null)
    }
}
