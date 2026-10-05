package dev.ccpocket.daemon.control

import dev.ccpocket.protocol.PocketJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.server.cio.CIO as ServerCIO

/**
 * The gate in front of the token-authenticated local control API ([authorize] / [body] in
 * LocalControlGate.kt), exercised on the `/v1/local/execution/…` routes — the surface `pairlet agent`
 * drives. Each check has at least one test here: the token, a browser `Origin`, the JSON Content-Type,
 * a malformed body, the body size cap; plus the CLI client's "no daemon" path and the token file itself.
 *
 * The execution planes are deliberately absent (all deps null): a request the gate ADMITS then answers
 * `execution_unavailable`, which is how these tests tell "refused by the gate" from "let through".
 * [ExecutionControlRoutesTest] covers what the routes do once a plane is there.
 */
class LocalControlGateTest {

    private val token = "test-token-not-a-secret"

    private val noPlanes = ExecutionControlDeps(target = { null }, grants = { null }, client = { null })

    private fun <T> serving(block: suspend (HttpClient, String) -> T): T = runBlocking {
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            routing { installExecutionControl(noPlanes, token) }
        }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO)
        try {
            block(client, "http://127.0.0.1:$port$LOCAL_CONTROL_PREFIX")
        } finally {
            client.close()
            server.stop(0, 0)
        }
    }

    private suspend fun HttpClient.authGet(url: String) = get(url) { header(LocalControlToken.HEADER, token) }

    private suspend fun HttpClient.authPost(url: String, body: String): HttpResponse = post(url) {
        header(LocalControlToken.HEADER, token)
        header("Content-Type", "application/json")
        setBody(body)
    }

    private fun codeOf(text: String) =
        runCatching { PocketJson.decodeFromString(LocalError.serializer(), text).code }.getOrNull()

    private val runBody = """{"target":"xg_1","workspace":"app","agent":"claude","prompt":"x"}"""

    // ---- the token ----------------------------------------------------------

    @Test
    fun a_missing_or_wrong_token_is_refused_on_every_route() = serving { client, base ->
        val gets = listOf("$base/execution/targets", "$base/execution/grants", "$base/execution/runs/xr_1", "$base/execution/runs/xr_1/result")
        for (url in gets) {
            val none = client.get(url)
            assertEquals(HttpStatusCode.Unauthorized, none.status, url)
            assertEquals("unauthorized", codeOf(none.bodyAsText()), url)
            val wrong = client.get(url) { header(LocalControlToken.HEADER, "not-the-token") }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status, url)
        }
        val posts = listOf("$base/execution/run", "$base/execution/join", "$base/execution/grants", "$base/execution/runs/xr_1/cancel", "$base/execution/grants/xg_1/confirm")
        for (url in posts) {
            val none = client.post(url) { header("Content-Type", "application/json"); setBody("{}") }
            assertEquals(HttpStatusCode.Unauthorized, none.status, url)
            val wrong = client.post(url) {
                header(LocalControlToken.HEADER, "not-the-token")
                header("Content-Type", "application/json")
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status, url)
            assertEquals("unauthorized", codeOf(wrong.bodyAsText()), url)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.delete("$base/execution/grants/xg_1").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.delete("$base/execution/grants/xg_1") { header(LocalControlToken.HEADER, "not-the-token") }.status,
        )
        // …and the right token is let through (to a plane that is not there)
        val admitted = client.authGet("$base/execution/targets")
        assertEquals(HttpStatusCode.ServiceUnavailable, admitted.status)
        assertEquals("execution_unavailable", codeOf(admitted.bodyAsText()))
    }

    // ---- a browser Origin ---------------------------------------------------

    /** A page can reach loopback; the point is that it announces itself while doing so. */
    @Test
    fun a_browser_origin_is_refused_even_with_a_correct_token() = serving { client, base ->
        val res = client.get("$base/execution/targets") {
            header(LocalControlToken.HEADER, token)
            header("Origin", "https://evil.example")
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertEquals("forbidden_origin", codeOf(res.bodyAsText()))

        // …including the same-origin form a local page would send
        val loopback = client.post("$base/execution/run") {
            header(LocalControlToken.HEADER, token)
            header("Origin", "http://127.0.0.1:8799")
            header("Content-Type", "application/json")
            setBody(runBody)
        }
        assertEquals(HttpStatusCode.Forbidden, loopback.status)
        assertEquals("forbidden_origin", codeOf(loopback.bodyAsText()))
    }

    // ---- the Content-Type ---------------------------------------------------

    @Test
    fun a_post_without_a_json_content_type_is_refused() = serving { client, base ->
        val res = client.post("$base/execution/run") {
            header(LocalControlToken.HEADER, token)
            header("Content-Type", "text/plain")
            setBody(runBody)
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, res.status)
        assertEquals("bad_content_type", codeOf(res.bodyAsText()))
        // a charset parameter is fine — that is an ordinary well-formed JSON post
        val ok = client.post("$base/execution/run") {
            header(LocalControlToken.HEADER, token)
            header("Content-Type", "application/json; charset=utf-8")
            setBody(runBody)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, ok.status)
        assertEquals("execution_unavailable", codeOf(ok.bodyAsText()))
    }

    // ---- the body -----------------------------------------------------------

    @Test
    fun a_malformed_body_is_a_clean_bad_request() = serving { client, base ->
        val res = client.authPost("$base/execution/run", "{not json")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("bad_request", codeOf(res.bodyAsText()))
    }

    @Test
    fun an_oversized_body_is_rejected_before_json_decode() = serving { client, base ->
        // the cap is the value it has always had; pinned so a change to it is a visible decision
        assertEquals(196_608, MAX_LOCAL_BODY_BYTES)
        for (size in listOf(MAX_LOCAL_BODY_BYTES + 1, 400 * 1024)) {
            val res = client.authPost("$base/execution/run", "x".repeat(size))
            assertEquals(HttpStatusCode.PayloadTooLarge, res.status, "$size bytes")
            assertEquals("body_too_large", codeOf(res.bodyAsText()), "$size bytes")
        }
    }

    // ---- the CLI's "no daemon" path ----------------------------------------

    @Test
    fun the_cli_client_fails_cleanly_when_nothing_is_listening() = runBlocking {
        // port 1 is never a cc-pocket daemon; the point is a clear CliktError, never an implicit spawn
        val tokenPath = Files.createTempDirectory("ccp-client-token").resolve("token").toFile()
        LocalControlToken.loadOrCreate(tokenPath)
        val client = LocalControlClient(1, "start it: cc-pocket-daemon run", tokenPath = tokenPath)
        val err = runCatching { client.get("/execution/targets", LocalExecTargetsRes.serializer()) }.exceptionOrNull()
        assertTrue(err is com.github.ajalt.clikt.core.CliktError, "expected a CliktError, got $err")
        assertTrue("no cc-pocket daemon" in err.message.orEmpty(), err.message.orEmpty())
    }

    // ---- the token file -----------------------------------------------------

    @Test
    fun the_token_comparison_is_exact_and_survives_a_reload() {
        val dir = Files.createTempDirectory("ccp-token").toFile()
        val path = dir.resolve("local-control-token")
        val minted = LocalControlToken.loadOrCreate(path)
        assertTrue(minted.length >= 40, "the token must carry real entropy: ${minted.length} chars")
        assertEquals(minted, LocalControlToken.loadOrCreate(path), "a second call must not re-mint")
        assertEquals(minted, LocalControlToken.read(path))
        assertTrue(LocalControlToken.matches(minted, minted))
        assertFalse(LocalControlToken.matches(minted, null))
        assertFalse(LocalControlToken.matches(minted, ""))
        assertFalse(LocalControlToken.matches(minted, minted.dropLast(1)))
        assertFalse(LocalControlToken.matches(minted, minted + "x"))
    }

    @Test
    fun token_creation_fails_closed_when_it_cannot_be_persisted() {
        val dir = Files.createTempDirectory("ccp-token-fail").toFile()
        val parentIsAFile = dir.resolve("not-a-directory").apply { writeText("x") }
        val err = runCatching { LocalControlToken.loadOrCreate(parentIsAFile.resolve("token")) }.exceptionOrNull()
        assertNotNull(err, "the daemon and CLI must never each continue with a different in-memory token")
    }
}
