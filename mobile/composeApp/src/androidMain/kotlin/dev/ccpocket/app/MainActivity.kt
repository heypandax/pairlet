package dev.ccpocket.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.rememberCoroutineScope
import dev.ccpocket.app.secure.initSecureStore
import dev.ccpocket.app.telemetry.initTelemetry
import dev.ccpocket.app.ui.App
import dev.ccpocket.app.voice.initVoice

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initSecureStore(this)
        initTelemetry(this)
        initVoice(this)
        setContent {
            val scope = rememberCoroutineScope()
            App(scope)
        }
    }
}
