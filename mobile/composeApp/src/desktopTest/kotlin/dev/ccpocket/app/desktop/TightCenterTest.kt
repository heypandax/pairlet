package dev.ccpocket.app.desktop

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [tightCenter] 是 #293 的根因修法：Windows 上中文走字体 fallback，度量与 mac 差一大截，
 * 紧凑 chip 里的文字位置全凭字体说了算，于是 mac 调好的 padding 到 Windows 就偏。
 *
 * 这里钉三件事：
 * 1. 样式字段本身（含反直觉的 `Trim.None`，理由见断言旁注释）；
 * 2. **真正的载荷**——同一字号下，行盒高度不再随字体族 / 中英文变化；
 * 3. mac 端不回退——行盒只会略收、不会变高，也不会窄到裁掉字形。
 *
 * 阈值都是实测值（desktop/skiko，density 2），别凭感觉放宽：放宽等于把 #293 又放回去。
 */
class TightCenterTest {
    private val sizes = listOf(9f, 9.5f, 10f, 10.5f, 11f, 11.5f, 12f, 12.5f)
    private val measurer by lazy { TextMeasurer(createFontFamilyResolver(), Density(2f), LayoutDirection.Ltr) }

    /** 桌面端 chip 会碰到的四种度量来源：两个随包字体族 × 拉丁文（走本体）/ 中文（走系统 fallback）。 */
    private val cases: List<Triple<String, FontFamily, String>> = listOf(
        Triple("ui/latin", Dk.ui, "Restart"),
        Triple("ui/cjk", Dk.ui, "正在打开"),
        Triple("mono/latin", Dk.mono, "Restart"),
        Triple("mono/cjk", Dk.mono, "正在打开"),
    )

    @Test
    fun `样式把行盒交给字号而不是字体`() {
        for (s in sizes) {
            val st = tightCenter(s.sp)
            val lhs = st.lineHeightStyle
            assertTrue(lhs != null, "${s}sp: lineHeightStyle 必须设置，否则字形位置仍由字体度量决定")
            assertEquals(LineHeightStyle.Alignment.Center, lhs.alignment, "${s}sp: 字形必须在行盒内居中")
            // 反直觉但实测如此：Trim.Both 正是 Compose 的默认值，它会把单行行盒收回字体自身的
            // ascent+descent，等于把 lineHeight 作废——那样这个工具就成了空操作，修不了 #293。
            assertEquals(LineHeightStyle.Trim.None, lhs.trim, "${s}sp: trim 必须是 None，Trim.Both 会让 lineHeight 失效")
            assertTrue(st.lineHeight != TextUnit.Unspecified, "${s}sp: lineHeight 必须显式给出")
            val ratio = st.lineHeight.value / s
            assertTrue(ratio in 1.09f..1.21f, "${s}sp: 行高倍率 $ratio 越界（应在 1.1–1.2 之间取整）")
            assertEquals(st.lineHeight.value.toInt().toFloat(), st.lineHeight.value, "${s}sp: 行高应取整 sp")
        }
    }

    /** #293 的载荷：同字号下，四种度量来源必须拿到同一个行盒高度。 */
    @Test
    fun `同一字号下行盒高度不随字体族与中英文变化`() {
        for (s in sizes) {
            val heights = cases.associate { (label, fam, text) ->
                label to measurer.measure(text, TextStyle(fontSize = s.sp, fontFamily = fam).merge(tightCenter(s.sp))).size.height
            }
            assertTrue(
                heights.values.distinct().size == 1,
                "${s}sp: 行盒高度仍随字体变化 $heights —— fallback 度量没被隔离掉，chip 在 Windows 上还会偏",
            )
        }
    }

    /** mac 端不回退：削掉字体 leading 后行盒只会略收；变高会撑大 chip，收太狠会裁字形。 */
    @Test
    fun `mac 端行盒只收不涨且不裁字形`() {
        for (s in sizes) {
            for ((label, fam, text) in cases) {
                val plain = TextStyle(fontSize = s.sp, fontFamily = fam)
                val before = measurer.measure(text, plain).size.height
                val after = measurer.measure(text, plain.merge(tightCenter(s.sp))).size.height
                assertTrue(after <= before, "${s}sp/$label: 行盒从 $before 涨到 $after，chip 会被撑大")
                // 行盒至少要装得下一个 em（density 2），否则中文字形会被裁
                assertTrue(after >= s * 2, "${s}sp/$label: 行盒只有 $after px，装不下一个 em，字形会被裁")
            }
        }
    }
}
