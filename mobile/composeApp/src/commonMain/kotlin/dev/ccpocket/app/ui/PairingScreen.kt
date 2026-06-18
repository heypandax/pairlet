package dev.ccpocket.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.defaultDaemonUrl
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource
import qrscanner.CameraLens
import qrscanner.QrScanner

/** "Connect your computer" — pair by 6-digit code (or scan/paste a link). Matches the design mock. */
@Composable
fun PairingScreen(repo: PocketRepository) {
    var code by remember { mutableStateOf("") }
    var showPaste by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(defaultDaemonUrl()) }
    val complete = code.length == 6
    // "Add a computer" entered from an existing binding — let the user back out to the device picker.
    val adding = repo.addingDevice.value
    if (adding) dev.ccpocket.app.SystemBackHandler(enabled = true) { repo.cancelAddDevice() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (adding) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                TextButton({ repo.cancelAddDevice() }) { Text("‹ " + stringResource(Res.string.cancel), color = Tok.muted, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(if (adding) 8.dp else 48.dp))
        Text(stringResource(Res.string.pairing_title), color = Tok.tx, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        Text(
            stringResource(Res.string.pairing_subtitle),
            color = Tok.tx2, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp),
        )
        Spacer(Modifier.height(22.dp))

        Viewfinder { repo.handlePairUrl(it) }

        Spacer(Modifier.height(22.dp))
        Divider(stringResource(Res.string.or_enter_code))
        Spacer(Modifier.height(18.dp))

        CodeInput(code) { v -> code = v; if (v.length == 6) repo.pairWithCode(v) }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.run_code_prefix) + " ", color = Tok.tx2, fontSize = 13.sp)
            Text(
                "cc-pocket pair", color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Tok.surface)
                    .border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
            Text(" " + stringResource(Res.string.run_code_suffix), color = Tok.tx2, fontSize = 13.sp)
        }

        TextButton({ showPaste = !showPaste }) { Text(stringResource(if (showPaste) Res.string.hide else Res.string.cant_scan_paste_link), color = Tok.muted, fontSize = 12.sp) }
        if (showPaste) {
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(link, { link = it }, placeholder = { Text(stringResource(Res.string.paste_pair_link)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ repo.pair(link) }, Modifier.fillMaxWidth(), enabled = link.isNotBlank()) { Text(stringResource(Res.string.pair_from_link)) }
        }

        Spacer(Modifier.height(10.dp))
        Text(repo.status.value.resolve(), color = Tok.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)

        Spacer(Modifier.height(20.dp))
        Button({ if (complete) repo.pairWithCode(code) }, Modifier.fillMaxWidth(), enabled = complete) { Text(stringResource(Res.string.connect)) }
        Spacer(Modifier.height(6.dp))
        TextButton({ advanced = !advanced }) { Text(stringResource(if (advanced) Res.string.hide_advanced else Res.string.advanced_direct_lan), color = Tok.muted, fontSize = 12.sp) }
        if (advanced) {
            OutlinedTextField(url, { url = it }, placeholder = { Text(stringResource(Res.string.daemon_ws_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ repo.startDirect(url) }, Modifier.fillMaxWidth()) { Text(stringResource(Res.string.connect_direct)) }
        }

        Spacer(Modifier.height(20.dp))
        // No computer? Explore the whole app with sample data — no pairing or account needed.
        OutlinedButton({ repo.enterDemo() }, Modifier.fillMaxWidth()) { Text(stringResource(Res.string.demo_cta)) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Viewfinder(onScanned: (String) -> Unit) {
    var scanning by remember { mutableStateOf(true) } // auto-start the camera on the pairing screen
    var handled by remember { mutableStateOf(false) }
    val anim = rememberInfiniteTransition()
    val scanY by anim.animateFloat(6f, 196f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse))
    Box(
        Modifier.size(226.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.radialGradient(listOf(Color(0xFF15171A), Color(0xFF0B0C0D))))
            .border(1.dp, Tok.hair, RoundedCornerShape(16.dp))
            .clickable { if (!scanning) { handled = false; scanning = true } },
    ) {
        if (scanning) {
            QrScanner(
                modifier = Modifier.fillMaxSize(),
                flashlightOn = false,
                cameraLens = CameraLens.Back,
                openImagePicker = false,
                onCompletion = { v -> if (!handled) { handled = true; scanning = false; onScanned(v) } },
                imagePickerHandler = {},
                onFailure = {},
                overlayColor = Color.Transparent,      // suppress qr-kit's own dimming; we draw the frame
                overlayBorderColor = Color.Transparent,
            )
        }
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            val len = 30.dp.toPx(); val th = 3.dp.toPx(); val w = size.width; val h = size.height
            fun l(a: Offset, b: Offset) = drawLine(Tok.accent, a, b, th, StrokeCap.Round)
            l(Offset(0f, 0f), Offset(len, 0f)); l(Offset(0f, 0f), Offset(0f, len))             // TL
            l(Offset(w, 0f), Offset(w - len, 0f)); l(Offset(w, 0f), Offset(w, len))             // TR
            l(Offset(0f, h), Offset(len, h)); l(Offset(0f, h), Offset(0f, h - len))             // BL
            l(Offset(w, h), Offset(w - len, h)); l(Offset(w, h), Offset(w, h - len))            // BR
        }
        if (scanning) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp).offset(y = scanY.dp).height(2.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Tok.accent, Color.Transparent))),
            )
        }
        Text(
            stringResource(if (scanning) Res.string.scanning else Res.string.tap_to_scan),
            color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun Divider(label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f).height(1.dp).background(Tok.hair))
        Text(label, color = Tok.muted, fontSize = 12.5.sp)
        Box(Modifier.weight(1f).height(1.dp).background(Tok.hair))
    }
}

@Composable
private fun CodeInput(code: String, onCode: (String) -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        // visible boxes
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            for (i in 0 until 6) {
                val ch = code.getOrNull(i)
                val active = i == code.length.coerceAtMost(5)
                Box(
                    Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                        .border(if (active) 1.5.dp else 1.dp, if (active) Tok.accent else Tok.hair, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        ch != null -> Text(ch.toString(), color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                        active -> Box(Modifier.width(2.dp).height(26.dp).background(Tok.accent))
                        else -> Box(Modifier.width(8.dp).height(2.dp).background(Tok.hair))
                    }
                }
            }
        }
        // transparent input on top, capturing taps + the numeric keyboard
        BasicTextField(
            value = code,
            onValueChange = { onCode(it.filter(Char::isDigit).take(6)) },
            modifier = Modifier.fillMaxWidth().height(58.dp).alpha(0f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            cursorBrush = SolidColor(Color.Transparent),
        )
    }
}
