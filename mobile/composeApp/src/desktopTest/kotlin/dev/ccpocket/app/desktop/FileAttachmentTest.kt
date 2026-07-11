package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.FileUpState
import dev.ccpocket.app.data.PendingFile
import dev.ccpocket.app.data.SentFile
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.FileGlyphKind
import dev.ccpocket.app.ui.fileGlyphKind
import dev.ccpocket.app.ui.fmtSize
import dev.ccpocket.app.ui.middleTrunc
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
