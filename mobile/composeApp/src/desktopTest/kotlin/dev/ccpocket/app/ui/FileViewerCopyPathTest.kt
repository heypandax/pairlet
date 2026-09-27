package dev.ccpocket.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import dev.ccpocket.app.advanceFrameAndWait
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.FileContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/** The phone's file viewer offers the file's path in its header, whatever the file's state. */
@OptIn(ExperimentalTestApi::class)
class FileViewerCopyPathTest {
    @Suppress("DEPRECATION")
    private class Clipboard : ClipboardManager {
        var copied: AnnotatedString? = null
        override fun getText() = copied
        override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
    }

    private fun copiedFromHeader(path: String, content: FileContent?): String? {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val clipboard = Clipboard()
        val repo = PocketRepository(scope).apply {
            workdir.value = WORKDIR
            viewedFilePath.value = path
            viewedFile.value = content
        }
        try {
            runDesktopComposeUiTest(402, 874) {
                setContent {
                    CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                        PocketTheme { FileViewerScreen(repo, onBack = {}) }
                    }
                }
                advanceFrameAndWait()
                onNodeWithTag(FILE_VIEWER_COPY_PATH_TAG).performClick()
                advanceFrameAndWait()
            }
        } finally {
            scope.cancel()
        }
        return clipboard.copied?.text
    }

    @Test
    fun aRelativePathCopiesAsTheAbsolutePathUnderTheWorkdir() {
        val content = FileContent(WORKDIR, "sid", "docs/设计说明.txt", text = "hello")
        assertEquals("$WORKDIR/docs/设计说明.txt", copiedFromHeader("docs/设计说明.txt", content))
    }

    @Test
    fun anAbsolutePathCopiesVerbatimEvenWhileLoading() {
        assertEquals("/srv/other/notes.txt", copiedFromHeader("/srv/other/notes.txt", content = null))
    }

    private companion object { const val WORKDIR = "/Users/alex/app" }
}
