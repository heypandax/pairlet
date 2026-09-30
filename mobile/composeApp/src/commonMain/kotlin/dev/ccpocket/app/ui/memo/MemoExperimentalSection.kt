package dev.ccpocket.app.ui.memo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.memo.MemoBlock
import dev.ccpocket.app.memo.MemoReadiness
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.session.Hairline
import org.jetbrains.compose.resources.stringResource

/** Settings → General → Experimental: the switch, the readiness rows and the data disclosure. */
@Composable
fun MemoExperimentalSection(readiness: MemoReadiness, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val on = readiness.featureOn
    Column(modifier.fillMaxWidth()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().heightIn(min = Metric.touch)
                .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                MemoText(stringResource(Res.string.memo_exp_toggle), MemoType.row, Tok.tx)
                MemoText(stringResource(Res.string.memo_exp_toggle_sub), MemoType.caption, Tok.tx2, Modifier.padding(top = 6.dp))
            }
            // the whole row is the switch (one 48 dp target, one announcement); the control only shows it
            Switch(checked = on, onCheckedChange = null)
        }
        Hairline()
        if (!on) {
            MemoText(stringResource(Res.string.memo_exp_off_note), MemoType.caption, Tok.tx2, Modifier.padding(top = 14.dp))
            return@Column
        }
        MemoSectionLabel(stringResource(Res.string.memo_exp_readiness), Modifier.padding(top = 22.dp))
        Column(Modifier.padding(top = 10.dp)) {
            Hairline()
            val name = computerName(readiness)
            val (computerValue, computerMark) = when {
                readiness.scope == null -> stringResource(Res.string.memo_exp_no_computer) to (MemoMark.SQUARE to Tok.danger)
                readiness.online -> stringResource(Res.string.memo_exp_online, name) to (MemoMark.DOT to Tok.ok)
                else -> stringResource(Res.string.memo_exp_offline, name) to (MemoMark.SQUARE to Tok.danger)
            }
            ReadinessRow(stringResource(Res.string.memo_exp_row_computer), computerValue, computerMark.first, computerMark.second)
            val (transcribeValue, transcribeMark) = transcriptionRow(readiness.block)
            ReadinessRow(stringResource(Res.string.memo_exp_row_transcribe), transcribeValue, transcribeMark.first, transcribeMark.second)
            OrganizerRow(readiness)
        }
        readinessProblem(readiness)?.let { problem ->
            val tint = if (problem.danger) Tok.danger else Tok.warn
            MemoNoticeBlock(
                problem.mark, tint, problem.title, problem.body,
                modifier = Modifier.padding(top = 14.dp),
            ) {
                MemoText(stringResource(Res.string.memo_exp_next, problem.next), MemoType.caption, Tok.tx, Modifier.padding(top = 8.dp))
            }
        }
        MemoSectionLabel(stringResource(Res.string.memo_exp_data), Modifier.padding(top = 22.dp))
        MemoText(stringResource(Res.string.memo_exp_data_body), MemoType.body, Tok.tx, Modifier.padding(top = 10.dp))
        MemoText(
            readiness.organizer?.let { stringResource(Res.string.memo_exp_no_model_choice, organizerDisplayName(it)) }
                ?: stringResource(Res.string.memo_exp_no_organizer_note),
            MemoType.caption, Tok.tx2, Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Nothing about the computer can be read in these states (offline, a stranger's phone, an old or unknown
 * computer side, an unencrypted link), so neither the transcription nor the organiser row may claim anything.
 */
private fun MemoBlock.leavesComputerUnchecked(): Boolean = when (this) {
    MemoBlock.NONE, MemoBlock.WHISPER_MISSING, MemoBlock.MODEL_MISSING, MemoBlock.CONVERTER_MISSING,
    MemoBlock.UNSUPPORTED_PLATFORM, MemoBlock.LIBRARY_FULL -> false
    else -> true
}

/** What can be said about local transcription from the readiness word alone — "unchecked" when nothing can. */
@Composable
private fun transcriptionRow(block: MemoBlock): Pair<String, Pair<MemoMark, Color>> = when (block) {
    MemoBlock.WHISPER_MISSING -> stringResource(Res.string.memo_exp_transcribe_whisper_missing) to (MemoMark.DIAMOND to Tok.warn)
    MemoBlock.MODEL_MISSING -> stringResource(Res.string.memo_exp_transcribe_model_missing) to (MemoMark.DIAMOND to Tok.warn)
    MemoBlock.CONVERTER_MISSING -> stringResource(Res.string.memo_exp_transcribe_converter_missing) to (MemoMark.DIAMOND to Tok.warn)
    MemoBlock.UNSUPPORTED_PLATFORM -> stringResource(Res.string.memo_exp_transcribe_unsupported) to (MemoMark.SQUARE to Tok.danger)
    MemoBlock.NONE, MemoBlock.LIBRARY_FULL -> stringResource(Res.string.memo_exp_transcribe_ready) to (MemoMark.RING to Tok.ok)
    else -> stringResource(Res.string.memo_exp_transcribe_unchecked) to (MemoMark.RING to Tok.muted)
}

/**
 * The organiser row, three states (v3.1): the app's default agent when it has an adapter on the computer;
 * another advertised agent, with a line saying the default had none; or none — transcription only, never a
 * block on recording. It always names who organises.
 */
@Composable
private fun OrganizerRow(readiness: MemoReadiness) {
    val organizer = readiness.organizer
    val key = stringResource(Res.string.memo_exp_row_organize)
    when {
        organizer != null -> {
            val name = organizerDisplayName(organizer)
            val fallback = !organizer.equals(readiness.defaultAgent, ignoreCase = true)
            ReadinessRow(
                key, stringResource(Res.string.memo_exp_organize_value, name), MemoMark.RING, Tok.ok,
                sub = if (fallback) stringResource(Res.string.memo_exp_organize_fallback_sub, organizerDisplayName(readiness.defaultAgent), name) else null,
            )
        }
        // with the computer unreadable, "no organiser" would be a guess; say what the transcription row says
        readiness.block.leavesComputerUnchecked() ->
            ReadinessRow(key, stringResource(Res.string.memo_exp_transcribe_unchecked), MemoMark.RING, Tok.muted)
        else -> ReadinessRow(
            key, stringResource(Res.string.memo_exp_organize_none), MemoMark.RING, Tok.muted,
            sub = stringResource(Res.string.memo_exp_organize_none_sub),
        )
    }
}

@Composable
private fun ReadinessRow(key: String, value: String, mark: MemoMark, tint: Color, sub: String? = null) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Metric.touch).padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // a caption key beside a body value: both tightCenter-based and the mark sits in one value line
            Box(Modifier.fillMaxWidth(0.32f).heightIn(min = lineHeightDp(MemoType.body)), contentAlignment = Alignment.CenterStart) {
                MemoText(key, MemoType.caption, Tok.tx2)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                LineMark(mark, tint, MemoType.body)
                Column(Modifier.weight(1f)) {
                    MemoText(value, MemoType.body, Tok.tx)
                    if (sub != null) MemoText(sub, MemoType.caption, Tok.tx2, Modifier.padding(top = 4.dp))
                }
            }
        }
        Hairline()
    }
}
