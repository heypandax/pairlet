package dev.ccpocket.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.PRIVACY_POLICY_URL
import dev.ccpocket.app.openWebUrl
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import dev.ccpocket.app.theme.tightCenter
import dev.ccpocket.app.ui.entry.EntryPrimaryButton
import dev.ccpocket.app.ui.entry.EntryQuietAction
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One-time data disclosure shown before ANYTHING else renders — pairing, Demo mode, and every
 * content screen sit behind it (App Review guideline 5.1.2(i): disclose what is sent and to whom,
 * and obtain permission, BEFORE any personal data can leave the device). Acceptance is persisted
 * via [dev.ccpocket.app.data.PocketRepository.acceptPrivacyConsent]; the linked policy stays
 * reachable afterwards from Settings.
 *
 * Visuals follow the claude.ai/design handoff "Privacy Disclosure Gate v1"
 * (docs/design/claude-design-handoff/privacy-disclosure-gate/): phone ⇢ encrypted ⇢ computer
 * diagram card, four hairline-separated points with 20dp line glyphs, docked action bar that
 * owns the home-indicator inset. All colors come from [Tok] so light mode follows for free.
 */
@Composable
fun PrivacyConsentScreen(onAgree: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Tok.base) {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(Res.string.privacy_gate_title),
                    color = Tok.tx,
                    style = TypeRole.screenTitle.copy(fontSize = 30.sp, lineHeight = 34.sp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(Res.string.privacy_gate_intro),
                    color = Tok.tx2,
                    style = TypeRole.preview.copy(fontSize = 15.sp, lineHeight = 22.sp),
                )
                Spacer(Modifier.height(22.dp))
                FlowDiagramCard()
                Spacer(Modifier.height(8.dp))
                ConsentPoint(Res.string.privacy_gate_what_title, Res.string.privacy_gate_what_body) { WhatGlyph() }
                PointDivider()
                ConsentPoint(Res.string.privacy_gate_where_title, Res.string.privacy_gate_where_body) { ComputerGlyph() }
                PointDivider()
                ConsentPoint(Res.string.privacy_gate_who_title, Res.string.privacy_gate_who_body) { TerminalGlyph() }
                PointDivider()
                ConsentPoint(Res.string.privacy_gate_voice_title, Res.string.privacy_gate_voice_body) { WaveGlyph() }
                PointDivider()
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(Res.string.privacy_gate_demo),
                    color = Tok.muted,
                    style = TypeRole.caption.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                )
                Spacer(Modifier.height(20.dp))
            }
            // Docked action bar: hairline on top, and per the full-bleed convention the bottom-most
            // surface consumes the nav-bar / home-indicator band itself.
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
                Spacer(Modifier.height(4.dp))
                EntryQuietAction(stringResource(Res.string.privacy_gate_policy)) { openWebUrl(PRIVACY_POLICY_URL) }
                Spacer(Modifier.height(4.dp))
                EntryPrimaryButton(stringResource(Res.string.privacy_gate_agree), onClick = onAgree)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** phone ⇢ [lock] ⇢ your computer — the whole security story in one glance (handoff §diagram). */
@Composable
private fun FlowDiagramCard() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhoneGlyph()
            Column(
                Modifier.weight(1f).padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DashedLine(Modifier.weight(1f))
                    Spacer(Modifier.width(7.dp))
                    LockGlyph()
                    Spacer(Modifier.width(7.dp))
                    DashedLine(Modifier.weight(1f))
                    ArrowHead()
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    stringResource(Res.string.privacy_gate_diagram_e2e),
                    color = Tok.accent,
                    style = tightCenter(10.5.sp).copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.6.sp),
                )
            }
            ComputerGlyph(size = 44.dp to 34.dp)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.privacy_gate_diagram_phone),
                color = Tok.muted, style = tightCenter(11.sp).copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                stringResource(Res.string.privacy_gate_diagram_relay),
                color = Tok.muted, style = tightCenter(11.sp).copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                stringResource(Res.string.privacy_gate_diagram_computer),
                color = Tok.muted, style = tightCenter(11.sp).copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun ConsentPoint(title: StringResource, body: StringResource, glyph: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Box(Modifier.padding(top = 2.dp).size(20.dp)) { glyph() }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(title),
                color = Tok.tx,
                style = TypeRole.rowTitle.copy(fontSize = 15.sp),
            )
            Spacer(Modifier.height(5.dp))
            Text(
                stringResource(body),
                color = Tok.tx2,
                style = TypeRole.preview.copy(fontSize = 13.5.sp, lineHeight = 20.sp),
            )
        }
    }
}

@Composable
private fun PointDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))

