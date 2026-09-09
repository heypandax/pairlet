package dev.ccpocket.app.ui

import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.FileDiff
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlPreviewPolicyTest {
    @Test
    fun htmlFilesOpenInPreviewIncludingWindowsAndUppercasePaths() {
        for (path in listOf("/project/index.html", "C:\\project\\INDEX.HTM", "/project/你好.HTML")) {
            assertTrue(isHtmlPath(path))
            assertFalse(defaultDiffTab(path, isImage = false, deleted = false))
        }
        assertFalse(isHtmlPath("/project.html/README"))
        assertFalse(isHtmlPath("/project/index.html.txt"))
        assertTrue(defaultDiffTab("/project/index.html", isImage = false, deleted = true))
        assertTrue(defaultDiffTab("/project/main.kt", isImage = false, deleted = false))
        assertFalse(defaultDiffTab("/project/photo.png", isImage = true, deleted = false))
    }

    @Test
    fun htmlHasNoTextWrapButtonButItsDiffStillDoes() {
        val content = FileContent("/project", "session", "/project/index.html", text = "<h1>Hello</h1>")
        val diff = FileDiff("/project", "session", content.path, diff = "@@ -0,0 +1 @@\n+<h1>Hello</h1>")
        assertFalse(wrapApplies(false, diff, content, "html", false))
        assertTrue(wrapApplies(true, diff, content, "html", false))
        assertTrue(wrapApplies(false, diff, content.copy(path = "/project/main.txt"), "txt", false))
    }
}
