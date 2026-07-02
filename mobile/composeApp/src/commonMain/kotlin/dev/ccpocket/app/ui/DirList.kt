package dev.ccpocket.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.DirectoryEntry

/** A row in the project browser, computed from the flat [DirectoryEntry] list client-side. */
sealed interface DirRow {
    data class Header(val label: String) : DirRow

    /** [direct] = a live-section row: tapping jumps straight into the running session (not the session list). */
    data class Dir(val entry: DirectoryEntry, val showPath: Boolean, val direct: Boolean = false) : DirRow
}

// Project paths arrive in the daemon HOST's native format: Unix "/a/b" OR Windows "C:\a\b". Split on
// either separator, but rebuild paths with the ORIGINAL separator so equality against DirectoryEntry.path
// (e.g. base == entry.path, here and in App.kt) still holds on a Windows daemon.
private val PATH_SEP = Regex("""[/\\]""")
internal fun sepOf(path: String): Char = if (path.contains('\\')) '\\' else '/' // shared with App.kt's NewPathSheet seed

/** Collapse $HOME to ~ (so paths stop repeating /Users/<name>/ everywhere). */
fun tilde(path: String): String {
    val seg = path.split(PATH_SEP)
    return if (seg.size > 3 && (seg[1] == "Users" || seg[1] == "home")) "~/" + seg.drop(3).joinToString("/") else path
}

/**
 * A one-line monospace path that overflows from the FRONT — the project folder (the tail) is what
 * identifies a workdir, so a long path renders as "…app/cc-pocket" instead of "/Users/lidapeng/…".
 * Compose 1.7 has no TextOverflow.StartEllipsis; this trims via onTextLayout until the tail fits.
 */
@Composable
fun TailPathText(path: String, modifier: Modifier = Modifier, color: Color = Tok.tx2, fontSize: TextUnit = 11.sp) {
    val full = tilde(path)
    var drop by remember(full) { mutableStateOf(0) }
    val shown = if (drop <= 0) full else "…" + full.takeLast((full.length - drop).coerceAtLeast(4))
    Text(
        shown, color = color, fontFamily = FontFamily.Monospace, fontSize = fontSize,
        maxLines = 1, softWrap = false,
        onTextLayout = { r ->
            // monospace → proportional first jump, then settle in a couple of passes
            if (r.hasVisualOverflow && drop < full.length - 4) {
                drop = (drop + (full.length / 6).coerceAtLeast(2)).coerceAtMost(full.length - 4)
            }
        },
        modifier = modifier,
    )
}

/**
 * Rows for the project browser: one "Open Sessions" section (every project with a live session —
 * the running/idle badge on the row tells them apart), then ALL projects. Live projects appear
 * twice on purpose — the top section jumps straight into the running session, the Projects copy
 * keeps the session list and "New session" reachable. The daemon sorts by transcript mtime.
 * Section labels come in pre-localized (this runs inside remember{}, outside composition).
 */
/** The entries for the pinned [paths] (in pin order) that are still present in [dirs]. Shared by the flat
 *  and tree views so both resolve pins the same way. */
fun pinnedEntries(dirs: List<DirectoryEntry>, paths: List<String>): List<DirectoryEntry> =
    paths.mapNotNull { p -> dirs.firstOrNull { it.path == p } }

fun buildDirRows(
    dirs: List<DirectoryEntry>,
    query: String,
    pinned: List<String>,
    pinnedLabel: String,
    openSessionsLabel: String,
    projectsLabel: String,
): List<DirRow> {
    val q = query.trim()
    // match path + project name + the LIVE session's title (what the card shows). Idle-session titles and
    // transcript content aren't in this flat list — searching those needs a daemon-side session search.
    val filtered = if (q.isEmpty()) dirs else dirs.filter {
        it.path.contains(q, ignoreCase = true) ||
            it.name.contains(q, ignoreCase = true) ||
            it.activeSessionTitle?.contains(q, ignoreCase = true) == true
    }
    // a session with running background work stays "open" in the list even if its claude process check lags
    val live = filtered.filter { it.open || it.busy }
    // pinned-to-top, in pin order; only those still present (and matching the filter). Like the live section,
    // a pinned project also keeps its copy in the full Projects list below.
    val pins = pinnedEntries(filtered, pinned)
    val rows = ArrayList<DirRow>()
    fun section(label: String, items: List<DirectoryEntry>, direct: Boolean) {
        if (items.isEmpty()) return
        if (label.isNotEmpty()) rows += DirRow.Header(label)
        items.forEach { rows += DirRow.Dir(it, showPath = true, direct = direct) }
    }
    section(pinnedLabel, pins, direct = true)
    section(openSessionsLabel, live, direct = true)
    section(if (live.isNotEmpty() || pins.isNotEmpty()) projectsLabel else "", filtered, direct = false)
    return rows
}

// ── tree browse: a client-side hierarchy over the flat project list ───────────────────────────────
// The daemon only knows project dirs (cwds with Claude history); it has no real filesystem tree. We
// derive one by grouping those flat paths on their directory segments, so the phone can drill in level
// by level (one level per screen, mobile-first) with a breadcrumb back up.

