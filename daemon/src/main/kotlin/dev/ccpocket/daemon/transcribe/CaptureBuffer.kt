package dev.ccpocket.daemon.transcribe

import dev.ccpocket.protocol.AudioChunk

/**
 * Reassembles [AudioChunk] frames into complete voice captures. One pending capture per convo:
 * a chunk with a new captureId evicts the previous pending one (the phone retries with a fresh
 * id, so the stale half-assembled capture must not linger). Pure logic — no I/O, injectable clock.
 */
class CaptureBuffer(private val nowMs: () -> Long = System::currentTimeMillis) {

    sealed interface Result {
        /** More chunks expected. [evicted] is the captureId this chunk displaced (cancel its job). */
        data class Incomplete(val evicted: String? = null) : Result

        /** All chunks 0..last present — [bytes] is the decoded audio. */
        class Complete(val captureId: String, val mediaType: String, val bytes: ByteArray, val evicted: String? = null) : Result

        /** Chunk of a capture that already finished or was cancelled — drop silently. */
        data object Stale : Result

        /** Reassembled payload is not valid base64 — tell the phone to retry. */
        data class Invalid(val captureId: String) : Result
    }

    private class Pending(val captureId: String, val mediaType: String, val createdMs: Long) {
        val chunks = HashMap<Int, String>()
        var lastIdx: Int? = null
    }

    private val pending = HashMap<String, Pending>()      // convoId -> capture in flight
    private val finished = ArrayDeque<String>()           // recent completed/cancelled captureIds

    @Synchronized
    fun add(c: AudioChunk): Result {
        sweep()
        if (c.captureId in finished) return Result.Stale
        var evicted: String? = null
        var p = pending[c.convoId]
        if (p != null && p.captureId != c.captureId) {
            evicted = p.captureId
            markFinished(p.captureId)
            p = null
        }
        if (p == null) {
            p = Pending(c.captureId, c.mediaType, nowMs())
            pending[c.convoId] = p
        }
        p.chunks[c.idx] = c.base64
        if (c.last) p.lastIdx = c.idx
        val last = p.lastIdx ?: return Result.Incomplete(evicted)
        if ((0..last).any { it !in p.chunks }) return Result.Incomplete(evicted)

        pending.remove(c.convoId)
        markFinished(p.captureId)
        val joined = buildString { for (i in 0..last) append(p.chunks[i]) }
        val bytes = try {
            java.util.Base64.getDecoder().decode(joined)
        } catch (_: IllegalArgumentException) {
            return Result.Invalid(p.captureId)
        }
        return Result.Complete(p.captureId, p.mediaType, bytes, evicted)
    }

    @Synchronized
    fun cancel(convoId: String, captureId: String) {
        val p = pending[convoId] ?: return
        if (p.captureId == captureId) {
            pending.remove(convoId)
            markFinished(captureId)
        }
    }

    private fun markFinished(id: String) {
        finished.addLast(id)
        while (finished.size > FINISHED_KEEP) finished.removeFirst()
    }

    private fun sweep() {
        val cutoff = nowMs() - EXPIRY_MS
        pending.entries.removeIf { it.value.createdMs < cutoff }
    }

    companion object {
        const val EXPIRY_MS = 60_000L
        private const val FINISHED_KEEP = 32
    }
}
