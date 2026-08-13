package dev.ccpocket.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalTextToolbar
import dev.ccpocket.app.rememberPlatformTextToolbar

/** The appearance setting (issue #63). SYSTEM follows the OS light/dark, the other two force it.
 *  Absent/garbage → DARK: the app shipped dark-only, so existing users stay dark until they opt into
 *  light or system (dark must not regress). Users pick SYSTEM/LIGHT explicitly in Settings ▸ Appearance. */
enum class ThemeMode { SYSTEM, LIGHT, DARK;
    companion object {
        fun from(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: DARK
    }
}

/** Global accent source (issue #204). POCKET keeps the terracotta brand accent (default — existing users see
 *  no change); CODEX repaints every `Tok.accent` slot with the project's Codex teal for a consistent
 *  Codex-first skin. Only the accent hue moves — success/warning/danger semantics stay put.
 *  Absent/garbage → POCKET so nobody's install silently recolors. */
enum class AccentTheme { POCKET, CODEX;
    companion object {
        fun from(name: String?): AccentTheme = entries.firstOrNull { it.name == name } ?: POCKET
    }
}

/** A full semantic color set — one per theme. Reads flow through [Tok], which points at the active one. */
data class Palette(
    val base: Color,
    val surface: Color,
    val raised: Color,
    val hair: Color,
    val tx: Color,
    val tx2: Color,
    val muted: Color,
    val accent: Color,
    val accentPressed: Color,
    val codex: Color,
    val codexPressed: Color,
    val opencode: Color,
    val kimi: Color,
    val zcode: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    val info: Color,
    val dark: Boolean,
)

/** Calm Terminal Companion — the original dark palette (unchanged values, so dark never regresses). */
val DarkPalette = Palette(
    base = Color(0xFF0E0F11),
    surface = Color(0xFF16181B),
    raised = Color(0xFF1E2125),
    hair = Color(0xFF2A2E33),
    tx = Color(0xFFECEDEE),
    tx2 = Color(0xFF9BA1A6),
    muted = Color(0xFF6B7177),
    accent = Color(0xFFD97757),
    accentPressed = Color(0xFFC4633F), // primary pressed/active (desktop hover-press)
    codex = Color(0xFF3FB5AC),         // Codex agent identity — calm teal that sits on the dark palette
    codexPressed = Color(0xFF339B93),  // Codex accent pressed/active (issue #204 — parity with accentPressed)
    opencode = Color(0xFF9B72CF),      // OpenCode agent identity — soft purple
    kimi = Color(0xFF4D8DFF),          // Kimi Code agent identity — Moonshot blue, distinct from teal/purple
    zcode = Color(0xFFE07A9D),         // ZCode agent identity — restrained rose, distinct from the existing four
    ok = Color(0xFF4FB477),
    warn = Color(0xFFE0A93B),
    danger = Color(0xFFE5604D),
    info = Color(0xFF5B9BD5),           // neutral info / links / "plan" mode
    dark = true,
)

/** Light variant (issue #63): warm off-white surfaces with the same terracotta identity, each hue
 *  darkened enough to stay legible on light backgrounds (accents double as text/borders in places). */
val LightPalette = Palette(
    base = Color(0xFFFAF9F7),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFF1EFEB),
    hair = Color(0xFFE4E1DB),
    tx = Color(0xFF1C1D1F),
    tx2 = Color(0xFF5B6066),
    muted = Color(0xFF878C92),
    accent = Color(0xFFC15F3C),
    accentPressed = Color(0xFFA94E30),
    codex = Color(0xFF1C8B82),
    codexPressed = Color(0xFF16706A),  // Codex accent pressed/active (issue #204)
    opencode = Color(0xFF7B52AB),      // OpenCode agent identity — deeper purple for light mode
    kimi = Color(0xFF2E6FD6),          // Kimi Code agent identity — deeper Moonshot blue for light mode
    zcode = Color(0xFFB4496E),         // ZCode agent identity — deeper rose for light-mode contrast
    ok = Color(0xFF2E9E5B),
    warn = Color(0xFFB07D1C),
    danger = Color(0xFFC53D2B),
    info = Color(0xFF3B7DC4),
    dark = false,
)

/**
 * Calm Terminal Companion tokens. Backed by a reactive [current] palette so switching light/dark
 * recomposes every reader — the ~1200 `Tok.base` / `Tok.tx` / … call sites stay untouched. Getters
 * (not cached vals) so derived objects like DiffTok re-read the live palette too.
 */
object Tok {
    /** The active palette. [PocketTheme] points this at light/dark; equal-value writes are no-ops. */
    var current by mutableStateOf(DarkPalette)
        internal set

