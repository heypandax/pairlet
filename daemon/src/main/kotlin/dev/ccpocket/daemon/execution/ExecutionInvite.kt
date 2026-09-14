package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.review.b64
import dev.ccpocket.daemon.review.b64d
import dev.ccpocket.daemon.review.validDaemonPub
import dev.ccpocket.protocol.EXECUTION_GRANT_INVITE_URI_PREFIX
import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * The one-time artifact the target owner hands to the source (#367 G0). Deliberately NOT a
 * [dev.ccpocket.protocol.CollaboratorInvite] with a new purpose value:
 *  - that type's `purpose` defaults to SESSION_HANDOFF when absent, and its legacy door
 *    (`ccpocket://collab#`) is scanned by released Apps — a purpose they cannot read is still a ticket they
 *    can burn;
 *  - the field names here ([targetDaemonPub], [connectTicket], …) differ from CollaboratorInvite's required
 *    `accountId`/`daemonPub`/`ticket`, so a bare blob pasted into either collaborator door fails to decode
 *    instead of redeeming under the wrong purpose, and vice versa ([decodeExecutionInvite] requires [kind]).
 *
 * [inviteSecret] (review HIGH-1) is 32 random bytes the relay never sees. It is mixed into the first-contact
 * PSK ([ExecutionPsk]); the relay redeems only [connectTicket]. The whole invite therefore has to travel
 * out-of-band (QR / copy-paste between the two humans), never through the relay.
 *
 * The credential class the redeemed key gets is still decided on the TARGET by what the owner minted —
 * nothing in this blob can name its own authority.
 */
@Serializable
data class ExecutionInvite(
    val kind: String,
    val relay: String,
    val targetAccountId: String,
    val targetDaemonPub: String,
    val connectTicket: String,
    val inviteSecret: String,
    val grantId: String,
    val ttlSec: Int,
    val targetLabel: String? = null,
) {
    companion object {
        const val KIND = "pairlet.execution_grant.v2"
        private val GRANT_ID = Regex("^xg_[A-Za-z0-9_-]{8,64}$")
        fun validGrantId(id: String) = GRANT_ID.matches(id)
    }
}

/** `host[:port]`, lowercased — what a join UI must show before anything is redeemed (review HIGH-2). */
val ExecutionInvite.relayAuthority: String get() = relayAuthorityOf(relay) ?: ""

internal fun relayAuthorityOf(relay: String): String? = runCatching {
    val u = URI(relay)
    val host = u.host?.lowercase() ?: return@runCatching null
    if (u.port == -1) host else "$host:${u.port}"
}.getOrNull()

fun ExecutionInvite.encodeUri(): String =
    EXECUTION_GRANT_INVITE_URI_PREFIX + b64(PocketJson.encodeToString(ExecutionInvite.serializer(), this).encodeToByteArray())

/** Decode ONLY at the execution door (full URI or bare blob). Null for anything else, including every
 *  collaborator / review-contact invite. */
fun decodeExecutionInvite(raw: String): ExecutionInvite? {
    val t = raw.trim()
    val blob = when {
        t.startsWith(EXECUTION_GRANT_INVITE_URI_PREFIX) -> t.removePrefix(EXECUTION_GRANT_INVITE_URI_PREFIX)
        t.startsWith("ccpocket://", ignoreCase = true) -> return null
        else -> t
    }.trim()
    if (blob.isEmpty() || blob.length > 16_000) return null
    val inv = runCatching {
        PocketJson.decodeFromString(ExecutionInvite.serializer(), b64d(blob).decodeToString())
    }.getOrNull() ?: return null
    val ok = inv.kind == ExecutionInvite.KIND &&
        validRelay(inv.relay) &&
        inv.targetAccountId.isNotBlank() && inv.targetAccountId.length <= 256 &&
        inv.connectTicket.isNotBlank() && inv.connectTicket.length <= 4_096 &&
        runCatching { b64d(inv.inviteSecret).size == ExecutionPsk.SECRET_BYTES }.getOrDefault(false) &&
        ExecutionInvite.validGrantId(inv.grantId) &&
        inv.ttlSec in 1..ExecutionTarget.MAX_TICKET_TTL_SEC &&
        validDaemonPub(inv.targetDaemonPub)
    return if (ok) inv.copy(relay = inv.relay.trimEnd('/')) else null
}

/**
 * Which relays a join may use without the human explicitly confirming the host (review HIGH-2). Any wss URL
 * decodes; a relay outside [knownRelayAuthorities] is refused unless the caller passes the exact authority the
 * user confirmed after being shown [ExecutionInvite.relayAuthority].
 */
class ExecutionRelayPolicy(knownRelayAuthorities: Set<String>) {
    private val known = knownRelayAuthorities.map { it.trim().lowercase() }.toSet()

    sealed interface Verdict {
        data object Known : Verdict
        data object ConfirmedByUser : Verdict
        data class Refused(val code: String) : Verdict
    }

    fun requireKnownRelay(invite: ExecutionInvite, confirmedRelayAuthority: String?): Verdict {
        val a = invite.relayAuthority
        if (a.isEmpty()) return Verdict.Refused("relay_invalid")
        if (a in known) return Verdict.Known
        if (confirmedRelayAuthority == null) return Verdict.Refused("relay_unconfirmed")
        return if (confirmedRelayAuthority.trim().lowercase() == a) Verdict.ConfirmedByUser else Verdict.Refused("relay_confirmation_mismatch")
    }

    companion object {
        /** The public Pairlet relay front (AGENTS.md: Cloudflare-fronted `pocket.ark-nexus.cc`). */
        val DEFAULT = ExecutionRelayPolicy(setOf("pocket.ark-nexus.cc"))
    }
}

/** Same rule as the review door: TLS, or plaintext only to loopback for development. */
private fun validRelay(raw: String): Boolean = runCatching {
    if (raw.length > 2_048) return@runCatching false
    val uri = URI(raw)
    val host = uri.host?.lowercase() ?: return@runCatching false
    val scheme = uri.scheme?.lowercase()
    val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
    (scheme == "wss" || (scheme == "ws" && loopback)) && uri.userInfo == null &&
        uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")
}.getOrDefault(false)
