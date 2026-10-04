package dev.ccpocket.daemon.relay

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit 2026-10-04 (session-relay M7): RelayClient.run launches the idle reaper and the guest-expiry sweep
 * in the SAME coroutineScope as the reconnect loop. One throwing iteration failed its child, which cancelled
 * the scope — the reconnect loop with it — and run() threw out of Main.
 */
class ResidentLoopTest {

    @Test
    fun `a failing iteration neither ends the loop nor the sibling reconnect loop`() = runBlocking {
        val iterations = AtomicInteger()
        val failures = AtomicInteger()
        val siblingTicks = AtomicInteger()
        withTimeout(5_000) {
            coroutineScope {
                val loop = launch {
                    residentLoop(periodMs = 10, onFailure = { failures.incrementAndGet() }) {
                        if (iterations.incrementAndGet() == 1) error("reapIdle blew up")
                    }
                }
                val sibling = launch { while (true) { delay(10); siblingTicks.incrementAndGet() } }
                while (iterations.get() < 3) delay(10)
                loop.cancelAndJoin()
                sibling.cancelAndJoin()
            }
        }
        assertEquals(1, failures.get(), "the failure is reported once")
        assertTrue(iterations.get() >= 3, "the loop kept going after the failing iteration")
        assertTrue(siblingTicks.get() > 0)
    }
}
