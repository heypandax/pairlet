package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalTestApi::class)
class LinkLongPressTest {
    @Suppress("DEPRECATION")
    private class Clipboard : ClipboardManager {
        var value: AnnotatedString? = null
        override fun getText() = value
        override fun setText(annotatedString: AnnotatedString) { value = annotatedString }
    }

    private class Toolbar : TextToolbar {
        var shows = 0
        override var status = TextToolbarStatus.Hidden
        override fun hide() { status = TextToolbarStatus.Hidden }
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            shows++
            status = TextToolbarStatus.Shown
        }
    }

    private class Fixture {
        val opened = mutableListOf<String>()
        val clipboard = Clipboard()
        val toolbar = Toolbar()
    }

    private fun ComposeUiTest.render(
        source: String = PATH, markdown: Boolean = false, copyOnly: Boolean = false,
        content: (@Composable () -> Unit)? = null,
    ): Fixture {
        val f = Fixture()
        val opener = if (copyOnly) null else RemotePathOpener(f.opened::add)
        setContent {
            PocketTheme {
                CompositionLocalProvider(
                    LocalPathOpener provides opener, LocalPathCwd provides "/Users/alex/project",
                    LocalClipboardManager provides f.clipboard, LocalTextToolbar provides f.toolbar,
                ) {
                    SelectionContainer {
                        Box(Modifier.width(360.dp)) {
                            if (content != null) content()
                            else if (markdown) MarkdownText(source, Color.Black)
                            else LinkifiedText(AnnotatedString(source), Color.Black, Modifier.testTag("link"))
                        }
                    }
                }
            }
        }
        return f
    }

    @Test
    fun longPressOnAFileInSelectableTextDoesNotOpenOnRelease() = runComposeUiTest {
        val f = render()
        onNodeWithTag("link").performTouchInput { longClick(Offset(8f, 8f)) }
        waitForIdle()
        assertTrue(f.opened.isEmpty(), "long-press release must not open the file: ${f.opened}")
        // The full address appears both in the transcript and in the action sheet.
        onAllNodes(hasText(PATH)).assertCountEquals(2)
        onNodeWithTag(LINK_COPY_TAG).assertIsDisplayed().performClick()
        assertEquals(PATH, f.clipboard.value?.text)
        onNodeWithTag(LINK_ACTION_SHEET_TAG).assertDoesNotExist()
        assertTrue(f.opened.isEmpty())
        assertEquals(0, f.toolbar.shows, "link long-press must not also select text")
    }

    @Test
    fun shortTouchAndMouseClickEachOpenExactlyOnce() = runComposeUiTest {
        val f = render()
        onNodeWithTag("link").performTouchInput { click(Offset(8f, 8f)) }
        assertEquals(listOf(PATH), f.opened)
        onNodeWithTag(LINK_ACTION_SHEET_TAG).assertDoesNotExist()
        onNodeWithTag("link").performMouseInput { click(Offset(8f, 8f)) }
        assertEquals(listOf(PATH, PATH), f.opened)
    }

    @Test
    fun switchingTheOpenerUpdatesNativeLinkClicks() = runComposeUiTest {
        val oldOpened = mutableListOf<String>()
        val newOpened = mutableListOf<String>()
        val opener = mutableStateOf(RemotePathOpener(oldOpened::add))
        render(content = {
            CompositionLocalProvider(LocalPathOpener provides opener.value) {
                LinkifiedText(AnnotatedString(PATH), Color.Black, Modifier.testTag("link"))
            }
        })
        runOnIdle { opener.value = RemotePathOpener(newOpened::add) }
        onNodeWithTag("link").performMouseInput { click(Offset(8f, 8f)) }
        assertTrue(oldOpened.isEmpty(), "a link must not retain the previous session's opener")
        assertEquals(listOf(PATH), newOpened)
    }

    @Test
    fun openingFromTheSheetIsAnExplicitSeparateAction() = runComposeUiTest {
        val f = render()
        onNodeWithTag("link").performTouchInput { longClick(Offset(8f, 8f)) }
        assertTrue(f.opened.isEmpty())
        onNodeWithTag(LINK_OPEN_TAG).performClick()
        assertEquals(listOf(PATH), f.opened)
        onNodeWithTag(LINK_ACTION_SHEET_TAG).assertDoesNotExist()
    }

    @Test
    fun dismissingTheSheetDoesNotOpenTheLink() = runComposeUiTest {
        val f = render()
        onNodeWithTag("link").performTouchInput { longClick(Offset(8f, 8f)) }
        onNodeWithTag(LINK_ACTION_SHEET_TAG).performTouchInput { click(Offset(4f, 4f)) }
        onNodeWithTag(LINK_ACTION_SHEET_TAG).assertDoesNotExist()
        assertTrue(f.opened.isEmpty())
    }

    @Test
    fun labelledFileCopiesTheDestinationInsteadOfTheLabel() = runComposeUiTest {
        val f = render("[交接文档](<docs/我的 交接.md>)", markdown = true)
        onNodeWithText("交接文档").performTouchInput { longClick(center) }
        onNodeWithTag(LINK_COPY_TAG).performClick()
        assertEquals("/Users/alex/project/docs/我的 交接.md", f.clipboard.value?.text)
        assertTrue(f.opened.isEmpty())
    }

    @Test
    fun tableLinksUseTheSameFullSizeMenu() = runComposeUiTest {
        val f = render("| 文档 | 状态 |\n| --- | --- |\n| [交接文档]($PATH) | 待处理 |", markdown = true)
        onNodeWithText("交接文档").performTouchInput { longClick(center) }
        onNodeWithTag(LINK_COPY_TAG).assertIsDisplayed().performClick()
        assertEquals(PATH, f.clipboard.value?.text)
        assertTrue(f.opened.isEmpty())
    }

    @Test
    fun aQuotedListLinkOpensItsActualTarget() = runComposeUiTest {
        val f = render("> - [交接文档]($PATH)", markdown = true)
        onNodeWithText("交接文档").performTouchInput { click(center) }
        assertEquals(listOf(PATH), f.opened)
    }

    @Test
    fun urlCopyPreservesTheEntireDestination() = runComposeUiTest {
        val url = "https://example.org/a_(b)?name=%E4%B8%AD&x=1#section"
        val f = render("[网站]($url)", markdown = true)
        onNodeWithText("网站").performTouchInput { longClick(center) }
        onNodeWithTag(LINK_COPY_TAG).performClick()
        assertEquals(url, f.clipboard.value?.text)
    }

    @Test
    fun unavailablePathsStillCopyWithoutAnOpenAction() = runComposeUiTest {
        val f = render(copyOnly = true)
        onNodeWithTag("link").performTouchInput { longClick(Offset(8f, 8f)) }
        onNodeWithTag(LINK_OPEN_TAG).assertDoesNotExist()
        onNodeWithTag(LINK_COPY_TAG).performClick()
        assertEquals(PATH, f.clipboard.value?.text)
    }

    @Test
    fun draggingFromALinkScrollsWithoutOpeningIt() = runComposeUiTest {
        val scroll = ScrollState(0)
        val f = render(content = {
            Column(Modifier.height(180.dp).verticalScroll(scroll)) {
                Spacer(Modifier.height(90.dp))
                LinkifiedText(AnnotatedString(PATH), Color.Black, Modifier.testTag("link"))
                Spacer(Modifier.height(600.dp))
            }
        })
        onNodeWithTag("link").performTouchInput { swipe(Offset(8f, 8f), Offset(8f, -70f), durationMillis = 200) }
        assertTrue(scroll.value > 0, "the scroll container must retain drag gestures starting on a link")
        assertTrue(f.opened.isEmpty())
        onNodeWithTag(LINK_ACTION_SHEET_TAG).assertDoesNotExist()
    }

    @Test
    fun longPressOnOrdinaryTextStillSelectsIt() = runComposeUiTest {
        val f = render("ordinary words before $PATH")
        onNodeWithTag("link").performTouchInput { longClick(Offset(8f, 8f)) }
        assertTrue(f.toolbar.shows > 0)
        onNodeWithTag(LINK_ACTION_SHEET_TAG).assertDoesNotExist()
        assertTrue(f.opened.isEmpty())
    }

    @Test
    fun longDestinationKeepsBothActionsVisibleInAPhoneWindow() = runDesktopComposeUiTest(width = 402, height = 874) {
        val path = "/Users/panda/Desktop/Pairlet/_local/2026-09-27-github-feedback-handoff/用户反馈修改交接.md"
        val f = render("[用户反馈修改交接文档]($path)", markdown = true)
        onNodeWithText("用户反馈修改交接文档").performTouchInput { longClick(center) }
        onNodeWithTag(LINK_COPY_TAG).assertIsDisplayed()
        onNodeWithTag(LINK_OPEN_TAG).assertIsDisplayed()
        System.getenv("PAIRLET_LINK_SHOT")?.let { destination ->
            val image = onNodeWithTag(LINK_ACTION_SHEET_TAG).captureToImage()
            File(destination).writeBytes(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        onNodeWithTag(LINK_COPY_TAG).performClick()
        assertEquals(path, f.clipboard.value?.text)
    }

    private companion object { const val PATH = "/Users/alex/project/readme.md" }
}
