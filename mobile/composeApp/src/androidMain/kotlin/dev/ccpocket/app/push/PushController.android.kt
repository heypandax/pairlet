package dev.ccpocket.app.push

import com.google.firebase.messaging.FirebaseMessaging

/**
 * Android push registration via FCM. The initial token comes from [FirebaseMessaging]; refreshes
 * arrive through [CcPocketMessagingService.onNewToken] → [deliver]. Both tag the token "fcm".
 */
actual object PushController {
    @Volatile private var cb: ((PushToken) -> Unit)? = null
    @Volatile private var last: PushToken? = null

    actual fun start(onToken: (PushToken) -> Unit) {
        cb = onToken
        last?.let { onToken(it) } // replay a token that arrived before start()
        // MULTI-VENDOR SEAM: choose the channel here (by Build.MANUFACTURER / build flavor) and tag the
        // token's platform accordingly ("xiaomi"/"huawei"/…) once a vendor SDK is integrated; FCM today.
        FirebaseMessaging.getInstance().token.addOnSuccessListener { t -> deliver(PushToken("fcm", t)) }
    }

    /** Fed by the initial token fetch and by [CcPocketMessagingService.onNewToken] on refresh. */
    fun deliver(token: PushToken) {
        last = token
        cb?.invoke(token)
    }
}
