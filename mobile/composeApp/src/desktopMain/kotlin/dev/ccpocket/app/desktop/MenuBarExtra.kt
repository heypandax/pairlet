package dev.ccpocket.app.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.tray_exit_app
import dev.ccpocket.app.resources.tray_open_app
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsConfiguration
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
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
 * Windows additionally gets a right-click menu drawn by the OS ([trayContextMenu]) and a transparent
 * flyout shell so the 8dp corners can actually read ([trayWindowChrome]) — both issue #322.
 *
 * Headless / unsupported trays (Linux without a tray, CI) compose to nothing, so every other platform
 * behavior is unchanged.
 */
@Composable
internal fun MenuBarExtra(
    model: DesktopModel,
    onActivateWindow: () -> Unit,
    onExitApplication: (() -> Unit)? = null,
    onAvailabilityChanged: (Boolean) -> Unit = {},
    // issue #292: Windows 换 flyout 载体（角锚定 + [WinTrayFlyout]）。参数化而不是就地读 os.name，
    // 是为了让宿主一眼看见分叉在哪；mac/Linux 路径一字未动。
    isWindows: Boolean = hostIsWindows(),
) {
    val supported = remember {
        runCatching { !GraphicsEnvironment.isHeadless() && java.awt.SystemTray.isSupported() }.getOrDefault(false)
    }
    if (!supported) return
    val reportAvailability by rememberUpdatedState(onAvailabilityChanged)

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
            val gc = screenConfigAt(x, y)
            anchor = if (isWindows) winFlyoutAnchor(gc) else trayAnchor(x, y, gc)
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
        reportAvailability(added)
        onDispose {
            reportAvailability(false)
            trayIcon.removeMouseListener(mouse)
            trayIcon.removeActionListener(action)
            if (added) runCatching { java.awt.SystemTray.getSystemTray().remove(trayIcon) }
        }
    }

    // ── 右键：Windows 的原生上下文菜单（issue #322） ──
    // stringResource 是 composable，effect 里调不了，所以文案先在组合作用域取出来，再当 key 用
    // （换语言 → 菜单重建；托盘图标本身不重挂，避免语言开关顺带让通知区图标闪一下）。
    val openLabel = stringResource(Res.string.tray_open_app)
    val exitLabel = stringResource(Res.string.tray_exit_app)
    val menuSpec = trayContextMenu(isWindows, openLabel, exitLabel, canExit = onExitApplication != null)
    // 菜单项活得比一次组合长，回调用 rememberUpdatedState 取最新的一份，避免钉死首次组合的闭包
    val activate by rememberUpdatedState(onActivateWindow)
    val exitApp by rememberUpdatedState(onExitApplication)
    DisposableEffect(menuSpec) {
        val items = menuSpec ?: return@DisposableEffect onDispose { }
        // PopupMenu 在 headless 下构造即抛 HeadlessException；这里已被 supported 挡住，兜底照 file 里
        // 其它 AWT 调用的写法用 runCatching，起不来就当没有右键菜单，左键那条路不受影响
        val built = runCatching {
            val menu = java.awt.PopupMenu()
            val wired = items.map { item ->
                val mi = java.awt.MenuItem(item.label)
                val l = java.awt.event.ActionListener {
                    anchor = null // 右键选中即收起左键浮层——两条路不该同时占着屏幕
                    closedAt = System.currentTimeMillis()
                    when (item.action) {
                        TrayMenuAction.OPEN_MAIN -> activate()
                        TrayMenuAction.EXIT_APP -> exitApp?.invoke()
                    }
                }
                mi.addActionListener(l)
                menu.add(mi)
                mi to l
            }
            trayIcon.popupMenu = menu
            menu to wired
        }.getOrNull()
        onDispose {
            if (built != null) {
                val (menu, wired) = built
                trayIcon.popupMenu = null // 先摘引用，AWT 才肯放掉 popup 的 isTrayIconPopup 标记
                wired.forEach { (mi, l) -> mi.removeActionListener(l) }
                menu.removeAll()
            }
        }
    }
    // every redraw asks the OS afresh; an appearance flip alone lands on the next state change or click
    LaunchedEffect(spec, appearancePing) { trayIcon.image = menuBarImage(spec, darkMenuBar = menuBarIsDark()) }

    // ── the anchored popover window ──
    // 外壳（透明与否 / 谁画投影）在 issue #322 里分叉，探一次逐像素透明能力就够——同一台机器上它不会变
    val chrome = remember(isWindows) { trayWindowChrome(isWindows, perPixelTranslucencySupported()) }
    val a = anchor
    if (a != null) {
        Window(
            onCloseRequest = { anchor = null },
            state = rememberWindowState(
                // pre-pack guess; placePopover() corrects it the moment the window knows its real size
                position = if (a.corner) {
                    WindowPosition.Absolute((a.workRight - WIN_FLYOUT_GAP - POPOVER_W).dp, (a.workBottom - WIN_FLYOUT_GAP - 480).dp)
                } else {
                    WindowPosition.Absolute((a.centerX - POPOVER_W / 2).dp, (if (a.fromTop) a.y else a.y - 480).dp)
                },
                size = DpSize.Unspecified, // pack to the popover's content
            ),
            undecorated = true,
            // OPAQUE on purpose ON macOS: skiko 0.8.18 (CMP 1.7.3) crashes creating the Metal device for a
            // TRANSPARENT window on macOS 26 "Tahoe" — EXC_BREAKPOINT in AppKit _NSWindowSetShadowProperties
            // via MetalRedrawer.createMetalDevice (JetBrains CMP-7352 / compose-multiplatform#3171). The
            // main window is undecorated+opaque and renders fine on the same OS, so this popover matches it:
            // the card fills an opaque root (below) and leans on the native window shadow instead of the
            // transparent-gutter self-shadow. Restore transparency (for rounded corners) after a skiko bump.
            // 那个崩溃是 **macOS 独有的**（Metal/AppKit 路径），Windows 的 DirectX 后端不受影响，所以
            // issue #322 在这里分叉：Windows 走透明。必须分叉是因为 DWM 只给「有 caption 的窗口」补圆角，
            // 不透明的无边框窗口在 Win11 上永远是方角——浮层自己画的 8dp 圆角会被那块方角底盖掉，
            // 发仔复报的「圆角没有正确呈现」就是这个。见 [trayWindowChrome]。
            transparent = chrome.transparent,
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
            // 透明外壳时窗口比卡片大一圈（自绘投影的留白），落点要把那一圈扣回去——见 [winFlyoutWindowOrigin]
            val gutter = if (chrome.selfShadow) WIN_FLYOUT_SHADOW_GUTTER else 0
            DisposableEffect(a, gutter) {
                val w = window
                val l = object : java.awt.event.ComponentAdapter() {
                    override fun componentResized(e: java.awt.event.ComponentEvent?) { placePopover(w, a, gutter) }
                }
                placePopover(w, a, gutter)
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
                // 两种外壳，见 [trayWindowChrome]：Windows 走透明窗口（圆角/投影归浮层自己），
                // mac/Linux 走不透明窗口（圆角画不出来，但避开了 skiko 在 Tahoe 上的必崩）。
                if (a.corner) {
                    // issue #292 — Windows flyout。入场按稿子上升 22dp + 淡入 250ms ease-out，但用
                    // graphicsLayer 而不是 AnimatedVisibility：后者在动画期间内容不参与测量，窗口会先
                    // pack 成 0 再长开，和贴角锚定打架。出场淡出**故意不做**——托盘窗口是即建即销毁的，
                    // 关闭那一刻窗口已经没了，没有可以淡的东西。
                    val intro = remember { Animatable(0f) }
                    LaunchedEffect(Unit) { intro.animateTo(1f, tween(250, easing = LinearOutSlowInEasing)) }
                    val flyout: @Composable () -> Unit = {
                        WinTrayFlyout(
                            model,
                            onOpenMain = { anchor = null; onActivateWindow() },
                            onExitApp = onExitApplication?.let { exit -> { anchor = null; exit() } },
                            modifier = Modifier.graphicsLayer {
                                alpha = intro.value
                                translationY = (1f - intro.value) * 22.dp.toPx()
                            },
                            // 透明外壳下窗口只是一块透明画布：圆角、描边、投影全由浮层自己画
                            elevated = chrome.selfShadow,
                            onRelayout = { window.pack() },
                        )
                    }
                    // issue #322：透明化之后**不能**再包这层不透明底——Tok.surface 会铺满整扇方角窗口，
                    // 正好把浮层自己的 8dp 圆角填回直角。逐像素透明拿不到时（虚拟机 / 远程桌面 / 老驱动，
                    // 见 [perPixelTranslucencySupported]）退回旧的不透明底，至少不比改动前差。
                    if (chrome.transparent) flyout() else Box(Modifier.background(Tok.surface)) { flyout() }
                } else {
                    // Opaque root so the borderless window's square corners blend into the card colour; the
                    // OS supplies the drop shadow an opaque window gets for free. flat (elevated=false) card,
                    // no transparent-gutter self-shadow, no pointer triangle (it needs transparency to read).
                    Box(Modifier.background(Tok.raised)) {
                        TrayPopover(
                            model,
                            onOpenMain = { anchor = null; onActivateWindow() },
                            onExitApp = onExitApplication?.let { exit -> { anchor = null; exit() } },
                            showPointer = false,
                            elevated = false,
                            keyHint = true,
                        )
                    }
                }
            }
        }
    }
}

