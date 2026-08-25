package dev.ccpocket.daemon.review

import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PairCredential
import dev.ccpocket.protocol.ReviewListing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FIRST-CONTACT PSK behaviour on the RECIPIENT side, against a fake transport that models what the
 * sender's daemon actually does (`DeviceSessions.onDevicePaired` + `transport`).
 *
 * There IS a known deadlock here (a lost first response leaves the two ends keyed apart forever), and
 * the obvious recipient-side cure — alternate ticket → empty until one lands — is what these tests exist
 * to keep OUT. See [PeerHandshake.psk]: when the sender's in-memory ticket anchor is missing at redeem
 * time it allow-lists the peer as a FULL-POWER device and arms an empty PSK, so an empty-PSK attempt
 * would not recover a review link — it would silently promote one to an owner credential. The deadlock
 * is the safe failure; the cure is not.
 */
class PeerHandshakeMigrationTest {

    private companion object {
        const val TICKET = "one-time-ticket"
    }

    /**
     * The sender daemon's half of first contact. `armed` is its `pskFor[deviceId]` entry: present until
     * the first frame it can decrypt, absent forever after (and persisted through its own restart, which
     * is why this object outlives the recipient's "process" below).
     */
    private class FakeSender {
        var armed: String? = TICKET
        val framesReceived = AtomicInteger(0)

        /** Would a handshake offering [psk] produce a session this daemon can decrypt? */
        fun accepts(psk: String): Boolean = armed?.let { it == psk } ?: psk.isEmpty()

        /** The first frame it decrypts proves the credential — the ticket has done its job (#161/§11.4). */
        fun onFrame() {
            framesReceived.incrementAndGet()
            armed = null
        }
    }

    /**
     * Dials the fake sender with whatever PSK [PeerHandshake] chose. A mismatch is NOT an exception at
     * the socket level in production — the handshake "succeeds" and then nothing ever decrypts — so it
     * is modelled as a connection that opens, carries nothing, and dies.
     */
    private class MigrationTransport(private val sender: FakeSender) : PeerTransport {
        val pskOffered = CopyOnWriteArrayList<String>()

        /** Set while the sender's replies are being lost on the way back to us. */
        @Volatile var dropResponses = false

        override fun generateKeys() = PeerKeys("cHJpdg", "cHVi")

        override suspend fun redeem(relay: String, ticket: String, devicePubB64: String) =
            PairCredential(deviceId = "devMe", credential = "bearer", accountId = "acctPeer")

        override suspend fun dial(link: PeerLink, secret: PeerLinkSecret, session: PeerSession) {
            val psk = PeerHandshake.psk(secret).decodeToString()
            pskOffered += psk
            if (!sender.accepts(psk)) {
                // wrong PSK: the peer cannot read us and we cannot read it. The socket eventually dies.
                delay(5)
                throw IllegalStateException("nothing decrypts under this psk")
            }
            val channel = PeerChannel { sender.onFrame() }
            session.onOpen(channel) // our first frame reaches the sender and finalizes its side
            if (dropResponses) {
                delay(5)
                throw IllegalStateException("the reply never made it back")
            }
            // a reply we CAN decrypt is what burns our own ticket
            session.onFrame(channel, ReviewListing(emptyList()) as Frame)
            delay(50)
        }
    }

    /** One recipient "process": fresh stores over the same files, fresh client, nothing shared in RAM. */
    private class Process(dir: java.io.File, scope: CoroutineScope, transport: PeerTransport) {
        val links = PeerLinkStore.load(dir.resolve("peer-links.json"), dir.resolve("peer-secrets.json"))
        val store = PeerInboxStore.load(dir.resolve("review-inbox.json"))
        var job: Job? = null

        fun run(scope: CoroutineScope, link: PeerLink, transport: PeerTransport) {
            job = scope.launch {
                PeerInboxClient(
                    link, links, store, transport, scope,
                    clock = { 1_000L }, backoffMs = { 5 }, resendIntervalMs = 10_000,
                ).run()
            }
        }
    }

