package dev.ccpocket.daemon.claude

import java.net.URI
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.net.ssl.SSLSession
import java.util.Optional
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #167 ②: ask the gateway for its own model list instead of trusting a hand-written table.
 *
 * The point of these cases is that EVERY failure is a silent fallback. This probe sits in front of the
 * model picker opening, and it carries the user's gateway credential — so "couldn't tell" must always
 * degrade to the seed list, never to an error, a retry, or a request to somewhere unexpected.
 */
class GatewayModelProbeTest {

    @BeforeTest
    fun clean() = GatewayModelProbe.clearCache()

    private fun gw(url: String, token: String, v: String = "ANTHROPIC_AUTH_TOKEN") =
        GatewayDetector.Paired(url, token, v)

    /** Minimal HttpResponse stand-in — only status and body are ever read. */
    private fun res(status: Int, body: String, uri: String = "https://gw.example/v1/models") =
        object : HttpResponse<String> {
            override fun statusCode() = status
            override fun body() = body
            override fun request(): HttpRequest = HttpRequest.newBuilder(URI(uri)).build()
            override fun uri(): URI = URI(uri)
            override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
            override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
            override fun sslSession(): Optional<SSLSession> = Optional.empty()
            override fun version(): java.net.http.HttpClient.Version = java.net.http.HttpClient.Version.HTTP_1_1
        }

    // ── the happy path ──────────────────────────────────────────────────────