// ── popover anchoring (AWT screen points) ────────────────────────────────────────────────────────

internal const val POPOVER_W = 360 // the opaque card's width (placePopover re-centers on the real packed size)

/** Windows 浮层与工作区边缘的间距（issue #292 稿子：距右屏边 12px、任务栏上方 12px）。 */
internal const val WIN_FLYOUT_GAP = 12

/**
 * 透明外壳下，浮层自绘投影要用的一圈透明外扩（issue #322）。
 *
 * 投影画在内容盒**之外**，而承载窗口是 pack 到内容的，不留这一圈就等于把投影整块裁掉——透明白开了。
 * 取值**故意等于** [WIN_FLYOUT_GAP]：窗口整体外扩 12、落点再往回缩 12（[winFlyoutWindowOrigin]），
 * 于是卡片与工作区边缘仍是稿子要的 12px，而窗口本身刚好贴齐工作区角、一个像素都不压到任务栏上——
 * 透明像素照样吃鼠标事件，压过去就会吞掉任务栏上的点击。
 */
internal const val WIN_FLYOUT_SHADOW_GUTTER = WIN_FLYOUT_GAP

/** 宿主是否 Windows —— [MenuBarExtra] 的载体分叉默认值（Main.kt 也各自算过一次，两处含义相同）。 */
internal fun hostIsWindows(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("windows")

// ── 托盘右键菜单（issue #322） ────────────────────────────────────────────────────────────────────

/** 右键菜单一项对应的出口。 */
internal enum class TrayMenuAction { OPEN_MAIN, EXIT_APP }

/** 右键菜单的一项：[label] 已本地化，[action] 是它落到哪个出口。 */
internal data class TrayMenuItem(val action: TrayMenuAction, val label: String)

/**
 * 托盘图标右键该弹什么 —— **纯函数**，因为另一半（[java.awt.PopupMenu]）在 headless 下构造即抛，
 * 测不了；把「挂不挂、挂哪几项」的决策搬到 AWT 之外，至少这一半是可断言的。
 *
 * `null` = **一个 popupMenu 都不挂**。issue #322 的边界写死「不改 macOS／Linux 行为」：mac 菜单栏图标
 * 的右键由系统给（等同左键那套），Linux 各家托盘实现也自带右键语义，硬塞一个 AWT 菜单只是多一层
 * 不属于那个平台的东西。
 *
 * Windows 反过来——通知区图标右键弹菜单是刻在肌肉记忆里的，而 #322 之前 [MenuBarExtra] 只监听
 * BUTTON1，右键**完全没有出口**（发仔复报的「右键无响应」）。走 AWT 原生 [java.awt.PopupMenu] 而不是
 * 自己监听 BUTTON3 再弹一扇 Compose 窗口：Win32 的托盘右键菜单由系统绘制、定位和消失，自绘的那种
 * 在多屏 + 任务栏靠侧边时必然错位，还得自己复刻「点别处就关」。
 *
 * [canExit] 为假时只留「打开」：[dev.ccpocket.app.main] 只在 Windows 传 onExitApplication（#189，
 * mac/Linux 的关窗语义不同），菜单里不该长出一个点了没反应的退出项。
 */
internal fun trayContextMenu(
    isWindows: Boolean,
    openLabel: String,
    exitLabel: String,
    canExit: Boolean,
): List<TrayMenuItem>? {
    if (!isWindows) return null
    val items = mutableListOf(TrayMenuItem(TrayMenuAction.OPEN_MAIN, openLabel))
    if (canExit) items += TrayMenuItem(TrayMenuAction.EXIT_APP, exitLabel)
    return items
}

// ── 浮层外壳：透明 / 圆角 / 投影归谁（issue #322） ────────────────────────────────────────────────

/** 承载窗口的外壳形态：[transparent] 交给 Compose 的 Window，[selfShadow] 交给 [WinTrayFlyout]。 */
internal data class TrayWindowChrome(val transparent: Boolean, val selfShadow: Boolean)

/**
 * 谁来画圆角和投影。
 *
 * - **Windows + 支持逐像素透明** → 透明窗口，圆角/描边/投影全归浮层自己（[WinTrayFlyout] 的
 *   `elevated`）。DWM 只给带 caption 的窗口补圆角，不透明的无边框窗口在 Win11 上永远是方角，
 *   浮层画的 8dp 圆角会被那块方角底盖掉——这就是 #322 复报的圆角失真。
 * - **macOS / Linux** → 一字不动的不透明窗口。macOS 那边不是审美选择而是硬约束：skiko 0.8.18 建
 *   **透明** Compose 窗口在 macOS 26 "Tahoe" 上必崩（CMP-7352，见 [MenuBarExtra] 里的长注释）。
 * - **Windows 但拿不到逐像素透明**（虚拟机 / 远程桌面 / 老驱动）→ 退回不透明。宁可维持改动前的
 *   方角，也不能让 `setBackground(alpha<255)` 在窗口创建时抛异常把整个 App 带崩。
 */
internal fun trayWindowChrome(isWindows: Boolean, translucencySupported: Boolean): TrayWindowChrome =
    if (isWindows && translucencySupported) TrayWindowChrome(transparent = true, selfShadow = true)
    else TrayWindowChrome(transparent = false, selfShadow = false)

/** 本机窗口系统是否支持逐像素透明。查不到 / 抛异常一律当不支持——见 [trayWindowChrome] 的退路。 */
internal fun perPixelTranslucencySupported(): Boolean = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
}.getOrDefault(false)

