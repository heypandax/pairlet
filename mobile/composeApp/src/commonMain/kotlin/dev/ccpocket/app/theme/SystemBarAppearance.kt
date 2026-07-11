package dev.ccpocket.app.theme

import androidx.compose.runtime.Composable

/**
 * Aligns the system status/navigation bar FOREGROUND (icon + text) color with the resolved app theme
 * (issue #117): DARK → light icons, LIGHT → dark icons. [PocketTheme] calls this with the theme it just
 * resolved, so a live LIGHT/DARK/SYSTEM switch flips the icons in place (no relaunch). The bar backgrounds
 * are already transparent edge-to-edge, so only the icon polarity needs to track the theme — otherwise the
 * hardcoded light icons vanish against the light palette's off-white base.
 *
 * Only Android exposes controllable system bars in this stack; the iOS and desktop actuals are no-ops
 * (iOS status-bar styling is owned natively; desktop windows have no system bars).
 */
@Composable
expect fun SystemBarAppearance(darkTheme: Boolean)
