package dev.ccpocket.daemon.disk

import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.protocol.FileChunk
import dev.ccpocket.protocol.FileUploadProgress
import dev.ccpocket.protocol.FileUploaded
import dev.ccpocket.protocol.Frame
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The phone paces large uploads (videos) on these receipts and times out on lack of progress. */
class FileInboxProgressTest {
    private fun chunk(idx: Int, last: Boolean, bytes: Int = 10) = FileChunk(
        "c1", "cap-progress", idx, last, "clip.mov", "video/quicktime",
        Base64.getEncoder().encodeToString(ByteArray(bytes)), totalBytes = if (idx == 0) 30 else 0,
    )

    @Test
    fun every_chunk_that_extends_the_on_disk_prefix_is_acknowledged_then_the_landing() = runBlocking {
        val wd = Files.createTempDirectory("inbox-progress")
        val out = mutableListOf<Frame>()
        val sink = OutboundSink { out += it }
        val svc = FileInboxService { wd }

        svc.onChunk(chunk(0, last = false), sink)
        assertEquals(listOf<Frame>(FileUploadProgress("c1", "cap-progress", 1, 10)), out)

        // out of order: chunk 2 is stashed — nothing new is on disk, so no receipt yet
        svc.onChunk(chunk(2, last = true), sink)
        assertEquals(1, out.size)

        // chunk 1 fills the gap and completes the file: the landing receipt, not another progress frame
        svc.onChunk(chunk(1, last = false), sink)
        assertEquals(2, out.size)
        val done = assertIs<FileUploaded>(out[1])
        assertEquals(true, done.ok)
        assertEquals(30, done.size)
    }
}