    /** Anthropic's shape: `data[].id`. */
    @Test
    fun readsAnthropicShape() {
        val body = """{"data":[{"id":"deepseek-chat","display_name":"DeepSeek"},{"id":"deepseek-reasoner"}]}"""
        val got = GatewayModelProbe.fetch(gw("https://gw.example", "tok")) { res(200, body) }
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), got)
    }

    /** The OpenAI-compatible shape is the same envelope, so one parser covers both. */
    @Test
    fun readsOpenAiCompatibleShape() {
        val body = """{"object":"list","data":[{"id":"glm-4.7","object":"model","owned_by":"zhipu"}]}"""
        assertEquals(listOf("glm-4.7"), GatewayModelProbe.fetch(gw("https://gw.example", "tok")) { res(200, body) })
    }

    /** Second call inside the TTL must not hit the network again — the picker can be opened repeatedly. */
    @Test
    fun cachesWithinTtl() {
        var calls = 0
        val body = """{"data":[{"id":"a"}]}"""
        repeat(3) {
            GatewayModelProbe.fetch(gw("https://gw.example", "tok")) { calls++; res(200, body) }
        }
        assertEquals(1, calls, "repeat opens must reuse the memoized answer")
    }

    // ── every way it can fail, and the fallback each time ────────────────────

    /** Plaintext http is only acceptable to a gateway on this machine; anywhere else the credential
     *  would cross a wire in the clear. (A local relay on 127.0.0.1 is a supported setup.) */
    @Test
    fun plaintextOnlyToLoopback() {
        var remoteCalled = false
        assertNull(GatewayModelProbe.fetch(gw("http://gw.remote", "tok")) { remoteCalled = true; res(200, "{}") })
        assertTrue(!remoteCalled, "no credential over plaintext to a remote host")

        val local = GatewayModelProbe.fetch(gw("http://127.0.0.1:3456", "tok")) { res(200, """{"data":[{"id":"a"}]}""") }
        assertEquals(listOf("a"), local, "a machine-local gateway over http is fine")
    }

    /** Failure is memoized too: a gateway that hangs must not cost a fresh timeout per picker-open. */
    @Test
    fun failureIsNegativelyCached() {
        var calls = 0
        repeat(3) { GatewayModelProbe.fetch(gw("https://gw.example", "tok")) { calls++; null } }
        assertEquals(1, calls, "a failing gateway is asked once per negative-TTL, not once per open")
    }

    /** Rotating the credential must not serve the previous answer. */
    @Test
    fun cacheIsKeyedByCredentialToo() {
        var calls = 0
        val body = """{"data":[{"id":"a"}]}"""
        GatewayModelProbe.fetch(gw("https://gw.example", "tok-1")) { calls++; res(200, body) }
        GatewayModelProbe.fetch(gw("https://gw.example", "tok-2")) { calls++; res(200, body) }
        assertEquals(2, calls, "a different token is a different question")
    }

    /** Transport blew up (unreachable, TLS, timeout): null, no throw. */
    @Test
    fun transportFailureFallsBack() {
        assertNull(GatewayModelProbe.fetch(gw("https://gw.example", "tok")) { null })
    }

    /** Not 2xx (no such endpoint, bad credential, rate limited): null. */
    @Test
    fun nonSuccessStatusFallsBack() {
        assertNull(GatewayModelProbe.fetch(gw("https://gw.example", "tok")) { res(404, """{"data":[{"id":"x"}]}""") })
        assertNull(GatewayModelProbe.fetch(gw("https://gw2.example", "tok")) { res(401, "nope") })
    }

    /** A body we don't recognize is not an answer. */
    @Test
    fun unknownBodyShapeFallsBack() {
        assertNull(GatewayModelProbe.fetch(gw("https://gw.example", "tok")) { res(200, "not json at all") })
        assertNull(GatewayModelProbe.fetch(gw("https://gw2.example", "tok")) { res(200, """{"models":["a"]}""") })
    }

    /**
     * An empty `data[]` is indistinguishable from "this endpoint isn't really implemented", and treating
     * it as authoritative would EMPTY the picker's gateway section. Fall back instead.
     */
    @Test
    fun emptyListFallsBackRatherThanEmptyingThePicker() {
        assertNull(GatewayModelProbe.fetch(gw("https://gw.example", "tok")) { res(200, """{"data":[]}""") })
    }

    /** Non-http schemes never get a credential (a `file://` base URL must not be dereferenced). */
    @Test
    fun nonHttpSchemeIsRefused() {
        var called = false
        assertNull(GatewayModelProbe.fetch(gw("file:///etc/passwd", "tok")) { called = true; res(200, "{}") })
        assertTrue(!called, "only http(s) may receive the credential")
    }

    // ── what actually goes on the wire ──────────────────────────────────────

    /** The request goes to the CONFIGURED base and nowhere else, as a GET of /v1/models. */
    @Test
    fun requestTargetsConfiguredBaseOnly() {
        var seen: HttpRequest? = null
        GatewayModelProbe.fetch(gw("https://gw.example/", "tok")) { req -> seen = req; res(200, """{"data":[{"id":"a"}]}""") }
        assertEquals("https://gw.example/v1/models", seen?.uri().toString(), "trailing slash must not double up")
        assertEquals("GET", seen?.method())
    }

    /**
     * ONE header, matching the variable the credential came from — the same one the CLI would use.
     * Sending both spellings doubles the number of places upstream infrastructure can log the secret
     * (`Authorization` in particular gets special handling in proxies, APM and WAFs).
     */
    @Test
    fun sendsOnlyTheHeaderMatchingTheCredentialSpelling() {
        var seen: HttpRequest? = null
        GatewayModelProbe.fetch(gw("https://gw.a", "sekrit", "ANTHROPIC_AUTH_TOKEN")) { req ->
            seen = req; res(200, """{"data":[{"id":"a"}]}""")
        }
        assertEquals("Bearer sekrit", seen?.headers()?.firstValue("authorization")?.orElse(null))
        assertTrue(seen?.headers()?.firstValue("x-api-key")?.isPresent == false, "must not also send x-api-key")

        GatewayModelProbe.fetch(gw("https://gw.b", "sekrit", "ANTHROPIC_API_KEY")) { req ->
            seen = req; res(200, """{"data":[{"id":"a"}]}""")
        }
        assertEquals("sekrit", seen?.headers()?.firstValue("x-api-key")?.orElse(null))
        assertTrue(seen?.headers()?.firstValue("authorization")?.isPresent == false, "must not also send Bearer")
    }

    // ── parser edge cases & the caps that keep a frame under 4 MiB ───────────

    @Test
    fun parserDropsBlanksAndDuplicates() {
        val ids = GatewayModelProbe.parseIds("""{"data":[{"id":"a"},{"id":" "},{"id":"a"},{"no":"id"},{"id":" b "}]}""")
        assertEquals(listOf("a", "b"), ids, "blank/dup/missing ids drop out, values are trimmed")
    }

    @Test
    fun parserRejectsNonObjectBody() {
        assertNull(GatewayModelProbe.parseIds("[]"))
        assertNull(GatewayModelProbe.parseIds(""))
    }

    /**
     * A hostile or broken gateway must not be able to push `gatewayModels` past the 4 MiB frame ceiling:
     * that throws FrameTooBigException and DISCONNECTS the phone — a failure this repo has shipped once
     * already, and one that would recur on every picker-open.
     */
    @Test
    fun parserCapsCountAndLength() {
        val many = (1..5000).joinToString(",") { """{"id":"model-$it"}""" }
        assertEquals(200, GatewayModelProbe.parseIds("""{"data":[$many]}""")?.size, "id count is bounded")

        val huge = "x".repeat(10_000)
        val ids = GatewayModelProbe.parseIds("""{"data":[{"id":"$huge"},{"id":"ok"}]}""")
        assertEquals(listOf("ok"), ids, "an absurdly long id is not an id")
    }
}
