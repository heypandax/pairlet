package dev.ccpocket.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.window.rememberWindowState
import dev.ccpocket.app.theme.PocketTheme
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import kotlinx.coroutines.delay
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

/**
 * The OS menu-bar presence (issue #151, direction 1 — "menubar-presence" handoff): a persistent status
 * glyph in the macOS menu bar (Windows: the notification area) that reads the fleet at a glance, plus the
 * anchored [TrayPopover] to approve/deny and jump back WITHOUT raising the main window. Lives at
 * `application` scope in [dev.ccpocket.app.main], so it outlives window minimize — "the moment an agent
 * needs you, it comes find you".
 *
 * Mechanics: an AWT [java.awt.TrayIcon] (macOS AWT puts it in the menu bar) redrawn per five-state spec
 * ([menuBarIcon] → [renderMenuBarImage]); a left click toggles an undecorated always-on-top transparent
 * Compose [Window] packed to the popover's content, anchored under the click (macOS) or above it
 * (bottom taskbars), dismissed on focus loss / Esc; ⌘⏎ raises the main window. The popover renders the
 * SAME [TrayPopover] the title-bar dot shows in-window — one surface, promoted to the OS layer.
 *
 * Headless / unsupported trays (Linux without a tray, CI) compose to nothing, so every other platform
 * behavior is unchanged.
 */
