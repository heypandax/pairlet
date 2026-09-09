package dev.ccpocket.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.asSkiaBitmap
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.present
import dev.ccpocket.app.theme.PocketTheme
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

@OptIn(ExperimentalTestApi::class)
class DesktopFilePreviewTest {
    @Test
    fun transcriptClickOpensRightPreviewAndKeepsComposerUsable() {
        val base = createTempDirectory("ccpocket-md-ui").toFile()
        try {
            val path = "notes/材料.md"
            val file = File(base, path).apply {
                parentFile.mkdirs()
                writeText("# 设计说明\n\n这里是 **Markdown 预览**。\n\n- 保留左侧会话\n- 右侧阅读文档\n\n```kotlin\nval preview = true\n```")
            }
            val model = object : SeedDesktopModel() {
                override val watch: DkWatch? = null
                override val chatWorkdir = base.path
                override val messages = listOf(ChatItem.Assistant("查看 $path"))
                override val ask = null
                override val streaming = false
            }
            runDesktopComposeUiTest(width = 1180, height = 798) {
                setContent { PocketTheme { DesktopApp(model) } }
                waitForIdle()
                onNodeWithTag("desktop-file-preview").assertDoesNotExist()
                val link = onNodeWithText("查看 $path", useUnmergedTree = true)
                val layouts = mutableListOf<TextLayoutResult>()
                link.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                val at = layouts.single().getBoundingBox("查看 ".length + 2).center
                link.performTouchInput { click(at) }
                waitUntil(timeoutMillis = 5_000) { present("设计说明") }
                onNodeWithTag("desktop-file-preview").assertIsDisplayed()
                val previewBounds = onNodeWithTag("desktop-file-preview").fetchSemanticsNode().boundsInRoot
                val chatBounds = onNodeWithText("查看 $path", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue(previewBounds.left > chatBounds.right, "preview must be beside the conversation")
                onAllNodes(hasSetTextAction()).onFirst().performTextInput("继续讨论")
                assertTrue(present("继续讨论"), "the chat composer remains editable")

                val image = onRoot().captureToImage()
                File("build/screenshots/markdown-split-preview.png").apply {
                    parentFile.mkdirs()
                    writeBytes(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }

                file.writeText("# 更新后的文档")
                onNodeWithTag("file-preview-refresh").performClick()
                waitUntil(timeoutMillis = 5_000) { present("更新后的文档") }
                onNodeWithText("设计说明").assertDoesNotExist()
                onNodeWithTag("file-preview-close").performClick()
                onNodeWithTag("desktop-file-preview").assertDoesNotExist()
                assertTrue(present("继续讨论"), "closing the preview preserves the draft")
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun switchingDocumentsReplacesTheBodyAndMissingFilesShowAnError() {
        val base = createTempDirectory("ccpocket-md-switch").toFile()
        try {
            val first = File(base, "first.md").apply { writeText("# First document") }
            val second = File(base, "second.md").apply { writeText("# Second document") }
            val state = DesktopFilePreviewState().apply { open(first) }
            runDesktopComposeUiTest(width = 1000, height = 700) {
                setContent {
                    PocketTheme {
                        DesktopFilePreviewLayout(state) { Box(Modifier.fillMaxSize().testTag("chat")) }
                    }
                }
                waitUntil(timeoutMillis = 5_000) { present("First document") }
                runOnIdle { state.open(second) }
                waitUntil(timeoutMillis = 5_000) { present("Second document") }
                onNodeWithText("First document").assertDoesNotExist()
                second.delete()
                onNodeWithTag("file-preview-refresh").performClick()
                waitUntil(timeoutMillis = 5_000) { present("file no longer exists") }
                onNodeWithTag("chat").assertIsDisplayed()
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun localReadIsBoundedAndDoesNotSplitUtf8Characters() {
        val base = createTempDirectory("ccpocket-md-read").toFile()
        try {
            val file = File(base, "large.md").apply { writeText("文".repeat(100_000)) }
            val content = readDesktopMarkdown(file)
            assertTrue(content.ok)
            assertTrue(content.truncated)
            assertEquals(300_000L, content.totalBytes)
            assertEquals("文".repeat(DESKTOP_MARKDOWN_MAX_BYTES / 3), content.text)
            file.writeText("# 中文文档\n完整正文")
            val short = readDesktopMarkdown(file)
            assertEquals(file.readText(), short.text)
            assertFalse(short.truncated)
            file.delete()
            assertFalse(readDesktopMarkdown(file).ok)
        } finally {
            base.deleteRecursively()
        }
    }
}
