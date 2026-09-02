package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.exit_fullscreen
import dev.ccpocket.app.resources.search
import dev.ccpocket.app.resources.tooltip_next_session
import dev.ccpocket.app.resources.tooltip_prev_session
import dev.ccpocket.app.resources.tooltip_toggle_sidebar
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/**
 * The window's chrome, handed DOWN to whatever surface currently owns it (desktop chrome v2).
 *
 * There is no title bar any more: nothing spans the window width, the sidebar runs to the window top and
 * the chat column's sub-header is its own first element. The pieces a title bar used to hold — traffic
 * lights, min/max/close, the drag-and-zoom handle — therefore have to be reachable from two very different
 * places (the sidebar's control row while it is open; the leftmost chat sub-header once it is collapsed),
 * neither of which is a [FrameWindowScope] and both of which are several composables deep. Passing five
 * callbacks plus a Modifier through every one of those layers is what this exists to avoid.
 *
 * The default instance is inert with an EMPTY [dragAndZoomModifier], which is what makes the seed/preview
 * models and the screenshot test compose without a real window: [mac] false also means no traffic lights
 * and no window buttons, so an unprovided chrome renders as "no chrome" rather than as dead controls.
 */
@Immutable
class DesktopWindowChrome(
    /** macOS — traffic lights on the leading edge, no [WinControls] on the trailing one. */
    val mac: Boolean = false,
    /** True native fullscreen (issue #94): macOS supplies its own auto-revealing menu bar, so the traffic
     *  lights stand down rather than doubling up; Win/Linux keep [FullscreenExitStrip] instead. */
    val fullscreen: Boolean = false,
    val onClose: () -> Unit = {},
    val onMinimize: () -> Unit = {},
    /** "Zoom" — fill the current screen's usable bounds. Distinct from [onToggleFullscreen] on purpose. */
    val onToggleMax: () -> Unit = {},
    val onToggleFullscreen: () -> Unit = {},
    /** Drag the window / double-click to zoom. Hang it on a surface's FLEXIBLE, non-interactive region
     *  only — never a whole row, or the controls inside it stop receiving clicks (the old title bar's
     *  lesson: its buttons sat deliberately outside the drag handle). */
    val dragAndZoomModifier: Modifier = Modifier,
)

/** The chrome for the surface being composed. [dev.ccpocket.app.PocketShell] provides the live one around
 *  the shell; everything else (tests, screenshots, previews) gets the inert default. */
val LocalWindowChrome = staticCompositionLocalOf { DesktopWindowChrome() }

/**
 * Drag-to-move plus double-click-to-zoom for an undecorated window, as a plain Modifier.
 *
 * Lifted verbatim out of the old title bar, comments included — this is the part that took the tuning.
 * Anchor to the GLOBAL mouse position (AWT points), not the compose drag delta: deltas are physical px
 * (2× on Retina → the window outruns the cursor) and are measured relative to the moving window itself
 * (feedback loop → jitter). Grab the mouse-to-window offset on press and pin the window to it —
 * density-independent, no feedback.
 */
fun windowDragAndZoom(window: java.awt.Window, onToggleMax: () -> Unit): Modifier = Modifier
    .pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { onToggleMax() }) // double-click zoom, beside the drag detector
    }
    .pointerInput(Unit) {
        var grab: java.awt.Point? = null // mouse−window offset at press, in screen points
        detectDragGestures(
            onDragStart = {
                grab = java.awt.MouseInfo.getPointerInfo()?.location?.let {
                    java.awt.Point(it.x - window.x, it.y - window.y)
                }
            },
            onDragEnd = { grab = null },
            onDragCancel = { grab = null },
        ) { change, _ ->
            change.consume()
            val g = grab ?: return@detectDragGestures
            java.awt.MouseInfo.getPointerInfo()?.location?.let { m ->
                window.setLocation(m.x - g.x, m.y - g.y)
            }
        }
    }

@Composable
internal fun TrafficLights(onClose: () -> Unit, onMinimize: () -> Unit, onFullscreen: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Light(Color(0xFFED6A5E), onClose)
        Light(Color(0xFFF4BE4F), onMinimize)
        Light(Color(0xFF61C554), onFullscreen) // green = native fullscreen (not zoom — that's double-click)
    }
}

@Composable
private fun Light(color: Color, onClick: () -> Unit) {
    Box(Modifier.size(12.dp).clip(RoundedCornerShape(999.dp)).background(color).clickable(onClick = onClick))
}