@Composable
internal fun MenuBarExtra(model: DesktopModel, onActivateWindow: () -> Unit) {
    val supported = remember {
        runCatching { !GraphicsEnvironment.isHeadless() && java.awt.SystemTray.isSupported() }.getOrDefault(false)
    }
    if (!supported) return

    // ── five-state machine: snapshot fold + the time-boxed done-flash bit ──
    val snapshot = menuBarSnapshot(model)
    var prev by remember { mutableStateOf<MenuBarSnapshot?>(null) }
    var flashNonce by remember { mutableStateOf(0) }
    LaunchedEffect(snapshot) {
        if (startsDoneFlash(prev, snapshot)) flashNonce++
        prev = snapshot
    }
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(flashNonce) {
        if (flashNonce > 0) { flashing = true; delay(MENUBAR_DONE_FLASH_MS); flashing = false }
    }
    val spec = menuBarIcon(snapshot, flashing)
    // repaint trigger for OS appearance flips: tray clicks bump it so the raster below re-asks skiko
    // (deliberately NOT isSystemInDarkTheme() — see menuBarIsDark for the process-cached-default trap)
    var appearancePing by remember { mutableStateOf(0) }

    // keep the running-elapsed clock fed while the popover is closed, so reopening shows honest ages
    val runningKeys = model.running.map { (m, p) -> runningKey(m, p) }
    LaunchedEffect(runningKeys) { TrayRunningSince.observe(runningKeys, System.currentTimeMillis()) }

    // ── the AWT tray icon ──
    var anchor by remember { mutableStateOf<TrayAnchor?>(null) }
    var openedAt by remember { mutableStateOf(0L) }
    var closedAt by remember { mutableStateOf(0L) }
    // mousePressed AND actionPerformed can fire for one click depending on platform; and clicking the icon
    // while the popover is open steals its focus first (focus-loss closes it) — both debounce here so a
    // single click is a single toggle instead of a flicker.
    val toggle: (Int, Int) -> Unit = toggle@{ x: Int, y: Int ->
        appearancePing++ // every click re-rasterizes — the user's own repaint path after an appearance flip
        val now = System.currentTimeMillis()
        if (anchor != null) {
            if (now - openedAt > 350) { anchor = null; closedAt = now }
        } else if (now - closedAt > 350) {
            anchor = trayAnchor(x, y, screenConfigAt(x, y))
            openedAt = now
        }
    }
    val trayIcon = remember {
        java.awt.TrayIcon(menuBarImage(MenuBarIconSpec(MenuBarKind.IDLE), darkMenuBar = menuBarIsDark())).apply {
            isImageAutoSize = false
            toolTip = "cc-pocket"
        }
    }
    DisposableEffect(Unit) {
        // tray mouse/action events arrive on the AWT EDT — the same thread Compose Desktop composes on,
        // so writing the anchor state here is safe
        val mouse = object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (e.button == java.awt.event.MouseEvent.BUTTON1) toggle(e.xOnScreen, e.yOnScreen)
            }
        }
        val action = java.awt.event.ActionListener {
            val p = java.awt.MouseInfo.getPointerInfo()?.location
            toggle(p?.x ?: 0, p?.y ?: 0)
        }
        trayIcon.addMouseListener(mouse)
        trayIcon.addActionListener(action)
        val added = runCatching { java.awt.SystemTray.getSystemTray().add(trayIcon) }.isSuccess
        onDispose {
            trayIcon.removeMouseListener(mouse)
            trayIcon.removeActionListener(action)
            if (added) runCatching { java.awt.SystemTray.getSystemTray().remove(trayIcon) }
        }
    }
    // every redraw asks the OS afresh; an appearance flip alone lands on the next state change or click
    LaunchedEffect(spec, appearancePing) { trayIcon.image = menuBarImage(spec, darkMenuBar = menuBarIsDark()) }

    // ── the anchored popover window ──
    val a = anchor
    if (a != null) {
        Window(
            onCloseRequest = { anchor = null },
            state = rememberWindowState(
                // pre-pack guess; placePopover() corrects it the moment the window knows its real size
                position = WindowPosition.Absolute((a.centerX - POPOVER_W / 2).dp, (if (a.fromTop) a.y else a.y - 480).dp),
                size = DpSize.Unspecified, // pack to the popover's content
            ),
            undecorated = true,
            transparent = true,
            resizable = false,
            alwaysOnTop = true,
            title = "cc-pocket",
            onPreviewKeyEvent = { e ->
                when {
                    e.type == KeyEventType.KeyDown && e.key == Key.Escape -> { anchor = null; true }
                    e.type == KeyEventType.KeyDown && (e.isMetaPressed || e.isCtrlPressed) && e.key == Key.Enter -> {
                        anchor = null; onActivateWindow(); true
                    }
                    else -> false
                }
            },
        ) {
            // anchor under the glyph (or above a bottom taskbar); re-place whenever packing/content resizes
            DisposableEffect(a) {
                val w = window
                val l = object : java.awt.event.ComponentAdapter() {
                    override fun componentResized(e: java.awt.event.ComponentEvent?) { placePopover(w, a) }
                }
                placePopover(w, a)
                w.addComponentListener(l)
                onDispose { w.removeComponentListener(l) }
            }
            // click-away dismissal — the OS-native popover behavior
            DisposableEffect(Unit) {
                val w = window
                val l = object : java.awt.event.WindowAdapter() {
                    override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                        closedAt = System.currentTimeMillis()
                        anchor = null
                    }
                }
                w.addWindowFocusListener(l)
                w.requestFocus()
                onDispose { w.removeWindowFocusListener(l) }
            }
            // resolving approvals / sessions ending changes the content height — repack so the card hugs it
            val rowCounts = model.attention.size to model.running.size
            LaunchedEffect(rowCounts) { window.pack() }
            PocketTheme(mode = model.themeMode) {
                TrayPopover(
                    model,
                    onOpenMain = { anchor = null; onActivateWindow() },
                    showPointer = a.fromTop,
                    elevated = true,
                    keyHint = true,
                )
            }
        }
    }
}

// ── popover anchoring (AWT screen points) ────────────────────────────────────────────────────────

internal const val POPOVER_W = 392 // 360dp card + the elevated shadow gutters

/** Where the popover hangs: the glyph's screen X, the edge Y to grow from, and which way it grows. */
internal data class TrayAnchor(val centerX: Int, val y: Int, val fromTop: Boolean, val screen: java.awt.Rectangle)

/** Anchor for a tray click at ([clickX], [clickY]): menu-bar trays drop the popover below the bar, bottom
 *  taskbars (Windows) grow it upward from above the bar. */