    /** Which hue backs the global accent slots (issue #204). [PocketTheme] points this at the persisted
     *  choice; POCKET (default) reads the terracotta accent, CODEX rewrites accent/accentPressed to the
     *  Codex teal — every `Tok.accent` reader recomposes on switch, so no call site changes. */
    var accentTheme by mutableStateOf(AccentTheme.POCKET)
        internal set

    val base: Color get() = current.base
    val surface: Color get() = current.surface
    val raised: Color get() = current.raised
    val hair: Color get() = current.hair
    val tx: Color get() = current.tx
    val tx2: Color get() = current.tx2
    val muted: Color get() = current.muted
    val accent: Color get() = when (accentTheme) {
        AccentTheme.POCKET -> current.accent
        AccentTheme.CODEX -> current.codex
    }
    val accentPressed: Color get() = when (accentTheme) {
        AccentTheme.POCKET -> current.accentPressed
        AccentTheme.CODEX -> current.codexPressed
    }
    /** The literal Codex identity hue — always teal regardless of [accentTheme], so agent tags/badges that
     *  mean "this is Codex" stay teal even when the global accent is terracotta. */
    val codex: Color get() = current.codex
    val opencode: Color get() = current.opencode
    val kimi: Color get() = current.kimi
    val zcode: Color get() = current.zcode
    val ok: Color get() = current.ok
    val warn: Color get() = current.warn
    val danger: Color get() = current.danger
    val info: Color get() = current.info
}

/** Chat text scale (issue #8), provided once at the app root from PocketRepository.fontScale; 1.0 = design default. */
val LocalFontScale = staticCompositionLocalOf { 1f }

/** Resolve a [ThemeMode] to an effective dark/light against the current OS setting. Pure seam (extracted
 *  from [PocketTheme]) so the polarity that also drives the system-bar icon color (issue #117) is unit-tested
 *  without a composition: LIGHT is never dark, DARK is always dark, SYSTEM follows [systemDark]. */
fun ThemeMode.resolvesToDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> systemDark
}

/** Appearance-aware entry point (issue #63): resolves [mode] against the OS so both app roots just pass their
 *  persisted [ThemeMode] — a live system light/dark flip re-themes, LIGHT/DARK force it. Delegates to the
 *  boolean overload, still the default for the ~dozen test/preview `PocketTheme { }` callers. */
@Composable
fun PocketTheme(mode: ThemeMode, accent: AccentTheme = AccentTheme.POCKET, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val dark = mode.resolvesToDark(systemDark = isSystemInDarkTheme())
    // issue #117: align the OS status/navigation-bar FOREGROUND (icon/text) color with the resolved theme so
    // the bars stay legible in light mode too — Android tints them, iOS/desktop no-op. On the mode overload
    // only, so the real app roots drive it while the boolean overload used by tests/previews stays inert.
    SystemBarAppearance(darkTheme = dark)
    PocketTheme(dark = dark, accent = accent, fontScale = fontScale, content = content)
}

@Composable
fun PocketTheme(dark: Boolean = true, accent: AccentTheme = AccentTheme.POCKET, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    // Point the global tokens at the active palette BEFORE children compose, so their first frame reads
    // the right colors (no dark flash for a light-mode launch). The write is idempotent — DarkPalette /
    // LightPalette are stable singletons, so an unchanged theme is a structural no-op and never loops.
    Tok.current = palette
    // accent source (issue #204): set before children compose so the first frame is the right hue; POCKET
    // (default) is a structural no-op, so existing installs never recolor. Tok.accent reads this below.
    Tok.accentTheme = accent
    // one override list over whichever baseline — the unset M3 fields keep each factory's light/dark
    // defaults (identical to spelling both schemes out), so a token add/rename stays in a single place.
    // primary rides Tok.accent so M3 components (buttons/sliders) follow the accent source too.
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = Tok.accent, onPrimary = Color.White,
        background = palette.base, onBackground = palette.tx,
        surface = palette.surface, onSurface = palette.tx,
        surfaceVariant = palette.raised, onSurfaceVariant = palette.tx2,
        outline = palette.hair, error = palette.danger,
    )
    // app-wide text toolbar: identity on android/desktop; iOS gets the select-all reshow fix (TextToolbarFix.kt)
    val textToolbar = rememberPlatformTextToolbar()
    MaterialTheme(
        colorScheme = scheme,
        content = { CompositionLocalProvider(LocalFontScale provides fontScale, LocalTextToolbar provides textToolbar, content = content) },
    )
}
