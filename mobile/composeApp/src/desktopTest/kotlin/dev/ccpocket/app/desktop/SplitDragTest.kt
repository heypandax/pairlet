package dev.ccpocket.app.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.data.SidePanes
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Drag-to-split: the rules that must hold no matter how the gesture is performed.
 *
 * The zone geometry and the index mapping are plain functions on purpose — the claims worth pinning
 * (which third is which zone, where an edge drop inserts, that open honours the chosen position) are
 * exactly the ones a flaky gesture replay would only obscure. The two UI tests cover the wiring the
 * maths cannot see: that a real drag from a sidebar row ends in openInSplit(s, at), and that a
 * centre drop is the row's ordinary click rather than a split.
 */
@OptIn(ExperimentalTestApi::class)
class SplitDragTest {

    private val column = listOf(Rect(100f, 0f, 400f, 700f))

    @Test
    fun zonesSplitTheHoveredColumnInThirds() {
        assertEquals(DropZone.LEFT, resolveDropTarget(column, Offset(150f, 350f))?.zone)
        assertEquals(DropZone.CENTER, resolveDropTarget(column, Offset(250f, 350f))?.zone)
        assertEquals(DropZone.RIGHT, resolveDropTarget(column, Offset(380f, 350f))?.zone)
    }

    @Test
    fun offEveryColumnMeansNoDrop() {
        assertNull(resolveDropTarget(column, Offset(50f, 350f)))       // over the sidebar
        assertNull(resolveDropTarget(emptyList(), Offset(250f, 350f))) // no droppable column at all
    }

    @Test
    fun edgeDropsMapToPanePositions() {
        // column 0 is the focused chat — it stays leftmost, so BOTH of its edges mean "first column
        // after it"; on side column i the left edge lands before panes[i-1], the right edge after it
        assertEquals(0, paneInsertIndex(DropTarget(0, DropZone.LEFT)))
        assertEquals(0, paneInsertIndex(DropTarget(0, DropZone.RIGHT)))
        assertEquals(0, paneInsertIndex(DropTarget(1, DropZone.LEFT)))
        assertEquals(1, paneInsertIndex(DropTarget(1, DropZone.RIGHT)))
        assertEquals(1, paneInsertIndex(DropTarget(2, DropZone.LEFT)))
        assertEquals(2, paneInsertIndex(DropTarget(2, DropZone.RIGHT)))
    }

    @Test
    fun zoneRectsCoverExactlyTheirTriggerRegions() {
        val r = column[0]
        assertEquals(Rect(100f, 0f, 190f, 700f), zoneRect(r, DropZone.LEFT))
        assertEquals(Rect(310f, 0f, 400f, 700f), zoneRect(r, DropZone.RIGHT))
        assertEquals(Rect(190f, 0f, 310f, 700f), zoneRect(r, DropZone.CENTER))
    }

    @Test
    fun openInsertsAtTheChosenIndexAndKeepsItsGuards() {
        val panes = SidePanes(CoroutineScope(Dispatchers.Unconfined), send = {}, newPromptId = { "p" })
        fun open(id: String, at: Int = -1) = panes.open("/w", id, id, AgentKind.CLAUDE, PermissionMode.DEFAULT, at)

        open("a")             // appended at the end: [a]
        open("b", at = 0)     // a LEFT drop on side column 1: [b, a]
        assertEquals(listOf("b", "a"), panes.panes.map { it.sessionId })
        // MAX_SPLIT_PANES counts the focused chat, so two side columns is the last room — a third
        // open is a no-op whichever index it quotes, and a session already in a column still is one
        assertNull(open("c", at = 1))
        assertNull(open("a"))
        assertEquals(listOf("b", "a"), panes.panes.map { it.sessionId })
    }

    @Test
    fun openClampsABogusIndexInsteadOfTrustingIt() {
        val panes = SidePanes(CoroutineScope(Dispatchers.Unconfined), send = {}, newPromptId = { "p" })
        panes.open("/w", "a", "a", AgentKind.CLAUDE, PermissionMode.DEFAULT)
        panes.open("/w", "b", "b", AgentKind.CLAUDE, PermissionMode.DEFAULT, at = 99) // stale index → the end
        assertEquals(listOf("a", "b"), panes.panes.map { it.sessionId })
    }

    /** A seed that can accept splits and records where the drop said to put them. */
    private class DragSeed : SeedDesktopModel() {
        val opened = mutableListOf<Pair<String, Int>>()
        override val watch: DkWatch? get() = null // the live model's split renders under this state
        override val canSplit: Boolean get() = true
        override fun openInSplit(s: DkSession, at: Int) { opened += s.sessionId to at }
    }

    /** Drag the seed's "Fix stream parser test" row (s2, never the selected one) to [target]. */
    private fun ComposeUiTest.dragSessionRowTo(target: (rootWidth: Float) -> Offset): DragSeed {
        val model = DragSeed()
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()
        val row = onAllNodes(hasText("Fix stream parser test", substring = true)).onFirst()
        val rootWidth = onRoot().fetchSemanticsNode().size.width.toFloat()
        val rowOrigin = row.fetchSemanticsNode().positionInRoot
        // performTouchInput speaks the ROW's coordinates; a root-space target translates through it
        row.performTouchInput {
            down(center)
            moveTo(target(rootWidth) - rowOrigin)
            up()
        }
        waitForIdle()
        return model
    }

    @Test
    fun droppingOnAnEdgeOpensTheSplitAtThatPosition() = runComposeUiTest {
        // 80px from the right edge of the window = deep inside the sole chat column's RIGHT zone
        val model = dragSessionRowTo { w -> Offset(w - 80f, 300f) }
        assertEquals(listOf("s2" to 0), model.opened)
    }

    @Test
    fun droppingOnTheCentreIsTheRowsOrdinaryClick() = runComposeUiTest {
        // NOT w/2: the sidebar eats ~300dp, so the window's midpoint can still sit in the chat
        // column's LEFT zone — 0.7·w is inside the centre zone for any width the shell renders at
        val model = dragSessionRowTo { w -> Offset(w * 0.7f, 300f) }
        assertTrue(model.opened.isEmpty(), "a centre drop must not open a split")
        assertEquals("s2", model.selectedSessionId) // the seed's selectSession recorded the pick
    }
}
