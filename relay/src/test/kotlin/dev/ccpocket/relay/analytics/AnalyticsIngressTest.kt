package dev.ccpocket.relay.analytics

import dev.ccpocket.relay.net.RateLimiter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/observability/DESKTOP-GA4-INGRESS.md — the relay side of the contract, endpoint by endpoint. */
class AnalyticsIngressTest {
    private var now = 1_700_000_000_000L
    private val clock = { now }
    private val prod = Ga4Stream("G-PROD1234", "prod-secret-value")
    private val staging = Ga4Stream("G-STAG1234", "staging-secret-1")
    private val key = ByteArray(32) { it.toByte() }

    private class FakeForwarder(var status: Int = 204) : Ga4Forwarder {
        val calls = mutableListOf<Pair<Ga4Stream, String>>()
        var gate: CompletableDeferred<Unit>? = null
        var fail: Exception? = null
        override suspend fun post(stream: Ga4Stream, body: String): Int {
            calls += stream to body
            fail?.let { throw it }
            gate?.await()
            return status
        }
    }

    private fun config(enabled: Boolean = true, debug: Boolean = false, streams: Map<String, Ga4Stream> = mapOf("production" to prod, "staging" to staging)) =
        AnalyticsConfig(enabled, streams, key, debug)

    private fun ingress(cfg: AnalyticsConfig = config(), fwd: FakeForwarder = FakeForwarder(), maxInFlight: Int = 8) =
        AnalyticsIngress(cfg, RateLimiter(clock), fwd, clock, maxInFlight)

    private val install = "12345678901234567890.98765432109876543210"
    private fun registerBody(id: String = install) = """{"v":1,"install_id":"$id"}"""
    private fun token(i: AnalyticsIngress, id: String = install): String {
        val r = i.register("1.1.1.1", registerBody(id))
        assertEquals(200, r.status)
        return Json.parseToJsonElement(r.body).jsonObject["token"]!!.jsonPrimitive.content
    }
    private fun params(env: String = "production", extra: String = "") =
        """{"app_platform":"desktop","edition":"desktop","analytics_schema":"v1","app_environment":"$env","app_version":"1.9.8","result":"success","duration_ms":1234$extra}"""
    private fun collectBody(env: String = "production", name: String = "session_open_result", extra: String = "", cid: String = install, n: Int = 1) =
        """{"v":1,"client_id":"$cid","session_id":"1700000000000","events":[${(1..n).joinToString(",") { """{"name":"$name","params":${params(env, extra)}}""" }}]}"""

    @Test fun disabled_ingress_answers_204_and_forwards_nothing() = runBlocking {
        val fwd = FakeForwarder()
        val i = ingress(config(enabled = false), fwd)
        assertEquals(204, i.register("1.1.1.1", registerBody()).status)
        assertEquals(204, i.collect("1.1.1.1", "anything", collectBody()).status)
        assertTrue(fwd.calls.isEmpty())
        assertEquals(2L, i.stats.snapshot()["dropped:disabled"])
    }

    @Test fun enabled_flag_without_any_stream_is_still_off() {
        assertFalse(config(streams = emptyMap()).active)
        assertEquals(204, ingress(config(streams = emptyMap())).register("1.1.1.1", registerBody()).status)
    }

    @Test fun register_issues_a_token_bound_to_the_install_id() {
        val i = ingress()
        val t = token(i)
        assertEquals(install, InstallToken(key, clock).verify(t))
        assertNull(InstallToken(ByteArray(32), clock).verify(t)) // other key → invalid
        now += InstallToken.TTL_MS + 1
        assertNull(InstallToken(key, clock).verify(t)) // expired
    }

    @Test fun register_rejects_malformed_ids_and_versions() {
        val i = ingress()
        assertEquals(400, i.register("1.1.1.1", """{"v":1,"install_id":"not-a-ga4-id"}""").status)
        assertEquals(400, i.register("1.1.1.1", """{"v":2,"install_id":"$install"}""").status)
        assertEquals(400, i.register("1.1.1.1", "{").status)
        assertEquals(413, i.register("1.1.1.1", "x".repeat(9000)).status)
    }

    @Test fun register_is_locked_out_per_ip_after_ten_per_minute() {
        val i = ingress()
        repeat(10) { assertEquals(200, i.register("9.9.9.9", registerBody()).status) }
        assertEquals(429, i.register("9.9.9.9", registerBody()).status)
        assertEquals(200, i.register("8.8.8.8", registerBody()).status) // other IP unaffected
    }

