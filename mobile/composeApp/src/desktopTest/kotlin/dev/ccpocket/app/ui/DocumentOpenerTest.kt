package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.advanceFrameAndWait
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.util.Base64
import kotlin.test.*

/** Tapping a document on the phone goes straight to the native preview: no viewer screen while the bytes
 *  travel (only a floating HUD if they are slow), the preview once they land, and the viewer only for what
 *  the previewer can't take. The previewer is faked — the real desktop actual would launch the system app. */
@OptIn(ExperimentalTestApi::class)
class DocumentOpenerTest {
    private val pdfBytes = "%PDF-1.4\n%%EOF\n".encodeToByteArray()

    private fun doc(path: String, mediaType: String = "application/pdf") = FileContent(
        WORKDIR, "sid", path,
        base64 = Base64.getEncoder().encodeToString(pdfBytes), mediaType = mediaType, totalBytes = pdfBytes.size.toLong(),
    )

    private class Opened(val name: String, val bytes: ByteArray, val mediaType: String?)

    /** Mounts [DocumentOpener] the way `openTappedFile` leaves the repo ([deferred]), then runs [script]. */
    private fun opener(
        path: String,
        initial: FileContent?,
        deferred: Boolean = true,
        previewerShows: Boolean = true,
        setup: PocketRepository.() -> Unit = {},
        script: ComposeUiTest.(PocketRepository, List<Opened>) -> Unit = { _, _ -> },
    ): Pair<PocketRepository, List<Opened>> {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val opened = mutableListOf<Opened>()
        val repo = PocketRepository(scope).apply {
            workdir.value = WORKDIR
            viewedFilePath.value = path
            viewerDeferred.value = deferred
            viewedFile.value = initial
            setup()
        }
        try {
            runDesktopComposeUiTest(402, 874) {
                setContent {
                    PocketTheme {
                        DocumentOpener(repo) { name, bytes, type, onShown ->
                            opened += Opened(name, bytes, type)
                            if (previewerShows) onShown()
                            previewerShows
                        }
                    }
                }
                advanceFrameAndWait()
                script(repo, opened)
                advanceFrameAndWait() // a later recomposition must never hand it over a second time
            }
        } finally {
            scope.cancel()
        }
        return repo to opened
    }

    @Test
    fun aTappedPdfSkipsTheViewerAndOpensOnceItsBytesLand() {
        val (repo, opened) = opener("out/report.pdf", initial = null) { repo, _ ->
            runOnUiThread { repo.viewedFile.value = doc("out/report.pdf") }
            advanceFrameAndWait()
        }
        val o = opened.single()
        assertEquals("report.pdf", o.name)
        assertContentEquals(pdfBytes, o.bytes)
        assertEquals("application/pdf", o.mediaType)
        assertNull(repo.viewedFilePath.value, "the preview is up: closing it must land where the file was tapped")
        assertFalse(repo.viewerDeferred.value)
    }

