package dev.ccpocket.app.memo

import dev.ccpocket.protocol.VOICE_MEMO_AGENT_NONE
import dev.ccpocket.protocol.VoiceMemoError
import dev.ccpocket.protocol.VoiceMemoInputKind
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoStage
import dev.ccpocket.protocol.VoiceMemoValidation

// Model → what the screens render. Pure; everything a screen shows is derived here from durable documents plus
// the process-local run state, so no second copy of "what the memo looks like" can drift.

internal fun MemoModel.project(): MemoUiState {
    val cur = currentId?.let { slots[it] }
    return MemoUiState(
        readiness = readiness,
        screen = screen,
        list = list,
        capture = MemoCaptureState(capture.phase, capture.elapsedMs, capture.secondsLeft, capture.leavePrompt),
        processing = cur?.takeIf { showsProcessing(it) }?.let { processingState(it) },
        document = cur?.takeIf { screen == MemoScreen.DETAIL }?.let { documentState(it) },
        selection = if (screen == MemoScreen.DETAIL && cur != null) selection() else MemoSelectionState(target = target),
        catalog = catalog,
        dispatch = batch?.let { dispatchState(it) },
        toast = toast,
    )
}

/**
 * Off the processing page, the processing state is shown only while it says something the result page cannot:
 * an attempt still running, or one that stopped with an issue (degraded, an organise-only attempt that ended
 * transcribed). A transcribed audio result - organiser lost or not - is a finished result: the page reads
 * [MemoDocumentState.organizerLost] instead, and no "running" processing state lingers next to it.
 */
private fun MemoModel.showsProcessing(s: Slot): Boolean {
    if (screen == MemoScreen.PROCESSING) return true
    if (s.run.reorganizing || s.run.hashing != null) return true
    val att = s.doc.processing ?: return false
    if (!att.accepted || att.stage == VoiceMemoStage.DEGRADED) return true
    return att.stage == VoiceMemoStage.TRANSCRIBED && att.inputKind == VoiceMemoInputKind.TRANSCRIPT
}

/** Transcribed, not organised, because the organiser the attempt asked for was gone when its turn came. */
internal fun organizerLost(att: MemoAttempt?): Boolean =
    att != null && att.stage == VoiceMemoStage.TRANSCRIBED && att.errorCode == VoiceMemoError.AGENT_UNAVAILABLE

private fun MemoModel.processingState(s: Slot): MemoProcessingState {
    val doc = s.doc
    val started = s.run.startedAt?.takeIf { !s.run.suspended }
    return MemoProcessingState(
        memoId = doc.memoId,
        audioDurationMs = doc.content.audioDurationMs,
        steps = memoSteps(doc.processing, s.run),
        elapsedMs = started?.let { ((s.run.finishedAt ?: nowMono) - it).coerceAtLeast(0) },
        issue = s.run.issue,
        reachedLimit = s.run.reachedLimit,
        hasTranscript = doc.content.transcript != null,
        hasAudio = doc.content.audio != null,
        organizer = doc.processing?.agent?.takeUnless { it == VOICE_MEMO_AGENT_NONE },
        organizerLost = organizerLost(doc.processing),
    )
}

private fun MemoModel.documentState(s: Slot): MemoDocumentState {
    val doc = s.doc
    val content = doc.content
    return MemoDocumentState(
        memoId = doc.memoId,
        title = content.title,
        titleFallback = memoTitleFallback(content.transcript),
        createdAtMs = doc.createdAtMs,
        audioDurationMs = content.audioDurationMs,
        summary = content.summary,
        summaryUnstructured = content.summaryUnstructured,
        degraded = isDegraded(doc),
        organizerLost = isUnorganisedForLostOrganizer(doc),
        organizedBy = content.organizedBy,
        organizerAvailable = readiness.organizer,
        wholeTranscriptFits = wholeTranscriptText(content.transcript) != null,
        todos = todoRows(doc),
        transcript = content.transcript,
        timings = doc.processing?.timings ?: MemoTimings(),
        saving = s.saving,
        saveFailed = s.saveFailed,
        atTodoLimit = doc.todos.size >= VoiceMemoLimits.MAX_TODOS,
        reorganizing = s.run.reorganizing || s.run.hashing?.reorganize == true,
    )
}

internal fun todoRows(doc: MemoDocument): List<MemoTodoRow> = doc.todos.map { t ->
    val state = doc.todoState(t.todoId)
    val source = t.copiedFrom
    MemoTodoRow(
        todoId = t.todoId,
        text = t.text,
        selected = t.selected,
        state = state,
        errorCode = if (state == MemoTodoState.FAILED) doc.dispatches.lastOrNull { it.todoId == t.todoId && it.state == MemoTodoState.FAILED }?.errorCode else null,
        copiedFromIndex = source?.let { src -> doc.todos.indexOfFirst { it.todoId == src }.takeIf { it >= 0 }?.plus(1) },
        copyOfUnknown = source?.let { doc.todoState(it) == MemoTodoState.UNKNOWN } ?: false,
        suggestedTarget = t.suggestedTarget,
        wholeTranscript = t.wholeTranscript,
    )
}

