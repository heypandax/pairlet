package dev.ccpocket.app.ui.memo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.memo.MemoDispatchPhase
import dev.ccpocket.app.memo.MemoDispatchState
import dev.ccpocket.app.memo.MemoDispatchStop
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.agentName
import dev.ccpocket.app.ui.session.Hairline
import org.jetbrains.compose.resources.stringResource

/** "a 项已送达 · b 项待核对 · c 项失败 · d 项未发送", zero segments left out. */
@Composable
internal fun memoDispatchCounts(state: MemoDispatchState): String = buildList {
    if (state.delivered > 0) add(stringResource(Res.string.memo_count_delivered, state.delivered))
    if (state.unknown > 0) add(stringResource(Res.string.memo_count_unknown, state.unknown))
    if (state.failed > 0) add(stringResource(Res.string.memo_count_failed, state.failed))
    if (state.unsent > 0) add(stringResource(Res.string.memo_count_unsent, state.unsent))
}.joinToString(MEMO_SEP)

/**
 * The OPENING line and its explanation — "正在新建会话…" for a batch that creates its session, else
 * "正在打开会话…". Shared by the chat strip and the result page, which is still on screen while this runs.
 */
@Composable
internal fun openingCopy(state: MemoDispatchState): Pair<String, String> =
    if (state.creating) stringResource(Res.string.memo_notice_creating) to stringResource(
        Res.string.memo_notice_creating_sub, targetProject(state.target), agentName(state.target.agent), state.total,
    )
    else stringResource(Res.string.memo_notice_opening) to stringResource(Res.string.memo_notice_opening_sub, state.total)

private data class NoticeCopy(val line: String, val sub: String, val mark: MemoMark, val tint: Color, val lineColor: Color)

@Composable
private fun noticeCopy(state: MemoDispatchState): NoticeCopy = when (state.phase) {
    MemoDispatchPhase.OPENING -> openingCopy(state).let { (line, sub) -> NoticeCopy(line, sub, MemoMark.PULSE, Tok.tx2, Tok.tx) }
    MemoDispatchPhase.SENDING -> NoticeCopy(
        stringResource(Res.string.memo_notice_sending, state.current.coerceIn(1, state.total.coerceAtLeast(1)), state.total),
        stringResource(Res.string.memo_notice_sending_sub),
        MemoMark.PULSE, Tok.tx2, Tok.tx,
    )
    MemoDispatchPhase.DONE -> NoticeCopy(
        memoDispatchCounts(state).ifBlank { stringResource(Res.string.memo_notice_nothing) },
        stringResource(
            if (state.creating) Res.string.memo_notice_done_created_sub else Res.string.memo_notice_done_sub,
            agentName(state.target.agent),
        ),
        MemoMark.DOT, Tok.ok, Tok.ok,
    )
    MemoDispatchPhase.OPEN_FAILED -> NoticeCopy(
        stringResource(Res.string.memo_notice_open_failed), stringResource(Res.string.memo_notice_open_failed_sub),
        MemoMark.SQUARE, Tok.danger, Tok.danger,
    )
    MemoDispatchPhase.STOPPED -> {
        val (mark, tint) = when {
            state.unknown > 0 -> MemoMark.DIAMOND to Tok.warn
            state.failed > 0 -> MemoMark.SQUARE to Tok.danger
            else -> MemoMark.RING to Tok.tx2
        }
        NoticeCopy(
            memoDispatchCounts(state).ifBlank { stringResource(Res.string.memo_notice_nothing) },
            stringResource(stopSentence(state)),
            mark, tint, if (mark == MemoMark.RING) Tok.tx else tint,
        )
    }
}

private fun stopSentence(state: MemoDispatchState) = when (state.stop) {
    MemoDispatchStop.RECEIPT_MISSING -> Res.string.memo_notice_stop_receipt
    MemoDispatchStop.NOT_SENT -> Res.string.memo_notice_stop_failed
    MemoDispatchStop.LEFT_CHAT -> Res.string.memo_notice_stop_left
    MemoDispatchStop.BACKGROUND -> Res.string.memo_notice_stop_background
    MemoDispatchStop.MANUAL_MESSAGE -> Res.string.memo_notice_stop_manual
    MemoDispatchStop.COMPUTER_CHANGED -> Res.string.memo_notice_stop_computer
    MemoDispatchStop.FEATURE_OFF -> Res.string.memo_notice_stop_feature
    MemoDispatchStop.TARGET_LOST -> Res.string.memo_notice_stop_target
    MemoDispatchStop.SAVE_FAILED -> Res.string.memo_notice_stop_save
    MemoDispatchStop.NONE -> when {
        state.unknown > 0 -> Res.string.memo_notice_stop_receipt
        state.failed > 0 -> Res.string.memo_notice_stop_failed
        else -> Res.string.memo_notice_stop_paused
    }
}

/** The status strip shown at the top of the target chat while / after a batch is dispatched. */
@Composable
fun MemoDispatchNotice(state: MemoDispatchState, onReturnToMemo: () -> Unit, modifier: Modifier = Modifier) {
    val gutter = LocalMemoGutter.current
    val copy = noticeCopy(state)
    val compact = memoLargeType()
    Column(modifier.fillMaxWidth().background(Tok.surface)) {
        if (compact) {
            // 150 %+ type: the summary and the way back, nothing else — the strip must not bury the chat
            Row(
                Modifier.fillMaxWidth().padding(start = gutter, end = gutter - 10.dp, top = 4.dp, bottom = 2.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.weight(1f).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LineMark(copy.mark, copy.tint, MemoType.captionStrong)
                    MemoText(copy.line, MemoType.captionStrong, copy.lineColor, Modifier.weight(1f))
                }
                MemoTextAction(stringResource(Res.string.memo_return), onClick = onReturnToMemo)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = gutter, end = gutter - 10.dp, top = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                LineMark(copy.mark, copy.tint, MemoType.label)
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    MemoText(stringResource(Res.string.memo_notice_title, state.memoTitle), MemoType.label, Tok.tx2)
                    MemoText(copy.line, MemoType.captionStrong, copy.lineColor, Modifier.padding(top = 3.dp))
                    MemoText(copy.sub, MemoType.caption, Tok.tx2, Modifier.padding(top = 3.dp))
                }
                MemoTextAction(stringResource(Res.string.memo_return), onClick = onReturnToMemo)
            }
        }
        Hairline()
    }
}

/** Status line under a chat bubble that came from a memo: shape + word for one MemoTodoState. */
@Composable
fun MemoDeliveryMark(todoState: String, modifier: Modifier = Modifier, detail: String? = null) {
    DeliveryLine(todoState, detail, modifier)
}