    @Test fun collect_requires_a_valid_token_matching_the_client_id() = runBlocking {
        val fwd = FakeForwarder()
        val i = ingress(fwd = fwd)
        assertEquals(401, i.collect("1.1.1.1", null, collectBody()).status)
        assertEquals(401, i.collect("1.1.1.1", "garbage", collectBody()).status)
        val other = "1.2"
        assertEquals(401, i.collect("1.1.1.1", token(i, other), collectBody()).status) // token for another install
        assertTrue(fwd.calls.isEmpty())
    }

    @Test fun production_event_is_forwarded_to_the_production_stream_with_server_owned_params() = runBlocking {
        val fwd = FakeForwarder(204)
        val i = ingress(fwd = fwd)
        val r = i.collect("1.1.1.1", token(i), collectBody())
        assertEquals(202, r.status)
        assertEquals("""{"accepted":1}""", r.body)
        val (stream, body) = fwd.calls.single()
        assertEquals(prod, stream)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals(install, json["client_id"]!!.jsonPrimitive.content)
        val ev = json["events"]!!.jsonArray.single().jsonObject
        assertEquals("session_open_result", ev["name"]!!.jsonPrimitive.content)
        val p = ev["params"]!!.jsonObject
        assertEquals("100", p["engagement_time_msec"]!!.jsonPrimitive.content)
        assertEquals("1700000000000", p["session_id"]!!.jsonPrimitive.content)
        assertEquals(1234L, p["duration_ms"]!!.jsonPrimitive.content.toLong())
        assertNull(p["debug_mode"]) // never on production
        assertEquals(1L, i.stats.snapshot()["upstream:2xx"])
    }

    @Test fun staging_gets_debug_mode_only_when_the_server_opts_in() = runBlocking {
        val off = FakeForwarder(); ingress(fwd = off).let { i -> i.collect("1.1.1.1", token(i), collectBody("staging")) }
        assertEquals(staging, off.calls.single().first)
        assertFalse(off.calls.single().second.contains("debug_mode"))
        val on = FakeForwarder(); ingress(config(debug = true), on).let { i -> i.collect("1.1.1.1", token(i), collectBody("staging")) }
        assertTrue(on.calls.single().second.contains("\"debug_mode\":1"))
    }

    @Test fun development_and_unknown_environments_are_dropped_not_forwarded() = runBlocking {
        val fwd = FakeForwarder()
        val i = ingress(fwd = fwd)
        assertEquals(204, i.collect("1.1.1.1", token(i), collectBody("development")).status)
        assertEquals(204, i.collect("1.1.1.1", token(i), collectBody("unknown")).status)
        assertTrue(fwd.calls.isEmpty())
        assertEquals(1L, i.stats.snapshot()["dropped:no_stream:development"])
    }

