package dev.ccpocket.app.ui.handoff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

// ════════════════════════════════════════════════════════════════════
//  WAITING lock banner (Frame 3b) — pinned card above the composer.
//  Waiting is NEUTRAL, not terracotta: nothing is running yet. Recall is
//  the only danger-tinted control on the screen. Both buttons 44pt tall.
// ════════════════════════════════════════════════════════════════════

@Composable
fun HandoffLockBanner(
    recipientLabel: String,
    metaLine: String,
    onCopyInvite: () -> Unit,
    onRecall: () -> Unit,
    /** Non-null on a device that is NOT the initiator: the actions collapse to one "View invite" row. */
    onViewInvite: (() -> Unit)? = null,
    /** True for a contact-bound offer: nothing to copy (no invite artefact) — Recall stands alone. */
    directDelivery: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Tok.tx2))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.ho_waiting_title, recipientLabel),
                    color = Tok.tx, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    metaLine, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                onViewInvite != null -> HandoffGhostButton(stringResource(Res.string.ho_view_invite), Modifier.weight(1f), onClick = onViewInvite)
                directDelivery -> HandoffGhostButton(stringResource(Res.string.ho_recall), Modifier.weight(1f), danger = true, onClick = onRecall)
                else -> {
                    HandoffGhostButton(stringResource(Res.string.ho_copy_invite), Modifier.weight(1f), onClick = onCopyInvite)
                    HandoffGhostButton(stringResource(Res.string.ho_recall), Modifier.weight(1f), danger = true, onClick = onRecall)
                }
            }
        }
    }
}

/** 44pt ghost button — hairline border; [danger] tints border and text (Recall/Revoke only). */
@Composable
fun HandoffGhostButton(text: String, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) Tok.danger else Tok.tx2
    Box(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
            .border(1.dp, if (danger) Tok.danger.copy(alpha = 0.45f) else Tok.hair, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ════════════════════════════════════════════════════════════════════
//  Role ribbons (Frames 6/7) — slim strip under the header.
//  Terracotta = the viewer is the actor (recipient in control);
//  neutral = the viewer is watching (initiator spectating).
// ════════════════════════════════════════════════════════════════════

@Composable
fun HandoffRibbon(
    accent: Boolean,
    text: String,
    countdown: String?,
    avatars: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val fg = if (accent) Tok.accent else Tok.tx2
    Row(
        modifier.fillMaxWidth().heightIn(min = 38.dp)
            .background(if (accent) Tok.accent.copy(alpha = 0.10f) else Tok.surface)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        avatars?.invoke()
        Text(text, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
        if (countdown != null) {
            Text(countdown, color = fg, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  Watch bar (Frame 7) — REPLACES the composer while spectating:
//  during IN_PROGRESS there is no draft to preserve, and the bar
//  carries the one escape hatch (Recall control).
// ════════════════════════════════════════════════════════════════════

@Composable
fun HandoffWatchBar(onRecall: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(Tok.surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.RemoveRedEye, null, tint = Tok.muted, modifier = Modifier.size(16.dp))
        Text(
            stringResource(Res.string.ho_watching), color = Tok.tx2, fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, modifier = Modifier.weight(1f),
        )
        Row(
            Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                .border(1.dp, Tok.danger.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                .clickable(onClick = onRecall).padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.Replay, null, tint = Tok.danger, modifier = Modifier.size(14.dp))
            Text(stringResource(Res.string.ho_recall_control), color = Tok.danger, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  Locked composer stand-in (Frame 3b) — the composer stays visibly
//  present at 45% with a lock glyph, so the return to normal is obvious.
// ════════════════════════════════════════════════════════════════════

@Composable
fun HandoffLockedComposer(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().alpha(0.45f).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Lock, null, tint = Tok.muted, modifier = Modifier.size(15.dp))
            Text(stringResource(Res.string.ho_locked_placeholder), color = Tok.muted, fontSize = 14.sp)
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Tok.surface).border(1.dp, Tok.hair, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("↑", color = Tok.muted, fontSize = 17.sp) }
    }
}

// ════════════════════════════════════════════════════════════════════
//  System rows (dashed) — "Handoff created…" / "Frank accepted…" /
//  "Result injected…" — quiet in-stream provenance markers.
// ════════════════════════════════════════════════════════════════════

@Composable
fun HandoffSysRow(text: String, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
            .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        HandoffRelayGlyph(Tok.muted)
        Text(text, color = Tok.tx2, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        if (onClick != null) Text("›", color = Tok.muted, fontSize = 13.sp)
    }
}

/** The relay/baton glyph (design i-relay): two nodes + an arrowed link, drawn so it needs no asset. */
@Composable
fun HandoffRelayGlyph(tint: Color, size: androidx.compose.ui.unit.Dp = 16.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val r = w * 0.11f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.085f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(tint, r, center = androidx.compose.ui.geometry.Offset(w * 0.20f, h * 0.5f), style = stroke)
        drawCircle(tint, r, center = androidx.compose.ui.geometry.Offset(w * 0.80f, h * 0.5f), style = stroke)
        drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.5f), strokeWidth = stroke.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.48f, h * 0.38f), androidx.compose.ui.geometry.Offset(w * 0.60f, h * 0.5f), strokeWidth = stroke.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.48f, h * 0.62f), androidx.compose.ui.geometry.Offset(w * 0.60f, h * 0.5f), strokeWidth = stroke.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** The prominent "Finish & return" pill above the recipient's composer (Frame 6) — terracotta outline. */
@Composable
fun HandoffFinishReturnButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(11.dp))
            .background(Tok.accent.copy(alpha = 0.10f))
            .border(1.dp, Tok.accent, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Replay, null, tint = Tok.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(Res.string.ho_finish_return), color = Tok.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
