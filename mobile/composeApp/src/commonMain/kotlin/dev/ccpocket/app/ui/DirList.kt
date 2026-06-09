package dev.ccpocket.app.ui

import dev.ccpocket.protocol.DirectoryEntry

/** A row in the directory browser, computed from the flat [DirectoryEntry] list client-side. */
sealed interface DirRow {
    data class Header(val label: String) : DirRow
    data class Dir(val entry: DirectoryEntry, val showPath: Boolean) : DirRow
    data class WtHeader(val name: String, val count: Int, val key: String, val expanded: Boolean) : DirRow
    data class WtChild(val entry: DirectoryEntry) : DirRow
}

/** Collapse $HOME to ~ (so paths stop repeating /Users/<name>/ everywhere). */
fun tilde(path: String): String {
    val seg = path.split('/')
    return if (seg.size > 3 && (seg[1] == "Users" || seg[1] == "home")) "~/" + seg.drop(3).joinToString("/") else path
}

private fun parentOf(p: String) = p.substringBeforeLast('/', "")
private fun baseOf(p: String) = p.substringAfterLast('/')

/**
 * Turn the flat directory list into a grouped, de-cluttered set of rows:
 *  - a search query flattens to two-line matches;
 *  - otherwise: recents pinned on top, the rest grouped by parent directory, and
 *    worktree branches (paths under a "-worktrees" dir) folded into one collapsible row.
 */
fun buildDirRows(dirs: List<DirectoryEntry>, query: String, expanded: Set<String>): List<DirRow> {
    val q = query.trim()
    val filtered = if (q.isEmpty()) dirs else dirs.filter { it.path.contains(q, ignoreCase = true) }
    if (q.isNotEmpty()) return filtered.sortedBy { it.path }.map { DirRow.Dir(it, showPath = true) }

    val rows = ArrayList<DirRow>()

    val recents = filtered.filter { it.recent }
    if (recents.isNotEmpty()) {
        rows += DirRow.Header("RECENT")
        recents.sortedBy { it.path }.forEach { e -> rows += DirRow.Dir(e, showPath = true) }
    }

    val rest = filtered.filterNot { it.recent }
    val (worktrees, normal) = rest.partition { parentOf(it.path).endsWith("-worktrees") }
    val wtGroups = worktrees.groupBy { parentOf(it.path) }          // worktree-root path -> branches
    val normalGroups = normal.groupBy { parentOf(it.path) }         // parent path -> dirs
    val wtHome = wtGroups.keys.associateWith { parentOf(it) }       // worktree-root -> its parent section

    val sectionKeys = (normalGroups.keys + wtHome.values).distinct().sortedBy { it }
    for (key in sectionKeys) {
        rows += DirRow.Header(tilde(key))
        normalGroups[key]?.sortedBy { it.name }?.forEach { e -> rows += DirRow.Dir(e, showPath = false) }
        val wtHere = wtGroups.filterKeys { wtHome[it] == key }
        for (wtRoot in wtHere.keys.sorted()) {
            val branches = wtHere.getValue(wtRoot)
            rows += DirRow.WtHeader(baseOf(wtRoot), branches.size, wtRoot, wtRoot in expanded)
            if (wtRoot in expanded) branches.sortedBy { it.name }.forEach { e -> rows += DirRow.WtChild(e) }
        }
    }
    return rows
}
