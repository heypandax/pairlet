package dev.ccpocket.app.ui.memo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.SystemBackHandler
import dev.ccpocket.app.memo.MemoAction
import dev.ccpocket.app.memo.MemoDispatchPhase
import dev.ccpocket.app.memo.MemoDocumentState
import dev.ccpocket.app.memo.MemoSelectionState
import dev.ccpocket.app.memo.MemoTargetRow
import dev.ccpocket.app.memo.MemoTodoRow
import dev.ccpocket.app.memo.MemoTodoState
import dev.ccpocket.app.memo.MemoUiState
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.tightCenter
import dev.ccpocket.app.ui.session.Hairline
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoValidation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.stringResource

/** How long typing must pause before an edit is stored (a blur stores it at once). */
internal const val MEMO_EDIT_COMMIT_MS = 600L

/**
 * The result: editable title, read-only summary, the to-do list with per-item delivery state, the folded
 * transcript and timings — and a fixed dispatch bar. The checkbox only means "in this dispatch"; delivery
 * is a separate shape + word, never a completion tick.
 */
@Composable
internal fun MemoDetailPage(state: MemoUiState, doc: MemoDocumentState, onAction: (MemoAction) -> Unit) {
    val gutter = LocalMemoGutter.current
    var showTargets by remember(doc.memoId) { mutableStateOf(false) }
    var showConfirm by remember(doc.memoId) { mutableStateOf(false) }
    var askReorganize by remember(doc.memoId) { mutableStateOf(false) }
    val unorganized = doc.isUnorganized()
    // v3.1: organising is offered with an organiser, and only before anything was dispatched — a re-organise
    // after a send would bring back as drafts the very items already delivered (prototype: canOrganize)
    val canOrganize = doc.organizerAvailable != null && doc.todos.none { it.state != MemoTodoState.DRAFT }
    // an unorganised memo asks nothing: there are no organiser drafts to replace, unless the user wrote some
    val requestOrganize: () -> Unit = {
        if (unorganized && doc.todos.isEmpty()) {
            onAction(MemoAction.Reorganize)
        } else {
            askReorganize = true
        }
    }
    // a degraded or unorganised result opens its transcript: it is the only content there is
    var transcriptOpen by remember(doc.memoId) { mutableStateOf(doc.degraded || unorganized) }
    var timingsOpen by remember(doc.memoId) { mutableStateOf(false) }
    val compact = memoLargeType()

    if (showTargets) {
        MemoTargetPicker(state, onAction = onAction) { showTargets = false }
        return
    }
    SystemBackHandler(enabled = !showConfirm && !askReorganize) { onAction(MemoAction.BackToList) }
    val selection = state.selection
    // opening / creating the target runs while THIS page is still on screen (the chat has not appeared yet)
    val dispatch = state.dispatch?.takeIf { it.memoId == doc.memoId }
    val opening = dispatch?.phase == MemoDispatchPhase.OPENING
    var dismissedFailure by remember(doc.memoId) { mutableStateOf<String?>(null) }
    val openFailure = dispatch?.takeIf { it.phase == MemoDispatchPhase.OPEN_FAILED && it.batchId != dismissedFailure }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = gutter)) {
                MemoBackRow(stringResource(Res.string.memo_back_to_list_cd)) { onAction(MemoAction.BackToList) }
                if (opening) {
                    val (line, sub) = openingCopy(dispatch!!)
                    MemoNoticeBlock(
                        MemoMark.PULSE, Tok.tx2, line, sub, titleColor = Tok.tx,
                        modifier = Modifier.padding(bottom = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = gutter).padding(top = 2.dp)) {
                TitleField(doc, onAction)
                MemoText(
                    stringResource(
                        Res.string.memo_detail_meta, memoTimeLabel(doc.createdAtMs), fmtClock(doc.audioDurationMs),
                        computerName(state.readiness),
                    ),
                    MemoType.mono, Tok.muted, Modifier.padding(top = 8.dp),
                )
                openFailure?.let { failed ->
                    MemoNoticeBlock(
                        MemoMark.SQUARE, Tok.danger,
                        stringResource(if (failed.creating) Res.string.memo_open_failed_create_title else Res.string.memo_open_failed_title),
                        // locale-aware join: a space in English, none between Chinese sentences
                        stringResource(
                            Res.string.memo_two_sentences,
                            enterFailureReason(failed.openFailure, state.readiness),
                            stringResource(Res.string.memo_open_failed_tail, failed.total),
                        ),
                        modifier = Modifier.padding(top = 14.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        // local only: the drafts and the target are untouched; the next dispatch is the user's call
                        MemoTextAction(stringResource(Res.string.memo_got_it), modifier = Modifier.padding(top = 6.dp).offset(x = (-10).dp)) {
                            dismissedFailure = failed.batchId
                        }
                    }
                }
                if (unorganized) UnorganizedBlock(doc, canOrganize, requestOrganize)
                if (doc.degraded) DegradedBanner(doc, canOrganize) { askReorganize = true }
                doc.summary?.let { SummaryBlock(it, doc.summaryUnstructured, doc.organizedBy) }
                TodoSection(doc, unorganized, canOrganize, onAction, requestOrganize)
                TranscriptSection(doc, transcriptOpen) { transcriptOpen = !transcriptOpen }
                TimingsSection(doc, timingsOpen) { timingsOpen = !timingsOpen }
                if (compact) TargetRow(selection, enabled = !opening, modifier = Modifier.padding(top = 4.dp)) { showTargets = true }
                Spacer(Modifier.height(20.dp))
            }
            DispatchBar(state, doc, compact, busy = opening, onTargets = { showTargets = true }, onDispatch = { showConfirm = true })
        }
        if (showConfirm) MemoConfirmSheet(state, onAction) { showConfirm = false }
        val organizer = doc.organizerAvailable
        if (askReorganize && organizer != null) MemoSheet(
            title = stringResource(if (unorganized) Res.string.memo_organize_confirm_title else Res.string.memo_reorg_confirm_title),
            onDismiss = { askReorganize = false },
            body = { MemoText(stringResource(Res.string.memo_reorg_confirm_body), MemoType.body, Tok.tx2, Modifier.padding(top = 8.dp)) },
        ) {
            MemoPrimaryButton(organizeLabel(organizer, again = !unorganized)) { askReorganize = false; onAction(MemoAction.Reorganize) }
            MemoOutlineButton(stringResource(Res.string.memo_cancel)) { askReorganize = false }
        }
    }
}

