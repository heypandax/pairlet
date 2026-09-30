package dev.ccpocket.app.memo

import dev.ccpocket.protocol.VOICE_MEMO_AGENT_NONE
import dev.ccpocket.protocol.VoiceMemoError
import dev.ccpocket.protocol.VoiceMemoGet
import dev.ccpocket.protocol.VoiceMemoCancel
import dev.ccpocket.protocol.VoiceMemoInputKind
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoMetrics
import dev.ccpocket.protocol.VoiceMemoStage
import dev.ccpocket.protocol.VoiceMemoStart
import dev.ccpocket.protocol.VoiceMemoState
import dev.ccpocket.protocol.VoiceMemoValidation

internal const val DEFAULT_RECEIPT_TIMEOUT_MS = 10_000L
internal const val GET_INTERVAL_MS = 10_000L
internal const val MEMO_PAGE = 50
internal const val SECONDS_LEFT_FROM_MS = 165_000L
internal const val LIMIT_TIMER = "limit"
internal fun getTimer(memoId: String) = "get:$memoId"
internal fun receiptTimer(promptId: String) = "receipt:$promptId"
internal fun submitTimer(promptId: String) = "submit:$promptId"

/**
 * Text that reaches the screen or a prompt: Unicode format characters (Cf — zero-width, bidi overrides, tags) and
 * control characters (Cc) are removed, except line feed and tab. Applied to organiser output and to user edits
 * BEFORE length and blank checks, so invisible characters can neither hide text nor pad an item past "blank".
 * Supplementary-plane Cf ranges are matched by code point, since [Char.category] only sees one UTF-16 unit.
 */
internal fun sanitizeMemoText(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            val cp = 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
            if (!isSupplementaryFormat(cp)) out.append(c).append(text[i + 1])
            i += 2
            continue
        }
        val drop = when (c.category) {
            CharCategory.FORMAT -> true
            CharCategory.CONTROL -> c != '\n' && c != '\t'
            else -> false
        }
        if (!drop) out.append(c)
        i++
    }
    return out.toString()
}

private fun isSupplementaryFormat(cp: Int): Boolean =
    cp == 0x110BD || cp == 0x110CD || cp in 0x13430..0x1343F || cp in 0x1BCA0..0x1BCA3 ||
        cp in 0x1D173..0x1D17A || cp == 0xE0001 || cp in 0xE0020..0xE007F

/** Stages at which the computer may still hold (or be building) this attempt: a reconnect asks, never resends. */
internal val QUERYABLE_STAGES = setOf(
    MemoLocalStage.SAVED, MemoLocalStage.UPLOADING, MemoLocalStage.INTERRUPTED,
    VoiceMemoStage.RECEIVING, VoiceMemoStage.QUEUED, VoiceMemoStage.TRANSCRIBING, VoiceMemoStage.SUMMARIZING,
)
private val POLLED_STAGES = QUERYABLE_STAGES - MemoLocalStage.INTERRUPTED
private val UPLOAD_STAGES = setOf(MemoLocalStage.SAVED, MemoLocalStage.UPLOADING, VoiceMemoStage.RECEIVING)

/**
 * What stopped processing, from the daemon's words. Pure and total: a code this build does not know is
 * [MemoProcessingIssue.INCOMPATIBLE], never a silent "fine". Null = nothing stopped (a running stage, or ready).
 *
 * `not_ready`, `invalid_input` and `input_conflict` arrive as the revision-0 refusal of a start the daemon did
 * not register — nothing was processed, so they read as an upload that failed and may be retried by the user.
 */
fun memoProcessingIssue(stage: String, errorCode: String?): MemoProcessingIssue? = when (stage) {
    VoiceMemoStage.CANCELLED -> MemoProcessingIssue.CANCELLED
    VoiceMemoStage.UNKNOWN -> MemoProcessingIssue.RECORD_UNAVAILABLE
    VoiceMemoStage.FAILED, VoiceMemoStage.DEGRADED -> when (errorCode) {
        VoiceMemoError.BUSY -> MemoProcessingIssue.COMPUTER_BUSY
        VoiceMemoError.NOT_READY, VoiceMemoError.INVALID_INPUT, VoiceMemoError.INPUT_CONFLICT,
        VoiceMemoError.UPLOAD_TIMEOUT -> MemoProcessingIssue.UPLOAD_FAILED
        VoiceMemoError.AUDIO_INVALID, VoiceMemoError.AUDIO_TOO_LONG -> MemoProcessingIssue.AUDIO_REJECTED
        VoiceMemoError.TRANSCRIBE_FAILED, VoiceMemoError.TRANSCRIPT_TOO_LONG -> MemoProcessingIssue.TRANSCRIBE_FAILED
        VoiceMemoError.TRANSCRIBE_TIMEOUT -> MemoProcessingIssue.TRANSCRIBE_TIMEOUT
        VoiceMemoError.EMPTY_TRANSCRIPT -> MemoProcessingIssue.EMPTY_TRANSCRIPT
        VoiceMemoError.AGENT_UNAVAILABLE -> MemoProcessingIssue.AGENT_UNAVAILABLE
        VoiceMemoError.SUMMARY_FAILED, VoiceMemoError.INVALID_RESULT -> MemoProcessingIssue.ORGANIZE_FAILED
        VoiceMemoError.SUMMARY_TIMEOUT -> MemoProcessingIssue.ORGANIZE_TIMEOUT
        VoiceMemoError.CANCELLED -> MemoProcessingIssue.CANCELLED
        VoiceMemoError.UNKNOWN_JOB -> MemoProcessingIssue.RECORD_UNAVAILABLE
        else -> MemoProcessingIssue.INCOMPATIBLE
    }
    MemoLocalStage.INTERRUPTED, MemoLocalStage.SAVED, MemoLocalStage.UPLOADING -> MemoProcessingIssue.UPLOAD_INTERRUPTED
    else -> null
}

/** The issue a document read from disk implies before anything was heard in this process. An attempt that was
 *  saved or uploading when the app died is an interrupted upload: only a query may follow, never a resend. */
internal fun issueFromDisk(attempt: MemoAttempt?): MemoProcessingIssue? = when (attempt?.stage) {
    null, VoiceMemoStage.READY, VoiceMemoStage.RECEIVING, VoiceMemoStage.QUEUED,
    VoiceMemoStage.TRANSCRIBING, VoiceMemoStage.SUMMARIZING -> null
    VoiceMemoStage.TRANSCRIBED -> transcribedIssue(attempt)
    else -> memoProcessingIssue(attempt.stage, attempt.errorCode)
}

/**
 * TRANSCRIBED is the whole result of an audio attempt (transcribe-only, or the organiser was gone): not an issue.
 * An organise-only attempt, though, exists only because the user asked for organising - ending transcribed means
 * no organiser did it, whatever the error word says.
 */
internal fun transcribedIssue(attempt: MemoAttempt): MemoProcessingIssue? =
    if (attempt.inputKind == VoiceMemoInputKind.TRANSCRIPT) MemoProcessingIssue.AGENT_UNAVAILABLE else null

/** Whole seconds left before the 3:00 stop, shown from 2:45 on; rounded up so the display never reads 0 early. */
internal fun secondsLeft(elapsedMs: Long): Int? =
    if (elapsedMs < SECONDS_LEFT_FROM_MS) null
    else ((VoiceMemoLimits.MAX_RECORDING_MS - elapsedMs).coerceAtLeast(0) + 999).div(1000).toInt()

