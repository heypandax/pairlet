package dev.ccpocket.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
actual fun OnAppForeground(action: () -> Unit) = onLifecycleEvent(Lifecycle.Event.ON_RESUME, action)

@Composable
actual fun OnAppBackground(action: () -> Unit) = onLifecycleEvent(Lifecycle.Event.ON_STOP, action)

@Composable
actual fun OnAppObscured(action: () -> Unit) = onLifecycleEvent(Lifecycle.Event.ON_PAUSE, action)

@Composable
private fun onLifecycleEvent(event: Lifecycle.Event, action: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(action)
    DisposableEffect(owner, event) {
        val observer = LifecycleEventObserver { _, e -> if (e == event) latest() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