internal fun trayAnchor(clickX: Int, clickY: Int, gc: GraphicsConfiguration): TrayAnchor {
    val b = gc.bounds
    val ins = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    val fromTop = clickY < b.y + b.height / 2
    val y = if (fromTop) b.y + maxOf(ins.top, 22) + 2 else b.y + b.height - ins.bottom - 6
    return TrayAnchor(clickX, y, fromTop, b)
}

private fun screenConfigAt(x: Int, y: Int): GraphicsConfiguration {
    val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
    return ge.screenDevices.map { it.defaultConfiguration }.firstOrNull { it.bounds.contains(x, y) }
        ?: ge.defaultScreenDevice.defaultConfiguration
}

/** Center the (now measured) window on the glyph, clamped to the screen, growing down or up per anchor. */
internal fun placePopover(w: java.awt.Window, a: TrayAnchor) {
    val minX = a.screen.x + 8
    val x = (a.centerX - w.width / 2).coerceIn(minX, maxOf(minX, a.screen.x + a.screen.width - w.width - 8))
    val y = if (a.fromTop) a.y else a.y - w.height
    w.setLocation(x, y)
}

// ── the glyph raster (template-style, five states) ───────────────────────────────────────────────
// Geometry is menubar.jsx's, 1pt = 1 viewBox unit: chevron (4,4.5)→(8.3,8.8)→(4,13.1) + underscore
// (9.6,13.2)→(14,13.2) in an 18×18 box, stroke 1.9 round (hollow offline: 1.4 @ 50%). Colour is spent
// ONLY on needs-you (terracotta dot + count) and the done tick (green) — everything else is monochrome
// against the menu bar, white on a dark bar / near-black on a light one (AWT has no template images —
// [menuBarIsDark] is how, and how honestly, the host tracks the bar's appearance).

private val MB_ACCENT = Color(0xD9, 0x77, 0x57) // Tok dark-palette accent — identical in both palettes' bars
private val MB_OK = Color(0x4F, 0xB4, 0x77)
private const val MB_H = 18 // pt — the menu-bar content box

/**
 * The menu bar's appearance, asked of skiko fresh on every call — deliberately NOT
 * [androidx.compose.foundation.isSystemInDarkTheme]. At `application` scope no window provides
 * LocalSystemTheme, so that composable reads the composition local's DEFAULT — a static default computed
 * once (it queries skiko) and then cached for the whole process. Flip macOS appearance mid-run and the
 * glyph would keep the old contrast (near-black strokes on a now-dark bar ≈ invisible) until relaunch.
 *
 * skiko's [currentSystemTheme] getter is an uncached JNI query (macOS: NSUserDefaults' AppleInterfaceStyle;
 * verified live — flipping appearance mid-process flips the returned value), so every raster pass gets the
 * bar as it is NOW. Honest limit: AWT surfaces no appearance-change event, so a flip alone shows on the
 * next redraw — any state change or a tray click — not instantly.
 * UNKNOWN or a failed query counts as a light bar, the same mapping the composition local's default uses.
 */
private fun menuBarIsDark(): Boolean =
    runCatching { currentSystemTheme == SystemTheme.DARK }.getOrDefault(false)

/** The tray image at 1x+2x so retina menu bars stay crisp (AWT picks the variant per backing scale). */
internal fun menuBarImage(spec: MenuBarIconSpec, darkMenuBar: Boolean): java.awt.Image =
    BaseMultiResolutionImage(renderMenuBarImage(spec, darkMenuBar, 1), renderMenuBarImage(spec, darkMenuBar, 2))

