package dev.ccpocket.app.telemetry

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

private var analytics: FirebaseAnalytics? = null
private var collectionEnabled = true

/** Call once from MainActivity. FirebaseApp itself is auto-initialised by the google-services plugin. */
fun initTelemetry(context: Context) {
    analytics = FirebaseAnalytics.getInstance(context.applicationContext)
    analytics?.setAnalyticsCollectionEnabled(collectionEnabled)
}

actual object Telemetry {
    actual fun setEnabled(enabled: Boolean) {
        collectionEnabled = enabled
        analytics?.setAnalyticsCollectionEnabled(enabled)
        runCatching { FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = enabled }
    }

    actual fun isEnabled(): Boolean = collectionEnabled

    actual fun track(event: TelEvent, params: Map<TelKey, Any>) {
        if (!collectionEnabled) return
        val b = Bundle()
        params.forEach { (k, v) ->
            when (v) {
                is Int -> b.putLong(k.id, v.toLong())
                is Long -> b.putLong(k.id, v)
                is Double -> b.putDouble(k.id, v)
                is Boolean -> b.putLong(k.id, if (v) 1 else 0)
                else -> b.putString(k.id, v.toString())
            }
        }
        analytics?.logEvent(event.id, b)
    }

    actual fun recordError(message: String, phase: String?) {
        if (!collectionEnabled) return
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                phase?.let { setCustomKey(TelKey.Phase.id, it) }
                recordException(RuntimeException(message))
            }
        }
    }
}
