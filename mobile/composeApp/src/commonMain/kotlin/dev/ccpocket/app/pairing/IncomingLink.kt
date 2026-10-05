package dev.ccpocket.app.pairing

/**
 * ONE parser for every `ccpocket://` string the app can be handed
 * (SESSION-HANDOFF-IMPLEMENTATION-REVIEW §7).
 *
 * Before this existed each entry point had its own idea of what a link was: the iOS `onOpenURL` bridge and
 * the pairing scanner both called `handlePairUrl()`, which only understands `ccpocket://pair`, so a scanned
 * invite of any other kind fell through as "invalid link". Android declared the scheme in its manifest and
 * then never read `intent.data` at all.
 *
 * The dispatch is by SCHEME + HOST, decided before any base64 is touched:
 *
 * ```text
 * ccpocket://pair?relay=…&acct=…&dpk=…&ticket=…   → Pair      (full pairing link)
 * ccpocket://pair?code=123456                     → Code      (relay-assisted short code)
 * ccpocket://collab#<anything>                    → Retired   (Collaborator Link — feature retired 2026-10)
 * ccpocket://review-contact#<anything>            → Retired   (ReviewRequest contact — feature retired 2026-10)
 * ccpocket://share#<anything>                     → Retired   (folder-share invite — feature retired 2026-10)
 * ccpocket://session?wd=…&sid=…                   → Session   (a push tap routing into a session)
 * ccpocket://handoff?<anything>                   → Retired   (Session Handoff offer — feature retired 2026-10)
 * ```
 *
 * A BARE base64url blob (no scheme at all) is no invite of any kind any more: every feature that minted one
 * has been retired, so with or without [allowBareBlob] it only gets the pre-scheme pairing forms.
 */
sealed interface IncomingLink {
    /** A full `ccpocket://pair?...` link — hand to the ordinary pairing path. */
    data class Pair(val url: String) : IncomingLink

    /** The 6-digit code form of a pairing link. */
    data class Code(val code: String) : IncomingLink

    /**
     * A link for a feature this build no longer has. Recognised by its HOST alone — the payload is never
     * decoded, so a corrupt one is still this — because the user deserves "that feature has been retired"
     * rather than "invalid link", and because it is not a failed pairing: callers park nothing, redeem
     * nothing and record no `pair_failed` for it. [feature] says which one, for callers that care.
     */
    data class Retired(val feature: RetiredFeature) : IncomingLink

    /** "Open this session" — the shape a task-complete push tap resolves to. */
    data class Session(val workdir: String, val sessionId: String) : IncomingLink

    /** Not a link this build understands. Callers show their own "invalid link" state. */
    data object Unknown : IncomingLink
}

/** The features whose links [IncomingLink.Retired] stands for. New values are only ever added. */
enum class RetiredFeature {
    /** ReviewRequest contact links (`ccpocket://review-contact#…`). */
    REVIEW,

    /** Collaborator Link connect tickets (`ccpocket://collab#…`). */
    COLLABORATOR,

    /** Session Handoff offers (`ccpocket://handoff?id=…`, and the `hid` an older daemon's offer push carries). */
    HANDOFF,

    /** Folder-share invites (`ccpocket://share#…`). */
    FOLDER_SHARE,
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
 * `onOpenURL`, the Android VIEW intent, the pairing scanner) can share it.
 *
 * [allowBareBlob] marks an explicit paste. It used to let a bare invite blob through; no feature mints one
 * any more, so it no longer changes the result. Kept so callers need not change.
 */
fun parseIncomingLink(raw: String, allowBareBlob: Boolean = false): IncomingLink {
    val t = raw.trim()
    if (t.isEmpty()) return IncomingLink.Unknown
    val host = hostOf(t)
    return when (host) {
        // retired features: the host alone decides, whatever the fragment or query holds
        "collab" -> IncomingLink.Retired(RetiredFeature.COLLABORATOR)
        "review-contact" -> IncomingLink.Retired(RetiredFeature.REVIEW)
        "handoff" -> IncomingLink.Retired(RetiredFeature.HANDOFF)
        "share" -> IncomingLink.Retired(RetiredFeature.FOLDER_SHARE)
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
        // no scheme: only the pre-scheme pair forms (no bare blob is an invite any more)
        "" -> legacyPairUrl(t)
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
