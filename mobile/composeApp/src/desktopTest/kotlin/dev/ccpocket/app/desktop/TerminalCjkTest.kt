package dev.ccpocket.app.desktop

import java.awt.Font
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the three UTF-8 links of the embedded terminal (issue #283): the Windows console code page, the
 * login shell's locale, and the CJK glyph fallback.
 */
class TerminalCjkTest {

    // ── the Windows code page arm ──

    @Test
    fun windows_shell_switches_the_console_to_utf8() {
        // without this the console decodes claude/git/node's UTF-8 output as GBK on a Chinese Windows
        assertContentEquals(
            arrayOf("""C:\Windows\system32\cmd.exe""", "/K", "chcp 65001>nul"),
            shellCommandFor("windows 11", """C:\Windows\system32\cmd.exe""", null),
        )
    }

    @Test
    fun windows_shell_falls_back_to_cmd_when_comspec_is_useless() {
        assertContentEquals(arrayOf("cmd.exe", "/K", "chcp 65001>nul"), shellCommandFor("windows 10", null, null))
        assertContentEquals(arrayOf("cmd.exe", "/K", "chcp 65001>nul"), shellCommandFor("windows 10", "  ", null))
    }

    @Test
    fun unix_shells_are_untouched_login_shells() {
        assertContentEquals(arrayOf("/bin/fish", "-l"), shellCommandFor("mac os x", null, "/bin/fish"))
        assertContentEquals(arrayOf("/bin/zsh", "-l"), shellCommandFor("mac os x", null, null))
        assertContentEquals(arrayOf("/bin/bash", "-l"), shellCommandFor("linux", null, null))
    }

    // ── the locale arm ──

    private val locales = setOf("C", "POSIX", "C.UTF-8", "en_US.UTF-8", "zh_CN.UTF-8", "zh_CN.GBK")

    @Test
    fun empty_environment_gets_the_users_own_utf8_locale() {
        assertEquals("zh_CN.UTF-8", utf8LocaleFor(emptyMap(), locales, Locale.CHINA))
    }

    @Test
    fun an_existing_locale_is_never_overridden() {
        // even a non-UTF-8 one: the user picked it, and clobbering it would be its own bug report
        assertNull(utf8LocaleFor(mapOf("LANG" to "zh_CN.GBK"), locales, Locale.CHINA))
        assertNull(utf8LocaleFor(mapOf("LC_ALL" to "en_US.UTF-8"), locales, Locale.CHINA))
        assertNull(utf8LocaleFor(mapOf("LC_CTYPE" to "UTF-8"), locales, Locale.CHINA))
    }

    @Test
    fun blank_locale_vars_count_as_absent() {
        assertEquals("zh_CN.UTF-8", utf8LocaleFor(mapOf("LANG" to "", "LC_ALL" to "  "), locales, Locale.CHINA))
    }

    @Test
    fun falls_through_to_portable_locales_then_gives_up() {
        // the user's own locale isn't generated on this box → C.UTF-8 → en_US.UTF-8
        assertEquals("C.UTF-8", utf8LocaleFor(emptyMap(), locales - "zh_CN.UTF-8", Locale.CHINA))
        assertEquals("en_US.UTF-8", utf8LocaleFor(emptyMap(), setOf("C", "en_US.UTF-8"), Locale.CHINA))
        // naming a locale the system never generated makes every libc program warn — rather set nothing
        assertNull(utf8LocaleFor(emptyMap(), setOf("C", "POSIX"), Locale.CHINA))
    }

    @Test
    fun a_country_less_locale_does_not_produce_a_bogus_name() {
        assertEquals("C.UTF-8", utf8LocaleFor(emptyMap(), locales, Locale.forLanguageTag("zh")))
    }

    // ── the glyph fallback arm ──

    /** The dock's actual font, loaded the same way [CcTermSettings] loads it. */
    private fun jetBrainsMono(): Font =
        javaClass.classLoader.getResourceAsStream("font/JetBrainsMono-Regular.ttf")!!
            .use { Font.createFont(Font.TRUETYPE_FONT, it) }.deriveFont(13f)

    @Test
    fun the_shipped_terminal_font_really_cannot_draw_chinese() {
        // this is the mac root cause in one assert — if it ever goes green-by-coverage the fallback is moot
        val font = jetBrainsMono()
        assertFalse(font.canDisplay('中'), "JetBrains Mono gained CJK coverage — revisit the fallback chain")
        assertFalse(font.canDisplay('，'), "not even fullwidth punctuation")
    }

    @Test
    fun ascii_short_circuits_without_asking_the_font() {
        val buf = "hello, world".toCharArray()
        assertTrue(primaryCanDraw(jetBrainsMono(), buf, 0, buf.size))
    }

    @Test
    fun chinese_is_reported_as_undrawable_by_the_primary_font() {
        val buf = "中".toCharArray()
        assertFalse(primaryCanDraw(jetBrainsMono(), buf, 0, buf.size))
    }

    @Test
    fun only_the_cluster_in_range_is_considered() {
        // JediTerm hands us one grapheme cluster inside a larger run; chars outside [start, end) must not count
        val buf = "ab中cd".toCharArray()
        assertTrue(primaryCanDraw(jetBrainsMono(), buf, 0, 2), "the ASCII prefix alone is drawable")
        assertFalse(primaryCanDraw(jetBrainsMono(), buf, 2, 3), "the CJK cluster alone is not")
        assertTrue(primaryCanDraw(jetBrainsMono(), buf, 3, 5), "the ASCII suffix alone is drawable")
    }

    @Test
    fun the_chain_keeps_only_installed_families_and_always_ends_in_a_composite() {
        val chain = cjkFontChain(setOf("PingFang SC", "SimSun"), listOf("PingFang SC", "Nope MS", "SimSun"))
        // "Nope MS" must be dropped: Font("Nope MS") silently becomes the Dialog composite, whose
        // canDisplay('中') is true — an unfiltered chain would "succeed" into a proportional font
        assertEquals(listOf("PingFang SC", "SimSun", Font.MONOSPACED), chain)
    }

    @Test
    fun the_chain_survives_a_machine_with_none_of_the_named_families() {
        assertEquals(listOf(Font.MONOSPACED), cjkFontChain(emptySet(), listOf("PingFang SC", "SimSun")))
    }

    @Test
    fun the_fallback_matches_the_primarys_size_and_style() {
        val buf = "中".toCharArray()
        val bold = jetBrainsMono().deriveFont(Font.BOLD).deriveFont(17f)
        val picked = CjkFallback.pick(buf, 0, 1, bold)
        // headless CI without a single CJK font legitimately has nothing to pick; only assert when it does
        if (picked != null) {
            assertEquals(17f, picked.size2D)
            assertEquals(Font.BOLD, picked.style)
            assertTrue(picked.canDisplay('中'))
        }
    }
}
