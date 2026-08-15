package dev.ccpocket.relay.net

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rate limiters key on [clientIp], so whether a caller can CHOOSE that key decides whether a
 * 6-digit pair code is brute-forceable. Ktor's default is the FIRST `X-Forwarded-For` entry and our
 * reverse proxy APPENDS rather than replaces — that combination makes the key caller-controlled.
 * [installRelayForwardedHeaders] pins the last hop instead; this test exists to keep it pinned.
 */
class ClientIpTest {

    /** Serves `call.clientIp()` back, through the exact plugin config [RelayServer] installs. */
    private fun withRelayHeaderConfig(block: (port: Int) -> Unit) {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            installRelayForwardedHeaders()
            routing { get("/whoami") { call.respondText(call.clientIp()) } }
        }
        server.start(wait = false)
        try {
            block(runBlocking { server.engine.resolvedConnectors().first().port })
        } finally {
            server.stop(0, 0)
        }
    }

    private fun whoami(port: Int, xff: String?): String {
        val conn = URI("http://127.0.0.1:$port/whoami").toURL().openConnection() as HttpURLConnection
        xff?.let { conn.setRequestProperty("X-Forwarded-For", it) }
        return try {
            conn.inputStream.readBytes().decodeToString()
        } finally {
            conn.disconnect()
        }
    }

    @Test fun forged_leading_hops_are_ignored_the_last_one_wins() = withRelayHeaderConfig { port ->
        // What an attacker sends, plus what the proxy appends. Everything before the final comma is
        // attacker-authored; only the tail is a hop we observed. Reading the head would let the caller
        // rotate limiter buckets per request and never trip a lockout.
        assertEquals("203.0.113.9", whoami(port, "6.6.6.6, 7.7.7.7, 203.0.113.9"))
    }

    @Test fun a_single_forged_hop_still_does_not_become_the_key_of_a_second_request() =
        withRelayHeaderConfig { port ->
            // Two requests, two forged identities, one real tail: both must land on the SAME key, or
            // the lockout counter resets on every attempt.
            val first = whoami(port, "10.0.0.1, 198.51.100.7")
            val second = whoami(port, "10.0.0.2, 198.51.100.7")
            assertEquals("198.51.100.7", first)
            assertEquals(first, second)
        }

    @Test fun no_forwarded_header_falls_back_to_the_real_peer() = withRelayHeaderConfig { port ->
        // Ktor's `origin.remoteHost` is a host NAME when there is no header to read, so a direct
        // loopback call reports "localhost" rather than the dotted quad. Either spelling is the real
        // peer, which is all that matters here — in production Caddy always supplies the header, and
        // an unproxied request reaching the relay at all means someone bypassed the proxy.
        assertTrue(whoami(port, null) in setOf("127.0.0.1", "localhost"))
    }
}
