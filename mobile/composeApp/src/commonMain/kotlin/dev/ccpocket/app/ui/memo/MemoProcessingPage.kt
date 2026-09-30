package dev.ccpocket.app.ui.memo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.SystemBackHandler
import dev.ccpocket.app.memo.MemoAction
import dev.ccpocket.app.memo.MemoProcessingIssue
import dev.ccpocket.app.memo.MemoProcessingState
import dev.ccpocket.app.memo.MemoStep
import dev.ccpocket.app.memo.MemoStepRow
import dev.ccpocket.app.memo.MemoStepStatus
import dev.ccpocket.app.memo.MemoUiState
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.session.Hairline
import org.jetbrains.compose.resources.stringResource

/**
 * Processing: three written stages with a shape each, the measured elapsed time, and — when something
 * stopped it — what happened and what can help. No percentage, no progress value, no time estimate.
 */
@Composable
internal fun MemoProcessingPage(state: MemoUiState, p: MemoProcessingState, onAction: (MemoAction) -> Unit) {
    val gutter = LocalMemoGutter.current
    var askDelete by remember(p.memoId) { mutableStateOf(false) }
    SystemBackHandler(enabled = !askDelete) { onAction(MemoAction.BackToList) }
    val running = p.running
    val cancelled = p.issue == MemoProcessingIssue.CANCELLED
    // an attempt that only runs the organise step (the result page's "用 X 整理 / 重新整理"): the contract marks
    // it on the memo's document — "a re-organise is running for this memo"
    val organizeOnly = state.document?.let { it.memoId == p.memoId && it.reorganizing } == true
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = gutter)) {
                MemoBackRow(
                    stringResource(if (running) Res.string.memo_back_to_list_running_cd else Res.string.memo_back_to_list_cd),
                ) { onAction(MemoAction.BackToList) }
                MemoText(
                    stringResource(
                        when {
                            running && organizeOnly -> Res.string.memo_proc_title_organizing
                            running -> Res.string.memo_proc_title_running
                            cancelled -> Res.string.memo_proc_title_cancelled
                            else -> Res.string.memo_proc_title_stopped
                        },
                    ),
                    MemoType.title, Tok.tx, Modifier.padding(top = 2.dp).semantics { heading() },
                )
                val clock = fmtClock(p.audioDurationMs)
                MemoText(
                    when {
                        p.hasAudio -> stringResource(Res.string.memo_proc_sub_audio, clock)
                        p.hasTranscript -> stringResource(Res.string.memo_proc_sub_transcript, clock)
                        else -> stringResource(Res.string.memo_proc_sub_plain, clock)
                    },
                    MemoType.caption, Tok.tx2, Modifier.padding(top = 8.dp),
                )
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = gutter).padding(top = 16.dp)) {
                if (p.reachedLimit) MemoNoticeBlock(
                    MemoMark.DIAMOND, Tok.warn, title = null,
                    // a transcribe-only attempt goes on to transcription, not to organising
                    body = stringResource(if (p.organizer != null) Res.string.memo_proc_limit_note else Res.string.memo_proc_limit_note_transcribe),
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // label and mono counter differ in size: both tightCenter-based (MemoType)
                    MemoSectionLabel(stringResource(Res.string.memo_proc_stages), Modifier.weight(1f))
                    MemoText(
                        p.elapsedMs?.let { stringResource(Res.string.memo_proc_elapsed, (it / 1000).toInt()) }
                            ?: stringResource(Res.string.memo_not_measured),
                        MemoType.mono, Tok.tx2,
                    )
                }
                Column(Modifier.padding(top = 10.dp)) {
                    Hairline()
                    p.steps.forEach { StepRow(it, p, computerName(state.readiness)) }
                }
                issueCopy(p, state.readiness)?.let { (title, body) ->
                    val waiting = p.issue == MemoProcessingIssue.WAITING_FOR_COMPUTER
                    val neutral = waiting || cancelled
                    MemoNoticeBlock(
                        mark = if (neutral) MemoMark.RING else MemoMark.SQUARE,
                        tint = if (neutral) Tok.tx2 else Tok.danger,
                        title = title, body = body,
                        titleColor = if (neutral) Tok.tx else Tok.danger,
                        modifier = Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (running) MemoText(stringResource(Res.string.memo_proc_footer), MemoType.caption, Tok.tx2, Modifier.padding(top = 14.dp))
                Spacer(Modifier.height(16.dp))
            }
            MemoBottomBar {
                val organizerNow = state.readiness.organizer
                processingButtons(p, organizerNow).forEach { (button, kind) ->
                    val label = when {
                        // REORGANIZE is only offered with an organiser; it names the one a retry would run
                        button == MemoProcButton.REORGANIZE -> stringResource(button.label, organizerNameOrGeneric(organizerNow))
                        button == MemoProcButton.CANCEL && organizeOnly -> stringResource(Res.string.memo_proc_cancel_organize)
                        else -> stringResource(button.label)
                    }
                    val tap = { button.action()?.let(onAction) ?: run { askDelete = true } }
                    when (kind) {
                        MemoButtonKind.PRIMARY -> MemoPrimaryButton(label, onClick = tap)
                        MemoButtonKind.SECONDARY -> MemoOutlineButton(label, onClick = tap)
                        MemoButtonKind.DANGER -> MemoOutlineButton(label, tint = Tok.danger, onClick = tap)
                    }
                }
            }
        }
        if (askDelete) MemoDeleteSheet(
            title = state.list.rows.firstOrNull { it.memoId == p.memoId }?.title?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.memo_untitled),
            onConfirm = { askDelete = false; onAction(MemoAction.DeleteMemo(p.memoId)) },
            onDismiss = { askDelete = false },
        )
    }
}

