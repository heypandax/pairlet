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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.pairing.encode
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.tilde
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.ShareInvite
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import qrgenerator.QRCodeImage

/**
 * Owner invite flow (issue #115, design frames 1a–1d): the share composer (the trust screen — access
 * level + lifetime + the boundary card) then the invite-ready screen (QR + copyable code). Full-screen,
 * replaces the projects list like Settings does.
 */
@Composable
fun ShareFolderScreen(repo: PocketRepository, entry: DirectoryEntry, onBack: () -> Unit) {
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    // a stale invite from a previous share must not flash invite-ready on entry
    LaunchedEffect(entry.path) { repo.lastShareCreated.value = null }
    var config by remember { mutableStateOf(InviteConfig()) }
    val created = repo.lastShareCreated.value
    val invite = created?.takeUnless { it.ok == false }?.invite

    if (invite != null) {
        InviteReady(
            repo, invite,
            folderPath = tilde(entry.path),
            onNewCode = { repo.createShare(entry.path, config.tier, config.expiry.seconds) },
            onDone = onBack,
        )
        return
    }
    ShareComposer(
        repo, entry, config,
        error = created?.takeIf { it.ok == false }?.error,
        onConfig = { config = it },
        onCreate = { repo.createShare(entry.path, config.tier, config.expiry.seconds) },
        onBack = onBack,
    )
}

// ── frame 1b: the share composer ──

@Composable
private fun ShareComposer(
    repo: PocketRepository,
    entry: DirectoryEntry,
    config: InviteConfig,
    error: String?,
    onConfig: (InviteConfig) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
) {
    val creating = repo.sharesRefreshing.value
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        ShareTopBar(stringResource(Res.string.share_composer_title), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 8.dp, bottom = 40.dp),
        ) {
            MonoFolderHead(tilde(entry.path), repo.paired.value?.displayName())

            ShareSectionLabel(stringResource(Res.string.share_access_level), Modifier.padding(top = 22.dp, bottom = 10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                for (tier in SHARE_TIERS) {
                    TierRadioCard(
                        tier = tier,
                        selected = config.tier == tier,
                        recommended = tier == DEFAULT_TIER,
                        warn = tier == AccessTier.AUTONOMOUS,
                        onSelect = { onConfig(config.copy(tier = tier)) },
                    )
                }
            }

            ShareSectionLabel(stringResource(Res.string.share_expires_label), Modifier.padding(top = 22.dp, bottom = 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (opt in ShareExpiryOption.entries) {
                    ExpiryPill(opt, selected = config.expiry == opt, modifier = Modifier.weight(1f)) { onConfig(config.copy(expiry = opt)) }
                }
            }
            Text(stringResource(Res.string.share_revoke_hint_anytime), color = Tok.muted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 9.dp, start = 2.dp))

            Spacer(Modifier.height(22.dp))
            BoundaryCard()

            if (error != null) {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(Res.string.share_create_failed), color = Tok.danger, fontSize = 12.5.sp, lineHeight = 17.sp)
            }

            Spacer(Modifier.height(18.dp))
            SharePrimaryButton(
                stringResource(if (creating) Res.string.share_creating else Res.string.share_create),
                enabled = !creating,
                onClick = onCreate,
            )
        }
    }
}

