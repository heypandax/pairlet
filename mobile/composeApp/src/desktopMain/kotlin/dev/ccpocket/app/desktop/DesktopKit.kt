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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.Tok
import kotlin.math.roundToInt

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

/**
 * 紧凑文本（chip / badge / 状态标签 / 计数气泡）的「垂直居中稳定」文本样式（#293）。
 *
 * 这类小组件全靠 1–4dp 的纵向 padding 撑高，文字在盒子里的落点因此完全由字体自带的
 * ascent/descent/leading 决定。[Dk.ui] / [Dk.mono] 随包的 Inter / JetBrains Mono 只有 latin
 * 子集，中文取不到字形就走系统 fallback——mac 落到苹方/SF，Windows 落到微软雅黑/Segoe UI，
 * 两边度量差一大截。于是在 mac 上调准的 padding 搬到 Windows 就整体偏上或偏下（真实用户反馈：
 * 目录选择器的「正在打开」chip 等多处不居中）。
 *
 * 修法是把行盒的高度从「字体说了算」改成「字号说了算」：显式 [TextStyle.lineHeight]（字号的
 * 1.15 倍取整）定死行盒，[LineHeightStyle.Alignment.Center] 把字形摆到行盒正中。同一字号在两个
 * 平台拿到同一个行盒，chip 的视觉重心就对齐了。**不要逐处加 offset/padding 补偿**——那是修表象。
 *
 * `trim` 必须是 [LineHeightStyle.Trim.None]，这点反直觉：Trim.Both 正是 Compose 的默认值
 * （`LineHeightStyle.Default`），它会把单行的行盒重新收回到字体自身的 ascent+descent，
 * 等于把上面设的 lineHeight 作废。实测（`TightCenterTest`，desktop/skiko、density 2）11sp：
 * 拉丁文行盒 27px、中文（fallback 到苹方）31px——Trim.Both 下两者原样不变，Trim.None 下同为 26px。
 * 也就是说带 Trim.Both 的写法在桌面端是个空操作，修不了 #293。
 *
 * 只设行高相关字段、不设 fontSize，所以调用方照旧写 `fontSize = 11.sp` 参数，两者不打架
 * （Text 的显式参数覆盖 style 同名字段）。
 *
 * 实现已上提到 commonMain（`theme/TightText.kt`）——手机端的额度胶囊踩了同一个坑，而这条规则
 * 与平台无关。这里保留桌面端的名字做一层直通，纯粹为了不动二十来处既有调用点；
 * `TightCenterTest` 照旧钉在这个入口上。
 */
fun tightCenter(fontSize: TextUnit): TextStyle = dev.ccpocket.app.theme.tightCenter(fontSize)

/** A small keycap chip — a mono pill with a hairline border, e.g. ⌘K / ⏎ / ⌘⏎. */
@Composable
fun Key(text: String) {
    Text(
        text,
        color = Tok.muted,
        fontFamily = Dk.mono,
        fontSize = 11.sp,
        style = tightCenter(11.sp),
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
        style = tightCenter(9.5.sp),
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, color.copy(alpha = 0.33f), RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}
