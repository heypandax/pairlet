package dev.ccpocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.desktop.AddComputerModal
import dev.ccpocket.app.desktop.ConnectPanel
import dev.ccpocket.app.desktop.DesktopApp
import dev.ccpocket.app.desktop.DesktopNotify
import dev.ccpocket.app.desktop.DkTitleBar
import dev.ccpocket.app.desktop.PaletteScope
import dev.ccpocket.app.desktop.RepoDesktopModel
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Toolkit

private const val K_WIN_BOUNDS = "desktop_window_bounds" // "x,y,w,h[,zoomed]" — restored on the next launch

/**
 * Desktop entry point — the two-pane "mission control": one host driving Claude Code / Codex on another.
 * Backed by the live [PocketRepository] via [RepoDesktopModel]; shows [ConnectPanel] until the relay is up,
 * then the [DesktopApp] shell. Undecorated so the app paints its own title bar. ⌘K / Ctrl+K opens the
 * command palette (Esc closes it) — handled at the window level so it works regardless of focus.
 */
fun main() = application {
    // GA4 Measurement Protocol backend for desktop analytics — resolves credentials (env / ga4.properties)
    // up front so a missing config logs at launch. No-op when unconfigured. Every PocketRepository event
    // (pair / connect / conn_failed / session / prompt / approval) fires automatically since desktop drives
    // the same repo; AppLaunch is the one exception — the shared App() composable that fires it on mobile
    // isn't used here, so we track it explicitly below.
    remember { dev.ccpocket.app.telemetry.initDesktopTelemetry() }
    val mac = System.getProperty("os.name").lowercase().contains("mac")
    // restore last window bounds (the off-screen guard below re-centers if displays changed since);
    // a 5th field marks "was zoomed" — the first four stay the PRE-zoom bounds so un-zoom has a target
    val savedBounds = remember {
        SecureStore.getString(K_WIN_BOUNDS)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size >= 4 }
    }
    val savedZoomed = remember { savedBounds?.getOrNull(4) == 1f }
    val windowState = rememberWindowState(
        size = savedBounds?.let { DpSize(it[2].dp, it[3].dp) } ?: DpSize(1180.dp, 760.dp),
        position = savedBounds?.let { WindowPosition(it[0].dp, it[1].dp) } ?: WindowPosition(Alignment.Center),
    )
    val scope = rememberCoroutineScope()
    val repo = remember { PocketRepository(scope) }
    // fleet: one live link per paired computer (primary + pinned satellites) — the grouped sidebar,
    // attention popover, and palette badges read across all of them
    remember { dev.ccpocket.app.data.FleetCoordinator(scope, repo).also { dev.ccpocket.app.data.FleetRuntime.coordinator = it; it.start() } }
    val model = remember { RepoDesktopModel(repo) }
    LaunchedEffect(Unit) {
        dev.ccpocket.app.telemetry.Telemetry.track(dev.ccpocket.app.telemetry.TelEvent.AppLaunch)
        if (repo.paired.value != null) repo.startRelay() // paired → connect straight away
    }
    val connected by repo.sessionActive

    Window(
        onCloseRequest = ::exitApplication,
        title = "CC Pocket",
        // taskbar/window icon on Windows & Linux and the dev-run (gradle :run) Dock icon on macOS;
        // the packaged macOS Dock icon comes from the bundle's .icns (build.gradle.kts iconFile)
        icon = androidx.compose.ui.res.painterResource("app-icon.png"),
        state = windowState,
        undecorated = true,
        resizable = true,
        onPreviewKeyEvent = { e ->
            val mod = e.isMetaPressed || e.isCtrlPressed
            // ⌘1–⌘9 jump to PINNED sessions; ⌘0 opens the machine switcher, where bare digits pick
            // a machine while it's open ("Sidebar Redesign" board)
            val digit = when (e.key) {
                Key.One -> 0; Key.Two -> 1; Key.Three -> 2; Key.Four -> 3; Key.Five -> 4
                Key.Six -> 5; Key.Seven -> 6; Key.Eight -> 7; Key.Nine -> 8
                else -> -1
            }
            when {
                e.type == KeyEventType.KeyDown && mod && e.key == Key.K && connected -> { model.palette = PaletteScope.ALL; true }
                e.type == KeyEventType.KeyDown && mod && e.key == Key.N && connected -> { model.openNewSession(); true }
                e.type == KeyEventType.KeyDown && mod && e.key == Key.R && connected -> { model.refresh(); true }
                e.type == KeyEventType.KeyDown && mod && e.key == Key.Zero && connected -> { model.switcherOpen = !model.switcherOpen; true }
                e.type == KeyEventType.KeyDown && digit >= 0 && connected && model.switcherOpen -> {
                    model.jumpMachine(digit); model.switcherOpen = false; true
                }
                e.type == KeyEventType.KeyDown && mod && digit >= 0 && connected -> { model.jumpPin(digit); true }
                e.type == KeyEventType.KeyDown && mod && e.key == Key.Comma && connected -> { model.showSettings = true; true }
                e.type == KeyEventType.KeyDown && e.key == Key.Escape && model.anyOverlayOpen -> { model.dismissOverlays(); true }
                else -> false
            }
        },
    ) {
        // zoom (green traffic light): fill the CURRENT screen's usable bounds (menu bar / Dock excluded),
        // second click restores. Manual, because WindowPlacement.Maximized is unreliable for undecorated
        // windows on macOS. Non-null == currently zoomed, holding the bounds to restore.
        var zoomRestore by remember { mutableStateOf<Rectangle?>(null) }
        LaunchedEffect(Unit) {
            // record the window bounds (debounced) so the next launch reopens exactly here. While zoomed,
            // persist the PRE-zoom bounds plus a zoomed flag: the next launch re-zooms itself (below) and
            // the green button still knows what to restore to.
            snapshotFlow { windowState.size to windowState.position }.collectLatest { (s, p) ->
                delay(400)
                val z = zoomRestore
                when {
                    z != null -> SecureStore.putString(K_WIN_BOUNDS, "${z.x},${z.y},${z.width},${z.height},1")
                    p is WindowPosition.Absolute -> SecureStore.putString(
                        K_WIN_BOUNDS,
                        "${p.x.value.toInt()},${p.y.value.toInt()},${s.width.value.toInt()},${s.height.value.toInt()},0",
                    )
                }
            }
        }
        // turn-finished signals (issue: no explicit "done" cue): while the window is unfocused, each
        // completed turn fires a macOS notification and bumps the Dock badge; focus clears the badge.
        var windowFocused by remember { mutableStateOf(true) }
        var unseenDone by remember { mutableStateOf(0) }
        DisposableEffect(Unit) {
            val l = object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) { windowFocused = true }
                override fun windowLostFocus(e: java.awt.event.WindowEvent?) { windowFocused = false }
            }
            window.addWindowFocusListener(l)
            repo.onTurnFinished = { title, preview ->
                if (!windowFocused) {
                    unseenDone++
                    DesktopNotify.badge(unseenDone)
                    DesktopNotify.notify(title, preview ?: "Turn complete")
                }
            }
            onDispose {
                window.removeWindowFocusListener(l)
                repo.onTurnFinished = null
            }
        }
        LaunchedEffect(windowFocused) {
            if (windowFocused && unseenDone > 0) { unseenDone = 0; DesktopNotify.badge(0) }
        }
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(720, 480)
            // multi-display: Alignment.Center places over the VIRTUAL bounds union, which in an L-shaped
            // arrangement can be a dead zone no screen covers (observed at (5688,-1176) — the window was
            // unfindable). If we spawned off every screen, re-center on the default one.
            val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            val onSomeScreen = ge.screenDevices.any { it.defaultConfiguration.bounds.intersects(window.bounds) }
            if (!onSomeScreen) {
                val b = ge.defaultScreenDevice.defaultConfiguration.bounds
                window.setLocation(b.x + (b.width - window.width) / 2, b.y + (b.height - window.height) / 2)
            }
            // quit while zoomed → come back zoomed: re-apply the zoom on the (possibly re-centered) screen,
            // keeping the restored pre-zoom bounds as the un-zoom target
            if (savedZoomed) {
                zoomRestore = window.bounds
                window.bounds = usableScreenBounds(window)
            }
        }
        // appearance (issue #63): PocketTheme resolves the persisted mode against the OS, so a SYSTEM pick
        // tracks a live OS light/dark flip and Settings' setThemeMode() re-themes the whole shell.
        PocketTheme(mode = repo.themeMode.value) {
            androidx.compose.runtime.CompositionLocalProvider(
                dev.ccpocket.app.ui.LocalPathOpener provides dev.ccpocket.app.desktop.DesktopPathOpener(),
            ) {
            Column(Modifier.fillMaxSize().background(Tok.base)) {
                DkTitleBar(
                    mac = mac,
                    onClose = ::exitApplication,
                    onMinimize = { windowState.isMinimized = true },
                    onToggleMax = {
                        val restore = zoomRestore
                        if (restore != null) {
                            window.bounds = restore
                            zoomRestore = null
                        } else {
                            zoomRestore = window.bounds
                            window.bounds = usableScreenBounds(window)
                        }
                    },
                    onTray = { model.showTray = !model.showTray },
                    onSearch = { model.palette = PaletteScope.ALL },
                )
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (connected) DesktopApp(model) else ConnectPanel(repo)
                    // "Add computer" pairs a new daemon in a modal over the live shell (no disconnect)
                    if (model.showAddComputer) AddComputerModal(repo) { model.showAddComputer = false }
                }
            }
            }
        }
    }
}

/** The window's CURRENT screen minus menu bar / Dock — what the zoom (green light) fills. Manual, because
 *  WindowPlacement.Maximized is unreliable for undecorated windows on macOS. */
private fun usableScreenBounds(window: java.awt.Window): Rectangle {
    val gc = window.graphicsConfiguration
    val b = gc.bounds
    val ins = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    return Rectangle(b.x + ins.left, b.y + ins.top, b.width - ins.left - ins.right, b.height - ins.top - ins.bottom)
}