@Composable
private fun TierRadioCard(tier: AccessTier, selected: Boolean, recommended: Boolean, warn: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(if (selected) Tok.accent.copy(alpha = 0.08f) else Tok.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Tok.accent.copy(alpha = 0.6f) else Tok.hair, RoundedCornerShape(13.dp))
            .clickable(onClick = onSelect).padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.padding(top = 1.dp).size(19.dp).clip(CircleShape)
                .border(1.5.dp, if (selected) Tok.accent else Tok.muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(9.dp).clip(CircleShape).background(Tok.accent)) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (warn) Icon(Icons.Rounded.Warning, null, tint = Tok.warn, modifier = Modifier.size(15.dp))
                Text(tierLabel(tier), color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (recommended) Text(
                    stringResource(Res.string.share_tier_recommended), color = Tok.accent, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Tok.accent.copy(alpha = 0.15f))
                        .border(1.dp, Tok.accent.copy(alpha = 0.35f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.5.dp),
                )
            }
            Text(tierDesc(tier), color = Tok.tx2, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun ExpiryPill(opt: ShareExpiryOption, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Text(
        expiryOptionLabel(opt), color = if (selected) Tok.base else Tok.tx2, fontSize = 12.5.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(20.dp))
            .then(if (selected) Modifier.background(Tok.accent) else Modifier.border(1.dp, Tok.hair, RoundedCornerShape(20.dp)))
            .clickable(onClick = onClick).padding(vertical = 9.dp),
    )
}

// ── frame 1c: invite ready ──

@Composable
private fun InviteReady(repo: PocketRepository, invite: ShareInvite, folderPath: String, onNewCode: () -> Unit, onDone: () -> Unit) {
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onDone() }
    val blob = remember(invite) { invite.encode() }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    // the redeem ticket's short TTL ticks down live (ttlSec from when the invite was minted)
    var now by remember { mutableStateOf(epochMillis()) }
    LaunchedEffect(invite) { while (true) { now = epochMillis(); delay(1000) } }
    // the ticket expiry is approximated as mint-time + ttlSec; we treat the SHARE expiry for the recap
    val ticketMs = remember(invite) { epochMillis() + invite.ttlSec * 1000L }
    val ticketLeft = (ticketMs - now).coerceAtLeast(0)
    val ticketExpired = ticketLeft <= 0

    Column(Modifier.fillMaxSize().background(Tok.base)) {
        ShareTopBar(stringResource(Res.string.share_invite_ready), onDone, closeGlyph = true)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 12.dp, bottom = 40.dp),
        ) {
            // QR card — white plate so the code scans in either theme
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(20.dp)).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(212.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(14.dp), contentAlignment = Alignment.Center) {
                    QRCodeImage(url = blob, contentDescription = "invite QR", modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.share_invite_hint), color = Tok.tx2, fontSize = 12.5.sp, textAlign = TextAlign.Center, lineHeight = 17.sp)
            }

            // copy code + short preview
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    shortCode(blob), color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Tok.surface)
                        .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
                )
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Tok.hair, RoundedCornerShape(10.dp))
                        .clickable { clipboard.setText(AnnotatedString(blob)); copied = true }.padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, tint = Tok.tx2, modifier = Modifier.size(15.dp))
                    Text(stringResource(if (copied) Res.string.share_invite_copied else Res.string.share_invite_copy), color = Tok.tx2, fontSize = 12.5.sp)
                }
            }

            // countdown / expired note
            Spacer(Modifier.height(10.dp))
            if (ticketExpired) {
                Text(stringResource(Res.string.share_invite_expired_note), color = Tok.warn, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    stringResource(Res.string.share_invite_expires_in, ticketCountdown(ticketLeft)),
                    color = Tok.muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }

            // recap strip: access / expires / folder
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(13.dp)).padding(horizontal = 15.dp)) {
                RecapRow(stringResource(Res.string.share_recap_access), tierLabel(invite.tier))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                RecapRow(stringResource(Res.string.share_recap_expires), countdown(invite.expiresAt, now))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                RecapRow(stringResource(Res.string.share_recap_folder), folderPath, mono = true)
            }

            // secondary: Share… / New code
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShareOutlineButton(stringResource(Res.string.share_invite_share), Modifier.weight(1f)) { clipboard.setText(AnnotatedString(blob)); copied = true }
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).border(1.dp, Tok.hair, RoundedCornerShape(13.dp))
                        .clickable { copied = false; onNewCode() }.padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Refresh, null, tint = Tok.tx, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.share_invite_new_code), color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(Res.string.share_manage_hint), color = Tok.muted, fontSize = 11.5.sp,
                textAlign = TextAlign.Center, lineHeight = 17.sp, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            SharePrimaryButton(stringResource(Res.string.share_done), onClick = onDone)
        }
    }
}

@Composable
private fun RecapRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Tok.muted, fontSize = 12.5.sp, modifier = Modifier.width(78.dp))
        Text(
            value, color = Tok.tx, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A short, human-glanceable slice of the (long) invite blob for the "did I copy the right thing" chip. */
private fun shortCode(blob: String): String {
    val body = blob.substringAfterLast('#')
    return if (body.length <= 16) body else body.take(8) + "…" + body.takeLast(6)
}

// ── shared top bar ──

@Composable
internal fun ShareTopBar(title: String, onBack: () -> Unit, closeGlyph: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().background(Tok.base).border(0.dp, Color.Transparent).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (closeGlyph) "✕" else "‹", color = Tok.tx2, fontSize = if (closeGlyph) 17.sp else 22.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(title, color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(34.dp))
    }
}
