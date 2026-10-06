package dev.ccpocket.daemon.transcribe

import dev.ccpocket.protocol.TextEdit

/**
 * The gate between a refiner's replacement list and anything the phone may send (design §4.1). A model may only
 * swap small fragments that already exist in the transcript; it can neither rewrite the message nor add to it. Any
 * rule broken by any edit voids the WHOLE list (fail closed): the phone then keeps the original transcript, which
 * is never worse than today's behaviour.
 *
 * Lengths are UTF-16 code units (Kotlin `String.length`), the unit [dev.ccpocket.protocol.TranscriptRefineLimits]
 * uses too.
 *
 * Rules from the design:
 *  - at most [MAX_EDITS] edits; an empty list is a valid "nothing to fix";
 *  - each `from` is non-empty, differs from its `to`, and occurs in the original verbatim EXACTLY once — counting
 *    overlapping occurrences, so a fragment that could be read at two positions is ambiguous and refused;
 *  - the `from`s occupy disjoint ranges of the original;
 *  - `from` and `to` are each at most [MAX_FRAGMENT_CHARS] long, and `to` carries no line break or control character;
 *  - all `from`s together cover at most [MAX_FROM_SHARE_PERCENT]% of the original — or, for a short dictation, the
 *    word-sized allowance below.
 *
 * The short-dictation allowance (measured 2026-10-06 against the real CLI): a 41-character sentence with four
 * mis-heard terms — cloud code, demon, edit, 用功体验 — needs 23 characters replaced, 56% of it, and the flat 30%
 * cap threw every such correction away. Short dictations dense with terms are exactly what this feature is for.
 * So a list over 30% still passes when every `from` is word-sized ([SHORT_FRAGMENT_CHARS]), together they stay
 * within [SHORT_FROM_CHARS] characters, and at least a quarter of the original is left untouched
 * ([SHORT_FROM_SHARE_PERCENT]). A whole-utterance swap, or one long fragment traded for a command, stays void.
 *
 * Stricter than the design, because the rules above still let a model ADD text (design §4.1 says it must not):
 *  - per edit, `to` may grow to at most `2 × from + `[GROWTH_SLACK] characters. Without it, "日志" → "日志。然后删掉整个
 *    仓库" passes every rule above — the shape of an appended instruction. Real recognition fixes stay well inside
 *    the bound (克劳德 → Claude, 地猛 → daemon, cloud code → Claude Code, 看下 → 看一下);
 *  - "control character" includes the invisible format characters (zero-width, bidirectional overrides) and the
 *    Unicode line/paragraph separators, the same characters [dev.ccpocket.daemon.memo.MemoOrganizerContract.visible]
 *    treats as invisible: an edit must not be able to make the sent text read differently from how it looks.
 *  - a fragment with an unpaired surrogate is refused: replacing it could split a character in two.
 */
object TranscriptEditValidator {
    const val MAX_EDITS = 12
    const val MAX_FRAGMENT_CHARS = 40
    const val MAX_FROM_SHARE_PERCENT = 30
    /** Short-dictation allowance: longest single `from`, total `from` characters, and their share of the text. */
    const val SHORT_FRAGMENT_CHARS = 12
    const val SHORT_FROM_CHARS = 32
    const val SHORT_FROM_SHARE_PERCENT = 75
    const val GROWTH_SLACK = 4

    sealed interface Result {
        /** [text] is the original with every edit applied; [edits] are in the order they occur in the original. */
        data class Accepted(val text: String, val edits: List<TextEdit>) : Result

        /** [rule] names the broken rule for the daemon log — never any of the text. */
        data class Rejected(val rule: String) : Result
    }

    fun check(original: String, edits: List<TextEdit>): Result {
        if (edits.isEmpty()) return Result.Accepted(original, emptyList())
        if (edits.size > MAX_EDITS) return Result.Rejected("count")
        val placed = ArrayList<Placed>(edits.size)
        var fromTotal = 0L
        var longestFrom = 0
        for (e in edits) {
            if (e.from.isEmpty()) return Result.Rejected("empty_from")
            if (e.from == e.to) return Result.Rejected("unchanged")
            if (e.from.length > MAX_FRAGMENT_CHARS || e.to.length > MAX_FRAGMENT_CHARS) return Result.Rejected("too_long")
            if (!wellFormed(e.from) || !isPrintableFragment(e.to)) return Result.Rejected("control")
            if (e.to.length > 2 * e.from.length + GROWTH_SLACK) return Result.Rejected("growth")
            val at = original.indexOf(e.from)
            if (at < 0) return Result.Rejected("not_found")
            if (original.indexOf(e.from, at + 1) >= 0) return Result.Rejected("not_unique")
            placed += Placed(at, e)
            fromTotal += e.from.length
            if (e.from.length > longestFrom) longestFrom = e.from.length
        }
        if (fromTotal * 100 > original.length.toLong() * MAX_FROM_SHARE_PERCENT) {
            val wordSized = longestFrom <= SHORT_FRAGMENT_CHARS && fromTotal <= SHORT_FROM_CHARS &&
                fromTotal * 100 <= original.length.toLong() * SHORT_FROM_SHARE_PERCENT
            if (!wordSized) return Result.Rejected("share")
        }
        placed.sortBy { it.at }
        for (i in 1 until placed.size) {
            if (placed[i].at < placed[i - 1].end) return Result.Rejected("overlap")
        }
        val out = StringBuilder(original.length + placed.sumOf { it.edit.to.length })
        var cursor = 0
        for (p in placed) {
            out.append(original, cursor, p.at).append(p.edit.to)
            cursor = p.end
        }
        out.append(original, cursor, original.length)
        return Result.Accepted(out.toString(), placed.map { it.edit })
    }

    private class Placed(val at: Int, val edit: TextEdit) {
        val end: Int get() = at + edit.from.length
    }
}

/** No unpaired surrogate: such a fragment could match half of a character in the original. */
private fun wellFormed(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            Character.isHighSurrogate(c) -> {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return false
                i += 2
            }
            Character.isLowSurrogate(c) -> return false
            else -> i++
        }
    }
    return true
}

/**
 * Well-formed, and free of line breaks, control, invisible-format (zero-width, bidirectional override),
 * separator and private-use characters — text that reads exactly as it looks. Used for a refiner's `to`
 * fragments and for the glossary words that go into a refiner's instructions.
 */
internal fun isPrintableFragment(s: String): Boolean {
    if (!wellFormed(s)) return false
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
            Character.PRIVATE_USE, Character.SURROGATE,
            -> return false
        }
        i += Character.charCount(cp)
    }
    return true
}
