package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.Tok

/**
 * Desktop design kit — the atoms shared by the two-pane shell, ported from `desktop-core.jsx`.
 *
 * Color tokens come straight from [Tok] (the design's `T` palette is byte-for-byte the app's), so there is
 * no second source of truth. Typography is the design's own — Inter for UI, JetBrains Mono for paths / ids /
 * code — bundled as classpath resources under `desktopMain/resources/font/` (latin subset, OFL) so the type
 * is pixel-identical across macOS / Windows / Linux instead of falling back to each OS's default sans/mono.
 */
object Dk {
    val ui = FontFamily(
        Font("font/Inter-Regular.ttf", FontWeight.Normal),
        Font("font/Inter-Medium.ttf", FontWeight.Medium),
        Font("font/Inter-SemiBold.ttf", FontWeight.SemiBold),
        Font("font/Inter-Bold.ttf", FontWeight.Bold),
    )
    val mono = FontFamily(
        Font("font/JetBrainsMono-Regular.ttf", FontWeight.Normal),
        Font("font/JetBrainsMono-Medium.ttf", FontWeight.Medium),
        Font("font/JetBrainsMono-SemiBold.ttf", FontWeight.SemiBold),
    )
    val backdrop = Color(0xFF08090A)      // the page/window backdrop, a touch under base
    val sidebarWidth = 300.dp
    val maxStreamWidth = 760.dp           // chat message column cap for readability
}

/** A small keycap chip — a mono pill with a hairline border, e.g. ⌘K / ⏎ / ⌘⏎. */
@Composable
fun Key(text: String) {
    Text(
        text,
        color = Tok.muted,
        fontFamily = Dk.mono,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Tok.base)
            .border(1.dp, Tok.hair, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** A status dot; [pulse] is wired by the caller via [PulseDot] so this stays allocation-free where static. */
@Composable
fun Dot(color: Color, size: Dp = 7.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(999.dp)).background(color))
}

/** SECTION LABEL — the 11sp uppercase muted group header (Projects / Sessions / Pending approvals …). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            color = Tok.muted,
            fontFamily = Dk.ui,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(1.dp)) // keep row baseline stable when trailing is absent
        trailing?.invoke()
    }
}

/**
 * Background that lifts to [hover] (raised surface) while the pointer is over the element — the desktop hover
 * affordance absent on mobile. Apply AFTER `.clip(shape)` so the fill is clipped to the row's corners.
 */
@Composable
fun Modifier.hoverFill(
    shape: Shape = RoundedCornerShape(0.dp),
    base: Color = Color.Transparent,
    hover: Color = Tok.raised,
): Modifier {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    return this.hoverable(src).background(if (hovered) hover else base, shape)
}

/**
 * A "pick one" list row background: a solid [Tok.surface] fill when [selected], else the hover lift. The shared
 * idiom behind the command-palette rows and the settings rail (clips to [radius] so the fill follows the corners).
 */
@Composable
fun Modifier.selectableRow(selected: Boolean, radius: Dp = 8.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape).then(if (selected) Modifier.background(Tok.surface) else Modifier.hoverFill(shape))
}

/** A dashed rounded border — marks "add" affordances (add-computer rows) apart from solid cards. */
fun Modifier.dashedBorder(color: Color, radius: Dp = 11.dp, stroke: Dp = 1.dp): Modifier = drawBehind {
    val r = radius.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = stroke.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)),
    )
}

/** A pill-shaped tinted badge (used for the "history" project marker and inline counts). */
@Composable
fun OutlinePill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        fontFamily = Dk.mono,
        fontSize = 9.5.sp,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, color.copy(alpha = 0.33f), RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}
