package dev.ccpocket.app.ui.approval

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.SystemBackHandler
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.ap_assessed
import dev.ccpocket.app.resources.ap_authority_wait_sub
import dev.ccpocket.app.resources.ap_authority_wait_title
import dev.ccpocket.app.resources.ap_fail_closed
import dev.ccpocket.app.resources.ap_kind
import dev.ccpocket.app.resources.ap_legacy_note
import dev.ccpocket.app.resources.ap_project
import dev.ccpocket.app.resources.ap_queue
import dev.ccpocket.app.resources.ap_recorded_title
import dev.ccpocket.app.resources.ap_required
import dev.ccpocket.app.resources.ap_waiting_sub
import dev.ccpocket.app.resources.ap_waiting_title
import dev.ccpocket.app.resources.diff_more_lines
import dev.ccpocket.app.resources.ho_bash_recorded
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import dev.ccpocket.app.ui.MarkdownText
import dev.ccpocket.app.ui.RiskBadge
import dev.ccpocket.app.ui.relativeTime
import dev.ccpocket.app.ui.tilde
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * The Secure Approval surface (Mobile UI 2.0 · Approval Protocol Handoff v1).
 *
 * NOT a `PocketSheet`. An approval is the one place where a stray tap must not be able to answer for the
 * user, so this shell owns a security presentation contract instead of a convenience one:
 *
 *  - the scrim swallows taps and resolves nothing; system back is intercepted and does nothing;
 *  - no grabber and — by construction, since nothing anchors or drags the surface — no swipe dismissal;
 *  - one stable 402×874-relative shell across states, with three zones: pinned header, scrolling body,
 *    pinned decisions. A long command, diff or path grows the BODY; it never pushes a decision off-screen.
 *
 * Only three things end it: an explicit decision, an authoritative withdrawal/session close (the host stops
 * passing an ask), or the daemon's `AskWithdrawn(TIMED_OUT)` → [ApprovalUi.timedOutSignal], the single state
 * in which [onDismiss] exists at all.
 *
 * The renderer reads [ui] and nothing else — no repository, no client-derived facts.
 */
@Composable
fun SecureApprovalSheet(
    ui: ApprovalUi,
    onDeny: () -> Unit,
    onAllowOnce: () -> Unit,
    onAllowTask: () -> Unit = {},
    onAllowSession: () -> Unit = {},
    onAlwaysAllow: () -> Unit = {},
    onRetrySafer: (List<String>) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    // keyed by the COMPOSITE identity: askId alone is only unique per agent connection, so two sessions
    // both asking as "1" must not share a countdown, a Retry-safer draft or a More-options disclosure
    val askKey = ui.ask.convoId to ui.ask.askId
    // a decision needs no typing: drop the keyboard the composer may still hold, or its inset and the
    // sheet's imePadding fight over the bottom and wedge the pinned decisions out of reach
    val focus = LocalFocusManager.current
    LaunchedEffect(askKey) { focus.clearFocus() }
    // back resolves nothing — deliberately an empty handler, not an absent one
    SystemBackHandler(enabled = true) { }

    // A re-emitted ask keeps its composite identity. Key the display clock by the authoritative window as
    // well, so a renewed/updated timeout restarts the display without letting an unrelated risk update do so.
    var seconds by remember(askKey, ui.timer) {
        mutableStateOf((ui.timer as? ApprovalTimer.Countdown)?.totalSec ?: 0)
    }
    LaunchedEffect(askKey, ui.timer) {
        val t = ui.timer as? ApprovalTimer.Countdown ?: return@LaunchedEffect
        seconds = t.totalSec
        while (seconds > 0) { delay(1000); seconds -= 1 }
    }
    val terminal = ui.isTerminal(seconds)

    var safer by remember(askKey) { mutableStateOf(false) }
    val constraints = remember(askKey) { mutableStateListOf<String>() }
    var custom by remember(askKey) { mutableStateOf("") }

    Box(Modifier.fillMaxSize().semantics { dialog() }) {
        // consumes the tap so nothing behind reacts — and answers nothing itself
        Box(Modifier.fillMaxSize().background(SCRIM).pointerInput(Unit) { detectTapGestures { } })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(APPROVAL_SHEET_HEIGHT_FRACTION)
                .clip(RoundedCornerShape(topStart = Metric.radiusSheet, topEnd = Metric.radiusSheet))
                .background(Tok.raised)
                .pointerInput(Unit) { detectTapGestures { } } // taps on the sheet never reach the scrim
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding() // the sheet renders outside the app's ime-padded root
                .padding(bottom = Metric.gap),
        ) {
            ApprovalHeader(ui, seconds, terminal)
            Hairline()
            // The body takes whatever the two pinned zones leave. `fill = true` keeps the shell height stable
            // across ordinary/legacy/one-off states; only this middle zone expands and scrolls.
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = Metric.gutter).padding(vertical = Metric.gapL)
                    .alpha(if (terminal) 0.55f else 1f),
            ) {
                if (safer) RetrySaferBody(constraints, custom) { custom = it } else ApprovalBody(ui)
            }
            Hairline()
            Column(Modifier.padding(horizontal = Metric.gutter).padding(top = Metric.gap)) {
                when {
                    terminal -> ApprovalTimeoutTerminal(onDismiss)
                    safer -> RetrySaferDecisions(
                        enabled = constraints.isNotEmpty() || custom.isNotBlank(),
                        onBack = { safer = false },
                        onSend = { onRetrySafer(constraints.toList() + listOfNotNull(custom.trim().takeIf { it.isNotBlank() })) },
                    )
                    else -> ApprovalDecisions(
                        ui,
                        onAction = {
                            when (it) {
                                ApprovalActionId.DENY -> onDeny()
                                ApprovalActionId.ALLOW_ONCE -> onAllowOnce()
                                ApprovalActionId.ALLOW_TASK -> onAllowTask()
                                ApprovalActionId.ALLOW_SESSION -> onAllowSession()
                                ApprovalActionId.ALWAYS_ALLOW -> onAlwaysAllow()
                                ApprovalActionId.RETRY_SAFER -> safer = true
                            }
                        },
                        onOpenSafer = { safer = true },
                    )
                }
            }
        }
    }
}

