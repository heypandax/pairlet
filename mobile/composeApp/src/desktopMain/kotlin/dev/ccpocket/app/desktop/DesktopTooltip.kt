package dev.ccpocket.app.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import dev.ccpocket.app.resources.file_show_full_path
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.TailPathText
import dev.ccpocket.app.ui.fileNameOf
import org.jetbrains.compose.resources.stringResource

// ── the ONE desktop hover-tooltip ───────────────────────────────────────────────────────────────
// Lifted out of WindowChrome (where the window-control cluster grew it) so every desktop surface that
// wants "what does this icon do?" reuses the same capsule, delay and placement instead of starting a
// second tooltip system. Hover-only by construction: [TooltipArea] reacts to pointer enter/exit and
// never consumes the press, so the wrapped control keeps its click, its disabled state and its keys.

/** The dwell before a tooltip appears — long enough that sweeping the pointer across a toolbar stays quiet. */
internal const val TOOLTIP_DELAY_MS = 500

/** Wraps [content] in the shared hover tooltip carrying [label] (and an optional [shortcut]). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesktopTooltip(
    label: String,
    shortcut: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipArea(tooltip = { TooltipCapsule(label, shortcut) }, modifier = modifier, delayMillis = TOOLTIP_DELAY_MS) {
        content()
    }
}

/** [DesktopTooltip] with a caller-built body, for the tooltips that are more than one line (a full path). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesktopTooltipBox(
    tooltip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipArea(tooltip = tooltip, modifier = modifier, delayMillis = TOOLTIP_DELAY_MS) { content() }
}

/** The mock's tooltip: a raised hairline capsule carrying the label and its shortcut (mock:323-330). */
@Composable
internal fun TooltipCapsule(label: String, shortcut: String? = null) {
    TooltipShell {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = Tok.tx, fontFamily = Dk.ui, fontSize = 10.5.sp, style = tightCenter(10.5.sp))
            if (!shortcut.isNullOrEmpty()) {
                Text(shortcut, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 10.sp, style = tightCenter(10.sp))
            }
        }
    }
}

/** A wrapped preview on hover, with a persistent, selectable view on click for paths taller than the window. */
@Composable
internal fun FilePathTitle(path: String, modifier: Modifier = Modifier) {
    var expanded by remember(path) { mutableStateOf(false) }
    val name = fileNameOf(path)
    val label = stringResource(Res.string.file_show_full_path)
    Box(modifier) {
        DesktopTooltipBox(tooltip = { if (!expanded) PathTooltipCapsule(name, path) }) {
            TailPathText(
                path, fontSize = 12.sp, color = Tok.tx2, tight = true,
                modifier = Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = label) { expanded = true }
                    .semantics { contentDescription = label },
            )
        }
        if (expanded) {
            Popup(
                popupPositionProvider = PathDetailsPosition,
                properties = PopupProperties(focusable = true),
                onDismissRequest = { expanded = false },
            ) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                SelectionContainer {
                    PathTooltipCapsule(
                        name, path,
                        modifier = Modifier.onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                                expanded = false
                                true
                            } else false
                        }.focusRequester(focus).focusable(),
                    )
                }
            }
        }
    }
}

private object PathDetailsPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset {
        val start = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        return IntOffset(
            start.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width)),
            anchorBounds.bottom.coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height)),
        )
    }
}

/** No text ellipsis: long content wraps and scrolls inside the bounded surface. */
@Composable
internal fun PathTooltipCapsule(name: String, path: String, modifier: Modifier = Modifier) {
    TooltipShell(modifier) {
        Column(
            Modifier.widthIn(max = 420.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(name, color = Tok.tx, fontFamily = Dk.ui, fontSize = 11.sp)
            if (path != name) {
                Text(path, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun TooltipShell(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(5.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
    ) { content() }
}
