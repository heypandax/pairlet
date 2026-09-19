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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real control transports against an isolated relay. Receipt of the final queued frame proves the
 *  writer drained the earlier entries; absence assertions do not depend on a sleep or a quiet socket. */
class RelayControlCancellationTest {
    private fun paired(relay: FakeRelayServer) = PairedDaemon(
        relay.url, "acct", B64Url.encode(E2ECrypto.generateKeyPair().publicRaw), "dev", "cred",
    )

    @Test fun cancelled_registration_cannot_restore_token_after_lan_clear() = FakeRelayServer().use { relay ->
        runBlocking {
            withTimeout(10_000) {
                val conn = RelayE2EConnection()
                val pairing = paired(relay)
                // Queued during a relay outage, then superseded by the user turning notifications off.
                val old = async(start = CoroutineStart.UNDISPATCHED) {
                    conn.sendControlAwaitWritten(pairing, RegisterPush("fcm", "old-token", "cancelled"))
                }
                old.cancelAndJoin()
                // LAN recovery completes both the clear and the registrar's extra confirmation.
                repeat(2) { n ->
                    val result = RelayControlDial.deposit(pairing, RegisterPush("fcm", "", "clear-$n"), 5_000)
                    assertEquals(PushRegistrationOutcome.CLEARED, assertIs<DepositOutcome.Acked>(result).result.result)
                }
                val receipt = async(start = CoroutineStart.UNDISPATCHED) {
                    conn.control.filterIsInstance<PushRegistrationResult>().first { it.requestId == "barrier" }
                }
                val valid = async(start = CoroutineStart.UNDISPATCHED) {
                    conn.sendControlAwaitWritten(pairing, RegisterPush("fcm", "", "barrier"))
                }
                val socket = launch(Dispatchers.IO) { runCatching { conn.connect(pairing, E2ECrypto.generateKeyPair(), null) } }
                try {
                    assertTrue(valid.await(), "an uncancelled queued request must survive reconnect")
                    assertEquals(PushRegistrationOutcome.CLEARED, receipt.await().result)
                    assertEquals(listOf("clear-0", "clear-1", "barrier"), relay.registrations.map { it.requestId })
                    assertTrue(relay.registrations.all { it.token.isEmpty() })
                } finally { socket.cancel() }
            }
        }
    }

    @Test fun timed_out_registration_is_not_flushed_with_the_next_token() = FakeRelayServer().use { relay ->
        runBlocking {
            withTimeout(10_000) {
                val conn = RelayE2EConnection()
                val pairing = paired(relay)
                assertNull(withTimeoutOrNull(50) {
                    conn.sendControlAwaitWritten(pairing, RegisterPush("fcm", "expired-token", "expired"))
                })
                val receipt = async(start = CoroutineStart.UNDISPATCHED) {
                    conn.control.filterIsInstance<PushRegistrationResult>().first { it.requestId == "current" }
                }
                val valid = async(start = CoroutineStart.UNDISPATCHED) {
                    conn.sendControlAwaitWritten(pairing, RegisterPush("fcm", "new-token", "current"))
                }
                val socket = launch(Dispatchers.IO) { runCatching { conn.connect(pairing, E2ECrypto.generateKeyPair(), null) } }
                try {
                    assertTrue(valid.await())
                    assertEquals(PushRegistrationOutcome.STORED, receipt.await().result)
                    assertEquals(listOf("new-token"), relay.registrations.map { it.token })
                } finally { socket.cancel() }
            }
        }
    }

    @Test fun queued_frames_do_not_follow_a_pairing_switch() = FakeRelayServer().use { relay ->
        runBlocking {
            withTimeout(10_000) {
                val current = paired(relay)
                // Each identity component fences independently, even if a previous caller stayed alive.
                for (previous in listOf(
                    current.copy(relay = "ws://previous-relay.invalid"),
                    current.copy(accountId = "previous-account"),
                    current.copy(deviceId = "previous-device"),
                )) {
                    val conn = RelayE2EConnection()
                    val old = async(start = CoroutineStart.UNDISPATCHED) {
                        conn.sendControlAwaitWritten(previous, RegisterPush("fcm", "old-token", "previous"))
                    }
                    conn.sendControl(previous, RegisterPush("fcm", "old-legacy-token"))
                    val receipt = async(start = CoroutineStart.UNDISPATCHED) {
                        conn.control.filterIsInstance<PushRegistrationResult>().first { it.requestId == "current" }
                    }
                    val valid = async(start = CoroutineStart.UNDISPATCHED) {
                        conn.sendControlAwaitWritten(current, RegisterPush("fcm", "new-token", "current"))
                    }
                    val socket = launch(Dispatchers.IO) { runCatching { conn.connect(current, E2ECrypto.generateKeyPair(), null) } }
                    try {
                        assertEquals(false, old.await(), "the previous identity must be rejected before writing")
                        assertTrue(valid.await())
                        assertEquals(PushRegistrationOutcome.STORED, receipt.await().result)
                        assertTrue(relay.registrations.all { it.token == "new-token" })
                    } finally { socket.cancelAndJoin() }
                }
                assertEquals(3, relay.registrations.size)
            }
        }
    }
}
