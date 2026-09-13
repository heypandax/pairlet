package dev.ccpocket.app.ui

import androidx.compose.runtime.Composable

/** A desktop window is freely resizable, so its width alone keeps deciding — the rule #334 shipped. */
@Composable
internal actual fun platformLayoutDeviceClass(): LayoutDeviceClass = LayoutDeviceClass.LARGE_SCREEN
