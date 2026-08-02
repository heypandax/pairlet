package dev.ccpocket.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// ===========================================================================
//  Session Handoff (docs/design/SESSION-HANDOFF.md): an initiator hands the
//  CURRENT Session to a chosen colleague at a stable checkpoint; the recipient
//  continues it EXCLUSIVELY on the owner's machine and returns control plus a
//  structured result. Domain models + the daemon↔client control frames (§6/§9.1).
//
//  Wire rules (the red lines this file must never break):
//   - every enum decodes TOLERANTLY: a value only a NEWER peer knows degrades to
//     UNKNOWN (the AccessTier/AuthBlockReason pattern) instead of failing the whole
//     Envelope decode — and consumers must treat UNKNOWN as the SAFEST reading
//     (a handoff in an unknown state stays locked, an unknown access grants nothing);
//   - every field added later must be a trailing optional/default;
//   - all pocket/handoff.* frames are brand-new message types: an old peer can't
//     decode the unknown "t" and DROPS the frame (the runCatching-at-decode path) —
//     clients arm a reply deadline and show their "update the daemon" state.
// ===========================================================================

/** What the initiator asks the recipient to do (§10: v1 ships REVIEW; CONTINUE is a later milestone). */
@Serializable(with = HandoffKindSerializer::class)
enum class HandoffKind(internal val wire: String) {
    /** Independent code review of the session's current work — read-only by default. */
    REVIEW("review"),

    /** Carry the task forward (edits allowed within the granted scope) — a later milestone. */
    CONTINUE("continue"),

    /** Decode fallback for a newer peer's value. Never encoded; a daemon refuses to CREATE with it. */
    UNKNOWN("unknown"),
}

private object HandoffKindSerializer : KSerializer<HandoffKind> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("HandoffKind", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: HandoffKind) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): HandoffKind {
        val s = decoder.decodeString()
        return HandoffKind.entries.firstOrNull { it.wire == s } ?: HandoffKind.UNKNOWN
    }
}

/**
 * A Handoff's lifecycle state (§5.1). The daemon's persisted state is the ONLY authorization truth
 * (§5.3 item 8) — App display state never is. [DRAFT] is a client-side preview notion: the daemon
 * creates directly in [WAITING] (the wire CreateHandoff IS the send) and never persists a DRAFT.
 */
@Serializable(with = HandoffStatusSerializer::class)
enum class HandoffStatus(internal val wire: String) {
    /** Client-side preview before send. Never persisted by the daemon. */
    DRAFT("draft"),

    /** Sent, nobody accepted yet. NO ONE may drive the session (§5.3 item 2) — the initiator must cancel first. */
    WAITING("waiting"),

    /** Accepted — the recipient holds the (only) controller lease and drives the session exclusively. */
    IN_PROGRESS("in_progress"),

    /** The recipient returned control + a [HandoffResult]; the initiator drives again. NON-terminal. */
    RETURNED("returned"),

    /** Terminal: the initiator acknowledged the returned result. */
    COMPLETED("completed"),

    /** Terminal: the recipient declined while waiting. */
    DECLINED("declined"),

    /** Terminal: the initiator withdrew while waiting. */
    CANCELLED("cancelled"),

    /** Terminal: nobody accepted before [SessionHandoff.expiresAt]. */
    EXPIRED("expired"),

    /** Terminal: the initiator (or the daemon, on lease timeout) took control back mid-progress. */
    RECALLED("recalled"),

    /** Decode fallback for a newer peer's value. Consumers treat it as LOCKED (fail closed), never as done. */
    UNKNOWN("unknown"),
}

private object HandoffStatusSerializer : KSerializer<HandoffStatus> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("HandoffStatus", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: HandoffStatus) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): HandoffStatus {
        val s = decoder.decodeString()
        return HandoffStatus.entries.firstOrNull { it.wire == s } ?: HandoffStatus.UNKNOWN
    }
}

/** The five states after which a Handoff is history: nothing transitions out of them, the Session is
 *  free again. [HandoffStatus.UNKNOWN] is deliberately NOT terminal — a state this build can't read
 *  must keep the session conservatively locked, not silently release it. */
val HandoffStatus.isTerminal: Boolean
    get() = this == HandoffStatus.COMPLETED || this == HandoffStatus.DECLINED ||
        this == HandoffStatus.CANCELLED || this == HandoffStatus.EXPIRED || this == HandoffStatus.RECALLED

