package dev.ccpocket.app.net

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.e2e.E2ECrypto
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * #403 / TRANSPORT-AUTO-REPATH-V1 3.1: one direct attempt is bounded end to end. A listener that accepts the TCP
 * connection and never answers the upgrade is the black hole the handshake timeout alone never covered.
 */
class DirectEstablishBudgetTest {
    private val paired = PairedDaemon(relay = "wss://127.0.0.1:9", accountId = "acct", daemonPub = "pk",
        deviceId = "dev", credential = "cred")
    private val keys = E2ECrypto.generateKeyPair()
    private val held = ArrayList<Socket>()
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).also { s ->
        thread(isDaemon = true) { runCatching { while (true) held += s.accept() } } // accept, never reply
    }

    @AfterTest fun tearDown() { server.close(); held.forEach { runCatching { it.close() } } }

    @Test fun blackHoleEndsAsUnreachableWithinTheBudget() = runBlocking {
        val conn = DirectE2EConnection(establishBudgetMs = 400)
        val t0 = TimeSource.Monotonic.markNow()
        val e = assertFailsWith<DirectUnreachableException> { conn.connect("ws://127.0.0.1:${server.localPort}/", paired, keys) }
        val took = t0.elapsedNow().inWholeMilliseconds
        assertEquals(DirectFallbackReason.BUDGET_EXPIRED, e.reason)
        assertTrue(took in 350..2_500, "ended at ${took}ms — budget 400ms plus scheduling slack")
        assertEquals(false, conn.connected)
    }

    @Test fun defaultBudgetIsThreeSeconds() {
        assertEquals(3_000L, DirectE2EConnection.DIRECT_ESTABLISH_BUDGET_MS)
    }

    @Test fun refusedPortIsUnreachableNotBudget() = runBlocking {
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).let { val p = it.localPort; it.close(); p }
        val e = assertFailsWith<DirectUnreachableException> {
            DirectE2EConnection(establishBudgetMs = 5_000).connect("ws://127.0.0.1:$closed/", paired, keys)
        }
        assertEquals(DirectFallbackReason.REFUSED, e.reason)
    }

    @Test fun callerCancellationStaysACancellation() = runBlocking {
        val conn = DirectE2EConnection(establishBudgetMs = 10_000)
        var thrown: Throwable? = null
        val job = launch { try { conn.connect("ws://127.0.0.1:${server.localPort}/", paired, keys) } catch (t: Throwable) { thrown = t; throw t } }
        delay(200)
        val t0 = TimeSource.Monotonic.markNow()
        job.cancel()
        job.join()
        assertTrue(thrown is CancellationException, "connect rethrew ${thrown} — a caller's cancel must not become unreachable")
        assertTrue(t0.elapsedNow().inWholeMilliseconds < 2_000, "cancel is prompt, not held until the budget")
    }
}
