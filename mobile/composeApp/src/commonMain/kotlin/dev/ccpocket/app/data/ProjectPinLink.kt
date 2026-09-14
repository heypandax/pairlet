package dev.ccpocket.app.data

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import dev.ccpocket.app.net.PinDispatchFence
import dev.ccpocket.app.net.PinEnqueueResult
import dev.ccpocket.app.net.PinOutbound
import dev.ccpocket.app.pairing.BindingRole
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pins.PinBindingLease
import dev.ccpocket.app.pins.PinLock
import dev.ccpocket.app.pins.PinRequestContext
import dev.ccpocket.app.pins.PinRequestKind
import dev.ccpocket.app.pins.PinScopeKey
import dev.ccpocket.app.pins.PinStateDoc
import dev.ccpocket.app.pins.PinSyncIssue
import dev.ccpocket.app.pins.PinUpdate
import dev.ccpocket.app.pins.ProjectPinReducer
import dev.ccpocket.app.pins.ProjectPinRegistry
import dev.ccpocket.app.pins.ProjectPinScope
import dev.ccpocket.protocol.ProjectPinErrors
import dev.ccpocket.protocol.ProjectPinsState
import dev.ccpocket.protocol.SyncProjectPins
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a pin-sync problem is, as far as the screen may say: a fixed vocabulary — never an error text or a path. */
enum class PinIssueKind {
    RETAINED_LOCALLY, REFUSED, CAPACITY, STORAGE_FAILED, LOCAL_STATE_RESET, OUTBOX_FULL,
    MIGRATION_UNCERTAIN, LEGACY_NOT_MIGRATED, ALIAS_LIMITED, PAIRING_CHANGED,
}

/**
 * One occurrence of a pin-sync problem for one computer's pins ([scope]). [id] grows across the process; the same
 * problem reported again by listener churn keeps its event, while a new failed explicit action ([explicit]) is a
 * new one. [count] only for the kinds that carry one.
 */
data class PinIssueEvent(val scope: PinScopeKey, val id: Long, val kind: PinIssueKind, val count: Int = 0, val explicit: Boolean = false) {
    /** Whether asking the computer again (a fetch, a storage re-read) can help. */
    val retryable: Boolean
        get() = kind == PinIssueKind.REFUSED || kind == PinIssueKind.CAPACITY || kind == PinIssueKind.STORAGE_FAILED
}

/** What ends one occurrence of a transient pin-sync problem (a refusal, a storage failure) — never mere success of
 *  some other step. */
internal sealed interface PinIssueProof {
    /** Fresh operations of [streamId] still unacknowledged when the computer refused them: ends once none of them is
     *  pending on that stream, however much newer work waits behind them, or once the stream itself moved on. */
    data class Work(val streamId: String, val seqs: Set<Long>) : PinIssueProof

    /** A refused lookup replay of acknowledged operations: ends with a working reply to those same operations after
     *  which none of them still awaits its resolution, or once the stream moved on. A working reply that resolves
     *  nothing leaves the lookup undone. */
    data class Lookup(val streamId: String, val seqs: Set<Long>) : PinIssueProof

    /** A refused fetch: ends with a working correlated fetch. */
    data object Fetch : PinIssueProof

    /** Storage that could not be read or saved: ends when it is read back or written again. */
    data object Storage : PinIssueProof
}

/** Process-wide event numbering, occurrences and dismissal, so a dismissed notice stays dismissed for that computer
 *  and problem across the repositories that may speak for it (a fleet promote swaps them). Dismissal hides a notice
 *  only. A refusal or storage failure is one OCCURRENCE per computer and kind, shared by every holder until its own
 *  [PinIssueProof] is met; a notice of an ended occurrence is never shown again. */
class PinIssueBoard {
    private class Occurrence(val id: Long, val proof: PinIssueProof)

    private val lock = PinLock()
    private var lastId = 0L
    private val dismissed = HashMap<PinScopeKey, PinIssueEvent>()
    private val active = HashMap<Pair<PinScopeKey, PinIssueKind>, Occurrence>()
    private val dismissedOccurrences = HashSet<Long>()
    private val revision = mutableStateOf(0)

    fun nextId(): Long = lock.withLock { ++lastId }

    fun dismiss(event: PinIssueEvent) {
        lock.withLock {
            if (event.kind !in OCCURRENCE_KINDS) dismissed[event.scope] = event
            else if (active[event.scope to event.kind]?.id == event.id) dismissedOccurrences += event.id
        }
        revision.value++
    }

