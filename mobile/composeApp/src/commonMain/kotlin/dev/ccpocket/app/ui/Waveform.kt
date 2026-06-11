package dev.ccpocket.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.theme.Tok
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * The recording bar's level waveform (design: 2.5dp terracotta bars, 2.5dp gaps, tapered ends).
 * [levels] is a rolling window, newest last — rendered so fresh audio enters from the right.
 * [frozen] = S3: bars hold their last shape at flat alpha while the spinner takes over.
 */
@Composable
fun Waveform(levels: List<Float>, frozen: Boolean, modifier: Modifier = Modifier, height: Dp = 28.dp) {
    Canvas(modifier.height(height)) {
        val barW = 2.5.dp.toPx()
        val gap = 2.5.dp.toPx()
        val minH = 3.dp.toPx()
        val bars = ((size.width + gap) / (barW + gap)).toInt().coerceAtLeast(1)
        for (i in 0 until bars) {
            val env = 0.55f + 0.45f * sin(PI * i / bars).toFloat() // taper toward both ends
            val li = levels.size - bars + i
            val level = if (li in levels.indices) levels[li] else 0f
            val h = max(minH, level * env * size.height)
            drawRoundRect(
                color = Tok.accent,
                topLeft = Offset(i * (barW + gap), (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
                alpha = if (frozen) 0.55f else 0.5f + 0.5f * level,
            )
        }
    }
}
