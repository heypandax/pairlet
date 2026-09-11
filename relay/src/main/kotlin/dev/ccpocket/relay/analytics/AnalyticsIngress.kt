package dev.ccpocket.relay.analytics

import dev.ccpocket.observability.AnalyticsCatalog
import dev.ccpocket.relay.net.RateLimiter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the HTTP layer sends back; `body` is already JSON (or empty for 204). */
data class IngressReply(val status: Int, val body: String = "") {
    companion object {
        fun error(status: Int, code: String) = IngressReply(status, """{"error":"$code"}""")
        val DISABLED = IngressReply(204)
    }
}

/**
 * Safe counters only (DESKTOP-GA4-INGRESS.md §7): no bodies, tokens, addresses or upstream text.
 * Latency is bucketed so the summary line stays fixed-size.
 */
class AnalyticsStats {
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    fun inc(key: String) { counts.computeIfAbsent(key) { AtomicLong() }.incrementAndGet() }
    fun latency(ms: Long) = inc("upstream_ms:" + when {
        ms < 100 -> "lt100"; ms < 500 -> "lt500"; ms < 2000 -> "lt2000"; else -> "ge2000"
    })
    fun snapshot(): Map<String, Long> = counts.entries.associate { it.key to it.value.get() }.toSortedMap()
    fun summaryLine(): String = snapshot().entries.joinToString(" ") { "${it.key}=${it.value}" }
}

/**
 * The analytics ingress (DESKTOP-GA4-INGRESS.md). Pure request→reply so it is testable without a server;
 * [dev.ccpocket.relay.RelayServer] only reads the body with a size cap and maps [IngressReply] to HTTP.
 * Isolation from the relay's real job: its own limiter namespace, a bounded upstream semaphore (no queue,
 * no persistence — saturation is a 503), and nothing here touches the broker, stores, or E2E frames.
 */
class AnalyticsIngress(
    @Volatile var config: AnalyticsConfig,
    private val limiter: RateLimiter,
    private val forwarder: Ga4Forwarder,
    private val clock: () -> Long = System::currentTimeMillis,
    maxInFlight: Int = 8,
    val stats: AnalyticsStats = AnalyticsStats(),
) {
    private val tokens get() = InstallToken(config.tokenKey, clock)
    private val inFlight = Semaphore(maxInFlight)

    fun register(ip: String, body: String): IngressReply {
        stats.inc("register")
        if (!config.active) { stats.inc("dropped:disabled"); return IngressReply.DISABLED }
        if (!limiter.check("analytics-register:ip:$ip", 10, 60_000, lockoutOnBreach = true) ||
            !limiter.check("analytics-register:global", 600, 60_000)) {
            stats.inc("rejected:rate_limited"); return IngressReply.error(429, "rate_limited")
        }
        if (body.toByteArray().size > AnalyticsCatalog.MAX_BODY_BYTES) { stats.inc("rejected:too_large"); return IngressReply.error(413, "too_large") }
        val installId = try { AnalyticsValidator.parseRegister(body) } catch (r: AnalyticsValidator.Rejected) {
            stats.inc("rejected:${r.code}"); return IngressReply.error(400, r.code)
        }
        stats.inc("registered")
        return IngressReply(200, """{"token":"${tokens.issue(installId)}","expires_in":${InstallToken.TTL_MS / 1000}}""")
    }

    suspend fun collect(ip: String, bearer: String?, body: String): IngressReply {
        stats.inc("received")
        if (!config.active) { stats.inc("dropped:disabled"); return IngressReply.DISABLED }
        if (body.toByteArray().size > AnalyticsCatalog.MAX_BODY_BYTES) { stats.inc("rejected:too_large"); return IngressReply.error(413, "too_large") }
        val installId = tokens.verify(bearer) ?: run { stats.inc("rejected:unauthorized"); return IngressReply.error(401, "unauthorized") }
        if (!limiter.check("analytics-collect:install:$installId", 120, 60_000) ||
            !limiter.check("analytics-collect:ip:$ip", 300, 60_000) ||
            !limiter.check("analytics-collect:global", 3000, 60_000)) {
            stats.inc("rejected:rate_limited"); return IngressReply.error(429, "rate_limited")
        }
        val req = try { AnalyticsValidator.parseCollect(body, installId) } catch (r: AnalyticsValidator.Rejected) {
            stats.inc("rejected:${r.code}")
            return IngressReply.error(if (r.code == "unauthorized") 401 else 400, r.code)
        }
        // Route each event by its environment; MP takes one client per request, so group per stream.
        val byEnv = req.events.groupBy { it.environment }
        val cfg = config
        var accepted = 0
        for ((env, events) in byEnv) {
            val stream = cfg.streams[env] ?: run { stats.inc("dropped:no_stream:$env"); null } ?: continue
            if (!inFlight.tryAcquire()) { stats.inc("saturated"); return IngressReply.error(503, "saturated") }
            val status = try {
                val started = clock()
                val s = try { forwarder.post(stream, mpBody(req, events, debug = env == "staging" && cfg.debugStaging)) }
                catch (c: CancellationException) { throw c }
                catch (_: Exception) { stats.inc("upstream:error"); return IngressReply.error(502, "upstream_failed") }
                stats.latency(clock() - started)
                s
            } finally { inFlight.release() }
            when (status) {
                in 200..299 -> { stats.inc("upstream:2xx"); accepted += events.size }
                in 400..499 -> { stats.inc("upstream:4xx"); return IngressReply.error(502, "upstream_failed") }
                else -> { stats.inc("upstream:5xx"); return IngressReply.error(502, "upstream_failed") }
            }
        }
        if (accepted == 0) return IngressReply.DISABLED
        stats.inc("accepted")
        return IngressReply(202, """{"accepted":$accepted}""")
    }

    private fun mpBody(req: ValidatedCollect, events: List<ValidatedEvent>, debug: Boolean): String = buildJsonObject {
        put("client_id", req.clientId)
        put("non_personalized_ads", true)
        putJsonArray("events") {
            for (e in events) addJsonObject {
                put("name", e.name)
                put("params", buildJsonObject {
                    e.params.forEach { (k, v) -> put(k, v) }
                    put("session_id", req.sessionId)
                    // Legacy MP compatibility value, not measured engagement (EVENT-CATALOG.md §6).
                    put("engagement_time_msec", "100")
                    if (debug) put("debug_mode", 1)
                })
            }
        }
    }.toString()
}
