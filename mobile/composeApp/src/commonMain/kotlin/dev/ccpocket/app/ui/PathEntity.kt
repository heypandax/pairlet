package dev.ccpocket.app.ui

import androidx.compose.ui.text.AnnotatedString

internal const val MARKDOWN_LINK_TARGET = "markdown-link-target"

/**
 * Classification of a recognised transcript reference, for the share-side affordance (issue #116).
 * The recognition itself is unchanged (issue #74's [pathRx] / [urlRx]); this only labels each hit so
 * the interaction layer can style and act on it:
 *   • [OPEN] — a filesystem path the CURRENT machine can open (desktop, resolves + exists locally).
 *   • [COPY] — a filesystem path that belongs to another machine (the paired computer, or a remote
 *     session's box). It can't be opened here, but the address is exactly what you want to copy and
 *     share, so it is still recognised — copy-only, no open.
 *   • [URL]  — an http(s) link; openable from any machine and always copyable.
 */
enum class EntityKind { OPEN, COPY, URL }

/**
 * One recognised path/URL span in a piece of transcript text. [start]/[end] index the rendered text;
 * [display] is the substring as it appears inline; [target] is its destination; [copyValue] is the normalized value that share
 * actually copies — a relative path resolved to an absolute one under the session cwd, or a URL with
 * glued sentence punctuation already stripped (so "所见即所复制": the chip/sheet shows exactly this).
 */
data class LinkEntity(
    val start: Int,
    val end: Int,
    val kind: EntityKind,
    val display: String,
    val copyValue: String,
    /** Destination differs from the visible label for an explicit Markdown link. */
    val target: String = display,
)

/** Explicit Markdown destinations take precedence over auto-detection inside their visible labels. */
fun recognizeEntities(text: AnnotatedString, cwd: String?, canOpen: (String) -> Boolean): List<LinkEntity> {
    val explicit = text.getStringAnnotations(MARKDOWN_LINK_TARGET, 0, text.length).map { link ->
        val target = link.item
        val kind = when {
            target.startsWith("https://") || target.startsWith("http://") -> EntityKind.URL
            canOpen(target) -> EntityKind.OPEN
            else -> EntityKind.COPY
        }
        LinkEntity(link.start, link.end, kind, text.text.substring(link.start, link.end),
            if (kind == EntityKind.URL) target else normalizePath(target, cwd), target)
    }
    return (explicit + recognizeEntities(text.text, cwd, canOpen).filter { auto ->
        explicit.none { auto.start < it.end && it.start < auto.end }
    }).sortedBy { it.start }
}

/**
 * Resolves what a path reference should COPY as. Absolute, `~`, and drive-letter paths are already
 * portable and pass through untouched (the phone can't expand `~` — it's the paired computer's home,
 * a meaningful reference as-is). A cwd-relative path is joined onto [cwd] with the base's own
 * separator, mirroring how [DesktopPathOpener] resolves it for opening — so the copied value points a
 * teammate at the same file the CLI wrote about. With no cwd known, the relative value copies verbatim.
 */
fun normalizePath(display: String, cwd: String?): String {
    if (display.startsWith('/') || display.startsWith('~')) return display
    if (display.length >= 2 && display[1] == ':') return display // windows drive, e.g. C:\...
    val base = cwd?.takeIf { it.isNotBlank() } ?: return display
    val sep = if (base.contains('\\')) "\\" else "/"
    return base.trimEnd('/', '\\') + sep + display
}

/**
 * Recognises every path/URL in [text] and labels each (issue #116). Reuses issue #74's regexes and
 * the shared [cleanUrl] boundary — nothing about WHAT is recognised is decided here. [canOpen] decides OPEN vs COPY
 * for a path by the session's machine: on the desktop it is the local exists() gate (a remote
 * session's paths report false → COPY); mobile's RemotePathOpener permits opening through the daemon.
 * URLs that a path regex would also touch are dropped from the path pass so a link is labelled once.
 */
fun recognizeEntities(text: String, cwd: String?, canOpen: (String) -> Boolean): List<LinkEntity> {
    val out = ArrayList<LinkEntity>()
    val urlRanges = ArrayList<IntRange>()
    if (text.contains("http")) urlRx?.let { rx ->
        for (m in rx.findAll(text)) {
            val url = cleanUrl(text, m) ?: continue // the one shared end-boundary rule (issue #154)
            val start = m.range.first
            val end = start + url.length
            urlRanges += start until end
            out += LinkEntity(start, end, EntityKind.URL, url, url)
        }
    }
    if (text.contains('/') || text.contains('\\')) pathRx?.let { rx ->
        for (m in rx.findAll(text)) {
            val display = m.value.trimEnd('.') // sentence-final period is punctuation, not the path
            val start = m.range.first
            val end = start + display.length
            if (urlRanges.any { start < it.last + 1 && it.first < end }) continue // already labelled a URL
            val kind = if (canOpen(display)) EntityKind.OPEN else EntityKind.COPY
            out += LinkEntity(start, end, kind, display, normalizePath(display, cwd))
        }
    }
    out.sortBy { it.start }
    return out
}