    @Test fun whitelist_violations_reject_the_whole_request_with_a_code_only() = runBlocking {
        val fwd = FakeForwarder()
        val i = ingress(fwd = fwd)
        val t = token(i)
        fun code(body: String) = runBlocking { i.collect("1.1.1.1", t, body) }.let { it.status to it.body }
        assertEquals(400 to """{"error":"invalid_event"}""", code(collectBody(name = "prompt_text")))
        assertEquals(400 to """{"error":"invalid_param"}""", code(collectBody(extra = ""","path":"/Users/x/secret"""")))
        assertEquals(400 to """{"error":"invalid_param"}""", code(collectBody(extra = ""","debug_mode":1"""))) // server-owned
        assertEquals(400 to """{"error":"invalid_param"}""", code(collectBody(extra = ""","engagement_time_msec":"5000"""")))
        assertEquals(400 to """{"error":"invalid_param"}""", code(collectBody(extra = ""","reason":"${"a".repeat(65)}""""))) // too long
        assertEquals(400 to """{"error":"invalid_param"}""", code(collectBody(extra = ""","reason":"has\nnewline"""")))
        assertEquals(400 to """{"error":"invalid_param"}""", code(collectBody(extra = ""","duration_ms":1.5""")))
        assertEquals(400 to """{"error":"invalid_param"}""", code(collectBody(extra = ""","resume":true""")))
        assertEquals(400 to """{"error":"invalid_param"}""", code(collectBody(extra = ""","duration_ms":9999999999999""")))
        assertEquals(400 to """{"error":"too_many_events"}""", code(collectBody(n = 11)))
        assertEquals(400 to """{"error":"platform_mismatch"}""", code(collectBody().replace("\"app_platform\":\"desktop\"", "\"app_platform\":\"ios\"")))
        assertEquals(400 to """{"error":"invalid_param"}""", code(collectBody().replace("\"analytics_schema\":\"v1\"", "\"analytics_schema\":\"v2\"")))
        assertEquals(400 to """{"error":"bad_request"}""", code("""{"v":1,"client_id":"$install","session_id":"1","events":[]}"""))
        assertEquals(413, i.collect("1.1.1.1", t, "x".repeat(8193)).status)
        assertTrue(fwd.calls.isEmpty())
        assertEquals(9L, i.stats.snapshot()["rejected:invalid_param"])
    }

    @Test fun ten_events_are_forwarded_in_one_upstream_request() = runBlocking {
        val fwd = FakeForwarder()
        val i = ingress(fwd = fwd)
        val r = i.collect("1.1.1.1", token(i), collectBody(n = 10))
        assertEquals("""{"accepted":10}""", r.body)
        assertEquals(10, Json.parseToJsonElement(fwd.calls.single().second).jsonObject["events"]!!.jsonArray.size)
    }

    @Test fun upstream_failures_map_to_502_and_are_only_counted() = runBlocking {
        val fwd = FakeForwarder(500)
        val i = ingress(fwd = fwd)
        assertEquals(502, i.collect("1.1.1.1", token(i), collectBody()).status)
        fwd.status = 403
        assertEquals(502, i.collect("1.1.1.1", token(i), collectBody()).status)
        fwd.fail = java.net.http.HttpTimeoutException("timeout")
        assertEquals(502, i.collect("1.1.1.1", token(i), collectBody()).status)
        val s = i.stats.snapshot()
        assertEquals(1L, s["upstream:5xx"]); assertEquals(1L, s["upstream:4xx"]); assertEquals(1L, s["upstream:error"])
        assertTrue(s.keys.none { it.contains("timeout") || it.contains("secret") })
    }

    @Test fun saturated_upstream_is_a_503_never_a_queue() = runBlocking {
        val fwd = FakeForwarder().apply { gate = CompletableDeferred() }
        val i = ingress(fwd = fwd, maxInFlight = 1)
        val t = token(i)
        val slow = async { i.collect("1.1.1.1", t, collectBody()) }
        while (fwd.calls.isEmpty()) yield()
        assertEquals(503, i.collect("1.1.1.1", t, collectBody()).status)
        fwd.gate!!.complete(Unit)
        assertEquals(202, slow.await().status)
        assertEquals(202, i.collect("1.1.1.1", t, collectBody()).status) // permit released
    }

    @Test fun collect_rate_limits_key_on_the_verified_install_and_the_ip() = runBlocking {
        val fwd = FakeForwarder()
        val i = ingress(fwd = fwd)
        val t = token(i)
        repeat(120) { assertEquals(202, i.collect("1.1.1.1", t, collectBody()).status) }
        assertEquals(429, i.collect("1.1.1.1", t, collectBody()).status)
        val t2 = token(i, "5.5")
        assertEquals(202, i.collect("1.1.1.1", t2, collectBody(cid = "5.5")).status) // other install, same IP, still ok
        now += 60_001
        assertEquals(202, i.collect("1.1.1.1", t, collectBody()).status)
    }

    @Test fun stats_line_has_no_identifiers() = runBlocking {
        val i = ingress()
        i.collect("203.0.113.7", token(i), collectBody())
        val line = i.stats.summaryLine()
        assertFalse(line.contains("203.0.113.7")); assertFalse(line.contains(install)); assertFalse(line.contains("secret"))
        assertTrue(line.contains("accepted=1"))
    }

    @Test fun stream_config_parses_and_never_prints_the_secret() {
        val s = assertNotNull(Ga4Stream.parse("G-ABC123:my:secret:with:colons"))
        assertEquals("G-ABC123", s.measurementId); assertEquals("my:secret:with:colons", s.apiSecret)
        assertFalse(s.toString().contains("my:secret"))
        assertNull(Ga4Stream.parse("G-ABC123:REPLACE_WITH_MP_API_SECRET"))
        assertNull(Ga4Stream.parse("notanid:secretsecret"))
        assertNull(Ga4Stream.parse("G-ABC123:"))
        val cfg = AnalyticsConfig.fromEnv(mapOf(
            "CCPOCKET_ANALYTICS_ENABLED" to "true",
            "CCPOCKET_ANALYTICS_STREAM_PRODUCTION" to "G-PROD1:prodsecret",
            "CCPOCKET_ANALYTICS_TOKEN_KEY" to "ab".repeat(32),
        )::get)
        assertTrue(cfg.active); assertEquals(setOf("production"), cfg.streams.keys)
        assertEquals(32, cfg.tokenKey.size); assertEquals(0xab.toByte(), cfg.tokenKey[0])
        assertFalse(AnalyticsConfig.fromEnv { null }.active)
    }
}
