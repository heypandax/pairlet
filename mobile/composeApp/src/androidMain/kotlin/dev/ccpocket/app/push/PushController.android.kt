package dev.ccpocket.app.push

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import com.google.firebase.messaging.FirebaseMessaging
import dev.ccpocket.app.voice.VoiceHost
import dev.ccpocket.observability.Diagnostics
import dev.ccpocket.observability.ErrorCode
import dev.ccpocket.observability.ErrorPath
import dev.ccpocket.observability.Stage

/**
 * Android push registration via FCM. The initial token comes from [FirebaseMessaging]; refreshes
 * arrive through [CcPocketMessagingService.onNewToken] → [deliver]. Both tag the token "fcm".
 *
 * The POST_NOTIFICATIONS runtime permission is still requested exactly where it always was
 * (MainActivity.setupNotifications, at launch) — [requestToken] deliberately never prompts here, so
 * the [prompt] flag only means something on iOS. Changing Android's ask-at-launch timing is a separate
 * product decision, and doing it as a side effect of a registration retry would be the worst version
 * of it: a dialog appearing minutes later, attached to nothing the user just did.
 */
actual object PushController {
    @Volatile private var cb: ((PushToken) -> Unit)? = null
    @Volatile private var last: PushToken? = null

    actual fun start(onToken: (PushToken) -> Unit) {
        cb = onToken
        last?.let { onToken(it) } // replay a token that arrived before start()
    }

    actual fun requestToken(prompt: Boolean) {
        // MULTI-VENDOR SEAM: choose the channel here (by Build.MANUFACTURER / build flavor) and tag the
        // token's platform accordingly ("xiaomi"/"huawei"/…) once a vendor SDK is integrated; FCM today.
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { t -> deliver(PushToken("fcm", t)) }
                .addOnFailureListener(::onTokenFailure)
        }.onFailure {
            // no Play Services / no Firebase config: a token is never coming on this device, and the
            // coordinator must learn that instead of burning its retry budget on it
            onRegistrationFailed?.invoke(PushRegistrationFailure.UNSUPPORTED)
        }
    }

    private fun onTokenFailure(error: Throwable) {
        // isError=false as before: a phone without a reachable FCM is an environment fact, not a defect
        // of this build — the retry budget, not the error stream, is what handles it.
        Diagnostics.report(ErrorPath.PUSH, Stage.REQUEST, ErrorCode.UNAVAILABLE, error, isError = false)
        onRegistrationFailed?.invoke(PushRegistrationFailure.NETWORK)
    }

    /**
     * Whether notifications are allowed, from the OS's own switch — which covers BOTH the runtime
     * POST_NOTIFICATIONS grant and a later toggle-off in system Settings. Never prompts.
     *
     * Uses the platform [NotificationManager.areNotificationsEnabled] rather than
     * `NotificationManagerCompat`: identical answer at this module's minSdk, and it adds no dependency.
     */
    actual fun readAuthorization(cb: (PushAuthorization) -> Unit) {
        val enabled = runCatching {
            VoiceHost.appContext.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled()
        }.getOrNull()
        cb(when (enabled) {
            true -> PushAuthorization.AUTHORIZED
            false -> PushAuthorization.DENIED
            null -> PushAuthorization.UNKNOWN // no context/service yet — never read as a refusal
        })
    }

    actual fun openNotificationSettings() {
        runCatching {
            val ctx = VoiceHost.appContext
            ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    // started from application context, outside any activity task
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    actual var onRegistrationFailed: ((PushRegistrationFailure) -> Unit)? = null

    /** Fed by the initial token fetch and by [CcPocketMessagingService.onNewToken] on refresh. */
    fun deliver(token: PushToken) {
        last = token
        cb?.invoke(token)
    }
}
