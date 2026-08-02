package dev.ccpocket.app.pairing

import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.ShareInvite

/**
 * ONE parser for every `ccpocket://` string the app can be handed
 * (SESSION-HANDOFF-IMPLEMENTATION-REVIEW §7).
 *
 * Before this existed each entry point had its own idea of what a link was: the iOS `onOpenURL` bridge and
 * the pairing scanner both called `handlePairUrl()`, which only understands `ccpocket://pair`, so a scanned
 * `ccpocket://collab#…` fell through as "invalid link" — the collaborator invite only worked from the one
 * paste field inside Join Folder. Android declared the scheme in its manifest and then never read
 * `intent.data` at all.
 *
 * The dispatch is by SCHEME + HOST, decided before any base64 is touched:
 *
 * ```text
 * ccpocket://pair?relay=…&acct=…&dpk=…&ticket=…   → Pair      (full pairing link)
 * ccpocket://pair?code=123456                     → Code      (relay-assisted short code)
 * ccpocket://collab#<b64url>                      → Collab    (Collaborator Link — CONFIRM, never redeem)
 * ccpocket://share#<b64url>                       → Share     (folder-share invite)
 * ccpocket://session?wd=…&sid=…                   → Session   (a push/handoff tap routing into a session)
 * ccpocket://handoff?id=…                         → Handoff   (a push tap routing into the offer inbox)
 * ```
 *
 * A BARE base64url blob (no scheme at all) is only decoded when [allowBareBlob] is set, i.e. from an
 * explicit paste field where the user typed/pasted a code on purpose. Guessing at bare blobs inside a
 * generic deep-link handler would let any `ccpocket://<anything>` be probed as an invite.
 */
sealed interface IncomingLink {
    /** A full `ccpocket://pair?...` link — hand to the ordinary pairing path. */
    data class Pair(val url: String) : IncomingLink

    /** The 6-digit code form of a pairing link. */
    data class Code(val code: String) : IncomingLink

    /** A Collaborator Link ticket. MUST go through the fingerprint confirm screen before redeeming. */
    data class Collab(val invite: CollaboratorInvite) : IncomingLink

    /** A folder-share invite. Goes through the guest accept-preview before redeeming. */
    data class Share(val invite: ShareInvite) : IncomingLink

    /** "Open this session" — the shape a task-complete push tap resolves to. */
    data class Session(val workdir: String, val sessionId: String) : IncomingLink

    /** "Open this handoff offer" — the routable, content-free id an offer push carries (§3.4). */
    data class Handoff(val handoffId: String) : IncomingLink

    /** Not a link this build understands. Callers show their own "invalid link" state. */
    data object Unknown : IncomingLink
}

private const val SCHEME = "ccpocket://"

/** `ccpocket://host/path?query#fragment` → the host token, lowercased ("" when there's no scheme). */
private fun hostOf(raw: String): String =
    if (!raw.startsWith(SCHEME, ignoreCase = true)) "" else raw.drop(SCHEME.length)
        .takeWhile { it != '/' && it != '?' && it != '#' }
        .lowercase()

/** Query params of a `ccpocket://…?a=1&b=2` link (the fragment is never part of the query). */
private fun queryOf(raw: String): Map<String, String> {
    val q = raw.substringBefore('#').substringAfter('?', "")
    if (q.isEmpty()) return emptyMap()
    return q.split('&').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
    }.toMap()
}

/**
 * Classify [raw]. Pure and total — never throws, never performs I/O, so every entry point (iOS
 * `onOpenURL`, the Android VIEW intent, the pairing scanner, the Join Folder paste field) can share it.
 */
fun parseIncomingLink(raw: String, allowBareBlob: Boolean = false): IncomingLink {
    val t = raw.trim()
    if (t.isEmpty()) return IncomingLink.Unknown
    val host = hostOf(t)
    return when (host) {
        // a collaborator/share blob is invalid rather than "maybe a pair link": we KNOW what it claimed
        // to be, so a truncated fragment or corrupt base64 must fail loudly instead of falling through
        "collab" -> decodeCollaboratorInvite(t)?.let(IncomingLink::Collab) ?: IncomingLink.Unknown
        "share" -> decodeShareInvite(t)?.let(IncomingLink::Share) ?: IncomingLink.Unknown
        "pair" -> {
            val q = queryOf(t)
            val code = q["code"]?.takeIf { it.length == 6 && it.all(Char::isDigit) }
            when {
                code != null -> IncomingLink.Code(code)
                Pairing.parse(t) != null -> IncomingLink.Pair(t)
                else -> IncomingLink.Unknown
            }
        }
        "session" -> {
            val q = queryOf(t)
            val wd = q["wd"]?.let(::pctDecode)?.takeIf { it.isNotBlank() }
            val sid = q["sid"]?.let(::pctDecode)?.takeIf { it.isNotBlank() }
            if (wd != null && sid != null) IncomingLink.Session(wd, sid) else IncomingLink.Unknown
        }
        "handoff" -> queryOf(t)["id"]?.let(::pctDecode)?.takeIf { it.isNotBlank() }
            ?.let(IncomingLink::Handoff) ?: IncomingLink.Unknown
        // no scheme: only an EXPLICIT paste entry may guess at a bare blob (share first — its codec is the
        // stricter of the two, and a collab blob never satisfies it)
        "" -> when {
            !allowBareBlob -> legacyPairUrl(t)
            else -> decodeShareInvite(t)?.let(IncomingLink::Share)
                ?: decodeCollaboratorInvite(t)?.let(IncomingLink::Collab)
                ?: legacyPairUrl(t)
        }
        else -> IncomingLink.Unknown
    }
}

/** Pre-scheme pairing links (and QR payloads from an older daemon) came as bare `?relay=…` query strings
 *  or a plain 6-digit code — keep both working rather than breaking existing printed/pasted material. */
private fun legacyPairUrl(t: String): IncomingLink = when {
    t.length == 6 && t.all(Char::isDigit) -> IncomingLink.Code(t)
    Regex("[?&]code=([0-9]{6})").find(t) != null ->
        IncomingLink.Code(Regex("[?&]code=([0-9]{6})").find(t)!!.groupValues[1])
    Pairing.parse(t) != null -> IncomingLink.Pair(t)
    else -> IncomingLink.Unknown
}

/** Minimal percent-decoding for the path/id params (a workdir routinely contains `/` and spaces). */
private fun pctDecode(s: String): String {
    if ('%' !in s && '+' !in s) return s
    val out = StringBuilder()
    var i = 0
    val bytes = ArrayList<Byte>()
    fun flush() {
        if (bytes.isNotEmpty()) { out.append(bytes.toByteArray().decodeToString()); bytes.clear() }
    }
    while (i < s.length) {
        val c = s[i]
        when {
            c == '%' && i + 2 < s.length -> {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex == null) { flush(); out.append(c); i++ } else { bytes.add(hex.toByte()); i += 3 }
            }
            c == '+' -> { flush(); out.append(' '); i++ }
            else -> { flush(); out.append(c); i++ }
        }
    }
    flush()
    return out.toString()
}