// ── editable text ───────────────────────────────────────────────────────────────────────────────────

/**
 * A field that owns its own buffer and stores it only when typing pauses or focus leaves — never one save
 * per keystroke. An outside change lands only while the user is NOT editing (and never mid-IME-composition,
 * see ComposerState), so a stored echo or a late result can not yank the caret.
 */
@Composable
private fun MemoEditableText(
    key: String,
    external: String,
    style: TextStyle,
    description: String,
    placeholder: String?,
    maxChars: Int,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Keep the placeholder in the semantics tree — for a placeholder that carries information of its own. */
    placeholderReadable: Boolean = false,
) {
    val field = remember(key) { TextFieldState(external, TextRange(external.length)) }
    var lastSent by remember(key) { mutableStateOf(external) }
    var focused by remember(key) { mutableStateOf(false) }
    val commitTo by rememberUpdatedState(onCommit)
    val commit = {
        val text = field.text.toString()
        if (text != lastSent) {
            lastSent = text
            commitTo(text)
        }
    }
    LaunchedEffect(key, external) {
        if (!focused && field.composition == null && field.text.toString() != external) field.setTextAndPlaceCursorAtEnd(external)
        if (!focused) lastSent = external
    }
    LaunchedEffect(key) {
        snapshotFlow { field.text.toString() to (field.composition != null) }.collectLatest { (_, composing) ->
            if (!composing) {
                delay(MEMO_EDIT_COMMIT_MS)
                commit()
            }
        }
    }
    DisposableEffect(key) { onDispose { commit() } }
    BasicTextField(
        state = field,
        modifier = modifier.fillMaxWidth()
            .onFocusChanged { f ->
                if (focused && !f.isFocused) commit()
                focused = f.isFocused
            }
            .semantics { contentDescription = description },
        textStyle = style.merge(TextStyle(color = Tok.tx)),
        cursorBrush = SolidColor(Tok.accent),
        inputTransformation = InputTransformation.maxLength(maxChars),
        lineLimits = TextFieldLineLimits.MultiLine(),
        decorator = { inner ->
            Box {
                if (placeholder != null && field.text.isEmpty()) MemoText(
                    placeholder, style, Tok.muted, if (placeholderReadable) Modifier else Modifier.clearAndSetSemantics { },
                )
                inner()
            }
        },
    )
}

