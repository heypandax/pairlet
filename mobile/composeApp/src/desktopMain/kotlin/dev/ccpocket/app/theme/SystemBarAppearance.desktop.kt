package dev.ccpocket.app.theme

import androidx.compose.runtime.Composable

/** No-op: desktop windows have no OS status/navigation bar to tint (issue #117 is Android-only). */
@Composable
actual fun SystemBarAppearance(darkTheme: Boolean) {
    // no system bars on desktop
}
