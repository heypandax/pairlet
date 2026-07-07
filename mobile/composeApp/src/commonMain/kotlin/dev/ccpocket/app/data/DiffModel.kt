package dev.ccpocket.app.data

import dev.ccpocket.protocol.FileDiff

/** Client-fabricated [FileDiff.error] for "the daemon never answered — it predates diffs". Never
 *  produced by a daemon; [PocketRepository]'s reply deadline is the only writer. */
const val DIFF_ERROR_STALE_DAEMON = "stale_daemon"

/** True for the fabricated no-reply frame — UIs show "update the daemon" instead of "no diff". */
val FileDiff.staleDaemon: Boolean get() = !ok && error == DIFF_ERROR_STALE_DAEMON

/**
 * Client-side model of the unified-diff text a [dev.ccpocket.protocol.FileDiff] carries — shared by
 * the mobile viewer and the desktop Changes browser so both render the same grammar.
 *
 * The daemon emits one hunk group per tool call: `@@ -a,b +c,d @@` headers (all-zero when the
 * source had no line numbers — Codex patch envelopes) followed by ` `/`+`/`-`-prefixed lines.
 */
data class DiffHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val lines: List<DiffLine>,
) {
    val adds: Int get() = lines.count { it.kind == DiffLineKind.ADD }
    val dels: Int get() = lines.count { it.kind == DiffLineKind.DEL }
    /** False for the `@@ -0,0 +0,0 @@` sentinel — the gutter hides numbers it doesn't have. */
    val numbered: Boolean get() = oldStart > 0 || newStart > 0
}

enum class DiffLineKind { ADD, DEL, CTX }

/** [oldNo]/[newNo] are 1-based file line numbers, null on the side a +/− line doesn't exist on. */
data class DiffLine(
    val kind: DiffLineKind,
    val text: String, // prefix stripped
    val oldNo: Int? = null,
    val newNo: Int? = null,
)

private val hunkHeader = Regex("""@@ -(\d+),(\d+) \+(\d+),(\d+) @@""")

/** Never throws; unrecognized lines are dropped (the wire format is ours, so drift = daemon bug). */
fun parseUnifiedDiff(text: String): List<DiffHunk> {
    val hunks = mutableListOf<DiffHunk>()
    var header: MatchResult? = null
    var lines = mutableListOf<DiffLine>()
    var oldNo = 0
    var newNo = 0

    fun flush() {
        val h = header ?: return
        val (a, b, c, d) = h.destructured
        hunks += DiffHunk(a.toInt(), b.toInt(), c.toInt(), d.toInt(), lines)
        header = null
    }

    for (raw in text.lineSequence()) {
        val m = hunkHeader.matchEntire(raw)
        when {
            m != null -> {
                flush()
                header = m
                lines = mutableListOf()
                oldNo = m.groupValues[1].toInt()
                newNo = m.groupValues[3].toInt()
            }
            header == null -> {} // preamble noise — nothing before the first header is renderable
            raw.startsWith("+") -> lines.add(DiffLine(DiffLineKind.ADD, raw.substring(1), newNo = newNo++))
            raw.startsWith("-") -> lines.add(DiffLine(DiffLineKind.DEL, raw.substring(1), oldNo = oldNo++))
            raw.startsWith(" ") || raw.isEmpty() -> lines.add(DiffLine(DiffLineKind.CTX, raw.drop(1), oldNo = oldNo++, newNo = newNo++))
        }
    }
    flush()
    return hunks
}
