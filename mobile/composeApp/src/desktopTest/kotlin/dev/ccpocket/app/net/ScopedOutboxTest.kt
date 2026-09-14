package dev.ccpocket.app.net

import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListPendingApprovals
import dev.ccpocket.protocol.ProjectPinOp
import dev.ccpocket.protocol.SyncProjectPins
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The connection outbox's scoped project-pin entries (issue #362), driven through the same writer loop both E2E
 * connections run, on a controlled scheduler: a pin frame belongs to the one connection it was queued for, and every
 * path that used to hand queued frames onward — a superseding writer, the post-handshake backlog, the LAN→relay
 * drain — drops it, while ordinary frames keep their long-standing behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScopedOutboxTest {

    private fun pin(n: Int) = SyncProjectPins("req-$n-0123456789ab", "sub-0123456789abcdef", "stream-0123456789ab", listOf(ProjectPinOp(n.toLong(), "/p$n", true)), "inc-1")

    private val valid = PinDispatchFence { true }

    @Test
    fun a_pin_queued_for_one_connection_never_reaches_the_connection_that_supersedes_it() = runTest {
        val outbox = ScopedOutbox()
        var current = 1
        assertEquals(PinEnqueueResult.ACCEPTED, outbox.tryEnqueuePin(pin(1), valid, expectedGen = 1) { current })
        outbox.send(ListPendingApprovals)

        current = 2 // the switch lands before connection 1's writer ever takes the entry
        val oldWritten = mutableListOf<Frame>()
        var oldEnd: Result<Unit>? = null
        val old = launch { oldEnd = runCatching { outbox.runWriter(1, { current == 1 }) { oldWritten += it } } }
        runCurrent()
        assertIs<DeadLinkException>(oldEnd?.exceptionOrNull(), "the superseded writer dies")
        assertTrue(oldWritten.isEmpty())
        old.cancel()

        outbox.prepareFor(2)
        val newWritten = mutableListOf<Frame>()
        val live = launch { runCatching { outbox.runWriter(2, { current == 2 }) { newWritten += it } } }
        runCurrent()
        assertEquals(listOf<Frame>(ListPendingApprovals), newWritten, "the ordinary frame moves on; the pin frame does not")
        live.cancel()
    }

    @Test
    fun a_superseded_writer_hands_back_an_ordinary_frame_but_drops_a_pin_frame_it_already_took() = runTest {
        val outbox = ScopedOutbox()
        var current = 1
        outbox.send(ListDirectories())
        assertEquals(PinEnqueueResult.ACCEPTED, outbox.tryEnqueuePin(pin(1), valid, 1) { current })
        current = 2
        repeat(2) { // each dying writer takes one entry before noticing
            val job = launch { runCatching { outbox.runWriter(1, { current == 1 }) { error("a stale writer wrote") } } }
            runCurrent()
            job.cancel()
        }
        assertEquals(listOf<Frame>(ListDirectories()), outbox.drainOrdinary())
        assertTrue(outbox.drainOrdinary().isEmpty(), "nothing else is left behind")
    }

    @Test
    fun a_pin_whose_fence_fell_before_the_write_is_dropped_right_before_encoding() = runTest {
        val outbox = ScopedOutbox()
        var fenced = true
        assertEquals(PinEnqueueResult.ACCEPTED, outbox.tryEnqueuePin(pin(1), { fenced }, 3) { 3 })
        outbox.send(ListPendingApprovals)
        fenced = false // e.g. the binding was re-paired or the generation retired while the frame sat queued
        val written = mutableListOf<Frame>()
        val job = launch { runCatching { outbox.runWriter(3, { true }) { written += it } } }
        runCurrent()
        assertEquals(listOf<Frame>(ListPendingApprovals), written)
        job.cancel()
    }

    @Test
    fun the_lan_to_relay_drain_returns_ordinary_frames_only() {
        val outbox = ScopedOutbox()
        assertEquals(PinEnqueueResult.ACCEPTED, outbox.tryEnqueuePin(pin(1), valid, 7) { 7 })
        kotlinx.coroutines.runBlocking { outbox.send(ListDirectories("/a")) }
        assertEquals(PinEnqueueResult.ACCEPTED, outbox.tryEnqueuePin(pin(2), valid, 7) { 7 })
        assertEquals(listOf<Frame>(ListDirectories("/a")), outbox.drainOrdinary())
        outbox.prepareFor(7)
        assertTrue(outbox.drainOrdinary().isEmpty(), "the pins went with the drain instead of waiting for the relay")
    }

    @Test
    fun queueing_a_pin_never_suspends_and_says_why_it_did_not_queue() {
        val outbox = ScopedOutbox()
        assertEquals(PinEnqueueResult.RETIRED, outbox.tryEnqueuePin(pin(1), valid, 0) { 0 }, "no live connection")
        assertEquals(PinEnqueueResult.RETIRED, outbox.tryEnqueuePin(pin(1), valid, 1) { 2 }, "another connection is live")
        assertEquals(PinEnqueueResult.RETIRED, outbox.tryEnqueuePin(pin(1), { false }, 1) { 1 }, "the fence fell")
        var accepted = 0
        var result = PinEnqueueResult.ACCEPTED
        while (result == PinEnqueueResult.ACCEPTED && accepted < 10_000) {
            result = outbox.tryEnqueuePin(pin(accepted), valid, 1) { 1 }
            if (result == PinEnqueueResult.ACCEPTED) accepted++
        }
        assertEquals(PinEnqueueResult.FULL, result, "a full outbox is reported, not waited on")
        assertTrue(accepted in 1 until 10_000)
    }

    @Test
    fun the_post_handshake_backlog_keeps_this_connections_pins_in_order_and_still_dedupes_ordinary_frames() {
        val outbox = ScopedOutbox()
        kotlinx.coroutines.runBlocking {
            outbox.send(ListDirectories())
            outbox.tryEnqueuePin(pin(1), valid, 4) { 4 }
            outbox.send(ListDirectories())
            outbox.tryEnqueuePin(pin(2), valid, 5) { 5 } // queued for a connection that is already gone
            outbox.send(ListPendingApprovals)
        }
        outbox.prepareFor(4)
        val written = mutableListOf<Frame>()
        kotlinx.coroutines.runBlocking {
            val job = launch { runCatching { outbox.runWriter(4, { true }) { written += it } } }
            kotlinx.coroutines.delay(50)
            job.cancel()
        }
        assertEquals(listOf<Frame>(pin(1), ListDirectories(), ListPendingApprovals), written)
    }
}
