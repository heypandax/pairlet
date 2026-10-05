package dev.ccpocket.daemon.peer

import dev.ccpocket.protocol.e2e.E2ECrypto
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FIRST-CONTACT PSK behaviour of a peer link (the #367 execution source dials with it): which PSK a
 * handshake offers, and the durable attempt counter beside it. See [PeerHandshake.psk] for why there is no
 * "alternate ticket → empty" cure — an empty-PSK attempt against a sender whose ticket anchor is gone would
 * be admitted as a FULL-POWER device.
 */
class PeerHandshakeTest {

    private companion object {
        const val TICKET = "one-time-ticket"
    }

    private fun link(id: String = "pl_1") = PeerLink(
        id = id, label = "Panda", relay = "wss://relay.example", peerAccountId = "acctPeer",
        peerDaemonPub = b64(E2ECrypto.generateKeyPair().publicRaw), deviceId = "devMe", fingerprint = "tiger-brick", joinedAt = 1,
    )

    /** The PSK choice itself: the held ticket, then empty. No third state. */
    @Test
    fun the_psk_is_the_ticket_while_held_and_empty_once_burned() {
        val withTicket = PeerLinkSecret("pl_1", "bearer", "priv", "pub", ticket = TICKET)
        assertEquals(TICKET, PeerHandshake.psk(withTicket).decodeToString())
        // it does NOT vary with the attempt count — that alternation is the unsafe cure, see the KDoc
        (0..8).forEach { n ->
            assertEquals(
                TICKET, PeerHandshake.psk(withTicket.copy(handshakeAttempts = n)).decodeToString(),
                "attempt $n must still offer the ticket",
            )
        }
        assertEquals("", PeerHandshake.psk(withTicket.copy(ticket = null, handshakeAttempts = 7)).decodeToString())
    }

    /** The counter is persisted BEFORE the attempt: an attempt whose outcome we never learn still moves
     *  it, or a machine that dies mid-handshake would retry the same losing PSK forever. */
    @Test
    fun the_attempt_counter_is_durable_and_resets_when_the_ticket_is_burned() {
        val dir = Files.createTempDirectory("ccp-psk-counter").toFile()
        val paths = arrayOf(dir.resolve("peer-links.json"), dir.resolve("peer-secrets.json"))
        val store = PeerLinkStore.load(paths[0], paths[1])
        assertTrue(store.put(link(), PeerLinkSecret("pl_1", "bearer", "priv", "pub", ticket = TICKET)))

        assertEquals(1, store.beginHandshake("pl_1")!!.handshakeAttempts)
        assertEquals(2, store.beginHandshake("pl_1")!!.handshakeAttempts)
        assertEquals(2, PeerLinkStore.load(paths[0], paths[1]).secretOf("pl_1")!!.handshakeAttempts, "durable")

        assertTrue(store.clearTicket("pl_1"))
        assertEquals(0, store.secretOf("pl_1")!!.handshakeAttempts)
        // and a burned link stops writing the file on every reconnect
        assertEquals(0, store.beginHandshake("pl_1")!!.handshakeAttempts)
    }
}
