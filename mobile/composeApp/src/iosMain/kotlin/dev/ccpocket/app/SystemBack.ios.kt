package dev.ccpocket.app

import androidx.compose.runtime.Composable

@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // no system back gesture on iOS — the in-app ← buttons own navigation
}
