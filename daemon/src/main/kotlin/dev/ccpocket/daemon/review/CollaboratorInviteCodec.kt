package dev.ccpocket.daemon.review

import dev.ccpocket.daemon.peer.TextLimits
import dev.ccpocket.daemon.peer.b64
import dev.ccpocket.daemon.peer.b64d
import dev.ccpocket.daemon.peer.validDaemonPub
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.PocketJson
import java.net.URI

// ---------------------------------------------------------------------------
//  Collaborator connect-ticket codec — the daemon-side twin of the app's
//  CollaboratorInvites.kt. Ported (not shared) on purpose: the daemon must not
//  depend on mobile code, and this is the whole of it.
// ---------------------------------------------------------------------------

/** The Session Handoff door — frozen, and not a door this daemon ever redeems at (a phone does). */
const val COLLAB_URI_PREFIX = dev.ccpocket.protocol.COLLAB_INVITE_URI_PREFIX

/** The Review contact door (REVIEW-REQUEST.md §13.3). Its own host so an older APP, which reads the
 *  trailing `purpose` as its default, cannot recognise — and therefore cannot burn — a Review ticket at
 *  its ordinary collaborator scanner. */
const val REVIEW_CONTACT_URI_PREFIX = dev.ccpocket.protocol.REVIEW_CONTACT_INVITE_URI_PREFIX

/** Publish under the door this invite's purpose names, so the artifact that crosses machines says what
 *  it is before anyone decodes it. */
fun CollaboratorInvite.encodeUri(): String =
    dev.ccpocket.protocol.inviteUriPrefix(purpose) +
        b64(PocketJson.encodeToString(CollaboratorInvite.serializer(), this).encodeToByteArray())

/**
 * Tolerant decode of the SESSION HANDOFF door: full URI, `ccpocket://collab` with any fragment, or a
 * bare base64url blob. Null when it is not a usable invite — every establishment field must be present
 * before we redeem anything — and null for a REVIEW ticket, which belongs to the other door.
 *
 * NO PRODUCTION CALLER on this side, deliberately: a daemon never redeems a Session Handoff invite (a
 * person's App does). It is kept because it is the OTHER HALF of a security-relevant pair, and the pair
 * is what the cross-door tests assert against — "a handoff ticket is refused here AND accepted at its
 * own door" is a claim about isolation; "refused here" alone would also hold if the codec had simply
 * stopped decoding anything.
 */
fun decodeCollaboratorInvite(raw: String): CollaboratorInvite? =
    decodeInviteAtDoor(raw, COLLAB_URI_PREFIX, "ccpocket://collab", CollaboratorPurpose.SESSION_HANDOFF)

/** Tolerant decode of the REVIEW CONTACT door, same rules. This is the ONLY decode `review join` runs:
 *  the credential it produces belongs to this always-on daemon, and a Session Handoff ticket redeemed
 *  into it would be a runtime-lease contact its owner never agreed to make. */
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
        // a `ccpocket://` URI naming some OTHER host is addressed elsewhere: refuse rather than re-read
        // its fragment as though it had been pasted at this door.
        //
        // ignoreCase ONLY here, matching the app's twin: the app routes on a case-INSENSITIVE scheme, so
        // a guard that knew only the lowercase spelling would not cover every string reaching this door.
        // The two accept branches above stay case-SENSITIVE — `ccpocket://collab#` is frozen, and neither
        // port may start accepting spellings the released build rejects.
        t.startsWith("ccpocket://", ignoreCase = true) -> return null
        else -> t // bare blob (a hand-pasted line): the purpose check below keeps the doors apart
    }.trim()
    if (blob.isEmpty()) return null
    return runCatching {
        PocketJson.decodeFromString(CollaboratorInvite.serializer(), b64d(blob).decodeToString())
    }.getOrNull()?.takeIf {
        // exact match, so UNKNOWN (a purpose only a newer peer knows) fails closed at BOTH doors
        it.purpose == want &&
            validRelay(it.relay) && it.accountId.isNotBlank() && it.accountId.length <= 256 &&
            it.ticket.isNotBlank() && it.ticket.length <= 4_096 &&
            validDaemonPub(it.daemonPub) &&
            TextLimits.singleLine(it.ownerLabel, TextLimits.MAX_LABEL, "owner label") == null
    }?.let { it.copy(relay = it.relay.trimEnd('/')) }
}

/** Establishment input is user-supplied. Production links require TLS; plaintext is accepted only for
 * explicit loopback development, never for an arbitrary LAN/Internet host. */
private fun validRelay(raw: String): Boolean = runCatching {
    if (raw.length > 2_048) return@runCatching false
    val uri = URI(raw)
    val host = uri.host?.lowercase() ?: return@runCatching false
    val scheme = uri.scheme?.lowercase()
    val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
    (scheme == "wss" || (scheme == "ws" && loopback)) && uri.userInfo == null &&
        uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")
}.getOrDefault(false)
