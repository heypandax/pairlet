package dev.ccpocket.app.ui.share

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.AccessTier
import org.jetbrains.compose.resources.stringResource

// ── tier label / description lookups (from the calibrated share_tier_* strings) ──

@Composable
fun tierLabel(t: AccessTier): String = stringResource(
    when (t) {
        AccessTier.REVIEW -> Res.string.share_tier_review
        AccessTier.COLLABORATE -> Res.string.share_tier_collaborate
        AccessTier.AUTONOMOUS -> Res.string.share_tier_autonomous
        AccessTier.UNKNOWN -> Res.string.share_tier_review
    },
)

@Composable
fun tierDesc(t: AccessTier): String = stringResource(
    when (t) {
        AccessTier.REVIEW -> Res.string.share_tier_review_desc
        AccessTier.COLLABORATE -> Res.string.share_tier_collaborate_desc
        AccessTier.AUTONOMOUS -> Res.string.share_tier_autonomous_desc
        AccessTier.UNKNOWN -> Res.string.share_tier_review_desc
    },
)

/** "6d left" / "4h left" / "12m left" / "expired" — the caption for a share's remaining validity. */
@Composable
fun expiryLeftText(left: ExpiryLeft): String = when (left) {
    is ExpiryLeft.Days -> stringResource(Res.string.share_left_days, left.n)
    is ExpiryLeft.Hours -> stringResource(Res.string.share_left_hours, left.n)
    is ExpiryLeft.Minutes -> stringResource(Res.string.share_left_minutes, left.n)
    ExpiryLeft.Expired -> stringResource(Res.string.share_expired)
}

/** Compact lifetime label for the composer's Expires pills — locale-neutral d/h tokens. */
fun expiryOptionLabel(o: ShareExpiryOption): String = when (o) {
    ShareExpiryOption.HOUR_1 -> "1h"
    ShareExpiryOption.HOURS_24 -> "24h"
    ShareExpiryOption.DAYS_7 -> "7d"
    ShareExpiryOption.DAYS_30 -> "30d"
}

// ── badges ──

/** The terracotta tier chip ("Collaborate") — attention hue, since the tier is what the owner is granting. */
@Composable
fun TierBadge(tier: AccessTier) {
    Text(
        tierLabel(tier), color = Tok.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Tok.accent.copy(alpha = 0.12f))
            .border(1.dp, Tok.accent.copy(alpha = 0.3f), RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** The neutral "Shared" pill on a guest's shared row (issue #115) — deliberately a hairline, NOT terracotta:
 *  terracotta stays reserved for "needs you". A link glyph + muted text reads as provenance, not attention. */
@Composable
fun SharedPill() {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("🔗", fontSize = 9.sp) // link glyph
        Text(stringResource(Res.string.shared_badge), color = Tok.tx2, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A round initial avatar for a guest ("A" for Alex) — tinted terracotta on the owner's page. */
@Composable
fun GuestAvatar(label: String, color: Color = Tok.accent) {
    val initial = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        Modifier.size(20.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(initial, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

// ── the boundary card (the hero) ──

@Composable
private fun BoundaryItem(get: Boolean, text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Icon(
            if (get) Icons.Rounded.Check else Icons.Rounded.Close, null,
            tint = if (get) Tok.ok else Tok.muted, modifier = Modifier.size(17.dp),
        )
        Text(text, color = if (get) Tok.tx else Tok.tx2, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/**
 * "They get ✓ / They don't get ✗ + shell warning" — the core safety statement of the whole feature.
 * Copy comes from the calibrated share_boundary_* strings (the "don't get" list is best-effort in v1, so
 * the honest shell footer — commands run as your user, not sandboxed — sits right under it, never oversold).
 */
@Composable
fun BoundaryCard(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(Res.string.share_boundary_get), color = Tok.ok, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                BoundaryItem(true, stringResource(Res.string.share_boundary_get_agents))
                BoundaryItem(true, stringResource(Res.string.share_boundary_get_files))
                BoundaryItem(true, stringResource(Res.string.share_boundary_get_env))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(Res.string.share_boundary_not), color = Tok.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                BoundaryItem(false, stringResource(Res.string.share_boundary_not_folders))
                BoundaryItem(false, stringResource(Res.string.share_boundary_not_memory))
                BoundaryItem(false, stringResource(Res.string.share_boundary_not_mcp))
                BoundaryItem(false, stringResource(Res.string.share_boundary_not_switch))
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Tok.warn.copy(alpha = 0.07f)).padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Warning, null, tint = Tok.warn, modifier = Modifier.size(16.dp))
            Text(stringResource(Res.string.share_boundary_shell_warn), color = Tok.warn, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

// ── shared buttons ──

/** The primary terracotta action ("Create invite" / "Join folder"). */
@Composable
fun SharePrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text, color = Tok.base, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Tok.accent else Tok.accent.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 15.dp),
    )
}

/** A bordered secondary action ("Share…" / "New code" / "Remove from list"). */
@Composable
fun ShareOutlineButton(text: String, modifier: Modifier = Modifier, color: Color = Tok.tx, onClick: () -> Unit) {
    Text(
        text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(13.dp)).border(1.dp, Tok.hair, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick).padding(vertical = 13.dp),
    )
}

/** Centered mono folder header ("~/work/acme-api" + "on Lidapeng-MacBook"). */
@Composable
fun MonoFolderHead(folder: String, host: String?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(folder, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (host != null) {
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("on ", color = Tok.muted, fontSize = 12.sp)
                Text(host, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

/** The uppercase section label used inside share screens ("ACCESS LEVEL", "EXPIRES"). */
@Composable
fun ShareSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp, modifier = modifier,
    )
}
