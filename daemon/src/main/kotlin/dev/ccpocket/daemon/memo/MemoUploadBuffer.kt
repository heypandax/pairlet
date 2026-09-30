package dev.ccpocket.daemon.memo

import dev.ccpocket.protocol.VoiceMemoError
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoValidation
import java.security.MessageDigest
import java.util.Base64

/**
 * A fully received, hash-verified recording. Holds the original chunk arrays — ownership moves here from
 * the upload buffer, so completing an upload never copies the whole file into a second array. Writers
 * stream the chunks in order ([writeTo]); [release] drops them as soon as the audio is on disk.
 */
class MemoAudio(chunks: List<ByteArray>, val byteLength: Long, val mediaType: String) {
    @Volatile private var chunks: List<ByteArray>? = chunks

    fun writeTo(out: java.io.OutputStream) {
        val parts = chunks ?: error("audio already released")
        parts.forEach { out.write(it) }
    }

    fun release() {
        chunks = null
    }
}

sealed interface MemoChunkResult {
    /** A new chunk was stored; [received] of the announced chunks are now held. */
    data class Accepted(val received: Int) : MemoChunkResult
    /** Same index, same bytes as before (a whole-file resend) — ignored. */
    data object Duplicate : MemoChunkResult
    /** Every chunk is in, the total size and SHA-256 match; the buffer has handed its chunks over. */
    class Complete(val audio: MemoAudio) : MemoChunkResult
    /** The upload is unusable; the buffer is released and every later chunk gets the same answer. */
    data class Rejected(val errorCode: String) : MemoChunkResult
}

/**
 * Bounded in-memory reassembly of ONE audio attempt (overall design §7.3). A fixed-size slot array sized
 * from the validated start frame; each chunk is Base64-decoded on its own, only after its encoded length
 * has been checked, and must be exactly [VoiceMemoValidation.expectedChunkBytes] long. Not thread-safe:
 * the job registry serialises every call under its mutex.
 */
class MemoUploadBuffer(
    val byteLength: Long,
    val chunkCount: Int,
    private val sha256: String,
    private val mediaType: String = VoiceMemoLimits.AUDIO_MEDIA_TYPE,
) {
    private var slots: Array<ByteArray?>? = arrayOfNulls(chunkCount)
    private var received = 0
    private var outcome: MemoChunkResult? = null

    init {
        require(byteLength in 1..VoiceMemoLimits.MAX_AUDIO_BYTES) { "byteLength out of range" }
        require(chunkCount == VoiceMemoLimits.chunkCountFor(byteLength) && chunkCount <= VoiceMemoLimits.MAX_CHUNKS) { "chunkCount mismatch" }
    }

    val isOpen: Boolean get() = outcome == null

    fun accept(index: Int, base64: String): MemoChunkResult {
        outcome?.let { return if (it is MemoChunkResult.Rejected) it else MemoChunkResult.Duplicate }
        val slots = slots ?: return MemoChunkResult.Duplicate
        val expected = VoiceMemoValidation.expectedChunkBytes(byteLength, index)
            ?: return reject(VoiceMemoError.INVALID_INPUT)
        // length first: an oversized string is refused before a single byte is decoded
        if (base64.length > VoiceMemoLimits.MAX_CHUNK_BASE64_CHARS) return reject(VoiceMemoError.INVALID_INPUT)
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            return reject(VoiceMemoError.INVALID_INPUT)
        }
        if (bytes.size != expected) return reject(VoiceMemoError.INVALID_INPUT)
        val existing = slots[index]
        if (existing != null) {
            return if (existing.contentEquals(bytes)) MemoChunkResult.Duplicate else reject(VoiceMemoError.INPUT_CONFLICT)
        }
        slots[index] = bytes
        received++
        if (received < chunkCount) return MemoChunkResult.Accepted(received)
        return complete(slots)
    }

    /** Drops every held chunk (upload timeout, cancel, revoke). Later chunks are refused. */
    fun release() {
        slots = null
        if (outcome == null) outcome = MemoChunkResult.Rejected(VoiceMemoError.CANCELLED)
    }

    private fun complete(slots: Array<ByteArray?>): MemoChunkResult {
        val parts = slots.map { it!! }
        val total = parts.sumOf { it.size.toLong() }
        if (total != byteLength) return reject(VoiceMemoError.INVALID_INPUT)
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        val hex = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        if (hex != sha256) return reject(VoiceMemoError.INVALID_INPUT)
        this.slots = null
        val done = MemoChunkResult.Complete(MemoAudio(parts, byteLength, mediaType))
        outcome = done
        return done
    }

    private fun reject(code: String): MemoChunkResult {
        slots = null
        return MemoChunkResult.Rejected(code).also { outcome = it }
    }
}
