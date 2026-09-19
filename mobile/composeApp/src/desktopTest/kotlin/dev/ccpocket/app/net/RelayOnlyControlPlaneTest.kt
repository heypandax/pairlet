package dev.ccpocket.app.net

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.util.B64Url
import dev.ccpocket.protocol.PushRegistrationOutcome
import dev.ccpocket.protocol.PushRegistrationResult
import dev.ccpocket.protocol.RegisterPush
import dev.ccpocket.protocol.e2e.E2ECrypto
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #389 review problem 3: an authenticated relay is enough to register a push token. The control plane must
 * not wait for the daemon's E2E handshake — with the computer off that handshake never completes, and a
 * RegisterPush (or the clearing one for "notifications off") used to sit in the control outbox until the
 * send timed out, its receipt dropped by the handshake-phase reader even if it had gone out.
 */
class RelayOnlyControlPlaneTest {
    private fun paired(relay: FakeRelayServer) = PairedDaemon(
        relay = relay.url, accountId = "acct",
        daemonPub = B64Url.encode(E2ECrypto.generateKeyPair().publicRaw), // a daemon that is never there
        deviceId = "dev", credential = "cred",
    )

    @Test fun aRegistrationAndItsReceiptCompleteOnARelayWithNoDaemon() = FakeRelayServer().use { relay ->
        runBlocking {
            val conn = RelayE2EConnection()
            val pairing = paired(relay)
            // queued BEFORE the socket exists: the outbox buffers across (re)connects
            val receipt = async(start = CoroutineStart.UNDISPATCHED) {
                conn.control.filterIsInstance<PushRegistrationResult>().first { it.requestId == "rid-1" }
            }
            val written = async(start = CoroutineStart.UNDISPATCHED) {
                conn.sendControlAwaitWritten(pairing, RegisterPush("fcm", "tok-A", "rid-1"))
            }
            val link = launch(Dispatchers.IO) { runCatching { conn.connect(pairing, E2ECrypto.generateKeyPair(), null) } }
            try {
                // well inside HANDSHAKE_TIMEOUT_MS: the answer must not be waiting on the handshake to give up
                withTimeout(5_000) { assertTrue(written.await(), "the frame must reach the attached socket") }
                val r = withTimeout(5_000) { receipt.await() }
                assertEquals(PushRegistrationOutcome.STORED, r.result)
                assertEquals(listOf("tok-A"), relay.registrations.map { it.token })
                assertEquals(0, conn.liveConnection, "no daemon answered: the E2E handshake is still pending")
                assertEquals(1, relay.handshakes.get(), "the handshake was offered — and is still unanswered")
                assertEquals(1, relay.connections.get(), "one socket per device: no helper dial to supersede it")
            } finally {
                link.cancel()
            }
        }
    }

    @Test fun aClearingRegistrationIsAcknowledgedTooBeforeAnyHandshake() = FakeRelayServer().use { relay ->
        runBlocking {
            val conn = RelayE2EConnection()
            val pairing = paired(relay)
            val link = launch(Dispatchers.IO) { runCatching { conn.connect(pairing, E2ECrypto.generateKeyPair(), null) } }
            try {
                val receipt = async(start = CoroutineStart.UNDISPATCHED) {
                    conn.control.filterIsInstance<PushRegistrationResult>().first { it.requestId == "rid-off" }
                }
                withTimeout(5_000) { assertTrue(conn.sendControlAwaitWritten(pairing, RegisterPush("fcm", "", "rid-off"))) }
                assertEquals(PushRegistrationOutcome.CLEARED, withTimeout(5_000) { receipt.await() }.result)
                assertEquals(0, conn.liveConnection)
            } finally {
                link.cancel()
            }
        }
    }
}
