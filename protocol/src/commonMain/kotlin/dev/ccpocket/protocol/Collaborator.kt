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
//  Collaborator Link (docs/design/SESSION-HANDOFF.md §4.1): a long-lived,
//  E2E-trusted CONTACT binding established by a one-time QR/deeplink scan.
//  It proves identity and provides a delivery address for Handoff offers —
//  and grants ZERO session access by itself. Per-handoff access is a separate
//  temporary Grant (the Handoff's recipient binding + its credential caps).
//
//  Same wire red lines as Handoff.kt: tolerant enums (UNKNOWN = safest
//  reading), trailing defaults, and all pocket/collaborator.* frames are new
//  types an old peer silently drops (clients arm a reply deadline).
// ===========================================================================

/**
 * The link's direction FROM THE OWNER'S PERSPECTIVE (§4.1): permissions stay directional even when
 * the UI renders a mutual pair as one contact. OUTBOUND = "I can send them handoffs" (A→B seen by A).
 */
@Serializable(with = CollaboratorDirectionSerializer::class)
enum class CollaboratorDirection(internal val wire: String) {
    /** They can receive MY handoffs (I am the initiator side of this link). */
    OUTBOUND("outbound"),

    /** I can receive THEIR handoffs (they initiate; my daemon is not involved). */
    INBOUND("inbound"),

    /** Both directions established — rendered as one bidirectional contact ("Both ways"). */
    MUTUAL("mutual"),

    /** Decode fallback for a newer peer's value. Treat as most restricted (no send affordance). */
    UNKNOWN("unknown"),
}

private object CollaboratorDirectionSerializer : KSerializer<CollaboratorDirection> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CollaboratorDirection", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: CollaboratorDirection) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): CollaboratorDirection {
        val s = decoder.decodeString()
        return CollaboratorDirection.entries.firstOrNull { it.wire == s } ?: CollaboratorDirection.UNKNOWN
    }
}

/**
 * What a contact link was established FOR (REVIEW-REQUEST.md §13.3). The two collaboration features
 * share the Collaborator Link transport but not their recipients: Session Handoff hands over a live
 * RUNTIME context to a person's App, ReviewRequest hands a TASK to a colleague's DAEMON. Offering one's
 * contacts as the other's recipients is how a review peer ends up holding a session drive lease.
 *
 * The default is [SESSION_HANDOFF] on purpose: a contact minted before this field existed carries no
 * `purpose` in its JSON, and its historical meaning is exactly Session Handoff — decoding it as
 * anything else would silently re-scope a link the owner already established.
 */
@Serializable(with = CollaboratorPurposeSerializer::class)
enum class CollaboratorPurpose(internal val wire: String) {
    /** The historical meaning, and the default for every pre-existing row: a Session Handoff recipient. */
    SESSION_HANDOFF("session_handoff"),

    /** A ReviewRequest daemon peer (REVIEW-REQUEST.md §9). NEVER a Session Handoff runtime recipient:
     *  it is somebody's daemon, and a task-context contact must not become a drive credential. */
    REVIEW("review"),

    /** Decode fallback for a newer peer's value. FAIL CLOSED — eligible for NEITHER feature, because a
     *  purpose this build cannot read is a purpose it cannot honour. */
    UNKNOWN("unknown"),
}

private object CollaboratorPurposeSerializer : KSerializer<CollaboratorPurpose> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CollaboratorPurpose", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: CollaboratorPurpose) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): CollaboratorPurpose {
        val s = decoder.decodeString()
        return CollaboratorPurpose.entries.firstOrNull { it.wire == s } ?: CollaboratorPurpose.UNKNOWN
    }
}

/**
 * One collaborator contact as the daemon knows it. [deviceId] is the collaborator's credential id in
 * the owner's account space — the handle Handoff recipient-binding, revoke and push routing all key on.
 * [removed] contacts stay listed (terminal group): past handoffs still reference them by label.
 * "Connected" means THE TRUST BINDING IS VALID — the wire deliberately carries no presence field.
 */
@Serializable
data class Collaborator(
    val deviceId: String,
    val label: String = "",
    val direction: CollaboratorDirection = CollaboratorDirection.UNKNOWN,
    val connectedAt: Long = 0,
    /** Deterministic word-group fingerprint of the PEER identity key (see [collaboratorFingerprint]). */
    val fingerprint: String? = null,
    val handoffCount: Int = 0,
    val lastHandoffAt: Long? = null,
    /** Whether the contact reported a daemon of their own (null = unknown) — gates "complete the reverse link". */
    val hasDaemon: Boolean? = null,
    val removed: Boolean = false,
    /** What this link is for. Trailing + defaulted so a pre-purpose contact keeps its historical
     *  Session Handoff meaning rather than being re-scoped by an upgrade. */
    val purpose: CollaboratorPurpose = CollaboratorPurpose.SESSION_HANDOFF,
)

