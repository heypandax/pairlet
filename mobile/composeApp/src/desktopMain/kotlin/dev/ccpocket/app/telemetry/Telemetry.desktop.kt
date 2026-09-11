package dev.ccpocket.app.telemetry

import dev.ccpocket.app.APP_VERSION
import dev.ccpocket.observability.*
import dev.ccpocket.observability.sentry.SentryRuntime

import dev.ccpocket.app.secure.SecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Properties
import java.util.UUID

/**
 * Desktop telemetry. There is no Firebase Analytics SDK for the JVM, so instead of the Firebase SDK
 * (Android/iOS) the SAME anonymous events are POSTed over HTTP. Only enum-level metadata ever leaves the
 * machine (see [TelEvent]/[TelKey]) — never prompts, paths, tool inputs, account ids, or any user content.
 * Every event carries `edition=desktop` so reports can split desktop from the mobile app streams.
 *
 * Two mutually exclusive routes (docs/observability/DESKTOP-GA4-INGRESS.md §8):
 * - [AnalyticsRoute.Ingress] — official builds. Events go to the relay-hosted `/v1/analytics` ingress, which
 *   owns the GA4 credentials, so **no measurement secret ships inside the app**. Configured by
 *   `CCPOCKET_ANALYTICS_ENDPOINT` or the bundled resource `cc-pocket-analytics.properties`.
 * - [AnalyticsRoute.DirectMp] — private developer testing ONLY. The old direct Measurement-Protocol path, kept
 *   for a local `ga4.properties` / `CCPOCKET_GA4_*` and refused outright in a production environment, so a
 *   stray credential can never route real users around the ingress.
 *
 * With neither configured every call is a safe no-op, so open-source checkouts and dev builds send nothing.
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

private sealed interface AnalyticsRoute {
    /** Relay ingress; [endpoint] is scheme+host with no trailing slash. */
    data class Ingress(val endpoint: String) : AnalyticsRoute
    data class DirectMp(val measurementId: String, val apiSecret: String) : AnalyticsRoute
}

private fun clean(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("REPLACE") }

private fun resourceProperty(resource: String, key: String): String? =
    Telemetry::class.java.getResourceAsStream(resource)?.use { clean(Properties().apply { load(it) }.getProperty(key)) }

/** §8: scheme+host (optional port) only. A path or query would silently send events somewhere unintended, so a
 *  malformed value counts as "no ingress configured" — and is reported without echoing the value itself. */
private fun ingressEndpoint(raw: String?): String? {
    val value = clean(raw)?.removeSuffix("/") ?: return null
    if (!Regex("https?://[A-Za-z0-9.-]+(:[0-9]+)?").matches(value)) {
        System.err.println("[telemetry] analytics endpoint is not a bare scheme+host — ignoring it")
        return null
    }
    return value
}

private val route: AnalyticsRoute? by lazy {
    // env first (CI / power users), then the bundled resource
    val endpoint = ingressEndpoint(System.getenv("CCPOCKET_ANALYTICS_ENDPOINT"))
        ?: ingressEndpoint(resourceProperty("/cc-pocket-analytics.properties", "endpoint"))
    if (endpoint != null) return@lazy AnalyticsRoute.Ingress(endpoint)
    // Direct MP is a developer affordance, never a production route (§8).
    if (metadata.environment == Environment.PRODUCTION) return@lazy null
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
    if (m != null && s != null) AnalyticsRoute.DirectMp(m, s) else null
}

private val ingressClient: AnalyticsIngressClient? by lazy {
    (route as? AnalyticsRoute.Ingress)?.let { target ->
        AnalyticsIngressClient(target.endpoint) { url, bearer, body ->
            val response = http.post(url) {
                contentType(ContentType.Application.Json)
                if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
                setBody(body)
            }
            IngressResponse(response.status.value, response.bodyAsText())
        }
    }
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

/** Optional early hook from [main] — forces lazies (route/clientId) to resolve up front so a bad config
 *  surfaces in logs at launch rather than on the first event. Safe to skip; every path is lazy anyway. */
fun initDesktopTelemetry() {
    SentryRuntime.configure(Component.DESKTOP, APP_VERSION, collectionEnabled)
    if (route == null) {
        System.err.println("[telemetry] no analytics ingress endpoint or GA4 credentials — desktop analytics disabled")
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
        // Re-enabling means a fresh handshake: drop any stale install token and any server-asked dormancy.
        // Opting out needs no client call — the delivery already cleared the queue and cancelled the in-flight.
        if (enabled) ingressClient?.reset()
        SentryRuntime.configure(Component.DESKTOP, APP_VERSION, enabled)
        SecureStore.putString("telemetry_enabled", enabled.toString())
    }

    actual fun isEnabled(): Boolean = collectionEnabled

    actual fun track(event: TelEvent, params: Map<TelKey, Any>) {
        telemetryTap?.invoke(event, params)
        if (!collectionEnabled || System.getProperty("ccpocket.test") == "true" || route == null || clientId == null) return
        val generation = delivery.currentGeneration() ?: return
        val prepared = metadata.prepare(event, params)
        if (delivery.offer(AnalyticsPacket(event.id, prepared), generation)) firstValue.observe(event, prepared)
    }
}

/** Serial IO worker owns the request; opt-out cancels it and discards the bounded pending queue. */
private suspend fun sendPacket(packet: AnalyticsPacket) {
    if (!collectionEnabled) return
    val cid = clientId ?: return
    when (val target = route) {
        is AnalyticsRoute.Ingress -> sendToIngress(cid, packet)
        is AnalyticsRoute.DirectMp -> sendToMeasurementProtocol(target, cid, packet)
        null -> return
    }
}

/** §3/§4: `session_id` is a top-level field here, and `engagement_time_msec`/`debug_mode` are server-owned —
 *  sending `debug_mode` is an outright `invalid_param`, so the ingress body deliberately carries neither. */
private suspend fun sendToIngress(cid: String, packet: AnalyticsPacket) {
    val client = ingressClient ?: return
    val params = buildMap<String, JsonElement> {
        packet.params.forEach { (k, v) ->
            put(k.id, when (v) {
                is Int -> JsonPrimitive(v)
                is Long -> JsonPrimitive(v)
                is Double -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(if (v) 1 else 0)
                else -> JsonPrimitive(v.toString())
            })
        }
        put("edition", JsonPrimitive("desktop"))
        put("app_version", JsonPrimitive(APP_VERSION))
    }
    if (!collectionEnabled) return
    currentCoroutineContext().ensureActive()
    val request: suspend () -> Int = { client.send(cid, sessionId, packet.name, params) }
    val probe = transportProbe
    if (probe != null) probe.send(request) else request()
}

/** Developer-only direct path; unchanged wire shape, including the two parameters the ingress owns itself. */
private suspend fun sendToMeasurementProtocol(cfg: AnalyticsRoute.DirectMp, cid: String, packet: AnalyticsPacket) {
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
