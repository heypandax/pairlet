package dev.ccpocket.app.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** What a transport observed. The body matters only for `register`; nothing else is ever read or logged. */
internal data class IngressResponse(val status: Int, val body: String)

/**
 * Speaks the relay-hosted analytics ingress (docs/observability/DESKTOP-GA4-INGRESS.md §2/§3) so official
 * desktop builds carry no GA4 secret: the measurement credentials live on the relay, the client only holds an
 * anonymous, revocable install token it can always re-obtain.
 *
 * The ingress deliberately has no retry semantics ("losing events is allowed"), so this client never replays a
 * batch. The one resend it does is the 401 path, which is safe precisely because a rejected token means the
 * event was refused *before* forwarding — a duplicate is impossible there.
 *
 * Dormancy is the whole backpressure story: when the server says "off" (204) or "too much" (429/5xx on
 * register) we stop touching the network for an hour / ten minutes instead of letting a 32-event queue drain
 * into a wall. [send] therefore returns 0 while dormant, which [Ga4TransportProbe] reports as `http_other`.
 *
 * @param endpoint scheme+host only, e.g. `https://pocket.ark-nexus.cc`; this class owns the path join.
 * @param transport does the actual POST — injected so tests never reach the network.
 */
internal class AnalyticsIngressClient(
    endpoint: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val transport: suspend (path: String, bearer: String?, body: String) -> IngressResponse,
) {
    private val registerUrl = endpoint.trimEnd('/') + "/v1/analytics/register"
    private val collectUrl = endpoint.trimEnd('/') + "/v1/analytics/collect"

    // Written by the serial delivery worker, read by reset() on the UI thread.
    @Volatile private var token: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L
    @Volatile private var dormantUntil: Long = 0L

    /**
     * Sends exactly one event and returns the last HTTP status observed (0 = suppressed while dormant), never
     * throwing on a status. Transport exceptions propagate on purpose so the probe can classify them, and a
     * cancellation (opt-out mid-flight) is never swallowed.
     */
    suspend fun send(clientId: String, sessionId: String, name: String, params: Map<String, JsonElement>): Int {
        if (clock() < dormantUntil) return 0
        val issued = ensureToken(clientId)
        val bearer = issued.token ?: return issued.status
        val body = collectBody(clientId, sessionId, name, params)
        var status = transport(collectUrl, bearer, body).status
        if (status == UNAUTHORIZED) {
            // Expired or key-rotated token: re-register once and resend the same (never-forwarded) batch.
            token = null
            tokenExpiresAt = 0L
            val renewed = ensureToken(clientId)
            val retryBearer = renewed.token ?: return renewed.status
            status = transport(collectUrl, retryBearer, body).status
            if (status == UNAUTHORIZED) token = null
        }
        if (status == UNAUTHORIZED || status == TOO_MANY_REQUESTS) sleepFor(BACKOFF_MS)
        return status
    }

    /** Re-enabling collection starts a fresh identity handshake; a stale token or dormancy must not survive it. */
    fun reset() {
        token = null
        tokenExpiresAt = 0L
        dormantUntil = 0L
    }

    private class Issued(val token: String?, val status: Int)

    private suspend fun ensureToken(clientId: String): Issued {
        val cached = token
        if (cached != null && clock() < tokenExpiresAt) return Issued(cached, OK)
        val response = transport(registerUrl, null, buildJsonObject {
            put("v", 1)
            put("install_id", clientId)
        }.toString())
        if (response.status == INGRESS_CLOSED) { // §3: the ingress is off, or this environment has no stream.
            sleepFor(CLOSED_MS)
            return Issued(null, response.status)
        }
        val granted = if (response.status == OK) parseToken(response.body) else null
        if (granted == null) {
            // 429 / 5xx / anything unexpected (including an unparseable 200) — back off rather than hammer.
            sleepFor(BACKOFF_MS)
            return Issued(null, response.status)
        }
        token = granted.first
        // Expire early so a token is never spent at the very edge of its validity window.
        tokenExpiresAt = clock() + (granted.second * 1000L - EXPIRY_SKEW_MS).coerceAtLeast(0L)
        return Issued(granted.first, response.status)
    }

    /** Register responses are the only body this client reads; a malformed one is treated as no token. */
    private fun parseToken(body: String): Pair<String, Long>? = runCatching {
        val json = Json.parseToJsonElement(body).jsonObject
        val value = json["token"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: return@runCatching null
        value to (json["expires_in"]?.jsonPrimitive?.long ?: 0L)
    }.getOrNull()

    private fun collectBody(clientId: String, sessionId: String, name: String, params: Map<String, JsonElement>) =
        buildJsonObject {
            put("v", 1)
            put("client_id", clientId)
            put("session_id", sessionId) // top-level per §3; it is NOT a param on this transport
            putJsonArray("events") {
                addJsonObject {
                    put("name", name)
                    put("params", JsonObject(params))
                }
            }
        }.toString()

    private fun sleepFor(millis: Long) { dormantUntil = clock() + millis }

    private companion object {
        const val OK = 200
        const val INGRESS_CLOSED = 204
        const val UNAUTHORIZED = 401
        const val TOO_MANY_REQUESTS = 429
        const val EXPIRY_SKEW_MS = 60_000L
        const val BACKOFF_MS = 10 * 60 * 1000L
        const val CLOSED_MS = 60 * 60 * 1000L
    }
}
