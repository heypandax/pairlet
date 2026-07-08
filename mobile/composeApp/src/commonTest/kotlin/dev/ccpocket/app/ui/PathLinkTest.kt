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
}
