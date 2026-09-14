package dev.ccpocket.app.pins

import dev.ccpocket.protocol.PROJECT_PINS_MAX_OPS
import dev.ccpocket.protocol.ProjectPin
import dev.ccpocket.protocol.ProjectPinErrors
import dev.ccpocket.protocol.ProjectPinOp
import dev.ccpocket.protocol.ProjectPinResolution
import dev.ccpocket.protocol.ProjectPinsSnapshot
import dev.ccpocket.protocol.ProjectPinsState
import dev.ccpocket.protocol.isValidProjectPinPath
import kotlinx.serialization.Serializable

// ── the client half of project-pin sync (issue #362) ────────────────────────────────────────────────
//
// One durable document per computer. For an OWNER computer it holds the last accepted authoritative snapshot,
// the outbox of explicit SET operations not yet acknowledged, and whatever uncertain intent had to be set aside;
// the visible list is always recomputed from those, never stored. Every function in [ProjectPinReducer] is pure:
// persistence, locking and the wire live elsewhere, so each rule here can be pinned without I/O.

/** The persisted pin state of one computer (or one local-only scope). Versioned; see [ProjectPinCodec]. */
@Serializable
data class PinStateDoc(
    val v: Int = VERSION,
    val stream: PinStream,
    /** Operations of [stream] not yet acknowledged, contiguous from `stream.ackedSeq + 1`, in send order. */
    val pending: List<ProjectPinOp> = emptyList(),
    /** The newest authoritative snapshot accepted from this computer's daemon. */
    val accepted: PinAccepted? = null,
    /** Intent from an abandoned stream whose commit could not be proven (re-pair, store reset, cursor mismatch):
     *  kept and shown on THIS device only, never replayed automatically. One last intent per path. */
    val quarantined: List<QuarantinedPin> = emptyList(),
    /** The one-time migration of the pre-#362 device-global list, when it landed on this computer. */
    val legacy: LegacyPins? = null,
    /** Daemon keys learned for paths on this stream's current store (newest learning last, bounded by
     *  [ProjectPinReducer.MAX_ALIASES]), so intent keeps meaning the same project after the daemon re-canonicalizes
     *  its display path. Forgotten whenever the stream moves to another device or store. */
    val aliases: Map<String, String> = emptyMap(),
    /** Local-only scopes (a folder-share guest binding, no binding at all): the plain list, newest first. */
    val local: List<String> = emptyList(),
    /** Already-acknowledged operations of [stream] (every seq ≤ `stream.ackedSeq`) whose path the daemon has not
     *  yet resolved to a key. NOT intent: never shown, never renumbered, never quarantined — only resent as-is to
     *  ask for a deduplicated replay's resolution, and only by the binding that created them (see [ProjectPinScope]).
     *  Bounded by [ProjectPinReducer.MAX_LOOKUP]. */
    val resolutionPending: List<ProjectPinOp> = emptyList(),
) {
    companion object {
        const val VERSION = 1
    }
}

/**
 * One client operation stream. [deviceId] and [incarnation] are the transport identity and daemon store the
 * stream belongs to — once either differs, its uncertain operations can never be proven and are set aside.
 * [sentSeq] is persisted BEFORE operations are handed to a transport: anything above it certainly never left
 * this device, so it can move to a fresh stream without any risk of a double commit.
 */
@Serializable
data class PinStream(
    val id: String,
    val deviceId: String? = null,
    val incarnation: String? = null,
    val nextSeq: Long = 1,
    val ackedSeq: Long = 0,
    val sentSeq: Long = 0,
)

@Serializable
data class PinAccepted(val incarnation: String, val revision: Long, val pins: List<ProjectPin> = emptyList()) {
    constructor(snapshot: ProjectPinsSnapshot) : this(snapshot.incarnation, snapshot.revision, snapshot.pins)
}

@Serializable
data class QuarantinedPin(val path: String, val pinned: Boolean)

/**
 * The pre-#362 list landed on this computer. [digest] is the landed marker and always stays. [fallback] (newest
 * first) is every entry not yet turned into an operation — including entries the wire cannot carry — shown here
 * locally. [eligible] is the subset proven to belong to this computer (all of it for the only owner computer, else
 * what this computer's own listing named); eligible entries drain into the outbox oldest first as room allows.
 * [claimed]: nothing is left in [fallback].
 */
