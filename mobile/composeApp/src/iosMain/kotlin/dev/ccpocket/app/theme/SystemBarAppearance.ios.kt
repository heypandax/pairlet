package dev.ccpocket.app.theme

import androidx.compose.runtime.Composable

/**
 * No-op: iOS status-bar appearance is managed on the UIViewController side, and issue #117 is Android-only.
 * Keeping the shared [PocketTheme] call site platform-agnostic (the bar icons don't change on iOS here).
 */
@Composable
actual fun SystemBarAppearance(darkTheme: Boolean) {
    // iOS status bar is owned natively; nothing to tint from Compose for issue #117
}
