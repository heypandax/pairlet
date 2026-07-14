package dev.ccpocket.app.data

import dev.ccpocket.protocol.FileContentChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Client-side reassembly of a chunked ReadFile reply (issue #134): a contiguous 0..last run yields
 * one FileContent whose base64 is the pieces joined in order (the daemon's multiple-of-3 chunk size
 * makes the join valid base64); anything non-contiguous or cross-identity is a dead stream and resets.
 */
class FileChunkAssemblerTest {

    private fun chunk(idx: Int, last: Boolean, b64: String, path: String = "/w/report.xlsx") =
        FileContentChunk("/w", "sid", path, idx, last, b64, mediaType = "application/zip", totalBytes = 9)

    @Test
    fun contiguousRunAssemblesIntoOneFileContent() {
        val a = FileChunkAssembler()
        assertNull(a.add(chunk(0, false, "AAAA")))
        assertTrue(a.assembling)
        assertNull(a.add(chunk(1, false, "BBBB")))
        val done = a.add(chunk(2, true, "Qw=="))!!
        assertEquals("AAAABBBBQw==", done.base64)
        assertEquals("/w", done.workdir)
        assertEquals("sid", done.sessionId)
        assertEquals("/w/report.xlsx", done.path)
        assertEquals("application/zip", done.mediaType)
        assertEquals(9L, done.totalBytes)
        assertTrue(done.ok)
        assertFalse(a.assembling) // completed stream leaves no residue
    }

    @Test
    fun singleChunkStreamCompletesImmediately() {
        val done = FileChunkAssembler().add(chunk(0, true, "AAAA"))!!
        assertEquals("AAAA", done.base64)
    }

    @Test
    fun identitySwitchDropsThePartialAndStartsFresh() {
        val a = FileChunkAssembler()
        assertNull(a.add(chunk(0, false, "AAAA")))
        // a fresh idx-0 for ANOTHER file supersedes the stale partial (the viewer moved on)
        val done = a.add(chunk(0, true, "BBBB", path = "/w/other.pdf"))!!
        assertEquals("BBBB", done.base64)
        assertEquals("/w/other.pdf", done.path)
    }

    @Test
    fun nonContiguousIdxResetsAndMidStreamStraysAreIgnored() {
        val a = FileChunkAssembler()
        assertNull(a.add(chunk(0, false, "AAAA")))
        assertNull(a.add(chunk(2, true, "CCCC"))) // gap → the stream is dead, nothing assembled
        assertFalse(a.assembling)
        // a stray mid-stream chunk with no idx-0 before it never starts an assembly
        assertNull(a.add(chunk(1, true, "BBBB")))
        assertFalse(a.assembling)
        // and a clean restart still works after the resets
        assertEquals("DDDD", a.add(chunk(0, true, "DDDD"))!!.base64)
    }

    @Test
    fun duplicateIdxZeroRestartsTheStream() {
        val a = FileChunkAssembler()
        assertNull(a.add(chunk(0, false, "AAAA")))
        assertNull(a.add(chunk(0, false, "EEEE"))) // a retried read: start over from the new stream
        val done = a.add(chunk(1, true, "FFFF"))!!
        assertEquals("EEEEFFFF", done.base64)
    }

    @Test
    fun resetDropsAnyPartial() {
        val a = FileChunkAssembler()
        assertNull(a.add(chunk(0, false, "AAAA")))
        a.reset()
        assertFalse(a.assembling)
        assertNull(a.add(chunk(1, true, "BBBB"))) // the old stream can't resume across a reset
    }
}
