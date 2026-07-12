package dev.ccpocket.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Locks the render-size guard that keeps one pathological transcript row (an ~800 KB skill
 *  injection, replayed whole since the #81 frame-budget change) from OOM-killing the iOS app on
 *  session open: clip at a line boundary under the cap, hard-cut single monster lines, never split
 *  a surrogate pair, never touch text within the cap. */
class RenderClipTest {

    @Test
    fun text_within_the_cap_passes_through_unchanged() {
        val s = "hello\nworld"
        assertEquals(s, renderClip(s))
        val exactly = "a".repeat(MAX_RENDER_CHARS)
        assertEquals(exactly, renderClip(exactly))
    }

    @Test
    fun long_text_cuts_at_the_last_line_break_under_the_cap() {
        val line = "x".repeat(999) + "\n" // 1000 chars per line, newlines at 999, 1999, …
        val out = renderClip(line.repeat(100)) // 100k chars
        assertEquals(MAX_RENDER_CHARS - 1, out.length) // cut lands ON the newline at 39_999, excluded
        assertTrue(out.endsWith("x")) // whole lines only — no mid-line tail
    }

    @Test
    fun single_monster_line_hard_cuts_at_the_cap() {
        val out = renderClip("y".repeat(MAX_RENDER_CHARS * 2))
        assertEquals(MAX_RENDER_CHARS, out.length)
    }

    @Test
    fun leading_newline_does_not_produce_an_empty_render() {
        // lastIndexOf finds only the newline at index 0 — that would clip to "" ; hard cut instead
        val out = renderClip("\n" + "y".repeat(MAX_RENDER_CHARS * 2))
        assertEquals(MAX_RENDER_CHARS, out.length)
    }

    @Test
    fun hard_cut_never_splits_a_surrogate_pair() {
        // the first emoji's high surrogate sits exactly at the cap boundary (index 39_999)
        val out = renderClip("z".repeat(MAX_RENDER_CHARS - 1) + "😀".repeat(10))
        assertEquals(MAX_RENDER_CHARS - 1, out.length) // stepped back off the high surrogate
        assertFalse(out.last().isHighSurrogate())
    }
}
