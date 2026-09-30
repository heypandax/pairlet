package dev.ccpocket.app.ui.memo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.SystemBackHandler
import dev.ccpocket.app.memo.MemoAction
import dev.ccpocket.app.memo.MemoCapturePhase
import dev.ccpocket.app.memo.MemoUiState
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.VoiceMemoLimits
import org.jetbrains.compose.resources.stringResource

private const val LEVEL_BARS = 36

/** What the back gesture / top-left back does in each capture phase; null = no way back (saving). */
internal fun recordingBackAction(phase: MemoCapturePhase): MemoAction? = when (phase) {
    MemoCapturePhase.RECORDING -> MemoAction.RequestLeaveRecording
    MemoCapturePhase.SAVING -> null
    else -> MemoAction.DiscardRecording
}

/** Capture: a real timer, a real level meter, finish or discard. No pause, no resume, no live transcript. */
@Composable
internal fun MemoRecordingPage(state: MemoUiState, level: Float, onAction: (MemoAction) -> Unit) {
    val gutter = LocalMemoGutter.current
    val cap = state.capture
    val back = recordingBackAction(cap.phase)
    val prompt = cap.leavePrompt && cap.phase == MemoCapturePhase.RECORDING
    SystemBackHandler(enabled = !prompt && cap.phase != MemoCapturePhase.CONSENT) { back?.let(onAction) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = gutter)) {
                MemoBackRow(stringResource(Res.string.memo_back_cd), back?.let { a -> { onAction(a) } })
            }
            when (cap.phase) {
                MemoCapturePhase.RECORDING -> LiveCapture(state, level, onAction)
                MemoCapturePhase.SAVING -> WaitingCapture(
                    stringResource(Res.string.memo_rec_saving), stringResource(Res.string.memo_rec_saving_body),
                )
                MemoCapturePhase.PERMISSION_DENIED -> ProblemCapture(
                    MemoMark.SQUARE, Tok.danger,
                    stringResource(Res.string.memo_rec_denied_title), stringResource(Res.string.memo_rec_denied_body),
                ) {
                    MemoPrimaryButton(stringResource(Res.string.memo_rec_open_settings)) { onAction(MemoAction.OpenMicSettings) }
                    MemoOutlineButton(stringResource(Res.string.memo_rec_retry)) { onAction(MemoAction.RetryRecording) }
                    MemoQuietAction(stringResource(Res.string.memo_back_to_list)) { onAction(MemoAction.DiscardRecording) }
                }
                MemoCapturePhase.INTERRUPTED -> ProblemCapture(
                    MemoMark.DIAMOND, Tok.warn,
                    stringResource(Res.string.memo_rec_interrupted_title), stringResource(Res.string.memo_rec_interrupted_body),
                ) {
                    MemoPrimaryButton(stringResource(Res.string.memo_rec_rerecord)) { onAction(MemoAction.RetryRecording) }
                    MemoOutlineButton(stringResource(Res.string.memo_back_to_list)) { onAction(MemoAction.DiscardRecording) }
                }
                MemoCapturePhase.MIC_BUSY -> ProblemCapture(
                    MemoMark.DIAMOND, Tok.warn,
                    stringResource(Res.string.memo_rec_busy_title), stringResource(Res.string.memo_rec_busy_body),
                ) {
                    MemoPrimaryButton(stringResource(Res.string.memo_rec_retry)) { onAction(MemoAction.RetryRecording) }
                    MemoOutlineButton(stringResource(Res.string.memo_back_to_list)) { onAction(MemoAction.DiscardRecording) }
                }
                MemoCapturePhase.SAVE_FAILED -> ProblemCapture(
                    MemoMark.SQUARE, Tok.danger,
                    stringResource(Res.string.memo_rec_save_failed_title), stringResource(Res.string.memo_rec_save_failed_body),
                ) {
                    MemoPrimaryButton(stringResource(Res.string.memo_rec_rerecord)) { onAction(MemoAction.RetryRecording) }
                    MemoOutlineButton(stringResource(Res.string.memo_leave_discard), tint = Tok.danger) { onAction(MemoAction.DiscardRecording) }
                }
                MemoCapturePhase.START_FAILED -> ProblemCapture(
                    MemoMark.SQUARE, Tok.danger,
                    stringResource(Res.string.memo_rec_start_failed_title), stringResource(Res.string.memo_rec_start_failed_body),
                ) {
                    MemoPrimaryButton(stringResource(Res.string.memo_rec_retry)) { onAction(MemoAction.RetryRecording) }
                    MemoOutlineButton(stringResource(Res.string.memo_back_to_list)) { onAction(MemoAction.DiscardRecording) }
                }
                // IDLE / CONSENT / PREPARING: the timer does not run until the microphone is really open
                else -> {
                    WaitingCapture(stringResource(Res.string.memo_rec_preparing), stringResource(Res.string.memo_rec_preparing_body))
                    if (cap.phase == MemoCapturePhase.PREPARING) MemoBottomBar {
                        MemoOutlineButton(stringResource(Res.string.memo_rec_discard)) { onAction(MemoAction.DiscardRecording) }
                    }
                }
            }
        }
        if (prompt) LeaveRecordingSheet(cap.elapsedMs, state.readiness.organizer, onAction)
    }
}

/**
 * "结束并整理", or "结束并转写" when no organiser is there ([organizer] = the readiness organiser, a wire word):
 * the button must not promise a step that will be skipped.
 */
