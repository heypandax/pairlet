package dev.ccpocket.app.telemetry

import dev.ccpocket.app.APP_VERSION
import dev.ccpocket.observability.*
import dev.ccpocket.observability.sentry.SentryRuntime

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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

// bounded request time so a black-holed network can't stack up fire-and-forget coroutines indefinitely
private val http: HttpClient by lazy { HttpClient(CIO) { engine { requestTimeout = 10_000 } } }

/** Stable random installation seed. Preserve it locally and serialize the numeric GA4 wire format. */
private val clientId: String? by lazy {
    val seed = SecureStore.getString("ga4_client_id")
        ?: UUID.randomUUID().toString().also { SecureStore.putString("ga4_client_id", it) }
    ga4ClientId(seed)
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

@Volatile private var collectionEnabled: Boolean = SecureStore.getString("telemetry_enabled") != "false" // default on

private data class AnalyticsPacket(val name: String, val params: Map<TelKey, Any>)
private val metadata by lazy {
    TelemetryMetadata(Component.DESKTOP, SentryRuntime.configuredEnvironmentOrNull(),
        System.getenv("CCPOCKET_ANALYTICS_INTERNAL")?.toBooleanStrictOrNull())
}
private val transportProbe by lazy {
    if (ga4ProbeEnabled(System.getenv("CCPOCKET_GA4_DEBUG"), metadata.environment))
        Ga4TransportProbe { System.err.println(it) }
    else null
}
private val delivery by lazy {
    TelemetryDelivery<AnalyticsPacket>(io, collectionEnabled) { packet -> sendPacket(packet) }
}

/** Optional early hook from [main] — forces lazies (config/clientId) to resolve up front so a bad config
 *  surfaces in logs at launch rather than on the first event. Safe to skip; every path is lazy anyway. */
fun initDesktopTelemetry() {
    SentryRuntime.configure(Component.DESKTOP, APP_VERSION, collectionEnabled)
    if (config == null) {
        System.err.println("[telemetry] no GA4 credentials (env or ga4.properties) — desktop analytics disabled")
    }
}

/** Test-only observation seam (desktop compilation only): `internal`, null in production, and read BEFORE the
 *  opt-out/credential gates so desktopTest can pin WHICH enum event a repo path fires without a GA4 config.
 *  It never sees more than [Telemetry.track]'s own arguments — enum event + enum-keyed params. */
internal var telemetryTap: ((TelEvent, Map<TelKey, Any>) -> Unit)? = null
private val firstValue = FirstValueObservation(CoroutineScope(SupervisorJob() + Dispatchers.IO),
    { env -> diagnosticBudgetStore(Component.DESKTOP, env,
        java.nio.file.Path.of(System.getProperty("user.home"), ".cc-pocket", "first-value").toString()) })

actual object Telemetry {
    actual fun setEnabled(enabled: Boolean) {
        TelemetryConsent.changed()
        collectionEnabled = enabled
        delivery.setEnabled(enabled)
        SentryRuntime.configure(Component.DESKTOP, APP_VERSION, enabled)
        SecureStore.putString("telemetry_enabled", enabled.toString())
    }

    actual fun isEnabled(): Boolean = collectionEnabled

    actual fun track(event: TelEvent, params: Map<TelKey, Any>) {
        telemetryTap?.invoke(event, params)
        if (!collectionEnabled || System.getProperty("ccpocket.test") == "true" || config == null || clientId == null) return
        val generation = delivery.currentGeneration() ?: return
        val prepared = metadata.prepare(event, params)
        if (delivery.offer(AnalyticsPacket(event.id, prepared), generation)) firstValue.observe(event, prepared)
    }
}

/** Serial IO worker owns the request; opt-out cancels it and discards the bounded pending queue. */
private suspend fun sendPacket(packet: AnalyticsPacket) {
    if (!collectionEnabled) return
    val cfg = config ?: return
    val cid = clientId ?: return
    val body = buildJsonObject {
        put("client_id", cid)
        put("non_personalized_ads", true)
        putJsonArray("events") {
            addJsonObject {
                put("name", packet.name)
                put("params", buildJsonObject {
                    packet.params.forEach { (k, v) ->
                        when (v) {
                            is Int -> put(k.id, v)
                            is Long -> put(k.id, v)
                            is Double -> put(k.id, v)
                            is Boolean -> put(k.id, if (v) 1 else 0)
                            else -> put(k.id, v.toString())
                        }
                    }
                    put("edition", "desktop")
                    put("app_version", APP_VERSION)
                    put("session_id", sessionId)
                    // Legacy MP compatibility parameter, not measured engagement. See EVENT-CATALOG.md.
                    put("engagement_time_msec", "100")
                    if (transportProbe != null) put("debug_mode", 1)
                })
            }
        }
    }
    if (!collectionEnabled) return
    currentCoroutineContext().ensureActive()
    val request: suspend () -> Int = {
        http.post("https://www.google-analytics.com/mp/collect?measurement_id=${cfg.measurementId}&api_secret=${cfg.apiSecret}") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.status.value
    }
    val probe = transportProbe
    if (probe != null) probe.send(request) else request()
}
