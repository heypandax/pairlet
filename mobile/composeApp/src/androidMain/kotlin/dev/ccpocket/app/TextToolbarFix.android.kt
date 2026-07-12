package dev.ccpocket.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar

/** Android's ActionMode survives its own menu actions (Select All invalidates the visible toolbar
 *  in place), so the iOS reshow fix would only add a teardown flicker here — pass through. */
@Composable
actual fun rememberPlatformTextToolbar(): TextToolbar = LocalTextToolbar.current
