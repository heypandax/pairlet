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
//  ReviewRequest (docs/design/REVIEW-REQUEST.md): TASK-context handoff. A sender
//  shares a narrow artifact reference (MR / document / commit range) plus a brief;
//  the recipient reviews it with THEIR OWN daemon, repo, agent and credentials and
//  returns a structured result. Nothing about the sender's runtime — session id,
//  transcript, workdir, controller lease — crosses this wire (§3.1).
//
//  DELIBERATELY SEPARATE from Handoff.kt: Session Handoff is the RUNTIME-context
//  handoff and keeps its own state machine, permission semantics and frames
//  untouched (§13.3). The two share only the Collaborator Link + E2E transport.
//
//  Same wire red lines as Handoff.kt/Collaborator.kt:
//   - every enum decodes TOLERANTLY to UNKNOWN, and UNKNOWN is the SAFEST reading
//     (an unknown status stays locked; an unknown artifact kind can't be prepared);
//   - every field added later is a trailing optional with a default;
//   - all pocket/review.* frames are new types an old peer silently drops, so the
//     caller arms a reply deadline and says "the other daemon needs an update".
// ===========================================================================

/**
 * A ReviewRequest's lifecycle state (§8). The SENDER daemon's persisted row is the only authority; the
 * recipient's mirror and every UI are followers.
 *
 * `QUEUED` means "the sender persisted it, the recipient has not confirmed landing it on disk". Only a
 * recipient-side durable ACK produces [DELIVERED] — a successful relay write is NOT delivery.
 */
@Serializable(with = ReviewStatusSerializer::class)
enum class ReviewStatus(internal val wire: String) {
    /** Persisted by the sender, not yet durably ACKed by the recipient daemon. */
    QUEUED("queued"),

    /** The recipient daemon persisted its local mirror and ACKed. Nobody has looked at it yet. */
    DELIVERED("delivered"),

    /** The recipient saw it and took it on ("I'll do this"), without starting work. */
    ACKNOWLEDGED("acknowledged"),

    /** The recipient is reviewing right now. Optional: a light review may [RESPONDED] straight away. */
    IN_PROGRESS("in_progress"),

    /** The recipient returned a [ReviewResult]. NON-terminal: the sender still has to close it. */
    RESPONDED("responded"),

    /** Terminal: the sender acknowledged the result. */
    CLOSED("closed"),

    /** Terminal: the recipient declined to review. */
    DECLINED("declined"),

    /** Terminal: the sender withdrew before any work/result existed. */
    CANCELLED("cancelled"),

    /** Terminal: nobody responded before [ReviewRequest.expiresAt]. */
    EXPIRED("expired"),

    /** Decode fallback for a newer peer's value. Treated as LOCKED (fail closed) — never as done, never
     *  as actionable: no transition may leave it and no `prepare` may execute from it. */
    UNKNOWN("unknown"),
}

private object ReviewStatusSerializer : KSerializer<ReviewStatus> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ReviewStatus", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ReviewStatus) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): ReviewStatus {
        val s = decoder.decodeString()
        return ReviewStatus.entries.firstOrNull { it.wire == s } ?: ReviewStatus.UNKNOWN
    }
}

/** The four states after which a request is history. [ReviewStatus.RESPONDED] is deliberately NOT
 *  terminal (the sender still closes it) and neither is [ReviewStatus.UNKNOWN] — a state this build
 *  cannot read must stay locked rather than be silently treated as finished. */
val ReviewStatus.isTerminal: Boolean
    get() = this == ReviewStatus.CLOSED || this == ReviewStatus.DECLINED ||
        this == ReviewStatus.CANCELLED || this == ReviewStatus.EXPIRED

/** What kind of thing is being reviewed (§7.2). M1 ships the three link-shaped kinds; FILE_SNAPSHOT
 *  (attachment bytes) is an M3 milestone and deliberately has no wire value yet. */
@Serializable(with = ArtifactKindSerializer::class)
enum class ArtifactKind(internal val wire: String) {
    /** A merge/pull request the recipient opens with THEIR OWN credentials. */
    MERGE_REQUEST("merge_request"),

    /** A design/spec document URL, read with the recipient's own access. */
    DOCUMENT_URL("document_url"),

    /** A repo + base..head range, for work that has no MR yet. */
    COMMIT_RANGE("commit_range"),

