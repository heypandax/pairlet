package dev.ccpocket.daemon.transcribe

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType
import java.util.concurrent.ConcurrentHashMap

/**
 * Mandarin readings for [TranscriptEditValidator]'s homophone rule, kept behind this object so the validator never
 * sees the library. A reading is toneless lower-case pinyin (`ü` written `v`); a polyphonic character has all of
 * its readings. A code point without a reading (not Han, outside the BMP, or missing from the table) gets an empty
 * set and is therefore never [near] anything.
 *
 * "Near" folds the confusions a recogniser makes most between Mandarin dialects: the initials zh/z, ch/c, sh/s, l/n
 * and h/f, and the finals ang/an, eng/en, ing/in (so iang/ian and uang/uan follow).
 */
internal object Pinyin {
    private val format = HanyuPinyinOutputFormat().apply {
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        caseType = HanyuPinyinCaseType.LOWERCASE
        vCharType = HanyuPinyinVCharType.WITH_V
    }
    private val readingCache = ConcurrentHashMap<Int, Set<String>>()

    /** Every toneless reading of [codePoint]; empty when it has none. */
    fun readings(codePoint: Int): Set<String> = readingCache.getOrPut(codePoint) {
        if (Character.isSupplementaryCodePoint(codePoint)) return@getOrPut emptySet()
        val raw = runCatching { PinyinHelper.toHanyuPinyinStringArray(codePoint.toChar(), format) }.getOrNull()
        raw?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    }

    /** True when some reading of [a] and some reading of [b] are equal after [normalize]. */
    fun near(a: Int, b: Int): Boolean {
        val left = readings(a).mapTo(HashSet(), ::normalize)
        if (left.isEmpty()) return false
        return readings(b).any { normalize(it) in left }
    }

    /** Folds the initial (zh→z, ch→c, sh→s, l→n, h→f) and the final (ang→an, eng→en, ing→in) of one syllable. */
    fun normalize(syllable: String): String {
        val initial = when {
            syllable.startsWith("zh") -> "z" + syllable.substring(2)
            syllable.startsWith("ch") -> "c" + syllable.substring(2)
            syllable.startsWith("sh") -> "s" + syllable.substring(2)
            syllable.startsWith("l") -> "n" + syllable.substring(1)
            syllable.startsWith("h") -> "f" + syllable.substring(1)
            else -> syllable
        }
        return when {
            initial.endsWith("ang") || initial.endsWith("eng") || initial.endsWith("ing") -> initial.dropLast(1)
            else -> initial
        }
    }
}
