package dev.ccpocket.app.desktop

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Drag-to-split (the sidebar-row gesture, issue #311's follow-up; halves per issue #336).
 *
 * The right-click "Open in split" answers WHERE a new column comes from, but not WHERE it lands: panes
 * append at the right end in click order. Dragging answers the position: press a session row, drag it
 * over the chat area, and the hovered column splits into two drop halves — LEFT means "the new column
 * goes to my left", RIGHT "to my right". Two halves, not thirds-plus-centre: a drop has exactly two
 * possible outcomes, so the geometry offers exactly two zones, and each zone can honour what it
 * highlights (the old CENTER=switch-session rode a gesture whose intent is splitting; switching stays
 * on the row's ordinary click). Everything positional is plain geometry over root-coordinate rects, so
 * the interesting rules live in testable functions rather than in gesture callbacks.
 */

enum class DropZone { LEFT, RIGHT }

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
    val zone = if (pos.x < r.left + r.width / 2) DropZone.LEFT else DropZone.RIGHT
    return DropTarget(column, zone)
}

/** The part of column rect [r] a [zone] highlight covers — exactly the region that triggers it. */
fun zoneRect(r: Rect, zone: DropZone): Rect = when (zone) {
    DropZone.LEFT -> Rect(r.left, r.top, r.left + r.width / 2, r.bottom)
    DropZone.RIGHT -> Rect(r.left + r.width / 2, r.top, r.right, r.bottom)
}

/**
 * Which visual SLOT a drop on [target] inserts at — [target.column] is already a visual slot (the
 * shell numbers droppable columns left to right, the focused chat included), so LEFT of column c is
 * slot c and RIGHT of it is slot c+1. Adjacent halves of neighbouring columns therefore name the SAME
 * landing point ("between A and B"), which is exactly what the eye expects of them.
 */
fun dropSlot(target: DropTarget): Int = target.column + if (target.zone == DropZone.RIGHT) 1 else 0

/**
 * The same "may this row open a split" the right-click menu gates its entry on, so a drag and a menu
 * can never disagree about what is possible: there must be room, and the session must not already be
 * the focused chat or sitting in a column.
 */
fun splittableNow(model: DesktopModel, s: DkSession): Boolean =
    model.canSplit && s.sessionId != model.selectedSessionId && model.sidePanes.none { it.sessionId == s.sessionId }

/**
 * End a drag the way the half under the pointer reads: [model.openInSplit] at [dropSlot]. Anything off
 * the columns, or a drop with no room left, is a cancelled drag — no-op, exactly like letting go over
 * the sidebar.
 */
fun performDrop(model: DesktopModel, s: DkSession, drag: SplitDragState) {
    val target = resolveDropTarget(drag.columnBounds, drag.position) ?: return
    if (!splittableNow(model, s)) return
    model.openInSplit(s, dropSlot(target))
}

/**
 * Inert by default so surfaces composed outside [DesktopApp] (standalone-Sidebar UI tests, showcase
 * screenshots) render unchanged: a drag there updates a nobody-is-listening state and drops nowhere.
 */
val LocalSplitDrag = compositionLocalOf { SplitDragState() }
