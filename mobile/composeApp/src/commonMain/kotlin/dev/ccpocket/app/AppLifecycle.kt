package dev.ccpocket.app

import androidx.compose.runtime.Composable

/**
 * Invokes [action] every time the app returns to the foreground (resume/become-active).
 * Used to reconnect the daemon link that iOS kills while the app is suspended.
 * Desktop never suspends sockets — its actual is a no-op.
 */
@Composable
expect fun OnAppForeground(action: () -> Unit)
