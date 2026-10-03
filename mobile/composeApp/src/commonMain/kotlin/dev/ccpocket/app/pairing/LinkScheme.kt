package dev.ccpocket.app.pairing

/** The scheme every link parser in this package understands — frozen with the pre-rename builds. */
private const val LEGACY_SCHEME = "ccpocket://"

/** The Pairlet-named scheme. Apps accept it from this build on; the daemon keeps PRINTING [LEGACY_SCHEME]
 *  until builds that accept this one are what people actually have installed (an older app cannot read it). */
const val PAIRLET_SCHEME = "pairlet://"

/**
 * `pairlet://…` → `ccpocket://…`, anything else unchanged. Called once at each door a link enters by (OS
 * deep link, scanner, paste field) so the parsers behind it — and the bytes of the links they already
 * accept — stay exactly as they were.
 */
fun canonicalLinkScheme(raw: String): String {
    val start = raw.indexOfFirst { !it.isWhitespace() }
    if (start < 0 || !raw.startsWith(PAIRLET_SCHEME, startIndex = start, ignoreCase = true)) return raw
    return raw.substring(0, start) + LEGACY_SCHEME + raw.substring(start + PAIRLET_SCHEME.length)
}
