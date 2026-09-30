package dev.ccpocket.app.ui.memo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.SystemBackHandler
import dev.ccpocket.app.memo.MemoAction
import dev.ccpocket.app.memo.MemoCapturePhase
import dev.ccpocket.app.memo.MemoHeader
import dev.ccpocket.app.memo.MemoScreen
import dev.ccpocket.app.memo.MemoToast
import dev.ccpocket.app.memo.MemoUiState
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.PocketSheet
import dev.ccpocket.app.ui.session.Hairline
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

internal const val MEMO_TOAST_MS = 2_600L

/** The whole memo surface: list, recording, processing and result, with their sheets. */
@Composable
fun VoiceMemoScreen(
    state: MemoUiState,
    level: Float, // 0..1 microphone envelope
    onAction: (MemoAction) -> Unit,
    onExit: () -> Unit, // back from the list to the session list
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(Tok.base)) {
        val gutter = if (maxWidth <= 340.dp) 16.dp else 20.dp
        CompositionLocalProvider(LocalMemoGutter provides gutter) {
            val consent = state.capture.phase == MemoCapturePhase.CONSENT
            when (state.screen) {
                MemoScreen.LIST -> MemoListPage(state, onAction, onExit, overlayOpen = consent)
                MemoScreen.RECORDING -> MemoRecordingPage(state, level, onAction)
                MemoScreen.PROCESSING -> {
                    val p = state.processing
                    if (p != null) MemoProcessingPage(state, p, onAction) else MemoLoadingPage(onAction)
                }
                MemoScreen.DETAIL -> {
                    val d = state.document
                    if (d != null) MemoDetailPage(state, d, onAction) else MemoLoadingPage(onAction)
                }
            }
            if (consent) MemoConsentSheet(state.readiness.organizer, onAction)
            state.toast?.let { MemoToastHost(it, onAction) }
        }
    }
}

/** A memo the data layer is still reading — the page's own back row over a quiet wait. */
@Composable
private fun MemoLoadingPage(onAction: (MemoAction) -> Unit) {
    val gutter = LocalMemoGutter.current
    SystemBackHandler(enabled = true) { onAction(MemoAction.BackToList) }
    Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
        MemoBackRow(stringResource(Res.string.memo_back_to_list_cd)) { onAction(MemoAction.BackToList) }
        Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MemoSpinner()
            MemoText(stringResource(Res.string.memo_list_loading), MemoType.caption, Tok.tx2)
        }
    }
}

// ── list ────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun MemoListPage(state: MemoUiState, onAction: (MemoAction) -> Unit, onExit: () -> Unit, overlayOpen: Boolean) {
    val gutter = LocalMemoGutter.current
    var deleteAsk by remember { mutableStateOf<MemoHeader?>(null) }
    SystemBackHandler(enabled = !overlayOpen && deleteAsk == null) { onExit() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = gutter)) {
                MemoBackRow(stringResource(Res.string.memo_back_to_sessions_cd), onExit)
                MemoText(stringResource(Res.string.memo_title), MemoType.title, Tok.tx, Modifier.padding(top = 2.dp).semantics { heading() })
                MemoText(stringResource(Res.string.memo_list_subtitle), MemoType.caption, Tok.tx2, Modifier.padding(top = 8.dp))
            }
            val list = state.list
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp)) {
                when {
                    // an unreadable library is an error, never an empty list
                    list.unreadable -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter)) {
                        MemoNoticeBlock(
                            MemoMark.SQUARE, Tok.danger,
                            stringResource(Res.string.memo_list_unreadable_title),
                            stringResource(Res.string.memo_list_unreadable_body),
                            titleColor = Tok.danger,
                        )
                    }
                    !list.loaded -> Row(
                        Modifier.padding(horizontal = gutter),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MemoSpinner()
                        MemoText(stringResource(Res.string.memo_list_loading), MemoType.caption, Tok.tx2)
                    }
                    list.rows.isEmpty() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter)) {
                        Hairline()
                        MemoText(stringResource(Res.string.memo_list_empty_title), MemoType.row, Tok.tx, Modifier.padding(top = 18.dp))
                        MemoText(
                            stringResource(Res.string.memo_list_empty_body, computerName(state.readiness)),
                            MemoType.body, Tok.tx2, Modifier.padding(top = 8.dp),
                        )
                    }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        item(key = "memo-top-rule") { Hairline(Modifier.padding(horizontal = gutter)) }
                        items(list.rows, key = { it.memoId }) { row ->
                            MemoListRow(row, onOpen = { onAction(MemoAction.OpenMemo(row.memoId)) }, onDelete = { deleteAsk = row })
                        }
                        if (list.hasMore) item(key = "memo-load-more") {
                            // composed only when scrolled into view: that is the "near the end" signal
                            LaunchedEffect(list.rows.size) { onAction(MemoAction.LoadMore) }
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = Metric.touch).padding(horizontal = gutter),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                MemoSpinner()
                                MemoText(stringResource(Res.string.memo_loading_more), MemoType.caption, Tok.tx2)
                            }
                        }
                        item(key = "memo-bottom-space") { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
            MemoBottomBar {
                val reason = newMemoBlockedReason(state.readiness)
                MemoOutlineButton(
                    stringResource(Res.string.memo_new),
                    enabled = reason == null,
                    leading = "+",
                ) { if (reason == null) onAction(MemoAction.NewMemo) }
                if (reason != null) MemoText(reason, MemoType.caption, Tok.tx2, Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
        deleteAsk?.let { row ->
            MemoDeleteSheet(
                title = row.title.ifBlank { stringResource(Res.string.memo_untitled) },
                onConfirm = { deleteAsk = null; onAction(MemoAction.DeleteMemo(row.memoId)) },
                onDismiss = { deleteAsk = null },
            )
        }
    }
}

@Composable
private fun MemoListRow(row: MemoHeader, onOpen: () -> Unit, onDelete: () -> Unit) {
    val gutter = LocalMemoGutter.current
    val title = row.title.ifBlank { stringResource(Res.string.memo_untitled) }
    val status = memoHeaderStatus(row)
    val statusColor = when {
        row.stopped() -> Tok.danger
        row.stage == null && row.unknown > 0 -> Tok.warn
        row.stage == null && row.failed > 0 -> Tok.danger
        else -> Tok.tx2
    }
    val openLabel = stringResource(Res.string.memo_row_open_cd, title, status)
    val deleteLabel = stringResource(Res.string.memo_delete_cd, title)
    Column(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
                    .semantics(mergeDescendants = true) { contentDescription = openLabel }
                    .padding(top = 13.dp, bottom = 14.dp),
            ) {
                MemoText(title, MemoType.row, Tok.tx)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // different sizes side by side: both tightCenter-based (MemoType) so the glyphs share a centre
                    MemoText(memoTimeLabel(row.createdAtMs), MemoType.mono, Tok.muted)
                    MemoText(status, MemoType.caption, statusColor, Modifier.weight(1f, fill = false))
                }
            }
            Box(
                Modifier.heightIn(min = Metric.touch).offset(x = 12.dp).clip(RoundedCornerShape(Metric.radiusS))
                    .clickable(role = Role.Button, onClick = onDelete)
                    .semantics { contentDescription = deleteLabel }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { MemoText(stringResource(Res.string.memo_delete), MemoType.caption, Tok.tx2, Modifier.clearAndSetSemantics { }) }
        }
        Hairline()
    }
}

