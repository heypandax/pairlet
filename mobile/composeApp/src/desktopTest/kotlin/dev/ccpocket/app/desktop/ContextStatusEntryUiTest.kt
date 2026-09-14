package dev.ccpocket.app.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.tightCenter
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.context_status_no_data
import dev.ccpocket.app.resources.context_status_no_data_detail
import dev.ccpocket.app.resources.context_status_open_details
import dev.ccpocket.app.resources.context_status_used_pending
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.contextStatusUi
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** #320-A desktop header: the `ctx` segment exists in every state and clicks open the explanation. */
@OptIn(ExperimentalTestApi::class)
class ContextStatusEntryUiTest {
    private fun s(res: StringResource) = runBlocking { getString(res) }

    private fun ComposeUiTest.anchor() = onNode(hasContentDescription(s(Res.string.context_status_open_details)))

    /** A real pointer sequence (move → press → release) through the scene, not a semantics click. */
    private fun ComposeUiTest.mouseClickAnchor() {
        anchor().performMouseInput { moveTo(center); press(); release() }
        waitForIdle()
    }

    @Test
    fun noDataStillShowsASegmentThatExplainsItself() = runComposeUiTest {
        setContent { PocketTheme { ContextStatusMetaEntry(contextStatusUi(null, null)) } }
        waitForIdle()
        assertFalse(present(s(Res.string.context_status_no_data)), "details stay behind the click")
        anchor().performClick()
        waitForIdle()
        assertPresent(s(Res.string.context_status_no_data))
        assertPresent(s(Res.string.context_status_no_data_detail))
    }

    /**
     * Anchor click while open → closed, via real mouse input. NOTE (review, 09-14): this does NOT reproduce
     * the toggle-reopen hazard — under CMP 1.12's test scene the focusable popup swallows the outside press
     * (see [focusablePopupSwallowsTheOutsidePress]), so a naive `open = !open` anchor passes too. It only
     * verifies that clicking the anchor while open closes it. Real-window behaviour needs a manual desktop check.
     */
    @Test
    fun clickingTheAnchorWhileOpenCloses() = runComposeUiTest {
        setContent { PocketTheme { ContextStatusMetaEntry(contextStatusUi(null, null)) } }
        waitForIdle()
        mouseClickAnchor()
        assertPresent(s(Res.string.context_status_no_data))
        mouseClickAnchor()
        assertFalse(present(s(Res.string.context_status_no_data)), "anchor click while open must close")
        mouseClickAnchor()
        assertPresent(s(Res.string.context_status_no_data)) // and the next click opens again
    }

    /**
     * Platform contract the anchor logic leans on: a focusable Popup dismisses on an outside press and the
     * press does NOT reach the anchor's clickable. If a CMP upgrade changes this, this goes red and the
     * press-time guard in [ContextStatusMetaEntry] becomes the only line of defence — re-verify manually.
     */
    @Test
    fun focusablePopupSwallowsTheOutsidePress() = runComposeUiTest {
        var open by mutableStateOf(true)
        var dismisses = 0
        var clicks = 0
        val below = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                IntOffset(anchorBounds.left, anchorBounds.bottom + 10)
        }
        setContent {
            Box {
                Text("ANCHOR", Modifier.clickable { clicks++; open = !open })
                if (open) Popup(below, onDismissRequest = { dismisses++; open = false }, properties = PopupProperties(focusable = true)) { Text("POPOVER") }
            }
        }
        waitForIdle()
        onNode(hasText("ANCHOR")).performMouseInput { moveTo(center); press(); release() }
        waitForIdle()
        assertEquals(1, dismisses, "outside press should dismiss the focusable popup")
        assertEquals(0, clicks, "outside press reached the anchor — toggle-reopen is possible again")
        assertFalse(open)
    }

    @Test
    fun escClosesAndALaterClickReopens() = runComposeUiTest {
        setContent { PocketTheme { ContextStatusMetaEntry(contextStatusUi(null, null)) } }
        waitForIdle()
        mouseClickAnchor()
        assertPresent(s(Res.string.context_status_no_data))
        // the main window and the popup window each keep a focused node; Esc goes to the popover card's
        onNode(isFocused() and !hasContentDescription(s(Res.string.context_status_open_details)))
            .performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(present(s(Res.string.context_status_no_data)), "Esc must close the popover")
        mouseClickAnchor()
        assertPresent(s(Res.string.context_status_no_data))
    }

    /**
     * Review P2-2: the ctx fragment must be exactly as tall as the 11sp tightCenter mono path text it sits
     * beside — any extra vertical padding makes the main column's meta line taller than a split column's
     * (which has no ctx) and the two header dividers stop lining up.
     */
    @Test
    fun ctxFragmentIsNoTallerThanTheMetaLineText() = runComposeUiTest {
        setContent {
            PocketTheme {
                Row {
                    Text(
                        "/w", fontFamily = Dk.mono, fontSize = 11.sp, style = tightCenter(11.sp), maxLines = 1,
                        modifier = Modifier.testTag("path"),
                    )
                    ContextStatusMetaEntry(contextStatusUi(84_000, 200_000))
                }
            }
        }
        waitForIdle()
        val path = onNode(hasTestTag("path")).getUnclippedBoundsInRoot()
        val ctx = anchor().getUnclippedBoundsInRoot()
        assertEquals(path.bottom - path.top, ctx.bottom - ctx.top, "ctx fragment height drifted from the meta line")
    }

    /**
     * Review P2-1. Only verifies that a right-press does not open the popover and does not block a later
     * left-click from opening it. It CANNOT prove the openAtPress-residue fix: under the old code a right-press
     * while closed also recorded false, and while open the focusable popup swallows the press. That branch
     * needs a manual desktop check.
     */
    @Test
    fun aSecondaryPressDoesNotBlockTheNextClick() = runComposeUiTest {
        setContent { PocketTheme { ContextStatusMetaEntry(contextStatusUi(null, null)) } }
        waitForIdle()
        anchor().performMouseInput { moveTo(center); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        waitForIdle()
        assertFalse(present(s(Res.string.context_status_no_data)), "a right-press must not open the popover")
        mouseClickAnchor()
        assertPresent(s(Res.string.context_status_no_data))
    }

    @Test
    fun windowOnlyPopoverSaysUsageIsPending() = runComposeUiTest {
        setContent { PocketTheme { ContextStatusMetaEntry(contextStatusUi(null, 200_000)) } }
        waitForIdle()
        anchor().performClick()
        waitForIdle()
        assertPresent(s(Res.string.context_status_used_pending))
    }
}