/**
 * Where the popover hangs: the glyph's screen X, the edge Y to grow from, and which way it grows.
 *
 * [corner] 是 Windows 的角锚定模式（issue #292）：忽略 [centerX]，改贴工作区右下角。工作区边界预先
 * 折进 [workRight] / [workBottom]（= 屏幕边界减任务栏 inset），所以真正的落点计算
 * （[winFlyoutOrigin]）是纯函数，无需显示器也能测——任务栏在上/左/右时 inset 同样成立。
 */
internal data class TrayAnchor(
    val centerX: Int,
    val y: Int,
    val fromTop: Boolean,
    val screen: java.awt.Rectangle,
    val corner: Boolean = false,
    val workRight: Int = screen.x + screen.width,
    val workBottom: Int = screen.y + screen.height,
)

/** Anchor for a tray click at ([clickX], [clickY]): menu-bar trays drop the popover below the bar, bottom
 *  taskbars (Windows) grow it upward from above the bar. */
internal fun trayAnchor(clickX: Int, clickY: Int, gc: GraphicsConfiguration): TrayAnchor {
    val b = gc.bounds
    val ins = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    val fromTop = clickY < b.y + b.height / 2
    val y = if (fromTop) b.y + maxOf(ins.top, 22) + 2 else b.y + b.height - ins.bottom - 6
    return TrayAnchor(clickX, y, fromTop, b)
}

