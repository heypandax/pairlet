package dev.ccpocket.app.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.PocketSheet
import dev.ccpocket.app.ui.tilde
import dev.ccpocket.protocol.ShareInfo
import org.jetbrains.compose.resources.stringResource

/**
 * Owner management page (issue #115, design frames 2a–2c + 4d): every folder I've shared out — who holds
 * it, whether it's in use, how long it's valid, and one-tap revoke; plus a History section for ended shares
 * with "Share again". Activity is read-only visibility; approvals go to the guest.
 */
@Composable
fun SharedFoldersScreen(repo: PocketRepository, onBack: () -> Unit, onShareAgain: (String) -> Unit = {}) {
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    LaunchedEffect(Unit) { repo.listShares() }
    val now = epochMillis()
    val groups = remember(repo.shares.toList(), now) { groupShares(repo.shares.toList(), now) }
    var revokeTarget by remember { mutableStateOf<ShareInfo?>(null) }

    Column(Modifier.fillMaxSize().background(Tok.base)) {
        ShareTopBar(stringResource(Res.string.shared_folders_title), onBack)
        if (repo.shares.isEmpty() && repo.sharesLoaded.value) {
            EmptyShares()
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (groups.active.isNotEmpty() && groups.history.isNotEmpty()) {
                    ShareSectionLabel(stringResource(Res.string.share_active_label), Modifier.padding(top = 4.dp))
                }
                groups.active.forEach { s -> ActiveShareCard(s, now) { revokeTarget = s } }
                if (groups.history.isNotEmpty()) {
                    ShareSectionLabel(stringResource(Res.string.share_history_label), Modifier.padding(top = 10.dp))
                    groups.history.forEach { s -> HistoryShareCard(s) { onShareAgain(s.path) } }
                }
                if (groups.active.isNotEmpty()) {
                    Text(
                        stringResource(Res.string.share_owner_footer), color = Tok.muted, fontSize = 11.5.sp,
                        textAlign = TextAlign.Center, lineHeight = 17.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 16.dp, end = 16.dp),
                    )
                }
            }
        }
    }

    revokeTarget?.let { s ->
        RevokeSheet(folder = tilde(s.path), onCancel = { revokeTarget = null }) {
            repo.revokeShare(s.deviceId); revokeTarget = null
        }
    }
}

// ── frame 2a: an active share card ──

@Composable
private fun ActiveShareCard(s: ShareInfo, now: Long, onRevoke: () -> Unit) {
    val status = shareStatus(s, now)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(16.dp)).padding(15.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(tilde(s.path), color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RevokeButton(onRevoke)
        }
        Spacer(Modifier.height(13.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GuestChip(s.guestLabel)
            TierBadge(s.tier)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(Res.string.share_expires_in, countdown(s.expiresAt, now)),
                color = if (status == ShareStatus.NEAR_EXPIRY) Tok.warn else Tok.muted,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1,
            )
        }
        Spacer(Modifier.height(13.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Spacer(Modifier.height(13.dp))
        StatusLine(status, s)
    }
}

@Composable
private fun StatusLine(status: ShareStatus, s: ShareInfo) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val dotColor = when (status) {
            ShareStatus.ACTIVE_NOW -> Tok.ok
            else -> Tok.muted
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        if (status == ShareStatus.ACTIVE_NOW) {
            Text(stringResource(Res.string.share_active_now).replaceFirstChar { it.uppercase() }, color = Tok.ok, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            if (s.activeSessions > 0) Text("· " + stringResource(Res.string.share_sessions_live, s.activeSessions), color = Tok.muted, fontSize = 12.sp)
        } else {
            Text(stringResource(Res.string.share_status_idle), color = Tok.tx2, fontSize = 12.5.sp)
        }
    }
}

// ── frame 4d: a history (ended) share card ──

@Composable
private fun HistoryShareCard(s: ShareInfo, onShareAgain: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(16.dp)).padding(15.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(tilde(s.path), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, Tok.hair, RoundedCornerShape(20.dp)).clickable(onClick = onShareAgain).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.Refresh, null, tint = Tok.tx2, modifier = Modifier.size(13.dp))
                Text(stringResource(Res.string.share_share_again), color = Tok.tx2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(13.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GuestChip(s.guestLabel, dim = true)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(if (s.revoked) Res.string.share_revoked_label else Res.string.share_expired_label),
                color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
        }
    }
}

// ── shared bits ──

@Composable
private fun GuestChip(label: String?, dim: Boolean = false) {
    val name = label ?: stringResource(Res.string.share_guest_someone)
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(if (dim) Color.Transparent else Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(20.dp)).padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        GuestAvatar(name, color = if (dim) Tok.muted else Tok.accent)
        Text(name, color = if (dim) Tok.muted else Tok.tx, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RevokeButton(onClick: () -> Unit) {
    Text(
        stringResource(Res.string.share_revoke), color = Tok.danger, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).border(1.5.dp, Tok.danger.copy(alpha = 0.45f), RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 6.dp),
    )
}

// ── frame 2c: empty state ──

@Composable
private fun EmptyShares() {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.FolderOff, null, tint = Tok.muted, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text(stringResource(Res.string.shared_folders_empty), color = Tok.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.shared_folders_empty_hint), color = Tok.muted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
    }
}

// ── frame 2b: revoke confirmation ──

@Composable
private fun RevokeSheet(folder: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    PocketSheet(onCancel) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 18.dp, top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Tok.danger.copy(alpha = 0.1f)).border(1.dp, Tok.danger.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("⛊", color = Tok.danger, fontSize = 24.sp) }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(Res.string.share_revoke_title), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(folder, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.share_revoke_c1), color = Tok.tx2, fontSize = 13.sp, textAlign = TextAlign.Center)
                Text(stringResource(Res.string.share_revoke_c2), color = Tok.tx2, fontSize = 13.sp, textAlign = TextAlign.Center)
                Text(stringResource(Res.string.share_revoke_c3), color = Tok.tx2, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                ShareOutlineButton(stringResource(Res.string.cancel), Modifier.weight(1f), onClick = onCancel)
                Text(
                    stringResource(Res.string.share_revoke_confirm), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1.3f).clip(RoundedCornerShape(14.dp)).background(Tok.danger).clickable(onClick = onConfirm).padding(vertical = 15.dp),
                )
            }
        }
    }
}
