package dev.ccpocket.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar

/** CMP 1.7.3's UIEditMenuInteraction keeps believing it is visible after a menu action dismisses
 *  it, so the re-show after 全选 no-ops and no copy button appears — see [ReshowingTextToolbar]. */
@Composable
actual fun rememberPlatformTextToolbar(): TextToolbar {
    val platform = LocalTextToolbar.current
    return remember(platform) { ReshowingTextToolbar(platform) }
}