/**
 * Windows 角锚定（issue #292）：**不跟随托盘图标的 x**。Win11 的通知区浮层（网络/音量/电池）一律贴
 * 工作区右下角，跟着图标横向游走的气泡在 Windows 上读起来就是一扇孤儿小窗。取点击所在屏的
 * [GraphicsConfiguration]，用 [Toolkit.getScreenInsets] 拿任务栏 inset 换算出工作区。
 */
internal fun winFlyoutAnchor(gc: GraphicsConfiguration): TrayAnchor {
    val b = gc.bounds
    val ins = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    return TrayAnchor(
        centerX = b.x + b.width, y = b.y + b.height - ins.bottom, fromTop = false, screen = b,
        corner = true, workRight = b.x + b.width - ins.right, workBottom = b.y + b.height - ins.bottom,
    )
}

/** 角锚定的**卡片**左上角：工作区右缘 −12 −宽、任务栏顶 −12 −高，并夹在屏幕内（超大浮层不会跑出屏外）。 */
internal fun winFlyoutOrigin(a: TrayAnchor, w: Int, h: Int): Pair<Int, Int> =
    (a.workRight - WIN_FLYOUT_GAP - w).coerceAtLeast(a.screen.x + WIN_FLYOUT_GAP) to
        (a.workBottom - WIN_FLYOUT_GAP - h).coerceAtLeast(a.screen.y + WIN_FLYOUT_GAP)

