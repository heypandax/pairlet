package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.isQuestion

/** The two-pane content (sidebar + chat) plus the popover/modal overlays. Window chrome lives in [Main]. */
@Composable
fun DesktopApp(model: DesktopModel) {
    // sidebar sizing (issue #62): drag the divider to resize; drag it past the minimum (or double-click)
    // to snap-hide it to the edge, where a slim strip brings it back. Persisted across relaunches. This is
    // desktop-shell-local state — the seed/screenshot model keeps the default width and never collapses.
    var collapsed by remember { mutableStateOf(SecureStore.getString(K_SIDEBAR_COLLAPSED) == "1") }
    var sidebarW by remember {
        mutableStateOf(
            (SecureStore.getString(K_SIDEBAR_WIDTH)?.toFloatOrNull() ?: Dk.sidebarWidth.value)
                .coerceIn(SIDEBAR_MIN, SIDEBAR_MAX),
        )
    }
    val density = LocalDensity.current
    val setCollapsed = { v: Boolean -> collapsed = v; SecureStore.putString(K_SIDEBAR_COLLAPSED, if (v) "1" else "0") }
    Box(Modifier.fillMaxSize().background(Tok.base)) {
        Row(Modifier.fillMaxSize()) {
            if (collapsed) {
                SidebarRevealStrip { setCollapsed(false) }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Tok.hair)) // pane splitter
            } else {
                Sidebar(model, width = sidebarW.dp)
                SidebarResizeHandle(
                    onDrag = { dxPx ->
                        val next = sidebarW + with(density) { dxPx.toDp().value }
                        if (next < SIDEBAR_COLLAPSE_AT) setCollapsed(true)
                        else sidebarW = next.coerceIn(SIDEBAR_MIN, SIDEBAR_MAX)
                    },
                    // persist once per gesture, not per drag tick — putString rewrites a whole file
                    onDragEnd = { if (!collapsed) SecureStore.putString(K_SIDEBAR_WIDTH, sidebarW.toString()) },
                    onCollapse = { setCollapsed(true) },
                )
            }
            val watch = model.watch
            if (watch == null) {
                ChatPane(model, Modifier.weight(1f))
            } else {
                // split: chat with the focused session while watching a second one read-only (Fleet ⑥)
                ChatPane(model, Modifier.weight(1f), focused = true)
                Box(Modifier.width(1.dp).fillMaxHeight().background(Tok.hair))
                WatchPane(watch, model, Modifier.weight(1f))
            }
            // workflow orchestration (issue #106): a persistent ~360dp docked panel — the chat stays
            // fully usable beside it (docked beats overlay, per the workflow-view handoff)
            val dockedWf = model.dockedWorkflowRunId?.let { model.workflowRuns[it] }
            if (dockedWf != null) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(Tok.hair))
                WorkflowPanel(model, dockedWf)
            }
        }
        if (model.showNewSession) {
            // anchored under the sidebar's New-session row (header 48 + row 32)
            Overlay(onDismiss = { model.showNewSession = false }, alignment = Alignment.TopStart, padding = PaddingValues(start = 14.dp, top = 84.dp)) {
                NewSessionPopover(model.newSessionSeed ?: "~/", model.defaultAgent, model.defaultMode) { dir, agent, mode -> model.newSession(dir, agent, mode) }
            }
        }
        if (model.showQuickActions) {
            // anchored under the chat header's ⋯ (header row ≈44 + meta line ≈30)
            Overlay(onDismiss = { model.showQuickActions = false }, alignment = Alignment.TopEnd, padding = PaddingValues(end = 14.dp, top = 44.dp)) {
                QuickActionsPopover(model) { model.showQuickActions = false }
            }
        }
        if (model.showTray) {
            Overlay(onDismiss = { model.showTray = false }, alignment = Alignment.TopEnd, padding = PaddingValues(end = 12.dp, top = 4.dp)) {
                TrayPopover()
            }
        }
        if (model.switcherOpen) {
            // anchored under the sidebar header — the fleet lives here now, not in the sidebar body
            Overlay(onDismiss = { model.switcherOpen = false }, alignment = Alignment.TopStart, padding = PaddingValues(start = 10.dp, top = 52.dp)) {
                MachineSwitcher(model)
            }
        }
        if (model.showAttention) {
            // anchored under the sidebar bell — cross-machine approvals without leaving the session
            Overlay(onDismiss = { model.showAttention = false }, alignment = Alignment.TopStart, padding = PaddingValues(start = 14.dp, top = 52.dp)) {
                AttentionPopover(model)
            }
        }
        if (model.palette != null) {
            Overlay(onDismiss = { model.palette = null }, alignment = Alignment.TopCenter, padding = PaddingValues(top = 80.dp), scrim = true) {
                CommandPalette(model) { model.palette = null }
            }
        }
        if (model.showChanges) {
            // the two-pane diff browser (changed-files v2) — same centered-scrim language as settings
            Overlay(onDismiss = { model.showChanges = false }, alignment = Alignment.Center, padding = PaddingValues(0.dp), scrim = true) {
                ChangesOverlay(model) { model.showChanges = false }
            }
        }
        if (model.showSettings) {
            Overlay(onDismiss = { model.showSettings = false }, alignment = Alignment.Center, padding = PaddingValues(0.dp), scrim = true) {
                SettingsModal(model) { model.showSettings = false }
            }
        }
        if (model.showPermissionModal) {
            // the focused modal is for permission gates only; an AskUserQuestion docks inline in ChatPane (#57)
            model.ask?.takeIf { !it.isQuestion }?.let { ask ->
                FocusedModal(
                    computer = model.activeComputer?.name ?: "your computer",
                    ask = ask, agent = model.chatAgent, workdir = model.chatWorkdir, branch = model.chatBranch,
                    onAllow = { rem -> model.resolve(allow = true, remember = rem) },
                    onDeny = { model.resolve(allow = false, remember = false) },
                    onDismiss = { model.dismissAsk() },
                )
            }
        }
    }
}

