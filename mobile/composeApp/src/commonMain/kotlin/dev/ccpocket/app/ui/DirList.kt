package dev.ccpocket.app.ui

import dev.ccpocket.protocol.DirectoryEntry

/** A row in the project browser, computed from the flat [DirectoryEntry] list client-side. */
sealed interface DirRow {
    data class Header(val label: String) : DirRow
    data class Dir(val entry: DirectoryEntry, val showPath: Boolean) : DirRow
}

/** Collapse $HOME to ~ (so paths stop repeating /Users/<name>/ everywhere). */
fun tilde(path: String): String {
    val seg = path.split('/')
    return if (seg.size > 3 && (seg[1] == "Users" || seg[1] == "home")) "~/" + seg.drop(3).joinToString("/") else path
}

/**
 * Rows for the project browser, newest-first within three sections: projects actively executing,
 * then ones with an open (idle) session, then the rest. The daemon already sorts by transcript mtime;
 * a search query just filters.
 */
fun buildDirRows(dirs: List<DirectoryEntry>, query: String): List<DirRow> {
    val q = query.trim()
    val filtered = if (q.isEmpty()) dirs else dirs.filter { it.path.contains(q, ignoreCase = true) }
    val executing = filtered.filter { it.executing }
    val openIdle = filtered.filter { it.open && !it.executing }
    val rest = filtered.filter { !it.open }
    val rows = ArrayList<DirRow>()
    fun section(label: String, items: List<DirectoryEntry>) {
        if (items.isEmpty()) return
        if (label.isNotEmpty()) rows += DirRow.Header(label)
        items.forEach { rows += DirRow.Dir(it, showPath = true) }
    }
    section("● Executing", executing)
    section("○ Open", openIdle)
    section(if (executing.isNotEmpty() || openIdle.isNotEmpty()) "Projects" else "", rest)
    return rows
}
