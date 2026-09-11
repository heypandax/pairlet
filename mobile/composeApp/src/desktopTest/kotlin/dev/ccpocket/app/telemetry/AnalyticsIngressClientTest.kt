package dev.ccpocket.app.telemetry

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Covers the ingress handshake WITHOUT a network: every request goes through an injected transport, so a
 * regression here can never leak an event or a token to a real host.
 */
class AnalyticsIngressClientTest {
    private data class Call(val path: String, val bearer: String?, val body: String)

    private class Fake(private val reply: (Int, Call) -> IngressResponse) {
        val calls = mutableListOf<Call>()
        suspend fun handle(path: String, bearer: String?, body: String): IngressResponse {
            val call = Call(path, bearer, body)
            calls += call
            return reply(calls.size, call)
        }
        fun paths() = calls.map { it.path.substringAfter("/v1/analytics/") }
    }

    private val register = "https://ingress.example/v1/analytics/register"
    private val collect = "https://ingress.example/v1/analytics/collect"
    private fun granted(token: String, expiresIn: Long = 86_400) =
        IngressResponse(200, """{"token":"$token","expires_in":$expiresIn}""")
    private val accepted = IngressResponse(202, """{"accepted":1}""")

    private fun client(fake: Fake, clock: () -> Long = { 0L }) =
        AnalyticsIngressClient("https://ingress.example", clock) { p, b, body -> fake.handle(p, b, body) }

    private suspend fun AnalyticsIngressClient.sendOne(name: String = "app_launch") =
        send("42.7", "1700000000000", name, mapOf<String, JsonElement>("edition" to JsonPrimitive("desktop")))

    @Test fun registersThenSendsTheEventWithTheIssuedBearer() = runBlocking {
        val fake = Fake { n, _ -> if (n == 1) granted("T1") else accepted }
        assertEquals(202, client(fake).sendOne())
        assertEquals(listOf(register, collect), fake.calls.map { it.path })
        assertNull(fake.calls[0].bearer, "register must not present a token")
        assertEquals("T1", fake.calls[1].bearer)
        assertEquals("42.7", Json.parseToJsonElement(fake.calls[0].body).jsonObject["install_id"]?.jsonPrimitive?.content)
    }

    @Test fun aValidTokenIsReusedInsteadOfReRegistering() = runBlocking {
        val fake = Fake { n, _ -> if (n == 1) granted("T1") else accepted }
        val client = client(fake)
        client.sendOne(); client.sendOne()
        assertEquals(listOf("register", "collect", "collect"), fake.paths())
        assertEquals(listOf("T1", "T1"), fake.calls.drop(1).map { it.bearer })
    }

    @Test fun aClosedIngressSilencesTheProcessForAnHour() = runBlocking {
        var now = 0L
        val fake = Fake { _, _ -> IngressResponse(204, "") }
        val client = client(fake) { now }
        assertEquals(204, client.sendOne())
        assertEquals(0, client.sendOne(), "dormant sends are suppressed, not attempted")
        now += 59 * 60 * 1000L
        assertEquals(0, client.sendOne())
        assertEquals(listOf("register"), fake.paths(), "no request may be made while dormant")
    }

    @Test fun anExpiredTokenIsReplacedOnceAndThenGivesUp() = runBlocking {
        var now = 0L
        // Both collects reject: the token is stale AND re-registering does not help (rotated server key).
        val fake = Fake { n, call -> if (call.path == register) granted("T$n") else IngressResponse(401, """{"error":"unauthorized"}""") }
        val client = client(fake) { now }
        assertEquals(401, client.sendOne())
        assertEquals(listOf("register", "collect", "register", "collect"), fake.paths())
        assertEquals(listOf("T1", "T3"), fake.calls.filter { it.path == collect }.map { it.bearer })
        assertEquals(fake.calls[1].body, fake.calls[3].body, "the resend must carry the same, never-forwarded batch")
        assertEquals(0, client.sendOne(), "a second 401 means ten minutes of silence")
        now += 10 * 60 * 1000L + 1
        assertEquals(401, client.sendOne(), "and then normal attempts resume")
    }

    @Test fun rateLimitingBacksOffForTenMinutes() = runBlocking {
        var now = 0L
        val fake = Fake { _, call -> if (call.path == register) granted("T1") else IngressResponse(429, """{"error":"rate_limited"}""") }
        val client = client(fake) { now }
        assertEquals(429, client.sendOne())
        now += 9 * 60 * 1000L
        assertEquals(0, client.sendOne())
        assertEquals(2, fake.calls.size)
        now += 61 * 1000L
        assertEquals(429, client.sendOne(), "the cached token survives the backoff, so only collect is retried")
        assertEquals(listOf("register", "collect", "collect"), fake.paths())
    }

    @Test fun theCollectBodyMatchesTheContractAndOmitsServerOwnedParams() = runBlocking {
        val fake = Fake { n, _ -> if (n == 1) granted("T1") else accepted }
        client(fake).sendOne("prompt_sent")
        val body = Json.parseToJsonElement(fake.calls[1].body).jsonObject
        assertEquals(1, body["v"]?.jsonPrimitive?.content?.toInt())
        assertEquals("42.7", body["client_id"]?.jsonPrimitive?.content)
        assertEquals("1700000000000", body["session_id"]?.jsonPrimitive?.content)
        val event = body["events"]!!.jsonArray.single().jsonObject
        assertEquals("prompt_sent", event["name"]?.jsonPrimitive?.content)
        assertEquals("desktop", event["params"]!!.jsonObject["edition"]?.jsonPrimitive?.content)
        assertNull(event["params"]!!.jsonObject["session_id"], "session_id is a top-level field, not a param")
        assertFalse(fake.calls[1].body.contains("engagement_time_msec"))
        assertFalse(fake.calls[1].body.contains("debug_mode"))
    }

    @Test fun resetDropsBothTheTokenAndTheDormancy() = runBlocking {
        val fake = Fake { _, call -> if (call.path == register) granted("T1") else IngressResponse(429, "") }
        val client = client(fake)
        client.sendOne()
        assertEquals(0, client.sendOne(), "dormant before reset")
        client.reset()
        assertEquals(429, client.sendOne())
        assertEquals(listOf("register", "collect", "register", "collect"), fake.paths(),
            "re-enabling collection starts a fresh handshake")
    }
}
