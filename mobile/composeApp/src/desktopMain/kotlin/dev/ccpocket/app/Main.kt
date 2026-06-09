package dev.ccpocket.app

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.ccpocket.app.ui.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "cc-pocket",
        state = rememberWindowState(size = DpSize(420.dp, 860.dp)),
    ) {
        val scope = rememberCoroutineScope()
        App(scope)
    }
}
