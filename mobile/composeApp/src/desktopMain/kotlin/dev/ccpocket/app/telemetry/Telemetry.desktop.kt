package dev.ccpocket.app.telemetry

import dev.ccpocket.app.APP_VERSION
import dev.ccpocket.app.secure.SecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Properties
import java.util.UUID

/**
 * Desktop telemetry over the GA4 Measurement Protocol. There is no Firebase Analytics SDK for the JVM, so
 * instead of the Firebase SDK (Android/iOS) we POST the SAME anonymous events straight to the SAME GA4
 * property's HTTP endpoint. Only enum-level metadata ever leaves the machine (see [TelEvent]/[TelKey]) —
 * never prompts, paths, tool inputs, account ids, or any user content. Every event carries `edition=desktop`
 * so reports can split desktop from the mobile app streams within the one property.
 *
 * Credentials — a GA4 **web** data stream's `measurement_id` (G-XXXXXXXX) plus a Measurement-Protocol
 * `api_secret`, both created in the GA4 console for the property behind Firebase project `cc-pocket-1b3ea` —
 * are resolved from env (`CCPOCKET_GA4_MEASUREMENT_ID` / `CCPOCKET_GA4_API_SECRET`) or the gitignored
 * classpath resource `ga4.properties`. When neither is present every call is a safe no-op, so open-source
 * checkouts and dev builds send nothing.
 */
private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private val http: HttpClient by lazy { HttpClient(CIO) }

/** Stable per-install pseudonymous id (a random UUID) — GA4 keys sessions/users off this, not off anything
 *  identifying. Persisted next to the app's other desktop prefs. */
private val clientId: String by lazy {
    SecureStore.getString("ga4_client_id") ?: UUID.randomUUID().toString().also { SecureStore.putString("ga4_client_id", it) }
}

/** A per-process session id so events group into a session in GA4. Millis is fine — this is only a bucket key. */
private val sessionId: String = System.currentTimeMillis().toString()

private data class Ga4Config(val measurementId: String, val apiSecret: String)

private val config: Ga4Config? by lazy {
    fun clean(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("REPLACE") }
    // env first (CI / power users), then the bundled resource
    var mid = clean(System.getenv("CCPOCKET_GA4_MEASUREMENT_ID"))
    var secret = clean(System.getenv("CCPOCKET_GA4_API_SECRET"))
    if (mid == null || secret == null) {
        Telemetry::class.java.getResourceAsStream("/ga4.properties")?.use { stream ->
            val p = Properties().apply { load(stream) }
            mid = mid ?: clean(p.getProperty("measurement_id"))
            secret = secret ?: clean(p.getProperty("api_secret"))
        }
    }
    val m = mid
    val s = secret
    if (m != null && s != null) Ga4Config(m, s) else null
}

private var collectionEnabled: Boolean = SecureStore.getString("telemetry_enabled") != "false" // default on

/** Optional early hook from [main] — forces lazies (config/clientId) to resolve up front so a bad config
 *  surfaces in logs at launch rather than on the first event. Safe to skip; every path is lazy anyway. */
fun initDesktopTelemetry() {
    if (config == null) {
        System.err.println("[telemetry] no GA4 credentials (env or ga4.properties) — desktop analytics disabled")
    }
}

actual object Telemetry {
    actual fun setEnabled(enabled: Boolean) {
        collectionEnabled = enabled
        SecureStore.putString("telemetry_enabled", enabled.toString())
    }

    actual fun isEnabled(): Boolean = collectionEnabled

    actual fun track(event: TelEvent, params: Map<TelKey, Any>) {
        if (!collectionEnabled) return
        val cfg = config ?: return
        val body = buildJsonObject {
            put("client_id", clientId)
            put("non_personalized_ads", true)
            putJsonArray("events") {
                addJsonObject {
                    put("name", event.id)
                    put("params", buildJsonObject {
                        params.forEach { (k, v) ->
                            when (v) {
                                is Int -> put(k.id, v)
                                is Long -> put(k.id, v)
                                is Double -> put(k.id, v)
                                is Boolean -> put(k.id, if (v) 1 else 0)
                                else -> put(k.id, v.toString())
                            }
                        }
                        // fixed dimensions on every event: edition splits desktop vs mobile; the two GA4
                        // required params below make events count toward sessions/engagement.
                        put("edition", "desktop")
                        put("app_version", APP_VERSION)
                        put("session_id", sessionId)
                        put("engagement_time_msec", "100")
                    })
                }
            }
        }
        post(cfg, body.toString())
    }

    actual fun recordError(message: String, phase: String?) {
        if (!collectionEnabled) return
        val cfg = config ?: return
        // No Crashlytics on the JVM — surface errors as an `app_error` GA4 event carrying the same phase key.
        val body = buildJsonObject {
            put("client_id", clientId)
            put("non_personalized_ads", true)
            putJsonArray("events") {
                addJsonObject {
                    put("name", "app_error")
                    put("params", buildJsonObject {
                        put("message", message.take(100))
                        if (phase != null) put(TelKey.Phase.id, phase)
                        put("edition", "desktop")
                        put("app_version", APP_VERSION)
                        put("session_id", sessionId)
                        put("engagement_time_msec", "100")
                    })
                }
            }
        }
        post(cfg, body.toString())
    }

    private fun post(cfg: Ga4Config, json: String) {
        // fire-and-forget: telemetry must never block or crash the app; all failures are swallowed
        io.launch {
            runCatching {
                http.post("https://www.google-analytics.com/mp/collect?measurement_id=${cfg.measurementId}&api_secret=${cfg.apiSecret}") {
                    contentType(ContentType.Application.Json)
                    setBody(json)
                }
            }
        }
    }
}
