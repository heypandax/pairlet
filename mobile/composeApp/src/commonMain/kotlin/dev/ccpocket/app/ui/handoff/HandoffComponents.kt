package dev.ccpocket.app.ui.handoff

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
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
//  UI-facing models — the wiring layer maps protocol types onto these,
//  so every screen below renders without knowing the wire shapes.
// ════════════════════════════════════════════════════════════════════

enum class HandoffUiStatus { WAITING, IN_PROGRESS, RETURNED, COMPLETED, DECLINED, CANCELLED, EXPIRED, RECALLED }

enum class FindingSeverity { HIGH, MEDIUM, LOW }

data class HandoffFindingUi(val severity: FindingSeverity, val title: String, val fileLine: String?)

/** One "Verified" line: [pass] true/false draws ✓/✗, null renders a neutral info row (e.g. files reviewed). */
data class HandoffVerifyUi(val label: String, val detail: String? = null, val pass: Boolean? = null)

data class HandoffBriefSectionUi(val label: String, val text: String? = null, val items: List<String> = emptyList())

data class HandoffResultUi(
    val verdict: String?,
    val findings: List<HandoffFindingUi> = emptyList(),
    val verifications: List<HandoffVerifyUi> = emptyList(),
    val nextSteps: List<String> = emptyList(),
    val returnedByLabel: String,
    val durationLabel: String? = null,
)

data class HandoffHistoryItemUi(
    val id: String,
    val recipientLabel: String,
    val status: HandoffUiStatus,
    val subLine: String,
)

// ── status colour law (design doc header): waiting = neutral · in-progress = terracotta ·
//    returned/completed = green · recalled/expired = amber · declined/cancelled = grey ──

@Composable
fun handoffStatusLabel(s: HandoffUiStatus): String = stringResource(
    when (s) {
        HandoffUiStatus.WAITING -> Res.string.ho_st_waiting
        HandoffUiStatus.IN_PROGRESS -> Res.string.ho_st_in_progress
        HandoffUiStatus.RETURNED -> Res.string.ho_st_returned
        HandoffUiStatus.COMPLETED -> Res.string.ho_st_completed
        HandoffUiStatus.DECLINED -> Res.string.ho_st_declined
        HandoffUiStatus.CANCELLED -> Res.string.ho_st_cancelled
        HandoffUiStatus.EXPIRED -> Res.string.ho_st_expired
        HandoffUiStatus.RECALLED -> Res.string.ho_st_recalled
    },
)

fun handoffStatusColor(s: HandoffUiStatus): Color = when (s) {
    HandoffUiStatus.WAITING -> Tok.tx2
    HandoffUiStatus.IN_PROGRESS -> Tok.accent
    HandoffUiStatus.RETURNED, HandoffUiStatus.COMPLETED -> Tok.ok
    HandoffUiStatus.RECALLED, HandoffUiStatus.EXPIRED -> Tok.warn
    HandoffUiStatus.DECLINED, HandoffUiStatus.CANCELLED -> Tok.muted
}

/** The 1.4s opacity pulse the design gives the one live state (in-progress dot). */
@Composable
fun pulseAlpha(): Float {
    val t = rememberInfiniteTransition(label = "hoPulse")
    val a by t.animateFloat(
        1f, 0.45f,
        infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "hoPulseA",
    )
    return a
}

/**
 * Status chip ("WAITING" mute hairline / "IN PROGRESS" terracotta + pulsing dot / "Completed" green…).
 * 22dp tall, r6, mono 10.5sp — the design's `.chip` recipe.
 */
@Composable
fun HandoffStatusChip(status: HandoffUiStatus, modifier: Modifier = Modifier) {
    val c = handoffStatusColor(status)
    val tinted = status != HandoffUiStatus.WAITING && status != HandoffUiStatus.DECLINED && status != HandoffUiStatus.CANCELLED
    Row(
        modifier.height(22.dp).clip(RoundedCornerShape(6.dp))
            .background(if (tinted) c.copy(alpha = 0.10f) else Tok.raised)
            .border(1.dp, if (tinted) c.copy(alpha = 0.35f) else Tok.hair, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (status == HandoffUiStatus.IN_PROGRESS) {
            Box(Modifier.size(6.dp).alpha(pulseAlpha()).clip(CircleShape).background(Tok.accent))
        }
        Text(
            handoffStatusLabel(status),
            color = if (tinted) c else Tok.tx2,
            fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, fontWeight = FontWeight.Medium,
        )
    }
}

/** Round initial avatar; [accent] fills terracotta (the acting side of a handoff pair). */
@Composable
fun HandoffAvatar(label: String, accent: Boolean, size: androidx.compose.ui.unit.Dp = 20.dp) {
    val initial = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (accent) Tok.accent else Tok.raised)
            .then(if (accent) Modifier else Modifier.border(1.dp, Tok.hair, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = if (accent) Tok.base else Tok.tx2, fontSize = (size.value * 0.48f).sp, fontWeight = FontWeight.SemiBold)
    }
}