@Serializable
data class LegacyPins(
    val digest: String,
    val claimed: Boolean,
    val fallback: List<String> = emptyList(),
    val eligible: List<String> = emptyList(),
)

/** What went wrong with pin sync, bounded and actionable. */
sealed interface PinSyncIssue {
    /** Changes made before this computer's pairing or pin store changed were kept on this device only;
     *  pin or unpin those projects again to apply them everywhere. */
    data class RetainedLocally(val count: Int) : PinSyncIssue

    /** The computer refused the pending changes ([code] is one of [ProjectPinErrors]); they stay queued. */
    data class Refused(val code: String, val message: String?) : PinSyncIssue

    /** This device could not save or read pin changes; nothing was changed. */
    data object StorageFailed : PinSyncIssue

    /** The pins saved on this device are unreadable. They are left untouched for recovery, and this computer's
     *  pins cannot be changed on this device until that is resolved. */
    data object LocalStateReset : PinSyncIssue

    /** Too many changes are waiting for this computer; connect to it before changing more pins. */
    data object OutboxFull : PinSyncIssue

    /** The old device-wide pin list could not be assigned safely (stored pin state could not be fully read); it is
     *  kept as a backup and nothing was guessed. */
    data object MigrationUncertain : PinSyncIssue

    /** [count] old pins could not be synced (the computer cannot accept their paths); they stay on this device. */
    data class LegacyNotMigrated(val count: Int) : PinSyncIssue

    /** More synced pins are awaiting their identity than this device keeps track of; some renamed spellings may
     *  show as separate projects. No pin change was lost. */
    data object AliasResolutionLimited : PinSyncIssue
}

/** Which kind of request a reply answers. A fetch carries no operations; a mutation carries at least one. */
enum class PinRequestKind { FETCH, MUTATION }

/** Exactly what went out, so a reply is judged against its own request — never against whatever is queued now. */
data class PinRequestContext(
    val kind: PinRequestKind,
    val streamId: String,
    val subscriptionId: String,
    val requestId: String,
    val expectedIncarnation: String?,
    val submitted: List<ProjectPinOp>,
)

object ProjectPinReducer {
    /** Most operations one computer's outbox holds while it cannot sync. Refusing more is explicit; silently
     *  dropping or coalescing unacknowledged operations would open gaps in the stream. */
    const val MAX_PENDING = 2048

    /** Most acknowledged operations kept only to learn their key. Evicting one loses comparison knowledge only. */
    const val MAX_LOOKUP = 2048

    /** Most learned path → key entries. Evicting one loses comparison knowledge only. */
    const val MAX_ALIASES = 2048

    fun fresh(streamId: String) = PinStateDoc(stream = PinStream(id = streamId))

    // ---- the visible list ----

    /** Newest first: the accepted snapshot, then quarantined intent, then unproven legacy fallback (the oldest
     *  pins), then the outbox. Identity is the daemon's key wherever the daemon has named a path, else the path
     *  itself — the client never canonicalizes a path on its own machine. Fallback entries the wire cannot carry
     *  stay stored but are not listed. */
    fun visible(doc: PinStateDoc, synced: Boolean): List<String> =
        if (!synced) doc.local else visibleRows(doc, Ids(doc)).map { it.first }.distinct()

    /** Whether the visible list shows the project [path] names — compared by the daemon's identity where it has
     *  named that spelling, so a known alias of a pinned project is that pin, never a second one. An unknown
     *  spelling is only itself: the client never canonicalizes. */
    fun isPinned(doc: PinStateDoc, path: String, synced: Boolean): Boolean {
        if (!synced) return path in doc.local
        val ids = Ids(doc)
        val id = ids.of(path)
        return visibleRows(doc, ids).any { it.second == id }
    }

