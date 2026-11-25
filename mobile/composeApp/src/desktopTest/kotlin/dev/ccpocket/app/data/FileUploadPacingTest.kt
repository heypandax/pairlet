package dev.ccpocket.app.data

import dev.ccpocket.app.media.PickedFile
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.FileChunk
import dev.ccpocket.protocol.FileUploadProgress
import dev.ccpocket.protocol.FileUploaded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A video used to fail every time: send() only QUEUES a chunk, so the loop "finished" while tens of MB still
 * waited to cross the phone's uplink, and the 20 s receipt deadline that started right then expired first.
 * The upload is now paced on the daemon's FileUploadProgress receipts and times out on lack of PROGRESS.
 */
class FileUploadPacingTest {
    private val window = PocketRepository.FILE_UPLOAD_WINDOW
    private val parts = 20
    private val convo = "c-upload"

    private fun TestScope.repo(sent: MutableList<FileChunk>) = PocketRepository(backgroundScope as CoroutineScope).apply {
        paired.value = PairedDaemon("wss://test", "acct-test", "pk", "dev", "cred")
        onSendForTest = { if (it is FileChunk) sent += it } // no transport: frames go nowhere, like a slow uplink
        convoId.value = convo
    }

    private fun video() = PickedFile("clip.mov", parts.toLong() * PocketRepository.FILE_CHUNK_RAW,
        ByteArray(parts * PocketRepository.FILE_CHUNK_RAW), "video/quicktime")

    @Test
    fun sends_at_most_a_window_ahead_of_the_receipts_and_lands_long_after_the_old_20s_deadline() = runTest {
        val sent = mutableListOf<FileChunk>()
        val repo = repo(sent)
        repo.attachFiles(listOf(video()))
        runCurrent()
        assertEquals(window, sent.size, "only a window may be queued before the computer confirms anything")
        val cap = sent.first().captureId

        // the first chunk reaches the computer within the probe (that is how a current daemon is recognized)…
        advanceTimeBy(5_000)
        var acked = 1
        repo.receiveForTest(FileUploadProgress(convo, cap, acked, PocketRepository.FILE_CHUNK_RAW.toLong()))
        runCurrent()
        assertEquals(acked + window, sent.size)
        // …then a slow uplink: receipts trickle in far apart — 50 s between them, well past the old 20 s deadline
        while (acked < parts) {
            advanceTimeBy(50_000)
            acked = minOf(parts, acked + 4)
            repo.receiveForTest(FileUploadProgress(convo, cap, acked, acked.toLong() * PocketRepository.FILE_CHUNK_RAW))
            runCurrent()
            assertTrue(sent.size <= acked + window, "never more than a window ahead (sent=${sent.size}, acked=$acked)")
            assertEquals(FileUpState.Uploading, repo.pendingFiles.single().state, "progress keeps a slow upload alive")
        }
        assertEquals(parts, sent.size)
        assertEquals(1f, repo.pendingFiles.single().progress)
        repo.receiveForTest(FileUploaded(convo, cap, path = ".ccpocket/inbox/$cap/clip.mov", name = "clip.mov", size = 1))
        assertEquals(FileUpState.Landed, repo.pendingFiles.single().state)
    }

    @Test
    fun a_daemon_without_receipts_streams_unpaced_after_the_probe_and_gets_a_size_scaled_deadline() = runTest {
        val sent = mutableListOf<FileChunk>()
        val repo = repo(sent)
        repo.attachFiles(listOf(video()))
        runCurrent()
        assertEquals(window, sent.size)
        advanceTimeBy(PocketRepository.FILE_UPLOAD_PROBE_MS + 1)
        runCurrent()
        assertEquals(parts, sent.size, "no receipt within the probe → an older daemon: stream the rest")
        // the old 20 s deadline would fail here; the queued bytes may still be crossing the uplink
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(FileUpState.Uploading, repo.pendingFiles.single().state)
        advanceTimeBy(PocketRepository.fileAckFallbackMs(parts.toLong() * PocketRepository.FILE_CHUNK_RAW))
        runCurrent()
        assertEquals(FileUpState.Failed, repo.pendingFiles.single().state, "a daemon that never answers still fails, eventually")
    }

    @Test
    fun a_stalled_upload_fails_after_the_no_progress_window() = runTest {
        val sent = mutableListOf<FileChunk>()
        val repo = repo(sent)
        repo.attachFiles(listOf(video()))
        runCurrent()
        val cap = sent.first().captureId
        repo.receiveForTest(FileUploadProgress(convo, cap, 1, PocketRepository.FILE_CHUNK_RAW.toLong()))
        runCurrent()
        assertEquals(window + 1, sent.size)
        advanceTimeBy(PocketRepository.FILE_UPLOAD_STALL_MS + 1)
        runCurrent()
        val f = repo.pendingFiles.single()
        assertEquals(FileUpState.Failed, f.state)
        assertTrue(f.error!!.contains("stalled"), f.error)
    }

    @Test
    fun receipts_for_another_upload_are_ignored() = runTest {
        val sent = mutableListOf<FileChunk>()
        val repo = repo(sent)
        repo.attachFiles(listOf(video()))
        runCurrent()
        repo.receiveForTest(FileUploadProgress(convo, "someone-else", parts, 1))
        runCurrent()
        assertEquals(window, sent.size)
    }
}