/** "P → F" avatar pair on the recipient's ribbon — arrow tinted with the ribbon's colour. */
@Composable
fun HandoffAvatarPair(fromLabel: String, toLabel: String, arrowTint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        HandoffAvatar(fromLabel, accent = false)
        Text("→", color = arrowTint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        HandoffAvatar(toLabel, accent = true)
    }
}

// ════════════════════════════════════════════════════════════════════
//  Trust boundary card — one component, first/second person + honest footer
//  (design: bcard; sibling of share's BoundaryCard but handoff copy)
// ════════════════════════════════════════════════════════════════════

@Composable
private fun BoundaryLine(get: Boolean, text: String, sub: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            if (get) Icons.Rounded.Check else Icons.Rounded.Close, null,
            tint = if (get) Tok.ok else Tok.muted, modifier = Modifier.padding(top = 1.dp).size(16.dp),
        )
        Column {
            Text(text, color = if (get) Tok.tx else Tok.tx2, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (get) FontWeight.SemiBold else FontWeight.Normal)
            if (sub != null) {
                Text(sub, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/**
 * The hero of both trust screens. [secondPerson] false = initiator draft ("They will see"),
 * true = recipient accept preview ("You will see"), with the honest footer swapped to the
 * recall/recording statement. [roots] renders as the mono path list under the roots line.
 */
@Composable
fun HandoffBoundaryCard(secondPerson: Boolean, roots: List<String>, ownerLabel: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text(
                stringResource(if (secondPerson) Res.string.ho_b_see2 else Res.string.ho_b_see).uppercase(),
                color = Tok.ok, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
            )
            BoundaryLine(
                true,
                if (secondPerson) stringResource(Res.string.ho_b_see_transcript2, ownerLabel) else stringResource(Res.string.ho_b_see_transcript),
                stringResource(Res.string.ho_b_see_transcript_sub),
            )
            BoundaryLine(true, stringResource(Res.string.ho_b_see_roots), roots.joinToString("\n").ifBlank { null })
            BoundaryLine(true, stringResource(Res.string.ho_b_see_tools), stringResource(Res.string.ho_b_see_tools_sub))
            // §2.2: shell is NOT blocked in REVIEW — it is asked for, one command at a time, and recorded.
            // Leaving it off this list is what made "READ ONLY" a boundary lie on both trust screens.
            BoundaryLine(true, stringResource(Res.string.ho_b_see_cmds), stringResource(Res.string.ho_b_see_cmds_sub))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text(
                stringResource(if (secondPerson) Res.string.ho_b_cant2 else Res.string.ho_b_cant).uppercase(),
                color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
            )
            // the sub names WHICH tools are hard-refused, so "can't edit files" isn't read as "can't
            // possibly change a file" — an approved command still can, which the footer says outright
            BoundaryLine(
                false,
                stringResource(if (secondPerson) Res.string.ho_b_cant_edit2 else Res.string.ho_b_cant_edit),
                stringResource(Res.string.ho_b_cant_edit_sub),
            )
            BoundaryLine(false, if (secondPerson) stringResource(Res.string.ho_b_cant_other2, ownerLabel) else stringResource(Res.string.ho_b_cant_other))
            BoundaryLine(false, stringResource(Res.string.ho_b_cant_settings))
            BoundaryLine(false, stringResource(Res.string.ho_b_cant_pass))
        }
        Row(
            Modifier.fillMaxWidth().background(Tok.raised).padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Warning, null, tint = Tok.warn, modifier = Modifier.padding(top = 1.dp).size(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (secondPerson) stringResource(Res.string.ho_honest_recipient, ownerLabel) else stringResource(Res.string.ho_honest_owner),
                    color = Tok.tx2, fontSize = 12.5.sp, lineHeight = 18.sp,
                )
                // the one sentence the whole §2.2 rewrite exists for — said on BOTH trust screens
                Text(stringResource(Res.string.ho_honest_shell), color = Tok.tx2, fontSize = 12.5.sp, lineHeight = 18.sp)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  Session facts card (design: sess) — title + agent chip + mono path · branch
// ════════════════════════════════════════════════════════════════════

@Composable
fun HandoffSessionFactsCard(title: String, path: String, branch: String?, agentLabel: String?, leading: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            leading?.invoke()
            Text(title, color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            if (agentLabel != null) {
                Text(
                    agentLabel, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Tok.raised).border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(path, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (branch != null) {
                Text("·", color = Tok.hair, fontSize = 11.5.sp)
                Text("⑂ $branch", color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, maxLines = 1)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  Findings / verification rows + the result card (design: rescard)
// ════════════════════════════════════════════════════════════════════

fun severityColor(s: FindingSeverity): Color = when (s) {
    FindingSeverity.HIGH -> Tok.danger
    FindingSeverity.MEDIUM -> Tok.warn
    FindingSeverity.LOW -> Tok.tx2
}

@Composable
fun FindingRow(f: HandoffFindingUi) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 5.dp).size(7.dp).clip(CircleShape).background(severityColor(f.severity)))
        Column {
            Text(f.title, color = Tok.tx, fontSize = 13.sp, lineHeight = 18.sp)
            if (f.fileLine != null) {
                Text(f.fileLine, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
fun VerifyRow(v: HandoffVerifyUi) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (v.pass) {
            true -> Icon(Icons.Rounded.Check, null, tint = Tok.ok, modifier = Modifier.size(14.dp))
            false -> Icon(Icons.Rounded.Close, null, tint = Tok.danger, modifier = Modifier.size(14.dp))
            null -> Box(Modifier.size(14.dp))
        }
        Text(v.label, color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
        if (v.detail != null) {
            Text(
                v.detail,
                color = when (v.pass) { true -> Tok.ok; false -> Tok.danger; null -> Tok.tx2 },
                fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
            )
        }
    }
}

/** Verdict chip — amber = attention (Approve with fixes), not failure; large size in the return sheet. */
@Composable
fun VerdictChip(text: String, selected: Boolean = true, large: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = if (selected) Tok.warn else Tok.tx2
    Text(
        text,
        color = if (selected) c else Tok.muted,
        fontFamily = FontFamily.Monospace, fontSize = if (large) 11.5.sp else 10.5.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (selected) c.copy(alpha = 0.10f) else Tok.raised)
            .border(1.dp, if (selected) c.copy(alpha = 0.35f) else Tok.hair, RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (large) 10.dp else 8.dp, vertical = if (large) 5.dp else 3.dp),
    )
}

/**
 * The in-stream result card (Frame 9 mobile / Frame 12 desktop): header "Frank returned this session"
 * + verdict chip; body previews [previewCount] findings + "view all", then mono verification lines;
 * footer actions Mark reviewed / Open full result. [twoColumn] lays findings in a grid (desktop ≥820dp).
 */
@Composable
fun HandoffResultCard(
    result: HandoffResultUi,
    previewCount: Int = 2,
    twoColumn: Boolean = false,
    onMarkReviewed: (() -> Unit)?,
    onOpenFull: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(14.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().background(Tok.raised).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            HandoffAvatar(result.returnedByLabel, accent = true)
            Text(
                stringResource(Res.string.ho_returned_title, result.returnedByLabel),
                color = Tok.tx, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f),
            )
            result.verdict?.let { VerdictChip(it) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            val shown = result.findings.take(if (twoColumn) result.findings.size else previewCount)
            if (twoColumn && shown.isNotEmpty()) {
                Text(
                    stringResource(Res.string.ho_findings_n, result.findings.size).uppercase(),
                    color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 0.8.sp,
                )
                // two-up grid: chunk rows of 2 (design findgrid)
                shown.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        pair.forEach { Box(Modifier.weight(1f)) { FindingRow(it) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                shown.forEach { FindingRow(it) }
                if (result.findings.size > previewCount) {
                    Text(
                        stringResource(Res.string.ho_view_all, result.findings.size),
                        color = Tok.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable(onClick = onOpenFull),
                    )
                }
            }
            if (result.verifications.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                result.verifications.forEach { VerifyRow(it) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onMarkReviewed != null) {
                Text(
                    stringResource(Res.string.ho_mark_reviewed), color = Tok.base, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Tok.accent).clickable(onClick = onMarkReviewed)
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                )
            }
            Text(
                stringResource(Res.string.ho_open_full), color = Tok.tx, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).clickable(onClick = onOpenFull)
                    .padding(horizontal = 13.dp, vertical = 11.dp),
            )
            Spacer(Modifier.weight(1f))
            result.durationLabel?.let {
                Text(it, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  History rows (Frame 10 / 10b)
// ════════════════════════════════════════════════════════════════════

@Composable
fun HandoffHistoryRow(item: HandoffHistoryItemUi, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        HandoffAvatar(item.recipientLabel, accent = item.status == HandoffUiStatus.IN_PROGRESS)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.recipientLabel, color = Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    stringResource(Res.string.ho_role_review), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(item.subLine, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        }
        HandoffStatusChip(item.status)
        Text("›", color = Tok.muted, fontSize = 14.sp)
    }
}

/** The "Handoffs" group inside the session info sheet — rows in a bordered group box, or the empty hint. */
@Composable
fun HandoffHistorySection(items: List<HandoffHistoryItemUi>, onOpen: (HandoffHistoryItemUi) -> Unit, onHandoff: () -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.ho_history_title).uppercase(),
                color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
            )
            if (items.isNotEmpty()) {
                Text(items.size.toString(), color = Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp)
            }
        }
        Column(
            Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp)),
        ) {
            if (items.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(Res.string.ho_history_empty), color = Tok.muted, fontSize = 13.sp, lineHeight = 20.sp)
                    Text(
                        stringResource(Res.string.ho_handoff_action), color = Tok.tx2, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Tok.hair, RoundedCornerShape(10.dp))
                            .clickable(onClick = onHandoff).padding(horizontal = 13.dp, vertical = 10.dp),
                    )
                }
            } else {
                items.forEachIndexed { i, item ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                    HandoffHistoryRow(item) { onOpen(item) }
                }
            }
        }
    }
}
