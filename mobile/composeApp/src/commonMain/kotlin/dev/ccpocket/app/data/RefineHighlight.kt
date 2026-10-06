package dev.ccpocket.app.data

import dev.ccpocket.protocol.TextEdit

/**
 * Voice input v2 (docs/design/VOICE-INPUT-V2-REVIEW.md §10 A8, §11): where each corrected fragment sits in the
 * daemon's refined text, for the 700 ms highlight before sending.
 *
 * Positions are NOT found by searching the result for `to` — a `to` may already occur elsewhere (`cloud → Claude`
 * in a sentence that also says "Claude"), and an earlier length-changing edit shifts every later one. Instead each
 * `from` is located in [original] (it must occur exactly once), the edits are applied in order of position while
 * tracking the length difference, and the rebuilt text must equal [refinedText] — the daemon's text stays the whole
 * truth; this only explains it.
 *
 * Returns the ranges of the `to` fragments in [refinedText] (UTF-16 indices, in order of position; an empty `to`
 * yields an empty range), or null when the answer cannot be explained: an empty or missing or repeated `from`,
 * overlapping edits, a boundary that splits a surrogate pair, or a rebuilt text that differs. The caller treats
 * null as "result not adopted".
 */
fun refineHighlightRanges(original: String, edits: List<TextEdit>, refinedText: String): List<IntRange>? {
    if (edits.isEmpty()) return if (original == refinedText) emptyList() else null
    val located = ArrayList<Pair<Int, TextEdit>>(edits.size)
    for (edit in edits) {
        if (edit.from.isEmpty()) return null
        val at = original.indexOf(edit.from)
        if (at < 0) return null
        // `at + 1`, not `at + from.length`: an overlapping second occurrence ("aa" in "aaa") is still ambiguous.
        if (original.indexOf(edit.from, at + 1) >= 0) return null
        if (splitsSurrogatePair(original, at) || splitsSurrogatePair(original, at + edit.from.length)) return null
        located += at to edit
    }
    located.sortBy { it.first }

    val rebuilt = StringBuilder(refinedText.length)
    val ranges = ArrayList<IntRange>(located.size)
    var cursor = 0
    for ((at, edit) in located) {
        if (at < cursor) return null // overlaps the previous edit's `from`
        rebuilt.append(original, cursor, at)
        val start = rebuilt.length
        rebuilt.append(edit.to)
        ranges += start until rebuilt.length
        cursor = at + edit.from.length
    }
    rebuilt.append(original, cursor, original.length)
    if (rebuilt.toString() != refinedText) return null
    if (ranges.any { splitsSurrogatePair(refinedText, it.first) || splitsSurrogatePair(refinedText, it.last + 1) }) return null
    return ranges
}

/** True when [index] falls between the two halves of a surrogate pair in [s]. */
private fun splitsSurrogatePair(s: String, index: Int): Boolean =
    index in 1 until s.length && s[index - 1].isHighSurrogate() && s[index].isLowSurrogate()