    /** Decode fallback for a newer peer's value. FAIL CLOSED: a request carrying one cannot be
     *  prepared or executed — the recipient is told to update their daemon instead. */
    UNKNOWN("unknown"),
}

private object ArtifactKindSerializer : KSerializer<ArtifactKind> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ArtifactKind", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ArtifactKind) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): ArtifactKind {
        val s = decoder.decodeString()
        return ArtifactKind.entries.firstOrNull { it.wire == s } ?: ArtifactKind.UNKNOWN
    }
}

/**
 * One thing to review (§7.2). Which fields are REQUIRED depends on [kind] — the sender daemon validates
 * before persisting and refuses rather than truncating:
 *  - [ArtifactKind.MERGE_REQUEST] / [ArtifactKind.DOCUMENT_URL]: [url];
 *  - [ArtifactKind.COMMIT_RANGE]: [repo] + [base] + [head].
 *
 * [repo] is a normalized remote identity (e.g. `git.example.com/team/repo`), never a filesystem path:
 * the recipient matches it against their OWN checkout, and the sender's directory layout is not shared.
 */
@Serializable
data class ArtifactRef(
    val kind: ArtifactKind = ArtifactKind.UNKNOWN,
    /** Canonical http(s) URL. Required for MERGE_REQUEST/DOCUMENT_URL, optional colour elsewhere. */
    val url: String? = null,
    /** Normalized remote identity (host/owner/name) — never a local path. */
    val repo: String? = null,
    val base: String? = null,
    val head: String? = null,
    /** Display title the sender typed; never fetched by the daemon. */
    val title: String? = null,
)

/**
 * The structured brief (§7.3): navigation for the recipient, NOT a permission grant and NOT an
 * instruction channel into their daemon. Only [request] must be non-blank; everything else is optional
 * colour, and every list is bounded by the sending daemon before it is persisted.
 */
@Serializable
data class ReviewBrief(
    /** What the recipient is asked to do — the one field a usable brief must carry. */
    val request: String = "",
    val background: String? = null,
    val completedWork: List<String> = emptyList(),
    val focusAreas: List<String> = emptyList(),
    val knownRisks: List<String> = emptyList(),
    val verification: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val definitionOfDone: List<String> = emptyList(),
)

/** The overall call the reviewer makes (§7.4). Tolerant like every other enum here. */
@Serializable(with = ReviewVerdictSerializer::class)
enum class ReviewVerdict(internal val wire: String) {
    APPROVE("approve"),
    COMMENT("comment"),
    REQUEST_CHANGES("request_changes"),
    /** The reviewer could not do it (no access, wrong person, not enough context). */
    UNABLE_TO_REVIEW("unable_to_review"),
    /** Decode fallback for a newer peer's value — rendered as "unknown verdict", never as an approval. */
    UNKNOWN("unknown"),
}

private object ReviewVerdictSerializer : KSerializer<ReviewVerdict> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ReviewVerdict", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ReviewVerdict) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): ReviewVerdict {
        val s = decoder.decodeString()
        return ReviewVerdict.entries.firstOrNull { it.wire == s } ?: ReviewVerdict.UNKNOWN
    }
}

/**
 * One finding inside a [ReviewResult]. [severity] is a plain string (the [HandoffFinding] pattern): a
 * future level degrades to display text instead of failing the whole decode. Suggested values are
 * [HandoffFinding.SEVERITY_CRITICAL] … [HandoffFinding.SEVERITY_INFO].
 */
@Serializable
data class ReviewFinding(
    val title: String = "",
    val severity: String = HandoffFinding.SEVERITY_INFO,
    val detail: String? = null,
    /** Which [ArtifactRef] this is about, by 0-based index into [ReviewRequest.artifacts]. */
    val artifactIndex: Int? = null,
    val file: String? = null,
    val line: Int? = null,
)

/**
 * The structured outcome the recipient returns (§7.4). [respondedByDeviceId]/[respondedAt] are STAMPED
 * BY THE SENDER'S DAEMON from the authenticated transport — a client-declared identity is never
 * trusted — which is why they default rather than being required.
 */
@Serializable
data class ReviewResult(
    val verdict: ReviewVerdict = ReviewVerdict.UNKNOWN,
    val summary: String = "",
    val findings: List<ReviewFinding> = emptyList(),
    val verification: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val recommendedNextSteps: List<String> = emptyList(),
    /** Daemon-stamped: the recipient device that responded. "" until the daemon stamps it. */
    val respondedByDeviceId: String = "",
    /** Daemon-stamped: when the response landed (epoch ms). 0 until the daemon stamps it. */
    val respondedAt: Long = 0,
)

