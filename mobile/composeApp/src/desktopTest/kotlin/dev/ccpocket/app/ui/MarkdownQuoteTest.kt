package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.code_copied
import dev.ccpocket.app.resources.code_copy
import dev.ccpocket.app.resources.quote_copied
import dev.ccpocket.app.resources.quote_copy
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `>` blockquotes render as a ruled quote instead of literal markers, with a copy glyph of their own. */
@OptIn(ExperimentalTestApi::class)
class MarkdownQuoteTest {
    private class Clipboard : ClipboardManager {
        var copied: AnnotatedString? = null
        override fun getText() = copied
        override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
    }

    private val copyLabel get() = str(Res.string.code_copy)

    private fun ComposeUiTest.glyphCount() = onAllNodes(hasTestTag(QUOTE_COPY_TAG)).fetchSemanticsNodes().size

    private fun ComposeUiTest.render(markdown: String): Clipboard {
        val clipboard = Clipboard()
        setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                PocketTheme { Box(Modifier.width(360.dp)) { MarkdownText(markdown, Color.Black) } }
            }
        }
        waitForIdle()
        return clipboard
    }

    @Test
    fun onlyAnOpeningMarkerQuotesALine() {
        assertEquals("a", mdQuoteBody("> a"))
        assertEquals("a", mdQuoteBody(">a"))
        assertEquals("", mdQuoteBody(">"))
        assertEquals(" two", mdQuoteBody(">  two")) // only one space belongs to the marker
        assertEquals("> nested", mdQuoteBody(">> nested"))
        assertEquals("under a list item", mdQuoteBody("    > under a list item"))
        assertNull(mdQuoteBody("a > b"))
        assertNull(mdQuoteBody("-> arrow"))
        assertNull(mdQuoteBody(""))
    }

    // The reported case: Codex drafts a WeChat message as a quote. The markers must not show, and the
    // quote's glyph copies the draft alone — as read, without the Markdown around its bold/code spans.
    @Test
    fun draftedMessageRendersWithoutMarkersAndCopiesAlone() = runComposeUiTest {
        val clipboard = render(
            "可以这样发：\n\n" +
                "> Hi Alex，**好久没联系了**，中秋假期快乐！\n" +
                ">\n" +
                "> 我最近从 HelloTalk 离开了，在看 `AI` 方向的机会。\n\n" +
                "等他回复再约时间。",
        )
        onAllNodes(hasText(">", substring = true)).assertCountEquals(0)
        assertPresent("Hi Alex，好久没联系了，中秋假期快乐！")
        assertPresent("等他回复再约时间。")
        onAllNodes(hasText(copyLabel)).assertCountEquals(0) // no text chip: the quote's copy is a glyph
        onNodeWithTag(QUOTE_COPY_TAG).assertContentDescriptionEquals(str(Res.string.quote_copy))
        onNodeWithTag(QUOTE_COPY_TAG).performClick()
        assertEquals("Hi Alex，好久没联系了，中秋假期快乐！\n\n我最近从 HelloTalk 离开了，在看 AI 方向的机会。", clipboard.copied?.text)
        waitForIdle()
        assertPresent(str(Res.string.code_copied)) // the "copied" label rides next to the check
        onNodeWithTag(QUOTE_COPY_TAG).assertContentDescriptionEquals(str(Res.string.quote_copied))
    }

    @Test
    fun quoteEndsAtTheFirstUnmarkedLine() = runComposeUiTest {
        val clipboard = render("> quoted\nnot quoted")
        assertPresent("not quoted")
        onNodeWithTag(QUOTE_COPY_TAG).performClick()
        assertEquals("quoted", clipboard.copied?.text)
    }

    // A `>` that is content rather than a marker keeps its literal meaning — mid-line, or inside a fence.
    @Test
    fun literalAnglesStayLiteral() = runComposeUiTest {
        render("a > b\n\n```bash\n> not a quote\n```")
        assertPresent("a > b")
        assertPresent("> not a quote")
        onAllNodes(hasText(copyLabel)).assertCountEquals(1) // the code block's chip
        assertEquals(0, glyphCount()) // and no quote
    }

    @Test
    fun copyDropsHeadingMarkersAndFencesButKeepsListMarkers() = runComposeUiTest {
        val clipboard = render("> ## 标题\n> - **一**\n> ```kotlin\n> val x = 1\n> ```\nafter")
        onAllNodes(hasText(copyLabel)).assertCountEquals(1) // the nested code block keeps its own chip
        onNodeWithTag(QUOTE_COPY_TAG).performClick()
        assertEquals("标题\n- 一\nval x = 1", clipboard.copied?.text)
    }

    // Design 1a: a nested level is a fainter rule with no glyph of its own; the outer glyph takes both.
    @Test
    fun nestedQuoteHasNoGlyphAndTheOuterCopyTakesBoth() = runComposeUiTest {
        val clipboard = render("> outer\n>\n>> inner\n\nafter")
        onAllNodes(hasText(">", substring = true)).assertCountEquals(0)
        assertEquals(1, glyphCount())
        onNodeWithTag(QUOTE_COPY_TAG).performClick()
        assertEquals("outer\n\ninner", clipboard.copied?.text)
    }

    // Thousands of `>` must neither overflow the stack nor nest past the cap; the rest stays literal.
    @Test
    fun deepNestingIsCapped() = runComposeUiTest {
        render(">".repeat(5_000) + " deep")
        assertEquals(1, glyphCount()) // only the outermost level carries a glyph
        assertPresent(">".repeat(5_000 - MAX_QUOTE_DEPTH) + " deep")
    }
}