/** The operation ceiling granted to the recipient (§8.3/§8.4). Enforced daemon-side; never widened by
 *  prompt text or client fields (§5.3 item 9). */
@Serializable(with = HandoffAccessSerializer::class)
enum class HandoffAccess(internal val wire: String) {
    /** v1 default: Read/search/diff/safe read-only commands; every write tool refuses (§8.3). */
    REVIEW_READ_ONLY("review_read_only"),

    /** Later milestone: edits inside [SessionHandoff.allowedRoots], ceiling ACCEPT_EDITS, never bypass (§8.4). */
    CONTINUE_SCOPED("continue_scoped"),

    /** Decode fallback for a newer peer's value — clamp to the SAFEST (read-only, or refuse outright).
     *  Never encoded; a daemon refuses to CREATE with it. */
    UNKNOWN("unknown"),
}

private object HandoffAccessSerializer : KSerializer<HandoffAccess> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("HandoffAccess", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: HandoffAccess) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): HandoffAccess {
        val s = decoder.decodeString()
        return HandoffAccess.entries.firstOrNull { it.wire == s } ?: HandoffAccess.UNKNOWN
    }
}

/**
 * One Session Handoff — an independent first-class entity hanging off its Source Session (§3), with
 * its own id, status and lifecycle. Durable binding is (provider [sourceSessionId] + [workdir] +
 * [agent]), never a process-lifetime convoId alone (§5.4) — [sourceConvoId] is only the live hint.
 *
 * Everything past the first two fields defaults, so the shape can grow tail-first and a minimal or
 * older peer's JSON still decodes (the enum fields additionally fall back to UNKNOWN on values only
 * a newer peer knows). A REAL daemon always encodes the full shape (encodeDefaults = true).
 */
@Serializable
data class SessionHandoff(
    val id: String,
    val sourceSessionId: String,
    val workdir: String = "",
    val agent: AgentKind = AgentKind.CLAUDE,
    val initiatorDeviceId: String = "",
    val initiatorLabel: String? = null,
    val recipientDeviceId: String? = null,
    val recipientLabel: String? = null,
    val kind: HandoffKind = HandoffKind.UNKNOWN,
    val status: HandoffStatus = HandoffStatus.UNKNOWN,
    val access: HandoffAccess = HandoffAccess.UNKNOWN,
    val brief: HandoffBrief = HandoffBrief(),
    /** CONTINUE_SCOPED only: the absolute roots file tools are confined to. Empty for REVIEW. */
    val allowedRoots: List<String> = emptyList(),
    val createdAt: Long = 0,
    /** WAITING deadline: unaccepted past this instant → EXPIRED (daemon sweep). Epoch ms. */
    val expiresAt: Long = 0,
    val sourceConvoId: String? = null,
    /** The transcript cursor at handoff time — where the recipient's view of "current work" starts. */
    val sourceEventSeq: Long = 0,
    val acceptedAt: Long? = null,
    val returnedAt: Long? = null,
    val result: HandoffResult? = null,
    /**
     * §5.4 graceful recall IN FLIGHT: the initiator asked for control back while a turn was still
     * EXECUTING, so the daemon interrupted it and is waiting for the stable point. The row stays
     * IN_PROGRESS (the lease outlives the request, so nothing else can grab the session), but NOBODY
     * may drive while this is true — the recipient's prompts/verdicts are refused and the initiator
     * shows "taking control back…" instead of a finished recall. Cleared on every IN_PROGRESS exit.
     */
    val recallPending: Boolean = false,
    /**
     * Stamped on the RECALLED row when the recall did NOT reach a clean stop: the interrupt did not
     * settle the turn within the daemon's bounded wait, or background work the daemon cannot kill (a
     * detached shell the agent started) is still running. The UI must say so rather than claim
     * everything stopped.
     */
    val recallIncomplete: Boolean = false,
)

/**
 * The structured brief the initiator's Skill drafts and the initiator confirms (§7.3) — navigation and
 * explanation for the recipient, NOT a transcript redaction mechanism and NOT an authorization surface
 * (§5.3 item 9, §8.5). Only [request] is expected to be non-blank; every other field is optional color.
 */
@Serializable
data class HandoffBrief(
    /** What the recipient is asked to do — the one field a usable brief must carry. */
    val request: String = "",
    val originalGoal: String? = null,
    val completedWork: List<String> = emptyList(),
    val currentState: String? = null,
    val decisions: List<String> = emptyList(),
    val focusAreas: List<String> = emptyList(),
    val relevantFiles: List<String> = emptyList(),
    val verification: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val definitionOfDone: List<String> = emptyList(),
)

