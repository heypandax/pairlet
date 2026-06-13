package dev.ccpocket.app

import androidx.compose.runtime.Composable

@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // no system back button on desktop — the in-app ← buttons own navigation
}