/**
 * "未能整理成待办": [MemoContent.degraded] (persisted, so it survives a later attempt that runs or fails), or
 * [MemoContent.summaryUnstructured] for a document written before that field.
 *
 * One more fallback for such older documents: a v1 degraded snapshot never carried a result, so an old degraded
 * document has no unstructured summary either - it is recognised by what it is: nobody's summary, no organiser,
 * and a latest attempt that was accepted as degraded. A current document in that shape always has the flag set,
 * so the fallback changes nothing for it.
 */
internal fun isDegraded(doc: MemoDocument): Boolean {
    val content = doc.content
    if (content.degraded || content.summaryUnstructured) return true
    val att = doc.processing
    return content.summary == null && content.organizedBy == null && content.transcript != null &&
        att != null && att.accepted && att.stage == VoiceMemoStage.DEGRADED
}

/**
 * The memo is unorganised (no organiser's summary, not degraded) and its latest accepted attempt ended
 * transcribed because the organiser it asked for was gone. Only the latest attempt is stored, so while a newer
 * attempt runs or after it failed this reads false; that attempt's own state then speaks.
 */
internal fun isUnorganisedForLostOrganizer(doc: MemoDocument): Boolean {
    val content = doc.content
    if (content.organizedBy != null || content.summary != null || isDegraded(doc)) return false
    val att = doc.processing ?: return false
    return att.accepted && organizerLost(att)
}

private val SENTENCE_ENDS = charArrayOf('。', '！', '？', '!', '?', '.', '\n')

/**
 * The title shown for a memo without one: the transcript's first sentence - the text before the first of
 * 。！？!?. or a line break, trimmed (a leading empty piece is skipped), at most
 * [VoiceMemoLimits.MAX_TITLE_CODE_POINTS] code points. Invisible and control characters are removed first, as for
 * any title. "" when there is no transcript.
 */
internal fun memoTitleFallback(transcript: String?): String {
    val text = transcript?.let(::sanitizeMemoText) ?: return ""
    val first = text.split(*SENTENCE_ENDS).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
    return clampCodePoints(first, VoiceMemoLimits.MAX_TITLE_CODE_POINTS).trimEnd()
}

/** The whole transcript as one to-do's text (sanitized like any to-do), or null when it is blank or longer than
 *  one to-do may be. */
internal fun wholeTranscriptText(transcript: String?): String? {
    val text = transcript?.let(::sanitizeMemoText) ?: return null
    if (text.isBlank() || VoiceMemoValidation.codePoints(text) > VoiceMemoLimits.MAX_TODO_CODE_POINTS) return null
    return text
}

/** Block priority: SAVING > BUSY (a new attempt is under way) > OFFLINE > BUSY (a batch runs) > NO_SELECTION > ONLY_BLANK > NO_TARGET > TARGET_UNAVAILABLE. */
internal fun MemoModel.selection(): MemoSelectionState {
    val s = currentId?.let { slots[it] } ?: return MemoSelectionState(target = target)
    val frozen = batch?.takeIf { it.active }?.items?.map { it.todoId }?.toSet().orEmpty()
    val drafts = todoRows(s.doc).filter { it.selected && it.state == MemoTodoState.DRAFT && it.todoId !in frozen }
    val items = drafts.filter { it.text.isNotBlank() }
    // An existing session takes its live status from whichever loaded row shows it; one not shown in any loaded
    // row (a project not opened) stays picked — the host re-checks it on entry. A new-session pick is itself.
    val latest = target?.let { t -> if (t.target.newSession) t else catalog.loadedRows().firstOrNull { it.target.sameAs(t.target) } }
    val agents = catalog.newSession.agents
    val block = when {
        s.saving -> MemoDispatchBlock.SAVING
        // A re-organise (or any new attempt) is under way: its result may replace these drafts.
        s.run.reorganizing || s.run.hashing != null || s.run.starting || s.run.upload != null -> MemoDispatchBlock.BUSY
        !readiness.online -> MemoDispatchBlock.OFFLINE
        batch?.active == true -> MemoDispatchBlock.BUSY
        drafts.isEmpty() -> MemoDispatchBlock.NO_SELECTION
        items.isEmpty() -> MemoDispatchBlock.ONLY_BLANK
        target == null -> MemoDispatchBlock.NO_TARGET
        target.target.newSession -> if (agents.isNotEmpty() && target.target.agent !in agents) MemoDispatchBlock.TARGET_UNAVAILABLE else MemoDispatchBlock.NONE
        latest != null && !latest.selectable -> MemoDispatchBlock.TARGET_UNAVAILABLE
        else -> MemoDispatchBlock.NONE
    }
    return MemoSelectionState(target = latest ?: target, items = items, block = block)
}