private val SCRIM = Color(0x94000000)
private val APPROVAL_WARN_LIGHT = Color(0xFF7A4F07)
private const val APPROVAL_SHEET_HEIGHT_FRACTION = 0.945f

// ── zone A · pinned header ───────────────────────────────────────────────────────────────────────

@Composable
private fun ApprovalHeader(ui: ApprovalUi, seconds: Int, terminal: Boolean) {
    // The shared light warning token is suitable for large decorative marks but misses 4.5:1 as body text
    // on this raised surface. The handoff pins the darker approval-specific ink for the label/countdown.
    val accent = if (ui.ask.danger) Tok.danger else if (Tok.current.dark) Tok.warn else APPROVAL_WARN_LIGHT
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Metric.gutter)
            .padding(top = Metric.gapL, bottom = Metric.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metric.gapS)) {
            // a diamond for warning, a square for danger: the states differ by SHAPE, not only by hue
            Box(Modifier.size(8.dp).rotate(if (ui.ask.danger) 0f else 45f).background(accent))
            Text(
                stringResource(Res.string.ap_required), color = accent,
                style = TypeRole.action, modifier = Modifier.weight(1f),
            )
            // never a fabricated "1 of 1": the counter exists only while a burst really is queued
            ui.queue?.let { (pos, total) ->
                Text(stringResource(Res.string.ap_queue, pos, total), color = Tok.tx2, style = TypeRole.captionMono)
            }
        }
        if (terminal) return@Column
        when (ui.timer) {
            // #201: the daemon RENEWS this window instead of expiring it. No ring, no number, no "∞" — an
            // infinity glyph would promise a forever the bounded renewal chain does not have.
            is ApprovalTimer.Waiting -> {
                Text(
                    stringResource(Res.string.ap_waiting_title), color = Tok.tx,
                    style = TypeRole.body.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(top = Metric.gapS),
                )
                Text(
                    stringResource(Res.string.ap_waiting_sub), color = Tok.tx2, style = TypeRole.caption,
                    modifier = Modifier.padding(top = Metric.gapXs),
                )
            }
            is ApprovalTimer.Countdown -> {
                // A grant-aware daemon may pause its own budget while this card holds an AttentionLease.
                // Local zero is therefore only a display floor: keep the actions available, remove the
                // false auto-deny claim, and wait for an authoritative renewal/withdrawal.
                if (seconds <= 0 && ui.ask.grantOptions != null) {
                    Text(
                        stringResource(Res.string.ap_authority_wait_title), color = Tok.tx,
                        style = TypeRole.body.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(top = Metric.gapS),
                    )
                    Text(
                        stringResource(Res.string.ap_authority_wait_sub), color = Tok.tx2,
                        style = TypeRole.caption, modifier = Modifier.padding(top = Metric.gapXs),
                    )
                } else {
                    val tint = if (seconds <= 5) Tok.danger else accent
                    Row(
                        Modifier.padding(top = Metric.gapS),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Metric.gapS),
                    ) {
                        Text("${seconds}s", color = tint, style = TypeRole.action.copy(fontFamily = FontFamily.Monospace))
                        Text(
                            stringResource(Res.string.ap_fail_closed), color = Tok.tx2, style = TypeRole.caption,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    CountdownBar(seconds, ui.timer.totalSec, tint)
                }
            }
        }
    }
}