@Composable
private fun TitleField(doc: MemoDocumentState, onAction: (MemoAction) -> Unit) {
    val dash = Tok.tx2.copy(alpha = 0.45f)
    MemoEditableText(
        key = "title-${doc.memoId}",
        external = doc.title,
        style = MemoType.sheetTitle,
        description = stringResource(Res.string.memo_title_cd),
        // an unorganised memo has no stored title: its transcript's first sentence stands in, as a placeholder
        // only — nothing is saved until the user types a title of their own
        placeholder = doc.titleFallback.ifBlank { stringResource(Res.string.memo_untitled) },
        // it is the memo's name until the user gives one — a screen reader should hear it too
        placeholderReadable = true,
        // chars, not code points: twice the limit so a stored title never becomes un-editable
        maxChars = VoiceMemoLimits.MAX_TITLE_CODE_POINTS * 2,
        onCommit = { onAction(MemoAction.EditTitle(it)) },
        modifier = Modifier.drawBehind {
            val y = size.height - 1f
            drawLine(dash, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
        }.padding(top = 2.dp, bottom = 8.dp),
    )
}

// ── blocks ──────────────────────────────────────────────────────────────────────────────────────────

/**
 * "未整理" (v3.1): transcribed, never organised — no summary, not degraded, no organiser on record, and a
 * transcript to work from. The transcript is the result; the to-dos are the user's to write.
 */
internal fun MemoDocumentState.isUnorganized(): Boolean =
    summary == null && !degraded && organizedBy == null && !transcript.isNullOrBlank()

/** "用 X 整理" / "用 X 重新整理" — the organise action always says which agent will run. */
@Composable
private fun organizeLabel(organizer: String, again: Boolean): String =
    stringResource(if (again) Res.string.memo_reorganize_with else Res.string.memo_organize_with, organizerDisplayName(organizer))

/** Two sentences in the locale's way: a space between them in English, none in Chinese. */
@Composable
private fun sentences(first: String, second: String): String = stringResource(Res.string.memo_two_sentences, first, second)

/** The spinner line shown in place of an organise action while that organising runs. */
@Composable
private fun OrganizingLine(text: String) {
    Row(
        Modifier.heightIn(min = 44.dp).padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MemoSpinner()
        MemoText(text, MemoType.captionStrong, Tok.tx2)
    }
}

/**
 * The note on an unorganised memo: neutral (a ring, never the danger ink — nothing failed), why the to-dos are
 * empty, and either the one action that organises it or what would make organising possible.
 */
@Composable
private fun UnorganizedBlock(doc: MemoDocumentState, canOrganize: Boolean, onOrganize: () -> Unit) {
    var body = stringResource(Res.string.memo_unorganized_body)
    if (doc.organizerLost) body = sentences(stringResource(Res.string.memo_unorganized_lost), body)
    if (doc.organizerAvailable == null) body = sentences(body, stringResource(Res.string.memo_unorganized_install))
    MemoNoticeBlock(
        MemoMark.RING, Tok.tx2, stringResource(Res.string.memo_unorganized_title), body, titleColor = Tok.tx,
        // the page's state, announced like the other result-page notes (Compose has no "alert" role)
        modifier = Modifier.padding(top = 14.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        val organizer = doc.organizerAvailable
        when {
            doc.reorganizing -> OrganizingLine(stringResource(Res.string.memo_organizing))
            canOrganize && organizer != null -> MemoTextAction(
                organizeLabel(organizer, again = false),
                modifier = Modifier.padding(top = 6.dp).offset(x = (-10).dp), onClick = onOrganize,
            )
        }
    }
}

/** "未能整理成待办" — the v2.1 banner; its action names the organiser that would run, and is absent without one. */
@Composable
private fun DegradedBanner(doc: MemoDocumentState, canOrganize: Boolean, onReorganize: () -> Unit) {
    MemoNoticeBlock(
        MemoMark.SQUARE, Tok.danger,
        stringResource(Res.string.memo_degraded_title),
        sentences(
            stringResource(Res.string.memo_degraded_body),
            stringResource(if (canOrganize) Res.string.memo_degraded_next else Res.string.memo_degraded_next_manual),
        ),
        modifier = Modifier.padding(top = 14.dp),
    ) {
        val organizer = doc.organizerAvailable
        when {
            doc.reorganizing -> OrganizingLine(stringResource(Res.string.memo_reorganizing))
            canOrganize && organizer != null -> MemoTextAction(
                organizeLabel(organizer, again = true),
                modifier = Modifier.padding(top = 6.dp).offset(x = (-10).dp), onClick = onReorganize,
            )
        }
    }
}

/** A small bordered tag beside a section label ("未结构化", "Codex 整理"). */
@Composable
private fun MemoTag(text: String, color: Color) {
    val shape = RoundedCornerShape(5.dp)
    // a bordered tag beside the label: tightCenter so its glyphs centre in the box on every font
    MemoText(
        text, tightCenter(10.5.sp).merge(TextStyle(fontSize = 10.5.sp)), color,
        Modifier.clip(shape).border(Metric.hairline, Tok.tx2.copy(alpha = 0.45f), shape).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryBlock(summary: String, unstructured: Boolean, organizedBy: String?) {
    Column(Modifier.padding(top = 18.dp)) {
        // label, tags and "只读" wrap as a flow at large type; every piece is tightCenter-based and centred
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            MemoSectionLabel(stringResource(Res.string.memo_summary))
            if (unstructured) MemoTag(stringResource(Res.string.memo_unstructured), Tok.warn)
            // who organised it — the screen always says which agent wrote the summary
            organizedBy?.let { MemoTag(stringResource(Res.string.memo_organized_by, organizerDisplayName(it)), Tok.tx2) }
            MemoText(stringResource(Res.string.memo_readonly), tightCenter(10.5.sp).merge(TextStyle(fontSize = 10.5.sp)), Tok.muted)
        }
        MemoText(summary, MemoType.body, Tok.tx2, Modifier.padding(top = 8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodoSection(
    doc: MemoDocumentState,
    unorganized: Boolean,
    canOrganize: Boolean,
    onAction: (MemoAction) -> Unit,
    onOrganize: () -> Unit,
) {
    val anyDispatched = doc.todos.any { it.state != MemoTodoState.DRAFT }
    Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MemoSectionLabel(stringResource(Res.string.memo_todos))
        MemoText(
            if (anyDispatched || doc.todos.isEmpty()) stringResource(Res.string.memo_todo_count, doc.todos.size)
            else stringResource(Res.string.memo_todo_count_default, doc.todos.size),
            MemoType.mono, Tok.muted, Modifier.weight(1f),
        )
    }
    Column(Modifier.padding(top = 10.dp)) {
        Hairline()
        doc.todos.forEachIndexed { i, row -> TodoRow(i + 1, row, onAction) }
        if (doc.todos.isEmpty()) {
            MemoText(
                stringResource(if (unorganized) Res.string.memo_no_todos_raw else Res.string.memo_no_todos),
                MemoType.caption, Tok.tx2, Modifier.padding(vertical = 14.dp),
            )
            Hairline()
        }
        val canAdd = !doc.atTodoLimit
        // "整段作为一项" belongs to an unorganised memo with nothing written yet; too long a transcript disables it
        val offerWhole = unorganized && doc.todos.isEmpty()
        val organizer = doc.organizerAvailable
        // the actions wrap onto more lines at large type, each keeping its own 48 dp target
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(
                Modifier.heightIn(min = Metric.touch)
                    .clickable(enabled = canAdd, role = Role.Button) { if (canAdd) onAction(MemoAction.AddTodo) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // "+" glyph and label at different sizes: both tightCenter-based
                MemoText("+", tightCenter(22.sp).merge(TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Light)), if (canAdd) Tok.accent else Tok.muted, Modifier.width(24.dp).clearAndSetSemantics { }, textAlign = TextAlign.Center)
                MemoText(stringResource(Res.string.memo_add_todo), MemoType.bodyStrong, if (canAdd) Tok.accent else Tok.muted)
            }
            if (offerWhole) {
                val fits = doc.wholeTranscriptFits
                val label = stringResource(Res.string.memo_whole_as_todo)
                val unavailable = stringResource(Res.string.memo_unavailable_cd, label, stringResource(Res.string.memo_whole_too_long))
                TodoListAction(
                    label, enabled = fits,
                    modifier = if (fits) Modifier else Modifier.semantics { contentDescription = unavailable },
                ) { if (fits) onAction(MemoAction.AddWholeTranscriptTodo) }
            }
            if (unorganized && canOrganize && organizer != null) {
                if (doc.reorganizing) OrganizingLine(stringResource(Res.string.memo_organizing))
                else TodoListAction(organizeLabel(organizer, again = false), onClick = onOrganize)
            }
        }
        if (offerWhole && !doc.wholeTranscriptFits) {
            MemoText(stringResource(Res.string.memo_whole_too_long), MemoType.caption, Tok.tx2, Modifier.padding(bottom = 10.dp))
        }
        if (!canAdd) MemoText(stringResource(Res.string.memo_todo_limit, VoiceMemoLimits.MAX_TODOS), MemoType.caption, Tok.tx2)
        if (doc.saveFailed) MemoText(stringResource(Res.string.memo_save_failed_note), MemoType.caption, Tok.danger, Modifier.padding(top = 6.dp))
    }
}

/** A text action in the to-do list's action row ("整段作为一项", "用 X 整理"): 48 dp tall, accent, or muted when off. */
@Composable
private fun TodoListAction(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = Metric.touch).clip(RoundedCornerShape(Metric.radiusS))
            // guarded as well as disabled: an assistive "activate" on a disabled node must not act
            .clickable(enabled = enabled, role = Role.Button) { if (enabled) onClick() },
        contentAlignment = Alignment.CenterStart,
    ) { MemoText(label, MemoType.bodyStrong, if (enabled) Tok.accent else Tok.muted) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodoRow(index: Int, row: MemoTodoRow, onAction: (MemoAction) -> Unit) {
    val editable = row.editable
    val failed = row.state == MemoTodoState.FAILED
    val (mark, tint) = todoMark(row.state)
    val bodyLine = lineHeightDp(MemoType.body)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().bleed(12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (editable) TodoCheckbox(row.selected, stringResource(Res.string.memo_todo_check_cd, index)) { onAction(MemoAction.ToggleTodo(row.todoId)) }
            else Box(Modifier.size(Metric.touch).clearAndSetSemantics { }, contentAlignment = Alignment.Center) { MemoMarkGlyph(mark, tint) }
            Column(Modifier.weight(1f).padding(top = ((Metric.touch - bodyLine) / 2).coerceAtLeast(0.dp), bottom = 4.dp)) {
                if (editable) MemoEditableText(
                    key = "todo-${row.todoId}",
                    external = row.text,
                    style = MemoType.body,
                    description = stringResource(Res.string.memo_todo_edit_cd, index),
                    placeholder = stringResource(Res.string.memo_todo_placeholder),
                    maxChars = VoiceMemoLimits.MAX_TODO_CODE_POINTS * 2,
                    onCommit = { onAction(MemoAction.EditTodo(row.todoId, it)) },
                ) else MemoText(row.text, MemoType.body, if (row.state == MemoTodoState.DELIVERED) Tok.tx2 else Tok.tx)
                DeliveryLine(
                    row.state,
                    when (row.state) {
                        MemoTodoState.UNKNOWN -> stringResource(Res.string.memo_unknown_note)
                        MemoTodoState.FAILED -> stringResource(Res.string.memo_failed_note)
                        else -> null
                    },
                    Modifier.padding(top = 6.dp),
                )
                row.copiedFromIndex?.let { from ->
                    MemoText(
                        if (row.copyOfUnknown) stringResource(Res.string.memo_copied_from_unknown, from)
                        else stringResource(Res.string.memo_copied_from, from),
                        MemoType.caption, if (row.copyOfUnknown) Tok.warn else Tok.tx2, Modifier.padding(top = 6.dp),
                    )
                }
                val actions = buildList {
                    when (row.state) {
                        MemoTodoState.DELIVERED, MemoTodoState.UNKNOWN -> {
                            add(Triple(stringResource(Res.string.memo_view_session), Tok.accent, MemoAction.ViewSession(row.todoId)))
                            add(Triple(stringResource(Res.string.memo_copy_todo), Tok.tx2, MemoAction.CopyTodo(row.todoId)))
                        }
                        MemoTodoState.FAILED -> add(Triple(stringResource(Res.string.memo_retry_failed), Tok.accent, MemoAction.RetryFailedTodo(row.todoId)))
                    }
                }
                if (actions.isNotEmpty()) FlowRow(Modifier.padding(top = 2.dp).offset(x = (-10).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    actions.forEach { (label, color, action) -> MemoTextAction(label, color) { onAction(action) } }
                }
            }
            if (editable || failed) {
                val removeLabel = stringResource(Res.string.memo_todo_delete_cd)
                Box(
                    Modifier.size(Metric.touch).clip(RoundedCornerShape(Metric.radiusS))
                        .clickable(role = Role.Button, onClickLabel = removeLabel) { onAction(MemoAction.DeleteTodo(row.todoId)) }
                        .semantics { contentDescription = removeLabel },
                    contentAlignment = Alignment.Center,
                ) { RemoveGlyph() }
            } else {
                // no remove target: give back the 12 dp the row bled into the gutter, so text keeps the margin
                Spacer(Modifier.width(8.dp)) // + the Row's 4 dp spacing = 12 dp
            }
        }
        Hairline()
    }
}

/** The shape + word of a delivery state, with an optional note; the same grammar as [MemoDeliveryMark]. */
@Composable
internal fun DeliveryLine(todoState: String, note: String?, modifier: Modifier = Modifier) {
    val (mark, tint) = todoMark(todoState)
    val label = todoStateLabel(todoState)
    val noteColor = Tok.tx2
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        LineMark(mark, tint, MemoType.caption)
        androidx.compose.material3.Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = tint, fontWeight = FontWeight.SemiBold)) { append(label) }
                if (!note.isNullOrBlank()) withStyle(SpanStyle(color = noteColor)) { append("  "); append(note) }
            },
            style = MemoType.caption,
        )
    }
}

