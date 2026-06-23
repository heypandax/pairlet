package dev.ccpocket.app.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM entry point. Forwards token refreshes to [PushController] and, when a notification message lands
 * while the app is in the foreground, posts it locally. Backgrounded notification messages are shown by
 * the system on the [CHANNEL_ID] channel (set via the default-channel manifest meta-data).
 */
class CcPocketMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushController.deliver(PushToken("fcm", token))
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val n = message.notification ?: return // data-only messages aren't used by the relay
        val nm = getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(nm)
        // carry the session-routing data so a tap opens the right session (mirrors how the system tray
        // delivers `data` as intent extras for backgrounded notifications)
        val wd = message.data["wd"]
        val sid = message.data["sid"]
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            if (wd != null && sid != null) { putExtra("wd", wd); putExtra("sid", sid) }
        }
        // a distinct request code per session keeps each notification's extras from clobbering another's
        val reqCode = (wd to sid).hashCode()
        val tap = launch?.let {
            PendingIntent.getActivity(this, reqCode, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setAutoCancel(true)
            .apply { tap?.let(::setContentIntent) }
            .build()
        nm.notify(message.messageId?.hashCode() ?: 0, notif)
    }

    companion object {
        const val CHANNEL_ID = "task_complete"

        /** Create the notification channel if absent. minSdk 26 → NotificationChannel always available. */
        fun ensureChannel(nm: NotificationManager) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Task complete", NotificationManager.IMPORTANCE_HIGH),
                )
            }
        }
    }
}
