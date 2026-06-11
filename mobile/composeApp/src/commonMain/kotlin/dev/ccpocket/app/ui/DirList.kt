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

/** Collapse $HOME to ~ (so paths stop repeating /Users/<name>/ everywhere). */
fun tilde(path: String): String {
    val seg = path.split('/')
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
fun buildDirRows(dirs: List<DirectoryEntry>, query: String, openSessionsLabel: String, projectsLabel: String): List<DirRow> {
    val q = query.trim()
    val filtered = if (q.isEmpty()) dirs else dirs.filter { it.path.contains(q, ignoreCase = true) }
    val live = filtered.filter { it.open }
    val rows = ArrayList<DirRow>()
    fun section(label: String, items: List<DirectoryEntry>, direct: Boolean) {
        if (items.isEmpty()) return
        if (label.isNotEmpty()) rows += DirRow.Header(label)
        items.forEach { rows += DirRow.Dir(it, showPath = true, direct = direct) }
    }
    section(openSessionsLabel, live, direct = true)
    section(if (live.isNotEmpty()) projectsLabel else "", filtered, direct = false)
    return rows
}
