package dev.ccpocket.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.cancel
import dev.ccpocket.app.resources.rewind_action_fork
import dev.ccpocket.app.resources.rewind_action_rewind
import dev.ccpocket.app.resources.rewind_confirm_fork_title
import dev.ccpocket.app.resources.rewind_confirm_rewind_title
import dev.ccpocket.app.resources.rewind_counting
import dev.ccpocket.app.resources.rewind_err_external
import dev.ccpocket.app.resources.rewind_err_first
import dev.ccpocket.app.resources.rewind_err_generic
import dev.ccpocket.app.resources.rewind_err_not_idle
import dev.ccpocket.app.resources.rewind_err_stale
import dev.ccpocket.app.resources.rewind_err_unsupported
import dev.ccpocket.app.resources.rewind_files_warning_lead
import dev.ccpocket.app.resources.rewind_files_warning_rest
import dev.ccpocket.app.resources.rewind_lineage_fork
import dev.ccpocket.app.resources.rewind_lineage_rewound
import dev.ccpocket.app.resources.rewind_menu_blocked
import dev.ccpocket.app.resources.rewind_menu_fork
import dev.ccpocket.app.resources.rewind_menu_fork_sub
import dev.ccpocket.app.resources.rewind_menu_rewind
import dev.ccpocket.app.resources.rewind_menu_rewind_sub
import dev.ccpocket.app.resources.rewind_mode_note_fork
import dev.ccpocket.app.resources.rewind_mode_note_rewind
import dev.ccpocket.app.resources.rewind_tool_calls
import dev.ccpocket.app.resources.rewind_turns
import dev.ccpocket.app.resources.rewind_will_drop
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.RewindMode
import dev.ccpocket.protocol.RewindRefusal
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Session rewind / fork surfaces (issue #282, board `rewind-fork-282`).
 *
 * Four states in the design, three composables here: the long-press menu rows (A), the dry-run
 * confirmation body (B), the lineage banner the branch wears (C) and the stale-anchor bar. The session
 * list's collapsed "rewound" group (D) is pure list math and lives in `SessionGroupUi`.
 *
 * Everything is a body — the sheet CHROME stays with whichever host wraps it (`PocketSheet` on mobile,
 * a popup on desktop) so the two platforms share the content and not the presentation.
 */

/** The design's glyph vocabulary: ↩ is a rewind, ⑂ is a fork. Used in the menu, the mode note, the
 *  lineage banner and the list captions, so the same mark means the same thing everywhere. */
internal fun rewindGlyph(mode: String): String = if (mode == RewindMode.FORK) "⑂" else "↩"

/**
 * The two rows a user message's long-press menu gains. [enabled] false renders the greyed state with
 * the reason strip underneath (design frame A/menuDisabled) — shown rather than hidden, because a
 * disappearing menu item reads as "this build can't do it" while a greyed one with a reason reads as
 * "not right now", which is the truth.
 */
@Composable
internal fun RewindMenuItems(enabled: Boolean, onPick: (String) -> Unit) {
    RewindMenuRow(RewindMode.REWIND, stringResource(Res.string.rewind_menu_rewind), stringResource(Res.string.rewind_menu_rewind_sub), enabled, onPick)
    RewindMenuRow(RewindMode.FORK, stringResource(Res.string.rewind_menu_fork), stringResource(Res.string.rewind_menu_fork_sub), enabled, onPick)
    if (!enabled) {
        Row(
            Modifier.padding(top = 9.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Tok.surface).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box7Dot()
            Text(stringResource(Res.string.rewind_menu_blocked), color = Tok.muted, fontSize = 12.5.sp)
        }
    }
}

/** A plain action row in the same sheet — the menu's Copy entry, so the long-press answers the whole
 *  "what can I do with this message" question instead of only its new half. */
@Composable
internal fun RewindSheetActionRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.padding(top = 9.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("⧉", color = Tok.tx2, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(18.dp))
        Text(label, color = Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RewindMenuRow(mode: String, label: String, caption: String, enabled: Boolean, onPick: (String) -> Unit) {
    Row(
        Modifier.padding(top = 9.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .clickable(enabled = enabled) { onPick(mode) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            rewindGlyph(mode),
            color = if (enabled) Tok.accent else Tok.muted,
            fontSize = 14.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) Tok.tx else Tok.muted, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            Text(caption, color = Tok.muted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/**
 * The dry-run confirmation body (design frame B). Three blocks in a fixed order, and the order is the
 * argument: what you lose, what you do NOT get back, then which of the two modes you picked.
 *
 * The file warning is unconditional and never softens. The CLI does have a `--rewind-files` capability,
 * but it depends on checkpointing this daemon does not drive, so a rewind moves the conversation and
 * leaves the working tree exactly where the discarded turns left it — the one thing a person is most
 * likely to assume otherwise.
 */
@Composable
internal fun RewindConfirmBody(
    sheet: PocketRepository.RewindSheet,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val fork = sheet.target.mode == RewindMode.FORK
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp, top = 4.dp)) {
        Text(
            stringResource(if (fork) Res.string.rewind_confirm_fork_title else Res.string.rewind_confirm_rewind_title),
            color = Tok.tx, fontSize = 19.sp, fontWeight = FontWeight.Bold,
        )
        // ① what leaves the context
        Column(
            Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(13.dp))
                .background(Tok.surface).padding(horizontal = 15.dp, vertical = 13.dp),
        ) {
            Text(
                stringResource(Res.string.rewind_will_drop), color = Tok.muted, fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp,
            )
            val counts = sheet.counts
            if (counts == null) {
                Text(stringResource(Res.string.rewind_counting), color = Tok.tx2, fontSize = 14.5.sp, modifier = Modifier.padding(top = 11.dp))
            } else {
                Row(Modifier.padding(top = 11.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DropStat(counts.turns, stringResource(Res.string.rewind_turns))
                    Box(Modifier.width(1.dp).height(20.dp).background(Tok.hair))
                    DropStat(counts.toolCalls, stringResource(Res.string.rewind_tool_calls))
                }
            }
        }
        // ② the thing people assume wrong
        Row(
            Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(13.dp))
                .background(Tok.danger.copy(alpha = 0.10f))
                .border(1.dp, Tok.danger.copy(alpha = 0.34f), RoundedCornerShape(13.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text("▤", color = Tok.danger, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.rewind_files_warning_lead), color = Tok.danger, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(Res.string.rewind_files_warning_rest), color = Tok.tx2, fontSize = 13.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        // ③ which mode, spelled out as its consequence for the session you are standing in
        Row(
            Modifier.padding(top = 14.dp).fillMaxWidth().heightIn(min = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text(rewindGlyph(sheet.target.mode), color = Tok.muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(16.dp))
            Text(
                stringResource(if (fork) Res.string.rewind_mode_note_fork else Res.string.rewind_mode_note_rewind),
                color = Tok.tx2, fontSize = 13.5.sp, modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(Res.string.cancel), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(13.dp))
                    .border(1.dp, Tok.hair, RoundedCornerShape(13.dp))
                    .clickable(onClick = onCancel).padding(vertical = 15.dp),
            )
            // Armed only once the dry run has answered: confirming a cut whose size you have not been
            // shown would make the mandatory preview optional in practice.
            val armed = sheet.counts != null && !sheet.submitting
            Text(
                stringResource(if (fork) Res.string.rewind_action_fork else Res.string.rewind_action_rewind),
                color = if (armed) Tok.base else Tok.muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1.5f).clip(RoundedCornerShape(13.dp))
                    .background(if (armed) Tok.accent else Tok.surface)
                    .clickable(enabled = armed, onClick = onConfirm).padding(vertical = 15.dp),
            )
        }
    }
}

@Composable
private fun DropStat(value: Int, unit: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("$value", color = Tok.tx, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(unit, color = Tok.tx2, fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
    }
}

/**
 * The branch's lineage row (design frame C) — it replaces the session-context line in the chat header
 * for as long as the branch is on screen. Not dismissible in this version, deliberately: a session that
 * silently isn't the one you were in a moment ago is the single most disorienting outcome of this
 * feature, and the banner is the only thing that says so before the first turn exists.
 */
@Composable
internal fun LineageBanner(mode: String, fromTitle: String, modifier: Modifier = Modifier, onOpenOriginal: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp)
            .let { m -> if (onOpenOriginal != null) m.clickable(onClick = onOpenOriginal) else m }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(rewindGlyph(mode), color = Tok.muted, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
        Text(
            stringResource(if (mode == RewindMode.FORK) Res.string.rewind_lineage_fork else Res.string.rewind_lineage_rewound),
            color = Tok.muted, fontSize = 12.5.sp,
        )
        Text(
            fromTitle, color = Tok.accent, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Map a daemon refusal onto copy. An UNKNOWN value from a newer daemon falls back to the generic
 *  line rather than being shown raw — the reason vocabulary is a protocol, not user-facing text. */
internal fun rewindErrorText(reason: String): StringResource = when (reason) {
    RewindRefusal.STALE -> Res.string.rewind_err_stale
    RewindRefusal.NOT_IDLE -> Res.string.rewind_err_not_idle
    RewindRefusal.UNSUPPORTED, RewindRefusal.BAD_MODE -> Res.string.rewind_err_unsupported
    RewindRefusal.EXTERNAL_WRITER -> Res.string.rewind_err_external
    RewindRefusal.FIRST_MESSAGE -> Res.string.rewind_err_first
    else -> Res.string.rewind_err_generic
}

/** The stale-anchor bar (design "State · stale anchor"). Self-dismissing, like the archive toast — a
 *  refusal is information, not a decision, so it must not need a tap to get out of the way. */
@Composable
internal fun RewindErrorBar(reason: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(reason) { delay(5_000); onDismiss() }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.raised)
            .border(1.dp, Tok.danger.copy(alpha = 0.38f), RoundedCornerShape(12.dp))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box7Dot(color = Tok.danger, top = 5.dp)
        Text(stringResource(rewindErrorText(reason)), color = Tok.tx, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Box7Dot(color: androidx.compose.ui.graphics.Color = Tok.muted, top: androidx.compose.ui.unit.Dp = 0.dp) {
    Box(Modifier.padding(top = top).size(7.dp).clip(RoundedCornerShape(4.dp)).background(color))
}