/**
 * One ReviewRequest (§7.1). The SENDER daemon owns the authoritative row; the recipient daemon keeps a
 * mirror keyed by (peer account, id) and never invents a transition the sender did not confirm.
 *
 * [revision] increases strictly on every REAL transition; a duplicate idempotency key returns the
 * already-applied row with the same revision. Clients replace wholesale by [id] whenever they see a
 * higher revision and ignore anything lower (a late replay must not regress a state).
 *
 * Deliberately ABSENT: source session id, workdir, transcript cursor, convo id, controller lease. A
 * task-context handoff shares the task, not the machine (§3.1).
 */
@Serializable
data class ReviewRequest(
    val id: String,
    /** The sender's own device identity, as stamped by the sender's daemon. */
    val senderDeviceId: String = "",
    val senderLabel: String? = null,
    /** The bound collaborator device in the SENDER's account — the only device that may act on this. */
    val recipientDeviceId: String = "",
    val recipientLabel: String? = null,
    val title: String = "",
    val brief: ReviewBrief = ReviewBrief(),
    val artifacts: List<ArtifactRef> = emptyList(),
    val status: ReviewStatus = ReviewStatus.UNKNOWN,
    /** Monotonic per-request version. Starts at 1; every real transition adds exactly 1. */
    val revision: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** When the sender would like an answer (display only — nothing expires on it). Epoch ms. */
    val dueAt: Long? = null,
    /** Hard cut-off: a non-terminal request past this settles EXPIRED. Epoch ms, null = never. */
    val expiresAt: Long? = null,
    val result: ReviewResult? = null,
    /** Why the recipient declined (their words). Only set alongside [ReviewStatus.DECLINED]. */
    val declineReason: String? = null,
)

// ===========================================================================
//  client/peer <-> daemon control frames (§10). Two planes on ONE frame family:
//   - OWNER plane (create/cancel/close): only a full-power owner credential of
//     the SENDER daemon may use these;
//   - RECIPIENT plane (delivered/acknowledge/start/decline/respond): only the
//     COLLABORATOR credential the request is addressed to.
//  `list`/`get` are shared and filtered by credential.
//  Identity ALWAYS comes from the authenticated transport, never from a payload
//  field (§10: "wire 身份来自 E2E transport").
// ===========================================================================

/** owner -> daemon: create + send a ReviewRequest to one bound contact. Reply: [ReviewRequestCreated]. */
@Serializable
@SerialName("pocket/review.create")
data class CreateReviewRequest(
    val recipientDeviceId: String,
    val title: String,
    val brief: ReviewBrief,
    val artifacts: List<ArtifactRef> = emptyList(),
    val dueAt: Long? = null,
    /** Hard expiry (epoch ms). Null asks for the daemon's default window. */
    val expiresAt: Long? = null,
) : ToDaemon

/** daemon -> owner: the reply to [CreateReviewRequest]. [code] carries the refusal machine-readably
 *  (`review_no_recipient`, `review_bad_artifact`, `review_too_large`, …). */
@Serializable
@SerialName("pocket/review.created")
data class ReviewRequestCreated(
    val ok: Boolean,
    val request: ReviewRequest? = null,
    val error: String? = null,
    val code: String? = null,
) : ToPhone

/** owner/recipient -> daemon: list the requests this caller may see (the daemon filters by credential:
 * an owner sees its outgoing rows, a collaborator only rows addressed to its own device). M1 returns
 * the bounded visible snapshot: request revisions are per-row, so [sinceRevision] cannot safely be a
 * global reconnect cursor and remains reserved for a future ledger cursor. Reply: [ReviewListing]. */
@Serializable
@SerialName("pocket/review.list")
data class ListReviewRequests(
    val status: ReviewStatus? = null,
    val sinceRevision: Long = 0,
) : ToDaemon

/** daemon -> caller: the reply to [ListReviewRequests], already credential-filtered. */
@Serializable
@SerialName("pocket/review.listing")
data class ReviewListing(val items: List<ReviewRequest> = emptyList()) : ToPhone

/** owner/recipient -> daemon: fetch one request by id. Reply: [ReviewUpdated] or a `review_*` error. */
@Serializable
@SerialName("pocket/review.get")
data class GetReviewRequest(val requestId: String) : ToDaemon

