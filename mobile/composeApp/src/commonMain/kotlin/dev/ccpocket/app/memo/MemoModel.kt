package dev.ccpocket.app.memo

import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.VoiceMemoCancel

// The data layer's private state, and the vocabulary between the pure [MemoReducer] and the effect runner in
// [DefaultVoiceMemoRepository]. Nothing here is persisted: the durable truth is each memo's [MemoDocument];
// the rest is what one process knows about the work it started (generations, timers, in-flight writes).

/** Time as the reducer sees it: stamped by the actor on every event, so the reducer itself reads no clock. */
internal data class MemoNow(val mono: Long, val wall: Long)

internal data class MemoModel(
    val readiness: MemoReadiness = MemoReadiness(),
    val scope: MemoScope? = null,
    val consent: Boolean = false,
    val foreground: Boolean = true,
    val screen: MemoScreen = MemoScreen.LIST,
    val list: MemoListState = MemoListState(),
    /** Documents this scope holds, for the [dev.ccpocket.protocol.VoiceMemoLimits.MAX_LOCAL_MEMOS] check; null = unknown. */
    val libraryCount: Int? = null,
    val listToken: Long = 0,
    val capture: Capture = Capture(),
    /** The memo shown on PROCESSING / DETAIL. */
    val currentId: String? = null,
    /** Loaded documents. A slot stays while it is shown, dispatching, uploading, writing or measuring. */
    val slots: Map<String, Slot> = emptyMap(),
    /** Memos being read from the store, with the events that arrived for them meanwhile (replayed on load). */
    val loading: Map<String, List<MemoEvent>> = emptyMap(),
    val openAfterLoad: String? = null,
    /** Tombstoned in this process: every later reply for these ids is dropped. */
    val deleted: Set<String> = emptySet(),
    val deleting: Set<String> = emptySet(),
    /** The picker's catalog as last read from the gateway; kept (with status FAILED) when a read throws. */
    val catalog: MemoTargetCatalog = MemoTargetCatalog(),
    /** The project the picker's second level shows; null = the first level. */
    val openProject: String? = null,
    /** The user's pick. Its live status is looked up in [catalog] by the projection. */
    val target: MemoTargetRow? = null,
    val batch: Batch? = null,
    val toast: MemoToast? = null,
    /** promptId → memoId for every prompt this process dispatched, so a late receipt finds its memo. */
    val promptIndex: Map<String, String> = emptyMap(),
    val startedScopes: Set<MemoScope> = emptySet(),
    val tickerOn: Boolean = false,
    val nowMono: Long = 0,
    val nextToken: Long = 1,
)

internal data class Capture(
    val phase: MemoCapturePhase = MemoCapturePhase.IDLE,
    /** Bumped whenever a capture is started, cancelled or interrupted; a callback carrying an older value is stale. */
    val gen: Long = 0,
    val startedAt: Long? = null,
    val elapsedMs: Long = 0,
    val secondsLeft: Int? = null,
    val leavePrompt: Boolean = false,
    val reachedLimit: Boolean = false,
    val stoppedAt: Long? = null,
    val memoId: String? = null,
    val scope: MemoScope? = null,
)

internal data class Slot(
    val doc: MemoDocument,
    /** The revision the store holds; null = never written. The next commit expects exactly this. */
    val diskRevision: Long?,
    /** Local mutation counter: a commit carries the seq it covers, a waiter the seq it needs. */
    val seq: Long = 0,
    val durableSeq: Long = 0,
    val failedSeq: Long = 0,
    val inFlight: InFlight? = null,
    val rereading: Boolean = false,
    val saveFailed: Boolean = false,
    val waiters: List<Waiter> = emptyList(),
    val run: Run = Run(),
) {
    val saving: Boolean get() = inFlight != null || rereading
}

internal data class InFlight(val seq: Long, val revision: Long)

/** What this process knows about the memo's current attempt. Lost on restart, on purpose. */
internal data class Run(
    val upload: Upload? = null,
    val pendingGet: PendingGet? = null,
    val lastGetAt: Long? = null,
    /** Monotonic start of processing (stop of the recording, or the retry), for elapsed and total time. */
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val firstChunkAt: Long? = null,
    /** The app was backgrounded mid-way: the durations can no longer be measured. */
    val suspended: Boolean = false,
    val reachedLimit: Boolean = false,
    val issue: MemoProcessingIssue? = null,
    val reorganizing: Boolean = false,
    /** A transcript attempt whose input hash is being computed. */
    val hashing: Hashing? = null,
    /** An attempt id is being stored; nothing has been sent under it, so nothing may be queried for it yet. */
    val starting: Boolean = false,
    val locallyCancelled: Boolean = false,
)