/** The first [max] Unicode code points of [text] (a surrogate pair is never split). */
internal fun clampCodePoints(text: String, max: Int): String {
    var count = 0
    var i = 0
    while (i < text.length) {
        if (count == max) return text.substring(0, i)
        val c = text[i]
        i += if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return text
}

internal fun MemoTarget.sameAs(other: MemoTarget): Boolean =
    bindingId == other.bindingId && sessionId == other.sessionId && workdir == other.workdir && agent == other.agent &&
        newSession == other.newSession

/** Every existing-session row the picker has loaded: "recent" plus the open project's list. */
internal fun MemoTargetCatalog.loadedRows(): List<MemoTargetRow> = recent + (project?.sessions ?: emptyList())

private fun MemoTimings.merge(metrics: VoiceMemoMetrics): MemoTimings = copy(
    audioDurationMs = metrics.audioDurationMs ?: audioDurationMs,
    queueMs = metrics.queueMs ?: queueMs,
    transcribeMs = metrics.transcribeMs ?: transcribeMs,
    summarizeMs = metrics.summarizeMs ?: summarizeMs,
    coldStart = metrics.coldStart ?: coldStart,
)

/**
 * The memo state machine (code design §4.3–§4.5): `reduce(model, event, now) -> (model, effects)`. It reads no
 * clock, file, socket or port — time arrives in [now], results arrive as events — so every race (a stale start
 * callback, a late reply, a receipt before its submit returns) is a plain function test. The only inputs besides
 * its arguments are the id generators, which tests pin.
 *
 * Every asynchronous operation carries a token (capture generation, upload token, get token, commit seq, batch
 * and prompt id); a result whose token is no longer current is dropped, so it can never revive a newer capture,
 * attempt or batch.
 */
internal class MemoReducer(
    private val newId: () -> String,
    newPromptId: () -> String,
    receiptTimeoutMs: Long = DEFAULT_RECEIPT_TIMEOUT_MS,
) {
    private val dispatch = MemoDispatchCoordinator(newId, newPromptId, receiptTimeoutMs)

    fun reduce(model: MemoModel, event: MemoEvent, now: MemoNow): MemoReduction {
        val tx = MemoTx(model.copy(nowMono = now.mono), now)
        tx.handle(event)
        tx.gc()
        tx.syncTicker()
        return MemoReduction(tx.m, tx.fx.toList())
    }

    // ── dispatch ──────────────────────────────────────────────────────────────────────────────────────

    private fun MemoTx.handle(e: MemoEvent) {
        when (e) {
            is MemoEvent.Act -> act(e.action)
            is MemoEvent.Readiness -> onReadiness(e.readiness)
            is MemoEvent.Remote -> onRemote(e.remote)
            is MemoEvent.Receipt -> {
                val id = dispatch.memoFor(this, e.receipt.promptId)
                if (id != null && slot(id) == null && id !in m.deleted) {
                    load(id); queue(id, e)
                } else {
                    dispatch.onReceipt(this, e.receipt)
                }
            }
            is MemoEvent.DispatchStopped -> dispatch.onStop(this, e.stop)
            MemoEvent.RecorderInterrupted -> interruptCapture()
            MemoEvent.Tick -> onTick()
            is MemoEvent.LimitReached -> if (e.gen == m.capture.gen) stopRecording(reachedLimit = true)
            is MemoEvent.ListLoaded -> onListLoaded(e)
            is MemoEvent.ConsentLoaded -> if (e.scope == m.scope) m = m.copy(consent = e.accepted)
            is MemoEvent.RecorderStarted -> onRecorderStarted(e)
            is MemoEvent.AudioSaved -> onAudioSaved(e)
            is MemoEvent.DocLoaded -> onDocLoaded(e)
            is MemoEvent.Committed -> onCommitted(e)
            is MemoEvent.Reread -> onReread(e)
            is MemoEvent.UploadFirstChunk -> {
                if (slot(e.memoId)?.run?.upload?.token == e.token) updateRun(e.memoId) { it.copy(firstChunkAt = now.mono) }
            }
            is MemoEvent.UploadFinished -> onUploadFinished(e.memoId, e.token, e.outcome)
            is MemoEvent.TranscriptHashed -> onTranscriptHashed(e)
            is MemoEvent.SendFinished -> onSendFinished(e)
            is MemoEvent.GetTimeout -> onGetTimeout(e)
            is MemoEvent.Deleted -> onDeleted(e)
            is MemoEvent.StartupScanned -> if (e.scope == m.scope) e.withSending.forEach { load(it) }
            is MemoEvent.CatalogLoaded -> {
                if (e.project != m.openProject) return // a read for a level the picker has left
                m = m.copy(catalog = e.catalog ?: m.catalog.copy(status = MemoListStatus.FAILED))
            }
            is MemoEvent.Resolved -> dispatch.onResolved(this, e.batchId, e.target, e.final)
            is MemoEvent.Entered -> dispatch.onEntered(this, e.batchId, e.result)
            is MemoEvent.HoldsChecked -> dispatch.onHolds(this, e.batchId, e.promptId, e.held)
            is MemoEvent.Submitted -> dispatch.onSubmitted(this, e.batchId, e.promptId, e.result)
            is MemoEvent.ReceiptTimeout -> dispatch.onReceiptTimeout(this, e.batchId, e.promptId)
            is MemoEvent.SubmitTimeout -> dispatch.onSubmitTimeout(this, e.batchId, e.promptId)
        }
    }

    private fun MemoTx.act(a: MemoAction) {
        when (a) {
            MemoAction.Opened -> m.scope?.let { scope ->
                reloadList()
                emit(MemoEffect.LoadConsent(scope))
                startupOnce(scope)
            }
            MemoAction.Closed -> onClosed()
            MemoAction.Foreground -> {
                m = m.copy(foreground = true)
                queryInProgress()
            }
            MemoAction.Background -> onBackground()
            MemoAction.LoadMore -> loadMore()
            is MemoAction.OpenMemo -> openMemo(a.memoId)
            is MemoAction.DeleteMemo -> deleteMemo(a.memoId)
            MemoAction.BackToList -> backToList()
            MemoAction.NewMemo, MemoAction.RetryRecording -> newMemo()
            MemoAction.AcceptConsent -> acceptConsent()
            MemoAction.DeclineConsent -> if (m.capture.phase == MemoCapturePhase.CONSENT) m = m.copy(capture = Capture(gen = m.capture.gen))
            MemoAction.StopRecording -> stopRecording(reachedLimit = false)
            MemoAction.DiscardRecording -> discardCapture()
            MemoAction.RequestLeaveRecording ->
                if (m.capture.phase == MemoCapturePhase.RECORDING) m = m.copy(capture = m.capture.copy(leavePrompt = true))
            MemoAction.ContinueRecording -> m = m.copy(capture = m.capture.copy(leavePrompt = false))
            MemoAction.OpenMicSettings -> emit(MemoEffect.OpenMicSettings)
            MemoAction.CancelProcessing -> cancelProcessing()
            MemoAction.ResumeUpload -> resumeUpload()
            MemoAction.RetryProcessing -> retry(forceAudio = false)
            MemoAction.RetryTranscription -> retry(forceAudio = true)
            MemoAction.ShowTranscript -> current()?.let { (id, s) ->
                if (s.doc.content.transcript != null) showDetail(id)
            }
            is MemoAction.EditTitle -> editTitle(a.title)
            is MemoAction.ToggleTodo -> editTodo(a.todoId) { t, _ -> t.copy(selected = !t.selected) }
            is MemoAction.EditTodo -> editTodo(a.todoId) { t, _ ->
                val text = clampCodePoints(sanitizeMemoText(a.text), VoiceMemoLimits.MAX_TODO_CODE_POINTS)
                // Once the text is changed it is no longer "the whole transcript".
                t.copy(text = text, wholeTranscript = t.wholeTranscript && text == t.text)
            }
            is MemoAction.DeleteTodo -> editTodo(a.todoId) { _, _ -> null }
            MemoAction.AddTodo -> addTodo()
            MemoAction.AddWholeTranscriptTodo -> addWholeTranscriptTodo()
            is MemoAction.CopyTodo -> copyTodo(a.todoId)
            is MemoAction.RetryFailedTodo -> retryFailedTodo(a.todoId)
            MemoAction.Reorganize -> reorganize()
            is MemoAction.ViewSession -> current()?.let { (_, s) ->
                val record = s.doc.dispatches.lastOrNull { it.todoId == a.todoId && it.state != MemoTodoState.DRAFT }
                    ?: s.doc.latestDispatch(a.todoId)
                record?.let { emit(MemoEffect.ShowTarget(it.target)) }
            }
            MemoAction.RefreshTargets -> loadCatalog()
            is MemoAction.OpenTargetProject -> {
                m = m.copy(openProject = a.workdir)
                loadCatalog()
            }
            MemoAction.CloseTargetProject -> {
                m = m.copy(openProject = null)
                loadCatalog()
            }
            is MemoAction.SelectTarget -> if (!a.target.newSession) {
                m.catalog.loadedRows().firstOrNull { it.target.sameAs(a.target) && it.selectable }?.let { m = m.copy(target = it) }
            }
            is MemoAction.SelectNewSession -> selectNewSession(a.workdir, a.agent)
            is MemoAction.ConfirmDispatch -> dispatch.confirm(this, a)
            MemoAction.ReturnToMemo -> dispatch.returnToMemo(this)
            MemoAction.ToastShown -> m = m.copy(toast = null)
        }
    }

    // ── scope, lifecycle, list ────────────────────────────────────────────────────────────────────────

    private fun MemoTx.onReadiness(r: MemoReadiness) {
        val prev = m.readiness
        m = m.copy(readiness = r)
        if (r.scope != m.scope) {
            switchScope(r.scope)
            return
        }
        if (r.online && (!prev.online || prev.connectionGeneration != r.connectionGeneration)) {
            // A send captured under the old connection can no longer be written: its upload is interrupted, and
            // the new connection only asks what the computer holds.
            for ((id, s) in m.slots) {
                val up = s.run.upload ?: continue
                if (up.generation != r.connectionGeneration) {
                    cancelUpload(id)
                    interruptUpload(id, MemoProcessingIssue.UPLOAD_INTERRUPTED)
                }
            }
            queryInProgress()
        }
    }

    private fun MemoTx.switchScope(scope: MemoScope?) {
        val c = m.capture
        val capture = when (c.phase) {
            MemoCapturePhase.PREPARING, MemoCapturePhase.RECORDING -> {
                emit(MemoEffect.CancelRecorder)
                emit(MemoEffect.CancelTimer(LIMIT_TIMER))
                Capture(gen = c.gen + 1)
            }
            MemoCapturePhase.SAVING -> c // the stop already has its audio; let it land in its own scope
            else -> Capture(gen = c.gen)
        }
        m = m.copy(
            scope = scope, capture = capture, screen = MemoScreen.LIST, currentId = null, list = MemoListState(),
            libraryCount = null, catalog = MemoTargetCatalog(), openProject = null, target = null, consent = false, openAfterLoad = null,
        )
        if (scope != null) {
            reloadList()
            emit(MemoEffect.LoadConsent(scope))
            startupOnce(scope)
        }
    }

    private fun MemoTx.startupOnce(scope: MemoScope) {
        if (scope in m.startedScopes) return
        m = m.copy(startedScopes = m.startedScopes + scope)
        emit(MemoEffect.Startup(scope))
    }

    private fun MemoTx.onClosed() {
        val c = m.capture
        val capture = when (c.phase) {
            MemoCapturePhase.PREPARING, MemoCapturePhase.RECORDING -> {
                emit(MemoEffect.CancelRecorder)
                emit(MemoEffect.CancelTimer(LIMIT_TIMER))
                Capture(gen = c.gen + 1)
            }
            MemoCapturePhase.SAVING -> c
            else -> Capture(gen = c.gen)
        }
        val batch = m.batch?.takeIf { it.active }
        m = m.copy(capture = capture, screen = MemoScreen.LIST, currentId = null, batch = batch)
    }

    private fun MemoTx.onBackground() {
        m = m.copy(foreground = false)
        interruptCapture()
        for ((id, s) in m.slots) {
            if (s.run.upload != null) {
                cancelUpload(id)
                interruptUpload(id, MemoProcessingIssue.UPLOAD_INTERRUPTED)
            }
            if (s.run.startedAt != null && s.run.finishedAt == null) updateRun(id) { it.copy(suspended = true) }
        }
    }

    private fun MemoTx.reloadList() {
        val scope = m.scope ?: return
        val token = token()
        m = m.copy(listToken = token)
        emit(MemoEffect.LoadList(token, scope, 0, maxOf(MEMO_PAGE, m.list.rows.size), append = false))
    }

    private fun MemoTx.loadMore() {
        val scope = m.scope ?: return
        if (!m.list.hasMore) return
        val token = token()
        m = m.copy(listToken = token)
        emit(MemoEffect.LoadList(token, scope, m.list.rows.size, MEMO_PAGE, append = true))
    }

    private fun MemoTx.onListLoaded(e: MemoEvent.ListLoaded) {
        if (e.token != m.listToken || e.scope != m.scope) return
        val count = (e.count as? MemoRead.Found)?.value
        m = when (val rows = e.rows) {
            is MemoRead.Found -> {
                val merged = if (e.append) (m.list.rows + rows.value).distinctBy { it.memoId } else rows.value
                val visible = merged.filter { it.memoId !in m.deleted && it.memoId !in m.deleting }
                m.copy(
                    list = MemoListState(loaded = true, unreadable = false, rows = visible, hasMore = count != null && merged.size < count),
                    libraryCount = count ?: m.libraryCount,
                )
            }
            // Never an empty library: the rows already shown stay, flagged as unreadable.
            else -> m.copy(list = m.list.copy(loaded = true, unreadable = true), libraryCount = count ?: m.libraryCount)
        }
    }

    // ── capture ───────────────────────────────────────────────────────────────────────────────────────

    private fun MemoTx.newMemo() {
        val c = m.capture
        if (c.phase in setOf(MemoCapturePhase.CONSENT, MemoCapturePhase.PREPARING, MemoCapturePhase.RECORDING, MemoCapturePhase.SAVING)) return
        val scope = m.scope ?: return
        if (!m.readiness.canRecord) return
        if ((m.libraryCount ?: 0) >= VoiceMemoLimits.MAX_LOCAL_MEMOS) {
            m = m.copy(toast = MemoToast.LIBRARY_FULL)
            return
        }
        if (!m.consent) {
            m = m.copy(capture = Capture(gen = c.gen, phase = MemoCapturePhase.CONSENT))
            return
        }
        startCapture(scope)
    }

    private fun MemoTx.acceptConsent() {
        if (m.capture.phase != MemoCapturePhase.CONSENT) return
        val scope = m.scope ?: return
        emit(MemoEffect.AcceptConsent(scope))
        m = m.copy(consent = true)
        if (!m.readiness.canRecord) {
            m = m.copy(capture = Capture(gen = m.capture.gen))
            return
        }
        startCapture(scope)
    }

    private fun MemoTx.startCapture(scope: MemoScope) {
        val gen = m.capture.gen + 1
        m = m.copy(capture = Capture(phase = MemoCapturePhase.PREPARING, gen = gen, scope = scope), screen = MemoScreen.RECORDING, currentId = null)
        emit(MemoEffect.StartRecorder(gen))
    }

    private fun MemoTx.onRecorderStarted(e: MemoEvent.RecorderStarted) {
        val c = m.capture
        if (e.gen != c.gen || c.phase != MemoCapturePhase.PREPARING) {
            // A start that outlived its capture must not keep the microphone — unless a newer capture owns it now.
            val newerActive = c.phase == MemoCapturePhase.PREPARING || c.phase == MemoCapturePhase.RECORDING
            if (e.result == MemoRecorderStart.Started && !newerActive) emit(MemoEffect.CancelRecorder)
            return
        }
        m = m.copy(
            capture = when (e.result) {
                MemoRecorderStart.Started -> {
                    emit(MemoEffect.StartTimer(LIMIT_TIMER, VoiceMemoLimits.MAX_RECORDING_MS, MemoEvent.LimitReached(c.gen)))
                    c.copy(phase = MemoCapturePhase.RECORDING, startedAt = now.mono, elapsedMs = 0)
                }
                MemoRecorderStart.PermissionDenied -> c.copy(phase = MemoCapturePhase.PERMISSION_DENIED)
                MemoRecorderStart.Busy -> c.copy(phase = MemoCapturePhase.MIC_BUSY)
                MemoRecorderStart.Failed -> c.copy(phase = MemoCapturePhase.START_FAILED)
            },
        )
    }

    private fun MemoTx.onTick() {
        val c = m.capture
        if (c.phase == MemoCapturePhase.RECORDING && c.startedAt != null) {
            val elapsed = now.mono - c.startedAt
            if (elapsed >= VoiceMemoLimits.MAX_RECORDING_MS) {
                stopRecording(reachedLimit = true)
            } else {
                m = m.copy(capture = c.copy(elapsedMs = elapsed, secondsLeft = secondsLeft(elapsed)))
            }
        }
        if (m.screen == MemoScreen.PROCESSING && m.foreground) pollCurrent()
    }

    /** Manual stop and the 3:00 limit both land here; only the first one out of RECORDING does anything. */
    private fun MemoTx.stopRecording(reachedLimit: Boolean) {
        val c = m.capture
        if (c.phase != MemoCapturePhase.RECORDING) return
        val scope = c.scope ?: return
        val memoId = newId()
        val elapsed = (now.mono - (c.startedAt ?: now.mono)).coerceIn(0, VoiceMemoLimits.MAX_RECORDING_MS)
        m = m.copy(
            capture = c.copy(
                phase = MemoCapturePhase.SAVING, stoppedAt = now.mono, reachedLimit = reachedLimit, memoId = memoId,
                leavePrompt = false, elapsedMs = elapsed, secondsLeft = secondsLeft(elapsed),
            ),
        )
        emit(MemoEffect.CancelTimer(LIMIT_TIMER))
        emit(MemoEffect.StopAndSave(c.gen, scope, memoId))
    }

    /** The OS took the microphone (or the app left the foreground): the fragment is gone, no document is made. */
    private fun MemoTx.interruptCapture() {
        val c = m.capture
        if (c.phase != MemoCapturePhase.RECORDING && c.phase != MemoCapturePhase.PREPARING) return
        emit(MemoEffect.CancelRecorder)
        emit(MemoEffect.CancelTimer(LIMIT_TIMER))
        m = m.copy(capture = c.copy(phase = MemoCapturePhase.INTERRUPTED, gen = c.gen + 1, leavePrompt = false, secondsLeft = null))
    }

    private fun MemoTx.discardCapture() {
        val c = m.capture
        when (c.phase) {
            MemoCapturePhase.PREPARING, MemoCapturePhase.RECORDING -> {
                emit(MemoEffect.CancelRecorder)
                emit(MemoEffect.CancelTimer(LIMIT_TIMER))
                m = m.copy(capture = Capture(gen = c.gen + 1), screen = MemoScreen.LIST)
                reloadList()
            }
            MemoCapturePhase.SAVING -> Unit
            else -> m = m.copy(capture = Capture(gen = c.gen), screen = if (m.screen == MemoScreen.RECORDING) MemoScreen.LIST else m.screen)
        }
    }

    private fun MemoTx.backToList() {
        val c = m.capture
        when (c.phase) {
            MemoCapturePhase.RECORDING -> {
                m = m.copy(capture = c.copy(leavePrompt = true))
                return
            }
            MemoCapturePhase.PREPARING -> {
                discardCapture()
                return
            }
            MemoCapturePhase.SAVING -> return
            else -> m = m.copy(capture = Capture(gen = c.gen))
        }
        m = m.copy(screen = MemoScreen.LIST, currentId = null)
        reloadList()
    }

    private fun MemoTx.onAudioSaved(e: MemoEvent.AudioSaved) {
        val c = m.capture
        if (e.gen != c.gen || c.phase != MemoCapturePhase.SAVING || e.memoId != c.memoId) return
        val write = e.write
        if (write == null) {
            m = m.copy(capture = c.copy(phase = MemoCapturePhase.INTERRUPTED, gen = c.gen + 1))
            return
        }
        if (write !is MemoWrite.Durable) {
            m = m.copy(capture = c.copy(phase = MemoCapturePhase.SAVE_FAILED))
            return
        }
        val scope = c.scope ?: return
        val ref = write.value
        val attempt = MemoAttempt(
            attemptId = newId(),
            inputKind = VoiceMemoInputKind.AUDIO,
            inputHash = ref.sha256,
            baseEditRevision = 0,
            stage = MemoLocalStage.SAVED,
            timings = MemoTimings(audioDurationMs = e.durationMs),
            agent = organizerOrNone(),
        )
        val doc = MemoDocument(
            scope = scope, memoId = e.memoId, createdAtMs = now.wall, updatedAtMs = now.wall, processing = attempt,
            content = MemoContent(audioDurationMs = e.durationMs, audio = ref),
        )
        putSlot(e.memoId, Slot(doc, diskRevision = null, run = Run(startedAt = c.stoppedAt, reachedLimit = c.reachedLimit, starting = true)))
        mutate(e.memoId, Purpose.Created) { it }
    }

    private fun MemoTx.onCreated(id: String, ok: Boolean) {
        val c = m.capture
        val ours = c.memoId == id && c.phase == MemoCapturePhase.SAVING
        if (!ok) {
            // Not stored → not "saved", not uploaded. The audio file is left as an orphan.
            if (ours) m = m.copy(capture = c.copy(phase = MemoCapturePhase.SAVE_FAILED))
            m = m.copy(slots = m.slots - id)
            return
        }
        if (ours) {
            m = m.copy(capture = Capture(gen = c.gen))
            if (m.screen == MemoScreen.RECORDING) m = m.copy(screen = MemoScreen.PROCESSING, currentId = id)
        }
        m = m.copy(libraryCount = m.libraryCount?.plus(1))
        updateRun(id) { it.copy(starting = false) }
        beginUpload(id)
    }

    // ── documents ─────────────────────────────────────────────────────────────────────────────────────

    private fun MemoTx.current(): Pair<String, Slot>? {
        val id = m.currentId ?: return null
        if (id in m.deleting) return null
        return slot(id)?.let { id to it }
    }

    private fun MemoTx.load(id: String) {
        if (id in m.slots || id in m.loading || id in m.deleted) return
        val scope = m.scope ?: return
        if (!MemoPaths.isToken(id)) return
        m = m.copy(loading = m.loading + (id to emptyList()))
        emit(MemoEffect.LoadDoc(scope, id))
    }

    private fun MemoTx.queue(id: String, e: MemoEvent) {
        val q = m.loading[id] ?: return
        m = m.copy(loading = m.loading + (id to q + e))
    }

    private fun MemoTx.onDocLoaded(e: MemoEvent.DocLoaded) {
        val queued = m.loading[e.memoId] ?: return
        m = m.copy(loading = m.loading - e.memoId)
        val doc = (e.read as? MemoRead.Found)?.value
        if (doc == null || doc.deleted || e.memoId in m.deleted) {
            if (m.openAfterLoad == e.memoId) m = m.copy(openAfterLoad = null)
            return
        }
        val att = doc.processing
        putSlot(e.memoId, Slot(doc, diskRevision = doc.revision, run = Run(issue = issueFromDisk(att), locallyCancelled = att?.stage == VoiceMemoStage.CANCELLED)))
        // A record still `sending` on disk belongs to a batch this process is not running: its receipt is lost
        // with the old connection, so it is "check the chat", never "send again".
        if (m.batch?.memoId != e.memoId && doc.dispatches.any { it.state == MemoTodoState.SENDING }) {
            mutate(e.memoId) { recoverSending(it, now.wall) }
        }
        if (m.openAfterLoad == e.memoId) {
            m = m.copy(openAfterLoad = null)
            show(e.memoId)
        }
        queued.forEach { handle(it) }
    }

    private fun MemoTx.openMemo(id: String) {
        if (id in m.deleted || id in m.deleting) return
        val phase = m.capture.phase
        if (phase == MemoCapturePhase.PREPARING || phase == MemoCapturePhase.RECORDING || phase == MemoCapturePhase.SAVING) return
        if (phase != MemoCapturePhase.IDLE) m = m.copy(capture = Capture(gen = m.capture.gen))
        if (slot(id) != null) show(id) else {
            m = m.copy(openAfterLoad = id)
            load(id)
        }
    }

    private fun MemoTx.show(id: String) {
        val s = slot(id) ?: return
        m = m.copy(currentId = id)
        if (s.doc.hasResult()) {
            showDetail(id)
        } else {
            m = m.copy(screen = MemoScreen.PROCESSING)
            if (needsGet(s)) sendGet(id, replace = false)
        }
    }

    private fun MemoTx.showDetail(id: String) {
        m = m.copy(screen = MemoScreen.DETAIL, currentId = id)
        loadCatalog()
    }

    /**
     * "New session in this project": only a project the catalog lists (or the one open on the second level) and an
     * agent the computer offers. Nothing is created here — the dispatch creates the session.
     */
    private fun MemoTx.selectNewSession(workdir: String, agent: dev.ccpocket.protocol.AgentKind) {
        val scope = m.scope ?: return
        val catalog = m.catalog
        val project = catalog.projects.firstOrNull { it.workdir == workdir }
        val open = catalog.project?.takeIf { it.workdir == workdir }
        if (project == null && open == null && workdir != m.openProject) return
        if (agent !in catalog.newSession.agents) return
        val name = project?.name ?: open?.name ?: workdir.trimEnd('/').substringAfterLast('/')
        val target = MemoTarget(scope.bindingId, sessionId = "", workdir = workdir, agent = agent, project = name, title = "", newSession = true)
        m = m.copy(target = MemoTargetRow(target, MemoTargetStatus.IDLE, mode = catalog.newSession.modeFor(agent)))
    }

    private fun MemoTx.deleteMemo(id: String) {
        if (id in m.deleting || id in m.deleted) return
        val s = slot(id)
        val scope = s?.doc?.scope ?: m.scope ?: return
        val att = s?.doc?.processing
        val cancel = att?.takeIf { !it.accepted && it.stage in QUERYABLE_STAGES && !s.run.locallyCancelled }
            ?.let { VoiceMemoCancel(id, it.attemptId) }
        m = m.copy(deleting = m.deleting + id, list = m.list.copy(rows = m.list.rows.filter { it.memoId != id }))
        cancelUpload(id)
        s?.run?.pendingGet?.let { emit(MemoEffect.CancelTimer(getTimer(id))) }
        if (m.currentId == id) m = m.copy(currentId = null, screen = MemoScreen.LIST)
        emit(MemoEffect.Delete(scope, id, cancel, m.readiness.connectionGeneration))
    }

    private fun MemoTx.onDeleted(e: MemoEvent.Deleted) {
        val id = e.memoId
        m = m.copy(deleting = m.deleting - id)
        if (e.result !is MemoWrite.Durable) {
            m = m.copy(toast = MemoToast.DELETE_FAILED)
            reloadList()
            return
        }
        m = m.copy(
            deleted = m.deleted + id,
            slots = m.slots - id,
            loading = m.loading - id,
            batch = m.batch?.takeIf { it.memoId != id },
            libraryCount = m.libraryCount?.minus(1)?.coerceAtLeast(0),
            toast = MemoToast.DELETED,
        )
        reloadList()
    }

    // ── the commit pipeline ───────────────────────────────────────────────────────────────────────────

    private fun MemoTx.onCommitted(e: MemoEvent.Committed) {
        val s = slot(e.memoId) ?: return
        val flight = s.inFlight?.takeIf { it.seq == e.seq } ?: return
        val settled = s.waiters.filter { it.seq <= e.seq }
        val rest = s.waiters - settled.toSet()
        val ok = e.result is MemoWrite.Durable
        when (val r = e.result) {
            is MemoWrite.Durable -> putSlot(
                e.memoId,
                s.copy(
                    doc = s.doc.copy(revision = flight.revision), diskRevision = flight.revision, durableSeq = e.seq,
                    inFlight = null, saveFailed = false, waiters = rest,
                ),
            )
            is MemoWrite.NotWritten -> {
                putSlot(e.memoId, s.copy(inFlight = null, failedSeq = e.seq, saveFailed = true, waiters = rest, rereading = r.conflict))
                if (r.conflict) emit(MemoEffect.Reread(s.doc.scope, e.memoId))
            }
            MemoWrite.Indeterminate -> {
                // May or may not have landed: learn the stored revision before writing again; nothing waiting on
                // this write proceeds.
                putSlot(e.memoId, s.copy(inFlight = null, failedSeq = e.seq, saveFailed = true, waiters = rest, rereading = true))
                emit(MemoEffect.Reread(s.doc.scope, e.memoId))
            }
        }
        if (!ok && settled.none { it.purpose == Purpose.Created }) m = m.copy(toast = MemoToast.SAVE_FAILED)
        commitIfIdle(e.memoId)
        settled.forEach { onSettled(e.memoId, it.purpose, ok) }
        if (ok && m.screen == MemoScreen.LIST) reloadList()
    }

    private fun MemoTx.onReread(e: MemoEvent.Reread) {
        val s = slot(e.memoId) ?: return
        if (!s.rereading) return
        when (val r = e.read) {
            is MemoRead.Found -> {
                putSlot(e.memoId, s.copy(diskRevision = r.value.revision, rereading = false))
                commitIfIdle(e.memoId)
            }
            is MemoRead.Missing ->
                if (s.diskRevision == null) failWaiters(e.memoId, s)
                else vanish(e.memoId) // tombstoned or removed underneath: never recreate it
            // The stored revision is still unknown: no write may follow, so whatever waits on one has failed —
            // a batch must stop, not sit in STORING_SENDING / STORING_DELIVERED.
            else -> failWaiters(e.memoId, s)
        }
    }

    private fun MemoTx.failWaiters(id: String, s: Slot) {
        putSlot(id, s.copy(rereading = false, waiters = emptyList(), failedSeq = s.seq))
        s.waiters.forEach { onSettled(id, it.purpose, ok = false) }
    }

    private fun MemoTx.vanish(id: String) {
        cancelUpload(id)
        m = m.copy(
            slots = m.slots - id,
            deleted = m.deleted + id,
            batch = m.batch?.takeIf { it.memoId != id },
        )
        if (m.currentId == id) m = m.copy(currentId = null, screen = MemoScreen.LIST)
    }

    private fun MemoTx.onSettled(id: String, purpose: Purpose, ok: Boolean) {
        when (purpose) {
            Purpose.Created -> onCreated(id, ok)
            is Purpose.ResultStored -> onResultStored(id, ok)
            Purpose.AudioRefDropped -> if (ok) slot(id)?.let { emit(MemoEffect.DeleteAudio(it.doc.scope, id)) }
            is Purpose.AttemptStored -> onAttemptStored(id, purpose.attemptId, ok)
            is Purpose.DraftsStored, is Purpose.SendingStored, is Purpose.DeliveredStored -> dispatch.onStored(this, id, purpose, ok)
        }
    }

    // ── processing ────────────────────────────────────────────────────────────────────────────────────

    private fun needsGet(s: Slot): Boolean {
        val att = s.doc.processing ?: return false
        return !att.accepted && att.stage in QUERYABLE_STAGES && s.run.upload == null && !s.run.locallyCancelled &&
            !s.run.starting && s.run.hashing == null
    }

    /** Reconnect / foreground: one query per memo whose attempt the computer may hold. Nothing is resent. */
    private fun MemoTx.queryInProgress() {
        if (!m.readiness.online) return
        for ((id, s) in m.slots) if (needsGet(s)) sendGet(id, replace = true)
    }

    private fun MemoTx.pollCurrent() {
        val (id, s) = current() ?: return
        val att = s.doc.processing ?: return
        if (!needsGet(s) || att.stage !in POLLED_STAGES || s.run.pendingGet != null) return
        if (s.run.issue != null && s.run.issue != MemoProcessingIssue.WAITING_FOR_COMPUTER) return
        val last = s.run.lastGetAt
        if (last != null && now.mono - last < GET_INTERVAL_MS) return
        sendGet(id, replace = false)
    }

    private fun MemoTx.sendGet(id: String, replace: Boolean) {
        if (!m.readiness.online) return
        val s = slot(id) ?: return
        val att = s.doc.processing ?: return
        if (s.run.pendingGet != null) {
            if (!replace) return
            emit(MemoEffect.CancelTimer(getTimer(id)))
        }
        val token = token()
        val gen = m.readiness.connectionGeneration
        updateRun(id) { it.copy(pendingGet = PendingGet(token, att.attemptId, gen, now.mono), lastGetAt = now.mono) }
        emit(MemoEffect.Send(id, token, SendKind.GET, s.doc.scope, gen, VoiceMemoGet(id, att.attemptId)))
        emit(MemoEffect.StartTimer(getTimer(id), GET_INTERVAL_MS, MemoEvent.GetTimeout(id, token)))
    }

    private fun MemoTx.onGetTimeout(e: MemoEvent.GetTimeout) {
        val s = slot(e.memoId) ?: return
        if (s.run.pendingGet?.token != e.token) return
        updateRun(e.memoId) { it.copy(pendingGet = null, issue = it.issue ?: MemoProcessingIssue.WAITING_FOR_COMPUTER) }
    }

    private fun MemoTx.onSendFinished(e: MemoEvent.SendFinished) {
        when (e.kind) {
            SendKind.GET -> {
                val s = slot(e.memoId) ?: return
                if (e.result != MemoLinkSend.Written && s.run.pendingGet?.token == e.token) {
                    emit(MemoEffect.CancelTimer(getTimer(e.memoId)))
                    updateRun(e.memoId) { it.copy(pendingGet = null) }
                }
            }
            SendKind.START -> onUploadFinished(
                e.memoId, e.token,
                if (e.result == MemoLinkSend.Written) UploadOutcome.Completed else UploadOutcome.LinkFailed(e.result),
            )
            SendKind.CANCEL -> Unit // best effort
        }
    }

    private fun MemoTx.beginUpload(id: String) {
        val s = slot(id) ?: return
        val att = s.doc.processing ?: return
        if (s.run.upload != null) return
        val audio = s.doc.content.audio
        if (audio == null) {
            updateRun(id) { it.copy(issue = MemoProcessingIssue.UPLOAD_FAILED) }
            return
        }
        s.run.pendingGet?.let { emit(MemoEffect.CancelTimer(getTimer(id))) }
        val gen = m.readiness.connectionGeneration
        val token = token()
        updateRun(id) { it.copy(upload = Upload(token, att.attemptId, gen), firstChunkAt = null, issue = null, pendingGet = null) }
        if (att.stage != MemoLocalStage.UPLOADING) {
            mutate(id) { d -> d.copy(processing = d.processing?.copy(stage = MemoLocalStage.UPLOADING)) }
        }
        emit(MemoEffect.Upload(id, token, s.doc.scope, gen, att.attemptId, audio, s.doc.content.audioDurationMs, att.agent))
    }

    private fun MemoTx.cancelUpload(id: String) {
        val s = slot(id) ?: return
        if (s.run.upload == null) return
        emit(MemoEffect.CancelUpload(id))
        updateRun(id) { it.copy(upload = null) }
    }

    private fun MemoTx.onUploadFinished(id: String, token: Long, outcome: UploadOutcome) {
        val s = slot(id) ?: return
        if (s.run.upload?.token != token) return
        updateRun(id) { it.copy(upload = null) }
        when (outcome) {
            // Handed to the socket, not received: the computer's next state proves it. Poll from now on.
            UploadOutcome.Completed -> updateRun(id) { it.copy(lastGetAt = now.mono) }
            is UploadOutcome.LinkFailed -> interruptUpload(id, MemoProcessingIssue.UPLOAD_INTERRUPTED)
            UploadOutcome.AudioUnavailable -> interruptUpload(id, MemoProcessingIssue.UPLOAD_FAILED)
            UploadOutcome.TooLarge -> interruptUpload(id, MemoProcessingIssue.AUDIO_REJECTED)
        }
    }

    /** Stops at the upload: no automatic retry; a reconnect only queries. */
    private fun MemoTx.interruptUpload(id: String, issue: MemoProcessingIssue) {
        val att = slot(id)?.doc?.processing ?: return
        if (att.accepted || att.stage !in UPLOAD_STAGES) return
        updateRun(id) { it.copy(issue = issue) }
        mutate(id) { d -> d.copy(processing = d.processing?.copy(stage = MemoLocalStage.INTERRUPTED)) }
    }

    private fun MemoTx.onRemote(r: MemoRemoteState) {
        val st = r.state
        // Ids are checked before anything is looked up or read: a malformed id never reaches the store.
        if (VoiceMemoValidation.validateIds(st.memoId, st.attemptId) != null) return
        val scope = m.scope ?: return
        if (r.bindingId != scope.bindingId) return
        val id = st.memoId
        if (id in m.deleted || id in m.deleting) return
        val s = slot(id)
        if (s == null) {
            // Replies only update a memo that exists: load it, and drop the reply if the store has none.
            load(id)
            queue(id, MemoEvent.Remote(r))
            return
        }
        if (s.doc.scope != scope || s.doc.deleted) return
        val att = s.doc.processing ?: return
        if (st.attemptId != att.attemptId || s.run.locallyCancelled || s.run.starting) return
        val pg = s.run.pendingGet?.takeIf { it.attemptId == att.attemptId && it.generation == m.readiness.connectionGeneration }
        when {
            st.stage == VoiceMemoStage.UNKNOWN -> {
                // Only an answer to the query we are waiting for, on this connection. Never compared with the
                // positive revisions, never a reason to erase what is stored or to start again.
                if (pg == null) return
                clearGet(id)
                adoptUnknown(id, st)
                return
            }
            st.revision == 0L -> {
                // The refusal of a start the daemon did not register — valid only before any real revision.
                if (st.stage != VoiceMemoStage.FAILED || att.remoteRevision != 0L) return
            }
            st.revision < att.remoteRevision -> return
            st.revision == att.remoteRevision && pg == null -> return
        }
        if (pg != null) clearGet(id)
        if (VoiceMemoValidation.validateState(st) != null) {
            // Keep everything we have; this build cannot read the reply. A valid transcript it carries is still
            // worth keeping when none is stored — a retry can then organise it without re-uploading.
            updateRun(id) { it.copy(issue = MemoProcessingIssue.INCOMPATIBLE) }
            val transcript = st.transcript?.takeIf { att.inputKind == VoiceMemoInputKind.AUDIO && VoiceMemoValidation.validateTranscript(it) == null }
            if (transcript != null && s.doc.content.transcript == null) {
                mutate(id) { d -> d.copy(content = d.content.copy(transcript = transcript)) }
            }
            return
        }
        applyState(id, st, answeredGet = pg != null)
    }

    private fun MemoTx.clearGet(id: String) {
        emit(MemoEffect.CancelTimer(getTimer(id)))
        updateRun(id) { it.copy(pendingGet = null) }
    }

    private fun MemoTx.adoptUnknown(id: String, st: VoiceMemoState) {
        cancelUpload(id)
        updateRun(id) {
            it.copy(issue = MemoProcessingIssue.RECORD_UNAVAILABLE, finishedAt = it.finishedAt ?: now.mono, reorganizing = false)
        }
        mutate(id) { d ->
            d.copy(processing = d.processing?.copy(stage = VoiceMemoStage.UNKNOWN, errorCode = st.errorCode ?: VoiceMemoError.UNKNOWN_JOB, retryable = st.retryable))
        }
    }

    private fun MemoTx.applyState(id: String, st: VoiceMemoState, answeredGet: Boolean) {
        val s = slot(id) ?: return
        val att = s.doc.processing ?: return
        var timings = att.timings.merge(st.metrics)
        val firstChunk = s.run.firstChunkAt
        if (st.stage != VoiceMemoStage.RECEIVING && timings.uploadMs == null && firstChunk != null && !s.run.suspended) {
            timings = timings.copy(uploadMs = now.mono - firstChunk)
        }
        val revision = maxOf(att.remoteRevision, st.revision)
        if (att.accepted) {
            // The same result again (re-delivery / a query): only the measurements may improve.
            val merged = att.copy(timings = timings, remoteRevision = revision)
            if (merged != att) mutate(id) { d -> d.copy(processing = merged) }
            return
        }
        val next = att.copy(
            remoteRevision = revision,
            stage = st.stage,
            errorCode = st.errorCode,
            retryable = st.retryable || st.errorCode == VoiceMemoError.NOT_READY,
            timings = timings,
        )
        // An organise-only attempt's input IS the local transcript: the echo in the reply never replaces it.
        val transcript = st.transcript?.takeIf { att.inputKind == VoiceMemoInputKind.AUDIO && VoiceMemoValidation.validateTranscript(it) == null }
        when (st.stage) {
            VoiceMemoStage.RECEIVING -> {
                val incomplete = answeredGet && s.run.upload == null
                updateRun(id) { it.copy(issue = if (incomplete) MemoProcessingIssue.UPLOAD_INCOMPLETE else null) }
                mutate(id) { d -> d.copy(processing = next) }
            }
            VoiceMemoStage.QUEUED, VoiceMemoStage.TRANSCRIBING, VoiceMemoStage.SUMMARIZING -> {
                updateRun(id) { it.copy(issue = null) }
                mutate(id) { d -> d.copy(processing = next, content = d.content.copy(transcript = transcript ?: d.content.transcript)) }
            }
            VoiceMemoStage.FAILED, VoiceMemoStage.CANCELLED -> {
                cancelUpload(id)
                updateRun(id) {
                    it.copy(issue = memoProcessingIssue(st.stage, st.errorCode), finishedAt = now.mono, reorganizing = false)
                }
                // A transcript that arrived with the failure is kept: a retry organises it without re-uploading.
                mutate(id) { d -> d.copy(processing = next, content = d.content.copy(transcript = transcript ?: d.content.transcript)) }
            }
            VoiceMemoStage.READY, VoiceMemoStage.DEGRADED -> acceptResult(id, st, next, transcript)
            VoiceMemoStage.TRANSCRIBED -> acceptTranscribed(id, next, transcript)
        }
    }

    /**
     * TRANSCRIBED - the transcript is there and nothing was organised. For an audio attempt that IS the result:
     * it is stored, shown and the audio released exactly like a result, with no summary, no organiser and no
     * to-dos (the user writes them, sends the whole transcript, or has it organised later). For an organise-only
     * attempt the user asked for an organiser and got none: the content stays exactly as it was, and the page
     * reports [MemoProcessingIssue.AGENT_UNAVAILABLE]. Either way the attempt is accepted (stage and error word
     * kept), so the same snapshot again changes nothing.
     */
    private fun MemoTx.acceptTranscribed(id: String, next: MemoAttempt, transcript: String?) {
        val s = slot(id) ?: return
        val doc = s.doc
        val organiseOnly = next.inputKind == VoiceMemoInputKind.TRANSCRIPT
        if (!organiseOnly && transcript == null) {
            // The transcript is this result's only content; one this build cannot take is not a result.
            updateRun(id) { it.copy(issue = MemoProcessingIssue.INCOMPATIBLE) }
            return
        }
        var content = doc.content
        var todos = doc.todos
        if (!organiseOnly) {
            content = content.copy(transcript = transcript)
            if (!doc.hasResult() && doc.editRevision == next.baseEditRevision) {
                content = content.copy(summary = null, summaryUnstructured = false, organizedBy = null, degraded = false)
                todos = emptyList()
            }
        }
        val started = s.run.startedAt?.takeIf { !s.run.suspended }
        val accepted = next.copy(accepted = true, timings = next.timings.copy(totalMs = started?.let { now.mono - it }))
        updateRun(id) { it.copy(issue = transcribedIssue(accepted), finishedAt = now.mono, reorganizing = false) }
        mutate(id, Purpose.ResultStored(accepted.attemptId)) { d -> d.copy(processing = accepted, content = content, todos = todos) }
    }

    /**
     * Stores the result, then (once durable) shows it and releases the audio. The first result initialises the
     * content; a re-organise replaces only the drafts. Either applies only while the user has not edited since
     * the attempt began — otherwise their copy wins and only the transcript and timings are taken.
     */
    private fun MemoTx.acceptResult(id: String, st: VoiceMemoState, next: MemoAttempt, transcript: String?) {
        val s = slot(id) ?: return
        val doc = s.doc
        val unedited = doc.editRevision == next.baseEditRevision
        val first = !doc.hasResult()
        var content = doc.content.copy(transcript = transcript ?: doc.content.transcript)
        var todos = doc.todos
        var issue: MemoProcessingIssue? = null
        if (st.stage == VoiceMemoStage.READY) {
            val result = st.result
            if (result != null && unedited) {
                val title = sanitizeMemoText(result.title).trim()
                content = content.copy(
                    title = if (title.isBlank()) content.title else clampCodePoints(title, VoiceMemoLimits.MAX_TITLE_CODE_POINTS),
                    summary = sanitizeMemoText(result.summary).takeIf { it.isNotBlank() },
                    summaryUnstructured = false,
                    language = result.language,
                    // Who organised it: the organiser this attempt asked for.
                    organizedBy = next.agent.takeUnless { it == VOICE_MEMO_AGENT_NONE },
                    degraded = false,
                )
                val fresh = result.todos.mapNotNull { suggestion ->
                    val text = sanitizeMemoText(suggestion.text)
                    if (text.isBlank()) null else MemoTodo(
                        todoId = newId(), text = clampCodePoints(text, VoiceMemoLimits.MAX_TODO_CODE_POINTS), selected = true,
                        suggestedTarget = suggestion.suggestedTarget?.let(::sanitizeMemoText)?.takeIf { it.isNotBlank() },
                    )
                }
                todos = if (first) {
                    fresh.take(VoiceMemoLimits.MAX_TODOS)
                } else {
                    // Items already sent, and items frozen in a batch that is still running, stay exactly as they are.
                    val inBatch = m.batch?.takeIf { it.active && it.memoId == id }?.items?.map { it.todoId }?.toSet().orEmpty()
                    val kept = doc.todos.filter { doc.todoState(it.todoId) != MemoTodoState.DRAFT || it.todoId in inBatch }
                    kept + fresh.take((VoiceMemoLimits.MAX_TODOS - kept.size).coerceAtLeast(0))
                }
            }
        } else {
            // The organiser failed: keep the transcript. A first result shows "未能整理成待办"; a re-organise
            // leaves the existing result alone and reports why it did not change.
            issue = memoProcessingIssue(st.stage, st.errorCode)
            if (first && unedited) {
                val trusted = st.result?.takeIf { VoiceMemoValidation.validateResult(it) == null }
                val summary = trusted?.summary?.let(::sanitizeMemoText)?.takeIf { it.isNotBlank() }
                content = content.copy(
                    summary = summary, summaryUnstructured = summary != null, language = trusted?.language ?: content.language,
                    organizedBy = null, degraded = true,
                )
            } else if (doc.content.organizedBy == null && doc.content.summary == null) {
                // A memo nobody organised yet (transcribe-only) whose organising now failed: the degraded result is
                // what its content amounts to. An organised memo keeps its result and is not marked.
                content = content.copy(degraded = true)
            }
        }
        val started = s.run.startedAt?.takeIf { !s.run.suspended }
        val accepted = next.copy(accepted = true, timings = next.timings.copy(totalMs = started?.let { now.mono - it }))
        updateRun(id) { it.copy(issue = issue, finishedAt = now.mono, reorganizing = false) }
        mutate(id, Purpose.ResultStored(accepted.attemptId)) { d -> d.copy(processing = accepted, content = content, todos = todos) }
    }

    private fun MemoTx.onResultStored(id: String, ok: Boolean) {
        if (m.currentId == id && m.screen == MemoScreen.PROCESSING) showDetail(id)
        if (!ok) return // the page shows "not saved"; the audio stays
        val s = slot(id) ?: return
        if (s.doc.content.audio != null) mutate(id, Purpose.AudioRefDropped) { d -> d.copy(content = d.content.copy(audio = null)) }
    }

    private fun MemoTx.cancelProcessing() {
        val (id, s) = current() ?: return
        if (s.run.hashing != null) {
            updateRun(id) { it.copy(hashing = null, reorganizing = false) }
            return
        }
        val att = s.doc.processing ?: return
        if (att.accepted || att.stage !in QUERYABLE_STAGES || s.run.locallyCancelled || s.run.starting) return
        cancelUpload(id)
        if (s.run.pendingGet != null) emit(MemoEffect.CancelTimer(getTimer(id)))
        emit(MemoEffect.Send(id, token(), SendKind.CANCEL, s.doc.scope, m.readiness.connectionGeneration, VoiceMemoCancel(id, att.attemptId)))
        updateRun(id) {
            it.copy(locallyCancelled = true, issue = MemoProcessingIssue.CANCELLED, pendingGet = null, finishedAt = now.mono, reorganizing = false)
        }
        mutate(id) { d -> d.copy(processing = d.processing?.copy(stage = VoiceMemoStage.CANCELLED, errorCode = VoiceMemoError.CANCELLED)) }
    }

    private fun MemoTx.resumeUpload() {
        val (id, s) = current() ?: return
        val att = s.doc.processing ?: return
        if (s.run.issue != MemoProcessingIssue.UPLOAD_INCOMPLETE || s.run.upload != null) return
        if (att.inputKind != VoiceMemoInputKind.AUDIO || s.doc.content.audio == null) return
        beginUpload(id)
    }

    private fun Slot.idle(): Boolean {
        val att = doc.processing ?: return true
        if (run.upload != null || run.hashing != null || run.starting) return false
        if (att.accepted || att.stage in VoiceMemoStage.terminal || att.stage == MemoLocalStage.INTERRUPTED) return true
        return run.issue != null && run.issue != MemoProcessingIssue.WAITING_FOR_COMPUTER
    }

    private fun MemoTx.retry(forceAudio: Boolean) {
        val (id, s) = current() ?: return
        if (!s.idle()) return
        val transcript = s.doc.content.transcript
        when {
            // Organising a stored transcript needs an organiser; without one the action does nothing (the page
            // does not offer it) - it never falls back to re-uploading the audio.
            !forceAudio && transcript != null -> if (m.readiness.organizer != null) startTranscriptAttempt(id, reorganize = false)
            s.doc.content.audio != null -> startAudioAttempt(id)
        }
    }

    /** Organise the transcript: the first time for a transcribe-only memo, or again. Needs a transcript and an
     *  organiser; without an organiser it is ignored. */
    private fun MemoTx.reorganize() {
        val (id, s) = current() ?: return
        if (s.doc.content.transcript == null || !s.idle() || m.readiness.organizer == null) return
        startTranscriptAttempt(id, reorganize = true)
    }

    /** The organiser a new attempt asks for: the one readiness names now, or "transcribe only". */
    private fun MemoTx.organizerOrNone(): String = m.readiness.organizer ?: VOICE_MEMO_AGENT_NONE

    private fun MemoTx.newAttemptRun(id: String, reorganize: Boolean, s: Slot) {
        s.run.pendingGet?.let { emit(MemoEffect.CancelTimer(getTimer(id))) }
        updateRun(id) { Run(startedAt = now.mono, starting = true, reorganizing = reorganize, reachedLimit = it.reachedLimit) }
    }

    private fun MemoTx.startAudioAttempt(id: String) {
        val s = slot(id) ?: return
        val audio = s.doc.content.audio ?: return
        val attempt = MemoAttempt(
            attemptId = newId(), inputKind = VoiceMemoInputKind.AUDIO, inputHash = audio.sha256,
            baseEditRevision = s.doc.editRevision, stage = MemoLocalStage.SAVED,
            timings = MemoTimings(audioDurationMs = s.doc.content.audioDurationMs),
            agent = organizerOrNone(),
        )
        newAttemptRun(id, reorganize = false, s)
        mutate(id, Purpose.AttemptStored(attempt.attemptId)) { it.copy(processing = attempt) }
    }

    private fun MemoTx.startTranscriptAttempt(id: String, reorganize: Boolean) {
        val s = slot(id) ?: return
        val text = s.doc.content.transcript ?: return
        val token = token()
        updateRun(id) { it.copy(hashing = Hashing(token, reorganize), reorganizing = reorganize, issue = null) }
        emit(MemoEffect.HashTranscript(id, token, text))
    }

    private fun MemoTx.onTranscriptHashed(e: MemoEvent.TranscriptHashed) {
        val s = slot(e.memoId) ?: return
        val h = s.run.hashing?.takeIf { it.token == e.token } ?: return
        // The organiser is read when the attempt is made; if it went away while the hash was computed, nothing
        // is started (a transcript with "none" is meaningless).
        val organizer = m.readiness.organizer
        if (organizer == null) {
            updateRun(e.memoId) { it.copy(hashing = null, reorganizing = false) }
            return
        }
        val attempt = MemoAttempt(
            attemptId = newId(), inputKind = VoiceMemoInputKind.TRANSCRIPT, inputHash = e.hash,
            baseEditRevision = s.doc.editRevision, stage = MemoLocalStage.UPLOADING,
            timings = MemoTimings(audioDurationMs = s.doc.content.audioDurationMs),
            agent = organizer,
        )
        newAttemptRun(e.memoId, reorganize = h.reorganize, s)
        mutate(e.memoId, Purpose.AttemptStored(attempt.attemptId)) { it.copy(processing = attempt) }
    }

    /** A new attempt id is stored before anything is sent under it, so a restart can still query it. */
    private fun MemoTx.onAttemptStored(id: String, attemptId: String, ok: Boolean) {
        val s = slot(id) ?: return
        val att = s.doc.processing?.takeIf { it.attemptId == attemptId } ?: return
        updateRun(id) { it.copy(starting = false) }
        if (!ok) {
            updateRun(id) { it.copy(issue = MemoProcessingIssue.UPLOAD_FAILED, reorganizing = false, finishedAt = now.mono) }
            return
        }
        when (att.inputKind) {
            VoiceMemoInputKind.AUDIO -> beginUpload(id)
            else -> {
                val transcript = s.doc.content.transcript
                if (transcript == null) {
                    updateRun(id) { it.copy(issue = MemoProcessingIssue.UPLOAD_FAILED, reorganizing = false) }
                    return
                }
                val gen = m.readiness.connectionGeneration
                val token = token()
                updateRun(id) { it.copy(upload = Upload(token, attemptId, gen)) }
                val start = VoiceMemoStart(
                    memoId = id, attemptId = attemptId, inputKind = VoiceMemoInputKind.TRANSCRIPT, agent = att.agent,
                    durationMs = s.doc.content.audioDurationMs.takeIf { it > 0 }, sha256 = att.inputHash, transcript = transcript,
                )
                emit(MemoEffect.Send(id, token, SendKind.START, s.doc.scope, gen, start))
            }
        }
    }

    // ── edits ─────────────────────────────────────────────────────────────────────────────────────────

    /** A user edit: +1 editRevision, stored atomically; a late remote result will no longer overwrite it. */
    private fun MemoTx.edit(f: (MemoDocument) -> MemoDocument?) {
        val (id, s) = current() ?: return
        if (!s.doc.hasResult() && s.doc.content.transcript == null) return
        val changed = f(s.doc) ?: return
        if (changed == s.doc) return
        mutate(id) { changed.copy(editRevision = it.editRevision + 1) }
    }

    private fun MemoTx.frozen(): Set<String> =
        m.batch?.takeIf { it.active }?.items?.map { it.todoId }?.toSet().orEmpty()

    private fun MemoTx.editTitle(title: String) {
        val clean = sanitizeMemoText(title)
        if (clean.isBlank()) return
        val clamped = clampCodePoints(clean, VoiceMemoLimits.MAX_TITLE_CODE_POINTS)
        edit { d -> if (d.content.title == clamped) null else d.copy(content = d.content.copy(title = clamped)) }
    }

    /** Only a draft that is not part of a running batch may be toggled, edited or deleted; null from [f] deletes. */
    private fun MemoTx.editTodo(todoId: String, f: (MemoTodo, MemoDocument) -> MemoTodo?) {
        val frozen = frozen()
        edit { d ->
            val todo = d.todos.firstOrNull { it.todoId == todoId } ?: return@edit null
            if (d.todoState(todoId) != MemoTodoState.DRAFT || todoId in frozen) return@edit null
            val next = f(todo, d)
            d.copy(todos = if (next == null) d.todos.filter { it.todoId != todoId } else d.todos.map { if (it.todoId == todoId) next else it })
        }
    }

    private fun MemoTx.addTodo() {
        val (_, s) = current() ?: return
        if (s.doc.todos.size >= VoiceMemoLimits.MAX_TODOS) {
            m = m.copy(toast = MemoToast.TODO_LIMIT)
            return
        }
        val todo = MemoTodo(todoId = newId(), text = "", selected = true)
        edit { d -> d.copy(todos = d.todos + todo) }
    }

    /** One selected draft holding the whole (sanitized) transcript - for a memo nobody organised. Ignored when the
     *  transcript does not fit one to-do or the list is full. */
    private fun MemoTx.addWholeTranscriptTodo() {
        val (_, s) = current() ?: return
        val text = wholeTranscriptText(s.doc.content.transcript) ?: return
        if (s.doc.todos.size >= VoiceMemoLimits.MAX_TODOS) return
        val todo = MemoTodo(todoId = newId(), text = text, selected = true, wholeTranscript = true)
        edit { d -> d.copy(todos = d.todos + todo) }
    }

    /** A new to-do after the source; the source and its records are untouched. A copy of an item awaiting its
     *  receipt starts unticked — sending it again is the user's explicit choice. */
    private fun MemoTx.copyTodo(todoId: String) {
        val (_, s) = current() ?: return
        val source = s.doc.todos.firstOrNull { it.todoId == todoId } ?: return
        if (s.doc.todos.size >= VoiceMemoLimits.MAX_TODOS) {
            m = m.copy(toast = MemoToast.TODO_LIMIT)
            return
        }
        val copy = MemoTodo(
            todoId = newId(), text = source.text, selected = s.doc.todoState(todoId) != MemoTodoState.UNKNOWN,
            suggestedTarget = source.suggestedTarget, copiedFrom = todoId, wholeTranscript = source.wholeTranscript,
        )
        edit { d ->
            val at = d.todos.indexOfFirst { it.todoId == todoId } + 1
            d.copy(todos = d.todos.take(at) + copy + d.todos.drop(at))
        }
        m = m.copy(toast = MemoToast.COPIED)
    }

    /** FAILED (proven not sent) → a selected draft. The failed record is rewritten to `draft`, keeping its
     *  errorCode as history, so [todoState] reads the item as a draft again; no record is removed. */
    private fun MemoTx.retryFailedTodo(todoId: String) {
        edit { d ->
            if (d.todoState(todoId) != MemoTodoState.FAILED) return@edit null
            val index = d.dispatches.indexOfLast { it.todoId == todoId && it.state == MemoTodoState.FAILED }
            if (index < 0) return@edit null
            val records = d.dispatches.toMutableList()
            records[index] = records[index].copy(state = MemoTodoState.DRAFT, updatedAtMs = now.wall)
            d.copy(dispatches = records, todos = d.todos.map { if (it.todoId == todoId) it.copy(selected = true) else it })
        }
    }

    // ── bookkeeping ───────────────────────────────────────────────────────────────────────────────────

    /** Drops slots nothing needs any more: not shown, not dispatching, not writing, not measuring. */
    private fun MemoTx.gc() {
        val keep = m.slots.filter { (id, s) -> id == m.currentId || id == m.batch?.memoId || s.busy() }
        if (keep.size != m.slots.size) m = m.copy(slots = keep)
    }

    private fun Slot.busy(): Boolean =
        run.upload != null || inFlight != null || rereading || waiters.isNotEmpty() || seq > durableSeq ||
            run.hashing != null || run.starting ||
            (run.startedAt != null && run.finishedAt == null && doc.processing?.let { !it.accepted && it.stage in QUERYABLE_STAGES } == true)

    private fun MemoTx.syncTicker() {
        val cur = slot(m.currentId)
        val running = cur != null && cur.run.finishedAt == null && !cur.run.locallyCancelled &&
            cur.doc.processing?.let { !it.accepted && it.stage in QUERYABLE_STAGES && it.stage != MemoLocalStage.INTERRUPTED } == true
        val want = m.capture.phase == MemoCapturePhase.RECORDING || (m.screen == MemoScreen.PROCESSING && m.foreground && running)
        if (want == m.tickerOn) return
        emit(if (want) MemoEffect.StartTicker else MemoEffect.StopTicker)
        m = m.copy(tickerOn = want)
    }
}

/**
 * The reducer's working copy for one event. Every helper replaces [m] with a new immutable value and appends to
 * [fx]; nothing escapes until [MemoReducer.reduce] returns.
 */
internal class MemoTx(var m: MemoModel, val now: MemoNow) {
    val fx = mutableListOf<MemoEffect>()

    fun emit(e: MemoEffect) {
        fx += e
    }

    fun token(): Long {
        val t = m.nextToken
        m = m.copy(nextToken = t + 1)
        return t
    }

    fun slot(id: String?): Slot? = id?.let { m.slots[it] }

    /** Reads the picker's catalog for the level it shows now. */
    fun loadCatalog() {
        m = m.copy(catalog = m.catalog.copy(status = MemoListStatus.LOADING))
        emit(MemoEffect.LoadCatalog(m.openProject))
    }

    fun putSlot(id: String, s: Slot) {
        m = m.copy(slots = m.slots + (id to s))
    }

    fun updateRun(id: String, f: (Run) -> Run) {
        val s = slot(id) ?: return
        putSlot(id, s.copy(run = f(s.run)))
    }

    /** Changes the in-memory document and schedules its commit. [purpose] waits for a commit covering this change. */
    fun mutate(id: String, purpose: Purpose? = null, f: (MemoDocument) -> MemoDocument): Boolean {
        val s = slot(id) ?: return false
        val seq = s.seq + 1
        val doc = f(s.doc).copy(updatedAtMs = now.wall)
        putSlot(id, s.copy(doc = doc, seq = seq, waiters = if (purpose == null) s.waiters else s.waiters + Waiter(seq, purpose)))
        commitIfIdle(id)
        return true
    }

    /**
     * One commit per memo at a time; changes made meanwhile are coalesced into the next. The commit expects the
     * revision the store last confirmed, so a lost or doubtful write is a conflict, never an overwrite.
     */
    fun commitIfIdle(id: String) {
        val s = slot(id) ?: return
        if (s.inFlight != null || s.rereading || s.seq <= s.durableSeq || s.seq <= s.failedSeq) return
        val revision = (s.diskRevision ?: 0) + 1
        putSlot(id, s.copy(inFlight = InFlight(s.seq, revision)))
        emit(MemoEffect.Commit(id, s.seq, s.doc.copy(revision = revision), s.diskRevision))
    }
}
