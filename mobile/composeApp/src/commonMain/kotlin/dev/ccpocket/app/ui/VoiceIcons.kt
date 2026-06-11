package dev.ccpocket.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Voice-input icons, path data transcribed from the claude-design handoff `voice-core.jsx`.
 * Stroke-based line icons (round caps/joins), single color — state is a tint swap at the
 * call site, same convention as [AttachImageIcon].
 */

private fun ImageVector.Builder.stroked(width: Float = 1.5f, block: PathBuilder.() -> Unit) =
    path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )

private fun ImageVector.Builder.filled(block: PathBuilder.() -> Unit) =
    path(fill = SolidColor(Color.White), pathBuilder = block)

private fun builder(name: String, vp: Float) =
    ImageVector.Builder(name = name, defaultWidth = vp.dp, defaultHeight = vp.dp, viewportWidth = vp, viewportHeight = vp)

/** Microphone: capsule body + bottom-half pickup arc + stand. */
val MicIcon: ImageVector by lazy {
    builder("Mic", 24f).apply {
        // capsule x9..15, y3..14, r3
        stroked {
            moveTo(9f, 6f)
            arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 15f, 6f)
            verticalLineTo(11f)
            arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 9f, 11f)
            close()
        }
        // pickup arc: M5.5 11.5 a6.5 6.5 0 0 0 13 0
        stroked {
            moveTo(5.5f, 11.5f)
            arcToRelative(6.5f, 6.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 13f, 0f)
        }
        // stand + base
        stroked { moveTo(12f, 18f); verticalLineTo(21f) }
        stroked { moveTo(9f, 21f); horizontalLineTo(15f) }
    }.build()
}

/** Up arrow for the filled send circle (stroke 2.1 in an 18-viewport). */
val SendArrowIcon: ImageVector by lazy {
    builder("SendArrow", 18f).apply {
        stroked(2.1f) { moveTo(9f, 14.5f); verticalLineTo(4f) }
        stroked(2.1f) { moveTo(9f, 4f); lineToRelative(-4.2f, 4.2f) }
        stroked(2.1f) { moveTo(9f, 4f); lineToRelative(4.2f, 4.2f) }
    }.build()
}

/** Done check for the recording bar (stroke 2.2 in a 20-viewport). */
val CheckIcon: ImageVector by lazy {
    builder("Check", 20f).apply {
        stroked(2.2f) { moveTo(4f, 10.5f); lineToRelative(4f, 4f); lineToRelative(8f, -9f) }
    }.build()
}

/** Cancel ✕ (stroke 1.8 in an 18-viewport). */
val XSmallIcon: ImageVector by lazy {
    builder("XSmall", 18f).apply {
        stroked(1.8f) { moveTo(4.5f, 4.5f); lineToRelative(9f, 9f) }
        stroked(1.8f) { moveTo(13.5f, 4.5f); lineToRelative(-9f, 9f) }
    }.build()
}

/** Shield with a mic inside — the S6 permission-sheet tile glyph (26-viewport). */
val ShieldMicIcon: ImageVector by lazy {
    builder("ShieldMic", 26f).apply {
        stroked {
            moveTo(13f, 2.5f)
            lineToRelative(8f, 3f)
            verticalLineToRelative(6f)
            curveToRelative(0f, 5.2f, -3.8f, 8.6f, -8f, 10f)
            curveToRelative(-4.2f, -1.4f, -8f, -4.8f, -8f, -10f)
            verticalLineToRelative(-6f)
            lineToRelative(8f, -3f)
            close()
        }
        // mic capsule x11..15, y8.5..14.9, r2
        stroked {
            moveTo(11f, 10.5f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 15f, 10.5f)
            verticalLineTo(12.9f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 11f, 12.9f)
            close()
        }
        stroked {
            moveTo(8.7f, 13.2f)
            arcToRelative(4.3f, 4.3f, 0f, isMoreThanHalf = false, isPositiveArc = false, 8.6f, 0f)
        }
    }.build()
}

/** Warning triangle for the S5 error chip (stroke 1.4 + filled dot, 18-viewport). */
val WarnTriIcon: ImageVector by lazy {
    builder("WarnTri", 18f).apply {
        stroked(1.4f) {
            moveTo(9f, 2.6f)
            lineToRelative(6.6f, 12f)
            horizontalLineTo(2.4f)
            lineTo(9f, 2.6f)
            close()
        }
        stroked(1.4f) { moveTo(9f, 7.2f); verticalLineToRelative(3.2f) }
        filled {
            moveTo(8.1f, 12.6f)
            arcTo(0.9f, 0.9f, 0f, isMoreThanHalf = false, isPositiveArc = true, 9.9f, 12.6f)
            arcTo(0.9f, 0.9f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8.1f, 12.6f)
            close()
        }
    }.build()
}