/**
 * 角锚定的**窗口**左上角（issue #322）。透明外壳下窗口比卡片四周各大 [gutter]（自绘投影的留白），
 * 所以先按卡片尺寸算贴角落点、再整体外扩一圈；[gutter] = 0 时与 [winFlyoutOrigin] 完全等价。
 *
 * 桌面端窗口几何 1 AWT 点 = 1 dp，所以这里的 [gutter] 和 [WinTrayFlyout] 里那圈 padding 是同一个数。
 */
internal fun winFlyoutWindowOrigin(a: TrayAnchor, winW: Int, winH: Int, gutter: Int): Pair<Int, Int> {
    val (x, y) = winFlyoutOrigin(a, winW - 2 * gutter, winH - 2 * gutter)
    return (x - gutter) to (y - gutter)
}

private fun screenConfigAt(x: Int, y: Int): GraphicsConfiguration {
    val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
    return ge.screenDevices.map { it.defaultConfiguration }.firstOrNull { it.bounds.contains(x, y) }
        ?: ge.defaultScreenDevice.defaultConfiguration
}

/** Center the (now measured) window on the glyph, clamped to the screen, growing down or up per anchor.
 *  角锚定（Windows）走 [winFlyoutWindowOrigin]，与点击点无关；[gutter] 是透明外壳自绘投影的留白。 */
internal fun placePopover(w: java.awt.Window, a: TrayAnchor, gutter: Int = 0) {
    if (a.corner) {
        val (cx, cy) = winFlyoutWindowOrigin(a, w.width, w.height, gutter)
        w.setLocation(cx, cy)
        return
    }
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