/** Linear determinate remaining-time bar — left-anchored remaining/total. A display floor, not a verdict. */
@Composable
private fun CountdownBar(seconds: Int, total: Int, color: Color) {
    val frac = if (total <= 0) 0f else (seconds.toFloat() / total).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(2.dp)
    Box(Modifier.padding(top = Metric.gapS).fillMaxWidth().height(3.dp).clip(shape).background(Tok.hair)) {
        Box(Modifier.fillMaxWidth(frac).height(3.dp).clip(shape).background(color))
    }
}

// ── zone B · scrolling body ──────────────────────────────────────────────────────────────────────

@Composable
private fun ApprovalBody(ui: ApprovalUi) {
    val ask = ui.ask
    val title = ask.title.trim()
    if (title.isNotEmpty()) {
        Text(title, color = Tok.tx, style = TypeRole.title)
        KindToolLine(ask.tool, promoted = false, modifier = Modifier.padding(top = Metric.gap))
    } else {
        // no title from the daemon → promote the fixed kind/tool line rather than invent a heading
        KindToolLine(ask.tool, promoted = true)
    }
    ApprovalEvidence(ui)
    ApprovalPayload(ui)
    if (ui.recordedShell) RecordedBand()
    ui.workdir?.let { ProjectRow(it) }
    if (ui.family == ApprovalFamily.LEGACY) {
        Text(
            stringResource(Res.string.ap_legacy_note), color = Tok.tx2, style = TypeRole.caption,
            modifier = Modifier.padding(top = Metric.gapL),
        )
    }
}

/** The fixed request kind plus the literal tool name — never a summary of what the command will do. */
@Composable
private fun KindToolLine(tool: String, promoted: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metric.gapS)) {
        Text(
            stringResource(Res.string.ap_kind), color = if (promoted) Tok.tx else Tok.tx2,
            style = if (promoted) TypeRole.title else TypeRole.body,
        )
        Box(Modifier.size(3.dp).clip(CircleShape).background(Tok.muted))
        Text(
            tool, color = Tok.tx,
            style = if (promoted) TypeRole.title.copy(fontFamily = FontFamily.Monospace) else TypeRole.bodyMono,
        )
    }
}

/**
 * Risk evidence and the danger explanation. Absent risk draws NOTHING — not a placeholder, not a "low" or
 * "safe" mark: failing to assess and assessing as safe are different facts. A late risk update fills this
 * in without touching the header timing or the decision family.
 */
@Composable
private fun ApprovalEvidence(ui: ApprovalUi) {
    val risk = ui.risk
    val note = ui.ask.dangerNote?.takeIf { it.isNotBlank() }
    if (risk == null && note == null) return
    val assessed = risk?.assessedAt?.let { stringResource(Res.string.ap_assessed, relativeTime(it)) }
    val meta = (listOfNotNull(assessed) + risk?.reasonCodes.orEmpty().filter { it.isNotBlank() }).joinToString(" · ")
    val shape = RoundedCornerShape(Metric.radiusS)
    Column(
        Modifier.padding(top = Metric.gapL).fillMaxWidth()
            // a flagged danger gets a bounded block, so the sentence explaining it cannot be scrolled past
            .then(
                if (ui.ask.danger) {
                    Modifier.clip(shape).background(Tok.danger.copy(alpha = 0.10f))
                        .border(Metric.hairline, Tok.danger.copy(alpha = 0.42f), shape).padding(Metric.gap)
                } else {
                    Modifier
                },
            ),
    ) {
        risk?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metric.gapS)) {
                RiskBadge(it.risk)
                it.reason?.takeIf { r -> r.isNotBlank() }?.let { r -> Text(r, color = Tok.tx2, style = TypeRole.body) }
            }
        }
        note?.let {
            Text(
                it, color = Tok.tx2, style = TypeRole.body,
                modifier = Modifier.padding(top = if (risk == null) 0.dp else Metric.gapS),
            )
        }
        if (meta.isNotEmpty()) {
            Text(meta, color = Tok.tx2, style = TypeRole.captionMono, modifier = Modifier.padding(top = Metric.gapS))
        }
    }
}

