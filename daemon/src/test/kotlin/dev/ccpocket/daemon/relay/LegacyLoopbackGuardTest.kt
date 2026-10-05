package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.control.LOCAL_CONTROL_PREFIX
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import io.ktor.server.cio.CIO as ServerCIO

/**
 * Audit 2026-10-04 H2 (compatible half): the legacy loopback routes refuse browser-shaped requests (an
 * `Origin`, a non-loopback `Host`) while the shipped CLI's exact request shape — Ktor CIO client, bare
 * `http://127.0.0.1:<port>/…`, `setBody(String)` with no Content-Type, no token — keeps working.
 * A real server on an ephemeral port; never the daemon's 8799.
 */
class LegacyLoopbackGuardTest {

    private class Served(val port: Int, val hits: AtomicInteger, val seen: ConcurrentHashMap<String, String>)

    private fun serving(block: suspend (Served, HttpClient) -> Unit) = runBlocking {
        val hits = AtomicInteger()
        val seen = ConcurrentHashMap<String, String>()
        fun record(call: ApplicationCall) {
            hits.incrementAndGet()
            seen["host"] = call.request.headers.getAll(HttpHeaders.Host).orEmpty().joinToString("|")
            seen["origin"] = call.request.headers[HttpHeaders.Origin] ?: "<none>"
            seen["contentType"] = call.request.headers[HttpHeaders.ContentType] ?: "<none>"
        }
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            routing {
                // the same shape PairLoopback uses: legacy routes on the guarded child…
                val legacy = LegacyLoopbackGuard.routes(this)
                legacy.post("/pair") { record(call); call.respondText("""{"ticket":"t"}""") }
                legacy.get("/status") { record(call); call.respondText("""{"attached":true}""") }
                legacy.post("/bridge/revoke") {
                    record(call)
                    seen["body"] = call.receiveText()
                    call.respondText("""{"revoked":"x"}""")
                }
                // …and a sibling (the token-gated /v1/local plane) the guard must not touch
                get("$LOCAL_CONTROL_PREFIX/probe") { call.respondText("sibling") }
            }
        }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO)
        try {
            block(Served(port, hits, seen), client)
        } finally {
            client.close()
            server.stop(0, 0)
        }
    }

    /** Status code of a hand-written request — the only way to put an arbitrary Host on the wire. */
    private fun rawStatus(port: Int, request: String): Int =
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().apply { write(request.toByteArray()); flush() }
            val line = s.getInputStream().bufferedReader().readLine() ?: return -1
            line.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        }

    private fun get(path: String, vararg headers: String) =
        "GET $path HTTP/1.1\r\n" + headers.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n"

    @Test
    fun the_shipped_cli_request_shape_still_works() = serving { s, client ->
        val base = "http://127.0.0.1:${s.port}"
        // exactly Main.kt's calls: bare POST /pair, POST with setBody(String) and no Content-Type, bare GET
        val pair = client.post("$base/pair")
        assertEquals(HttpStatusCode.OK, pair.status)
        assertEquals("127.0.0.1:${s.port}", s.seen["host"], "the CLI's Host is 127.0.0.1:<port>")
        assertEquals("<none>", s.seen["origin"], "the CLI sends no Origin")

        val revoke = client.post("$base/bridge/revoke") { setBody("""{"idOrName":"feishu-bot"}""") }
        assertEquals(HttpStatusCode.OK, revoke.status)
        assertEquals("""{"idOrName":"feishu-bot"}""", s.seen["body"])
        // Ktor 3.5.2 stamps `text/plain; charset=UTF-8` for setBody(String) — NOT application/json, which is
        // why the legacy guard must never demand a JSON Content-Type the way /v1/local does
        assertNotEquals("application/json", s.seen["contentType"]?.substringBefore(';'))

        assertEquals(HttpStatusCode.OK, client.get("$base/status").status)
        assertEquals(3, s.hits.get())
    }

    /** Folder sharing (#115) is retired and PairLoopback no longer registers `/share`, `/shares` or `/share/revoke`:
     *  a path the guarded child does not register answers an ordinary 404 to the CLI's request shape. */
    @Test
    fun a_path_no_longer_registered_answers_a_plain_404() = serving { s, client ->
        val base = "http://127.0.0.1:${s.port}"
        assertEquals(HttpStatusCode.NotFound, client.post("$base/share") { setBody("""{"workdir":"/w"}""") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("$base/shares").status)
        assertEquals(HttpStatusCode.NotFound, client.post("$base/share/revoke") { setBody("""{"deviceId":"d"}""") }.status)
        assertEquals(0, s.hits.get())
    }

    @Test
    fun a_request_carrying_an_origin_is_refused() = serving { s, client ->
        val base = "http://127.0.0.1:${s.port}"
        for (origin in listOf("https://evil.example", "null", "http://127.0.0.1:${s.port}", "http://localhost:${s.port}")) {
            val pair = client.post("$base/pair") { header(HttpHeaders.Origin, origin) }
            assertEquals(HttpStatusCode.Forbidden, pair.status, "POST /pair with Origin $origin")
            assertEquals("""forbidden_origin""", Regex("\"error\":\"([a-z_]+)\"").find(pair.bodyAsText())?.groupValues?.get(1))
            val status = client.get("$base/status") { header(HttpHeaders.Origin, origin) }
            assertEquals(HttpStatusCode.Forbidden, status.status, "GET /status with Origin $origin")
            // a text/plain "simple request" body — what a no-cors fetch would send — is refused too
            val revoke = client.post("$base/bridge/revoke") {
                header(HttpHeaders.Origin, origin)
                header(HttpHeaders.ContentType, "text/plain")
                setBody("""{"idOrName":"feishu-bot"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, revoke.status, "POST /bridge/revoke with Origin $origin")
        }
        assertEquals(0, s.hits.get(), "no refused request may reach a handler")
    }

    @Test
    fun a_non_loopback_or_wrong_port_host_is_refused() = serving { s, _ ->
        val p = s.port
        val refused = listOf(
            "Host: evil.example:$p",          // DNS rebinding: the page's own name, our port
            "Host: evil.example",
            "Host: 127.0.0.1:${p + 1}",       // loopback, wrong port
            "Host: 127.0.0.1",                // bare form only allowed when the port is 80
            "Host: localhost",
            "Host: [::1]:$p",                 // the listener is IPv4-only; the CLI never sends this
            "Host: 127.0.0.1.evil.example:$p",
            "Host: localhost.evil.example:$p",
            "Host: 0.0.0.0:$p",
        )
        for (host in refused) {
            assertEquals(403, rawStatus(p, get("/status", host)), host)
        }
        // two Host headers are ambiguous — refused (or rejected by the engine), never served
        assertNotEquals(200, rawStatus(p, get("/status", "Host: 127.0.0.1:$p", "Host: evil.example:$p")))
        // no Host at all (HTTP/1.0 allows it) — never served
        assertNotEquals(200, rawStatus(p, "GET /status HTTP/1.0\r\n\r\n"))
        assertEquals(0, s.hits.get(), "no refused request may reach a handler")

        // the loopback names on the right port pass; the host name is case-insensitive
        assertEquals(200, rawStatus(p, get("/status", "Host: 127.0.0.1:$p")))
        assertEquals(200, rawStatus(p, get("/status", "Host: localhost:$p")))
        assertEquals(200, rawStatus(p, get("/status", "Host: LocalHost:$p")))
        assertEquals(3, s.hits.get())
    }

    @Test
    fun routes_beside_the_guarded_child_are_untouched() = serving { s, client ->
        // the token-gated /v1/local plane keeps its own rules; the legacy guard must not leak onto it
        val res = client.get("http://127.0.0.1:${s.port}$LOCAL_CONTROL_PREFIX/probe") { header(HttpHeaders.Origin, "https://evil.example") }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("sibling", res.bodyAsText())
    }

    @Test
    fun rejection_rules() {
        assertNull(LegacyLoopbackGuard.rejection(null, listOf("127.0.0.1:8799"), 8799))
        assertNull(LegacyLoopbackGuard.rejection(null, listOf("localhost:8799"), 8799))
        assertEquals("forbidden_origin", LegacyLoopbackGuard.rejection("https://evil.example", listOf("127.0.0.1:8799"), 8799))
        assertEquals("forbidden_origin", LegacyLoopbackGuard.rejection("", listOf("127.0.0.1:8799"), 8799))
        assertEquals("forbidden_host", LegacyLoopbackGuard.rejection(null, emptyList(), 8799))
        assertEquals("forbidden_host", LegacyLoopbackGuard.rejection(null, listOf("127.0.0.1:8799", "127.0.0.1:8799"), 8799))
        assertEquals("forbidden_host", LegacyLoopbackGuard.rejection(null, listOf("127.0.0.1"), 8799))
        // a client omits the default port from Host — `--pair-port 80` must keep working
        assertNull(LegacyLoopbackGuard.rejection(null, listOf("127.0.0.1"), 80))
        assertNull(LegacyLoopbackGuard.rejection(null, listOf("localhost:80"), 80))
    }
}