// ── 1.4dp line glyphs, 20×20 grid, stroked in Tok.tx2 (handoff icon set) ────────────────────────

private fun DrawScope.strokeWidthPx() = 1.4.dp.toPx()

/** Sheet-of-paper: rounded rect with a squared-off bottom-left corner. */
@Composable
private fun WhatGlyph() = Canvas(Modifier.fillMaxSize()) {
    val r = 4.dp.toPx()
    val p = Path().apply {
        addRoundRect(
            RoundRect(
                left = strokeWidthPx() / 2, top = strokeWidthPx() / 2,
                right = size.width - strokeWidthPx() / 2, bottom = size.height - strokeWidthPx() / 2,
                topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                bottomRightCornerRadius = CornerRadius(r), bottomLeftCornerRadius = CornerRadius.Zero,
            ),
        )
    }
    drawPath(p, Tok.tx2, style = Stroke(strokeWidthPx()))
}

/** Monitor + stand foot. Default 20×20 for the points list; the diagram passes a wider size. */
@Composable
private fun ComputerGlyph(size: Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp>? = null) {
    val mod = if (size != null) Modifier.width(size.first).height(size.second) else Modifier.fillMaxSize()
    Canvas(mod) {
        val sw = strokeWidthPx()
        val screenH = this.size.height * 0.62f
        drawRoundRect(
            Tok.tx2, topLeft = Offset(sw / 2, this.size.height - screenH),
            size = Size(this.size.width - sw, screenH - sw / 2),
            cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(sw),
        )
        drawRoundRect(
            Tok.tx2, topLeft = Offset(this.size.width * 0.25f, 0f),
            size = Size(this.size.width * 0.5f, this.size.height * 0.34f),
            cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(sw),
        )
    }
}

/** `>_` in a rounded box — the agent CLI. tightCenter: mono text centered in a geometric box. */
@Composable
private fun TerminalGlyph() {
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))
            .border(1.4.dp, Tok.tx2, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ">_", color = Tok.tx2,
            style = tightCenter(9.sp).copy(fontFamily = FontFamily.Monospace),
        )
    }
}

/** Five waveform bars. */
@Composable
private fun WaveGlyph() = Canvas(Modifier.fillMaxSize()) {
    val heights = listOf(0.4f, 0.7f, 0.9f, 0.5f, 0.3f)
    val barW = 1.6.dp.toPx()
    val gap = 2.5.dp.toPx()
    val total = heights.size * barW + (heights.size - 1) * gap
    var x = (size.width - total) / 2
    heights.forEach { h ->
        val barH = size.height * h
        drawRoundRect(
            Tok.tx2, topLeft = Offset(x, (size.height - barH) / 2), size = Size(barW, barH),
            cornerRadius = CornerRadius(barW / 2),
        )
        x += barW + gap
    }
}

/** Phone outline with a home line. */
@Composable
private fun PhoneGlyph() = Canvas(Modifier.width(26.dp).height(40.dp)) {
    val sw = strokeWidthPx()
    drawRoundRect(
        Tok.tx2, topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
        cornerRadius = CornerRadius(5.dp.toPx()), style = Stroke(sw),
    )
    drawLine(
        Tok.tx2, Offset(size.width / 2 - 5.dp.toPx(), size.height - 5.dp.toPx()),
        Offset(size.width / 2 + 5.dp.toPx(), size.height - 5.dp.toPx()), strokeWidth = sw,
    )
}

/** Padlock in the accent color — the payload is sealed before it leaves. */
@Composable
private fun LockGlyph() = Canvas(Modifier.width(15.dp).height(19.dp)) {
    val sw = strokeWidthPx()
    val bodyTop = size.height - 13.dp.toPx()
    drawRoundRect(
        Tok.accent, topLeft = Offset(sw / 2, bodyTop), size = Size(size.width - sw, size.height - bodyTop - sw / 2),
        cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(sw),
    )
    // shackle: an open-bottomed arc over the body
    drawArc(
        Tok.accent, startAngle = 180f, sweepAngle = 180f, useCenter = false,
        topLeft = Offset(size.width / 2 - 4.5.dp.toPx(), sw / 2),
        size = Size(9.dp.toPx(), 12.dp.toPx()), style = Stroke(sw),
    )
}

@Composable
private fun DashedLine(modifier: Modifier) = Canvas(modifier.height(2.dp)) {
    drawLine(
        Tok.tx2.copy(alpha = 0.5f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
        strokeWidth = 1.4.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
    )
}

@Composable
private fun ArrowHead() = Canvas(Modifier.width(8.dp).height(10.dp)) {
    val p = Path().apply {
        moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height); close()
    }
    drawPath(p, Tok.tx2.copy(alpha = 0.6f))
}
