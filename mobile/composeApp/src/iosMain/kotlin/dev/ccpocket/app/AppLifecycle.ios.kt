package dev.ccpocket.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification

@Composable
actual fun OnAppForeground(action: () -> Unit) = onAppNotification(UIApplicationDidBecomeActiveNotification, action)

@Composable
actual fun OnAppBackground(action: () -> Unit) = onAppNotification(UIApplicationDidEnterBackgroundNotification, action)

@Composable
actual fun OnAppObscured(action: () -> Unit) = onAppNotification(UIApplicationWillResignActiveNotification, action)

@Composable
private fun onAppNotification(name: String?, action: () -> Unit) {
    val latest by rememberUpdatedState(action)
    DisposableEffect(name) {
        val token = NSNotificationCenter.defaultCenter.addObserverForName(
            name = name,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> latest() }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(token) }
    }
}
