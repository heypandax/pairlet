package dev.ccpocket.daemon.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [Pinyin]: the readings and the "near" folding behind [TranscriptEditValidator]'s homophone rule. */
class PinyinTest {
    private fun cp(c: Char) = c.code

    @Test
    fun normalization_folds_initials_and_finals() {
        assertEquals("zan", Pinyin.normalize("zhang"))
        assertEquals("ci", Pinyin.normalize("chi"))
        assertEquals("si", Pinyin.normalize("shi"))
        assertEquals("nan", Pinyin.normalize("lan"))
        assertEquals("fu", Pinyin.normalize("hu"))
        assertEquals("nian", Pinyin.normalize("liang"))
        assertEquals("fuan", Pinyin.normalize("huang"))
        assertEquals("zen", Pinyin.normalize("zheng"))
        assertEquals("yin", Pinyin.normalize("ying"))
        assertEquals("fong", Pinyin.normalize("hong"), "ong is not folded")
        assertEquals("an", Pinyin.normalize("an"))
    }

    @Test
    fun polyphones_carry_every_reading() {
        assertTrue(Pinyin.readings(cp('行')).containsAll(setOf("xing", "hang")))
        assertTrue(Pinyin.readings(cp('长')).containsAll(setOf("chang", "zhang")))
        // 长 (zhang) ~ 脏 (zang) only through the second reading
        assertTrue(Pinyin.near(cp('长'), cp('脏')))
        assertTrue(Pinyin.near(cp('行'), cp('航')))
    }

    @Test
    fun near_pairs_and_non_pairs() {
        assertTrue(Pinyin.near(cp('在'), cp('再')))
        assertTrue(Pinyin.near(cp('流'), cp('牛'))) // liu ~ niu
        assertTrue(Pinyin.near(cp('湖'), cp('福'))) // hu ~ fu
        assertTrue(Pinyin.near(cp('心'), cp('星'))) // xin ~ xing
        assertFalse(Pinyin.near(cp('功'), cp('户')))
        assertFalse(Pinyin.near(cp('检'), cp('删')))
    }

    @Test
    fun characters_without_a_reading_are_never_near() {
        assertTrue(Pinyin.readings(cp('A')).isEmpty())
        assertFalse(Pinyin.near(cp('A'), cp('A')))
        assertTrue(Pinyin.readings("𠀀".codePointAt(0)).isEmpty())
    }
}
