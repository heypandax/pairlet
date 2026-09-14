package dev.ccpocket.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── project-pin sync (issue #362) ───────────────────────────────────────────────────────────────────
//
// ONE ordered project-pin list per paired computer, owned by that computer's daemon and shared by every
// OWNER client of it. Clients never replace the list: each keeps a durable outbox of explicit SET operations
// (pin / unpin — never a toggle) and the daemon commits them in arrival order, so different projects'
// offline changes both survive and, for the SAME project, daemon commit order wins.
//
// Identities, none of which grants anything:
//  - streamId: one client's operation stream. Sequence numbers are strictly increasing and contiguous per
//    stream; the daemon keeps a durable high-water cursor per (transport-authenticated deviceId, streamId),
//    so a replayed request is deduplicated and a caller naming another device's stream only ever touches
//    its OWN partition. It is an idempotency identifier, not a credential.
//  - subscriptionId: one client sync generation (one connection). Echoed on the reply and on every push the
//    daemon delivers to that connection, so a late frame from a retired generation can be recognized.
//  - incarnation: the daemon store's identity. A different incarnation means cursors from before are gone, so
//    a batch names the incarnation it was queued against and is refused under any other.
//
// Frame budget: PROJECT_PINS_MAX pins × PROJECT_PIN_PATH_MAX_CHARS chars (≤3 UTF-8 bytes per UTF-16 unit)
// plus a fixed-size key — and, on a reply, one resolution row per operation of a full batch — stays under
// 1 MiB, and a request of PROJECT_PINS_MAX_OPS ops under 600 KiB — both far below the relay/LAN 4 MiB cap.

/** Most pins one computer holds. A batch whose RESULT would exceed it is refused whole. */
const val PROJECT_PINS_MAX: Int = 100

/** Longest project path either end accepts. */
const val PROJECT_PIN_PATH_MAX_CHARS: Int = 2048

/** Most operations one [SyncProjectPins] may carry; a longer outbox is flushed in consecutive batches. */
const val PROJECT_PINS_MAX_OPS: Int = 64

/** Bounds of the opaque request / subscription / stream identifiers. */
const val PROJECT_PIN_ID_MAX_CHARS: Int = 64
const val PROJECT_PIN_TOKEN_MIN_CHARS: Int = 16

/** Structural path check shared by both ends, so a client never queues an operation the daemon must
 *  refuse (a refused operation would otherwise block every later one in its stream). OS-level meaning —
 *  tilde expansion, absolute or not, canonical identity — stays the daemon's decision. */
fun isValidProjectPinPath(path: String): Boolean =
    path.isNotBlank() && path.length <= PROJECT_PIN_PATH_MAX_CHARS && path.none { it < ' ' || it == '' }

/** An identifier of [minChars]..[PROJECT_PIN_ID_MAX_CHARS] URL-safe characters. */
fun isValidProjectPinToken(id: String, minChars: Int = PROJECT_PIN_TOKEN_MIN_CHARS): Boolean =
    id.length in minChars..PROJECT_PIN_ID_MAX_CHARS &&
        id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

/** One pinned project. [path] is the display/open spelling the daemon stored; [key] is an opaque identity
 *  the daemon derives from its own canonical form of the path — equal keys are the same project on that
 *  computer, so clients compare keys for equality and never derive them. */
@Serializable
data class ProjectPin(
    val path: String,
    val key: String,
)

/** One explicit SET operation: [pinned] true pins [path] (a no-op when already pinned — it does not
 *  reorder), false unpins it. */
@Serializable
data class ProjectPinOp(
    val seq: Long,
    val path: String,
    val pinned: Boolean,
)

/** The authoritative list, newest pin first. [revision] increases whenever the list changes and is only
 *  comparable within one [incarnation]. Never carries another client's cursor. */
@Serializable
data class ProjectPinsSnapshot(
    val incarnation: String,
    val revision: Long,
    val pins: List<ProjectPin> = emptyList(),
)

/** client -> daemon, OWNER clients only: apply [ops] (contiguous sequence numbers of [streamId]) and answer
 *  with the authoritative snapshot. Empty [ops] is a fetch — the first request of every sync generation,
 *  and the only request that registers [subscriptionId] as this connection's push generation (once it is
 *  accepted); a batch must name the subscription already registered. Sequence numbers at or below the
 *  daemon's cursor are deduplicated; new ones must start exactly one past it.
 *
 *  [expectedIncarnation] is the store incarnation the client's stream belongs to. A non-empty batch without
 *  it, or naming another incarnation, is refused whole with [ProjectPinErrors.INCARNATION_MISMATCH]; a fetch
 *  may omit it. Absent (an older client) decodes to null. */
@Serializable
@SerialName("pocket/pins.sync")
data class SyncProjectPins(
    val requestId: String,
    val subscriptionId: String,
    val streamId: String,
    val ops: List<ProjectPinOp> = emptyList(),
    val expectedIncarnation: String? = null,
) : ToDaemon

/** How the daemon resolves one submitted operation's path NOW: [seq] and [path] exactly as submitted, [key]
 *  the fixed-size identity of that path's CURRENT resolution. It proves the requested spelling, not the
 *  identity the operation had when it was first committed — a duplicate replay reports today's resolution. */
@Serializable
data class ProjectPinResolution(
    val seq: Long,
    val path: String,
    val key: String,
)

/**
 * daemon -> client, only to a connection that declared [ClientCaps.supportsProjectPins].
 *
 * A REPLY carries [requestId], [streamId] and [ackSeq] — the requester's own high-water sequence for that
 * stream after this request. A PUSH (another client committed a change) carries only [subscriptionId] and
 * [snapshot]. A refusal carries [error] (one of [ProjectPinErrors]) and leaves the refused operations
 * uncommitted; its [snapshot]/[ackSeq] are present only when the store itself could be read, so a storage
 * failure can never look like an empty list. An [ProjectPinErrors.INCARNATION_MISMATCH] refusal carries the
 * current snapshot when readable but never an [ackSeq]: no cursor of the store it refused speaks for the batch.
 *
 * [resolutions] appear only on a SUCCESSFUL reply to a non-empty request, a deduplicated replay included: one
 * [ProjectPinResolution] per submitted operation, in request order. A fetch, a push and every refusal carry
 * none. Absent (an older daemon) decodes to null.
 */
@Serializable
@SerialName("pocket/pins.state")
data class ProjectPinsState(
    val subscriptionId: String,
    val requestId: String? = null,
    val streamId: String? = null,
    val ackSeq: Long? = null,
    val snapshot: ProjectPinsSnapshot? = null,
    val error: String? = null,
    val message: String? = null,
    val resolutions: List<ProjectPinResolution>? = null,
) : ToPhone

/** The bounded refusal vocabulary of [ProjectPinsState.error]. */
object ProjectPinErrors {
    /** This connection cannot sync (no authenticated device identity, or no pin store wired). */
    const val UNAVAILABLE = "pins_unavailable"
    /** Malformed identifiers, too many operations, or non-contiguous sequence numbers. */
    const val INVALID_REQUEST = "pins_invalid_request"
    /** An operation's path fails [isValidProjectPinPath]. */
    const val INVALID_PATH = "pins_invalid_path"
    /** The first new sequence number is not one past the cursor: the stream is not continuous here. */
    const val SEQUENCE_GAP = "pins_sequence_gap"
    /** The resulting list would exceed [PROJECT_PINS_MAX]. */
    const val CAPACITY = "pins_capacity"
    /** The daemon holds as many stream cursors as it can keep without dropping needed dedup state. */
    const val STREAM_CAPACITY = "pins_stream_capacity"
    /** The store could not be read or durably written; nothing was acknowledged. After a save whose outcome is
     *  unknown the daemon refuses every later request until it restarts and re-reads the store. */
    const val STORE_UNAVAILABLE = "pins_store_unavailable"
    /** The store file exists but cannot be decoded; it is left untouched for recovery. */
    const val STORE_CORRUPT = "pins_store_corrupt"
    /** A batch named no incarnation, or another one than the store's: nothing was committed or acknowledged. The
     *  client fetches before it sends that intent again; it never replays it into the new incarnation. */
    const val INCARNATION_MISMATCH = "pins_incarnation_mismatch"
    /** The request came from a connection that is not its device's registered pin subscription — a retired
     *  connection, or a batch sent before its connection's fetch was accepted. Nothing was committed. */
    const val SUBSCRIPTION_STALE = "pins_subscription_stale"
}
