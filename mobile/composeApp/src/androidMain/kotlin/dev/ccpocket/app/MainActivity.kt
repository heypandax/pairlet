package dev.ccpocket.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.fragment.app.FragmentActivity
import dev.ccpocket.app.lock.initAppLock
import dev.ccpocket.app.push.CcPocketMessagingService
import dev.ccpocket.app.secure.initSecureStore
import dev.ccpocket.app.share.initFileExport
import dev.ccpocket.app.telemetry.initTelemetry
import dev.ccpocket.app.ui.App
import dev.ccpocket.app.voice.initVoice

// FragmentActivity (not ComponentActivity) so androidx.biometric BiometricPrompt can host its dialog (issue #109).
class MainActivity : FragmentActivity() {
    private val requestNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge so Compose owns ALL insets (the root Column pads systemBars + ime itself).
        // Without this, pre-15 devices keep decorFitsSystemWindows=true and the window manager
        // pans/resizes the window for the keyboard ON TOP of imePadding() -> composer floats a
        // full keyboard-height above the IME. Bars stay transparent; the FOREGROUND icon color is
        // (re)driven from the resolved theme by SystemBarAppearance (issue #117) — this initial pick
        // just matches the DARK default so frame 1 is right before the theme resolves.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        initSecureStore(this)
        initAppLock(this) // App Lock (issue #109): the biometric prompt needs this FragmentActivity as host
        initTelemetry(this)
        initVoice(this)
        initUrlOpener(this)
        initFileExport(this)
        setupNotifications()
        setContent {
            val scope = rememberCoroutineScope()
            App(scope)
        }
        routeFromIntent(intent) // cold start: launched by tapping a task-complete notification
    }

    /** Warm start: the activity is already running when a notification is tapped. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeFromIntent(intent)
    }

    /** A tapped task-complete notification carries `wd`/`sid` extras (FCM `data`) → open that session. */
    private fun routeFromIntent(intent: Intent?) {
        val wd = intent?.getStringExtra("wd") ?: return
        val sid = intent.getStringExtra("sid") ?: return
        PushRoute.open(wd, sid)
    }

    /** Create the push channel up front and request POST_NOTIFICATIONS (Android 13+). The token is only
     *  sent to the relay once notifications are on AND a relay link attaches (see PocketRepository). */
    private fun setupNotifications() {
        getSystemService(NotificationManager::class.java)?.let(CcPocketMessagingService::ensureChannel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