@Composable
private fun TodoCheckbox(checked: Boolean, description: String, onToggle: () -> Unit) {
    val box = if (LocalDensity.current.fontScale >= 1.5f) 30.dp else 22.dp
    val accent = Tok.accent
    val onAccent = Tok.base
    val ring = Tok.muted
    Box(
        Modifier.size(Metric.touch).clip(RoundedCornerShape(Metric.radiusS))
            .toggleable(value = checked, role = Role.Checkbox) { onToggle() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(7.dp)
        if (checked) Box(Modifier.size(box).clip(shape).background(accent), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(box * 0.62f)) {
                val w = size.width
                val h = size.height
                val p = Path().apply { moveTo(w * 0.2f, h * 0.53f); lineTo(w * 0.42f, h * 0.72f); lineTo(w * 0.8f, h * 0.3f) }
                drawPath(p, onAccent, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        } else Box(Modifier.size(box).clip(shape).border(1.6.dp, ring, shape))
    }
}

@Composable
private fun RemoveGlyph() {
    val tint = Tok.tx2
    Canvas(Modifier.size(12.dp)) {
        val s = 1.5.dp.toPx()
        drawLine(tint, Offset(0f, 0f), Offset(size.width, size.height), s, cap = StrokeCap.Round)
        drawLine(tint, Offset(size.width, 0f), Offset(0f, size.height), s, cap = StrokeCap.Round)
    }
}

@Composable
private fun DisclosureRow(title: String, trailing: String?, open: Boolean, onToggle: () -> Unit) {
    val state = stringResource(if (open) Res.string.memo_expanded else Res.string.memo_collapsed)
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Metric.touch)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = state },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // body title and mono value side by side: both tightCenter-based
            MemoText(title, MemoType.body.merge(TextStyle(fontWeight = FontWeight.Medium)), Tok.tx, Modifier.weight(1f))
            if (trailing != null) MemoText(trailing, MemoType.mono, Tok.muted)
            DisclosureChevron(open)
        }
        Hairline()
    }
}

