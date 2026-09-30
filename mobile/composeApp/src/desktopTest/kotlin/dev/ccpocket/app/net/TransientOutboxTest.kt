package dev.ccpocket.app.net

import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListPendingApprovals
import dev.ccpocket.protocol.ProjectPinOp
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SyncProjectPins
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The connection outbox's transient entries (voice memo upload and dispatch): a frame the user confirmed once
 * belongs to the one connection it was queued for, reports exactly one terminal state, and is never re-flushed by
 * a reconnect, a supersede or a LAN→relay drain — while ordinary and pin frames keep their behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransientOutboxTest {

    private fun prompt(n: Int) = SendPrompt("convo", "todo $n", promptId = "p$n")
    private val valid = TransientDispatchFence { true }

    private fun TransientTicket.state(): TransientDisposition? = if (outcome.isCompleted) outcome.getCompleted() else null

    @Test
    fun a_written_frame_reports_written_only_after_the_write_returned() = runTest {
        val outbox = ScopedOutbox()
        val ticket = outbox.tryEnqueueTransient(prompt(1), valid, expectedGen = 1) { 1 }
        assertEquals(TransientEnqueueResult.ACCEPTED, ticket.result)
        assertEquals(null, ticket.state(), "accepted is not delivered")

        val gate = CompletableDeferred<Unit>()
        val written = mutableListOf<Frame>()
        val writer = launch { runCatching { outbox.runWriter(1, { true }) { gate.await(); written += it } } }
        runCurrent()
        assertEquals(null, ticket.state(), "the write has started but not returned")
        gate.complete(Unit)
        runCurrent()
        assertEquals(TransientDisposition.WRITTEN, ticket.state())
        assertEquals(listOf<Frame>(prompt(1)), written)
        writer.cancel()
    }

    @Test
    fun a_stale_generation_or_a_dead_fence_is_refused_at_the_door() = runTest {
        val outbox = ScopedOutbox()
        val noConnection = outbox.tryEnqueueTransient(prompt(1), valid, expectedGen = 0) { 0 }
        val moved = outbox.tryEnqueueTransient(prompt(1), valid, expectedGen = 1) { 2 }
        val fenced = outbox.tryEnqueueTransient(prompt(1), { false }, expectedGen = 1) { 1 }
        for (ticket in listOf(noConnection, moved, fenced)) {
            assertEquals(TransientEnqueueResult.RETIRED, ticket.result)
            assertEquals(TransientDisposition.NOT_WRITTEN, ticket.state())
        }
        assertTrue(outbox.drainOrdinary().isEmpty(), "nothing was queued")
    }

    @Test
    fun a_full_outbox_refuses_without_queueing() = runTest {
        val outbox = ScopedOutbox()
        var accepted = 0
        var full: TransientTicket? = null
        repeat(200) { // well past the default buffered capacity
            val t = outbox.tryEnqueueTransient(prompt(it), valid, 1) { 1 }
            if (t.result == TransientEnqueueResult.ACCEPTED) accepted++ else full = full ?: t
        }
        assertTrue(accepted > 0)
        assertEquals(TransientEnqueueResult.FULL, full?.result)
        assertEquals(TransientDisposition.NOT_WRITTEN, full?.state())
    }

    @Test
    fun a_fence_that_fails_after_queueing_drops_the_frame_before_the_write() = runTest {
        val outbox = ScopedOutbox()
        var holds = true
        val ticket = outbox.tryEnqueueTransient(prompt(1), { holds }, 1) { 1 }
        holds = false
        val written = mutableListOf<Frame>()
        val writer = launch { runCatching { outbox.runWriter(1, { true }) { written += it } } }
        runCurrent()
        assertEquals(TransientDisposition.NOT_WRITTEN, ticket.state())
        assertTrue(written.isEmpty())
        writer.cancel()
    }

    @Test
    fun a_superseded_writer_drops_the_transient_frame_and_hands_back_the_ordinary_one() = runTest {
        val outbox = ScopedOutbox()
        var current = 1
        val ticket = outbox.tryEnqueueTransient(prompt(1), valid, 1) { current }
        outbox.send(ListPendingApprovals)
        current = 2

        val oldWritten = mutableListOf<Frame>()
        var oldEnd: Result<Unit>? = null
        val old = launch { oldEnd = runCatching { outbox.runWriter(1, { current == 1 }) { oldWritten += it } } }
        runCurrent()
        assertIs<DeadLinkException>(oldEnd?.exceptionOrNull())
        assertTrue(oldWritten.isEmpty())
        assertEquals(TransientDisposition.NOT_WRITTEN, ticket.state())
        old.cancel()

        outbox.prepareFor(2)
        val newWritten = mutableListOf<Frame>()
        val live = launch { runCatching { outbox.runWriter(2, { current == 2 }) { newWritten += it } } }
        runCurrent()
        assertEquals(listOf<Frame>(ListPendingApprovals), newWritten, "the confirmed prompt is not re-flushed on the new connection")
        live.cancel()
    }

    @Test
    fun the_post_handshake_backlog_drops_another_connections_frame() = runTest {
        val outbox = ScopedOutbox()
        val ticket = outbox.tryEnqueueTransient(prompt(1), valid, 1) { 1 }
        outbox.send(ListDirectories())
        outbox.prepareFor(2)
        assertEquals(TransientDisposition.NOT_WRITTEN, ticket.state())
        assertEquals(listOf<Frame>(ListDirectories()), outbox.drainOrdinary())
    }

    @Test
    fun a_fallback_drain_never_returns_a_transient_frame_for_rerouting() = runTest {
        val outbox = ScopedOutbox()
        val ticket = outbox.tryEnqueueTransient(prompt(1), valid, 1) { 1 }
        outbox.send(ListPendingApprovals)
        assertEquals(listOf<Frame>(ListPendingApprovals), outbox.drainOrdinary())
        assertEquals(TransientDisposition.NOT_WRITTEN, ticket.state())
    }

    @Test
    fun a_write_that_throws_is_indeterminate() = runTest {
        val outbox = ScopedOutbox()
        val ticket = outbox.tryEnqueueTransient(prompt(1), valid, 1) { 1 }
        var end: Result<Unit>? = null
        val writer = launch { end = runCatching { outbox.runWriter(1, { true }) { throw DeadLinkException() } } }
        runCurrent()
        assertIs<DeadLinkException>(end?.exceptionOrNull())
        assertEquals(TransientDisposition.INDETERMINATE, ticket.state())
        writer.cancel()
    }

    @Test
    fun a_write_cancelled_midway_is_indeterminate_and_later_frames_are_not_written() = runTest {
        val outbox = ScopedOutbox()
        val first = outbox.tryEnqueueTransient(prompt(1), valid, 1) { 1 }
        val second = outbox.tryEnqueueTransient(prompt(2), valid, 1) { 1 }
        val never = CompletableDeferred<Unit>()
        val written = mutableListOf<Frame>()
        val writer = launch { runCatching { outbox.runWriter(1, { true }) { never.await(); written += it } } }
        runCurrent()
        writer.cancel()
        runCurrent()
        outbox.retire(1)
        assertEquals(TransientDisposition.INDETERMINATE, first.state(), "bytes may be on the wire")
        assertEquals(TransientDisposition.NOT_WRITTEN, second.state(), "still queued when the connection ended")
        assertTrue(written.isEmpty())
    }

    @Test
    fun retiring_a_connection_keeps_ordinary_frames_and_another_connections_entries() = runTest {
        val outbox = ScopedOutbox()
        val mine = outbox.tryEnqueueTransient(prompt(1), valid, 1) { 1 }
        outbox.send(ListPendingApprovals)
        var gen = 1
        gen = 2
        val theirs = outbox.tryEnqueueTransient(prompt(2), valid, 2) { gen }
        outbox.retire(1)
        assertEquals(TransientDisposition.NOT_WRITTEN, mine.state())
        assertEquals(null, theirs.state())

        val written = mutableListOf<Frame>()
        val live = launch { runCatching { outbox.runWriter(2, { true }) { written += it } } }
        runCurrent()
        assertEquals(listOf<Frame>(ListPendingApprovals, prompt(2)), written)
        assertEquals(TransientDisposition.WRITTEN, theirs.state())
        live.cancel()
    }

    @Test
    fun a_retired_entry_is_never_written_even_if_a_writer_reaches_it() = runTest {
        val send = TransientSend(valid)
        send.drop()
        assertFalse(send.claimForWrite(), "the drop path claimed it first")
        assertEquals(TransientDisposition.NOT_WRITTEN, send.outcome.getCompleted())

        val claimed = TransientSend(valid)
        assertTrue(claimed.claimForWrite())
        claimed.drop()
        assertFalse(claimed.outcome.isCompleted, "a drop cannot report not-written for a frame a writer holds")
        claimed.written(ok = true)
        assertEquals(TransientDisposition.WRITTEN, claimed.outcome.getCompleted())
    }

    @Test
    fun once_its_memo_frames_are_settled_the_outbox_is_left_alone_again() = runTest {
        val outbox = ScopedOutbox()
        val ticket = outbox.tryEnqueueTransient(prompt(1), valid, 1) { 1 }
        val written = mutableListOf<Frame>()
        val writer = launch { runCatching { outbox.runWriter(1, { true }) { written += it } } }
        runCurrent()
        assertEquals(TransientDisposition.WRITTEN, ticket.state())
        writer.cancel()
        runCurrent()

        // a frame a closing connection would have to drop if it looked: it must not look
        var holds = true
        val waiting = outbox.tryEnqueueTransient(prompt(2), { holds }, 2) { 2 }
        holds = false
        outbox.prepareFor(2) // keeps it (same connection); the fence is only read by a writer
        val refused = outbox.tryEnqueueTransient(prompt(3), valid, 0) { 0 }
        assertEquals(TransientDisposition.NOT_WRITTEN, refused.state(), "a refused frame is settled at once")
        outbox.retire(2)
        assertEquals(TransientDisposition.NOT_WRITTEN, waiting.state(), "still unsettled, so the closing connection does look")
    }

    @Test
    fun retire_leaves_an_outbox_that_never_saw_a_transient_frame_untouched() = runTest {
        val outbox = ScopedOutbox()
        outbox.send(ListDirectories())
        val pin = SyncProjectPins("req-1-0123456789ab", "sub-0123456789abcdef", "stream-0123456789ab", listOf(ProjectPinOp(1, "/p", true)), "inc-1")
        assertEquals(PinEnqueueResult.ACCEPTED, outbox.tryEnqueuePin(pin, { true }, 1) { 1 })
        outbox.retire(1)
        val written = mutableListOf<Frame>()
        val live = launch { runCatching { outbox.runWriter(1, { true }) { written += it } } }
        runCurrent()
        assertEquals(listOf<Frame>(ListDirectories(), pin), written, "pin and ordinary behaviour is unchanged")
        live.cancel()
    }
}