    @Test
    fun aSlowFetchShowsTheHudOnlyAfterTheGraceAndCancelAbandonsIt() {
        // same session identity as the reply, so only the closed viewer can be what drops it
        val (repo, opened) = opener("out/report.pdf", initial = null, setup = { sessionKey.value = "sid" }) { repo, _ ->
            assertTrue(onAllNodesWithTag(DOCUMENT_OPENING_HUD_TAG).fetchSemanticsNodes().isEmpty(), "a quick fetch shows nothing")
            mainClock.advanceTimeBy(400)
            advanceFrameAndWait()
            onNodeWithTag(DOCUMENT_OPENING_CANCEL_TAG).performClick()
            advanceFrameAndWait()
            assertTrue(onAllNodesWithTag(DOCUMENT_OPENING_HUD_TAG).fetchSemanticsNodes().isEmpty())
            runOnUiThread { repo.receiveForTest(doc("out/report.pdf")) } // the late reply finds nothing open
        }
        assertNull(repo.viewedFilePath.value)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun aFailureRevealsTheViewerToExplainIt() {
        val tooLarge = FileContent(WORKDIR, "sid", "out/big.pdf", ok = false, error = "file too large to send (90000 KB — the link caps transfers at 65536 KB)")
        val (repo, opened) = opener("out/big.pdf", initial = null) { repo, _ ->
            runOnUiThread { repo.viewedFile.value = tooLarge }
            advanceFrameAndWait()
        }
        assertTrue(opened.isEmpty())
        assertEquals("out/big.pdf", repo.viewedFilePath.value)
        assertFalse(repo.viewerDeferred.value)
    }

    @Test
    fun aPreviewerThatCannotShowItRevealsTheCardWithoutRetrying() {
        val (repo, opened) = opener("out/deck.pptx", initial = doc("out/deck.pptx", "application/vnd.ms-powerpoint"), previewerShows = false)
        assertEquals(1, opened.size)
        assertEquals("out/deck.pptx", repo.viewedFilePath.value)
        assertFalse(repo.viewerDeferred.value)
    }

    @Test
    fun appLockHoldsThePreviewUntilUnlocked() {
        val (repo, opened) = opener("out/report.pdf", initial = doc("out/report.pdf"), setup = { appLock.locked.value = true }) { repo, seen ->
            assertTrue(seen.isEmpty(), "a preview presented over the lock gate would leak the document")
            runOnUiThread { repo.appLock.locked.value = false }
            advanceFrameAndWait()
        }
        assertEquals(1, opened.size)
        assertNull(repo.viewedFilePath.value)
    }

    @Test
    fun aDocumentArrivingInAnOpenViewerStillGoesToThePreviewer() {
        // e.g. an export approved from the viewer's refusal state: the bytes land with the viewer on screen
        val (repo, opened) = opener("out/report.pdf", initial = null, deferred = false) { repo, _ ->
            runOnUiThread { repo.viewedFile.value = doc("out/report.pdf") }
            advanceFrameAndWait()
        }
        assertEquals(1, opened.size)
        assertNull(repo.viewedFilePath.value)
    }

    @Test
    fun zipsBinariesAndTextNeverGoToThePreviewer() {
        for (content in listOf(
            doc("out/bundle.zip", "application/zip"),
            doc("out/blob.bin", "application/octet-stream"),
            FileContent(WORKDIR, "sid", "notes.txt", text = "hello"),
        )) {
            val (repo, opened) = opener(content.path, initial = content, deferred = false)
            assertTrue(opened.isEmpty(), content.path)
            assertEquals(content.path, repo.viewedFilePath.value, content.path)
        }
    }

    @Test
    fun whileATappedDocumentLoadsTheChatStaysOnScreen() = runComposeUiTest {
        lateinit var repo: PocketRepository
        setContent {
            val scope = rememberCoroutineScope()
            repo = remember {
                PocketRepository(scope, PairedDaemon(relay = "wss://test.invalid", accountId = "acct-doc-open", daemonPub = "pub", deviceId = "dev", credential = "cred")).apply {
                    receiveForTest(SessionLive(convoId = "c-doc", workdir = WORKDIR, sessionId = "s-doc", mode = PermissionMode.DEFAULT, executing = false, agent = AgentKind.CLAUDE))
                    receiveForTest(ConvoHistory("c-doc", listOf(HistoryMessage(ChatRole.ASSISTANT, "Wrote out/report.pdf"))))
                }
            }
            PocketTheme { Box(Modifier.requiredSize(390.dp, 700.dp)) { ChatScreen(repo) } }
        }
        waitForIdle()
        runOnUiThread { repo.viewedFilePath.value = "out/report.pdf"; repo.viewerDeferred.value = true }
        waitForIdle()
        assertTrue(onAllNodesWithTag(FILE_VIEWER_COPY_PATH_TAG).fetchSemanticsNodes().isEmpty(), "no viewer screen on the way to the preview")
        runOnUiThread { repo.viewerDeferred.value = false } // e.g. the read failed: now the viewer explains it
        waitForIdle()
        assertEquals(1, onAllNodesWithTag(FILE_VIEWER_COPY_PATH_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun onlyPreviewableDocumentsSkipTheViewer() {
        for (path in listOf("out/report.pdf", "/Users/alex/app/Q3.XLSX", "C:\\work\\memo.docx", "slides.ppt")) assertTrue(isNativePreviewPath(path), path)
        for (path in listOf("out/bundle.zip", "notes.txt", "photo.png", "index.html", "/project.pdf/README")) assertFalse(isNativePreviewPath(path), path)
    }

    @Test
    fun documentsLandOnTheFileTab() {
        assertFalse(defaultDiffTab("/project/out/report.pdf", isImage = false, deleted = false))
        assertFalse(defaultDiffTab("C:\\project\\Q3.XLSX", isImage = false, deleted = false))
        assertTrue(defaultDiffTab("/project/out/report.pdf", isImage = false, deleted = true))
        assertTrue(defaultDiffTab("/project.pdf/README", isImage = false, deleted = false))
    }

    private companion object { const val WORKDIR = "/Users/alex/app" }
}