// sidebar sizing bounds (issue #62). COLLAPSE_AT < MIN so dragging the divider left of the minimum snaps
// the sidebar hidden rather than stopping at the floor.
private const val SIDEBAR_MIN = 220f
private const val SIDEBAR_MAX = 460f
private const val SIDEBAR_COLLAPSE_AT = 170f
private const val K_SIDEBAR_WIDTH = "desktop_sidebar_width"
private const val K_SIDEBAR_COLLAPSED = "desktop_sidebar_collapsed"

private val resizeCursor = PointerIcon(java.awt.Cursor(java.awt.Cursor.E_RESIZE_CURSOR))

/** The sidebar/chat divider as a drag handle (issue #62): drag to resize, double-click to hide. A 5dp hit
 *  strip with a centered 1px hairline keeps the resting look; the pointer flips to a horizontal-resize cursor. */
@Composable
private fun SidebarResizeHandle(onDrag: (Float) -> Unit, onDragEnd: () -> Unit, onCollapse: () -> Unit) {
    Box(
        Modifier.width(5.dp).fillMaxHeight()
            .pointerHoverIcon(resizeCursor)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onCollapse() }) }
            .pointerInput(Unit) { detectDragGestures(onDragEnd = { onDragEnd() }) { change, drag -> change.consume(); onDrag(drag.x) } },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(Tok.hair))
    }
}

/** When the sidebar is hidden to the edge (issue #62), a slim strip that brings it back — click anywhere. */
@Composable
private fun SidebarRevealStrip(onExpand: () -> Unit) {
    Column(
        Modifier.width(16.dp).fillMaxHeight().background(Tok.surface).clickable(onClick = onExpand),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Icon(Icons.Rounded.KeyboardArrowRight, "Show sidebar", tint = Tok.muted, modifier = Modifier.width(15.dp).height(15.dp))
    }
}

/** A dismiss-on-scrim overlay that anchors [content] at [alignment]; clicks on the content are swallowed. */
@Composable
private fun Overlay(
    onDismiss: () -> Unit,
    alignment: Alignment,
    padding: PaddingValues,
    scrim: Boolean = false,
    content: @Composable () -> Unit,
) {
    val base = Modifier.fillMaxSize()
    Box((if (scrim) base.background(Dk.backdrop.copy(alpha = 0.5f)) else base).noRippleClick(onDismiss)) {
        Box(Modifier.align(alignment).padding(padding).noRippleClick {}) { content() }
    }
}

@Composable
private fun Modifier.noRippleClick(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return clickable(interactionSource = src, indication = null, onClick = onClick)
}