/**
 * May a Session Handoff be bound to this contact? A [CollaboratorPurpose.REVIEW] peer may not: it is a
 * colleague's daemon holding a task-context link, and a runtime handoff would hand it a drive lease it
 * was never established for. An [CollaboratorPurpose.UNKNOWN] purpose fails closed the same way.
 */
val Collaborator.acceptsSessionHandoff: Boolean
    get() = !removed && purpose == CollaboratorPurpose.SESSION_HANDOFF

/**
 * May a ReviewRequest be sent to this contact? ONLY a [CollaboratorPurpose.REVIEW] link, and the
 * strictness is the point: the two features are separated by what the owner chose at MINT time, in both
 * directions. A [CollaboratorPurpose.SESSION_HANDOFF] contact is a person's App, established to receive
 * a runtime lease — silently widening it into a task-context peer would re-scope a link the owner
 * already made, which is exactly what the default in [Collaborator.purpose] exists to prevent for
 * pre-purpose rows. [CollaboratorPurpose.UNKNOWN] fails closed the same way.
 *
 * The migration story is deliberately explicit rather than automatic: to use ReviewRequest with someone,
 * make a Review link with them.
 */
val Collaborator.acceptsReviewRequest: Boolean
    get() = !removed && purpose == CollaboratorPurpose.REVIEW

/**
 * The one-time connect ticket the initiator's App renders as QR/link (§4.1 step 2). Carries ONLY
 * establishment material — no session, folder or handoff content. Mirrors [ShareInvite]'s transport
 * fields so the recipient App reuses the same relay redeem path with a COLLABORATOR-kind credential.
 */
@Serializable
data class CollaboratorInvite(
    val relay: String,
    val accountId: String,
    val daemonPub: String,
    val ticket: String,
    /** Initiator's display label ("Panda · MacBook Pro") for the recipient's confirm screen. */
    val ownerLabel: String? = null,
    /** How long the REDEEM ticket is valid (short). The LINK itself has no expiry. */
    val ttlSec: Int = 600,
    /**
     * What this invite establishes (REVIEW-REQUEST.md §13.3), so the REDEEMER can tell the two features
     * apart before burning a single-use ticket.
     *
     * Without it the two are byte-identical, and the mint frame being new does not help: the artifact
     * that crosses machines is this one. A phone scanning a Review QR from its ordinary scanner would
     * redeem it as a Session Handoff contact — consuming the ticket the colleague's DAEMON was supposed
     * to redeem, and leaving the owner with a "review contact" that can never answer a review.
     *
     * Trailing + defaulted to [CollaboratorPurpose.SESSION_HANDOFF]: an invite minted before this field
     * existed carries no key, and its historical meaning is exactly that.
     */
    val purpose: CollaboratorPurpose = CollaboratorPurpose.SESSION_HANDOFF,
)

// ---------------------------------------------------------------------------
//  The deep-link DOOR an invite travels through (REVIEW-REQUEST.md §13.3).
//
//  The URI prefix is the OUTER half of a two-layer split; [CollaboratorInvite.purpose]
//  is the inner one. Both are needed, for different peers:
//
//   - the purpose alone does not protect an OLD app. It decodes the trailing
//     field as its default and happily redeems a Review ticket at the ordinary
//     Session Handoff door — burning the single-use ticket the colleague's
//     DAEMON was supposed to redeem;
//   - the host alone does not protect against a stripped prefix (a bare blob
//     pasted by hand), which is why every door re-checks the purpose after
//     decoding.
//
//  The strings live HERE, in the one module both the daemon and the app already
//  depend on, because the codec itself is deliberately PORTED rather than shared
//  (the daemon must not depend on mobile code). Two ported copies may each keep
//  their own base64/validation; they must never disagree about which door a
//  ticket was addressed to.
// ---------------------------------------------------------------------------

/** The Session Handoff door. FROZEN: released apps parse exactly this, and a QR/link already
 *  printed or pasted has to keep working. */
const val COLLAB_INVITE_URI_PREFIX = "ccpocket://collab#"

/** The ReviewRequest contact door. Deliberately a host an older build does not recognise at all,
 *  so it falls through as "not a link I understand" instead of redeeming the ticket. */
