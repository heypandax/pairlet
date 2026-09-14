package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.context_status_open_details
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.tightCenter
import dev.ccpocket.app.ui.ContextStatusPanel
import dev.ccpocket.app.ui.ContextStatusUi
import dev.ccpocket.app.ui.contextColor
import dev.ccpocket.app.ui.contextStatusMetaSegment
import org.jetbrains.compose.resources.stringResource

/**
 * Issue #320-A, desktop header: the `ctx …` segment of the chat sub-header's mono meta line, lifted out
 * so it is present in EVERY evidence shape (`ctx 42%`, `ctx ~84k`, `ctx — / 200k`, `ctx —`) and clicks
 * open a small popover explaining what is and isn't known — same card look and Esc-to-close as the
 * composer's model popover. Colour follows the existing occupancy ramp once a ratio exists.
 *
 * Standalone on purpose: ChatPane is wired by the integrator (see the #320-A hand-off).
 */
@Composable
internal fun ContextStatusMetaEntry(status: ContextStatusUi, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    // Anchor-click-while-open must CLOSE, never bounce back open. With CMP 1.12's default (single-scene)
    // layers a focusable Popup swallows the outside press: it dismisses and the anchor never sees the click
    // (pinned by ContextStatusEntryUiTest.focusablePopupSwallowsTheOutsidePress). Should a platform/layer
    // mode deliver that press to the anchor anyway, the decision is taken from the state AT PRESS time
    // (Initial pass, before any child or the dismiss can flip it) — no timing window, so holding the button
    // or a slow frame can't reopen it. Reset after use so a later keyboard activation starts clean.
    var openAtPress by remember { mutableStateOf(false) }
    val desc = stringResource(Res.string.context_status_open_details)
    Box(modifier) {
        Text(
            contextStatusMetaSegment(status),
            color = status.fraction?.let { contextColor(it, Tok.tx2) } ?: Tok.tx2,
            fontFamily = Dk.mono, fontSize = 11.sp, style = tightCenter(11.sp),
            maxLines = 1, overflow = TextOverflow.Clip,
            modifier = Modifier.clip(RoundedCornerShape(5.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        // only a primary press (left button / touch) can become a click; a right-press or any
                        // other button must not leave a value behind for the next gesture
                        val primary = down.type == PointerType.Touch || currentEvent.buttons.isPrimaryPressed
                        if (!primary) return@awaitEachGesture
                        openAtPress = open
                        // clear when THIS gesture ends, up or cancel (dragged out). Final pass on purpose: the
                        // release reaches clickable in the Main pass first, so its onClick has already read the
                        // value by the time it is reset here.
                        waitForUpOrCancellation(pass = PointerEventPass.Final)
                        openAtPress = false
                    }
                }
                .clickable { open = !openAtPress && !open }
                .semantics { contentDescription = desc }
                // horizontal only: vertical padding made the main column's meta line ~2dp taller than a split
                // column's (no ctx), misaligning the two headers' dividers. Height = the 11sp tightCenter line box.
                .padding(horizontal = 4.dp),
        )
        if (open) {
            val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(gap) { BelowAnchorEndPopupPositionProvider(gap) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) { ContextStatusPopover(status) { open = false } }
        }
    }
}

/** The popover card itself (also usable from any other desktop anchor). */
@Composable
internal fun ContextStatusPopover(status: ContextStatusUi, onDismiss: () -> Unit) {
    // the card holds no focusable child, so it takes focus itself — otherwise Esc has nowhere to land
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.width(280.dp).clip(RoundedCornerShape(14.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(14.dp))
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onDismiss(); true } else false
            }
            .focusRequester(focus)
            .focusable()
            .padding(15.dp),
    ) {
        ContextStatusPanel(status)
    }
}

/** Header anchors sit at the TOP of the pane, so this popover grows downward, right edges aligned. */
private class BelowAnchorEndPopupPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val y = (anchorBounds.bottom + gapPx).coerceAtMost(maxOf(0, windowSize.height - popupContentSize.height))
        return IntOffset(x, y)
    }
}