@Composable
private fun TranscriptSection(doc: MemoDocumentState, open: Boolean, onToggle: () -> Unit) {
    val transcript = doc.transcript ?: return
    Column(Modifier.padding(top = 14.dp)) {
        Hairline()
        DisclosureRow(stringResource(Res.string.memo_transcript, VoiceMemoValidation.codePoints(transcript)), null, open, onToggle)
        if (open) {
            SelectionContainer {
                MemoText(transcript, MemoType.body.merge(TextStyle(lineHeight = 24.sp)), Tok.tx2, Modifier.padding(top = 12.dp, bottom = 14.dp))
            }
            Hairline()
        }
    }
}

@Composable
private fun TimingsSection(doc: MemoDocumentState, open: Boolean, onToggle: () -> Unit) {
    val t = doc.timings
    val notMeasured = stringResource(Res.string.memo_not_measured)
    @Composable
    fun secs(ms: Long?): String = ms?.let { stringResource(Res.string.memo_step_seconds, fmtSecondsTenths(it)) } ?: notMeasured
    val total = secs(t.totalMs)
    Column(Modifier.padding(top = if (doc.transcript == null) 14.dp else 0.dp)) {
        if (doc.transcript == null) Hairline()
        DisclosureRow(stringResource(Res.string.memo_timings), total, open, onToggle)
        if (open) {
            Column(Modifier.padding(top = 6.dp, bottom = 10.dp)) {
                val rows = buildList {
                    add(stringResource(Res.string.memo_timing_recording) to fmtClock(t.audioDurationMs ?: doc.audioDurationMs))
                    add(stringResource(Res.string.memo_step_upload) to secs(t.uploadMs))
                    if (t.queueMs != null) add(stringResource(Res.string.memo_timing_queue) to secs(t.queueMs))
                    add(stringResource(Res.string.memo_step_transcribe) to secs(t.transcribeMs))
                    // a memo that was never organised skipped that step — it was not left unmeasured
                    add(
                        stringResource(Res.string.memo_step_organize) to
                            if (t.summarizeMs == null && doc.isUnorganized()) stringResource(Res.string.memo_timing_skipped) else secs(t.summarizeMs),
                    )
                    add(stringResource(Res.string.memo_timing_total) to total)
                }
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        MemoText(k, MemoType.caption, Tok.tx2, Modifier.weight(1f))
                        MemoText(v, MemoType.mono, Tok.tx, textAlign = TextAlign.End)
                    }
                }
            }
            Hairline()
        }
    }
}

