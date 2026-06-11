package dev.ccpocket.daemon.transcribe

import dev.ccpocket.protocol.AudioChunk
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CaptureBufferTest {

    private fun chunk(
        convo: String = "c1",
        capture: String = "cap-1",
        idx: Int,
        last: Boolean = false,
        payload: String = "A",
    ) = AudioChunk(convo, capture, idx, last, "audio/mp4", Base64.getEncoder().encodeToString(payload.toByteArray()))

    /** The phone encodes the WHOLE capture once, then slices the base64 STRING — mirror that here. */
    private fun chunksOf(payload: String, parts: Int, convo: String = "c1", capture: String = "cap-1"): List<AudioChunk> {
        val b64 = Base64.getEncoder().encodeToString(payload.toByteArray())
        val size = (b64.length + parts - 1) / parts
        return (0 until parts).map { i ->
            AudioChunk(convo, capture, i, last = i == parts - 1, "audio/mp4", b64.substring(i * size, minOf((i + 1) * size, b64.length)))
        }
    }

    @Test
    fun single_chunk_capture_completes_with_decoded_bytes() {
        val buf = CaptureBuffer()
        val r = buf.add(chunk(idx = 0, last = true, payload = "hello"))
        val c = assertIs<CaptureBuffer.Result.Complete>(r)
        assertEquals("cap-1", c.captureId)
        assertEquals("audio/mp4", c.mediaType)
        assertContentEquals("hello".toByteArray(), c.bytes)
        assertNull(c.evicted)
    }

    @Test
    fun chunks_join_in_idx_order_even_when_arriving_out_of_order() {
        val buf = CaptureBuffer()
        val (c0, c1, c2) = chunksOf("the quick brown fox", parts = 3)
        assertIs<CaptureBuffer.Result.Incomplete>(buf.add(c2))
        assertIs<CaptureBuffer.Result.Incomplete>(buf.add(c0))
        val done = assertIs<CaptureBuffer.Result.Complete>(buf.add(c1))
        assertContentEquals("the quick brown fox".toByteArray(), done.bytes)
    }

    @Test
    fun missing_middle_chunk_stays_incomplete() {
        val buf = CaptureBuffer()
        buf.add(chunk(idx = 0, payload = "a"))
        assertIs<CaptureBuffer.Result.Incomplete>(buf.add(chunk(idx = 2, last = true, payload = "c")))
    }

    @Test
    fun new_captureId_evicts_pending_capture_and_reports_it() {
        val buf = CaptureBuffer()
        buf.add(chunk(capture = "old", idx = 0))
        val r = buf.add(chunk(capture = "new", idx = 0, last = true))
        val c = assertIs<CaptureBuffer.Result.Complete>(r)
        assertEquals("old", c.evicted)
        // late chunk of the evicted capture is stale, not a fresh pending entry
        assertIs<CaptureBuffer.Result.Stale>(buf.add(chunk(capture = "old", idx = 1, last = true)))
    }

    @Test
    fun duplicate_last_chunk_after_completion_is_stale() {
        val buf = CaptureBuffer()
        buf.add(chunk(idx = 0, last = true))
        assertIs<CaptureBuffer.Result.Stale>(buf.add(chunk(idx = 0, last = true)))
    }

    @Test
    fun cancel_drops_pending_and_marks_it_stale() {
        val buf = CaptureBuffer()
        buf.add(chunk(idx = 0))
        buf.cancel("c1", "cap-1")
        assertIs<CaptureBuffer.Result.Stale>(buf.add(chunk(idx = 1, last = true)))
    }

    @Test
    fun cancel_with_wrong_captureId_keeps_pending() {
        val buf = CaptureBuffer()
        val (c0, c1) = chunksOf("payload", parts = 2)
        buf.add(c0)
        buf.cancel("c1", "other")
        assertIs<CaptureBuffer.Result.Complete>(buf.add(c1))
    }

    @Test
    fun pending_capture_expires_after_60s() {
        var now = 0L
        val buf = CaptureBuffer { now }
        buf.add(chunk(idx = 0))
        now = CaptureBuffer.EXPIRY_MS + 1
        // the expired half is swept; this chunk starts a NEW pending for the same captureId, so 0 is missing
        assertIs<CaptureBuffer.Result.Incomplete>(buf.add(chunk(idx = 1, last = true)))
    }

    @Test
    fun corrupted_base64_reports_invalid() {
        val buf = CaptureBuffer()
        val r = buf.add(AudioChunk("c1", "cap-x", 0, last = true, mediaType = "audio/mp4", base64 = "@@not-base64@@"))
        assertEquals("cap-x", assertIs<CaptureBuffer.Result.Invalid>(r).captureId)
    }

    @Test
    fun convos_are_independent() {
        val buf = CaptureBuffer()
        val (a0, a1) = chunksOf("first convo audio", parts = 2, convo = "c1", capture = "a")
        buf.add(a0)
        val c2 = assertIs<CaptureBuffer.Result.Complete>(buf.add(chunk(convo = "c2", capture = "b", idx = 0, last = true)))
        assertEquals("b", c2.captureId)
        assertIs<CaptureBuffer.Result.Complete>(buf.add(a1))
    }
}