/** One five-state frame at [scale]× (pure Java2D — unit-tested headlessly). */
internal fun renderMenuBarImage(spec: MenuBarIconSpec, darkMenuBar: Boolean, scale: Int): BufferedImage {
    val s = scale.toFloat()
    val fg = if (darkMenuBar) Color.WHITE else Color(0x1C, 0x1D, 0x1F)
    val count = spec.count.takeIf { it > 0 && spec.kind != MenuBarKind.IDLE && spec.kind != MenuBarKind.OFFLINE }
        ?.let { if (it > 9) "9+" else "$it" }
    val font = trayMonoFont(11f * s)
    val fm = SHARED_METRICS.getFontMetrics(font)
    val textW = count?.let { fm.stringWidth(it) } ?: 0
    val gap = (4 * scale)
    val w = when (spec.kind) {
        MenuBarKind.IDLE, MenuBarKind.OFFLINE -> MB_H * scale
        MenuBarKind.DONE_FLASH -> MB_H * scale + gap + 14 * scale
        MenuBarKind.RUNNING -> MB_H * scale + gap + textW
        MenuBarKind.NEEDS_YOU -> MB_H * scale + gap + 6 * scale + gap + textW
    }
    val h = MB_H * scale
    val img = BufferedImage(maxOf(w, 1), h, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        // the glyph
        val glyphAlpha = when (spec.kind) {
            MenuBarKind.IDLE -> 0.85f
            MenuBarKind.RUNNING, MenuBarKind.DONE_FLASH -> 0.9f
            MenuBarKind.NEEDS_YOU -> 1f
            MenuBarKind.OFFLINE -> 0.5f
        }
        g.color = withAlpha(fg, glyphAlpha)
        g.stroke = BasicStroke((if (spec.kind == MenuBarKind.OFFLINE) 1.4f else 1.9f) * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Path2D.Float().apply { moveTo(4f * s, 4.5f * s); lineTo(8.3f * s, 8.8f * s); lineTo(4f * s, 13.1f * s) })
        g.draw(Path2D.Float().apply { moveTo(9.6f * s, 13.2f * s); lineTo(14f * s, 13.2f * s) })
        // the companions
        var x = (MB_H * scale + gap).toFloat()
        val baseline = (h + fm.ascent - fm.descent) / 2f
        when (spec.kind) {
            MenuBarKind.RUNNING -> if (count != null) {
                g.font = font
                g.color = withAlpha(fg, 0.75f)
                g.drawString(count, x, baseline)
            }
            MenuBarKind.NEEDS_YOU -> {
                g.color = MB_ACCENT
                g.fill(Ellipse2D.Float(x, h / 2f - 3f * s, 6f * s, 6f * s))
                x += 6f * s + gap
                if (count != null) {
                    g.font = font
                    g.drawString(count, x, baseline)
                }
            }
            MenuBarKind.DONE_FLASH -> {
                // the tick, menubar.jsx's 14pt check scaled from its 18-unit viewBox
                val u = 14f / 18f * s
                g.color = MB_OK
                g.stroke = BasicStroke(2.2f * u, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val cy = (h - 14f * s) / 2f
                g.draw(
                    Path2D.Float().apply {
                        moveTo(x + 3.5f * u, cy + 9.5f * u)
                        lineTo(x + 7f * u, cy + 13f * u)
                        lineTo(x + 14.5f * u, cy + 4.5f * u)
                    },
                )
            }
            else -> {}
        }
    } finally {
        g.dispose()
    }
    return img
}

private fun withAlpha(c: Color, alpha: Float): Color = Color(c.red, c.green, c.blue, (alpha * 255).toInt().coerceIn(0, 255))

/** Metrics scratchpad — FontMetrics without allocating a Graphics per frame. */
private val SHARED_METRICS: java.awt.Graphics2D by lazy { BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics() }

/** The count's type: the bundled JetBrains Mono (same face as the popover), falling back to the JVM mono. */
private val TRAY_MONO_BASE: Font? by lazy {
    runCatching {
        MenuBarKind::class.java.classLoader.getResourceAsStream("font/JetBrainsMono-SemiBold.ttf")?.use {
            Font.createFont(Font.TRUETYPE_FONT, it)
        }
    }.getOrNull()
}

private fun trayMonoFont(size: Float): Font =
    TRAY_MONO_BASE?.deriveFont(Font.PLAIN, size) ?: Font(Font.MONOSPACED, Font.BOLD, size.toInt())
