package dev.ccpocket.daemon.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * TRANSPORT-AUTO-REPATH-V1 4.6: once a connection's writer is gone, a fan-out into its outbox must return in
 * bounded time. Built on the same Channel(BUFFERED) the connection uses, filled the way a dead writer leaves it.
 */
class OutboxRetireTest {
    private fun fullOutbox(): Channel<String> = Channel<String>(Channel.BUFFERED).also { ch ->
        while (ch.trySend("queued").isSuccess) Unit // the writer stopped draining: buffer full
    }

    @Test fun bareCloseLeavesAParkedSenderHanging() = runBlocking {
        val outbox = fullOutbox()
        val parked = launch(start = CoroutineStart.UNDISPATCHED) { outbox.send("turn-end") }
        outbox.close() // the pre-fix teardown
        assertNull(withTimeoutOrNull(500) { parked.join() }, "control: close() does not release a suspended sender")
        parked.cancel()
    }

    @Test fun retireReleasesAParkedSenderPromptly() = runBlocking {
        val outbox = fullOutbox()
        val parked = async(start = CoroutineStart.UNDISPATCHED) { runCatching { outbox.send("turn-end") } }
        yield()
        assertFalse(parked.isCompleted, "precondition: the fan-out is parked on the full outbox")
        retireOutbox(outbox)
        val result = withTimeoutOrNull(1_000) { parked.await() }
        assertTrue(result != null, "the parked fan-out returned after the outbox was retired")
        assertTrue(result.isSuccess, "released normally — no CancellationException leaks into the emitter's scope")
    }

    @Test fun sendsAfterRetireFailFastAsBefore() = runBlocking {
        val outbox = fullOutbox()
        retireOutbox(outbox)
        val r = withTimeoutOrNull(1_000) { runCatching { outbox.send("late") } }
        assertIs<ClosedSendChannelException>(r?.exceptionOrNull(), "a closed sink still throws at once (ignored by fan-out)")
        assertEquals(true, outbox.isClosedForReceive, "nothing is left queued for a writer that will never come")
    }
}
