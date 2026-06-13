package dev.ccpocket.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.rememberCoroutineScope
import dev.ccpocket.app.secure.initSecureStore
import dev.ccpocket.app.telemetry.initTelemetry
import dev.ccpocket.app.ui.App
import dev.ccpocket.app.voice.initVoice

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge so Compose owns ALL insets (the root Column pads systemBars + ime itself).
        // Without this, pre-15 devices keep decorFitsSystemWindows=true and the window manager
        // pans/resizes the window for the keyboard ON TOP of imePadding() -> composer floats a
        // full keyboard-height above the IME. The app is always dark -> force light bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        initSecureStore(this)
        initTelemetry(this)
        initVoice(this)
        setContent {
            val scope = rememberCoroutineScope()
            App(scope)
        }
    }
}
