package dev.ccpocket.app.ui

import dev.ccpocket.protocol.PathEntry

// ════════════════════════════════════════════════════════════════════
//  Composer "@file" completion (issue #75) — the pure token/path math,
//  shared by the desktop composer (keyboard-driven menu) and the mobile
//  one. Kept UI-free so both surfaces rank/split identically and it's
//  unit-testable. The daemon does the filesystem read (ListPathEntries);
//  this only decides WHAT to browse and HOW to compose the inserted path.
// ════════════════════════════════════════════════════════════════════

/** The active `@file` token under the cursor: [at] is the `@` index, [end] the cursor offset, and
 *  [query] the text between them (never contains whitespace). */
data class AtToken(val at: Int, val end: Int, val query: String)

/**
 * The `@file` completion token the cursor sits in, or null when it isn't in one. A token starts at an
 * `@` that is at the very start of the text or right after whitespace, and runs to the cursor with no
 * whitespace in between — so "look at @src/Ma|" is a token (query "src/Ma") but "email a@b" is not
 * (the `@` follows a non-space) and "@one two|" is not (the cursor is past a space).
 */
fun atTokenAt(text: String, cursor: Int): AtToken? {
    if (cursor !in 0..text.length) return null
    val before = text.substring(0, cursor)
    val at = before.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0 && !before[at - 1].isWhitespace()) return null
    val query = before.substring(at + 1)
    if (query.any { it.isWhitespace() }) return null
    return AtToken(at, cursor, query)
}

/** The directory part of an @-query (everything before the last [sep]); empty = the cwd root. */
fun atDirOf(query: String, sep: Char): String = query.substringBeforeLast(sep, "")

/** The leaf being typed in an @-query (everything after the last [sep]) — the client-side filter. */
fun atLeafOf(query: String, sep: Char): String = query.substringAfterLast(sep)

/** The children of [dir] matching the typed [leaf] (case-insensitive prefix; empty leaf = all). The
 *  daemon already returns dirs-first, name-sorted, so order is preserved. */
fun atMatches(entries: List<PathEntry>, leaf: String): List<PathEntry> =
    if (leaf.isEmpty()) entries else entries.filter { it.name.startsWith(leaf, ignoreCase = true) }

/**
 * The text an entry pick inserts in place of the current @-query. Picking a directory appends a
 * trailing [sep] so the next browse drills into it; picking a file inserts the bare relative path.
 * [dir] is the query's current directory part (from [atDirOf]).
 */
fun atInsertText(dir: String, entry: PathEntry, sep: Char): String {
    val prefix = if (dir.isEmpty()) "" else dir + sep
    return prefix + entry.name + if (entry.isDir) sep.toString() else ""
}
