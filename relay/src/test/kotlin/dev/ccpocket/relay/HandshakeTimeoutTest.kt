package dev.ccpocket.relay

import dev.ccpocket.protocol.Attached
import dev.ccpocket.protocol.Challenge
import dev.ccpocket.relay.store.InMemoryRelayStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Audit M2: before authentication the relay waited for hello/auth with a bare `incoming.receive()`. Any
 * WebSocket library answers the relay's pings on its own, so a socket that never sends a hello was kept
 * alive forever (coroutine + socket + a Caddy connection each). The handshake now has a deadline.
 */
class HandshakeTimeoutTest {
    private fun relay() = RelayServer("127.0.0.1", 0, InMemoryRelayStore(), handshakeTimeoutMs = 300)

    private fun RelayWsHarness.Peer.awaitClose() = try {
        closed.get(3, TimeUnit.SECONDS)
    } catch (_: TimeoutException) {
        fail("a socket that never authenticated is still open")
    }

    @Test fun device_socket_that_never_says_hello_is_closed() = RelayWsHarness(relay()).use { h ->
        assertEquals("handshake_timeout", h.device().awaitClose().reason)
    }

    @Test fun daemon_socket_that_never_says_hello_is_closed() = RelayWsHarness(relay()).use { h ->
        assertEquals("handshake_timeout", h.daemon().awaitClose().reason)
    }

    @Test fun daemon_socket_that_never_answers_the_challenge_is_closed() = RelayWsHarness(relay()).use { h ->
        val d = h.daemon()
        d.sendControl(RelayWsHarness.DaemonKeys().hello)
        d.expectControl<Challenge>()
        assertEquals("handshake_timeout", d.awaitClose().reason)
    }

    @Test fun a_prompt_handshake_is_unaffected_and_the_attached_socket_has_no_deadline() {
        val store = InMemoryRelayStore()
        val hello = kotlinx.coroutines.runBlocking { RelayWsHarness.seedDevice(store, "acct", "DDDDDDDDDDDDDDDDDDDDDD") }
        RelayWsHarness(RelayServer("127.0.0.1", 0, store, handshakeTimeoutMs = 300)).use { h ->
            val phone = h.device()
            phone.sendControl(hello)
            phone.expectControl<Attached>()
            Thread.sleep(1_000) // well past the handshake deadline
            assertEquals(false, phone.closed.isDone, "an authenticated socket was closed by the handshake deadline")
        }
    }
}
