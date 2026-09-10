package dev.ccpocket.app.telemetry

import dev.ccpocket.app.APP_VERSION
import dev.ccpocket.observability.*
import dev.ccpocket.observability.sentry.SentryRuntime

import dev.ccpocket.app.secure.SecureStore
import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

private var analytics: FirebaseAnalytics? = null
private var collectionEnabled = true
private var budgetDirectory: String? = null
private var metadata = TelemetryMetadata(Component.ANDROID)
private val firstValue = FirstValueObservation(
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
    { env -> budgetDirectory?.let { diagnosticBudgetStore(Component.ANDROID, env, "$it/first-value") } },
)

/** Call once from MainActivity. FirebaseApp itself is auto-initialised by the google-services plugin. */
fun initTelemetry(context: Context) {
    collectionEnabled = SecureStore.getString("telemetry_enabled") != "false"
    budgetDirectory = java.io.File(context.filesDir, "diagnostic-budgets").absolutePath
    metadata = TelemetryMetadata(Component.ANDROID, SentryRuntime.configuredEnvironmentOrNull(),
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) true else null)
    analytics = FirebaseAnalytics.getInstance(context.applicationContext)
    runCatching { Telemetry.setEnabled(collectionEnabled) }
}

actual object Telemetry {
    actual fun setEnabled(enabled: Boolean) {
        TelemetryConsent.changed()
        collectionEnabled = enabled
        SentryRuntime.configure(Component.ANDROID, APP_VERSION, enabled, budgetDirectory = budgetDirectory)
        analytics?.setAnalyticsCollectionEnabled(enabled)
        if (!enabled) { analytics?.resetAnalyticsData(); firstValue.resetIdentity() }
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                isCrashlyticsCollectionEnabled = enabled
                if (!enabled) deleteUnsentReports()
            }
        }
        // Apply opt-out before preference I/O; a full disk cannot leave an active collector behind.
        SecureStore.putString("telemetry_enabled", enabled.toString())
    }

    actual fun isEnabled(): Boolean = collectionEnabled

    actual fun track(event: TelEvent, params: Map<TelKey, Any>) {
        if (!collectionEnabled) return
        val b = Bundle()
        val prepared = metadata.prepare(event, params)
        prepared.forEach { (k, v) ->
            when (v) {
                is Int -> b.putLong(k.id, v.toLong())
                is Long -> b.putLong(k.id, v)
                is Double -> b.putDouble(k.id, v)
                is Boolean -> b.putLong(k.id, if (v) 1 else 0)
                else -> b.putString(k.id, v.toString())
            }
        }
        analytics?.let { it.logEvent(event.id, b); firstValue.observe(event, prepared) }
    }
}
