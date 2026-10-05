package dev.ccpocket.relay

import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.Challenge
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.PairBegin
import dev.ccpocket.protocol.PairCodePayload
import dev.ccpocket.protocol.PairTicket
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.Route
import dev.ccpocket.relay.store.InMemoryRelayStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pairing security phase 0: the relay registers a 6-digit code ONLY for an interactive owner pairing.
 *
 * The code is how a phone or desktop App types/scans its way to the pairing payload; restricted mints
 * (a bridge's headless ticket, a #367 execution link's headless+collaborator ticket) are handed over as full
 * credentials and never read the code — a code registered for them was just a second, guessable door to
 * those credentials. They now get `code = ""`, which no lookup resolves. The wire shape is unchanged and an
 * old daemon's PairBegin (no markers at all) is still an interactive pairing that gets its code.
 */
class PairCodeInteractiveOnlyTest {
    private val http = HttpClient.newHttpClient()

    private fun lookup(port: Int, code: String): Pair<Int, String> {
        val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/pair/code"))
            .header("X-Forwarded-For", "198.51.100.7")
            .POST(HttpRequest.BodyPublishers.ofString("""{"code":"$code"}"""))
            .build()
        return http.send(req, HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
    }

    /** An authenticated daemon socket, past the attach replay barrier, ready for control frames. */
    private fun attachedDaemon(h: RelayWsHarness): Pair<RelayWsHarness.Peer, RelayWsHarness.DaemonKeys> {
        val keys = RelayWsHarness.DaemonKeys()
        val d = h.daemon()
        d.sendControl(keys.hello)
        d.sendControl(keys.auth(d.expectControl<Challenge>()))
        d.expectControl<Attached>()
        return d to keys
    }

    /** The next PairTicket, skipping the attach-time replay barrier and anything else unrelated. */
    private fun RelayWsHarness.Peer.nextTicket(): PairTicket {
        repeat(10) {
            val got = next() as? RelayWsHarness.In.Text ?: error("no control frame")
            val body = PocketJson.decodeFromString<Envelope>(got.text).body
            if (body is PairTicket) return body
        }
        error("no PairTicket")
    }

    @Test fun an_interactive_pairing_gets_a_code_that_resolves_to_its_ticket() {
        RelayWsHarness(RelayServer("127.0.0.1", 0, InMemoryRelayStore())).use { h ->
            val (d, keys) = attachedDaemon(h)
            d.sendControl(PairBegin(e2ePub = "daemon-pub"))
            val ticket = d.nextTicket()
            assertTrue(Regex("^[1-9][0-9]{5}$").matches(ticket.code), "a 6-digit code: ${ticket.code}")
            val (status, body) = lookup(h.port, ticket.code)
            assertEquals(200, status, body)
            val payload = PocketJson.decodeFromString<PairCodePayload>(body)
            assertEquals(PairCodePayload(keys.accountId, "daemon-pub", ticket.ticket), payload)
        }
    }

    @Test fun restricted_mints_get_no_redeemable_code() {
        RelayWsHarness(RelayServer("127.0.0.1", 0, InMemoryRelayStore())).use { h ->
            val (d, _) = attachedDaemon(h)
            // a bridge (#91), the #367 execution link, and the collaborator marker on its own
            listOf(
                PairBegin("daemon-pub", headless = true),
                PairBegin("daemon-pub", headless = true, collaborator = true),
                PairBegin("daemon-pub", collaborator = true),
            ).forEach { begin ->
                d.sendControl(begin)
                val ticket = d.nextTicket()
                assertEquals("", ticket.code, "no code for $begin")
                assertTrue(ticket.ticket.isNotEmpty(), "the full ticket is still minted for $begin")
            }
            assertEquals(400, lookup(h.port, "").first, "an empty code resolves to nothing")
        }
    }

    @Test fun an_old_daemons_pair_begin_without_markers_is_still_an_interactive_pairing() = runBlocking {
        RelayWsHarness(RelayServer("127.0.0.1", 0, InMemoryRelayStore())).use { h ->
            val (d, keys) = attachedDaemon(h)
            // what a pre-#91 daemon sends: no headless / collaborator keys at all
            val legacy = PocketJson.encodeToString(Envelope(id = "1", ts = 0, to = Route.RELAY, body = PairBegin("old-pub")))
                .replace(Regex(""","?(headless|collaborator)":(true|false)"""), "")
            assertTrue("headless" !in legacy && "collaborator" !in legacy, legacy)
            h.relay.handleDaemonControl(keys.accountId, legacy)
            val ticket = d.nextTicket()
            assertTrue(Regex("^[1-9][0-9]{5}$").matches(ticket.code), "an old daemon still gets its code")
            assertEquals(200, lookup(h.port, ticket.code).first)
        }
    }
}