// ── dispatch bar ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun TargetRow(selection: MemoSelectionState, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val target = selection.target
    val label = target?.let { targetSummary(it.target) } ?: stringResource(Res.string.memo_target_choose)
    val description = if (target != null) stringResource(Res.string.memo_target_change_cd, label) else stringResource(Res.string.memo_target_choose)
    Row(
        modifier.fillMaxWidth().heightIn(min = Metric.touch)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description) { if (enabled) onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            MemoText(stringResource(Res.string.memo_target), MemoType.caption, Tok.muted)
            MemoText(
                label, MemoType.body.merge(TextStyle(fontWeight = FontWeight.Medium)),
                if (target != null) Tok.tx else Tok.accent, Modifier.padding(top = 2.dp),
            )
        }
        ForwardChevron()
    }
}

@Composable
private fun DispatchBar(
    state: MemoUiState,
    doc: MemoDocumentState,
    compact: Boolean,
    busy: Boolean,
    onTargets: () -> Unit,
    onDispatch: () -> Unit,
) {
    val selection = state.selection
    val reason = dispatchBlockReason(selection.block, state.readiness)
    val label = dispatchLabel(selection.count, selection.target?.target?.newSession == true)
    val blockedDescription = reason?.let { stringResource(Res.string.memo_dispatch_blocked_cd, it) }
    // while the target is being opened / created, neither a second dispatch nor a target change is offered
    val canDispatch = selection.canDispatch && !busy
    val button = @Composable {
        MemoPrimaryButton(
            label, enabled = canDispatch,
            modifier = Modifier.then(if (blockedDescription != null) Modifier.semantics { contentDescription = blockedDescription } else Modifier),
            // guarded as well as disabled: an assistive "activate" on a disabled node must not open the sheet
            onClick = { if (canDispatch) onDispatch() },
        )
    }
    MemoBottomBar {
        if (compact) {
            // 150 %+ type: one summary line and the button — the bar must not eat half the screen
            val target = selection.target?.let { stringResource(Res.string.memo_target_line, targetShort(it.target)) }
                ?: stringResource(Res.string.memo_target_line_none)
            val line = listOfNotNull(stringResource(Res.string.memo_selected, selection.count), target, reason).joinToString(MEMO_SEP)
            MemoText(line, MemoType.caption, Tok.tx2, Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Button) { if (!busy) onTargets() })
            button()
        } else {
            TargetRow(selection, enabled = !busy, onClick = onTargets)
            MemoText(selectedLine(doc, selection.count), MemoType.caption, Tok.tx2)
            button()
            if (reason != null) MemoText(reason, MemoType.caption, Tok.tx2, Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun selectedLine(doc: MemoDocumentState, count: Int): String {
    val parts = mutableListOf(stringResource(Res.string.memo_selected, count))
    if (doc.todos.any { !it.editable }) parts += stringResource(Res.string.memo_selected_drafts_only)
    if (doc.todos.any { it.editable && it.selected && it.text.isBlank() }) parts += stringResource(Res.string.memo_selected_blank_skipped)
    return parts.joinToString(MEMO_SEP)
}

// ── confirm ─────────────────────────────────────────────────────────────────────────────────────────

/**
 * The ONLY execution authorisation: exact target, exact numbered text, and — for more than one item — the
 * same-turn notice verbatim. Body scrolls; the notice and both buttons stay pinned.
 */
@Composable
private fun MemoConfirmSheet(state: MemoUiState, onAction: (MemoAction) -> Unit, onClose: () -> Unit) {
    val selection = state.selection
    MemoSheet(
        title = stringResource(Res.string.memo_confirm_title),
        onDismiss = onClose,
        body = {
            selection.target?.let { row -> ConfirmTargetBlock(row, state, Modifier.padding(top = 12.dp)) }
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MemoSectionLabel(stringResource(Res.string.memo_confirm_sequence))
                MemoText(stringResource(Res.string.memo_todo_count, selection.count), MemoType.mono, Tok.muted)
            }
            Column(Modifier.padding(top = 8.dp)) {
                Hairline()
                selection.items.forEachIndexed { i, item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.width(18.dp).height(lineHeightDp(MemoType.body)), contentAlignment = Alignment.CenterStart) {
                            MemoText("${i + 1}", MemoType.mono, Tok.muted)
                        }
                        Column(Modifier.weight(1f)) {
                            // the whole transcript goes out as one item: say that the target's agent will sort it out
                            if (item.wholeTranscript) MemoText(
                                stringResource(Res.string.memo_whole_transcript_note), MemoType.caption, Tok.tx2,
                                Modifier.padding(bottom = 4.dp),
                            )
                            MemoText(item.text.trim(), MemoType.body, Tok.tx)
                        }
                    }
                    Hairline()
                }
            }
        },
    ) {
        if (selection.showsMultiItemNotice) MemoNoticeBlock(
            MemoMark.DIAMOND, Tok.warn, title = null, body = stringResource(Res.string.memo_multi_notice),
        )
        MemoPrimaryButton(dispatchLabel(selection.count, selection.target?.target?.newSession == true), enabled = selection.canDispatch) {
            val target = selection.target?.target
            if (selection.canDispatch && target != null) {
                onClose()
                // exactly what this sheet drew: the data layer refuses the dispatch if the memo moved on since
                onAction(MemoAction.ConfirmDispatch(selection.shown, target))
            }
        }
        MemoOutlineButton(stringResource(Res.string.memo_back_edit), onClick = onClose)
    }
}

