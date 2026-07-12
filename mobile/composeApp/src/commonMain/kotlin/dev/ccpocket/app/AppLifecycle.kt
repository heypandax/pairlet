package dev.ccpocket.app

import androidx.compose.runtime.Composable

/**
 * Invokes [action] every time the app returns to the foreground (resume/become-active).
 * Used to reconnect the daemon link that iOS kills while the app is suspended.
 * Desktop never suspends sockets — its actual is a no-op.
 */
@Composable
expect fun OnAppForeground(action: () -> Unit)

/**
 * Invokes [action] when the app is fully backgrounded (Android ON_STOP / iOS didEnterBackground).
 * App Lock (issue #109) arms its auto-lock timer here. Keyed on the *background* signal, not resign/pause,
 * so presenting the biometric sheet (which only resigns-active) can never re-arm a re-lock of its own gate.
 * Desktop is a no-op.
 */
@Composable
expect fun OnAppBackground(action: () -> Unit)

/**
 * Invokes [action] the instant the app is about to be obscured (Android ON_PAUSE / iOS willResignActive) —
 * earlier than [OnAppBackground], before the OS captures the app-switcher snapshot. App Lock draws its opaque
 * privacy cover here so the switcher thumbnail never leaks session content. Desktop is a no-op.
 */
@Composable
expect fun OnAppObscured(action: () -> Unit)
