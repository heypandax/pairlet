package dev.ccpocket.app.pairing

import dev.ccpocket.app.util.B64Url
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.PocketJson

/**
 * Collaborator-link connect codec (SESSION-HANDOFF.md §4.1) — the [ShareInvite] codec's sibling.
 * The ticket blob carries ONLY establishment material (identity + delivery address), never any
 * session, folder or handoff content; the recipient decodes it, confirms the safety fingerprint,
 * then redeems the ticket through the ordinary pairing path with a COLLABORATOR-kind credential.
 */
const val COLLAB_URI_PREFIX = "ccpocket://collab#"

fun CollaboratorInvite.encode(): String =
    COLLAB_URI_PREFIX + B64Url.encode(PocketJson.encodeToString(CollaboratorInvite.serializer(), this).encodeToByteArray())

/** Tolerant decode: full URI, `ccpocket://collab` with any fragment, or a bare base64url blob. */
fun decodeCollaboratorInvite(raw: String): CollaboratorInvite? {
    val t = raw.trim()
    val blob = when {
        t.startsWith(COLLAB_URI_PREFIX) -> t.removePrefix(COLLAB_URI_PREFIX)
        t.startsWith("ccpocket://collab") -> t.substringAfter('#', "")
        else -> t
    }.trim()
    if (blob.isEmpty()) return null
    return runCatching {
        PocketJson.decodeFromString(CollaboratorInvite.serializer(), B64Url.decode(blob).decodeToString())
    }.getOrNull()?.takeIf {
        it.relay.isNotBlank() && it.accountId.isNotBlank() && it.daemonPub.isNotBlank() && it.ticket.isNotBlank()
    }
}

/** The subset the recipient needs to redeem — same shape as a scanned pairing QR. (Named apart from
 *  the ShareInvite sibling so files importing both stay unambiguous.) */
fun CollaboratorInvite.toCollabPairingInfo(): PairingInfo = PairingInfo(relay, accountId, daemonPub, ticket)