/**
 * The confirm sheet's target, in two unmistakable shapes: an existing session sits in a plain hairline box
 * tagged "已有会话"; a session to be created sits in a dashed accent box tagged "新建会话" with the sentence
 * saying item 1 becomes its first message. Both carry the permission mode when it is known.
 */
@Composable
private fun ConfirmTargetBlock(row: MemoTargetRow, state: MemoUiState, modifier: Modifier = Modifier) {
    val target = row.target
    val isNew = target.newSession
    val shape = RoundedCornerShape(12.dp)
    val accent = Tok.accent
    val frame = if (isNew) {
        Modifier.clip(shape).background(accent.copy(alpha = 0.10f)).drawBehind {
            val s = 1.dp.toPx()
            drawRoundRect(
                accent, topLeft = Offset(s / 2, s / 2), size = androidx.compose.ui.geometry.Size(size.width - s, size.height - s),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                style = Stroke(s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
            )
        }
    } else Modifier.clip(shape).background(Tok.base).border(Metric.hairline, Tok.hair, shape)
    val mode = row.mode ?: if (isNew) state.catalog.newSession.modeFor(row.target.agent) else null
    Column(modifier.fillMaxWidth().then(frame).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tagShape = RoundedCornerShape(5.dp)
            val tagStyle = tightCenter(10.5.sp).merge(TextStyle(fontSize = 10.5.sp, fontWeight = if (isNew) FontWeight.SemiBold else null))
            // a boxed tag beside a mono line: both tightCenter-based so they share one visual centre
            if (isNew) MemoText(
                stringResource(Res.string.memo_target_new_session), tagStyle, Tok.base,
                Modifier.clip(tagShape).background(accent).padding(horizontal = 6.dp, vertical = 3.dp),
            ) else MemoText(
                stringResource(Res.string.memo_confirm_existing), tagStyle, Tok.tx2,
                Modifier.clip(tagShape).border(Metric.hairline, Tok.tx2.copy(alpha = 0.45f), tagShape).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            MemoText(targetProjectLine(target, computerName(state.readiness)), MemoType.mono, Tok.muted, Modifier.weight(1f))
        }
        if (!isNew) MemoText(targetTitle(target), MemoType.row, Tok.tx, Modifier.padding(top = 6.dp))
        MemoText(
            if (isNew) dev.ccpocket.app.ui.agentName(target.agent)
            else listOf(dev.ccpocket.app.ui.agentName(target.agent), targetStatusLabel(row.status)).joinToString(MEMO_SEP),
            if (isNew) MemoType.row else MemoType.caption, if (isNew) Tok.tx else Tok.tx2, Modifier.padding(top = if (isNew) 6.dp else 4.dp),
        )
        if (mode != null || isNew) MemoText(
            stringResource(Res.string.memo_confirm_mode_line, mode?.let { permissionModeName(it) } ?: stringResource(Res.string.memo_permission_app_default)),
            MemoType.caption, Tok.tx2, Modifier.padding(top = 4.dp),
        )
        if (isNew) MemoText(stringResource(Res.string.memo_confirm_new_note), MemoType.caption, Tok.tx, Modifier.padding(top = 8.dp))
    }
}
