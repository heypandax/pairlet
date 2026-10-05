package dev.ccpocket.daemon.acp

import dev.ccpocket.protocol.ImageData
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The single-flight FIFO on its own: gate, order, one in flight, refusals that never stall the queue. */
class AcpPromptFifoTest {
    private val ids = AtomicLong(10)
    private val fifo = AcpPromptFifo { ids.getAndIncrement() }
    private val all: (AcpPrompt) -> Boolean = { true }

    private fun p(text: String, images: Int = 0) = AcpPrompt(text, List(images) { ImageData("image/png", "AA==") })

    @Test
    fun `a shut gate queues everything and opening releases only the oldest`() = runBlocking {
        assertNull(fifo.admit(p("one")))
        assertNull(fifo.admit(p("two")))
        val first = fifo.open({ true }, all, mutableListOf())
        assertEquals(10L to p("one"), first)
        assertNull(fifo.next(all, mutableListOf()), "one prompt in flight at a time")
        assertEquals("one", fifo.settle(10))
        assertEquals(11L to p("two"), fifo.next(all, mutableListOf()))
    }

    @Test
    fun `a vetoed opening keeps the gate shut`() = runBlocking {
        fifo.admit(p("one"))
        assertNull(fifo.open({ false }, all, mutableListOf()))
        assertNull(fifo.next(all, mutableListOf()), "the gate never opened")
        assertNull(fifo.admit(p("two")), "still queued behind the shut gate")
        assertEquals(listOf(p("one"), p("two")), fifo.drain())
    }

    @Test
    fun `an idle open gate reserves directly and a running turn queues`() = runBlocking {
        fifo.open({ true }, all, mutableListOf())
        assertEquals(10L, fifo.admit(p("direct")))
        assertTrue(fifo.isInFlight(10))
        assertNull(fifo.admit(p("queued")))
        assertNull(fifo.settle(99), "an unknown id settles nothing")
        assertEquals("direct", fifo.settle(10))
        assertNull(fifo.settle(10), "a prompt settles exactly once")
        assertEquals(11L to p("queued"), fifo.next(all, mutableListOf()))
    }

    @Test
    fun `an unacceptable head is refused and the next one goes`() = runBlocking {
        fifo.admit(p("img", images = 1))
        fifo.admit(p("text"))
        val refused = mutableListOf<AcpPrompt>()
        val first = fifo.open({ true }, { it.images.isEmpty() }, refused)
        assertEquals(listOf(p("img", images = 1)), refused)
        assertEquals(10L to p("text"), first)
    }

    @Test
    fun `a reservation holds the queue like an in-flight prompt and reset forgets everything`() = runBlocking {
        fifo.open({ true }, all, mutableListOf())
        val refusal = fifo.reserve("refused")
        assertNull(fifo.admit(p("behind")), "the refusal holds the FIFO until it settles")
        assertEquals("refused", fifo.settle(refusal))
        fifo.reset()
        assertTrue(!fifo.isInFlight(refusal))
        assertNull(fifo.admit(p("after reset")), "a fresh process starts with the gate shut")
        assertEquals(listOf(p("after reset")), fifo.drain())
    }

    @Test
    fun `the prompt params put the text first and never send an empty text block`() {
        val img = ImageData("image/png", "AA==")
        val both = AcpPrompt("hi", listOf(img)).sessionPromptParams("s").toString()
        assertEquals(
            """{"sessionId":"s","prompt":[{"type":"text","text":"hi"},{"type":"image","data":"AA==","mimeType":"image/png"}]}""",
            both,
        )
        val imageOnly = AcpPrompt(" ", listOf(img)).sessionPromptParams("s").toString()
        assertEquals("""{"sessionId":"s","prompt":[{"type":"image","data":"AA==","mimeType":"image/png"}]}""", imageOnly)
        val empty = AcpPrompt("", emptyList()).sessionPromptParams("s").toString()
        assertEquals("""{"sessionId":"s","prompt":[{"type":"text","text":""}]}""", empty)
    }
}
