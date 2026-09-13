package dev.ccpocket.app.ui

import androidx.compose.runtime.Composable
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPhone

/**
 * The interface idiom, fixed for the life of the process: an iPhone is a phone in every orientation, while an
 * iPad — or the iPad app running on a Mac — is a large screen whose window width keeps deciding.
 */
@Composable
internal actual fun platformLayoutDeviceClass(): LayoutDeviceClass =
    if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPhone) LayoutDeviceClass.PHONE
    else LayoutDeviceClass.LARGE_SCREEN