/**
 * recipient -> daemon: "I have this on disk" (§8: QUEUED → DELIVERED). Sent ONLY after the recipient
 * daemon's own atomic persist, so a relay write that never reached storage can never read as delivered.
 */
@Serializable
@SerialName("pocket/review.delivered")
data class MarkReviewDelivered(
    val requestId: String,
    /** Repeat-safe key: re-sending the same key returns the applied row without a second transition. */
    val idempotencyKey: String = "",
) : ToDaemon

/** recipient -> daemon: "I'll take this" (DELIVERED → ACKNOWLEDGED). */
@Serializable
@SerialName("pocket/review.acknowledge")
data class AcknowledgeReviewRequest(
    val requestId: String,
    val idempotencyKey: String = "",
) : ToDaemon

/** recipient -> daemon: "I'm reviewing now" (DELIVERED/ACKNOWLEDGED → IN_PROGRESS). */
@Serializable
@SerialName("pocket/review.start")
data class StartReviewRequest(
    val requestId: String,
    val idempotencyKey: String = "",
) : ToDaemon

/** recipient -> daemon: refuse the review (→ DECLINED). [reason] is the recipient's own words. */
@Serializable
@SerialName("pocket/review.decline")
data class DeclineReviewRequest(
    val requestId: String,
    val reason: String? = null,
    val idempotencyKey: String = "",
) : ToDaemon

/** recipient -> daemon: return the structured result (→ RESPONDED). May skip acknowledge/start for a
 *  light review. The daemon stamps [ReviewResult.respondedByDeviceId]/[ReviewResult.respondedAt]. */
@Serializable
@SerialName("pocket/review.respond")
data class RespondReviewRequest(
    val requestId: String,
    val result: ReviewResult,
    val idempotencyKey: String = "",
) : ToDaemon

/** owner -> daemon: withdraw a request the recipient has not started (QUEUED/DELIVERED/ACKNOWLEDGED). */
@Serializable
@SerialName("pocket/review.cancel")
data class CancelReviewRequest(val requestId: String) : ToDaemon

/** owner -> daemon: acknowledge a returned result (RESPONDED → CLOSED, the terminal happy path). */
@Serializable
@SerialName("pocket/review.close")
data class CloseReviewRequest(val requestId: String) : ToDaemon

/**
 * daemon -> allowed clients: the authoritative row after a change — pushed on every transition and
 * returned as the reply to every recipient mutation, so the recipient's mirror is only ever written
 * from sender truth. Clients key on [ReviewRequest.id] and replace wholesale when [ReviewRequest.revision]
 * is higher than what they hold.
 */
@Serializable
@SerialName("pocket/review.updated")
data class ReviewUpdated(val request: ReviewRequest) : ToPhone

// ===========================================================================
//  OWNER-LOCAL control plane (§6 + §12). Everything above is the two daemons'
//  conversation about ONE sender-owned ledger. The frames below are a different
//  question: "let the owner's own App/desktop drive the local-control surface
//  the CLI already drives" — contacts, THIS machine's received inbox, prepare,
//  and the recipient-side actions this daemon queues on the owner's behalf.
//
//  Why a separate family rather than widening the frames above: the recipient
//  plane authenticates as the BOUND COLLABORATOR of a remote sender. An owner
//  device is not that peer, and letting it reuse those frames would be exactly
//  the impersonation path §11.1 exists to prevent. So the owner asks its OWN
//  daemon to act as recipient, and the daemon answers honestly with `queued`.
//
//  ALL of these are OWNER-ONLY: a bridge, guest or collaborator credential is
//  refused before dispatch (they expose this machine's whole peer inbox).
//  Nothing here may carry a ticket, credential, private key or control token.
// ===========================================================================

/**
 * One review contact, in the terms the owner's UI renders (§9). MERGED VIEW of two different
 * credentials: an OUTBOUND row is a [Collaborator] in this account (I can send them reviews), an
 * INBOUND row is a peer link this daemon holds in THEIR account (they can send me theirs).
 *
 * [id] is whichever handle that direction takes — the collaborator deviceId, or the local `pl_…` link
 * id — so `(id, direction)` is the stable key every action takes. Public metadata ONLY: the fingerprint
 * is a display aid both ends compute identically, never key material.
 */
