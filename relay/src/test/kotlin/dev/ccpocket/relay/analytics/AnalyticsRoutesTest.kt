package dev.ccpocket.relay.analytics

import dev.ccpocket.relay.RelayServer
import dev.ccpocket.relay.store.InMemoryRelayStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The HTTP layer over [AnalyticsIngress]: body cap before read, bearer parsing, 204 with empty body. */
class AnalyticsRoutesTest {
    private val install = "1.2"
    private val prod = Ga4Stream("G-PROD1234", "prod-secret-value")

    private fun <T> withServer(cfg: AnalyticsConfig, upstream: Int = 204, block: (base: String, calls: List<String>) -> T): T {
        val calls = mutableListOf<String>()
        val server = RelayServer("127.0.0.1", 0, InMemoryRelayStore(), analyticsConfig = cfg,
            ga4Forwarder = { _, body -> calls += body; upstream }).server()
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            return block("http://127.0.0.1:$port", calls)
        } finally { server.stop(100, 500) }
    }

    private val http = HttpClient.newHttpClient()
    private fun post(url: String, body: String, bearer: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        if (bearer != null) b.header("Authorization", "Bearer $bearer")
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun collect(n: Int = 1) = """{"v":1,"client_id":"$install","session_id":"7","events":[${(1..n).joinToString(",") {
        """{"name":"app_launch","params":{"app_platform":"desktop","edition":"desktop","analytics_schema":"v1","app_environment":"production"}}""" }}]}"""

    @Test fun register_then_collect_over_http() = withServer(AnalyticsConfig(true, mapOf("production" to prod), ByteArray(32), false)) { base, calls ->
        val reg = post("$base/v1/analytics/register", """{"v":1,"install_id":"$install"}""")
        assertEquals(200, reg.statusCode())
        val token = Json.parseToJsonElement(reg.body()).jsonObject["token"]!!.jsonPrimitive.content
        assertEquals(401, post("$base/v1/analytics/collect", collect()).statusCode())          // no bearer
        assertEquals(401, post("$base/v1/analytics/collect", collect(), "nope").statusCode())  // bad bearer
        val ok = post("$base/v1/analytics/collect", collect(), token)
        assertEquals(202, ok.statusCode()); assertEquals("""{"accepted":1}""", ok.body())
        assertTrue(ok.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
        assertEquals(1, calls.size)
        val big = post("$base/v1/analytics/collect", collect(1).dropLast(1) + " ".repeat(8300) + "}", token)
        assertEquals(413, big.statusCode()); assertEquals("""{"error":"too_large"}""", big.body())
        assertEquals(1, calls.size)
        val dev = post("$base/v1/analytics/collect", collect().replace("production", "development"), token)
        assertEquals(204, dev.statusCode()); assertEquals("", dev.body())
    }

    @Test fun disabled_ingress_is_204_over_http_and_other_routes_still_serve() = withServer(AnalyticsConfig.disabled()) { base, calls ->
        val reg = post("$base/v1/analytics/register", """{"v":1,"install_id":"$install"}""")
        assertEquals(204, reg.statusCode()); assertEquals("", reg.body())
        assertEquals(204, post("$base/v1/analytics/collect", collect(), "x").statusCode())
        assertTrue(calls.isEmpty())
        val health = http.send(HttpRequest.newBuilder(URI("$base/healthz")).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals("ok", health.body())
    }
}