internal data class Upload(val token: Long, val attemptId: String, val generation: Int)

internal data class PendingGet(val token: Long, val attemptId: String, val generation: Int, val sentAt: Long)

internal data class Hashing(val token: Long, val reorganize: Boolean)

internal sealed interface Purpose {
    data object Created : Purpose
    data class ResultStored(val attemptId: String) : Purpose
    data object AudioRefDropped : Purpose
    data class AttemptStored(val attemptId: String) : Purpose
    data class DraftsStored(val batchId: String) : Purpose
    data class SendingStored(val batchId: String, val promptId: String) : Purpose
    data class DeliveredStored(val batchId: String, val promptId: String) : Purpose
}

internal data class Waiter(val seq: Long, val purpose: Purpose)

internal enum class BatchStep { IDLE, CHECKING, STORING_SENDING, SUBMITTING, AWAITING, STORING_DELIVERED }

internal data class BatchItem(val todoId: String, val promptId: String, val text: String)

/** One confirmed dispatch. [phase] null = the prompt ids are being stored; nothing is visible or sent yet.
 *  [target] starts as what the user confirmed and becomes the session actually opened (and later resolved);
 *  [creating] remembers that the confirmation asked for a NEW session. */
internal data class Batch(
    val batchId: String,
    val memoId: String,
    val target: MemoTarget,
    val items: List<BatchItem>,
    val creating: Boolean = false,
    val openFailure: String? = null,
    val phase: MemoDispatchPhase? = null,
    val stop: MemoDispatchStop = MemoDispatchStop.NONE,
    val stopRequested: MemoDispatchStop? = null,
    val lease: MemoTargetLease? = null,
    val cursor: Int = 0,
    val step: BatchStep = BatchStep.IDLE,
    /** Receipts that arrived while their submit had not returned yet. */
    val early: Set<String> = emptySet(),
) {
    val active: Boolean get() = phase == null || phase == MemoDispatchPhase.OPENING || phase == MemoDispatchPhase.SENDING
    val current: BatchItem? get() = items.getOrNull(cursor)
}

internal sealed interface UploadOutcome {
    data object Completed : UploadOutcome
    data class LinkFailed(val result: MemoLinkSend) : UploadOutcome
    data object AudioUnavailable : UploadOutcome
    data object TooLarge : UploadOutcome
}

internal enum class SendKind { GET, START, CANCEL }

