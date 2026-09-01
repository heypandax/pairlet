package dev.ccpocket.app.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.data.SidePanes
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Drag-to-split: the rules that must hold no matter how the gesture is performed (halves, issue #336).
 *
 * The zone geometry and the slot mapping are plain functions on purpose — the claims worth pinning
 * (which half is which zone, which SLOT a drop names, that open honours the slot and moves the focused
 * chat's own position) are exactly the ones a flaky gesture replay would only obscure. The UI tests
 * cover the wiring the maths cannot see: that a real drag from a sidebar row ends in
 * openInSplit(s, slot) for the half it was released on.
 */
@OptIn(ExperimentalTestApi::class)
class SplitDragTest {

    private val column = listOf(Rect(100f, 0f, 400f, 700f))

    @Test
    fun zonesSplitTheHoveredColumnInHalves() {
        // midpoint of [100, 400] is 250 — one pixel either side lands on opposite halves, and there
        // is deliberately no third zone in between (a drop has exactly two possible outcomes)
        assertEquals(DropZone.LEFT, resolveDropTarget(column, Offset(150f, 350f))?.zone)
        assertEquals(DropZone.LEFT, resolveDropTarget(column, Offset(249f, 350f))?.zone)
        assertEquals(DropZone.RIGHT, resolveDropTarget(column, Offset(251f, 350f))?.zone)
        assertEquals(DropZone.RIGHT, resolveDropTarget(column, Offset(380f, 350f))?.zone)
    }

    @Test
    fun offEveryColumnMeansNoDrop() {
        assertNull(resolveDropTarget(column, Offset(50f, 350f)))       // over the sidebar
        assertNull(resolveDropTarget(emptyList(), Offset(250f, 350f))) // no droppable column at all
    }

    @Test
    fun dropsNameVisualSlotsAndAdjacentHalvesAgree() {
        // LEFT of column c = slot c, RIGHT = slot c+1 — so column 0's right half and column 1's left
        // half both say "between 0 and 1", which is what the eye expects of two touching halves
        assertEquals(0, dropSlot(DropTarget(0, DropZone.LEFT)))
        assertEquals(1, dropSlot(DropTarget(0, DropZone.RIGHT)))
        assertEquals(1, dropSlot(DropTarget(1, DropZone.LEFT)))
        assertEquals(2, dropSlot(DropTarget(1, DropZone.RIGHT)))
        assertEquals(2, dropSlot(DropTarget(2, DropZone.LEFT)))
        assertEquals(3, dropSlot(DropTarget(2, DropZone.RIGHT)))
    }

    @Test
    fun zoneRectsCoverExactlyTheirTriggerRegions() {
        val r = column[0]
        assertEquals(Rect(100f, 0f, 250f, 700f), zoneRect(r, DropZone.LEFT))
        assertEquals(Rect(250f, 0f, 400f, 700f), zoneRect(r, DropZone.RIGHT))
    }

    @Test
    fun openHonoursTheSlotAndMovesTheFocusedChatWithIt() {
        val panes = SidePanes(CoroutineScope(Dispatchers.Unconfined), send = {}, newPromptId = { "p" })
        fun open(id: String, at: Int = -1) = panes.open("/w", id, id, AgentKind.CLAUDE, PermissionMode.DEFAULT, at)

        open("a")             // appended at the right end: [Chat][a]
        assertEquals(0, panes.focusedSlot.value)
        open("b", at = 0)     // a LEFT-half drop on the chat: [b][Chat][a] — the chat itself moves right
        assertEquals(listOf("b", "a"), panes.panes.map { it.sessionId })
        assertEquals(1, panes.focusedSlot.value)
        // MAX_SPLIT_PANES counts the focused chat, so two side columns is the last room — a third
        // open is a no-op whichever slot it quotes, and a session already in a column still is one
        assertNull(open("c", at = 1))
        assertNull(open("a"))
        assertEquals(listOf("b", "a"), panes.panes.map { it.sessionId })
        assertEquals(1, panes.focusedSlot.value)
    }

    @Test
    fun openClampsABogusSlotInsteadOfTrustingIt() {
        val panes = SidePanes(CoroutineScope(Dispatchers.Unconfined), send = {}, newPromptId = { "p" })
        panes.open("/w", "a", "a", AgentKind.CLAUDE, PermissionMode.DEFAULT)
        panes.open("/w", "b", "b", AgentKind.CLAUDE, PermissionMode.DEFAULT, at = 99) // stale slot → the end
        assertEquals(listOf("a", "b"), panes.panes.map { it.sessionId })
        assertEquals(0, panes.focusedSlot.value)
    }

    @Test
    fun closingAColumnLeftOfTheFocusSlidesTheFocusDown() {
        val panes = SidePanes(CoroutineScope(Dispatchers.Unconfined), send = {}, newPromptId = { "p" })
        val left = panes.open("/w", "a", "a", AgentKind.CLAUDE, PermissionMode.DEFAULT, at = 0)!! // [a][Chat]
        assertEquals(1, panes.focusedSlot.value)
        panes.close(left.paneId)
        assertEquals(0, panes.focusedSlot.value) // the chat is the single column again, not a phantom slot 1
    }

    @Test
    fun promotionWalksTheFocusIntoTheColumnsOwnPlace() {
        val panes = SidePanes(CoroutineScope(Dispatchers.Unconfined), send = {}, newPromptId = { "p" })
        panes.open("/w", "a", "a", AgentKind.CLAUDE, PermissionMode.DEFAULT, at = 0) // [a][Chat]
        panes.open("/w", "b", "b", AgentKind.CLAUDE, PermissionMode.DEFAULT)         // [a][Chat][b]
        assertEquals(1, panes.focusedSlot.value)

        panes.releaseToFocus("a") // promoting the LEFT column: the chat takes ITS slot…
        assertEquals(listOf("b"), panes.panes.map { it.sessionId })
        assertEquals(0, panes.focusedSlot.value) // …so the promoted conversation stays where the eye was

        panes.clear()
        assertEquals(0, panes.focusedSlot.value)
    }

    /** A seed that can accept splits and records where the drop said to put them. */
    private class DragSeed : SeedDesktopModel() {
        val opened = mutableListOf<Pair<String, Int>>()
        override val watch: DkWatch? get() = null // the live model's split renders under this state
        override val canSplit: Boolean get() = true
        override fun openInSplit(s: DkSession, at: Int) { opened += s.sessionId to at }
    }

    /** Drag the seed's "Fix stream parser test" row (s2, never the selected one) HORIZONTALLY to root
     *  x = [targetX]. The y stays on the row's own line on purpose: the gesture's direction lock reads
     *  the vector at slop-crossing, and a drag toward a target far above/below the row would read as the
     *  list scrolling — which is also not how a human drags a row sideways into the chat area. */
    private fun ComposeUiTest.dragSessionRowTo(targetX: (rootWidth: Float, sidebarRight: Float) -> Float): DragSeed {
        val model = DragSeed()
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        val row = onAllNodes(hasText("Fix stream parser test", substring = true)).onFirst()
        val rootWidth = onRoot().fetchSemanticsNode().size.width.toFloat()
        val sidebar = onNodeWithTag("sidebar-list").fetchSemanticsNode()
        val sidebarRight = sidebar.positionInRoot.x + sidebar.size.width
        val rowOrigin = row.fetchSemanticsNode().positionInRoot
        // performTouchInput speaks the ROW's coordinates; a root-space x translates through it
        row.performTouchInput {
            down(center)
            moveTo(Offset(targetX(rootWidth, sidebarRight) - rowOrigin.x, center.y))
            up()
        }
        waitForIdle()
        return model
    }

    @Test
    fun trimmedColumnsStopCatchingDrops() {
        // a closed third column's rect must not keep winning drops over whatever covers that area now
        val drag = SplitDragState()
        drag.setColumnBounds(0, Rect(0f, 0f, 100f, 700f))
        drag.setColumnBounds(1, Rect(100f, 0f, 200f, 700f))
        drag.setColumnBounds(2, Rect(200f, 0f, 300f, 700f))
        drag.trimColumnBounds(2)
        assertNull(resolveDropTarget(drag.columnBounds, Offset(250f, 350f)))
        assertEquals(1, resolveDropTarget(drag.columnBounds, Offset(150f, 350f))?.column)
    }

    @Test
    fun aPlainMouseClickOnARowIsAClickNotAConsumedDrag() = runComposeUiTest {
        // the pin rows' documented trap: mouse drag slop is ~0.125dp, so a whole-row drag gesture turned
        // any real click's 1px jitter into a consumed drag and the row never opened. The split drag must
        // leave a sub-threshold press entirely alone — including one that wobbles a couple of pixels.
        val model = DragSeed()
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        onAllNodes(hasText("Fix stream parser test", substring = true)).onFirst().performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(2f, 1f)) // the jitter of a human click
            release()
        }
        waitForIdle()
        assertEquals("s2", model.selectedSessionId)
        assertTrue(model.opened.isEmpty(), "a click must never open a split")
    }

    @Test
    fun aVerticalTouchDragBelongsToTheListNotTheSplit() = runComposeUiTest {
        // direction lock: a vertical swipe starting on a row is the list scrolling. The row gesture must
        // abandon it (unconsumed) instead of crossing its slop first and cancelling the scroll.
        val model = DragSeed()
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        onAllNodes(hasText("Fix stream parser test", substring = true)).onFirst().performTouchInput {
            down(center)
            moveBy(Offset(0f, 90f))
            up()
        }
        waitForIdle()
        assertTrue(model.opened.isEmpty(), "a vertical swipe must not open a split")
        assertNotEquals("s2", model.selectedSessionId)
    }

    @Test
    fun aDropWhileAModalIsOpenIsACancelledDrag() {
        // the feedback overlay stands down under a modal; the drop itself must match it, or a release
        // over the scrim would still open a column through the modal
        val model = DragSeed().apply { showSettings = true }
        val s = DkSession("s2", "/w", "Fix stream parser test")
        val drag = SplitDragState()
        drag.setColumnBounds(0, Rect(100f, 0f, 400f, 700f))
        drag.begin(s, Offset(150f, 350f))
        performDrop(model, s, drag)
        assertTrue(model.opened.isEmpty(), "a drop through a modal must be a no-op")
    }

    @Test
    fun droppingOnTheRightHalfOpensTheSplitToTheRight() = runComposeUiTest {
        // 80px from the right edge of the window = deep inside the sole chat column's RIGHT half
        val model = dragSessionRowTo { w, _ -> w - 80f }
        assertEquals(listOf("s2" to 1), model.opened)
    }

    @Test
    fun droppingOnTheLeftHalfOpensTheSplitToTheLeft() = runComposeUiTest {
        // just inside the chat column's left edge — its left half for any width the shell renders at.
        // The drop is a SPLIT, not the row's click: the selection must not move (the old centre zone
        // is gone on purpose; switching sessions stays on the ordinary click, issue #336).
        val model = dragSessionRowTo { _, sidebarRight -> sidebarRight + 60f }
        assertEquals(listOf("s2" to 0), model.opened)
        assertNotEquals("s2", model.selectedSessionId)
    }

    @Test
    fun aDragReleasedOverTheSidebarIsACancelledDrag() = runComposeUiTest {
        val model = dragSessionRowTo { _, sidebarRight -> sidebarRight - 60f }
        assertTrue(model.opened.isEmpty(), "letting go before the chat area must not open a split")
        assertNotEquals("s2", model.selectedSessionId)
    }
}
