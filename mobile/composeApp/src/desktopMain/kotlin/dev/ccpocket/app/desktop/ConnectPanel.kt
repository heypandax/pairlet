package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.decodeShareInvite
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.resolve

/** Not-connected state: pick a paired computer, or pair a new one with the 6-digit code it prints. */
@Composable
fun ConnectPanel(repo: PocketRepository) {
    Box(Modifier.fillMaxSize().background(Tok.base), contentAlignment = Alignment.Center) {
        Column(Modifier.width(380.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("CC Pocket", color = Tok.tx, fontFamily = Dk.ui, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(28.dp))
            if (repo.addingDevice.value || repo.pairedList.isEmpty()) PairingForm(repo) else DevicePicker(repo)
            Spacer(Modifier.height(18.dp))
            JoinSharedFolder(repo)
        }
    }
}

/** Guest entry (issue #115): paste a folder-share invite to join ONE folder (not a whole computer). */
@Composable
private fun JoinSharedFolder(repo: PocketRepository) {
    var open by remember { mutableStateOf(false) }
    var blob by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    if (!open) {
        Text(
            "Join a shared folder", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true }.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Text("JOIN A SHARED FOLDER", color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface).border(1.dp, if (error) Tok.danger else Tok.hair, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 11.dp)) {
            if (blob.isEmpty()) Text("Paste invite code", color = Tok.muted, fontFamily = Dk.mono, fontSize = 12.sp)
            BasicTextField(blob, { blob = it; error = false }, singleLine = true, textStyle = TextStyle(color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp), cursorBrush = SolidColor(Tok.accent), modifier = Modifier.fillMaxWidth())
        }
        if (error) {
            Spacer(Modifier.height(8.dp))
            Text("This invite is invalid or has expired. Ask for a new one.", color = Tok.danger, fontFamily = Dk.ui, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Join folder", enabled = blob.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            val inv = decodeShareInvite(blob)
            if (inv != null) repo.redeemShareInvite(inv) else error = true
        }
    }
}

@Composable
private fun PairingForm(repo: PocketRepository) {
    var code by remember { mutableStateOf("") }
    Text("CONNECT A COMPUTER", color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
    Spacer(Modifier.height(16.dp))
    CodeField(code, big = true) { v -> code = v; if (v.length == 6) repo.pairWithCode(v) }
    Spacer(Modifier.height(18.dp))
    PrimaryButton("Connect", enabled = code.length == 6, modifier = Modifier.fillMaxWidth()) { repo.pairWithCode(code) }
    Spacer(Modifier.height(14.dp))
    PairHint()
    Spacer(Modifier.height(12.dp))
    StatusLine(repo.status.value.resolve(), Tok.muted)
    if (repo.pairedList.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Back", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { repo.cancelAddDevice() }.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DevicePicker(repo: PocketRepository) {
    Text("CHOOSE A COMPUTER", color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
    Spacer(Modifier.height(13.dp))
    repo.pairedList.forEach { d ->
        Row(
            Modifier.fillMaxWidth().padding(bottom = 9.dp).clip(RoundedCornerShape(11.dp)).background(Tok.surface)
                .border(1.dp, Tok.hair, RoundedCornerShape(11.dp)).hoverFill(RoundedCornerShape(11.dp))
                .clickable { repo.switchDaemon(d) }.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Icon(Icons.Rounded.LaptopMac, null, tint = Tok.tx2, modifier = Modifier.size(17.dp))
            Text(d.displayName(), color = Tok.tx, fontFamily = Dk.ui, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.KeyboardArrowRight, null, tint = Tok.muted, modifier = Modifier.size(16.dp))
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).dashedBorder(Tok.hair, 11.dp).hoverFill(RoundedCornerShape(11.dp))
            .clickable { repo.beginAddDevice() }.padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(Icons.Rounded.Add, null, tint = Tok.accent, modifier = Modifier.size(15.dp))
        Text("Add computer", color = Tok.accent, fontFamily = Dk.ui, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * A segmented 6-cell code field. The visible cells reflect [code]; an invisible full-size [BasicTextField]
 * captures digits + keyboard focus (the standard OTP pattern). The current cell shows an accent border + caret.
 */
@Composable
private fun CodeField(code: String, big: Boolean = false, onCodeChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val cellW = if (big) 48.dp else 42.dp
    val cellH = if (big) 58.dp else 52.dp
    Box(
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(if (big) 10.dp else 8.dp)) {
            repeat(6) { i ->
                val filled = i < code.length
                val cur = i == code.length
                Box(
                    Modifier.size(cellW, cellH).clip(RoundedCornerShape(10.dp)).background(Tok.surface)
                        .border(1.5.dp, if (cur) Tok.accent else Tok.hair, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (filled) Text(code[i].toString(), color = Tok.tx, fontFamily = Dk.mono, fontSize = if (big) 24.sp else 21.sp, fontWeight = FontWeight.SemiBold)
                    else if (cur) Box(Modifier.size(2.dp, if (big) 26.dp else 22.dp).background(Tok.accent))
                }
            }
        }
        BasicTextField(
            value = code,
            onValueChange = { v -> onCodeChange(v.filter { it.isDigit() }.take(6)) },
            singleLine = true,
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            cursorBrush = SolidColor(Color.Transparent),
            modifier = Modifier.matchParentSize().focusRequester(focus).alpha(0f),
        )
    }
}

/** Helper line with the pairing command in an inline mono chip. */
@Composable
private fun PairHint() {
    Text(
        buildAnnotatedString {
            append("Run ")
            withStyle(SpanStyle(fontFamily = Dk.mono, fontSize = 12.sp, background = Tok.surface, color = Tok.tx2)) { append(" cc-pocket pair ") }
            append(" on the other computer to get a code.")
        },
        color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.5.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
    )
}

/** A bottom status line — muted by default, danger on a bad code, with a spinner while connecting. */
@Composable
private fun StatusLine(text: String, color: Color, spinner: Boolean = false) {
    if (text.isBlank()) {
        Spacer(Modifier.height(18.dp))
        return
    }
    Row(Modifier.fillMaxWidth().height(18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (spinner) {
            CircularProgressIndicator(Modifier.size(11.dp), color = color, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = color, fontFamily = Dk.mono, fontSize = 11.5.sp)
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, color = if (enabled) Tok.base else Tok.muted, fontFamily = Dk.ui, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Tok.accent else Tok.surface)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 11.dp),
    )
}

/**
 * Pair a NEW computer from the live shell — a centered modal over a scrim. Uses [PocketRepository.addDeviceByCode]
 * so the current session stays connected (the new computer just appears in the switcher afterward). Closes on a
 * successful pair; a bad/expired code clears the field and shows a danger status line for a retry.
 */
@Composable
fun AddComputerModal(repo: PocketRepository, onClose: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errored by remember { mutableStateOf(false) }
    fun submit() {
        if (code.length != 6 || busy) return
        busy = true; errored = false
        repo.addDeviceByCode(code) { ok -> busy = false; if (ok) onClose() else { errored = true; code = "" } }
    }
    val scrim = remember { MutableInteractionSource() }
    val card = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Dk.backdrop.copy(alpha = 0.64f)).clickable(interactionSource = scrim, indication = null) { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(420.dp).shadow(30.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Tok.raised)
                .border(1.dp, Tok.hair, RoundedCornerShape(16.dp)).clickable(interactionSource = card, indication = null) {}.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Add a computer", color = Tok.tx, fontFamily = Dk.ui, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            Text(
                "Pair another computer — your current session stays connected.",
                color = Tok.tx2, fontFamily = Dk.ui, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 20.dp),
            )
            CodeField(code) { v -> code = v; errored = false; if (v.length == 6) submit() }
            Spacer(Modifier.height(18.dp))
            PairHint()
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Cancel", color = Tok.tx, fontFamily = Dk.ui, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).clickable { onClose() }.padding(vertical = 11.dp),
                )
                PrimaryButton(if (busy) "Pairing…" else "Connect", enabled = code.length == 6 && !busy, modifier = Modifier.weight(1f)) { submit() }
            }
            Spacer(Modifier.height(12.dp))
            when {
                busy -> StatusLine("connecting…", Tok.tx2, spinner = true)
                errored -> StatusLine("invalid or expired code", Tok.danger)
                else -> StatusLine(repo.status.value.resolve(), Tok.muted)
            }
        }
    }
}