/**
 * The structured outcome the recipient returns (§7.5). [returnedByDeviceId]/[returnedAt] are STAMPED
 * BY THE DAEMON on the return transition (the recipient's draft may leave them blank — a client-declared
 * identity is never trusted), which is why they default rather than being required like the §6 sketch.
 */
@Serializable
data class HandoffResult(
    val summary: String = "",
    /** REVIEW: the overall verdict line (e.g. "LGTM with 2 findings"). Null for a plain CONTINUE return. */
    val verdict: String? = null,
    val findings: List<HandoffFinding> = emptyList(),
    val workCompleted: List<String> = emptyList(),
    val changedFiles: List<String> = emptyList(),
    val verification: List<String> = emptyList(),
    val remainingRisks: List<String> = emptyList(),
    val recommendedNextSteps: List<String> = emptyList(),
    /** Daemon-stamped: the recipient device that returned. "" until the daemon stamps it. */
    val returnedByDeviceId: String = "",
    /** Daemon-stamped: when the return landed (epoch ms). 0 until the daemon stamps it. */
    val returnedAt: Long = 0,
)

/**
 * One review finding inside a [HandoffResult]. [severity] is a plain string (the [AuthState.apiKeySource]
 * pattern): a future level degrades to display text instead of failing the decode — the suggested values
 * are the SEVERITY_* constants, ordered most→least severe.
 */
@Serializable
data class HandoffFinding(
    val title: String = "",
    val severity: String = SEVERITY_INFO,
    /** The finding's body — why it matters, what to change. */
    val detail: String? = null,
    val file: String? = null,
    val line: Int? = null,
) {
    companion object {
        const val SEVERITY_CRITICAL = "critical"
        const val SEVERITY_HIGH = "high"
        const val SEVERITY_MEDIUM = "medium"
        const val SEVERITY_LOW = "low"
        const val SEVERITY_INFO = "info"
    }
}

/**
 * The Session's exclusive input-control lease (§5.3 invariant 1): at most ONE active lease per
 * Session, stored and checked INDEPENDENTLY of [HandoffStatus] — never inferred from UI or from
 * "status looks IN_PROGRESS". No lease exists during WAITING (everyone is refused); the lease is
 * created atomically on accept and deleted the instant the Handoff leaves IN_PROGRESS.
 */
@Serializable
data class SessionControllerLease(
    val sessionId: String,
    val handoffId: String,
    val controllerDeviceId: String,
    val acquiredAt: Long,
    /** Past this instant the daemon reclaims control (IN_PROGRESS → RECALLED). Epoch ms. */
    val leaseExpiresAt: Long,
    /** §5.4: the initiator asked to recall while a turn was executing — reclaim at the next stable point. */
    val recallRequested: Boolean = false,
)

// ===========================================================================
//  client <-> daemon control frames (§9.1). All are NEW message types: an old
//  peer drops the unknown "t" (runCatching at decode) — silence is the client's
//  signal to arm a deadline and show its "update the daemon" state.
// ===========================================================================

/**
 * owner -> daemon: create a Handoff on the (persistently identified) Source Session and put it in
 * WAITING. The daemon refuses when the session already has a non-terminal Handoff (§3: at most one),
 * when [kind]/[access] decode to UNKNOWN (a newer client's value this daemon can't enforce — fail
 * closed, never guess), or when the turn/pending-ask preconditions of §4.1 fail. The initiator's
 * device identity comes from the TRANSPORT, never from a frame field. Reply: one [HandoffCreated].
 */
@Serializable
@SerialName("pocket/handoff.create")
data class CreateHandoff(
    val workdir: String,
    val sessionId: String,
    val brief: HandoffBrief,
    val kind: HandoffKind = HandoffKind.REVIEW,
    val access: HandoffAccess = HandoffAccess.REVIEW_READ_ONLY,
    val agent: AgentKind = AgentKind.CLAUDE,
    /** CONTINUE_SCOPED only; ignored (kept empty) for REVIEW. */
    val allowedRoots: List<String> = emptyList(),
    /** WAITING lifetime; daemon-clamped into sane bounds. */
    val expiresInSec: Long = DEFAULT_HANDOFF_EXPIRES_SEC,
    /** Display nickname for the intended colleague (the invite is what actually binds a device). */
    val recipientLabel: String? = null,
    val sourceConvoId: String? = null,
    /** Collaborator-picker flow (§4.2 step 7): bind the Grant to this contact's device up front —
     *  only that device may accept. Null keeps the older open-invite behaviour (any owner device). */
    val recipientDeviceId: String? = null,
) : ToDaemon

