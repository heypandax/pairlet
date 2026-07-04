package dev.ccpocket.app.ui

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the path-linkify contract, esp. Unicode filenames — an ASCII-only segment class used to
 *  truncate CJK paths mid-match, fail exists(), and silently produce no link. */
class PathLinkifyTest {

    /** exists() == true for everything: isolates the REGEX behavior from the filesystem. */
    private class OpenAll : PathOpener {
        val opened = mutableListOf<String>()
        override fun exists(path: String) = true
        override fun open(path: String) { opened.add(path) }
    }

    private fun linksIn(text: String): List<String> {
        val linked = AnnotatedString(text).withPathLinks(OpenAll())
        return linked.getLinkAnnotations(0, text.length).map { text.substring(it.start, it.end) }
    }

    @Test
    fun chinese_filename_links_whole_path() {
        val path = "~/Desktop/Brain/60_Outbox/2026-07-03-ccpocket-侧边栏固定会话设计提示词.md"
        assertEquals(listOf(path), linksIn("已写到 $path"))
    }

    @Test
    fun chinese_fullwidth_punctuation_terminates_the_match() {
        val path = "~/Desktop/笔记/设计.md"
        assertEquals(listOf(path), linksIn("已写到 $path，请查看。"))
    }

    @Test
    fun ascii_paths_and_sentence_period_still_work() {
        assertEquals(listOf("/Users/me/project/file.txt"), linksIn("see /Users/me/project/file.txt."))
        assertEquals(listOf("~/code/app"), linksIn("in ~/code/app now"))
    }

    @Test
    fun url_tails_and_slash_commands_do_not_link() {
        assertTrue(linksIn("open https://host.com/a/b and type /help").isEmpty())
    }
}
