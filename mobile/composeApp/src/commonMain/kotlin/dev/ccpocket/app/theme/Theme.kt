package dev.ccpocket.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Calm Terminal Companion tokens. */
object Tok {
    val base = Color(0xFF0E0F11)
    val surface = Color(0xFF16181B)
    val raised = Color(0xFF1E2125)
    val hair = Color(0xFF2A2E33)
    val tx = Color(0xFFECEDEE)
    val tx2 = Color(0xFF9BA1A6)
    val muted = Color(0xFF6B7177)
    val accent = Color(0xFFD97757)
    val accentPressed = Color(0xFFC4633F) // primary pressed/active (desktop hover-press)
    val codex = Color(0xFF3FB5AC) // Codex agent identity (Claude uses accent); calm teal that sits on the dark palette
    val ok = Color(0xFF4FB477)
    val warn = Color(0xFFE0A93B)
    val danger = Color(0xFFE5604D)
    val info = Color(0xFF5B9BD5) // neutral info / links / "plan" mode
}

/** Chat text scale (issue #8), provided once at the app root from PocketRepository.fontScale; 1.0 = design default. */
val LocalFontScale = staticCompositionLocalOf { 1f }

@Composable
fun PocketTheme(fontScale: Float = 1f, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tok.accent,
            onPrimary = Color.White,
            background = Tok.base,
            onBackground = Tok.tx,
            surface = Tok.surface,
            onSurface = Tok.tx,
            surfaceVariant = Tok.raised,
            onSurfaceVariant = Tok.tx2,
            outline = Tok.hair,
            error = Tok.danger,
        ),
        content = { CompositionLocalProvider(LocalFontScale provides fontScale, content = content) },
    )
}
