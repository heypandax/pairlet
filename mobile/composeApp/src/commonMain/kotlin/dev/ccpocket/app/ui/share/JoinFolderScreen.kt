package dev.ccpocket.app.ui.share

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.pairing.decodeShareInvite
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.ShareInvite
import org.jetbrains.compose.resources.stringResource
import qrscanner.CameraLens
import qrscanner.QrScanner

/**
 * Guest join flow (issue #115, design frames 3a/3a-err/3b): redeem an invite by scanning or pasting it,
 * then the accept-preview (the guest trust screen — folder, origin, tier/lifetime, and the boundary card,
 * the same "They get / They don't get" statement the owner saw). Joining ONE folder, not a whole computer.
 */
@Composable
fun JoinFolderScreen(repo: PocketRepository, onBack: () -> Unit, onJoined: () -> Unit) {
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    var preview by remember { mutableStateOf<ShareInvite?>(null) }

    val invite = preview
    if (invite != null) {
        AcceptPreview(
            invite,
            onJoin = { repo.redeemShareInvite(invite); onJoined() },
            onDecline = { preview = null },
        )
        return
    }
    RedeemScreen(onBack = onBack, onInvite = { preview = it })
}

// ── frame 3a / 3a-err: redeem (scan or paste) ──

@Composable
private fun RedeemScreen(onBack: () -> Unit, onInvite: (ShareInvite) -> Unit) {
    var pasted by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    fun tryDecode(raw: String) {
        val inv = decodeShareInvite(raw)
        if (inv != null) onInvite(inv) else error = true
    }

    Column(Modifier.fillMaxSize().background(Tok.base)) {
        ShareTopBar(stringResource(Res.string.join_title), onBack, closeGlyph = true)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(top = 12.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(Res.string.join_scan_title), color = Tok.tx, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(7.dp))
            Text(stringResource(Res.string.join_scan_hint), color = Tok.tx2, fontSize = 13.sp, textAlign = TextAlign.Center)

            Spacer(Modifier.height(24.dp))
            ScanBox { v -> error = false; tryDecode(v) }

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).height(1.dp).background(Tok.hair))
                Text(stringResource(Res.string.join_or_paste), color = Tok.muted, fontSize = 11.sp)
                Box(Modifier.weight(1f).height(1.dp).background(Tok.hair))
            }

            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                    .border(1.dp, if (error) Tok.danger else Tok.hair, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                if (pasted.isEmpty()) Text(stringResource(Res.string.join_paste_placeholder), color = Tok.muted, fontSize = 13.sp)
                BasicTextField(
                    value = pasted,
                    onValueChange = { pasted = it; error = false; if (it.trim().length > 24) tryDecode(it) },
                    singleLine = true,
                    textStyle = TextStyle(color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    cursorBrush = SolidColor(Tok.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (error) {
                Spacer(Modifier.height(12.dp))
                Text("⚠ " + stringResource(Res.string.join_invalid), color = Tok.danger, fontSize = 12.sp, textAlign = TextAlign.Center)
            } else {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.join_single_use), color = Tok.muted, fontSize = 11.5.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A camera viewfinder with a terracotta corner frame — reuses the same qr-kit scanner as pairing (#5.1). */
@Composable
private fun ScanBox(onScanned: (String) -> Unit) {
    var handled by remember { mutableStateOf(false) }
    Box(
        Modifier.size(246.dp).clip(RoundedCornerShape(22.dp))
            .background(Brush.radialGradient(listOf(Color(0xFF15171A), Color(0xFF0A0B0C))))
            .border(1.dp, Tok.hair, RoundedCornerShape(22.dp)),
    ) {
        QrScanner(
            modifier = Modifier.fillMaxSize(),
            flashlightOn = false,
            cameraLens = CameraLens.Back,
            openImagePicker = false,
            onCompletion = { v -> if (!handled) { handled = true; onScanned(v) } },
            imagePickerHandler = {},
            onFailure = {},
            overlayColor = Color.Transparent,
            overlayBorderColor = Color.Transparent,
        )
        Canvas(Modifier.fillMaxSize().padding(20.dp)) {
            val len = 34.dp.toPx(); val th = 3.dp.toPx(); val w = size.width; val h = size.height
            fun l(a: Offset, b: Offset) = drawLine(Tok.accent, a, b, th, StrokeCap.Round)
            l(Offset(0f, 0f), Offset(len, 0f)); l(Offset(0f, 0f), Offset(0f, len))
            l(Offset(w, 0f), Offset(w - len, 0f)); l(Offset(w, 0f), Offset(w, len))
            l(Offset(0f, h), Offset(len, h)); l(Offset(0f, h), Offset(0f, h - len))
            l(Offset(w, h), Offset(w - len, h)); l(Offset(w, h), Offset(w, h - len))
        }
    }
}

// ── frame 3b: accept preview (the guest trust screen) ──

@Composable
private fun AcceptPreview(invite: ShareInvite, onJoin: () -> Unit, onDecline: () -> Unit) {
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onDecline() }
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        ShareTopBar(stringResource(Res.string.join_review_title), onDecline)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 30.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📁", fontSize = 30.sp)
                Spacer(Modifier.height(14.dp))
                Text(invite.folderName, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                invite.ownerLabel?.let { Text(stringResource(Res.string.shared_by_caption, it), color = Tok.muted, fontSize = 12.sp) }
                Spacer(Modifier.height(15.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TierBadge(invite.tier)
                    val left = expiryLeft(invite.expiresAt, epochMillis())
                    Text(
                        expiryLeftText(left), color = Tok.tx2, fontSize = 11.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(20.dp)).padding(horizontal = 11.dp, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            BoundaryCard()
            Spacer(Modifier.height(14.dp))
            Text(stringResource(Res.string.join_note), color = Tok.muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(22.dp))
            SharePrimaryButton(stringResource(Res.string.join_cta), onClick = onJoin)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(Res.string.join_decline), color = Tok.tx2, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onDecline).padding(vertical = 12.dp),
            )
        }
    }
}
