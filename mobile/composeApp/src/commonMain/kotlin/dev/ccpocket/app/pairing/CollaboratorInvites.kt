package dev.ccpocket.app.pairing

import dev.ccpocket.app.util.B64Url
import dev.ccpocket.protocol.COLLAB_INVITE_URI_PREFIX
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.REVIEW_CONTACT_INVITE_URI_PREFIX
import dev.ccpocket.protocol.e2e.E2ECrypto
import dev.ccpocket.protocol.inviteUriPrefix

/**
 * Collaborator-link connect codec (SESSION-HANDOFF.md §4.1) — the [ShareInvite] codec's sibling.
 * The ticket blob carries ONLY establishment material (identity + delivery address), never any
 * session, folder or handoff content; the recipient decodes it, confirms the safety fingerprint,
 * then redeems the ticket through the ordinary pairing path with a COLLABORATOR-kind credential.
 *
 * TWO DOORS, one codec (REVIEW-REQUEST.md §13.3). A Session Handoff invite is addressed to a person's
 * APP; a Review contact invite is addressed to a colleague's DAEMON. They are byte-identical apart from
 * [CollaboratorInvite.purpose], and the ticket inside either is single-use — so redeeming one at the
 * other's door does not merely go to the wrong screen, it BURNS the ticket the other side was waiting
 * for. Hence [decodeCollaboratorInvite] and [decodeReviewContactInvite]: each accepts only its own URI
 * host, and each re-checks the embedded purpose so a hand-stripped bare blob cannot cross either.
 */
const val COLLAB_URI_PREFIX = COLLAB_INVITE_URI_PREFIX

/** The Review contact door. An older app does not know this host and treats it as an unknown link —
 *  which is the point: it cannot redeem what it cannot recognise. */
const val REVIEW_CONTACT_URI_PREFIX = REVIEW_CONTACT_INVITE_URI_PREFIX

/** Publish under the door this invite's [CollaboratorInvite.purpose] names — never under a fixed one. */
fun CollaboratorInvite.encode(): String =
    inviteUriPrefix(purpose) +
        B64Url.encode(PocketJson.encodeToString(CollaboratorInvite.serializer(), this).encodeToByteArray())

/**
 * Tolerant decode of the SESSION HANDOFF door: full URI, `ccpocket://collab` with any fragment, or a
 * bare base64url blob. A [CollaboratorPurpose.REVIEW] ticket is refused here even when it arrives as a
 * bare blob — this is the door that redeems into a phone binding, and a review peer can never answer
 * from one.
 */
fun decodeCollaboratorInvite(raw: String): CollaboratorInvite? =
    decodeInviteAtDoor(raw, COLLAB_URI_PREFIX, "ccpocket://collab", CollaboratorPurpose.SESSION_HANDOFF)

/**
 * Tolerant decode of the REVIEW CONTACT door, same shape. Requires [CollaboratorPurpose.REVIEW]: a
 * Session Handoff ticket redeemed here would hand a colleague's daemon a contact its owner minted for a
 * runtime lease, which is the same re-scoping in the other direction.
 */
fun decodeReviewContactInvite(raw: String): CollaboratorInvite? =
    decodeInviteAtDoor(raw, REVIEW_CONTACT_URI_PREFIX, "ccpocket://review-contact", CollaboratorPurpose.REVIEW)

private fun decodeInviteAtDoor(
    raw: String,
    prefix: String,
    host: String,
    want: CollaboratorPurpose,
): CollaboratorInvite? {
    val t = raw.trim()
    val blob = when {
        t.startsWith(prefix) -> t.removePrefix(prefix)
        t.startsWith(host) -> t.substringAfter('#', "")
        // a `ccpocket://` URI that names some OTHER host is addressed elsewhere — never re-read its
        // fragment as if it had been pasted here, which is exactly the cross-door redeem this splits.
        //
        // ignoreCase ONLY here: [parseIncomingLink] routes on a case-INSENSITIVE scheme, so a guard that
        // only knew the lowercase spelling would not cover every string that reaches this door — it would
        // let `CCPOCKET://collab#…` fall through to the bare-blob branch and lean entirely on `purpose`.
        // The two accept branches above stay case-SENSITIVE: `ccpocket://collab#` is frozen, and this
        // door must not start accepting spellings the released build rejects.
        t.startsWith("ccpocket://", ignoreCase = true) -> return null
        else -> t // bare blob (explicit paste): the purpose check below is what keeps the doors apart
    }.trim()
    if (blob.isEmpty()) return null
    return runCatching {
        PocketJson.decodeFromString(CollaboratorInvite.serializer(), B64Url.decode(blob).decodeToString())
    }.getOrNull()?.takeIf {
        // exact match, so UNKNOWN (a purpose a newer peer minted) fails closed at BOTH doors
        it.purpose == want &&
            it.relay.isNotBlank() && it.accountId.isNotBlank() && it.ticket.isNotBlank() &&
            validDaemonPub(it.daemonPub)
    }
}

/**
 * Is the invite's `daemonPub` a real P-256 key this app could pin and derive a session from?
 *
 * The same check the daemon runs before it redeems anything, and it belongs here too: this app shows a
 * FINGERPRINT of these bytes and asks a human to trust it. [dev.ccpocket.protocol.collaboratorFingerprint]
 * will happily hash 32 bytes, or noise, into a convincing word group — so a blob that can never complete
 * a handshake would be presented as an identity to verify, and the human would verify it.
 */
private fun validDaemonPub(daemonPubB64: String): Boolean {
    if (daemonPubB64.length > MAX_DAEMON_PUB_B64) return false
    val raw = runCatching { B64Url.decode(daemonPubB64) }.getOrNull() ?: return false
    return E2ECrypto.isValidPublicKey(raw)
}

/** 65 raw bytes is 88 base64url characters; the cap only keeps a pathological blob out of the decoder. */
private const val MAX_DAEMON_PUB_B64 = 128

/** The subset the recipient needs to redeem — same shape as a scanned pairing QR. (Named apart from
 *  the ShareInvite sibling so files importing both stay unambiguous.) */
fun CollaboratorInvite.toCollabPairingInfo(): PairingInfo = PairingInfo(relay, accountId, daemonPub, ticket)
