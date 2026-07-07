package dev.ccpocket.app.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Guards the appearance defaults + palette integrity for the light-mode work (issue #63). Pure — it
 *  never touches the global Tok.current, so it can't perturb the screenshot/UI tests that read it. */
class ThemeTest {
    @Test
    fun themeMode_defaults_to_dark_and_parses_known_names() {
        // the app shipped dark-only, so an absent/garbage pref must stay DARK — no surprise flip on update
        assertEquals(ThemeMode.DARK, ThemeMode.from(null))
        assertEquals(ThemeMode.DARK, ThemeMode.from("nonsense"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.from("SYSTEM"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.from("LIGHT"))
        assertEquals(ThemeMode.DARK, ThemeMode.from("DARK"))
    }

    @Test
    fun palettes_carry_their_flag_and_genuinely_differ() {
        assertTrue(DarkPalette.dark)
        assertFalse(LightPalette.dark)
        // light isn't dark reused — background and text really invert
        assertTrue(LightPalette.base != DarkPalette.base, "light base must differ from dark")
        assertTrue(LightPalette.tx != DarkPalette.tx, "light text must differ from dark")
        // dark palette keeps its exact historical values (no regression)
        assertEquals(Color(0xFF0E0F11), DarkPalette.base)
        assertEquals(Color(0xFFECEDEE), DarkPalette.tx)
    }
}