@Composable
private fun StepRow(row: MemoStepRow, p: MemoProcessingState, computer: String) {
    val (name, detail) = when (row.step) {
        MemoStep.UPLOAD -> stringResource(Res.string.memo_step_upload) to stringResource(Res.string.memo_step_upload_detail, computer)
        MemoStep.TRANSCRIBE -> stringResource(Res.string.memo_step_transcribe) to stringResource(Res.string.memo_step_transcribe_detail, computer)
        MemoStep.ORGANIZE -> stringResource(Res.string.memo_step_organize) to (
            // named by the organiser THIS attempt runs; a transcribe-only attempt says where it goes instead
            if (row.status == MemoStepStatus.SKIPPED || p.organizer == null) stringResource(Res.string.memo_step_skipped_detail)
            else stringResource(Res.string.memo_step_organize_detail, organizerDisplayName(p.organizer))
            )
    }
    val skipped = row.status == MemoStepStatus.SKIPPED
    val cancelled = p.issue == MemoProcessingIssue.CANCELLED
    val emptyResult = row.step == MemoStep.TRANSCRIBE && p.issue == MemoProcessingIssue.EMPTY_TRANSCRIPT && row.status != MemoStepStatus.NOT_STARTED
    val measured = row.durationMs?.let { stringResource(Res.string.memo_step_seconds, fmtSecondsTenths(it)) }
    val notMeasured = stringResource(Res.string.memo_not_measured)
    val time = when {
        skipped && p.organizerLost -> stringResource(Res.string.memo_step_skipped_lost, organizerNameOrGeneric(p.organizer))
        skipped -> stringResource(Res.string.memo_step_skipped_none)
        emptyResult -> stringResource(Res.string.memo_step_empty_result, measured ?: notMeasured)
        row.status == MemoStepStatus.DONE -> measured ?: notMeasured
        row.status == MemoStepStatus.RUNNING -> stringResource(Res.string.memo_step_running)
        row.status == MemoStepStatus.WAITING -> stringResource(Res.string.memo_step_waiting)
        row.status == MemoStepStatus.NOT_STARTED -> stringResource(Res.string.memo_step_not_started)
        else -> measured ?: notMeasured // FAILED
    }
    val failed = row.status == MemoStepStatus.FAILED || emptyResult
    val (mark, tint) = when {
        failed && cancelled -> MemoMark.RING to Tok.tx2
        failed -> MemoMark.SQUARE to Tok.danger
        row.status == MemoStepStatus.DONE -> MemoMark.RING to Tok.ok
        // skipped is not a failure: a quiet ring, a shade darker than "waiting"
        skipped -> MemoMark.RING to Tok.tx2
        else -> MemoMark.RING to Tok.muted
    }
    val nameColor = when {
        failed && !cancelled -> Tok.danger
        row.status == MemoStepStatus.RUNNING || row.status == MemoStepStatus.DONE -> Tok.tx
        else -> Tok.tx2
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Metric.touch).padding(vertical = 13.dp)
                .semantics(mergeDescendants = true) { },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.width(18.dp).height(lineHeightDp(MemoType.bodyStrong)), contentAlignment = Alignment.Center) {
                if (row.status == MemoStepStatus.RUNNING && !failed) MemoSpinner() else MemoMarkGlyph(mark, tint)
            }
            Column(Modifier.weight(1f)) {
                MemoText(name, MemoType.bodyStrong, nameColor)
                MemoText(detail, MemoType.caption, Tok.tx2, Modifier.padding(top = 3.dp))
            }
            // a mono time beside a larger name: its own box one name-line tall keeps the first lines level
            Box(Modifier.widthIn(max = 140.dp).heightIn(min = lineHeightDp(MemoType.bodyStrong)), contentAlignment = Alignment.CenterEnd) {
                MemoText(time, MemoType.mono, if (failed && !cancelled) Tok.danger else Tok.tx2, textAlign = TextAlign.End)
            }
        }
        Hairline()
    }
}
