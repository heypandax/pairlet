package dev.ccpocket.app.theme

import androidx.compose.runtime.Composable

/**
 * Aligns the OS chrome around the app — status/navigation bar FOREGROUND (icon + text) color, and on iOS the
 * keyboard, alerts and sheets too — with the theme [PocketTheme] just resolved. Called from the mode overload
 * only, so the real app roots drive it while the boolean overload used by tests/previews stays inert.
 *
 *  - Android (issue #117): the bars are already transparent edge-to-edge, so only the icon polarity needs to
 *    track [darkTheme] — DARK → light icons, LIGHT → dark icons — otherwise the hardcoded light icons vanish
 *    against the light palette's off-white base.
 *  - iOS: there is no per-app bar tint; the status bar's `.default` style follows the window's interface
 *    style, so the window is pinned for a forced LIGHT/DARK [mode] and released (Unspecified) for SYSTEM.
 *    Releasing it is what lets `isSystemInDarkTheme()` see the OS flip at all — a pinned window feeds its own
 *    style back into Compose, which is why "follow system" used to stay dark on iOS through OS flips and
 *    relaunches (the Swift host pinned the whole window dark from the dark-only days).
 *  - Desktop: no-op (no system bars on a desktop window).
 *
 * [mode] is the persisted pick and [darkTheme] its resolution against the OS; both are passed because the two
 * platforms key off different ones.
 */
@Composable
expect fun SystemBarAppearance(mode: ThemeMode, darkTheme: Boolean)