/** Windows/Linux min · max · close, now rendered at the trailing edge of the RIGHTMOST chat sub-header. */
@Composable
internal fun WinControls(onMinimize: () -> Unit, onMax: () -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WinCell(Icons.Rounded.Remove, onMinimize)
        WinCell(Icons.Rounded.CropSquare, onMax)
        WinCell(Icons.Rounded.Close, onClose)
    }
}

@Composable
private fun WinCell(icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(width = 30.dp, height = 38.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Tok.tx2, modifier = Modifier.size(13.dp))
    }
}

// ── the four-control chrome cluster (desktop chrome v2) ──────────────────────────────────────────
// Sidebar-toggle + ‹ ›, rendered in the sidebar's control row while it is open and in the LEFTMOST chat
// sub-header once it is collapsed. ONE definition for both, so the two surfaces cannot drift: the whole
// point of the collapsed layout is that the same buttons moved, not that a second set appeared.

/** 28dp square hit target with the mock's rounded-6 raised plate on hover; [enabled] false takes the
 *  hover plate AND the tooltip away, so a disabled arrow can't look like it is offering something. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChromeButton(
    tooltip: String,
    shortcut: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    glyph: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val cell = @Composable {
        Box(
            Modifier.size(28.dp).clip(shape)
                .then(if (enabled) Modifier.hoverFill(shape, base = if (active) Tok.raised else Color.Transparent) else Modifier)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) { glyph() }
    }
    if (!enabled) { cell(); return }
    TooltipArea(tooltip = { TooltipCapsule(tooltip, shortcut) }, delayMillis = 500) { cell() }
}

/** The mock's tooltip: a raised hairline capsule carrying the label and its shortcut (mock:323-330). */
@Composable
private fun TooltipCapsule(label: String, shortcut: String) {
    Row(
        Modifier.clip(RoundedCornerShape(5.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = Tok.tx, fontFamily = Dk.ui, fontSize = 10.5.sp, style = tightCenter(10.5.sp))
        Text(shortcut, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 10.sp, style = tightCenter(10.sp))
    }
}

/** The panel-left glyph (mock:44-47): a 15×13 rounded outline with a rail line at x=5. [filled] shades the
 *  rail — the "sidebar is hidden, this brings it back" state, matching the mock's collapsed frame. */
@Composable
private fun PanelLeftGlyph(color: Color, filled: Boolean) {
    Canvas(Modifier.size(width = 15.dp, height = 13.dp)) {
        val s = 1.5.dp.toPx()
        val rail = 5.dp.toPx()
        if (filled) drawRect(color.copy(alpha = 0.22f), topLeft = Offset.Zero, size = Size(rail, size.height))
        // box-sizing: border-box — inset by half the stroke so the outline stays inside the 15×13 box
        drawRoundRect(
            color = color,
            topLeft = Offset(s / 2, s / 2),
            size = Size(size.width - s, size.height - s),
            cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
            style = Stroke(width = s),
        )
        drawLine(color, Offset(rail + s / 2, 0f), Offset(rail + s / 2, size.height), strokeWidth = s)
    }
}

/** ‹ / › — the mock's 6px square rotated 45° with two 1.5px borders, drawn directly as the chevron it is. */
@Composable
private fun ChevronGlyph(left: Boolean, color: Color) {
    Canvas(Modifier.size(width = 6.dp, height = 10.dp)) {
        val s = 1.5.dp.toPx()
        val arm = size.width - s // the rotated square's half-diagonal, minus the stroke it carries
        val tipX = if (left) s / 2 else size.width - s / 2
        val backX = if (left) size.width - s / 2 else s / 2
        val midY = size.height / 2
        drawLine(color, Offset(backX, midY - arm), Offset(tipX, midY), strokeWidth = s, cap = StrokeCap.Square)
        drawLine(color, Offset(tipX, midY), Offset(backX, midY + arm), strokeWidth = s, cap = StrokeCap.Square)
    }
}

/** Hide/show the sidebar — the cluster's first control (⌘\). */
@Composable
internal fun SidebarToggleButton(model: DesktopModel) {
    val collapsed = model.sidebarCollapsed
    ChromeButton(
        tooltip = stringResource(Res.string.tooltip_toggle_sidebar),
        shortcut = "⌘\\",
        onClick = { model.setSidebarCollapsed(!collapsed) },
        active = collapsed, // hidden sidebar = the button is the only way back, so it reads as "on"
    ) { PanelLeftGlyph(if (collapsed) Tok.tx else Tok.tx2, filled = collapsed) }
}

/** ‹ › over the session history (⌘[ / ⌘]) — browser semantics, disabled at either end of the trail. */
@Composable
internal fun SessionNavButtons(model: DesktopModel) {
    ChromeButton(
        tooltip = stringResource(Res.string.tooltip_prev_session),
        shortcut = "⌘[",
        onClick = { model.goBack() },
        enabled = model.canGoBack,
    ) { ChevronGlyph(left = true, color = if (model.canGoBack) Tok.tx2 else DISABLED_GLYPH) }
    ChromeButton(
        tooltip = stringResource(Res.string.tooltip_next_session),
        shortcut = "⌘]",
        onClick = { model.goForward() },
        enabled = model.canGoForward,
    ) { ChevronGlyph(left = false, color = if (model.canGoForward) Tok.tx2 else DISABLED_GLYPH) }
}

/** The mock's disabled chevron (#4A4E55): a muted step below [Tok.tx2], composited over the surface it
 *  sits on so the one value works in both themes rather than pinning a dark-only hex. */
private val DISABLED_GLYPH: Color @Composable get() = Tok.muted.copy(alpha = 0.55f)

/** The 1×16dp rule that separates the re-homed cluster from the session title (mock:211). */
@Composable
internal fun ChromeDivider() {
    Box(Modifier.size(width = 1.dp, height = 16.dp).background(Tok.hair))
}

/** The control row's search field — the ⌘K palette's entry point, wearing the mock's 26dp input shape.
 *  No ⌘K keycap: at a 260px sidebar the row leaves ~78px here, which fits the magnifier and the
 *  placeholder but not the hint, and the design's call is to drop the hint rather than shrink the field. */
@Composable
internal fun ChromeSearchField(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier.height(26.dp).clip(shape).background(Tok.base)
            .border(1.dp, if (hovered) Tok.tx2.copy(alpha = 0.35f) else Tok.hair, shape)
            .hoverable(src).clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = Tok.muted, modifier = Modifier.size(12.dp))
        Text(
            stringResource(Res.string.search), color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp,
            style = tightCenter(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The one surface that still needs a bar of its own: the pre-pairing [ConnectPanel].
 *
 * Everywhere else the chrome-v2 controls ride a real column — the sidebar's control row, a chat
 * sub-header — but before a daemon is paired there is no sidebar and no chat, so removing the title bar
 * would have left an UNDECORATED window with no way to move, zoom or close it. Deliberately bare: no
 * title, no search, no connection dot, because at this point there is no session and, by definition, no
 * link to report. Just the window buttons and a drag handle.
 */
@Composable
fun ConnectChromeRow() {
    val chrome = LocalWindowChrome.current
    Row(
        Modifier.fillMaxWidth().height(38.dp).background(Tok.base).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (chrome.mac && !chrome.fullscreen) TrafficLights(chrome.onClose, chrome.onMinimize, chrome.onToggleFullscreen)
        Box(Modifier.weight(1f).height(38.dp).then(chrome.dragAndZoomModifier)) // drag / double-click zoom
        if (!chrome.mac && !chrome.fullscreen) WinControls(chrome.onMinimize, chrome.onToggleMax, chrome.onClose)
    }
}

/**
 * Fullscreen exit affordance for Windows/Linux borderless fullscreen, which — unlike macOS — has no
 * auto-revealing system menu bar to reach for (issue #94). A hairline strip pinned to the top edge that
 * expands on hover into a clickable "Exit Full Screen" pill; Esc / ⌃⌘F / F11 exit too. macOS deliberately
 * doesn't draw this (its native menu bar reveals on the same top hover — no double-up).
 */
@Composable
fun FullscreenExitStrip(onExit: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(
        Modifier.fillMaxWidth().height(if (hovered) 34.dp else 5.dp).hoverable(src)
            .background(if (hovered) Tok.base else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (hovered) {
            Row(
                Modifier.clip(RoundedCornerShape(7.dp)).border(1.dp, Tok.hair, RoundedCornerShape(7.dp))
                    .clickable(onClick = onExit).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.FullscreenExit, null, tint = Tok.muted, modifier = Modifier.size(13.dp))
                Text(stringResource(Res.string.exit_fullscreen), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.5.sp, style = tightCenter(11.5.sp))
            }
        }
    }
}