@Serializable
data class ReviewContact(
    val id: String,
    val label: String = "",
    val direction: CollaboratorDirection = CollaboratorDirection.UNKNOWN,
    val fingerprint: String? = null,
    val connectedAt: Long = 0,
    val removed: Boolean = false,
    /** Outbound only. Inbound peer links are review links by construction. */
    val purpose: CollaboratorPurpose = CollaboratorPurpose.REVIEW,
    /** True when this contact may be picked as a NEW request's recipient — the daemon's own answer, so
     *  a client never has to re-derive eligibility from purpose/direction/removed and get it wrong. */
    val canSend: Boolean = false,
)

/** owner -> daemon: list review contacts, both directions. Reply: [ReviewContactsListing]. */
@Serializable
@SerialName("pocket/review.contacts")
data object ListReviewContacts : ToDaemon

/** daemon -> owner: the reply to [ListReviewContacts]. */
@Serializable
@SerialName("pocket/review.contacts_listing")
data class ReviewContactsListing(val items: List<ReviewContact> = emptyList()) : ToPhone

/**
 * owner -> daemon: mint a one-time invite that establishes a REVIEW peer link (§4.1).
 *
 * Deliberately a separate frame from [CreateCollaboratorTicket] rather than a flag on it: the two mint
 * contacts with different purposes, and a defaulted flag on the old frame would make an old client's
 * Session Handoff invite indistinguishable from an unset review one. Reply: [ReviewInviteCreated].
 */
@Serializable
@SerialName("pocket/review.contact_invite")
data class CreateReviewInvite(val label: String? = null) : ToDaemon

/**
 * daemon -> owner: the reply to [CreateReviewInvite]. [invite] is the single-use connect URI — the one
 * piece of establishment material this family carries, returned once for the owner to show as QR/text
 * and never logged. Absent on failure, where [code] carries the refusal machine-readably.
 */
@Serializable
@SerialName("pocket/review.contact_invited")
data class ReviewInviteCreated(
    val ok: Boolean,
    val invite: String? = null,
    val ttlSec: Int = 0,
    val label: String? = null,
    val error: String? = null,
    val code: String? = null,
) : ToPhone

/**
 * owner -> daemon: redeem a peer's invite on THIS machine, so this daemon starts receiving their review
 * requests. The scanning client never redeems it itself — the resulting credential belongs to the
 * always-on daemon, which is what keeps delivery working with every UI closed. Reply: [ReviewContactUpdated].
 */
@Serializable
@SerialName("pocket/review.contact_join")
data class JoinReviewContact(val invite: String, val label: String? = null) : ToDaemon

/** owner -> daemon: sever one contact. [direction] disambiguates the two id spaces rather than making
 *  the daemon guess which ledger `id` belongs to. Reply: [ReviewContactUpdated]. */
@Serializable
@SerialName("pocket/review.contact_remove")
data class RemoveReviewContact(
    val id: String,
    val direction: CollaboratorDirection = CollaboratorDirection.UNKNOWN,
) : ToDaemon

/** daemon -> owner: the reply to [JoinReviewContact]/[RemoveReviewContact]. */
@Serializable
@SerialName("pocket/review.contact_updated")
data class ReviewContactUpdated(
    val ok: Boolean,
    val contact: ReviewContact? = null,
    val error: String? = null,
    val code: String? = null,
) : ToPhone

/**
 * One row of THIS machine's received inbox: the sender's authoritative [request], plus the local truth
 * around it. [pending] names the actions this daemon has queued but the sender has not confirmed — the
 * honesty that keeps "queued" from being rendered as "they saw it" (§8).
 */
@Serializable
data class ReviewInboxItem(
    val linkId: String,
    val peerLabel: String = "",
    /** The fingerprint the two humans compared when the link was established. Display only. */
    val peerFingerprint: String = "",
    val request: ReviewRequest,
    val pending: List<String> = emptyList(),
)

/** owner -> daemon: list the review requests THIS machine received. Reply: [ReviewInboxListing]. */
@Serializable
@SerialName("pocket/review.inbox")
data class ListReviewInbox(val status: ReviewStatus? = null) : ToDaemon

/** daemon -> owner: the reply to [ListReviewInbox] — the bounded complete snapshot a reconnecting
 *  client heals from, newest first. */
@Serializable
@SerialName("pocket/review.inbox_listing")
data class ReviewInboxListing(val items: List<ReviewInboxItem> = emptyList()) : ToPhone

