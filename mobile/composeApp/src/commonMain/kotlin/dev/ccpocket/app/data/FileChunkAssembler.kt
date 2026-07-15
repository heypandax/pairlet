package dev.ccpocket.app.data

import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.FileContentChunk

// ════════════════════════════════════════════════════════════════════
//  Chunked ReadFile reassembly (issue #134) — the pure counterpart of
//  the daemon's chunk pump. The daemon guarantees every non-final
//  chunk's base64 encodes a multiple-of-3 byte count, so concatenating
//  the base64 strings in idx order IS the whole file's base64: no
//  per-chunk decode, no re-encode, one allocation-cheap StringBuilder.
// ════════════════════════════════════════════════════════════════════

/**
 * Accumulates one in-flight [FileContentChunk] stream and yields the assembled [FileContent] on the
 * last piece. The E2E channel is ordered, so anything out of contiguous order means a superseded /
 * retried read: the stale stream is dropped and, when the stray chunk is itself an idx-0, a fresh
 * assembly starts from it. Identity (workdir, sessionId, path) is checked per chunk so a late
 * stream for a file the viewer has left can never splice into the current one.
 */
class FileChunkAssembler {
    private var key: Triple<String, String, String>? = null
    private var nextIdx = 0
    private var totalBytes = 0L
    private val base64 = StringBuilder()

    /** Feed one chunk; returns the fully assembled [FileContent] when [FileContentChunk.last] closes a
     *  contiguous 0..last run, null while more pieces are expected (or the chunk was stale/ignored). */
    fun add(c: FileContentChunk): FileContent? {
        val k = Triple(c.workdir, c.sessionId, c.path)
        if (k != key || c.idx != nextIdx) {
            reset()
            if (c.idx != 0) return null // mid-stream stray of a dead read — nothing to start from
            key = k
        }
        base64.append(c.base64)
        nextIdx = c.idx + 1
        totalBytes = c.totalBytes
        if (!c.last) return null
        val whole = base64.toString()
        val done = FileContent(
            c.workdir, c.sessionId, c.path,
            base64 = whole, mediaType = c.mediaType, totalBytes = c.totalBytes,
        )
        reset()
        return done
    }

    /** Whether a stream is mid-assembly (used to keep the viewer's loading state honest). */
    val assembling: Boolean get() = key != null

    /** Received/total bytes of the in-flight stream — the loading card's determinate progress bar
     *  (issue #134 · 0714 chat-components handoff A1). Non-final chunks encode a multiple-of-3 byte
     *  count, so the accumulated base64 length maps EXACTLY back to raw bytes (len/4·3) with no
     *  decode. Null while nothing is assembling or the daemon didn't declare a total. */
    val progress: Pair<Long, Long>?
        get() = if (key != null && totalBytes > 0) (base64.length.toLong() / 4 * 3) to totalBytes else null

    /** Drop any partial state — a fresh read, a final [FileContent] reply, or a closed viewer. */
    fun reset() {
        key = null
        nextIdx = 0
        totalBytes = 0
        base64.setLength(0)
    }
}
