package dev.ccpocket.app.telemetry

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryDeliveryTest {
    @Test fun optOutCancelsInFlightAndDropsQueuedItemsAcrossReenable() = runTest {
        val started = mutableListOf<Int>()
        val completed = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        val delivery = TelemetryDelivery<Int>(backgroundScope, true) {
            started += it
            if (it == 1) gate.await()
            completed += it
        }
        assertTrue(delivery.offer(1))
        runCurrent()
        assertEquals(listOf(1), started)
        assertTrue(delivery.offer(2))
        delivery.setEnabled(false)
        assertFalse(delivery.offer(3))
        delivery.setEnabled(true)
        assertTrue(delivery.offer(4))
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 4), started)
        assertEquals(listOf(4), completed)
        delivery.close()
    }

    @Test fun optOutBeforeWorkerStartsAndAFullQueueNeverStartOldWork() = runTest {
        val sent = mutableListOf<Int>()
        val delivery = TelemetryDelivery<Int>(backgroundScope, true, capacity = 2) { sent += it }
        assertTrue(delivery.offer(1))
        assertTrue(delivery.offer(2))
        assertFalse(delivery.offer(3))
        val oldGeneration = delivery.currentGeneration()
        delivery.setEnabled(false)
        delivery.setEnabled(true)
        assertFalse(delivery.offer(9, oldGeneration), "a producer that prepared data before opt-out stays invalid")
        runCurrent()
        assertTrue(sent.isEmpty())
        assertTrue(delivery.offer(4))
        runCurrent()
        assertEquals(listOf(4), sent)
        delivery.close()
    }

    @Test fun transportFailureDoesNotRetryOrBlockFollowingEvents() = runTest {
        val attempted = mutableListOf<Int>()
        val delivery = TelemetryDelivery<Int>(backgroundScope, true) {
            attempted += it
            if (it == 1) error("TRANSPORT_SECRET")
        }
        delivery.offer(1); delivery.offer(2)
        runCurrent()
        assertEquals(listOf(1, 2), attempted)
        delivery.close()
    }
}