    private suspend fun await(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            delay(5)
        }
    }

    private fun link(id: String = "pl_1") = PeerLink(
        id = id, label = "Panda", relay = "wss://relay.example", peerAccountId = "acctPeer",
        peerDaemonPub = TestKeys.DAEMON_PUB, deviceId = "devMe", fingerprint = "tiger-brick", joinedAt = 1,
    )

    /**
     * THE REGRESSION GUARD. A sender whose ticket anchor is gone (its own restart between the mint and
     * our redeem) allow-lists us as a full-power device and accepts ONLY an empty PSK. We must never
     * meet it there: nothing decrypts, the link is visibly dead, and a human re-invites — which is the
     * correct outcome, because the alternative is routing as an owner with no collaborator
     * classification behind it.
     */
    @Test
    fun an_unanchored_sender_is_never_met_with_an_empty_psk() {
        val dir = Files.createTempDirectory("ccp-psk-unanchored").toFile()
        val sender = FakeSender().also { it.armed = null } // its ticket anchor did not survive its restart
        val transport = MigrationTransport(sender)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                val p = Process(dir, scope, transport)
                assertTrue(p.links.put(link(), PeerLinkSecret("pl_1", "bearer", "priv", "pub", ticket = TICKET)))
                p.run(scope, link(), transport)

                await("several connect attempts") { transport.pskOffered.size >= 4 }
                assertTrue(
                    transport.pskOffered.all { it == TICKET },
                    "every attempt must offer the ticket — an empty one would be admitted as an OWNER: ${transport.pskOffered}",
                )
                assertEquals(0, sender.framesReceived.get(), "and nothing may reach it")
                assertEquals(
                    TICKET, p.links.secretOf("pl_1")?.ticket,
                    "the ticket is not burned by a link that never authenticated",
                )
            }
        } finally {
            scope.cancel()
        }
    }

    /** The happy path still works: the anchor is there, the ticket proves us, the reply burns it. */
    @Test
    fun a_first_contact_that_completes_burns_the_ticket_and_reconnects_empty() {
        val dir = Files.createTempDirectory("ccp-psk-happy").toFile()
        val sender = FakeSender()
        val transport = MigrationTransport(sender)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                val p = Process(dir, scope, transport)
                assertTrue(p.links.put(link(), PeerLinkSecret("pl_1", "bearer", "priv", "pub", ticket = TICKET)))
                p.run(scope, link(), transport)

                await("the ticket to be burned") { p.links.secretOf("pl_1")?.ticket == null }
                assertEquals(TICKET, transport.pskOffered.first(), "the first connect offers the ticket")
                assertTrue(sender.framesReceived.get() >= 1)

                // …and from here on it is an ordinary empty-PSK reconnect, which the sender now expects
                assertEquals("", PeerHandshake.psk(p.links.secretOf("pl_1")!!).decodeToString())
                assertEquals(0, p.links.secretOf("pl_1")!!.handshakeAttempts, "burning the ticket resets the counter")
            }
        } finally {
            scope.cancel()
        }
    }

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

    /** A pinned key that cannot be parsed can never complete a handshake — retrying it is a busy loop. */
    @Test
    fun an_unusable_pinned_key_stops_the_inbox_instead_of_retrying_forever() {
        val dir = Files.createTempDirectory("ccp-psk-badkey").toFile()
        val sender = FakeSender()
        val transport = MigrationTransport(sender)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                val p = Process(dir, scope, transport)
                val bad = link().copy(peerDaemonPub = "not-a-key")
                assertTrue(p.links.put(bad, PeerLinkSecret("pl_1", "bearer", "priv", "pub", ticket = TICKET)))
                PeerInboxClient(
                    bad, p.links, p.store, transport, scope,
                    clock = { 1_000L }, backoffMs = { 5 }, resendIntervalMs = 10_000,
                ).run() // returns rather than looping — the test would hang otherwise
                assertTrue(transport.pskOffered.isEmpty(), "it must not even dial")
            }
        } finally {
            scope.cancel()
        }
    }
}
