package dev.ccpocket.app.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 紧凑文本（chip / badge / 状态标签 / 计数气泡）的「垂直居中稳定」文本样式（#293）。
 *
 * 这类小组件全靠 1–4dp 的纵向 padding 撑高，文字在盒子里的落点因此完全由字体自带的
 * ascent / descent / leading 决定——而这三个值在不同平台、不同 fallback 字体下差别很大：
 * 桌面端是中文 fallback（mac 苹方 / Windows 微软雅黑）的度量差，手机端则是 `FontFamily.Monospace`
 * 各自解析到 Roboto Mono / SF Mono 的度量差。于是在一处调准的 padding 换个平台就整体偏上或偏下。
 *
 * 修法是把行盒的高度从「字体说了算」改成「字号说了算」：显式 [TextStyle.lineHeight]（字号的
 * 1.15 倍取整）定死行盒，[LineHeightStyle.Alignment.Center] 把字形摆到行盒正中。同一字号在所有
 * 平台拿到同一个行盒，chip 的视觉重心就对齐了。**不要逐处加 offset / padding 补偿**——那是修表象。
 *
 * `trim` 必须是 [LineHeightStyle.Trim.None]，这点反直觉：Trim.Both 正是 Compose 的默认值
 * （`LineHeightStyle.Default`），它会把单行的行盒重新收回到字体自身的 ascent+descent，
 * 等于把上面设的 lineHeight 作废。实测（`TightCenterTest`，desktop/skiko、density 2）11sp：
 * 拉丁文行盒 27px、中文（fallback 到苹方）31px——Trim.Both 下两者原样不变，Trim.None 下同为 26px。
 * 也就是说带 Trim.Both 的写法是个空操作，修不了 #293。
 *
 * 只设行高相关字段、不设 fontSize，所以调用方照旧写 `fontSize = 11.sp` 参数，两者不打架
 * （Text 的显式参数覆盖 style 同名字段）。
 *
 * originally desktop-only（`desktop/DesktopKit.kt`）。手机端的额度胶囊踩了同一个坑之后提到
 * commonMain：这条规则与平台无关，两端各留一份就是留了两次跑偏的机会。
 */
fun tightCenter(fontSize: TextUnit): TextStyle = TextStyle(
    lineHeight = (fontSize.value * 1.15f).roundToInt().sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)