/** The literal payload: the daemon's `diff` when it sent one, otherwise its `inputPreview`, verbatim. */
@Composable
private fun ApprovalPayload(ui: ApprovalUi) {
    val ask = ui.ask
    val shape = RoundedCornerShape(Metric.radiusS)
    val diff = ask.diff
    when {
        !diff.isNullOrEmpty() -> DiffPayload(diff)
        ask.tool == "ExitPlanMode" || ask.tool == "exit_plan_mode" ->
            // a plan approval's inputPreview IS the plan — rendered as the markdown it is, not paraphrased
            Column(
                Modifier.padding(top = Metric.gapL).fillMaxWidth().clip(shape).background(Tok.base)
                    .border(Metric.hairline, Tok.hair, shape).padding(Metric.gap),
            ) { MarkdownText(ask.inputPreview, Tok.tx) }
        ask.inputPreview.isNotEmpty() ->
            Box(
                Modifier.padding(top = Metric.gapL).fillMaxWidth().clip(shape).background(Tok.base)
                    .border(Metric.hairline, Tok.hair, shape).padding(Metric.gap),
            ) {
                // wraps instead of clipping: half a command is a misleading command
                Text(ask.inputPreview, color = Tok.tx, style = TypeRole.bodyMono.copy(lineHeight = 21.sp))
            }
    }
}

/** Unified diff as +/- rows. Wraps rather than clipping; the honest count states what was left out. */
@Composable
private fun DiffPayload(diff: String) {
    val all = remember(diff) { diff.lines() }
    val shown = remember(diff) { all.take(DIFF_MAX_LINES) }
    val shape = RoundedCornerShape(Metric.radiusS)
    Column(
        Modifier.padding(top = Metric.gapL).fillMaxWidth().clip(shape).background(Tok.base)
            .border(Metric.hairline, Tok.hair, shape).padding(vertical = Metric.gapS),
    ) {
        shown.forEach { ln ->
            val sign = ln.firstOrNull()
            val bg = when (sign) { '+' -> Tok.ok.copy(alpha = 0.12f); '-' -> Tok.danger.copy(alpha = 0.12f); else -> Color.Transparent }
            val col = when (sign) { '+' -> Tok.ok; '-' -> Tok.danger; else -> Tok.tx2 }
            Text(
                ln.ifEmpty { " " }, color = col, style = TypeRole.captionMono.copy(lineHeight = 18.sp),
                modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = Metric.gap),
            )
        }
        if (all.size > shown.size) {
            Text(
                stringResource(Res.string.diff_more_lines, all.size - shown.size), color = Tok.tx2,
                style = TypeRole.captionMono,
                modifier = Modifier.padding(horizontal = Metric.gap, vertical = Metric.gapXs),
            )
        }
    }
}

private const val DIFF_MAX_LINES = 140

/** §2.2/§4.3: a shell command inside a REVIEW handoff is confirmed on its own and leaves a record. Lives in
 *  the BODY next to the evidence — beside the buttons it would read as advice about which one to press. */
@Composable
private fun RecordedBand() {
    Row(Modifier.padding(top = Metric.gapL).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metric.gapS)) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).border(1.5.dp, Tok.tx2, CircleShape))
        Column {
            Text(stringResource(Res.string.ap_recorded_title), color = Tok.tx, style = TypeRole.body.copy(fontWeight = FontWeight.Medium))
            Text(stringResource(Res.string.ho_bash_recorded), color = Tok.tx2, style = TypeRole.caption, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** The session's real working directory. No workdir → no row; never substituted with a repo name. */
@Composable
private fun ProjectRow(workdir: String) {
    Column(Modifier.padding(top = Metric.gapL).fillMaxWidth()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().padding(vertical = Metric.gap),
            horizontalArrangement = Arrangement.spacedBy(Metric.gapL),
        ) {
            Text(stringResource(Res.string.ap_project), color = Tok.tx2, style = TypeRole.body)
            Text(
                tilde(workdir), color = Tok.tx, style = TypeRole.captionMono, textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        Hairline()
    }
}

@Composable
private fun Hairline() = Box(Modifier.fillMaxWidth().height(Metric.hairline).background(Tok.hair))
