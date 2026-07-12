package dev.ccpocket.app

import androidx.compose.runtime.Composable

/** Desktop JVM sockets survive backgrounding, and the desktop root never mounts the lock gate; nothing to hook. */
@Composable
actual fun OnAppForeground(action: () -> Unit) {
}

@Composable
actual fun OnAppBackground(action: () -> Unit) {
}

@Composable
actual fun OnAppObscured(action: () -> Unit) {
}