/** owner -> daemon: build the safe execution bundle for one received request. Reply: [ReviewPrepared]. */
@Serializable
@SerialName("pocket/review.prepare")
data class PrepareReviewRequest(val requestId: String) : ToDaemon

/** daemon -> owner: the reply to [PrepareReviewRequest]. */
@Serializable
@SerialName("pocket/review.prepared")
data class ReviewPrepared(
    val ok: Boolean,
    val bundle: ReviewExecutionBundle? = null,
    val error: String? = null,
    val code: String? = null,
) : ToPhone

/** Which recipient-side action the owner is asking its own daemon to queue. Tolerant like every other
 *  enum here; [UNKNOWN] fails closed (no action is taken for a verb this build cannot read). */
@Serializable(with = ReviewInboxActionSerializer::class)
enum class ReviewInboxAction(internal val wire: String) {
    ACKNOWLEDGE("acknowledge"),
    START("start"),
    DECLINE("decline"),
    RESPOND("respond"),
    UNKNOWN("unknown"),
}

private object ReviewInboxActionSerializer : KSerializer<ReviewInboxAction> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ReviewInboxAction", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ReviewInboxAction) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): ReviewInboxAction {
        val s = decoder.decodeString()
        return ReviewInboxAction.entries.firstOrNull { it.wire == s } ?: ReviewInboxAction.UNKNOWN
    }
}

/**
 * owner -> daemon: queue one recipient-side action on THIS machine's inbox. The daemon owns the retry:
 * the answer says whether it was recorded, never whether the colleague has seen it. Reply: [ReviewInboxActed].
 */
@Serializable
@SerialName("pocket/review.inbox_act")
data class ActOnReviewInbox(
    val requestId: String,
    val action: ReviewInboxAction = ReviewInboxAction.UNKNOWN,
    /** [ReviewInboxAction.DECLINE] only. */
    val reason: String? = null,
    /** [ReviewInboxAction.RESPOND] only. */
    val result: ReviewResult? = null,
) : ToDaemon

/**
 * daemon -> owner: the reply to [ActOnReviewInbox]. [queued] false with [ok] true means the request was
 * ALREADY in that state and nothing needed sending — not a failure, and not a delivery either.
 */
@Serializable
@SerialName("pocket/review.inbox_acted")
data class ReviewInboxActed(
    val ok: Boolean,
    val requestId: String = "",
    val queued: Boolean = false,
    val status: ReviewStatus = ReviewStatus.UNKNOWN,
    val error: String? = null,
    val code: String? = null,
) : ToPhone

/** Who sent this, in the only terms a reviewer needs: a label and the fingerprint they verified when
 *  the link was established. NEVER the credential, ticket or key material behind the link. */
@Serializable
data class PreparePeer(
    val linkId: String,
    val label: String,
    val fingerprint: String,
)

/**
 * The deterministic execution bundle `review prepare` returns (§4.3): everything the recipient's own
 * agent needs to start reviewing, and nothing else.
 *
 * What is deliberately ABSENT is the point: no sender session id, no sender workdir, no transcript, no
 * control channel back into the sender's machine. A task-context handoff hands over a task.
 *
 * [peerContentIsUntrusted] is always true and is stated explicitly rather than left implicit: everything
 * under [brief] and [artifacts] is text a colleague typed (or that a document/MR contains), so it is
 * DATA for the agent to consider, never instructions for it to follow (§11.2).
 */
@Serializable
data class ReviewExecutionBundle(
    val requestId: String,
    val peer: PreparePeer,
    val title: String,
    val status: ReviewStatus,
    val revision: Long,
    val brief: ReviewBrief,
    val artifacts: List<ArtifactRef>,
    val dueAt: Long? = null,
    val expiresAt: Long? = null,
    val peerContentIsUntrusted: Boolean = true,
    /** A concise prompt the Skill can hand to the CURRENT agent session, so the reviewer keeps their
     *  own context instead of starting cold. */
    val recommendedPrompt: String,
    /** Things the reviewer should know before starting (e.g. "you have not acknowledged this yet"). */
    val notes: List<String> = emptyList(),
)

/** Default expiry window for a fresh request (7 days) — daemon-clamped, mirrored by client forms. */
const val DEFAULT_REVIEW_EXPIRES_SEC: Long = 7 * 24 * 3600
