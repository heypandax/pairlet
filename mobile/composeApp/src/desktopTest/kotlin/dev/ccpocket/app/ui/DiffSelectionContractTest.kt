package dev.ccpocket.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import dev.ccpocket.app.data.parseUnifiedDiff
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for the "改动文件无法选择文本" report: DiffView ships two selection shapes, and both
 * must actually yield copyable text. Mobile (dense=false) selects through a container around each
 * ≤100-line block of a hunk — long-press must raise the copy toolbar, and the selection must be
 * able to span lines; wrapping the whole LazyColumn instead is dead on iOS devices (chat/terminal,
 * container-inside-the-item, are the shapes that work). Desktop (dense=true) keeps one container
 * around the list — mouse drag must select even though each line also carries the shared
 * horizontalScroll. Re-check on any CMP upgrade.
 */
@OptIn(ExperimentalTestApi::class)
class DiffSelectionContractTest {

    private class RecordingToolbar : TextToolbar {
        var copyCb: (() -> Unit)? = null
        var selectAllCb: (() -> Unit)? = null
        var shows = 0
        override var status = TextToolbarStatus.Hidden
        override fun hide() { status = TextToolbarStatus.Hidden }
        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            shows++; copyCb = onCopyRequested; selectAllCb = onSelectAllRequested; status = TextToolbarStatus.Shown
        }
    }

    private class RecordingClipboard : ClipboardManager {
        var stored: AnnotatedString? = null
        override fun getText(): AnnotatedString? = stored
        override fun setText(annotatedString: AnnotatedString) { stored = annotatedString }
    }

    private val addedLine = "charlie"

    @Composable
    private fun diffUnderTest(tb: TextToolbar, cb: ClipboardManager, dense: Boolean) {
        val hunks = parseUnifiedDiff(
            """
            @@ -1,2 +1,2 @@
             alpha context
            -bravo removed
            +$addedLine
            """.trimIndent(),
        )
        CompositionLocalProvider(LocalTextToolbar provides tb, LocalClipboardManager provides cb) {
            PocketTheme { DiffView(hunks, ext = null, dense = dense, wrap = false) }
        }
    }

    @Test
    fun mobile_longPress_selects_and_offers_copy() = runComposeUiTest {
        val tb = RecordingToolbar()
        val cb = RecordingClipboard()
        setContent { diffUnderTest(tb, cb, dense = false) }
        onNode(hasText(addedLine, substring = true)).performTouchInput { longClick(center) }
        waitForIdle()
        assertTrue(tb.shows > 0, "long-press must raise the copy toolbar")
        tb.copyCb!!.invoke()
        waitForIdle()
        assertEquals(addedLine, cb.stored?.text?.trimEnd('\n'), "copy must yield the pressed code text")
    }

    @Test
    fun desktop_mouseDrag_selects_despite_perline_hscroll() = runComposeUiTest {
        val tb = RecordingToolbar()
        val cb = RecordingClipboard()
        setContent { diffUnderTest(tb, cb, dense = true) }
        onNode(hasText(addedLine, substring = true)).performMouseInput {
            // start ON the glyphs (the node is row-wide; its center sits in empty space past the
            // short line, and a drag from empty space never starts a selection)
            val y = centerLeft.y
            moveTo(androidx.compose.ui.geometry.Offset(centerLeft.x + 12f, y)); press()
            moveTo(androidx.compose.ui.geometry.Offset(centerLeft.x + 40f, y))
            moveTo(androidx.compose.ui.geometry.Offset(centerLeft.x + 90f, y)); release()
        }
        waitForIdle()
        // desktop mouse selection has no toolbar — copy rides the platform shortcut (send both mappings)
        onRoot().performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.C); keyUp(Key.CtrlLeft)
            keyDown(Key.MetaLeft); pressKey(Key.C); keyUp(Key.MetaLeft)
        }
        waitForIdle()
        assertEquals(addedLine, cb.stored?.text?.trimEnd('\n'), "mouse drag + copy shortcut must yield the dragged line")
    }

    /** The point of the per-BLOCK container (vs per-line): a mobile selection can span lines.
     *  Select-all from the long-press menu must therefore cover the whole hunk block — and only
     *  code: gutters and the ± markers ride in DisableSelection. */
    @Test
    fun mobile_selection_spans_lines_within_a_block() = runComposeUiTest {
        val tb = RecordingToolbar()
        val cb = RecordingClipboard()
        setContent { diffUnderTest(tb, cb, dense = false) }
        onNode(hasText(addedLine, substring = true)).performTouchInput { longClick(center) }
        waitForIdle()
        tb.selectAllCb!!.invoke()
        waitForIdle()
        tb.copyCb!!.invoke()
        waitForIdle()
        assertEquals(
            "alpha context\nbravo removed\n$addedLine", cb.stored?.text?.trimEnd('\n'),
            "select-all in a block must yield every code line of the hunk, line-per-line, nothing else",
        )
    }

    @Test
    fun mobile_gutter_and_hunkHeader_stay_unselectable() = runComposeUiTest {
        val tb = RecordingToolbar()
        val cb = RecordingClipboard()
        setContent { diffUnderTest(tb, cb, dense = false) }
        onNode(hasText("@@", substring = true)).performTouchInput { longClick(center) }
        waitForIdle()
        assertEquals(0, tb.shows, "hunk header must not start a selection")
    }
}
