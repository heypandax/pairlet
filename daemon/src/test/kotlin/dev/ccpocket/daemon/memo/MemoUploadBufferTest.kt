package dev.ccpocket.daemon.memo

import dev.ccpocket.protocol.VoiceMemoError
import dev.ccpocket.protocol.VoiceMemoHash
import dev.ccpocket.protocol.VoiceMemoLimits
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemoUploadBufferTest {

    private val chunk = VoiceMemoLimits.CHUNK_BYTES

    private fun audio(size: Int, seed: Int = 1): ByteArray = Random(seed).nextBytes(size)

    private fun chunks(bytes: ByteArray): List<String> =
        (0 until VoiceMemoLimits.chunkCountFor(bytes.size.toLong())).map { i ->
            val from = i * chunk
            Base64.getEncoder().encodeToString(bytes.copyOfRange(from, minOf(from + chunk, bytes.size)))
        }

    private fun buffer(bytes: ByteArray, sha: String = VoiceMemoHash.sha256Hex(bytes)) =
        MemoUploadBuffer(bytes.size.toLong(), VoiceMemoLimits.chunkCountFor(bytes.size.toLong()), sha)

    private fun MemoAudio.bytes(): ByteArray = ByteArrayOutputStream().also { writeTo(it) }.toByteArray()

    @Test
    fun out_of_order_chunks_complete_with_exact_bytes() {
        val bytes = audio(chunk * 2 + 1000)
        val b = buffer(bytes)
        val parts = chunks(bytes)
        assertEquals(MemoChunkResult.Accepted(1), b.accept(2, parts[2]))
        assertEquals(MemoChunkResult.Accepted(2), b.accept(0, parts[0]))
        val done = assertIs<MemoChunkResult.Complete>(b.accept(1, parts[1]))
        assertContentEquals(bytes, done.audio.bytes())
        assertEquals(bytes.size.toLong(), done.audio.byteLength)
    }

    @Test
    fun duplicate_same_chunk_is_ignored_and_conflicting_chunk_fails() {
        val bytes = audio(chunk + 10)
        val parts = chunks(bytes)
        val b = buffer(bytes)
        b.accept(0, parts[0])
        assertEquals(MemoChunkResult.Duplicate, b.accept(0, parts[0]))
        val other = Base64.getEncoder().encodeToString(audio(chunk, seed = 9))
        assertEquals(MemoChunkResult.Rejected(VoiceMemoError.INPUT_CONFLICT), b.accept(0, other))
        // once rejected, later chunks get the same answer and nothing completes
        assertEquals(MemoChunkResult.Rejected(VoiceMemoError.INPUT_CONFLICT), b.accept(1, parts[1]))
    }

    @Test
    fun missing_chunks_never_complete() {
        val bytes = audio(chunk * 3)
        val parts = chunks(bytes)
        val b = buffer(bytes)
        assertIs<MemoChunkResult.Accepted>(b.accept(0, parts[0]))
        assertIs<MemoChunkResult.Accepted>(b.accept(2, parts[2]))
        assertEquals(MemoChunkResult.Duplicate, b.accept(2, parts[2]))
        assertTrue(b.isOpen)
    }

    @Test
    fun whole_file_resend_completes_once() {
        val bytes = audio(chunk + 5)
        val parts = chunks(bytes)
        val b = buffer(bytes)
        b.accept(0, parts[0])
        assertIs<MemoChunkResult.Complete>(b.accept(1, parts[1]))
        // a second pass of the same file: no second Complete
        assertEquals(MemoChunkResult.Duplicate, b.accept(0, parts[0]))
        assertEquals(MemoChunkResult.Duplicate, b.accept(1, parts[1]))
    }

    @Test
    fun empty_or_wrong_sized_chunks_are_rejected() {
        val bytes = audio(chunk + 5)
        assertEquals(MemoChunkResult.Rejected(VoiceMemoError.INVALID_INPUT), buffer(bytes).accept(0, ""))
        val shortFirst = Base64.getEncoder().encodeToString(audio(chunk - 1))
        assertEquals(MemoChunkResult.Rejected(VoiceMemoError.INVALID_INPUT), buffer(bytes).accept(0, shortFirst))
        val longLast = Base64.getEncoder().encodeToString(audio(6))
        assertEquals(MemoChunkResult.Rejected(VoiceMemoError.INVALID_INPUT), buffer(bytes).accept(1, longLast))
    }

    @Test
    fun oversized_base64_is_refused_before_decoding() {
        val bytes = audio(chunk + 5)
        // not valid Base64 at all — a decoder would throw; the length check must answer first
        val huge = "!".repeat(VoiceMemoLimits.MAX_CHUNK_BASE64_CHARS + 1)
        assertEquals(MemoChunkResult.Rejected(VoiceMemoError.INVALID_INPUT), buffer(bytes).accept(0, huge))
    }

    @Test
    fun malformed_base64_and_out_of_range_index_are_rejected() {
        val bytes = audio(100)
        assertEquals(MemoChunkResult.Rejected(VoiceMemoError.INVALID_INPUT), buffer(bytes).accept(0, "@@@@"))
        val part = chunks(bytes)[0]
        assertEquals(MemoChunkResult.Rejected(VoiceMemoError.INVALID_INPUT), buffer(bytes).accept(1, part))
        assertEquals(MemoChunkResult.Rejected(VoiceMemoError.INVALID_INPUT), buffer(bytes).accept(-1, part))
    }

    @Test
    fun hash_mismatch_is_rejected_at_completion() {
        val bytes = audio(chunk + 5)
        val b = buffer(bytes, sha = VoiceMemoHash.sha256Hex(byteArrayOf(1)))
        val parts = chunks(bytes)
        b.accept(0, parts[0])
        assertEquals(MemoChunkResult.Rejected(VoiceMemoError.INVALID_INPUT), b.accept(1, parts[1]))
    }

    @Test
    fun max_size_upload_fits_the_chunk_cap() {
        val bytes = audio(VoiceMemoLimits.MAX_AUDIO_BYTES.toInt())
        val parts = chunks(bytes)
        assertEquals(VoiceMemoLimits.MAX_CHUNKS, parts.size)
        assertTrue(parts.all { it.length <= VoiceMemoLimits.MAX_CHUNK_BASE64_CHARS })
        val b = buffer(bytes)
        var last: MemoChunkResult? = null
        parts.forEachIndexed { i, p -> last = b.accept(i, p) }
        assertIs<MemoChunkResult.Complete>(last)
    }

    @Test
    fun release_drops_chunks_and_refuses_more() {
        val bytes = audio(chunk + 5)
        val parts = chunks(bytes)
        val b = buffer(bytes)
        b.accept(0, parts[0])
        b.release()
        assertIs<MemoChunkResult.Rejected>(b.accept(1, parts[1]))
    }
}
