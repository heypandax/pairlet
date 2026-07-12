package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.FileUpState
import dev.ccpocket.app.data.PendingFile
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.SentFile
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.FileGlyphKind
import dev.ccpocket.app.ui.fileGlyphKind
import dev.ccpocket.app.ui.fmtSize
import dev.ccpocket.app.ui.isVideoAttachment
import dev.ccpocket.app.ui.middleTrunc
import dev.ccpocket.app.ui.mmss
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * File-upload UI (issue #90): the pure display helpers, the composer's pending-chip strip with its
 * send-waits hint, and the delivered in-stream chip with its `@inbox` path — rendered through the
 * real ChatPane over a [SeedDesktopModel] extended by delegation (the seed itself stays inert).
 */
@OptIn(ExperimentalTestApi::class)
class FileAttachmentTest {

    // ---- pure helpers -----------------------------------------------------------------------

    @Test
    fun middleTruncKeepsTheExtensionEnd() {
        assertEquals("Q3-…t.pdf", middleTrunc("Q3-metrics-report.pdf")) // head 3 + … + tail 5
        assertEquals("a.csv", middleTrunc("a.csv")) // short names pass through untouched
    }

    @Test
    fun fmtSizeMatchesTheDesignCopy() {
        assertEquals("2.4 MB", fmtSize(2_516_582))   // 1 decimal under 10 MB
        assertEquals("12 MB", fmtSize(12_582_912))   // whole MB from 10 up
        assertEquals("812 KB", fmtSize(831_488))     // sub-MB in KB
        assertEquals("0 KB", fmtSize(0))
    }

    @Test
    fun glyphKindRoutesTablesCodeAndDocs() {
        assertEquals(FileGlyphKind.Table, fileGlyphKind("metrics.csv"))
        assertEquals(FileGlyphKind.Code, fileGlyphKind("server.log"))
        assertEquals(FileGlyphKind.Code, fileGlyphKind("Main.kt"))
        assertEquals(FileGlyphKind.Doc, fileGlyphKind("report.pdf"))
        assertEquals(FileGlyphKind.Doc, fileGlyphKind("noextension"))
    }

    // ---- video derivation (issue #98) -------------------------------------------------------

    @Test
    fun glyphKindRoutesVideos() {
        assertEquals(FileGlyphKind.Video, fileGlyphKind("clip.mov"))
        assertEquals(FileGlyphKind.Video, fileGlyphKind("screen-recording.mp4"))
        assertEquals(FileGlyphKind.Video, fileGlyphKind("demo.webm"))
        assertEquals(FileGlyphKind.Video, fileGlyphKind("OLD.AVI")) // case-insensitive
        assertEquals(FileGlyphKind.Doc, fileGlyphKind("movie.txt")) // not by name-contains, by extension
    }

    @Test
    fun isVideoAttachmentPrefersMimeThenFallsBackToExtension() {
        assertTrue(isVideoAttachment("video/mp4", "clip.mp4"))
        assertTrue(isVideoAttachment("video/quicktime", "clip.mov"))
        // blank / octet-stream mediaType (e.g. a desktop pick) still routes by extension
        assertTrue(isVideoAttachment("", "capture.mov"))
        assertTrue(isVideoAttachment("application/octet-stream", "capture.mp4"))
        // a real document never becomes a video card
        assertFalse(isVideoAttachment("application/pdf", "report.pdf"))
        assertFalse(isVideoAttachment("", "report.pdf"))
    }

    @Test
    fun mmssFormatsDurationPill() {
        assertEquals("0:42", mmss(42))
        assertEquals("1:05", mmss(65))
        assertEquals("0:00", mmss(0))
        assertEquals("1:01:07", mmss(3667)) // rolls to hh:mm:ss past an hour
    }

    @Test
    fun fileChunkPartsCoversTheLargeFileBoundary() {
        val chunk = PocketRepository.FILE_CHUNK_RAW
        assertEquals(1, PocketRepository.fileChunkParts(0))        // empty still sends one terminal chunk
        assertEquals(1, PocketRepository.fileChunkParts(1))
        assertEquals(1, PocketRepository.fileChunkParts(chunk))    // exact multiple: NO trailing empty chunk
        assertEquals(2, PocketRepository.fileChunkParts(chunk + 1))
        assertEquals(2, PocketRepository.fileChunkParts(2 * chunk))
        assertEquals(3, PocketRepository.fileChunkParts(2 * chunk + 1))
        // a 200 MB video: 209_715_200 / 768_000 = 274 frames (under the daemon's 4096 chunk cap)
        assertEquals(274, PocketRepository.fileChunkParts(200 * 1024 * 1024))
    }

    // ---- composer strip: uploading + failed chips, send waits --------------------------------

    @Test
    fun composerShowsPendingChipsAndSendWaits() = runComposeUiTest {
        val model = object : DesktopModel by SeedDesktopModel() {
            override val pendingFiles = listOf(
                PendingFile(1, "server.log", 6_396_313, ByteArray(0), "text/plain", FileUpState.Uploading, progress = 0.64f, captureId = "cap1"),
                PendingFile(2, "trace.txt", 1_024, ByteArray(0), "text/plain", FileUpState.Failed, error = "upload failed"),
            )
            override fun uploadsBusy() = true
        }
        setContent { PocketTheme { DesktopApp(model) } }
        assertPresent("server.log")
        assertPresent("upload failed · retry")
        assertPresent("send waits", substring = true) // "uploading 1 of 2 — send waits"
    }

    // ---- delivered turn: dense chip with the @inbox landing path ------------------------------

    @Test
    fun sentTurnRendersFileChipWithInboxPath() = runComposeUiTest {
        val model = object : DesktopModel by SeedDesktopModel() {
            override val messages = listOf<ChatItem>(
                ChatItem.User(
                    "here's the crash report",
                    files = listOf(SentFile("report.pdf", 2_516_582, ".ccpocket/inbox/ab12cd34/report.pdf")),
                ),
            )
        }
        setContent { PocketTheme { DesktopApp(model) } }
        assertPresent("report.pdf", substring = true)
        assertPresent("@.ccpocket/inbox/ab12cd34/report.pdf")
    }

    // ---- delivered VIDEO turn: 16:9 thumb with the duration pill + @inbox path (issue #98) ----

    @Test
    fun sentTurnRendersVideoThumbWithDuration() = runComposeUiTest {
        val model = object : DesktopModel by SeedDesktopModel() {
            override val messages = listOf<ChatItem>(
                ChatItem.User(
                    "here's the screen recording",
                    files = listOf(
                        SentFile(
                            "clip.mov", 12_800_000, ".ccpocket/inbox/ab12cd34/clip.mov",
                            mediaType = "video/quicktime", durationSecs = 42,
                        ),
                    ),
                ),
            )
        }
        setContent { PocketTheme { DesktopApp(model) } }
        assertPresent("clip.mov", substring = true)
        assertPresent("@.ccpocket/inbox/ab12cd34/clip.mov")
        assertPresent("0:42") // the duration pill only the video thumb draws — proves the video branch rendered
    }

    @Test
    fun videoThumbDegradesWhenDurationUnknown() = runComposeUiTest {
        // duration is null on v1 (no client-side probing) — the card must still render, just no pill
        val model = object : DesktopModel by SeedDesktopModel() {
            override val messages = listOf<ChatItem>(
                ChatItem.User(
                    "recording",
                    files = listOf(SentFile("capture.mp4", 8_400_000, ".ccpocket/inbox/ef56/capture.mp4", mediaType = "video/mp4")),
                ),
            )
        }
        setContent { PocketTheme { DesktopApp(model) } }
        // the card still renders (name + @inbox path) with no duration pill — no crash, graceful degrade
        assertPresent("capture.mp4", substring = true)
        assertPresent("@.ccpocket/inbox/ef56/capture.mp4")
    }
}