/** daemon -> owner: the reply to [CreateHandoff]. On failure [error] says why (busy session, an
 *  existing non-terminal handoff, unknown kind/access, bad expiry) and [code] carries the same refusal
 *  machine-readably (`handoff_not_supported` for a kind/access combination this daemon defines but does
 *  not enforce yet, `unknown_kind`/`unknown_access` for a newer peer's value, …). Trailing + defaulted:
 *  an older peer omits it and clients keep falling back to [error] text. */
@Serializable
@SerialName("pocket/handoff.created")
data class HandoffCreated(
    val ok: Boolean,
    val handoff: SessionHandoff? = null,
    val error: String? = null,
    val code: String? = null,
) : ToPhone

/** owner/recipient -> daemon: list Handoffs this caller may see (the daemon filters by credential:
 *  an owner sees all, a HANDOFF credential only its own). Both filters null = everything visible.
 *  Reply: one [HandoffListing]. */
@Serializable
@SerialName("pocket/handoff.list")
data class ListHandoffs(
    val workdir: String? = null,
    val sessionId: String? = null,
) : ToDaemon

/** daemon -> caller: the reply to [ListHandoffs]. */
@Serializable
@SerialName("pocket/handoff.listing")
data class HandoffListing(val items: List<SessionHandoff> = emptyList()) : ToPhone

/**
 * recipient -> daemon: accept a WAITING Handoff. The daemon's compare-and-set makes exactly ONE
 * accepting device win (§5.3 item 5) — the second device, an expired invite, and a cancel that landed
 * first all get a [HandoffUpdated]/[PocketError] refusal. On success the Handoff turns IN_PROGRESS and
 * the accepting device holds the freshly minted [SessionControllerLease].
 */
@Serializable
@SerialName("pocket/handoff.accept")
data class AcceptHandoff(val handoffId: String) : ToDaemon

/** recipient -> daemon: decline a WAITING Handoff (→ DECLINED, control back to the initiator). */
@Serializable
@SerialName("pocket/handoff.decline")
data class DeclineHandoff(val handoffId: String, val reason: String? = null) : ToDaemon

/** owner -> daemon: withdraw a WAITING Handoff (→ CANCELLED, the initiator may type again). */
@Serializable
@SerialName("pocket/handoff.cancel")
data class CancelHandoff(val handoffId: String) : ToDaemon

/** owner -> daemon: take control back from an IN_PROGRESS Handoff. An IDLE session settles → RECALLED
 *  immediately (the lease dies NOW). With a turn EXECUTING the daemon first marks
 *  [SessionControllerLease.recallRequested] + [SessionHandoff.recallPending] (every input is refused
 *  from that instant), interrupts the turn, and only settles RECALLED at the stable point — so the
 *  answer arrives as TWO [HandoffUpdated]s: the pending one, then the terminal one (§5.4). */
@Serializable
@SerialName("pocket/handoff.recall")
data class RecallHandoff(val handoffId: String) : ToDaemon

/** recipient -> daemon: return control (IN_PROGRESS → RETURNED). [result] is the recipient's
 *  confirmed draft; the daemon stamps [HandoffResult.returnedByDeviceId]/[HandoffResult.returnedAt]
 *  itself. Null result = an empty return (still a valid give-back). */
@Serializable
@SerialName("pocket/handoff.return")
data class ReturnHandoff(val handoffId: String, val result: HandoffResult? = null) : ToDaemon

/** owner -> daemon: acknowledge a RETURNED Handoff's result (→ COMPLETED, the terminal happy path). */
@Serializable
@SerialName("pocket/handoff.complete")
data class CompleteHandoff(val handoffId: String) : ToDaemon

/** daemon -> attached allowed clients: a Handoff changed state — pushed on every transition (accept,
 *  decline, cancel, recall, return, expiry, completion) so both sides' UI reconciles from daemon truth
 *  (§5.3 item 8). Clients key on [SessionHandoff.id] and replace wholesale. */
@Serializable
@SerialName("pocket/handoff.updated")
data class HandoffUpdated(val handoff: SessionHandoff) : ToPhone

/** Default WAITING lifetime for a fresh Handoff (24h) — daemon-clamped, mirrored by client forms. */
const val DEFAULT_HANDOFF_EXPIRES_SEC: Long = 24 * 3600