internal sealed interface MemoEvent {
    data class Act(val action: MemoAction) : MemoEvent
    data class Readiness(val readiness: MemoReadiness) : MemoEvent
    data class Remote(val remote: MemoRemoteState) : MemoEvent
    data class Receipt(val receipt: MemoPromptReceipt) : MemoEvent
    data class DispatchStopped(val stop: MemoDispatchStop) : MemoEvent
    data object RecorderInterrupted : MemoEvent
    data object Tick : MemoEvent
    data class LimitReached(val gen: Long) : MemoEvent
    data class ListLoaded(
        val token: Long,
        val scope: MemoScope,
        val append: Boolean,
        val rows: MemoRead<List<MemoHeader>>,
        val count: MemoRead<Int>,
    ) : MemoEvent
    data class ConsentLoaded(val scope: MemoScope, val accepted: Boolean) : MemoEvent
    data class RecorderStarted(val gen: Long, val result: MemoRecorderStart) : MemoEvent
    /** [write] null = the recorder had nothing to return. */
    data class AudioSaved(val gen: Long, val memoId: String, val write: MemoWrite<AudioRef>?, val durationMs: Long) : MemoEvent
    data class DocLoaded(val memoId: String, val read: MemoRead<MemoDocument>) : MemoEvent
    data class Committed(val memoId: String, val seq: Long, val revision: Long, val result: MemoWrite<Unit>) : MemoEvent
    data class Reread(val memoId: String, val read: MemoRead<MemoDocument>) : MemoEvent
    data class UploadFirstChunk(val memoId: String, val token: Long) : MemoEvent
    data class UploadFinished(val memoId: String, val token: Long, val outcome: UploadOutcome) : MemoEvent
    data class TranscriptHashed(val memoId: String, val token: Long, val hash: String) : MemoEvent
    data class SendFinished(val memoId: String, val token: Long, val kind: SendKind, val result: MemoLinkSend) : MemoEvent
    data class GetTimeout(val memoId: String, val token: Long) : MemoEvent
    data class Deleted(val memoId: String, val result: MemoWrite<Unit>) : MemoEvent
    data class StartupScanned(val scope: MemoScope, val withSending: List<String>) : MemoEvent
    /** [catalog] null = the gateway threw. */
    data class CatalogLoaded(val project: String?, val catalog: MemoTargetCatalog?) : MemoEvent
    /** [final]: asked because the batch ended — a session whose id is still unknown is then dropped from the pick. */
    data class Resolved(val batchId: String, val target: MemoTarget?, val final: Boolean) : MemoEvent
    data class Entered(val batchId: String, val result: MemoEnterResult) : MemoEvent
    data class HoldsChecked(val batchId: String, val promptId: String, val held: Boolean) : MemoEvent
    data class Submitted(val batchId: String, val promptId: String, val result: MemoSubmitResult) : MemoEvent
    data class ReceiptTimeout(val batchId: String, val promptId: String) : MemoEvent
    data class SubmitTimeout(val batchId: String, val promptId: String) : MemoEvent
}

internal sealed interface MemoEffect {
    data class LoadList(val token: Long, val scope: MemoScope, val offset: Int, val limit: Int, val append: Boolean) : MemoEffect
    data class LoadConsent(val scope: MemoScope) : MemoEffect
    data class AcceptConsent(val scope: MemoScope) : MemoEffect
    data class StartRecorder(val gen: Long) : MemoEffect
    data class StopAndSave(val gen: Long, val scope: MemoScope, val memoId: String) : MemoEffect
    data object CancelRecorder : MemoEffect
    data object OpenMicSettings : MemoEffect
    data object StartTicker : MemoEffect
    data object StopTicker : MemoEffect
    data class StartTimer(val key: String, val delayMs: Long, val event: MemoEvent) : MemoEffect
    data class CancelTimer(val key: String) : MemoEffect
    data class LoadDoc(val scope: MemoScope, val memoId: String) : MemoEffect
    data class Commit(val memoId: String, val seq: Long, val document: MemoDocument, val expectedRevision: Long?) : MemoEffect
    data class Reread(val scope: MemoScope, val memoId: String) : MemoEffect
    data class DeleteAudio(val scope: MemoScope, val memoId: String) : MemoEffect
    data class Upload(
        val memoId: String,
        val token: Long,
        val scope: MemoScope,
        val generation: Int,
        val attemptId: String,
        val audio: AudioRef,
        val durationMs: Long,
        /** The attempt's organiser, or "none" - the start frame asks for exactly what the attempt recorded. */
        val agent: String,
    ) : MemoEffect
    data class CancelUpload(val memoId: String) : MemoEffect
    data class HashTranscript(val memoId: String, val token: Long, val text: String) : MemoEffect
    data class Send(val memoId: String, val token: Long, val kind: SendKind, val scope: MemoScope, val generation: Int, val frame: ToDaemon) : MemoEffect
    data class Delete(val scope: MemoScope, val memoId: String, val cancel: VoiceMemoCancel?, val generation: Int) : MemoEffect
    data class Startup(val scope: MemoScope) : MemoEffect
    data class LoadCatalog(val project: String?) : MemoEffect
    data class Resolve(val batchId: String, val lease: MemoTargetLease, val final: Boolean) : MemoEffect
    data class Enter(val batchId: String, val target: MemoTarget) : MemoEffect
    data class CheckHolds(val batchId: String, val promptId: String, val lease: MemoTargetLease) : MemoEffect
    data class Submit(val batchId: String, val prompt: ConfirmedMemoPrompt) : MemoEffect
    data class ShowTarget(val target: MemoTarget) : MemoEffect
    data class ShowMemo(val memoId: String) : MemoEffect
}

internal data class MemoReduction(val model: MemoModel, val effects: List<MemoEffect>)
