package dev.ccpocket.daemon.relay

import dev.ccpocket.protocol.CreateBridge
import dev.ccpocket.protocol.CreateReviewInvite
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListBridges
import dev.ccpocket.protocol.PairTicket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The relay leg has ONE reader coroutine, and everything a device sends is decoded and dispatched on it.
 * A frame whose handler waits for a reply that arrives through that same reader therefore cannot be
 * dispatched inline: it blocks the loop it is waiting on, times out, and starves every other device for
 * the duration.
 *
 * Minting is exactly that shape — `createTicket` suspends until the relay answers with a [PairTicket] —
 * which is why the share/bridge mints run off the loop, selected by [isOwnerControlFrame].
 *
 * This models the loop rather than standing up a relay: the property under test is the DISPATCH DECISION,
 * and a socket would add nothing but flakiness.
 */
class OwnerFrameDispatchTest {

    /** A mint: suspends until the reader delivers the relay's PairTicket. */
    private class Mint {
        val ticket = CompletableDeferred<PairTicket>()
        val completed = CompletableDeferred<String>()
        suspend fun run() = completed.complete(ticket.await().ticket)
    }

    private val bridgeMint = CreateBridge("ci", listOf("/w"))

    /**
     * The reader loop, in the shape [DeviceSessions.route] gives it: owner frames that need the loop free
     * are launched, everything else is handled inline.
     */
    private suspend fun readerLoop(
        inbound: Channel<Frame>,
        mint: Mint,
        offReader: (Frame) -> Boolean,
        handledInline: MutableList<Frame>,
    ) = coroutineScope {
        val reader = launch {
            for (frame in inbound) {
                when {
                    frame is PairTicket -> mint.ticket.complete(frame)
                    frame is CreateBridge ->
                        if (offReader(frame)) launch { mint.run() } else mint.run()
                    else -> handledInline += frame
                }
            }
        }
        try {
            withTimeout(2_000) { mint.completed.await() }
        } finally {
            inbound.close()
            reader.cancelAndJoin()
        }
    }

    @Test
    fun a_ticket_mint_leaves_the_reader_free_to_deliver_the_pair_ticket_it_waits_for() = runBlocking {
        val inbound = Channel<Frame>(Channel.UNLIMITED)
        val mint = Mint()
        inbound.send(bridgeMint)
        // the relay's answer arrives through the SAME reader, AFTER the frame that is waiting for it
        inbound.send(PairTicket("TICKET-123", 120, "482913"))
        // …and an unrelated frame, which must not be stuck behind the mint either
        inbound.send(ListBridges)

        val handledInline = mutableListOf<Frame>()
        readerLoop(inbound, mint, ::isOwnerControlFrame, handledInline)

        assertEquals("TICKET-123", mint.completed.await())
        assertTrue(
            handledInline.any { it is ListBridges },
            "an unrelated frame behind the mint must still have been served",
        )
    }

    /**
     * The same loop with the frame dispatched INLINE — what happens if the predicate ever stops covering
     * it. Pinning the failure keeps the test above honest: without this, a predicate that always returned
     * true would pass and prove nothing.
     */
    @Test
    fun dispatching_the_same_frame_inline_deadlocks_the_reader() = runBlocking {
        val inbound = Channel<Frame>(Channel.UNLIMITED)
        val mint = Mint()
        inbound.send(bridgeMint)
        inbound.send(PairTicket("TICKET-123", 120, "482913"))

        try {
            readerLoop(inbound, mint, offReader = { false }, handledInline = mutableListOf())
            fail("an inline mint must not be able to complete — it blocks the reader delivering its ticket")
        } catch (expected: TimeoutCancellationException) {
            assertFalse(mint.completed.isCompleted)
        }
    }

    /** The predicate itself: the ticket-minting owner frames take the off-loop path. A retired review
     *  invite or collaborator ticket mints nothing any more — it goes to the router, which answers it inline. */
    @Test
    fun the_ticket_minting_owner_frames_take_the_off_reader_path() {
        assertTrue(isOwnerControlFrame(bridgeMint))
        assertFalse(isOwnerControlFrame(dev.ccpocket.protocol.CreateCollaboratorTicket()))
        assertFalse(isOwnerControlFrame(CreateReviewInvite()))
    }
}