@Composable
private fun finishLabel(organizer: String?): String =
    stringResource(if (organizer != null) Res.string.memo_rec_finish else Res.string.memo_rec_finish_transcribe)

@Composable
private fun ColumnScope.LiveCapture(state: MemoUiState, level: Float, onAction: (MemoAction) -> Unit) {
    val gutter = LocalMemoGutter.current
    val cap = state.capture
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = gutter)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            LineMark(MemoMark.PULSE, Tok.accent, MemoType.bodyStrong)
            MemoText(stringResource(Res.string.memo_rec_live), MemoType.bodyStrong, Tok.tx)
            // a smaller caption beside a larger label: aligned by line box, both tightCenter-based
            Box(Modifier.weight(1f).height(lineHeightDp(MemoType.bodyStrong)), contentAlignment = Alignment.CenterEnd) {
                MemoText(
                    stringResource(Res.string.memo_rec_where, computerName(state.readiness)),
                    MemoType.caption, Tok.tx2, textAlign = TextAlign.End, maxLines = 2,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val time = fmtClockFloor(cap.elapsedMs)
            MemoText(time, MemoType.timer, Tok.tx)
            MemoText(stringResource(Res.string.memo_rec_limit, fmtClock(VoiceMemoLimits.MAX_RECORDING_MS)), MemoType.mono, Tok.muted)
            LevelMeter(level, Modifier.padding(top = 10.dp))
        }
        cap.secondsLeft?.let { left ->
            MemoNoticeBlock(
                MemoMark.DIAMOND, Tok.warn, title = null,
                body = stringResource(
                    if (state.readiness.organizer != null) Res.string.memo_rec_seconds_left else Res.string.memo_rec_seconds_left_transcribe,
                    left, fmtClock(VoiceMemoLimits.MAX_RECORDING_MS),
                ),
                modifier = Modifier.padding(bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
    MemoBottomBar {
        MemoPrimaryButton(finishLabel(state.readiness.organizer)) { onAction(MemoAction.StopRecording) }
        MemoOutlineButton(stringResource(Res.string.memo_rec_discard)) { onAction(MemoAction.DiscardRecording) }
    }
}

/** The input level over the last [LEVEL_BARS] readings: a real envelope, never a simulated waveform. */
@Composable
private fun LevelMeter(level: Float, modifier: Modifier = Modifier) {
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(level) {
        history.add(level.coerceIn(0f, 1f))
        while (history.size > LEVEL_BARS) history.removeAt(0)
    }
    val last = history.lastOrNull() ?: 0f
    val description = stringResource(
        when {
            last < 0.33f -> Res.string.memo_rec_level_low
            last < 0.66f -> Res.string.memo_rec_level_mid
            else -> Res.string.memo_rec_level_high
        },
    )
    val tint = Tok.accent
    Canvas(
        modifier.fillMaxWidth().heightIn(min = 64.dp).height(64.dp)
            .semantics { role = Role.Image; contentDescription = description },
    ) {
        val barW = 4.dp.toPx()
        val gap = 3.dp.toPx()
        val total = LEVEL_BARS * barW + (LEVEL_BARS - 1) * gap
        val start = (size.width - total) / 2f
        for (i in 0 until LEVEL_BARS) {
            val v = history.getOrNull(history.size - LEVEL_BARS + i) ?: 0.06f
            val h = size.height * (0.08f + v.coerceAtLeast(0.06f) * 0.92f)
            drawRoundRect(
                color = tint.copy(alpha = 0.4f + 0.6f * v),
                topLeft = Offset(start + i * (barW + gap), (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

@Composable
private fun ColumnScope.WaitingCapture(title: String, body: String) {
    val gutter = LocalMemoGutter.current
    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = gutter).padding(top = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.height(lineHeightDp(MemoType.sheetTitle)), contentAlignment = Alignment.Center) { MemoSpinner(18.dp) }
            MemoText(title, MemoType.sheetTitle, Tok.tx, Modifier.weight(1f).semantics { heading() })
        }
        MemoText(body, MemoType.body, Tok.tx2, Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun ColumnScope.ProblemCapture(
    mark: MemoMark,
    tint: Color,
    title: String,
    body: String,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val gutter = LocalMemoGutter.current
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = gutter).padding(top = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LineMark(mark, tint, MemoType.sheetTitle)
            MemoText(title, MemoType.sheetTitle, Tok.tx, Modifier.weight(1f).semantics { heading() })
        }
        MemoText(body, MemoType.body, Tok.tx2, Modifier.padding(top = 12.dp))
    }
    MemoBottomBar(content = actions)
}

/** Back while recording: keep going (primary), finish (and organise / transcribe), or discard — never a silent loss. */
@Composable
private fun LeaveRecordingSheet(elapsedMs: Long, organizer: String?, onAction: (MemoAction) -> Unit) {
    MemoSheet(
        title = stringResource(Res.string.memo_leave_title),
        onDismiss = { onAction(MemoAction.ContinueRecording) },
        body = {
            MemoText(stringResource(Res.string.memo_leave_body, fmtClockFloor(elapsedMs)), MemoType.caption, Tok.tx2, Modifier.padding(top = 8.dp))
        },
    ) {
        MemoPrimaryButton(stringResource(Res.string.memo_leave_continue)) { onAction(MemoAction.ContinueRecording) }
        MemoOutlineButton(finishLabel(organizer)) { onAction(MemoAction.StopRecording) }
        MemoQuietAction(stringResource(Res.string.memo_leave_discard), color = Tok.danger) { onAction(MemoAction.DiscardRecording) }
    }
}