private fun MemoModel.dispatchState(b: Batch): MemoDispatchState? {
    val phase = b.phase ?: return null
    val doc = slots[b.memoId]?.doc ?: return null
    val records = doc.dispatches.filter { it.batchId == b.batchId }
    return MemoDispatchState(
        batchId = b.batchId,
        memoId = b.memoId,
        memoTitle = doc.content.title,
        target = b.target,
        convoId = b.lease?.convoId,
        creating = b.creating,
        openFailure = b.openFailure,
        phase = phase,
        stop = b.stop,
        total = b.items.size,
        current = if (phase == MemoDispatchPhase.SENDING) b.cursor + 1 else 0,
        delivered = records.count { it.state == MemoTodoState.DELIVERED },
        unknown = records.count { it.state == MemoTodoState.UNKNOWN },
        failed = records.count { it.state == MemoTodoState.FAILED },
    )
}

private val UPLOAD_ERRORS = setOf(
    VoiceMemoError.NOT_READY, VoiceMemoError.BUSY, VoiceMemoError.INVALID_INPUT, VoiceMemoError.INPUT_CONFLICT,
    VoiceMemoError.UPLOAD_TIMEOUT, VoiceMemoError.AUDIO_INVALID, VoiceMemoError.AUDIO_TOO_LONG, VoiceMemoError.UNSUPPORTED,
)
private val TRANSCRIBE_ERRORS = setOf(
    VoiceMemoError.TRANSCRIBE_FAILED, VoiceMemoError.TRANSCRIBE_TIMEOUT, VoiceMemoError.EMPTY_TRANSCRIPT, VoiceMemoError.TRANSCRIPT_TOO_LONG,
)

/** Upload → transcribe → organise, from the attempt's stage alone: no percentage, no estimate. The organise step
 *  is SKIPPED for a transcribe-only attempt throughout, and for any attempt that ended TRANSCRIBED. */
internal fun memoSteps(att: MemoAttempt?, run: Run): List<MemoStepRow> {
    val W = MemoStepStatus.WAITING
    val R = MemoStepStatus.RUNNING
    val D = MemoStepStatus.DONE
    val F = MemoStepStatus.FAILED
    val N = MemoStepStatus.NOT_STARTED
    val S = MemoStepStatus.SKIPPED
    val t = att?.timings ?: MemoTimings()
    val statuses: List<MemoStepStatus> = when {
        att == null -> listOf(W, W, W)
        att.inputKind == VoiceMemoInputKind.TRANSCRIPT -> listOf(
            D, D,
            when (att.stage) {
                VoiceMemoStage.SUMMARIZING -> R
                VoiceMemoStage.READY -> D
                VoiceMemoStage.TRANSCRIBED -> S
                VoiceMemoStage.DEGRADED, VoiceMemoStage.FAILED -> F
                VoiceMemoStage.CANCELLED, VoiceMemoStage.UNKNOWN, MemoLocalStage.INTERRUPTED -> N
                else -> W
            },
        )
        else -> when (att.stage) {
            MemoLocalStage.SAVED, MemoLocalStage.UPLOADING, VoiceMemoStage.RECEIVING ->
                if (run.issue == MemoProcessingIssue.UPLOAD_INCOMPLETE || run.issue == MemoProcessingIssue.UPLOAD_FAILED ||
                    run.issue == MemoProcessingIssue.UPLOAD_INTERRUPTED
                ) listOf(F, N, N) else listOf(R, W, W)
            MemoLocalStage.INTERRUPTED -> listOf(F, N, N)
            VoiceMemoStage.QUEUED -> listOf(D, W, W)
            VoiceMemoStage.TRANSCRIBING -> listOf(D, R, W)
            VoiceMemoStage.SUMMARIZING -> listOf(D, D, R)
            VoiceMemoStage.READY -> listOf(D, D, D)
            VoiceMemoStage.TRANSCRIBED -> listOf(D, D, S)
            VoiceMemoStage.DEGRADED -> listOf(D, D, F)
            VoiceMemoStage.FAILED -> when {
                att.remoteRevision == 0L || att.errorCode in UPLOAD_ERRORS -> listOf(F, N, N)
                att.errorCode in TRANSCRIBE_ERRORS -> listOf(D, F, N)
                else -> listOf(D, D, F)
            }
            else -> {
                val uploaded = t.uploadMs != null || t.queueMs != null || t.transcribeMs != null
                listOf(if (uploaded) D else N, if (t.transcribeMs != null) D else N, N)
            }
        }
    }
    // Transcribe-only: there is nothing to organise, from the first moment to the last.
    val final = if (att?.agent == VOICE_MEMO_AGENT_NONE) statuses.take(2) + S else statuses
    val durations = listOf(t.uploadMs, t.transcribeMs, t.summarizeMs)
    return MemoStep.entries.mapIndexed { i, step ->
        MemoStepRow(step, final[i], durations[i].takeIf { final[i] == D })
    }
}
