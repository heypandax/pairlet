package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.theme.Tok

/** The two-pane content (sidebar + chat) plus the popover/modal overlays. Window chrome lives in [Main]. */
@Composable
fun DesktopApp(model: DesktopModel) {
    Box(Modifier.fillMaxSize().background(Tok.base)) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(model)
            Box(Modifier.width(1.dp).fillMaxHeight().background(Tok.hair)) // pane splitter
            val watch = model.watch
            if (watch == null) {
                ChatPane(model, Modifier.weight(1f))
            } else {
                // split: chat with the focused session while watching a second one read-only (Fleet ⑥)
                ChatPane(model, Modifier.weight(1f), focused = true)
                Box(Modifier.width(1.dp).fillMaxHeight().background(Tok.hair))
                WatchPane(watch, model, Modifier.weight(1f))
            }
        }
        if (model.showNewSession) {
            // anchored under the sidebar's New-session row (header 48 + row 32)
            Overlay(onDismiss = { model.showNewSession = false }, alignment = Alignment.TopStart, padding = PaddingValues(start = 14.dp, top = 84.dp)) {
                NewSessionPopover(model.newSessionSeed ?: "~/") { dir, agent, mode -> model.newSession(dir, agent, mode) }
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
        if (model.showSettings) {
            Overlay(onDismiss = { model.showSettings = false }, alignment = Alignment.Center, padding = PaddingValues(0.dp), scrim = true) {
                SettingsModal(model) { model.showSettings = false }
            }
        }
        if (model.showPermissionModal) {
            model.ask?.let { ask ->
                FocusedModal(
                    computer = model.activeComputer?.name ?: "your computer",
                    ask = ask, agent = model.chatAgent, workdir = model.chatWorkdir, branch = model.chatBranch,
                    onAllow = { model.resolve(allow = true, remember = false) },
                    onDeny = { model.resolve(allow = false, remember = false) },
                    onDismiss = { model.dismissAsk() },
                )
            }
        }
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
