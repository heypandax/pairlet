package dev.ccpocket.app

import androidx.compose.runtime.Composable

/**
 * Routes the platform back gesture/button into in-app navigation while [enabled]; when disabled
 * the system default applies (Android: leave the app). Only Android has a system back — the
 * iOS/desktop actuals are no-ops (those platforms navigate with the on-screen ← buttons).
 */
@Composable
expect fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit)
