package dev.ccpocket.app.pairing

import dev.ccpocket.app.util.B64Url
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ShareInvite

/**
 * Folder-share invite codec (issue #115). The owner's [ShareInvite] is turned into ONE self-contained
 * string the owner hands the guest out of band — rendered as a QR and a copyable code. The guest scans
 * or pastes it, we decode it back, show the accept-preview, then redeem [ShareInvite.ticket] through the
 * ordinary pairing path ([toPairingInfo] + [Pairing.redeem]). The relay never sees the blob, so the
 * shared path (already stripped to a basename in the invite) never leaks server-side.
 *
 * Wire form: `ccpocket://share#<base64url(json)>`. The fragment (`#`) keeps the blob out of any query
 * string a share sheet or log might capture; a bare base64url blob (no scheme) also decodes, so a pasted
 * code without the prefix still works.
 */
const val SHARE_URI_PREFIX = "ccpocket://share#"

/** Encode an invite into the scannable / pasteable string. */
fun ShareInvite.encode(): String =
    SHARE_URI_PREFIX + B64Url.encode(PocketJson.encodeToString(ShareInvite.serializer(), this).encodeToByteArray())

/**
 * Decode a scanned/pasted string back into a [ShareInvite]. Tolerant: accepts the full `ccpocket://share#…`
 * URI or a bare base64url blob, trims whitespace, and returns null on anything that isn't a well-formed
 * invite (so the redeem screen can shake + show "expired/invalid" instead of crashing).
 */
fun decodeShareInvite(raw: String): ShareInvite? {
    val t = raw.trim()
    val blob = when {
        t.startsWith(SHARE_URI_PREFIX) -> t.removePrefix(SHARE_URI_PREFIX)
        t.startsWith("ccpocket://share") -> t.substringAfter('#', "")
        else -> t
    }.trim()
    if (blob.isEmpty()) return null
    return runCatching {
        PocketJson.decodeFromString(ShareInvite.serializer(), B64Url.decode(blob).decodeToString())
    }.getOrNull()?.takeIf {
        it.relay.isNotBlank() && it.accountId.isNotBlank() && it.daemonPub.isNotBlank() && it.ticket.isNotBlank()
    }
}

/** The subset the guest needs to redeem — same shape as a scanned pairing QR. */
fun ShareInvite.toPairingInfo(): PairingInfo = PairingInfo(relay, accountId, daemonPub, ticket)
