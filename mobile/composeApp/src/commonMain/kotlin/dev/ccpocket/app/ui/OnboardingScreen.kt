package dev.ccpocket.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.ob_guide
import dev.ccpocket.app.resources.ob_note_linux
import dev.ccpocket.app.resources.ob_note_mac_run
import dev.ccpocket.app.resources.ob_note_win
import dev.ccpocket.app.resources.ob_pair_hint
import dev.ccpocket.app.resources.ob_pair_now
import dev.ccpocket.app.resources.ob_secure
import dev.ccpocket.app.resources.ob_step_bucket
import dev.ccpocket.app.resources.ob_step_install
import dev.ccpocket.app.resources.ob_step_pair
import dev.ccpocket.app.resources.ob_step_run
import dev.ccpocket.app.resources.ob_sub
import dev.ccpocket.app.resources.ob_title
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/** One numbered install step. [cmds] render as copyable `$ …` lines; the last step drops the connector line. */
private data class OStep(
    val n: Int,
    val title: String,
    val cmds: List<String> = emptyList(),
    val note: String? = null,
    val last: Boolean = false,
)

/**
 * "Connect your computer" — guides the user through installing + running the daemon on their computer, per OS,
 * before pairing (issue #23). The commands are the REAL ones from the README (not the design mock's placeholders):
 * a Windows/macOS/Linux segmented switch swaps them. [onPairNow] returns to the pairing screen.
 */
@Composable
fun OnboardingScreen(onBack: () -> Unit, onPairNow: () -> Unit) {
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    var os by remember { mutableStateOf("macOS") }
    val uri = LocalUriHandler.current

    // every OS is now: one install command (service auto-registers + starts) → pair. The pair step
    // SHOWS the command — a phone-only reader previously had nothing to type on the computer (#23).
    val steps: List<OStep> = when (os) {
        "Windows" -> listOf(
            OStep(1, stringResource(Res.string.ob_step_install), listOf("irm https://raw.githubusercontent.com/heypandax/cc-pocket/main/scripts/install.ps1 | iex"), stringResource(Res.string.ob_note_win)),
            OStep(2, stringResource(Res.string.ob_step_pair), listOf("cc-pocket-daemon pair"), stringResource(Res.string.ob_pair_hint), last = true),
        )
        "Linux" -> listOf(
            OStep(1, stringResource(Res.string.ob_step_install), listOf("curl -fsSL https://raw.githubusercontent.com/heypandax/cc-pocket/main/scripts/install.sh | bash"), stringResource(Res.string.ob_note_linux)),
            OStep(2, stringResource(Res.string.ob_step_pair), listOf("cc-pocket-daemon pair"), stringResource(Res.string.ob_pair_hint), last = true),
        )
        else -> listOf(
            OStep(1, stringResource(Res.string.ob_step_install), listOf("brew install --cask heypandax/tap/cc-pocket"), stringResource(Res.string.ob_note_mac_run)),
            OStep(2, stringResource(Res.string.ob_step_pair), listOf("cc-pocket-daemon pair"), stringResource(Res.string.ob_pair_hint), last = true),
        )
    }

    Column(Modifier.fillMaxSize().background(Tok.base)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ onBack() }) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 12.dp)) {
            Text(stringResource(Res.string.ob_title), color = Tok.tx, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.ob_sub), color = Tok.tx2, fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(20.dp))

            // OS segmented switch
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Tok.surface)
                    .border(1.dp, Tok.hair, RoundedCornerShape(11.dp)).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for (k in listOf("macOS", "Windows", "Linux")) {
                    val on = k == os
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .then(if (on) Modifier.background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable { os = k }.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(k, color = if (on) Tok.tx else Tok.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            Spacer(Modifier.height(22.dp))

            for (s in steps) StepRow(s)
            Spacer(Modifier.height(6.dp))

            // reassuring end-to-end callout
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                    .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).padding(13.dp),
                verticalAlignment = Alignment.Top,
            ) {
                LockGlyph()
                Spacer(Modifier.width(10.dp))
                Text(stringResource(Res.string.ob_secure), color = Tok.tx2, fontSize = 12.5.sp, lineHeight = 19.sp)
            }
        }

        // footer
        Column(Modifier.fillMaxWidth().background(Tok.base)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp)) {
                Button(
                    { onPairNow() },
                    Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Tok.accent, contentColor = Tok.base),
                ) { Text(stringResource(Res.string.ob_pair_now), fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                TextButton({ uri.openUri("https://heypandax.github.io/cc-pocket/") }, Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.ob_guide), color = Tok.tx2, fontSize = 13.5.sp)
                }
            }
        }
    }
}

@Composable
private fun StepRow(s: OStep) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(27.dp).clip(CircleShape).background(Tok.base).border(1.dp, Tok.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(s.n.toString(), color = Tok.accent, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
            if (!s.last) Box(Modifier.width(1.dp).weight(1f).background(Tok.hair))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f).padding(bottom = if (s.last) 0.dp else 22.dp)) {
            Text(s.title, color = Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
            if (s.cmds.isNotEmpty()) CmdCard(s.cmds)
            s.note?.let {
                Spacer(Modifier.height(if (s.cmds.isEmpty()) 4.dp else 9.dp))
                Text(it, color = Tok.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun CmdCard(lines: List<String>) {
    Spacer(Modifier.height(9.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B0C0D))
            .border(1.dp, Tok.hair, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            for (l in lines) Row {
                Text("$", color = Tok.accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp)
                Spacer(Modifier.width(9.dp))
                Text(l, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp, softWrap = false, maxLines = 1)
            }
        }
        Spacer(Modifier.width(8.dp))
        CopyChip(lines.joinToString("\n")) // reuse the Markdown copy affordance
    }
}

/** A small terracotta padlock, drawn (no icon dependency). */
@Composable
private fun LockGlyph() {
    Canvas(Modifier.size(15.dp)) {
        val w = size.width
        val h = size.height
        val st = w * 0.11f
        drawArc( // shackle
            color = Tok.accent, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.3f, h * 0.16f), size = Size(w * 0.4f, h * 0.42f), style = Stroke(st),
        )
        drawRoundRect( // body
            color = Tok.accent, topLeft = Offset(w * 0.18f, h * 0.44f), size = Size(w * 0.64f, h * 0.46f),
            cornerRadius = CornerRadius(w * 0.12f), style = Stroke(st),
        )
    }
}