/** A node at the current tree level: a folder to drill into, or a project leaf to open. */
sealed interface TreeRow {
    /** [project] is non-null when this folder is ALSO a project itself — tapping then opens its sessions
     *  directly (a separate chevron drills into subfolders), instead of dead-ending behind the drill. */
    data class Folder(val name: String, val path: String, val project: DirectoryEntry? = null) : TreeRow
    data class Leaf(val entry: DirectoryEntry) : TreeRow
}

/** The tree root: the user's home (~/…) inferred from the project paths, else their common parent dir. */
fun treeRoot(dirs: List<DirectoryEntry>): String {
    dirs.firstNotNullOfOrNull { e ->
        val s = e.path.split(PATH_SEP)
        // keep s[0] (the drive on Windows, "" on Unix) so the root stays a real prefix of the entries
        if (s.size > 3 && (s[1] == "Users" || s[1] == "home")) s.take(3).joinToString(sepOf(e.path).toString()) else null
    }?.let { return it }
    val paths = dirs.map { it.path }
    if (paths.isEmpty()) return "/"
    val sep = sepOf(paths.first())
    var prefix = paths.first().substringBeforeLast(sep)
    for (p in paths.drop(1)) {
        while (prefix.isNotEmpty() && p != prefix && !p.startsWith("$prefix$sep")) prefix = prefix.substringBeforeLast(sep, "")
    }
    return prefix.ifEmpty { sep.toString() }
}

/**
 * Rows directly under [base]: immediate child folders (drill in) + project leaves (open). Newest-first.
 * A child that has ANY deeper project is a Folder (drillable) EVEN if it is itself a project — otherwise
 * dirs like ~/Desktop (a project that also holds many projects) would dead-end as a leaf. When [base]
 * itself is a project, its own sessions appear as a leaf at the top of this level.
 *
 * [includeOrphans] (pass true at the tree ROOT): projects NOT under [base] — another drive on Windows,
 * or outside the inferred home — are appended as plain leaves, else tree mode simply loses them (the
 * home-pinned root filters them out and no drill can reach them; flat view always had them).
 */
fun buildTree(dirs: List<DirectoryEntry>, base: String, includeOrphans: Boolean = false): List<TreeRow> {
    // sep from base when it carries one; a degenerate root ("C:" from the common-prefix walk, or "/")
    // has none to detect, so fall back to what the entries actually use
    val sep = if (base.contains('\\') || base.contains('/')) sepOf(base) else dirs.firstOrNull()?.path?.let(::sepOf) ?: '/'
    val prefix = if (base.endsWith(sep)) base else "$base$sep" // don't double the sep when base already ends with it ("/", "C:\")
    val relevant = dirs.filter { it.path == base || it.path.startsWith(prefix) }
    val rows = ArrayList<TreeRow>()
    relevant.firstOrNull { it.path == base }?.let { rows += TreeRow.Leaf(it) } // base's own sessions, if any
    val byChild = LinkedHashMap<String, MutableList<DirectoryEntry>>()
    for (e in relevant) {
        if (e.path == base) continue
        val seg = e.path.removePrefix(prefix).split(PATH_SEP).first()
        byChild.getOrPut(seg) { mutableListOf() }.add(e)
    }
    byChild.entries
        .sortedByDescending { (_, es) -> es.maxOf { it.lastModified } }
        .forEach { (seg, es) ->
            val childPath = prefix + seg
            if (es.any { it.path.startsWith("$childPath$sep") }) { // has deeper projects → drillable folder
                rows += TreeRow.Folder(seg, childPath, project = es.firstOrNull { it.path == childPath })
            } else {
                rows += TreeRow.Leaf(es.first { it.path == childPath })
            }
        }
    if (includeOrphans) {
        dirs.filterNot { it.path == base || it.path.startsWith(prefix) }
            .sortedByDescending { it.lastModified }
            .forEach { rows += TreeRow.Leaf(it) }
    }
    return rows
}

/** Breadcrumb segments for [base], home collapsed to ~. e.g. /Users/x/proj/app -> [~, proj, app]. */
fun crumbs(base: String): List<String> = tilde(base).split(PATH_SEP).filter { it.isNotEmpty() }

/**
 * Breadcrumb labels WITH their drill targets, anchored at the tree [root]. The old label-only
 * reconstruction assumed the first segment WAS the root — wrong whenever root is deeper than one
 * segment (a common-prefix root like `/opt/x` or `C:\dev`), where a middle-crumb jump then composed
 * a nonsense path and bounced the view back to root.
 */
fun crumbTargets(base: String, root: String): List<Pair<String, String>> {
    val sep = sepOf(base)
    val rs = root.split(PATH_SEP)
    // the home root itself is exactly [drive-or-empty, Users|home, <user>] — tilde() only collapses DEEPER paths
    val rootLabel = if (rs.size == 3 && (rs[1] == "Users" || rs[1] == "home")) "~" else rs.lastOrNull { it.isNotEmpty() } ?: root
    val rest = base.removePrefix(root).split(PATH_SEP).filter { it.isNotEmpty() }
    val out = ArrayList<Pair<String, String>>()
    out += rootLabel to root
    var acc = root
    for (seg in rest) {
        acc = if (acc.endsWith(sep)) "$acc$seg" else "$acc$sep$seg"
        out += seg to acc
    }
    return out
}
