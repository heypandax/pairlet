package dev.ccpocket.relay

import dev.ccpocket.protocol.*
import dev.ccpocket.relay.store.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlin.test.*

/**
 * #389 review, issue 2: a device socket that has been REPLACED must never change the push row again —
 * not with a registration already inside the handler, not with a frame still queued on the old socket,
 * not with one arriving after the socket left. Otherwise: the user turns notifications off on the new
 * socket, gets the "cleared" receipt, and the old socket's late write restores the token.
 */
class PushSupersedeRaceTest {
    private fun seeded() = InMemoryRelayStore().also { base ->
        runBlocking {
            base.insertAccount("acct", ByteArray(32), 1)
            base.insertDevice(Device("dev", "acct", ByteArray(1), ByteArray(1), 1, null, false))
        }
    }

    private fun connection(receipts: MutableList<String>) =
        Conn("acct", Role.DEVICE, "dev", sendText = { receipts += it }, sendBinary = {}, close = {})

    private fun frame(token: String, requestId: String? = "rid-$token") =
        PocketJson.encodeToString(Envelope(id = "control", ts = 0, to = Route.RELAY, body = RegisterPush("fcm", token, requestId = requestId)))

    @Test fun superseded_connection_cannot_restore_token_after_clear_ack() = runBlocking {
        val base = seeded()
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var reads = 0
        val store = object : RelayStore by base {
            override suspend fun getDevice(deviceId: String): Device? {
                val result = base.getDevice(deviceId)
                if (++reads == 1) { started.complete(Unit); resume.await() }
                return result
            }
        }
        val server = RelayServer("127.0.0.1", 0, store, clock = { 1000 })
        val broker = server.broker
        val receipts = mutableListOf<String>()
        val oldReceipts = mutableListOf<String>()
        val old = connection(oldReceipts)
        broker.attachDevice(old)
        val delayed = launch { server.handleDeviceControl(old, frame("old-token")) }
        started.await()
        val current = connection(receipts)
        broker.attachDevice(current)?.close?.invoke("superseded")
        server.handleDeviceControl(current, frame(""))
        assertTrue(base.pushTargets("acct").isEmpty())
        assertEquals(1, receipts.size, "clear was acknowledged before old write resumes")
        resume.complete(Unit)
        delayed.join()
        assertTrue(base.pushTargets("acct").isEmpty(), "Superseded request restored ${base.pushTargets("acct")}")
        assertTrue(oldReceipts.isEmpty(), "a superseded socket gets no verdict: $oldReceipts")
    }

    @Test fun frames_queued_on_a_superseded_socket_or_after_it_left_write_nothing() = runBlocking {
        val base = seeded()
        val server = RelayServer("127.0.0.1", 0, base, clock = { 1000 })
        val receipts = mutableListOf<String>()
        val old = connection(receipts)
        server.broker.attachDevice(old)
        val current = connection(receipts)
        server.broker.attachDevice(current)
        server.handleDeviceControl(current, frame("new-token"))
        assertEquals(listOf("new-token"), base.pushTargets("acct").map { it.token })

        // still being read off the old socket after the swap: with and without a requestId (legacy client)
        server.handleDeviceControl(old, frame("old-token"))
        server.handleDeviceControl(old, frame("old-legacy", requestId = null))
        server.handleDeviceControl(old, frame(""))
        assertEquals(listOf("new-token"), base.pushTargets("acct").map { it.token })

        // the current socket leaves; a registration processed after that has no live socket to speak for
        server.broker.detachDevice(current)
        server.handleDeviceControl(current, frame(""))
        assertEquals(listOf("new-token"), base.pushTargets("acct").map { it.token })
        assertEquals(1, receipts.size, "only the live socket's registration was answered: $receipts")
    }
}
