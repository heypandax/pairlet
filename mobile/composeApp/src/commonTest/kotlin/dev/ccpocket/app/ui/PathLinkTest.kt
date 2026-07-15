package dev.ccpocket.app.ui

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs on every target INCLUDING Kotlin/Native (iosSimulatorArm64Test): PATH_RX uses lookbehind +
 * \p{L}/\p{N} property classes, and a construct the Native regex engine rejects would throw in the
 * file's top-level initializer — i.e. crash the iOS app the first time ANY Markdown renders
 * (= tapping a session). This pins "the regex compiles and matches on this engine" as a test.
 */
class PathLinkTest {

    private val openAll = object : PathOpener {
        override fun exists(path: String) = true
        override fun open(path: String) {}
    }

    @Test
    fun pathRegexCompilesAndLinksOnThisEngine() {
        val text = "see /Users/x/proj/File.kt and ~/code/设计提示词.md plus C:\\Users\\x\\a.txt."
        val linked = AnnotatedString(text).withPathLinks(openAll)
        assertEquals(text, linked.text) // linkification never mutates the text itself
        assertTrue(linked.getLinkAnnotations(0, text.length).size >= 2, "expected path links to be added")
    }

    @Test
    fun relativePathWithExtensionLinks() {
        // issue #74: a cwd-relative path (no leading / or ~) with a CJK filename and an extension is
        // the shape the CLI prints for edited files; it must linkify (the opener resolves it under cwd)
        val text = "写到 10_Notes/会议/2026-07-09_对齐材料.md 完成"
        val linked = AnnotatedString(text).withPathLinks(openAll)
        val links = linked.getLinkAnnotations(0, text.length)
        assertEquals(1, links.size)
        assertEquals("10_Notes/会议/2026-07-09_对齐材料.md", text.substring(links[0].start, links[0].end))
    }

    @Test
    fun relativeProseWithoutExtensionDoesNotLink() {
        // extension-less relative compounds ("and/or", "src/main") stay prose — the conservative gate
        val text = "flip the and/or switch in src/main today"
        assertEquals(0, AnnotatedString(text).withPathLinks(openAll).getLinkAnnotations(0, text.length).size)
    }

    @Test
    fun nullOpenerIsInert() {
        val text = "plain /a/b/c prose"
        val out = AnnotatedString(text).withPathLinks(null)
        assertEquals(0, out.getLinkAnnotations(0, text.length).size)
    }

    @Test
    fun urlRegexCompilesAndLinksOnThisEngine() {
        // same K/N-engine pin as the path regex; trailing sentence punctuation must stay outside the link
        val text = "docs at https://example.dev/a/b?q=1, and 中文见 https://x.cn/文档。"
        val linked = AnnotatedString(text).withUrlLinks()
        assertEquals(text, linked.text)
        val links = linked.getLinkAnnotations(0, text.length)
        assertEquals(2, links.size)
        val first = text.substring(links[0].start, links[0].end)
        assertEquals("https://example.dev/a/b?q=1", first) // the "," stayed out
    }

    @Test
    fun plainProseGetsNoUrlLinks() {
        val text = "no links in http-less prose /a/b/c"
        assertEquals(0, AnnotatedString(text).withUrlLinks().getLinkAnnotations(0, text.length).size)
    }

    @Test
    fun urlEndsAtTheMarkdownLinkParen() {
        // issue #154, the reported shape verbatim: a markdown link trailed by a CJK parenthetical (the
        // backticks are already gone — inline() strips them before linkification sees the text). The match
        // used to jump the link's own ")" into "（Lp9noe" and halt at "）"; ending on a letter, URL_TRAIL's
        // trim never fired, so one span covered ")（Lp9noe" and the tap opened that address.
        val text = "Base 执行副本 — [执行副本](https://hellotalk.feishu.cn/base/Lp9noe)（Lp9noe）"
        val linked = AnnotatedString(text).withUrlLinks()
        val links = linked.getLinkAnnotations(0, text.length)
        assertEquals(1, links.size)
        assertEquals("https://hellotalk.feishu.cn/base/Lp9noe", text.substring(links[0].start, links[0].end))
    }

    @Test
    fun urlEndsAtAnAdjacentBracketOfEitherWidth() {
        // the same class either side of the pair: a halfwidth parenthetical glued straight onto the link's
        // ")", and a bare URL a CJK "（" runs into with no ")" for the trim to catch in the first place
        val half = "[t](https://a.dev/x)(Lp9noe)"
        val halfLinks = AnnotatedString(half).withUrlLinks().getLinkAnnotations(0, half.length)
        assertEquals("https://a.dev/x", half.substring(halfLinks[0].start, halfLinks[0].end))

        val full = "见 https://x.dev/a（说明）继续"
        val fullLinks = AnnotatedString(full).withUrlLinks().getLinkAnnotations(0, full.length)
        assertEquals("https://x.dev/a", full.substring(fullLinks[0].start, fullLinks[0].end))
    }

    @Test
    fun anIdeographicCommaSeparatesTwoUrls() {
        // "、" is a list separator, never URL content: it used to glue both links into one dead span
        val text = "见 https://a.dev/x、https://b.dev/y"
        val links = AnnotatedString(text).withUrlLinks().getLinkAnnotations(0, text.length)
        assertEquals(2, links.size)
        assertEquals("https://a.dev/x", text.substring(links[0].start, links[0].end))
        assertEquals("https://b.dev/y", text.substring(links[1].start, links[1].end))
    }
}
