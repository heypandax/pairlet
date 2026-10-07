package dev.ccpocket.app.data

import dev.ccpocket.protocol.TextEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Voice input v2 highlight ranges (docs/design/VOICE-INPUT-V2-REVIEW.md §10 A8): computed from the original and the
 *  ordered edits, never by searching the result for `to`, and only when the rebuilt text matches the daemon's. */
class RefineHighlightTest {

    private fun slices(text: String, ranges: List<IntRange>) = ranges.map { text.substring(it) }

    @Test fun noEditsAndSameTextHighlightsNothing() {
        assertEquals(emptyList(), refineHighlightRanges("看一下日志", emptyList(), "看一下日志"))
    }

    @Test fun noEditsButDifferentTextIsNotAdopted() {
        assertNull(refineHighlightRanges("看一下日志", emptyList(), "看一下日志吧"))
    }

    @Test fun targetAlreadyPresentElsewhereIsLocatedByPositionNotBySearch() {
        val original = "请比较 cloud 和 Claude 的输出"
        val refined = "请比较 Claude 和 Claude 的输出"
        val ranges = refineHighlightRanges(original, listOf(TextEdit("cloud", "Claude")), refined)!!
        // A search for "Claude" in the result would find the same first spot here, so pin the exact index too.
        assertEquals(listOf(4 until 10), ranges)
        assertEquals(listOf("Claude"), slices(refined, ranges))
    }

    @Test fun secondTargetEqualsTextAlreadyPresent() {
        // The second `to` ("Claude") also exists earlier in the result — a naive search would mark the wrong one.
        val original = "Claude 写的，再跑 cloud 一次 codex"
        val edits = listOf(TextEdit("写的", "写得"), TextEdit("cloud", "Claude"))
        val refined = "Claude 写得，再跑 Claude 一次 codex"
        val ranges = refineHighlightRanges(original, edits, refined)!!
        assertEquals(listOf(7 until 9, 13 until 19), ranges)
        assertEquals(listOf("写得", "Claude"), slices(refined, ranges))
    }

    @Test fun lengthChangingEditShiftsLaterRanges() {
        val original = "open the read me and the cloud dot md"
        val edits = listOf(TextEdit("read me", "README"), TextEdit("cloud dot md", "CLAUDE.md"))
        val refined = "open the README and the CLAUDE.md"
        val ranges = refineHighlightRanges(original, edits, refined)!!
        assertEquals(listOf("README", "CLAUDE.md"), slices(refined, ranges))
        assertEquals(24, ranges[1].first)
    }

    @Test fun editsGivenOutOfOrderAreAppliedByPosition() {
        val original = "aa 一 bbb 二 c"
        val edits = listOf(TextEdit("bbb", "B"), TextEdit("aa", "AAAA"))
        val refined = "AAAA 一 B 二 c"
        assertEquals(listOf("AAAA", "B"), slices(refined, refineHighlightRanges(original, edits, refined)!!))
    }

    @Test fun surrogatePairEmojiBeforeAndBetweenEdits() {
        val original = "👍 跑一下 cloud 🚀 然后 get status"
        val edits = listOf(TextEdit("cloud", "Claude"), TextEdit("get status", "git status"))
        val refined = "👍 跑一下 Claude 🚀 然后 git status"
        val ranges = refineHighlightRanges(original, edits, refined)!!
        assertEquals(listOf("Claude", "git status"), slices(refined, ranges))
        assertEquals(7, ranges[0].first) // the emoji counts as two UTF-16 units
    }

    @Test fun boundaryInsideASurrogatePairIsNotAdopted() {
        val original = "a🚀b"
        assertNull(refineHighlightRanges(original, listOf(TextEdit("\uDE80b", "x")), "a\uD83Dx"))
    }

    @Test fun missingFromIsNotAdopted() {
        assertNull(refineHighlightRanges("看一下日志", listOf(TextEdit("cloud", "Claude")), "看一下日志"))
    }

    @Test fun repeatedFromIsNotAdopted() {
        assertNull(refineHighlightRanges("cloud 和 cloud", listOf(TextEdit("cloud", "Claude")), "Claude 和 cloud"))
    }

    @Test fun overlappingSecondOccurrenceIsNotAdopted() {
        assertNull(refineHighlightRanges("aaa", listOf(TextEdit("aa", "b")), "ba"))
    }

    @Test fun emptyFromIsNotAdopted() {
        assertNull(refineHighlightRanges("abc", listOf(TextEdit("", "x")), "xabc"))
    }

    @Test fun overlappingEditsAreNotAdopted() {
        val edits = listOf(TextEdit("cloud code", "Claude Code"), TextEdit("code base", "codebase"))
        assertNull(refineHighlightRanges("the cloud code base", edits, "the Claude Codebase"))
    }

    @Test fun rebuiltTextDifferentFromDaemonTextIsNotAdopted() {
        assertNull(refineHighlightRanges("跑 cloud", listOf(TextEdit("cloud", "Claude")), "跑 Claude 吧"))
    }
}
