package dev.ccpocket.app

import androidx.compose.runtime.Composable

/** Desktop JVM sockets survive backgrounding; nothing to hook. */
@Composable
actual fun OnAppForeground(action: () -> Unit) {
}
