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
 *  - a fragment with an unpaired surrogate is refused: replacing it could split a character in two;
 *  - all `to`s together may outgrow their `from`s by at most [TOTAL_GROWTH_SLACK] + 1/5 of the original
 *    (`total_growth`). The per-edit bound alone let twelve one-letter edits each add five letters.
 *
 * The rules above void the whole list. What survives them is sorted edit by edit into two tiers, because the phone
 * sends a corrected text WITHOUT a further look when [Result.Accepted.autoSend] is set (review §5, §11):
 *  - hard blocks DROP the edit (it is not applied; the rest still are) and clear `autoSend`. An edit may not blank
 *    its fragment (`empty_to`), nor change the digits and Chinese numerals (`numeral`), the count of negation markers
 *    — 不/别/没/无/… and the English no/not/never/…/n't (`negation`), or the shell characters `/ \ ~ $ | ; & > <` and
 *    the backtick (`command`) it carries. Edits are unique and disjoint, so leaving one out cannot disturb another;
 *  - every applied edit must be allow-listed for `autoSend` to stay set: a case/space-only change, a `to` that is a
 *    glossary term verbatim, or a near-homophone — after the common prefix and suffix are stripped, two Han cores of
 *    equal length whose characters pairwise share a reading under [Pinyin.near]. An edit that is none of these is
 *    still applied; the user sees it in the composer;
 *  - an applied edit whose `to` brings in a destructive word its `from` lacks (删, 清空, 重置, delete, force, rm, …)
 *    clears `autoSend` too, even when allow-listed: a correct 山 → 删 is the user's to confirm.
 *
 * Known and accepted consequences of the allow list — tests pin them so nobody "fixes" them by accident:
 *  - 用功 → 用户 is mis-heard but not a near-homophone: it is applied without `autoSend`;
 *  - 看下 → 看一下 adds a numeral and is dropped;
 *  - the glossary rule only looks at `to`, so edit → effort passes when `effort` is a glossary term even where the
 *    speaker meant "edit". The harm is bounded: a short glossary noun swapped in for a word of similar size.
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
    /** `total_growth`: the whole list may add at most this many characters plus a fifth of the original. */
    const val TOTAL_GROWTH_SLACK = 12

    sealed interface Result {
        /**
         * [text] is the original with [edits] applied — the edits that survived the hard blocks, in the order they
         * occur in the original. [autoSend] is true only when no edit was dropped and every applied one is
         * allow-listed and free of destructive words; otherwise the phone puts [text] in the composer.
         */
        data class Accepted(val text: String, val edits: List<TextEdit>, val autoSend: Boolean) : Result

        /** [rule] names the broken rule for the daemon log — never any of the text. */
        data class Rejected(val rule: String) : Result
    }

    fun check(original: String, edits: List<TextEdit>, glossary: List<String>): Result {
        if (edits.isEmpty()) return Result.Accepted(original, emptyList(), autoSend = true)
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
        val growth = placed.sumOf { it.edit.to.length.toLong() - it.edit.from.length }
        if (growth > TOTAL_GROWTH_SLACK + original.length / 5) return Result.Rejected("total_growth")
        var autoSend = true
        val applied = placed.filter { p ->
            if (hardBlock(p.edit) != null) {
                autoSend = false
                return@filter false
            }
            if (!allowListed(p.edit, glossary) || destructive(p.edit)) autoSend = false
            true
        }
        val out = StringBuilder(original.length + applied.sumOf { it.edit.to.length })
        var cursor = 0
        for (p in applied) {
            out.append(original, cursor, p.at).append(p.edit.to)
            cursor = p.end
        }
        out.append(original, cursor, original.length)
        return Result.Accepted(out.toString(), applied.map { it.edit }, autoSend)
    }

    /** The hard block [edit] breaks — `empty_to`, `numeral`, `negation` or `command`, in that order — or null. */
    internal fun hardBlock(edit: TextEdit): String? = when {
        edit.to.isBlank() -> "empty_to"
        numerals(edit.from) != numerals(edit.to) -> "numeral"
        negations(edit.from) != negations(edit.to) -> "negation"
        commandChars(edit.from) != commandChars(edit.to) -> "command"
        else -> null
    }

    /** Case/space-only, a glossary term verbatim (case-sensitive), or a near-homophone of Han characters. */
    internal fun allowListed(edit: TextEdit, glossary: List<String>): Boolean =
        edit.from.filterNot(Char::isWhitespace).equals(edit.to.filterNot(Char::isWhitespace), ignoreCase = true) ||
            edit.to.trim() in glossary ||
            nearHomophone(edit.from, edit.to)

    /** [edit]'s `to` carries a destructive word that its `from` does not. */
    internal fun destructive(edit: TextEdit): Boolean {
        if (DESTRUCTIVE_HAN.any { it in edit.to && it !in edit.from }) return true
        val before = latinWords(edit.from)
        return latinWords(edit.to).any { it in DESTRUCTIVE_LATIN && it !in before }
    }

    private const val NUMERAL_HAN = "零〇一二两三四五六七八九十百千万亿"
    private const val NEGATION_HAN = "不别沒没无無勿非未否莫"
    private const val COMMAND_CHARS = "/\\~$|;&><`"
    private val NEGATION_LATIN = setOf("no", "not", "never", "none", "without", "cannot", "dont")
    private val CONTRACTED_NOT = Regex("n['’]t", RegexOption.IGNORE_CASE)
    private val LATIN_WORD = Regex("[A-Za-z]+")
    private val DESTRUCTIVE_HAN = listOf("删", "除", "清空", "销毁", "覆盖", "重置", "格式化", "强推", "回滚", "卸载", "关闭", "停止", "杀")
    private val DESTRUCTIVE_LATIN =
        setOf("delete", "remove", "drop", "reset", "force", "rm", "wipe", "kill", "purge", "destroy", "overwrite")

    private fun numerals(s: String): String = s.filter { Character.isDigit(it) || it in NUMERAL_HAN }

    private fun commandChars(s: String): String = s.filter { it in COMMAND_CHARS }

    private fun latinWords(s: String): List<String> = LATIN_WORD.findAll(s).map { it.value.lowercase() }.toList()

    private fun negations(s: String): Int =
        s.count { it in NEGATION_HAN } + latinWords(s).count { it in NEGATION_LATIN } + CONTRACTED_NOT.findAll(s).count()

    /** After the common prefix and suffix go, two non-empty Han cores of one length, pairwise [Pinyin.near]. */
    private fun nearHomophone(from: String, to: String): Boolean {
        val a = from.codePoints().toArray()
        val b = to.codePoints().toArray()
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--
            endB--
        }
        if (endA == start || endA - start != endB - start) return false
        for (i in 0 until endA - start) {
            val x = a[start + i]
            val y = b[start + i]
            if (!isHan(x) || !isHan(y) || !Pinyin.near(x, y)) return false
        }
        return true
    }

    private fun isHan(cp: Int): Boolean = Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN

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