    /** True when [event] belongs to a dismissed or ended occurrence, or repeats a dismissed static problem of its
     *  computer and is not a newer explicit failure. */
    fun hides(event: PinIssueEvent): Boolean {
        revision.value // observed: a dismissal, a new or an ended occurrence recomposes every notice reading this
        return lock.withLock {
            if (event.kind in OCCURRENCE_KINDS) {
                active[event.scope to event.kind]?.id != event.id || event.id in dismissedOccurrences
            } else {
                val gone = dismissed[event.scope]
                gone != null && gone.kind == event.kind && gone.count == event.count && !(event.explicit && event.id > gone.id)
            }
        }
    }

    /** The id of [scope]'s [kind] occurrence this report belongs to: the unended one — keeping the proof it started
     *  with — unless this is a new failed explicit action, which starts a new one. */
    internal fun report(scope: PinScopeKey, kind: PinIssueKind, proof: PinIssueProof, explicit: Boolean): Long {
        var started = false
        val id = lock.withLock {
            val current = active[scope to kind]
            if (current != null && !explicit) return@withLock current.id
            current?.let { dismissedOccurrences -= it.id }
            started = true
            Occurrence(++lastId, proof).also { active[scope to kind] = it }.id
        }
        if (started) revision.value++
        return id
    }

    /** End every occurrence of [scope] whose proof is met by the committed [doc], the working reply to [success] or
     *  storage proven [storageReady]. */
    internal fun reconcile(scope: PinScopeKey, doc: PinStateDoc?, success: PinRequestContext? = null, storageReady: Boolean = false) {
        val ended = lock.withLock {
            val over = active.filter { (key, occurrence) -> key.first == scope && occurrence.proof.metBy(doc, success, storageReady) }
            over.forEach { (key, occurrence) -> active -= key; dismissedOccurrences -= occurrence.id }
            over.isNotEmpty()
        }
        if (ended) revision.value++
    }

    private fun PinIssueProof.metBy(doc: PinStateDoc?, success: PinRequestContext?, storageReady: Boolean): Boolean = when (this) {
        is PinIssueProof.Work -> doc != null && (doc.stream.id != streamId || doc.pending.none { it.seq in seqs })
        is PinIssueProof.Lookup -> doc != null && (doc.stream.id != streamId ||
            (success?.kind == PinRequestKind.MUTATION && success.streamId == streamId && success.submitted.map { it.seq }.containsAll(seqs) &&
                doc.resolutionPending.none { it.seq in seqs }))
        PinIssueProof.Fetch -> success?.kind == PinRequestKind.FETCH
        PinIssueProof.Storage -> storageReady
    }

    internal fun occurrenceIdForTest(scope: PinScopeKey, kind: PinIssueKind): Long? = lock.withLock { active[scope to kind]?.id }

    internal fun clearForTest() {
        lock.withLock {
            dismissed.clear()
            active.clear()
            dismissedOccurrences.clear()
        }
        revision.value++
    }

    companion object {
        val shared = PinIssueBoard()

        private val OCCURRENCE_KINDS = setOf(PinIssueKind.REFUSED, PinIssueKind.CAPACITY, PinIssueKind.STORAGE_FAILED)
    }
}

/**
 * One repository's end of project-pin sync (issue #362).
 *
 * The repository binds this to the computer it currently speaks for; the pins themselves live in that computer's
 * shared [ProjectPinScope]. An OWNER binding acts only through a [PinBindingLease] the registry grants for the
 * binding it currently holds as authority — a controller whose binding was re-paired or removed stops at its next
 * step and can never reacquire with that stale binding.
 *
 * A SYNC GENERATION starts only when THIS connection's DaemonInfo advertises pin support on the paired transport, and
 * it carries its own random subscription id and the [PinOutbound] captured for exactly that connection:
 *
 *  1. FETCHING: no operation leaves until the latest correlated fetch reply has bound the stream to this device and
 *     this daemon store (or set uncertain intent aside, see [ProjectPinReducer.applyReply]). An older fetch reply
 *     speaks for nothing; a stale cursor, an unfamiliar pushed store, a foreground return or a retry fetch again;
 *  2. READY: flush the outbox one bounded batch at a time — fresh intent first, then (once per explicit trigger)
 *     acknowledged operations that still await their key — marking fresh intent as possibly sent, durably, before
 *     it is queued. Every non-empty request names the incarnation it was queued against;
 *  3. BLOCKED: a refusal, a storage failure or a bounded run of unanswered attempts — nothing is sent until an
 *     explicit trigger (a user edit, foreground, retry, a new connection). A refusal also latches the shared scope,
 *     so no other controller of the same computer resends because the refusal's snapshot was published.
 *
 * Enqueueing never suspends and never reports a transport failure: a full or retired outbox only waits.
 * Not thread-safe by itself: the repository drives it from its own (UI) scope, like the rest of its state.
 */
