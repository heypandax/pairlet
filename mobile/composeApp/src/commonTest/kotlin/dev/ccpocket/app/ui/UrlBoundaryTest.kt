package dev.ccpocket.app.ui

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * URL end-boundary rules (issue #154): the markdown form `[文本](https://…)` must not swallow its
 * closing paren, and punctuation glued after a URL (ASCII or full-width) stays out of the link.
 * Runs on every target including Kotlin/Native — like [PathLinkTest] this doubles as an engine pin
 * for [urlRx]. Both recognition pipelines are covered: [recognizeEntities] (LinkifiedText body
 * lines, phone + desktop) and [withUrlLinks] (the pathLinked pass for code blocks / tables).
 */
class UrlBoundaryTest {

    private fun entities(text: String) = recognizeEntities(text, cwd = null) { false }
    private fun urls(text: String) = entities(text).filter { it.kind == EntityKind.URL }

    // ① markdown link syntax: the wrapping `)` is structure, not URL
    @Test
    fun markdown_link_keeps_closing_paren_out() {
        val e = urls("[执行副本](https://x.dev/base/abc123) 已建好").single()
        assertEquals("https://x.dev/base/abc123", e.copyValue)
        assertEquals(e.copyValue, e.display)
    }

    @Test
    fun real_captured_line_markdown_link_glued_to_fullwidth_paren() {
        // the REAL transcript line behind issue #154 (backticks already stripped by inline(), as the
        // renderer does): `)（token）` follows the URL with no whitespace. The old blocklist regex
        // didn't stop at the full-width `（`, so the link swallowed ")（token" whole and the tap
        // opened a broken address. Exactly one entity: the clean URL; the bare token stays inert.
        val text = "Task 1 · Base 执行副本 — [执行副本](https://hellotalk.feishu.cn/base/LpTJbWmZNa2uBrsuzBlcjjg9noe)（LpTJbWmZNa2uBrsuzBlcjjg9noe）"
        val all = entities(text)
        assertEquals(1, all.size)
        assertEquals(EntityKind.URL, all.single().kind)
        assertEquals("https://hellotalk.feishu.cn/base/LpTJbWmZNa2uBrsuzBlcjjg9noe", all.single().copyValue)
    }

    // ② bare URL + glued punctuation, ASCII and full-width
    @Test
    fun parenthesized_prose_url_trims_trailing_paren() {
        assertEquals("https://x.dev/a", urls("(见 https://x.dev/a)").single().copyValue)
    }

    @Test
    fun fullwidth_punctuation_after_url_stays_out() {
        assertEquals("https://x.dev/a", urls("文档在 https://x.dev/a，稍后看").single().copyValue)
        assertEquals("https://x.dev/a", urls("发布见 https://x.dev/a。").single().copyValue)
        assertEquals("https://x.dev/a", urls("链接（https://x.dev/a）在此").single().copyValue)
        assertEquals("https://x.dev/a", urls("说明 https://x.dev/a（附 token）").single().copyValue)
    }

    // ③ a bare token (no scheme, no slash) is never clickable
    @Test
    fun bare_token_in_parens_is_not_recognised() {
        assertTrue(entities("（LpTJbWmZNa2uBrsuzBlcjjg9noe）").isEmpty())
        assertTrue(entities("(LpTJbWmZNa2uBrsuzBlcjjg9noe)").isEmpty())
    }

    // the pathLinked pipeline (code blocks / tables) shares the same boundary rule
    @Test
    fun withUrlLinks_shares_the_boundary() {
        val text = "[执行副本](https://x.dev/base/abc)（token）"
        val linked = AnnotatedString(text).withUrlLinks()
        assertEquals(text, linked.text) // linkification never mutates the text itself
        val links = linked.getLinkAnnotations(0, text.length)
        assertEquals(1, links.size)
        assertEquals("https://x.dev/base/abc", text.substring(links[0].start, links[0].end))
    }

    // guard: the allowlist keeps real URL machinery — -._~:/?#@!$&()*+,;=%[] all survive mid-URL
    @Test
    fun url_machinery_survives_intact() {
        val u = "https://x.dev/p-a_b~c/(v1)/f.php?q=a+b&r[]=c%20d,e;f=g:h@i#frag"
        assertEquals(u, urls("see $u now").single().copyValue)
    }
}
