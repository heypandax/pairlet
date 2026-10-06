package dev.ccpocket.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.uikit.LocalUIViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * iOS has no per-app status-bar tint: the bar's `.default` style — like the keyboard, alerts, share sheets
 * and QuickLook — simply follows the window's `userInterfaceStyle`. So the theme reaches the OS chrome by
 * pinning the window (`overrideUserInterfaceStyle`) for a forced LIGHT/DARK [mode], and RELEASING it
 * (Unspecified) for SYSTEM. The release is the whole point: Compose's `isSystemInDarkTheme()` reads this
 * very view controller's trait collection, so a pinned window feeds its own style back into the SYSTEM
 * resolution and the OS flip never arrives. That feedback loop was the bug while iOSApp.swift pinned the
 * window with `.preferredColorScheme(.dark)` (a leftover of the dark-only days): "follow system" stayed dark
 * through OS flips and relaunches alike, while LIGHT/DARK picks — which never consult the OS — kept working.
 */
@Composable
actual fun SystemBarAppearance(mode: ThemeMode, darkTheme: Boolean) {
    // `darkTheme` is unused here: the pin is keyed off the pick, since the resolved polarity of a SYSTEM pick
    // must never be written back into the window (see above).
    val controller = LocalUIViewController.current
    val style = mode.uiUserInterfaceStyle()
    // SideEffect: re-applied after every recomposition of PocketTheme (a mode change or an OS flip), before
    // the frame draws — so even frame 1 of a forced-DARK app on a light-mode phone gets light status-bar text.
    SideEffect { applyInterfaceStyle(style, hostingWindows(controller)) }
}

/** Pure seam (iosTest): SYSTEM inherits the OS, LIGHT/DARK pin the window. */
fun ThemeMode.uiUserInterfaceStyle(): UIUserInterfaceStyle = when (this) {
    ThemeMode.SYSTEM -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
    ThemeMode.LIGHT -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
    ThemeMode.DARK -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
}

/** Pins every window in [windows] to [style] (or releases it for Unspecified). Equal-value writes are skipped
 *  so an unchanged theme never churns UIKit's trait propagation. */
internal fun applyInterfaceStyle(style: UIUserInterfaceStyle, windows: Collection<UIWindow>) {
    for (window in windows) {
        if (window.overrideUserInterfaceStyle != style) window.overrideUserInterfaceStyle = style
    }
}

/** The window hosting the Compose view, plus every window of the connected scenes. The latter matters for the
 *  first composition: it runs from viewWillAppear, before the Compose view is in a window (`view.window` is
 *  still nil there), while the SwiftUI window already exists — without the scene walk a forced-DARK launch on
 *  a light-mode phone would keep dark status-bar text until the next theme recomposition. */
private fun hostingWindows(controller: UIViewController): Collection<UIWindow> {
    val windows = LinkedHashSet<UIWindow>()
    controller.view.window?.let(windows::add)
    for (scene in UIApplication.sharedApplication.connectedScenes) {
        val sceneWindows = (scene as? UIWindowScene)?.windows ?: continue
        for (window in sceneWindows) (window as? UIWindow)?.let(windows::add)
    }
    return windows
}
