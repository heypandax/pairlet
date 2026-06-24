package dev.ccpocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.desktop.ConnectPanel
import dev.ccpocket.app.desktop.DesktopApp
import dev.ccpocket.app.desktop.DkTitleBar
import dev.ccpocket.app.desktop.RepoDesktopModel
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import java.awt.Dimension

/**
 * Desktop entry point — the two-pane "mission control": one host driving Claude Code / Codex on another.
 * Backed by the live [PocketRepository] via [RepoDesktopModel]; shows [ConnectPanel] until the relay is up,
 * then the [DesktopApp] shell. Undecorated so the app paints its own title bar.
 */
fun main() = application {
    val mac = System.getProperty("os.name").lowercase().contains("mac")
    val windowState = rememberWindowState(size = DpSize(1180.dp, 760.dp), position = WindowPosition(Alignment.Center))
    Window(
        onCloseRequest = ::exitApplication,
        title = "cc-pocket",
        state = windowState,
        undecorated = true,
        resizable = true,
    ) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(720, 480) }
        val scope = rememberCoroutineScope()
        val repo = remember { PocketRepository(scope) }
        val model = remember { RepoDesktopModel(repo) }
        LaunchedEffect(Unit) { if (repo.paired.value != null) repo.startRelay() } // paired → connect straight away
        val connected by repo.sessionActive

        PocketTheme {
            Column(Modifier.fillMaxSize().background(Tok.base)) {
                DkTitleBar(
                    mac = mac,
                    onClose = ::exitApplication,
                    onMinimize = { windowState.isMinimized = true },
                    onToggleMax = {
                        windowState.placement =
                            if (windowState.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
                    },
                    onTray = { model.showTray = !model.showTray },
                )
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (connected) DesktopApp(model) else ConnectPanel(repo)
                }
            }
        }
    }
}
