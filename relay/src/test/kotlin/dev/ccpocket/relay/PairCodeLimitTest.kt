package dev.ccpocket.relay

import dev.ccpocket.relay.net.rateLimitSubject
import dev.ccpocket.relay.store.InMemoryRelayStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 6-digit pair code (900 000 values, 120 s life) is guarded per source address. Two holes:
 *  - an IPv6 caller was counted per ADDRESS — one subscriber's /64 is 2^64 fresh buckets, so the per-IP limit
 *    never tripped;
 *  - nothing bounded the relay as a whole: enough sources together could sweep the code space.
 * Now IPv6 is counted per /64, and failed lookups draw on a relay-wide budget.
 */
class PairCodeLimitTest {
    private val http = HttpClient.newHttpClient()

    private fun lookup(port: Int, from: String, code: String = "000000"): Int {
        val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/pair/code"))
            .header("X-Forwarded-For", from) // the relay keys on the last hop, i.e. what Caddy wrote
            .POST(HttpRequest.BodyPublishers.ofString("""{"code":"$code"}"""))
            .build()
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test fun ipv6_addresses_are_counted_per_64_ipv4_and_names_unchanged() {
        val a = rateLimitSubject("2001:db8:1:2::5")
        assertEquals("2001:db8:1:2::/64", a)
        assertEquals(a, rateLimitSubject("2001:0DB8:0001:0002:ffff:1:2:3"))
        assertEquals(a, rateLimitSubject("[2001:db8:1:2::9]"))
        assertEquals("fe80:0:0:0::/64", rateLimitSubject("fe80::1%en0"))
        assertEquals("2001:db8:1:3::/64", rateLimitSubject("2001:db8:1:3::5"), "a neighbouring /64 is another subscriber")
        assertEquals("0:0:0:0::/64", rateLimitSubject("::1"))
        assertEquals("203.0.113.9", rateLimitSubject("::ffff:203.0.113.9"), "IPv4-mapped counts as its IPv4")
        assertEquals("203.0.113.9", rateLimitSubject("203.0.113.9"))
        assertEquals("localhost", rateLimitSubject("localhost"))
        assertEquals("1:2:3:zz::1", rateLimitSubject("1:2:3:zz::1"), "unparseable stays as-is (never resolved)")
        assertEquals("1::2::3", rateLimitSubject("1::2::3"))
    }

    @Test fun rotating_addresses_inside_one_ipv6_64_shares_one_limit() {
        val store = InMemoryRelayStore()
        RelayWsHarness(RelayServer("127.0.0.1", 0, store)).use { h ->
            val codes = (1..10).map { lookup(h.port, "2001:db8:1:2::${it.toString(16)}") }
            assertEquals(List(10) { 400 }, codes, "ten wrong codes: each answered invalid_or_expired")
            assertEquals(429, lookup(h.port, "2001:db8:1:2:abcd::1"), "the 11th from the same /64 must be limited")
            assertEquals(400, lookup(h.port, "2001:db8:1:3::1"), "another /64 has its own allowance")
        }
    }

    @Test fun failed_lookups_draw_on_a_relay_wide_budget_that_lifts_with_the_window() {
        val store = InMemoryRelayStore()
        val now = AtomicLong(1_000_000)
        val relay = RelayServer(
            "127.0.0.1", 0, store, clock = now::get,
            pairCodeFailureBudget = 3, pairCodeFailureWindowMs = 60_000,
        )
        RelayWsHarness(relay).use { h ->
            // three different sources, each far under its own per-address limit
            listOf("198.51.100.1", "198.51.100.2", "2001:db8:9::1").forEach { assertEquals(400, lookup(h.port, it)) }
            assertEquals(429, lookup(h.port, "203.0.113.50"), "a fresh source is refused once the relay-wide budget is spent")
            now.addAndGet(60_000)
            assertEquals(400, lookup(h.port, "203.0.113.50"), "the budget lifts by itself with its window")
        }
    }
}
