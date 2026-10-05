package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.allow
import dev.ccpocket.app.resources.attention_empty
import dev.ccpocket.app.resources.attention_footer
import dev.ccpocket.app.resources.attention_open_session
import dev.ccpocket.app.resources.attention_stats
import dev.ccpocket.app.resources.deny
import dev.ccpocket.app.resources.tray_needs_you
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/**
 * Fleet overlays ("Fleet Desktop" board ⑦): the bell's Attention popover — cross-machine approvals
 * handled without leaving the focused session.
 */

/** OS glyph + mono hostname + status dot — the desktop MachineChip (Dk typography). */
@Composable
fun DkMachineChip(name: String, os: DkOs, online: Boolean = true, fontSize: TextUnit = 11.sp, glyph: androidx.compose.ui.unit.Dp = 12.dp, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(osIcon(os), null, tint = Tok.tx2, modifier = Modifier.size(glyph))
        // lineHeight 单独给不生效（Compose 默认 Trim.Both 会把行盒收回字体度量），走 tightCenter（#293）
        Text(name, color = Tok.tx, fontFamily = Dk.mono, fontSize = fontSize, style = tightCenter(fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (online) PulseDot(Tok.ok, 6.dp) else Dot(Tok.muted, 6.dp)
    }
}

// ── Attention popover (⑦) ────────────────────────────────────────────────────────────────────────

@Composable
fun AttentionPopover(model: DesktopModel) {
    Column(
        Modifier.width(380.dp).shadow(28.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
            .background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(Res.string.tray_needs_you), color = Tok.tx, fontFamily = Dk.ui, fontSize = 13.5.sp, style = tightCenter(13.5.sp), fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            val online = model.machines.count { it.computer.online }
            Text(
                stringResource(Res.string.attention_stats, model.machines.size, online, model.attention.size),
                color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp, style = tightCenter(10.5.sp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        if (model.attention.isEmpty()) {
            Text(
                stringResource(Res.string.attention_empty),
                color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 18.dp),
            )
        } else {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
                model.attention.forEach { a ->
                    AttentionRow(
                        a,
                        onDeny = { model.resolveAttention(a, allow = false) },
                        onAllow = { model.resolveAttention(a, allow = true) },
                        onOpen = {
                            model.showAttention = false
                            model.openAttention(a) // the asking session when the list named it, else its machine
                        },
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Text(
            stringResource(Res.string.attention_footer),
            color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** One approval: MachineChip · tool · countdown, mono preview, cursor-sized Deny/Allow; hover reveals open. */
@Composable
private fun AttentionRow(a: DkAttention, onDeny: () -> Unit, onAllow: () -> Unit, onOpen: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).hoverable(src)
            .background(if (hovered) Tok.surface else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onOpen).padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            DkMachineChip(a.machine, a.os, fontSize = 10.5.sp, glyph = 12.dp)
            Text("·", color = Tok.hair, fontFamily = Dk.ui, fontSize = 11.5.sp, style = tightCenter(11.5.sp))
            Text(a.tool, color = Tok.tx, fontFamily = Dk.ui, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, style = tightCenter(11.5.sp))
            Spacer(Modifier.weight(1f))
            a.seconds?.let { s ->
                Text(
                    "${s / 60}:${(s % 60).toString().padStart(2, '0')}",
                    color = if (s <= 25) Tok.warn else Tok.tx2, fontFamily = Dk.mono, fontSize = 10.5.sp,
                    style = tightCenter(10.5.sp),
                )
            }
        }
        Text(
            a.preview, color = Tok.tx2, fontFamily = Dk.mono, fontSize = 10.5.sp, style = tightCenter(10.5.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(7.dp))
                .background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(7.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hovered) Text(stringResource(Res.string.attention_open_session), color = Tok.tx2, fontFamily = Dk.ui, fontSize = 10.5.sp, style = tightCenter(10.5.sp))
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(Res.string.deny), color = Tok.danger, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                style = tightCenter(11.sp),
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).border(1.dp, Tok.danger.copy(alpha = 0.33f), RoundedCornerShape(7.dp))
                    .clickable(onClick = onDeny).padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Text(
                stringResource(Res.string.allow), color = Tok.base, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                style = tightCenter(11.sp),
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Tok.accent)
                    .clickable(onClick = onAllow).padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}