internal class ProjectPinLink(
    private val registry: ProjectPinRegistry,
    private val coroutines: CoroutineScope,
    private val onVisible: (List<String>) -> Unit,
    private val replyTimeoutMs: Long = REPLY_TIMEOUT_MS,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val board: PinIssueBoard = PinIssueBoard.shared,
) {
    /** The latest bounded, actionable sync problem for the bound computer (null = none). */
    val issue = mutableStateOf<PinSyncIssue?>(null)

    private val event = mutableStateOf<PinIssueEvent?>(null)

    /** The problem to show now: the current event unless its notice was dismissed. */
    val notice: State<PinIssueEvent?> = derivedStateOf { event.value?.takeUnless(board::hides) }

    private var bound: ProjectPinScope? = null
    private var boundBinding: PairedDaemon? = null
    private var lease: PinBindingLease? = null
    private var lastVisible: List<String>? = null
    private var generation: Generation? = null

    /** Observed by [isPinned]: bumped when what identifies a path changes — a learned alias can pin a spelling
     *  without changing the visible list. */
    private val identityRevision = mutableStateOf(0)
    private var lastIdentity: Any? = null

    /** A reply or push is being reconciled: its own publication must not start a flush of its own. */
    private var reconciling = false

    /** The one listener this link ever registers, so teardown can remove it without knowing its current scope. */
    private val listener: (List<String>) -> Unit = { visible ->
        noteIdentity()
        publish(visible)
        // another holder's change may leave work to flush — unless this generation is waiting out a delayed retry
        // or a reply deadline, which only its timer or an explicit trigger ends
        val gen = generation
        if (!reconciling && gen != null && gen.timer?.isActive != true) flushIfReady(gen)
    }

    init {
        // a repository dropped without disconnect() — its composition went away — must not stay reachable from the
        // process-wide registry through this listener (the registry removes it under its own lock, from any thread)
        coroutines.coroutineContext[Job]?.invokeOnCompletion { registry.removeListener(listener) }
    }

    private enum class Phase { FETCHING, READY, BLOCKED }

    private inner class Generation(val lease: PinBindingLease, val subscriptionId: String, val outbound: PinOutbound) {
        var phase = Phase.FETCHING
        /** The latest fetch of this generation was accepted (a later BLOCKED can resume without fetching). */
        var fetched = false
        var fetchRequestId: String? = null
        var inflightRequestId: String? = null
        var inflightLookup = false
        /** Lookup-only batches this generation may still send before the next explicit trigger or lookup progress. */
        var lookupBudget = 1
        /** Consecutive attempts that went unanswered or could not be queued. */
        var misses = 0
        /** The one reply deadline or delayed retry this generation has pending. */
        var timer: Job? = null
        val outstanding = LinkedHashMap<String, PinRequestContext>()
        @Volatile var retired = false
        val fence = PinDispatchFence { !retired && registry.isCurrent(lease) }
    }

    val scopeKey: PinScopeKey? get() = bound?.key

    /** Point this repository at [binding]'s pins. A no-op only for the same identity with a still-current lease; a
     *  changed device or credential drops the old lease and generation first. Never copies another scope. */
    fun bind(binding: PairedDaemon?, demo: Boolean) {
        val key = PinScopeKey.of(binding, demo)
        val current = bound
        if (current != null && current.key == key && sameIdentity(boundBinding, binding) &&
            (key !is PinScopeKey.Owner || currentLease() != null)
        ) {
            boundBinding = binding // labels and addresses may have changed; the identity has not
            publish(current.visible())
            return
        }
        retireGeneration()
        current?.removeListener(listener)
        val next = registry.scope(key)
        bound = next
        boundBinding = binding
        lease = if (key is PinScopeKey.Owner && binding != null) registry.acquireLease(binding) else null
        lastVisible = null
        next.addListener(listener)
        noteIdentity()
        publish(next.visible())
        if (key is PinScopeKey.Owner && lease == null) post(PinIssueKind.PAIRING_CHANGED, explicit = false)
        else post(initialIssue(next), explicit = false)
    }

    /** Tear down: retire the generation, stop listening and clear the mirror. The next [bind] starts over. */
    fun release() {
        retireGeneration()
        bound?.removeListener(listener)
        bound = null
        boundBinding = null
        lease = null
        issue.value = null
        event.value = null
        publish(emptyList())
    }

    fun visible(): List<String> = bound?.visible().orEmpty()

    /** Whether [path] is pinned by identity (a known alias of a pinned project is that pin); null when unbound. */
    fun isPinned(path: String): Boolean? {
        identityRevision.value // observed: a composition re-reads when an identity changes the answer
        return bound?.let { ProjectPinReducer.isPinned(it.document(), path, it.synced) }
    }

    /** An explicit user SET. False when nothing changed (invalid path, full outbox, storage or pairing failure). */
    fun setPinned(path: String, pinned: Boolean): Boolean {
        val scope = bound ?: return false
        var edit: ProjectPinReducer.Edit = ProjectPinReducer.Edit.Invalid
        if (scope.key !is PinScopeKey.Owner) {
            val saved = scope.updateTrusted { doc ->
                edit = ProjectPinReducer.setLocal(doc, path, pinned)
                (edit as? ProjectPinReducer.Edit.Applied)?.doc ?: doc
            }
            if (saved !is PinUpdate.Committed) post(scope.storageIssue() ?: PinSyncIssue.StorageFailed, explicit = true)
            else if (saved.changed) storageRecovered(scope) // a written local edit proves storage works
            return saved is PinUpdate.Committed && edit is ProjectPinReducer.Edit.Applied
        }
        val lease = currentLease() ?: return false.also { pairingChanged(explicit = true) }
        val result = scope.updateFor(lease, deferPublish = true) { doc ->
            edit = ProjectPinReducer.enqueue(doc, path, pinned)
            (edit as? ProjectPinReducer.Edit.Applied)?.doc ?: doc
        }
        if (result !is PinUpdate.Committed) {
            if (result == PinUpdate.StaleLease) pairingChanged(explicit = true) else post(failureOf(result), explicit = true)
            return false
        }
        when (edit) {
            is ProjectPinReducer.Edit.Applied -> {}
            ProjectPinReducer.Edit.OutboxFull -> return false.also { post(PinSyncIssue.OutboxFull, explicit = true) }
            ProjectPinReducer.Edit.Invalid -> return false
        }
        scope.clearFlushBlockedOnExplicitTrigger() // latched before listeners hear the edit
        reconciling = true // this link flushes once, below, as the explicit trigger — not again from its own listener
        try {
            result.publish()
        } finally {
            reconciling = false
        }
        // a written edit proves storage works; it proves nothing about the computer's refusal of what is still pending
        if (result.changed) board.reconcile(scope.key, doc = null, storageReady = true)
        clearResolvedIssue(scope)
        generation?.let(::explicitTrigger)
        return true
    }

    /** This connection's DaemonInfo: the only thing that starts a sync generation. [outbound] is the queue of the
     *  exact connection that advertised (null: none to capture — nothing starts). */
    fun onDaemonInfo(supportsProjectPins: Boolean, binding: PairedDaemon?, pairedTransport: Boolean, outbound: PinOutbound?) {
        retireGeneration()
        val scope = bound ?: return
        if (!supportsProjectPins || !pairedTransport || outbound == null) return
        if (binding == null || binding.role != BindingRole.OWNER) return
        if (scope.key != PinScopeKey.Owner(binding.accountId) || !sameIdentity(boundBinding, binding)) return
        val lease = currentLease() ?: return pairingChanged(explicit = false)
        val gen = Generation(lease, registry.newToken(), outbound)
        generation = gen // installed BEFORE fetching: every step acts only for the current generation
        beginFetch(gen)
    }

    /** Back in the foreground: fetch again to heal a missed push. Never revives a stale binding. */
    fun onForeground() {
        val gen = generation ?: return
        if (!live(gen)) return
        gen.misses = 0
        gen.lookupBudget = 1 // an explicit trigger: one more lookup batch may go out after the fetch
        if (gen.phase != Phase.FETCHING) beginFetch(gen)
    }

    /** The notice's retry: re-read storage that could not be read, then fetch. Never touches the transport. */
    fun retry() {
        val scope = bound ?: return
        // only storage that could not take changes is re-read; a ready scope's unchanged update touches no storage
        val blocked = scope.storageIssue()
        val reread = if (scope.key is PinScopeKey.Owner) {
            val result = currentLease()?.let { scope.updateFor(it) { doc -> doc } }
            if (result == PinUpdate.StaleLease) return pairingChanged(explicit = true)
            result
        } else {
            scope.updateTrusted { doc -> doc } // a local-only scope has no lease and sends nothing: its storage re-read is trusted
        }
        if (reread is PinUpdate.Committed && (reread.changed || (blocked != null && scope.storageIssue() == null))) storageRecovered(scope)
        val gen = generation ?: return
        if (!live(gen)) return
        gen.misses = 0
        gen.lookupBudget = 1
        if (gen.phase != Phase.FETCHING) beginFetch(gen)
    }

    fun dismiss(shown: PinIssueEvent) = board.dismiss(shown)

    fun onDisconnect() = retireGeneration()

    fun onState(frame: ProjectPinsState) {
        val gen = generation ?: return
        if (frame.subscriptionId != gen.subscriptionId) return // a retired generation, connection or computer
        if (!live(gen)) return
        val scope = bound ?: return
        if (scope.key != PinScopeKey.Owner(gen.lease.accountId)) return
        val requestId = frame.requestId ?: return onPush(gen, scope, frame)
        val context = gen.outstanding[requestId] ?: return
        if (frame.streamId != context.streamId) return // not an answer to what went out
        gen.outstanding.remove(requestId)
        val isFetch = context.kind == PinRequestKind.FETCH
        if (isFetch && requestId != gen.fetchRequestId) return // an older fetch speaks for nothing
        val fetchPending = !isFetch && gen.phase == Phase.FETCHING // an older mutation answered while a fetch is out

        var outcome: ProjectPinReducer.ReplyOutcome? = null
        reconciling = true // before the slot is freed or anything is persisted
        val result: PinUpdate = try {
            val lookupReply = requestId == gen.inflightRequestId && gen.inflightLookup
            val lookupBefore = scope.document().resolutionPending.size
            if (isFetch || requestId == gen.inflightRequestId) {
                gen.timer?.cancel(); gen.timer = null
            }
            if (requestId == gen.inflightRequestId) {
                gen.inflightRequestId = null
                gen.inflightLookup = false
            }
            val error = frame.error
            val staleCursor = error == ProjectPinErrors.INCARNATION_MISMATCH || error == ProjectPinErrors.SUBSCRIPTION_STALE
            // the barrier is latched before any listener can hear what this reply changes
            if (error != null && !staleCursor) scope.setFlushBlocked(PinSyncIssue.Refused(error, frame.message))
            if (error != null && !fetchPending) gen.phase = Phase.BLOCKED
            val update = scope.updateFor(gen.lease, deferPublish = true) { doc ->
                ProjectPinReducer.applyReply(doc, frame, context, gen.lease.deviceId, registry.newToken).also { outcome = it }.doc
            }
            val out = outcome
            if (update is PinUpdate.Committed && out != null) {
                when {
                    out.refetch -> if (!fetchPending) { gen.phase = Phase.BLOCKED; gen.fetched = false }
                    out.blocked -> {
                        scope.setFlushBlocked(out.issue ?: PinSyncIssue.StorageFailed)
                        if (!fetchPending) gen.phase = Phase.BLOCKED
                        if (isFetch) gen.fetched = false
                    }
                    isFetch -> {
                        gen.phase = Phase.READY
                        gen.fetched = true
                        gen.misses = 0
                        scope.clearFlushBlockedOnExplicitTrigger() // an accepted current fetch
                    }
                    else -> gen.misses = 0
                }
                // a lookup that resolved something may be followed by the next contiguous lookup batch; a daemon that
                // answered fresh intent without any resolution would answer a lookup the same way — do not ask it
                if (lookupReply && scope.document().resolutionPending.size < lookupBefore) gen.lookupBudget = 1
                if (!isFetch && !lookupReply && frame.resolutions == null) gen.lookupBudget = 0
                update.publish()
            } else if (update != PinUpdate.StaleLease) {
                gen.phase = Phase.BLOCKED
            }
            update
        } finally {
            reconciling = false
        }

        // the committed view is published; report, then act at most once
        val out = outcome
        when {
            result == PinUpdate.StaleLease -> pairingChanged(explicit = false)
            result !is PinUpdate.Committed || out == null -> post(failureOf(result), explicit = false)
            else -> {
                // what this committed reply proves ends earlier occurrences first; a refusal in the same reply is judged after
                val committed = (result as PinUpdate.Committed).doc
                board.reconcile(scope.key, committed, success = context.takeIf { frame.error == null && out.issue == null }, storageReady = result.changed)
                when (val problem = out.issue) {
                    null -> clearResolvedIssue(scope)
                    is PinSyncIssue.Refused -> post(problem, explicit = false, proof = refusalProof(context, committed))
                    else -> post(problem, explicit = false)
                }
                when {
                    !out.refetch -> flushIfReady(gen)
                    isFetch -> retryLater(gen) { beginFetch(gen) } // a fetch answered with a stale cursor: bounded
                    !fetchPending -> beginFetch(gen)
                }
            }
        }
    }

    /** This computer's own authoritative directory listing, on a connection that is syncing with it: proves which
     *  legacy fallback paths belong here. */
    fun onDirectories(paths: Collection<String>) {
        val gen = generation ?: return
        if (!live(gen)) return
        val scope = bound ?: return
        if (scope.key != PinScopeKey.Owner(gen.lease.accountId)) return
        val fallback = scope.document().legacy?.fallback.orEmpty()
        if (fallback.isEmpty()) return
        val listed = paths.toSet()
        if (fallback.none { it in listed }) return
        when (val result = scope.updateFor(gen.lease) { ProjectPinReducer.claimProvenFallback(it, listed) }) {
            is PinUpdate.Committed -> flushIfReady(gen)
            PinUpdate.StaleLease -> pairingChanged(explicit = false)
            else -> post(failureOf(result), explicit = false)
        }
    }

    /** An unsolicited snapshot: no cursor, no resolution, no error of it counts — only a newer view of a known store. */
    private fun onPush(gen: Generation, scope: ProjectPinScope, frame: ProjectPinsState) {
        val snapshot = frame.snapshot ?: return
        if (gen.phase == Phase.FETCHING || !gen.fetched) return // the correlated fetch reply is authoritative
        var refetch = false
        reconciling = true
        val result = try {
            scope.updateFor(gen.lease, deferPublish = true) { doc ->
                ProjectPinReducer.applyPush(doc, snapshot).also { refetch = it.refetch }.doc
            }.also { (it as? PinUpdate.Committed)?.publish() }
        } finally {
            reconciling = false
        }
        when {
            result == PinUpdate.StaleLease -> pairingChanged(explicit = false)
            result !is PinUpdate.Committed -> post(failureOf(result), explicit = false)
            refetch -> beginFetch(gen)
        }
    }

    private fun explicitTrigger(gen: Generation) {
        if (!live(gen)) return
        gen.misses = 0
        gen.lookupBudget = 1
        when (gen.phase) {
            Phase.FETCHING -> {} // no mutation leaves before the fetch is answered
            Phase.BLOCKED -> if (gen.fetched) { gen.phase = Phase.READY; flushIfReady(gen) } else beginFetch(gen)
            Phase.READY -> flushIfReady(gen)
        }
    }

    private fun beginFetch(gen: Generation) {
        if (!live(gen)) return
        val scope = bound ?: return
        // stop any mutation deadline or retry BEFORE queuing: nothing but this fetch may act until it is answered
        gen.timer?.cancel(); gen.timer = null
        gen.phase = Phase.FETCHING
        gen.fetched = false
        gen.inflightRequestId = null
        gen.inflightLookup = false
        val requestId = registry.newToken()
        val context = PinRequestContext(PinRequestKind.FETCH, scope.document().stream.id, gen.subscriptionId, requestId, null, emptyList())
        gen.fetchRequestId = requestId
        track(gen, context)
        when (gen.outbound.tryEnqueue(SyncProjectPins(requestId, gen.subscriptionId, context.streamId), gen.fence)) {
            PinEnqueueResult.ACCEPTED -> arm(gen) {
                if (gen.phase == Phase.FETCHING && gen.fetchRequestId == requestId) {
                    gen.outstanding.remove(requestId)
                    retryLater(gen) { beginFetch(gen) }
                }
            }
            PinEnqueueResult.FULL -> {
                gen.outstanding.remove(requestId)
                retryLater(gen) { beginFetch(gen) }
            }
            PinEnqueueResult.RETIRED -> {
                gen.outstanding.remove(requestId)
                retireGeneration(gen)
            }
        }
    }

    private fun flushIfReady(gen: Generation) {
        if (reconciling || gen.phase != Phase.READY || gen.inflightRequestId != null) return
        if (!live(gen)) return
        val scope = bound ?: return
        if (scope.key != PinScopeKey.Owner(gen.lease.accountId) || !scope.canFlush(gen.lease)) return
        val doc = scope.document()
        val incarnation = doc.stream.incarnation ?: return
        val fresh = ProjectPinReducer.batch(doc)
        val lookup = fresh.isEmpty()
        val ops = when {
            !lookup -> fresh
            gen.lookupBudget > 0 -> scope.lookupBatchFor(gen.lease)
            else -> emptyList()
        }
        if (ops.isEmpty()) return
        val requestId = registry.newToken()
        gen.inflightRequestId = requestId // claimed before the durable mark: its listener callback must not re-enter
        gen.inflightLookup = lookup
        if (lookup) {
            gen.lookupBudget--
        } else {
            // possibly sent from here on — durably, before it is queued (queued-but-never-written is safely uncertain)
            val marked = scope.updateFor(gen.lease) { ProjectPinReducer.markSent(it, ops.last().seq) }
            if (marked !is PinUpdate.Committed) {
                gen.inflightRequestId = null
                gen.inflightLookup = false
                if (marked == PinUpdate.StaleLease) return pairingChanged(explicit = false)
                gen.phase = Phase.BLOCKED
                return post(failureOf(marked), explicit = false)
            }
        }
        val context = PinRequestContext(PinRequestKind.MUTATION, doc.stream.id, gen.subscriptionId, requestId, incarnation, ops)
        track(gen, context)
        val frame = SyncProjectPins(requestId, gen.subscriptionId, doc.stream.id, ops, expectedIncarnation = incarnation)
        fun freeSlot() {
            if (gen.inflightRequestId == requestId) {
                gen.inflightRequestId = null
                gen.inflightLookup = false
            }
            gen.outstanding.remove(requestId)
        }
        when (gen.outbound.tryEnqueue(frame, gen.fence)) {
            // no answer: release the slot and try once more later; the daemon deduplicates a resend
            PinEnqueueResult.ACCEPTED -> arm(gen) {
                if (gen.inflightRequestId == requestId) {
                    freeSlot()
                    retryLater(gen) { flushIfReady(gen) }
                }
            }
            PinEnqueueResult.FULL -> {
                freeSlot()
                retryLater(gen) { flushIfReady(gen) }
            }
            PinEnqueueResult.RETIRED -> {
                freeSlot()
                retireGeneration(gen)
            }
        }
    }

    private fun arm(gen: Generation, onDeadline: () -> Unit) {
        gen.timer?.cancel()
        gen.timer = coroutines.launch {
            delay(replyTimeoutMs)
            if (live(gen)) onDeadline()
        }
    }

    /** A delayed, bounded retry: after [MAX_MISSES] in a row the generation waits for an explicit trigger. */
    private fun retryLater(gen: Generation, action: () -> Unit) {
        gen.timer?.cancel(); gen.timer = null
        if (++gen.misses > MAX_MISSES) {
            gen.phase = Phase.BLOCKED
            return
        }
        gen.timer = coroutines.launch {
            delay(retryDelayMs)
            if (live(gen)) action()
        }
    }

    private fun track(gen: Generation, context: PinRequestContext) {
        gen.outstanding[context.requestId] = context
        // replies that never come (a silent link) must not accumulate for the generation's lifetime
        while (gen.outstanding.size > MAX_OUTSTANDING) {
            val oldest = gen.outstanding.keys.first { it != gen.fetchRequestId && it != gen.inflightRequestId }
            gen.outstanding.remove(oldest)
        }
    }

    /** The generation is current and its lease still is; a replaced binding retires it on the spot. */
    private fun live(gen: Generation): Boolean {
        if (generation !== gen || gen.retired) return false
        if (registry.isCurrent(gen.lease)) return true
        pairingChanged(explicit = false)
        return false
    }

    private fun currentLease(): PinBindingLease? = lease?.takeIf(registry::isCurrent)

    private fun pairingChanged(explicit: Boolean) {
        retireGeneration()
        post(PinIssueKind.PAIRING_CHANGED, explicit)
    }

    private fun retireGeneration() {
        generation?.let {
            it.retired = true
            it.timer?.cancel()
        }
        generation = null
    }

    private fun retireGeneration(gen: Generation) {
        if (generation === gen) retireGeneration() else { gen.retired = true; gen.timer?.cancel() }
    }

    private fun publish(visible: List<String>) {
        if (visible == lastVisible) return
        lastVisible = visible
        onVisible(visible)
    }

    private fun noteIdentity() {
        val doc = bound?.document()
        val identity = doc?.let { Triple(it.accepted?.pins, it.aliases, it.local) }
        if (identity == lastIdentity) return
        lastIdentity = identity
        identityRevision.value++
    }

    private fun initialIssue(scope: ProjectPinScope): PinSyncIssue? {
        scope.storageIssue()?.let { return it }
        if (!scope.synced) return null
        val doc = scope.document()
        return registry.migrationIssue
            ?: ProjectPinReducer.migrationIssue(doc)
            ?: doc.quarantined.size.takeIf { it > 0 }?.let { PinSyncIssue.RetainedLocally(it) }
    }

    /** A step succeeded: what it cleared gives way to whatever is still unresolved (storage, migration, retained
     *  intent) — a working sync does not settle those. Whether an occurrence ended is the board's, by its proof. */
    private fun clearResolvedIssue(scope: ProjectPinScope) = post(initialIssue(scope), explicit = false)

    /** Storage was actually read back or written: its failure occurrence is over, and so is this link's report of it. */
    private fun storageRecovered(scope: ProjectPinScope) {
        board.reconcile(scope.key, doc = null, storageReady = true)
        if (event.value?.kind == PinIssueKind.STORAGE_FAILED) clearResolvedIssue(scope)
    }

    /** What must happen for a refusal of [context] to be over, judged against the [committed] document after its reply:
     *  the submitted operations it left pending are refused work; with none, it refused a fetch or a lookup replay. */
    private fun refusalProof(context: PinRequestContext, committed: PinStateDoc): PinIssueProof {
        if (context.kind == PinRequestKind.FETCH) return PinIssueProof.Fetch
        val submitted = context.submitted.mapTo(HashSet()) { it.seq }
        val work = if (committed.stream.id != context.streamId) emptySet()
        else committed.pending.mapNotNullTo(HashSet()) { op -> op.seq.takeIf { it in submitted } }
        return if (work.isNotEmpty()) PinIssueProof.Work(context.streamId, work) else PinIssueProof.Lookup(context.streamId, submitted)
    }

    /** [proof]: required with a [PinSyncIssue.Refused], which only a correlated reply reports. */
    private fun post(problem: PinSyncIssue?, explicit: Boolean, proof: PinIssueProof? = null) {
        issue.value = problem
        when (problem) {
            null -> event.value = null
            is PinSyncIssue.RetainedLocally -> post(PinIssueKind.RETAINED_LOCALLY, explicit, problem.count)
            is PinSyncIssue.Refused -> post(
                if (problem.code == ProjectPinErrors.CAPACITY) PinIssueKind.CAPACITY else PinIssueKind.REFUSED, explicit,
                proof = checkNotNull(proof) { "a refusal is reported with what it refused" },
            )
            PinSyncIssue.StorageFailed -> post(PinIssueKind.STORAGE_FAILED, explicit, proof = PinIssueProof.Storage)
            PinSyncIssue.LocalStateReset -> post(PinIssueKind.LOCAL_STATE_RESET, explicit)
            PinSyncIssue.OutboxFull -> post(PinIssueKind.OUTBOX_FULL, explicit)
            PinSyncIssue.MigrationUncertain -> post(PinIssueKind.MIGRATION_UNCERTAIN, explicit)
            is PinSyncIssue.LegacyNotMigrated -> post(PinIssueKind.LEGACY_NOT_MIGRATED, explicit, problem.count)
            PinSyncIssue.AliasResolutionLimited -> post(PinIssueKind.ALIAS_LIMITED, explicit)
        }
    }

    private fun post(kind: PinIssueKind, explicit: Boolean, count: Int = 0, proof: PinIssueProof? = null) {
        val scope = bound?.key ?: return
        val current = event.value
        if (proof != null) {
            // a refusal or storage failure: the event of its computer's shared occurrence, whichever holder heard it
            val id = board.report(scope, kind, proof, explicit)
            if (current == null || current.scope != scope || current.id != id) event.value = PinIssueEvent(scope, id, kind, count, explicit)
            return
        }
        // the same problem heard again is the same event; only a new failed explicit action is a new one
        if (!explicit && current != null && current.scope == scope && current.kind == kind && current.count == count) return
        event.value = PinIssueEvent(scope, board.nextId(), kind, count, explicit)
    }

    private fun failureOf(result: PinUpdate): PinSyncIssue = when (result) {
        is PinUpdate.Blocked -> result.issue
        is PinUpdate.NotSaved -> result.issue
        is PinUpdate.Indeterminate -> result.issue
        else -> PinSyncIssue.StorageFailed
    }

    private fun sameIdentity(a: PairedDaemon?, b: PairedDaemon?): Boolean =
        a === b || (a != null && b != null && a.accountId == b.accountId && a.deviceId == b.deviceId &&
            a.credential == b.credential && a.role == b.role)

    companion object {
        const val REPLY_TIMEOUT_MS = 20_000L
        const val RETRY_DELAY_MS = 30_000L
        private const val MAX_OUTSTANDING = 16
        private const val MAX_MISSES = 3
    }
}