const val REVIEW_CONTACT_INVITE_URI_PREFIX = "ccpocket://review-contact#"

/**
 * Which door an invite for [purpose] must be published under. [CollaboratorPurpose.REVIEW] is the only
 * value that leaves the legacy door, so a purpose this build cannot read ([CollaboratorPurpose.UNKNOWN])
 * never gets published as a Review invite — the fail-closed half lives in the DECODERS, which require an
 * exact purpose match per door and therefore refuse UNKNOWN at both.
 */
fun inviteUriPrefix(purpose: CollaboratorPurpose): String =
    if (purpose == CollaboratorPurpose.REVIEW) REVIEW_CONTACT_INVITE_URI_PREFIX else COLLAB_INVITE_URI_PREFIX

// ---------------------------------------------------------------------------
//  client <-> daemon control frames. Owner side manages contacts; the
//  recipient side redeems the invite out-of-band (relay redeem, not a frame).
// ---------------------------------------------------------------------------

/** owner -> daemon: mint a one-time collaborator connect ticket. Reply: [CollaboratorTicketCreated]. */
@Serializable
@SerialName("pocket/collaborator.ticket")
data class CreateCollaboratorTicket(
    /** Optional display nickname to pre-assign the contact on redeem. */
    val label: String? = null,
) : ToDaemon

/** daemon -> owner: the reply to [CreateCollaboratorTicket]. */
@Serializable
@SerialName("pocket/collaborator.ticket_created")
data class CollaboratorTicketCreated(
    val ok: Boolean,
    val invite: CollaboratorInvite? = null,
    val error: String? = null,
) : ToPhone

/** owner -> daemon: list my collaborator contacts (removed ones included, flagged). Reply: [CollaboratorListing]. */
@Serializable
@SerialName("pocket/collaborator.list")
data object ListCollaborators : ToDaemon

/** daemon -> caller: the reply to [ListCollaborators]. */
@Serializable
@SerialName("pocket/collaborator.listing")
data class CollaboratorListing(val items: List<Collaborator> = emptyList()) : ToPhone

/** owner -> daemon: sever a link (kills the credential; the contact row turns terminal, not deleted). */
@Serializable
@SerialName("pocket/collaborator.remove")
data class RemoveCollaborator(val deviceId: String) : ToDaemon

/** daemon -> attached owner clients: a contact changed (redeemed, relabelled, removed) — replace by [Collaborator.deviceId]. */
@Serializable
@SerialName("pocket/collaborator.updated")
data class CollaboratorUpdated(val collaborator: Collaborator) : ToPhone

/**
 * daemon -> owner clients: someone redeemed the pending connect ticket — the "waiting for scan…"
 * screen flips to its Connected sub-state and returns to the interrupted handoff draft.
 */
@Serializable
@SerialName("pocket/collaborator.connected")
data class CollaboratorConnected(val collaborator: Collaborator) : ToPhone

// ---------------------------------------------------------------------------
//  Safety fingerprint (§4.1 step 3): deterministic word groups derived from the
//  peer's identity public key, computed THE SAME WAY on both ends so the two
//  people can compare them out loud. Display-only — never an authorization input.
// ---------------------------------------------------------------------------

private val FP_WORDS = listOf(
    "tiger", "brick", "mango", "void", "ember", "delta", "canvas", "orbit",
    "pixel", "cedar", "lunar", "quartz", "raven", "sable", "tempo", "umber",
    "vivid", "willow", "xenon", "yarrow", "zephyr", "amber", "basil", "coral",
    "dune", "echo", "fjord", "grove", "haven", "iris", "jade", "krill",
)

/**
 * "tiger-brick-mango-void · ember-delta-canvas-orbit" from a base64url public key. 8 words from a
 * 32-word list ≈ 40 bits of display entropy — a HUMAN VERIFICATION AID against a wrong-QR mixup,
 * not the cryptographic trust root (that stays the key exchange itself).
 */
fun collaboratorFingerprint(pubB64: String): String {
    // FNV-1a over the key bytes' text form — stable across platforms, no crypto dependency here
    var h = 0xcbf29ce484222325UL
    for (c in pubB64) {
        h = h xor c.code.toULong()
        h *= 0x100000001b3UL
    }
    val words = (0 until 8).map { i ->
        val idx = ((h shr (i * 5)) and 31UL).toInt()
        FP_WORDS[idx]
    }
    return words.take(4).joinToString("-") + " · " + words.drop(4).joinToString("-")
}
