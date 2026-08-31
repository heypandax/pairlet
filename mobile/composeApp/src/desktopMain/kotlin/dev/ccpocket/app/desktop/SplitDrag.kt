package dev.ccpocket.app.desktop

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Drag-to-split (the sidebar-row gesture, issue #311's follow-up).
 *
 * The right-click "Open in split" answers WHERE a new column comes from, but not WHERE it lands: panes
 * append at the right end in click order. This is the VS Code editor-drag design adapted to the flat
 * column model — press a session row, drag it over the chat area, and the hovered column grows drop
 * zones: LEFT/RIGHT edges insert a new column at that position, CENTER is the row's ordinary click
 * (focus that session here). Everything positional is plain geometry over root-coordinate rects, so the
 * interesting rules live in testable functions rather than in gesture callbacks.
 */

/** How much of a column's width each edge zone claims. 0.30 → left third / centre 40% / right third. */
const val SPLIT_EDGE_FRACTION = 0.30f

enum class DropZone { LEFT, CENTER, RIGHT }

/** A drag hovering [zone] over column [column] — 0 is the focused chat, i ≥ 1 is sidePanes[i-1]. */
data class DropTarget(val column: Int, val zone: DropZone)

/**
 * The one drag the desktop tracks: what a sidebar row is currently carrying, where the pointer is
 * (root coordinates), and where every droppable column sits (also root coordinates, published by the
 * shell's column wrappers as they lay out).
 *
 * Deliberately dumb — no verbs, no policy. The row that started a drag ends it with [performDrop];
 * the shell only reads it to draw the zone overlay. Kept as a class (not scattered locals) because
 * the gesture starts in the sidebar and the feedback renders over the chat area.
 */
class SplitDragState {
    var session by mutableStateOf<DkSession?>(null)
        private set
    var position by mutableStateOf(Offset.Zero)
        private set

    /** Root-coordinate bounds per column index; Rect.Zero placeholders keep indices aligned. */
    var columnBounds by mutableStateOf<List<Rect>>(emptyList())
        private set

    fun begin(s: DkSession, at: Offset) {
        session = s
        position = at
    }

    fun moveTo(at: Offset) {
        position = at
    }

    fun clear() {
        session = null
    }

    /** Publish column [index]'s current bounds; skips the state write when nothing moved. */
    fun setColumnBounds(index: Int, rect: Rect) {
        val next = columnBounds.toMutableList()
        while (next.size <= index) next.add(Rect.Zero)
        if (next[index] == rect) return
        next[index] = rect
        columnBounds = next
    }
}

/**
 * Which column and zone [pos] lands in, or null when it is off every column (over the sidebar, the
 * docked workflow panel, or outside the window). Column order is the search order, so a stale rect
 * left behind by a column that just closed can never win against the wider rect that replaced it.
 */
fun resolveDropTarget(bounds: List<Rect>, pos: Offset): DropTarget? {
    val column = bounds.indexOfFirst { it.contains(pos) }
    if (column < 0) return null
    val r = bounds[column]
    val zone = when {
        pos.x < r.left + r.width * SPLIT_EDGE_FRACTION -> DropZone.LEFT
        pos.x > r.right - r.width * SPLIT_EDGE_FRACTION -> DropZone.RIGHT
        else -> DropZone.CENTER
    }
    return DropTarget(column, zone)
}

/** The part of column rect [r] a [zone] highlight covers — exactly the region that triggers it. */
fun zoneRect(r: Rect, zone: DropZone): Rect = when (zone) {
    DropZone.LEFT -> Rect(r.left, r.top, r.left + r.width * SPLIT_EDGE_FRACTION, r.bottom)
    DropZone.RIGHT -> Rect(r.right - r.width * SPLIT_EDGE_FRACTION, r.top, r.right, r.bottom)
    DropZone.CENTER -> Rect(r.left + r.width * SPLIT_EDGE_FRACTION, r.top, r.right - r.width * SPLIT_EDGE_FRACTION, r.bottom)
}

/**
 * Where an edge drop on [target] inserts into the panes list. Column 0 is the focused chat, which
 * stays leftmost by design, so both of its edges mean "first column after it"; on side column c the
 * RIGHT edge lands after panes[c-1] and the LEFT edge before it. CENTER never consults this.
 */
fun paneInsertIndex(target: DropTarget): Int = when (target.zone) {
    DropZone.RIGHT -> target.column
    DropZone.LEFT -> (target.column - 1).coerceAtLeast(0)
    DropZone.CENTER -> 0 // not reachable — [performDrop] routes CENTER to the ordinary open
}

/**
 * The same "may this row open a split" the right-click menu gates its entry on, so a drag and a menu
 * can never disagree about what is possible: there must be room, and the session must not already be
 * the focused chat or sitting in a column.
 */
fun splittableNow(model: DesktopModel, s: DkSession): Boolean =
    model.canSplit && s.sessionId != model.selectedSessionId && model.sidePanes.none { it.sessionId == s.sessionId }

/**
 * End a drag the way the zone under the pointer reads: CENTER is the row's own click ([openHere]),
 * an edge is [model.openInSplit] at [paneInsertIndex], and anything off the columns (or an edge with
 * no room left) is a cancelled drag — no-op, exactly like letting go over the sidebar.
 */
fun performDrop(model: DesktopModel, s: DkSession, drag: SplitDragState, openHere: () -> Unit) {
    val target = resolveDropTarget(drag.columnBounds, drag.position) ?: return
    if (target.zone == DropZone.CENTER) {
        openHere()
        return
    }
    if (!splittableNow(model, s)) return
    model.openInSplit(s, paneInsertIndex(target))
}

/**
 * Inert by default so surfaces composed outside [DesktopApp] (standalone-Sidebar UI tests, showcase
 * screenshots) render unchanged: a drag there updates a nobody-is-listening state and drops nowhere.
 */
val LocalSplitDrag = compositionLocalOf { SplitDragState() }
