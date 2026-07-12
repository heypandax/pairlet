package dev.ccpocket.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar

/** Desktop selection goes through the native context menu, not a floating toolbar — pass through. */
@Composable
actual fun rememberPlatformTextToolbar(): TextToolbar = LocalTextToolbar.current
