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
)

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
)

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