// ── sheets ──────────────────────────────────────────────────────────────────────────────────────────

/**
 * A memo bottom sheet on the app's [PocketSheet]: title and body scroll together, the actions stay pinned
 * at the bottom — so at 320 dp / 200 % type the primary button and any notice beside it stay reachable.
 */
@Composable
internal fun MemoSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit = {},
    pinned: @Composable ColumnScope.() -> Unit,
) {
    val gutter = LocalMemoGutter.current
    PocketSheet(onDismiss) {
        Column(modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = gutter)) {
            MemoText(title, MemoType.sheetTitle, Tok.tx, Modifier.semantics { heading() })
            body()
            Spacer(Modifier.height(8.dp))
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = gutter).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = pinned,
        )
    }
}

@Composable
internal fun MemoDeleteSheet(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    MemoSheet(
        title = stringResource(Res.string.memo_delete_title, title),
        onDismiss = onDismiss,
        body = { MemoText(stringResource(Res.string.memo_delete_body), MemoType.body, Tok.tx2, Modifier.padding(top = 8.dp)) },
    ) {
        MemoOutlineButton(stringResource(Res.string.memo_delete), tint = Tok.danger, onClick = onConfirm)
        MemoOutlineButton(stringResource(Res.string.memo_cancel), onClick = onDismiss)
    }
}

/**
 * First use: the three places the data goes, then an explicit start. Nothing records before the tap. The
 * note names the organiser this memo would use ([organizer], a wire word) — or says there is none and the
 * memo will only be transcribed.
 */
@Composable
private fun MemoConsentSheet(organizer: String?, onAction: (MemoAction) -> Unit) {
    MemoSheet(
        title = stringResource(Res.string.memo_consent_title),
        onDismiss = { onAction(MemoAction.DeclineConsent) },
        body = {
            MemoText(stringResource(Res.string.memo_consent_lead), MemoType.caption, Tok.tx2, Modifier.padding(top = 8.dp))
            Column(Modifier.padding(top = 14.dp)) {
                Hairline()
                listOf(Res.string.memo_consent_1, Res.string.memo_consent_2, Res.string.memo_consent_3).forEachIndexed { i, res ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // mono numeral beside proportional text of another size: both tightCenter-based
                        Box(Modifier.width(18.dp).height(lineHeightDp(MemoType.body)), contentAlignment = Alignment.CenterStart) {
                            MemoText("${i + 1}", MemoType.mono, Tok.muted)
                        }
                        MemoText(stringResource(res), MemoType.body, Tok.tx, Modifier.weight(1f))
                    }
                    Hairline()
                }
            }
            MemoText(
                organizer?.let { stringResource(Res.string.memo_consent_note, organizerDisplayName(it)) }
                    ?: stringResource(Res.string.memo_consent_note_none),
                MemoType.caption, Tok.tx2, Modifier.padding(top = 12.dp),
            )
        },
    ) {
        MemoPrimaryButton(stringResource(Res.string.memo_consent_accept)) { onAction(MemoAction.AcceptConsent) }
        MemoOutlineButton(stringResource(Res.string.memo_consent_decline)) { onAction(MemoAction.DeclineConsent) }
    }
}

@Composable
private fun BoxScopeToast(text: String) {
    MemoText(
        text, MemoType.caption, Tok.tx,
        Modifier.clip(RoundedCornerShape(10.dp)).background(Tok.raised)
            .border(Metric.hairline, Tok.hair, RoundedCornerShape(10.dp))
            .padding(horizontal = 13.dp, vertical = 9.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun MemoToastHost(toast: MemoToast, onAction: (MemoAction) -> Unit) {
    val current by rememberUpdatedState(onAction)
    LaunchedEffect(toast) {
        delay(MEMO_TOAST_MS)
        current(MemoAction.ToastShown)
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
        BoxScopeToast(toastText(toast))
    }
}