    /** The visible rows as (display path, identity), newest first. */
    private fun visibleRows(doc: PinStateDoc, ids: Ids): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        doc.accepted?.pins?.forEach { pin -> if (rows.none { it.second == pin.key }) rows += pin.path to pin.key }
        fun set(path: String, pinned: Boolean) {
            val id = ids.of(path)
            if (pinned) {
                if (rows.none { it.second == id }) rows.add(0, path to id)
            } else {
                rows.removeAll { it.second == id }
            }
        }
        doc.quarantined.forEach { set(it.path, it.pinned) }
        doc.legacy?.fallback?.forEach { path ->
            if (!isValidProjectPinPath(path)) return@forEach
            val id = ids.of(path)
            if (rows.none { it.second == id }) rows += path to id
        }
        doc.pending.forEach { set(it.path, it.pinned) }
        return rows
    }

    // ---- local edits ----

    sealed interface Edit {
        data class Applied(val doc: PinStateDoc) : Edit
        data object Invalid : Edit
        data object OutboxFull : Edit
    }

    /** One explicit user SET on a synced computer. It supersedes quarantined and legacy-fallback intent for the
     *  same project (the legacy digest stays), and joins the outbox with the next sequence number of the stream. */
    fun enqueue(doc: PinStateDoc, path: String, pinned: Boolean): Edit {
        if (!isValidProjectPinPath(path)) return Edit.Invalid
        if (doc.pending.size >= MAX_PENDING) return Edit.OutboxFull
        val ids = Ids(doc)
        val id = ids.of(path)
        val next = doc.copy(
            stream = doc.stream.copy(nextSeq = doc.stream.nextSeq + 1),
            pending = doc.pending + ProjectPinOp(doc.stream.nextSeq, path, pinned),
            quarantined = doc.quarantined.filterNot { ids.of(it.path) == id },
            legacy = doc.legacy?.let { l ->
                val fallback = l.fallback.filterNot { ids.of(it) == id }
                if (fallback.size == l.fallback.size) l
                else l.copy(claimed = l.claimed || fallback.isEmpty(), fallback = fallback, eligible = l.eligible.filter { it in fallback })
            },
        )
        return Edit.Applied(remember(next, previous = null))
    }

    /** A local-only scope: no stream, no daemon — the list itself. Pinning an already pinned path keeps its place. */
    fun setLocal(doc: PinStateDoc, path: String, pinned: Boolean): Edit {
        if (!isValidProjectPinPath(path)) return Edit.Invalid
        val next = when {
            pinned && path in doc.local -> doc.local
            pinned -> listOf(path) + doc.local
            else -> doc.local - path
        }
        return Edit.Applied(doc.copy(local = next))
    }

    /** The next batch of fresh intent to send, oldest first. Never mixed with [lookupBatch]. */
    fun batch(doc: PinStateDoc): List<ProjectPinOp> = doc.pending.take(PROJECT_PINS_MAX_OPS)

    /** The next batch of acknowledged operations to replay for their resolution only: lowest seq first, at most
     *  [PROJECT_PINS_MAX_OPS], ending at the first gap in sequence numbers. */
    fun lookupBatch(doc: PinStateDoc): List<ProjectPinOp> {
        val sorted = doc.resolutionPending.sortedBy { it.seq }
        val out = ArrayList<ProjectPinOp>()
        for (op in sorted) {
            if (out.size == PROJECT_PINS_MAX_OPS) break
            if (out.isNotEmpty() && op.seq != out.last().seq + 1) break
            out += op
        }
        return out
    }

    /** Record, durably and before sending, that operations through [seq] may have reached the daemon. */
    fun markSent(doc: PinStateDoc, seq: Long): PinStateDoc =
        if (seq <= doc.stream.sentSeq) doc else doc.copy(stream = doc.stream.copy(sentSeq = minOf(seq, doc.stream.nextSeq - 1)))

    // ---- replies and pushes ----

    /** [blocked]: stop flushing until the next trigger (reconnect, foreground, a new user action).
     *  [refetch]: nothing was applied; a correlated fetch must succeed before anything is sent again. */
    data class ReplyOutcome(val doc: PinStateDoc, val issue: PinSyncIssue?, val blocked: Boolean, val refetch: Boolean = false)

    /**
     * A reply to the request described by [context]. The caller has already established it belongs to the current
     * binding and generation; this still refuses anything whose identifiers do not match [context] and the
     * document's stream. [deviceId] is the transport identity of the connection the request went out on.
     *
     * Only a FETCH may move the stream to another device or store (quarantining what may have left). Resolutions
     * are learned only from an error-free MUTATION reply in the stream's current store, and only for rows naming
     * exactly one submitted `(seq, path)`. Mappings, acknowledgement, lookup bookkeeping and legacy draining land
     * in one resulting document.
     */
    fun applyReply(
        doc: PinStateDoc,
        reply: ProjectPinsState,
        context: PinRequestContext,
        deviceId: String,
        newStreamId: () -> String,
    ): ReplyOutcome {
        val stream = doc.stream
        if (reply.subscriptionId != context.subscriptionId || reply.requestId != context.requestId ||
            reply.streamId != context.streamId || context.streamId != stream.id
        ) {
            return ReplyOutcome(doc, issue = null, blocked = false)
        }
        val error = reply.error
        val refusal = error?.let { PinSyncIssue.Refused(it, reply.message) }
        if (error == ProjectPinErrors.INCARNATION_MISMATCH || error == ProjectPinErrors.SUBSCRIPTION_STALE) {
            // no cursor speaks for the batch: never rebase from here, fetch first
            return ReplyOutcome(doc, refusal, blocked = true, refetch = true)
        }
        val snapshot = reply.snapshot
        if (snapshot != null) {
            val otherDevice = stream.deviceId != null && stream.deviceId != deviceId
            val otherStore = stream.incarnation != null && stream.incarnation != snapshot.incarnation
            if (otherDevice || otherStore) {
                return if (context.kind == PinRequestKind.FETCH && refusal != null) {
                    // only a successful fetch proves a new binding or store; a refused one moves nothing and, being a
                    // refusal rather than a stale cursor, does not ask for yet another fetch
                    ReplyOutcome(doc, refusal, blocked = true)
                } else if (context.kind == PinRequestKind.FETCH) {
                    rebase(doc, snapshot, deviceId, newStreamId, blocked = false, foreign = true)
                } else {
                    ReplyOutcome(doc, refusal, blocked = true, refetch = true)
                }
            }
        }
        if (error == ProjectPinErrors.SEQUENCE_GAP) {
            // same device, same store, yet the daemon's cursor is not where this stream left it: nothing below the
            // gap can be proven either way
            return rebase(doc, snapshot, deviceId, newStreamId, blocked = false, foreign = false)
        }
        if (snapshot == null) return ReplyOutcome(doc, refusal, blocked = true)

        var next = stream.copy(deviceId = stream.deviceId ?: deviceId, incarnation = stream.incarnation ?: snapshot.incarnation)
        val ack = reply.ackSeq ?: next.ackedSeq
        if (ack > next.nextSeq - 1) {
            // the daemon holds operations this state never allocated (restored from an older copy): uncertain
            return rebase(doc, snapshot, deviceId, newStreamId, blocked = refusal != null, foreign = false)
        }
        val acked = maxOf(ack, next.ackedSeq) // a lower value is a stale reply, never a cursor regression
        next = next.copy(ackedSeq = acked, sentSeq = maxOf(next.sentSeq, acked))

        val resolved = if (error == null) validResolutions(doc, reply, context) else emptyMap()
        fun isResolved(op: ProjectPinOp) = resolved[op.seq]?.path == op.path
        val aliases = LinkedHashMap(doc.aliases)
        resolved.values.forEach { aliases.remove(it.path); aliases[it.path] = it.key }
        val lookupCandidates = doc.resolutionPending.filterNot(::isResolved) +
            doc.pending.filter { it.seq <= acked && !isResolved(it) }
        val lookup = lookupCandidates.filter { it.seq <= acked }.distinctBy { it.seq }.sortedBy { it.seq }
        val bounded = if (lookup.size > MAX_LOOKUP) lookup.drop(lookup.size - MAX_LOOKUP) else lookup

        var cleared = doc.copy(
            stream = next,
            pending = doc.pending.filter { it.seq > acked },
            aliases = aliases,
            resolutionPending = bounded,
        )
        if (error == null) cleared = advanceLegacy(cleared)
        val issue = refusal ?: if (bounded.size < lookup.size) PinSyncIssue.AliasResolutionLimited else null
        return ReplyOutcome(withAccepted(cleared, acceptIfNewer(doc.accepted, snapshot)), issue, blocked = refusal != null)
    }

    data class PushOutcome(val doc: PinStateDoc, val refetch: Boolean)

    /** An unsolicited snapshot. Never adopts an incarnation: an unfamiliar one only asks for a correlated fetch. */
    fun applyPush(doc: PinStateDoc, snapshot: ProjectPinsSnapshot): PushOutcome {
        val accepted = doc.accepted
        val bound = doc.stream.incarnation
        return when {
            bound == null || accepted == null -> PushOutcome(doc, refetch = false)
            snapshot.incarnation != bound || accepted.incarnation != bound -> PushOutcome(doc, refetch = true)
            snapshot.revision > accepted.revision -> PushOutcome(withAccepted(doc, PinAccepted(snapshot)), refetch = false)
            else -> PushOutcome(doc, refetch = false)
        }
    }

    // ---- legacy migration ----

    /** Exactly one owner computer: every legacy entry is kept with the marker, every carriable one is eligible, and
     *  as many as the outbox has room for become operations now, oldest first, so the daemon's newest-first order
     *  ends as the old list's order. */
    fun claimLegacy(doc: PinStateDoc, newestFirst: List<String>, digest: String): PinStateDoc {
        val paths = legacyPaths(newestFirst)
        val seeded = doc.copy(
            legacy = LegacyPins(digest, claimed = false, fallback = paths, eligible = paths.filter(::isValidProjectPinPath)),
        )
        return advanceLegacy(remember(seeded, previous = null))
    }

    /** Several owner computers: keep the list visible on the computer active at migration time only. */
    fun holdLegacyFallback(doc: PinStateDoc, newestFirst: List<String>, digest: String): PinStateDoc =
        remember(doc.copy(legacy = LegacyPins(digest, claimed = false, fallback = legacyPaths(newestFirst))), previous = null)

    /** This computer's own authoritative directory listing names some fallback paths: those are proven to belong
     *  here, and the proof and whatever it lets drain into the outbox are one document change. */
    fun claimProvenFallback(doc: PinStateDoc, listedPaths: Set<String>): PinStateDoc {
        val legacy = doc.legacy ?: return doc
        val proven = legacy.fallback.filter { it in listedPaths && it !in legacy.eligible }
        if (proven.isEmpty()) return advanceLegacy(doc)
        val eligible = legacy.eligible.toSet() + proven
        return advanceLegacy(doc.copy(legacy = legacy.copy(eligible = legacy.fallback.filter { it in eligible })))
    }

    /**
     * Drain eligible fallback into the outbox, oldest first, with contiguous sequence numbers, while it has room.
     * Only an entry that became an operation leaves the fallback; one the wire cannot carry stays (see
     * [migrationIssue]). Existing pending operations are never coalesced or dropped. Returns [doc] itself when
     * nothing changes, so a repeated attempt is not a write.
     */
    fun advanceLegacy(doc: PinStateDoc): PinStateDoc {
        val legacy = doc.legacy ?: return doc
        val eligible = legacy.eligible.toSet()
        val added = ArrayList<ProjectPinOp>()
        val moved = HashSet<String>()
        var seq = doc.stream.nextSeq
        for (path in legacy.fallback.asReversed()) {
            if (doc.pending.size + added.size >= MAX_PENDING) break
            if (path !in eligible || !isValidProjectPinPath(path)) continue
            added += ProjectPinOp(seq++, path, pinned = true)
            moved += path
        }
        val fallback = if (moved.isEmpty()) legacy.fallback else legacy.fallback.filterNot { it in moved }
        val claimed = legacy.claimed || fallback.isEmpty()
        if (moved.isEmpty() && claimed == legacy.claimed) return doc
        val next = doc.copy(
            stream = doc.stream.copy(nextSeq = seq),
            pending = doc.pending + added,
            legacy = legacy.copy(claimed = claimed, fallback = fallback, eligible = legacy.eligible.filterNot { it in moved }),
        )
        return remember(next, previous = null)
    }

    /** The static migration problem of [doc], if any: legacy entries that can never become operations. */
    fun migrationIssue(doc: PinStateDoc): PinSyncIssue? {
        val stuck = doc.legacy?.fallback?.count { !isValidProjectPinPath(it) } ?: 0
        return if (stuck > 0) PinSyncIssue.LegacyNotMigrated(stuck) else null
    }

    // ---- internals ----

    /** Identity of a path: the key the current snapshot gives it, else one learned on this store, else the path
     *  itself. `:` never occurs in a daemon key (fixed-size base64url), so the forms cannot collide. */
    private class Ids(doc: PinStateDoc) {
        private val current = doc.accepted?.pins?.associate { it.path to it.key }.orEmpty()
        private val learned = doc.aliases
        fun of(path: String): String = current[path] ?: learned[path] ?: "path:$path"
    }

    /** Every distinct nonblank entry, in order — nothing is dropped, not even what the wire cannot carry. */
    private fun legacyPaths(newestFirst: List<String>) = newestFirst.filter { it.isNotBlank() }.distinct()

    /**
     * The rows of [reply] that resolve exactly one submitted operation: an error-free mutation reply, on the
     * stream's current store and the incarnation the request named. A seq that appears twice, names another path,
     * or carries an unusable key is ignored — nothing is inferred from order or from what is missing.
     */
    private fun validResolutions(doc: PinStateDoc, reply: ProjectPinsState, context: PinRequestContext): Map<Long, ProjectPinResolution> {
        val rows = reply.resolutions ?: return emptyMap()
        val incarnation = doc.stream.incarnation ?: return emptyMap()
        if (context.kind != PinRequestKind.MUTATION || context.submitted.isEmpty()) return emptyMap()
        if (context.expectedIncarnation != incarnation || reply.snapshot?.incarnation != incarnation) return emptyMap()
        if (rows.size > PROJECT_PINS_MAX_OPS) return emptyMap()
        val submitted = context.submitted.groupBy { it.seq }
        return rows.groupBy { it.seq }
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }
            .filter { (seq, row) ->
                val op = submitted[seq]?.singleOrNull()
                op != null && op.path == row.path && row.key.isNotBlank() && ':' !in row.key
            }
    }

    /** Adopt [next] as the accepted snapshot while keeping the keys of paths this device's intent still names. */
    private fun withAccepted(doc: PinStateDoc, next: PinAccepted): PinStateDoc =
        remember(doc.copy(accepted = next), previous = doc.accepted)

    /** Learn the snapshot keys of paths this device's intent names. Learned keys are not forgotten when that intent
     *  completes — only when the bound is exceeded (unreferenced entries first) or the store changes. */
    private fun remember(doc: PinStateDoc, previous: PinAccepted?): PinStateDoc {
        val referenced = HashSet<String>()
        doc.pending.forEach { referenced += it.path }
        doc.quarantined.forEach { referenced += it.path }
        doc.legacy?.fallback?.let(referenced::addAll)
        val learned = LinkedHashMap(doc.aliases)
        fun learn(pin: ProjectPin) {
            if (pin.path !in referenced || learned[pin.path] == pin.key) return
            learned.remove(pin.path)
            learned[pin.path] = pin.key
        }
        previous?.pins?.forEach(::learn)
        doc.accepted?.pins?.forEach(::learn) // the newest naming of a path wins
        if (learned.size > MAX_ALIASES) {
            val excess = learned.size - MAX_ALIASES
            val victims = learned.keys.filter { it !in referenced }.take(excess).toMutableList()
            if (victims.size < excess) victims += learned.keys.filter { it !in victims }.take(excess - victims.size)
            victims.forEach(learned::remove)
        }
        return if (learned == doc.aliases) doc else doc.copy(aliases = learned)
    }

    /** Move to a fresh stream bound to [deviceId] and the reply's store. Operations that may have left this device
     *  are quarantined (one last intent per path); those that certainly never did keep their order on the new
     *  stream from sequence 1. Acknowledged lookup work never moves; for a [foreign] device or store, nothing learned
     *  about the old store's identities moves either. */
    private fun rebase(
        doc: PinStateDoc,
        snapshot: ProjectPinsSnapshot?,
        deviceId: String,
        newStreamId: () -> String,
        blocked: Boolean,
        foreign: Boolean,
    ): ReplyOutcome {
        val uncertain = doc.pending.filter { it.seq <= doc.stream.sentSeq }
        val certain = doc.pending.filter { it.seq > doc.stream.sentSeq }.mapIndexed { i, op -> op.copy(seq = i + 1L) }
        val quarantined = (doc.quarantined + uncertain.map { QuarantinedPin(it.path, it.pinned) })
            .asReversed().distinctBy { it.path }.asReversed()
        val rebased = doc.copy(
            stream = PinStream(
                id = newStreamId(), deviceId = deviceId, incarnation = snapshot?.incarnation, nextSeq = certain.size + 1L,
            ),
            pending = certain,
            quarantined = quarantined,
            resolutionPending = emptyList(),
            aliases = if (foreign) emptyMap() else doc.aliases,
            accepted = if (foreign) null else doc.accepted,
        )
        val next = if (snapshot != null) withAccepted(rebased, PinAccepted(snapshot)) else remember(rebased, previous = null)
        val issue = if (quarantined.isNotEmpty()) PinSyncIssue.RetainedLocally(quarantined.size) else null
        return ReplyOutcome(next, issue, blocked)
    }

    private fun acceptIfNewer(current: PinAccepted?, snapshot: ProjectPinsSnapshot): PinAccepted =
        if (current == null || current.incarnation != snapshot.incarnation || snapshot.revision >= current.revision) {
            PinAccepted(snapshot)
        } else {
            current
        }
}
