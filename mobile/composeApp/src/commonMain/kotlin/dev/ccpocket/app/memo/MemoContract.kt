package dev.ccpocket.app.memo

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.VoiceMemoState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

// Voice memo → tasks: the frozen seam between the memo data layer, its screens, and the app that hosts them
// (docs/design/VOICE-MEMO-CODE-DESIGN.md §4–§8). Three parties build against this file:
//
//  - the DATA LAYER implements [VoiceMemoStore], [VoiceMemoRepository] and the dispatch coordinator,
//    and talks to the rest of the app only through the ports in the last section;
//  - the SCREENS render [MemoUiState] and emit [MemoAction] — they launch no business coroutine and
//    read no file;
//  - the HOST (PocketRepository / App) implements the ports and wires navigation.

// ── persisted domain model ──────────────────────────────────────────────────────────────────────────

/** Bump when the on-disk document changes shape. An unknown version is read-only, never overwritten. */
const val MEMO_SCHEMA_VERSION = 1

/** Who a memo belongs to: one paired computer, as seen from this device. [bindingId] comes from the
 *  trusted pairing record — never from a host name or a path. */
@Serializable
data class MemoScope(val bindingId: String, val deviceId: String)

/**
 * Where a to-do is dispatched: an existing session, or — with [newSession] — a session to be CREATED in
 * [workdir] on [agent]. Identity is [bindingId] + [sessionId] + [workdir] + [agent] + [newSession]; the label
 * fields are what the user saw when confirming and are display-only.
 *
 * A new-session target has an empty [sessionId]. Once the session exists the gateway hands back the target of
 * that session ([MemoEnterResult.Entered], [MemoSessionGateway.resolved]), and the memo is pointed at it: what
 * is left of the batch goes into the SAME session on the next confirmation, never into another new one.
 */
@Serializable
data class MemoTarget(
    val bindingId: String,
    val sessionId: String,
    val workdir: String,
    val agent: AgentKind = AgentKind.CLAUDE,
    val project: String = "",
    val title: String = "",
    val newSession: Boolean = false,
)

/** The five delivery states of a dispatched to-do. There is no "executing" and no "done". */
object MemoTodoState {
    const val DRAFT = "draft"
    const val SENDING = "sending"
    const val DELIVERED = "delivered"
    /** Submitted, no receipt. Never resent automatically — the user checks the chat first. */
    const val UNKNOWN = "unknown"
    /** Proven not sent. May go back to a draft. */
    const val FAILED = "failed"
}

/** Local processing stages that precede (or replace) the daemon's [dev.ccpocket.protocol.VoiceMemoStage] words. */
object MemoLocalStage {
    const val SAVED = "saved"          // audio durable, nothing submitted yet
    const val UPLOADING = "uploading"
    const val INTERRUPTED = "interrupted" // upload stopped by a lost link / background
}

@Serializable
data class MemoTimings(
    val audioDurationMs: Long? = null,
    val uploadMs: Long? = null,
    val queueMs: Long? = null,
    val transcribeMs: Long? = null,
    val summarizeMs: Long? = null,
    /** Stop-recording → result stored. Null when the app was suspended in between ("未完整测得"). */
    val totalMs: Long? = null,
    val coldStart: Boolean? = null,
)

@Serializable
data class MemoAttempt(
    val attemptId: String,
    val inputKind: String,
    val inputHash: String,
    val baseEditRevision: Long,
    /** The daemon's revision of this attempt; unrelated to [MemoDocument.revision]. */
    val remoteRevision: Long = 0,
    /** A [dev.ccpocket.protocol.VoiceMemoStage] or [MemoLocalStage] word. */
    val stage: String,
    val errorCode: String? = null,
    val retryable: Boolean = false,
    val timings: MemoTimings = MemoTimings(),
    /** True once this attempt's result initialised [MemoDocument.content] / [MemoDocument.todos]. */
    val accepted: Boolean = false,
    /** The organiser this attempt asked for — an agent wire name, or [dev.ccpocket.protocol.VOICE_MEMO_AGENT_NONE]
     *  for transcribe-only. Chosen from [MemoReadiness.organizer] when the attempt starts. */
    val agent: String = dev.ccpocket.protocol.VOICE_MEMO_AGENT_CLAUDE,
)

@Serializable
data class AudioRef(val fileName: String, val byteLength: Long, val sha256: String, val mediaType: String)

@Serializable
data class MemoContent(
    val title: String = "",
    val summary: String? = null,
    /** True when [summary] came from a degraded result and is shown with the "未结构化" tag. */
    val summaryUnstructured: Boolean = false,
    val transcript: String? = null,
    val language: String? = null,
    val audioDurationMs: Long = 0,
    /** Null once the transcript is stored and the audio was released. */
    val audio: AudioRef? = null,
    /** The agent whose result [summary] and the initial to-dos came from; null = never organised (the
     *  transcript is the whole result) or the organiser failed. */
    val organizedBy: String? = null,
    /** The latest organisation that became this content produced nothing usable ("未能整理成待办"): set when a
     *  degraded result is accepted, cleared when a ready result replaces it. Persisted, so it survives a later
     *  attempt that is still running or failed. */
    val degraded: Boolean = false,
)

@Serializable
data class MemoTodo(
    val todoId: String,
    val text: String,
    val selected: Boolean = true,
    val suggestedTarget: String? = null,
    /** Set on a copy: the to-do it was copied from. */
    val copiedFrom: String? = null,
    /** Made by [MemoAction.AddWholeTranscriptTodo] and not edited since: the confirm page notes that the whole
     *  transcript goes out as one item. Cleared by an edit of the text. */
    val wholeTranscript: Boolean = false,
)

@Serializable
data class MemoDispatchRecord(
    val batchId: String,
    val todoId: String,
    val promptId: String,
    /** The to-do text the user confirmed, WITHOUT the fixed prefix. */
    val confirmedText: String,
    val target: MemoTarget,
    /** Filled in, and stored, once the exact target session is live. */
    val convoId: String? = null,
    /** A [MemoTodoState] word. */
    val state: String,
    val updatedAtMs: Long,
    val errorCode: String? = null,
)

@Serializable
data class MemoDocument(
    val schemaVersion: Int = MEMO_SCHEMA_VERSION,
    val scope: MemoScope,
    val memoId: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** +1 on every local commit. */
    val revision: Long = 0,
    /** +1 only when the user changed content. */
    val editRevision: Long = 0,
    val processing: MemoAttempt? = null,
    val content: MemoContent = MemoContent(),
    val todos: List<MemoTodo> = emptyList(),
    /** Every dispatch ever made for this memo; a re-organise never rewrites it. */
    val dispatches: List<MemoDispatchRecord> = emptyList(),
    val deleted: Boolean = false,
)

/** The delivery state of [todoId]: its latest dispatch record, or a draft when it has none. A FAILED record
 *  the user retried is followed by no newer record, so retrying REMOVES nothing — see [MemoAction.RetryFailedTodo]. */
fun MemoDocument.todoState(todoId: String): String =
    dispatches.lastOrNull { it.todoId == todoId && it.state != MemoTodoState.DRAFT }?.state ?: MemoTodoState.DRAFT

fun MemoDocument.latestDispatch(todoId: String): MemoDispatchRecord? = dispatches.lastOrNull { it.todoId == todoId }

/** One list row, read without loading transcripts. */
@Serializable
data class MemoHeader(
    val memoId: String,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** Processing stage while there is no result yet; null once the memo has content. */
    val stage: String? = null,
    val errorCode: String? = null,
    val drafts: Int = 0,
    val sending: Int = 0,
    val delivered: Int = 0,
    val unknown: Int = 0,
    val failed: Int = 0,
)

// ── storage ─────────────────────────────────────────────────────────────────────────────────────────

sealed interface MemoRead<out T> {
    data class Found<T>(val value: T) : MemoRead<T>
    data object Missing : MemoRead<Nothing>
    /** The file exists but could not be read or parsed. NOT an empty library. */
    data class Unreadable(val reason: String) : MemoRead<Nothing>
    data class UnsupportedVersion(val version: Int) : MemoRead<Nothing>
}

sealed interface MemoWrite<out T> {
    data class Durable<T>(val value: T) : MemoWrite<T>
    /** Nothing changed on disk. [conflict] = the expected revision did not match. */
    data class NotWritten(val conflict: Boolean = false) : MemoWrite<Nothing>
    /** The write may or may not have landed; re-read before acting on it. Never send on this. */
    data object Indeterminate : MemoWrite<Nothing>
}

interface VoiceMemoStore {
    suspend fun list(scope: MemoScope, offset: Int, limit: Int): MemoRead<List<MemoHeader>>
    suspend fun count(scope: MemoScope): MemoRead<Int>
    suspend fun read(scope: MemoScope, memoId: String): MemoRead<MemoDocument>
    suspend fun readAudio(scope: MemoScope, memoId: String): MemoRead<ByteArray>
    suspend fun writeAudio(scope: MemoScope, memoId: String, bytes: ByteArray, mediaType: String): MemoWrite<AudioRef>
    /** Atomic replace. [expectedRevision] null = create; otherwise the stored revision must match and the
     *  stored document must not be a tombstone. The caller passes the document with revision already +1. */
    suspend fun commit(document: MemoDocument, expectedRevision: Long?): MemoWrite<Unit>
    suspend fun deleteAudio(scope: MemoScope, memoId: String): MemoWrite<Unit>
    /** Durable tombstone first; content is removed by [purgeDeleted]. */
    suspend fun markDeleted(scope: MemoScope, memoId: String): MemoWrite<Unit>
    suspend fun purgeDeleted(scope: MemoScope, memoId: String): MemoWrite<Unit>
    /** Memo ids holding a tombstone that still has files — finished on the next start. */
    suspend fun pendingPurges(scope: MemoScope): List<String>
}

/**
 * App-private file access for [VoiceMemoStore]: `voice-memos/<dir>/<name>` under the platform's private,
 * backup-excluded directory. [dir] and [name] are internal tokens (`[A-Za-z0-9._-]`), never user text.
 * A write is temp file → fsync → atomic rename → directory fsync, like the project-pin persistence.
 */
interface MemoFiles {
    fun read(dir: String, name: String): MemoRead<ByteArray>
    fun write(dir: String, name: String, bytes: ByteArray): MemoWrite<Unit>
    fun delete(dir: String, name: String): MemoWrite<Unit>
    /** Direct child directory names of [dir] ("" = the root), or null when the listing failed. */
    fun listDirs(dir: String): List<String>?
    fun listFiles(dir: String): List<String>?
    fun deleteDir(dir: String): MemoWrite<Unit>
}

// ── what the screens render ─────────────────────────────────────────────────────────────────────────

enum class MemoScreen { LIST, RECORDING, PROCESSING, DETAIL }

/** Why a new recording cannot start. [NONE] = it can. */
enum class MemoBlock {
    NONE,
    FEATURE_OFF,
    NOT_OWNER,
    OFFLINE,
    /** The computer's daemon predates voice memos, or announced a contract this build does not know. */
    COMPUTER_OUTDATED,
    WHISPER_MISSING,
    MODEL_MISSING,
    CONVERTER_MISSING,
    UNSUPPORTED_PLATFORM,
    /** The link is up but not end-to-end encrypted. */
    NOT_ENCRYPTED,
    /** An unknown status word — "当前无法使用，请更新或检查电脑配置". */
    UNKNOWN,
    LIBRARY_FULL,
}

data class MemoReadiness(
    val featureOn: Boolean = false,
    /** Null while no computer is paired. */
    val scope: MemoScope? = null,
    val computerName: String = "",
    val online: Boolean = false,
    val block: MemoBlock = MemoBlock.FEATURE_OFF,
    /** Bumps on every new connection; a send captured under an older value must not be written. */
    val connectionGeneration: Int = 0,
    /** The organiser a new memo is organised by — an agent wire name the computer advertised: the app's default
     *  agent when it has an adapter there, otherwise the first one the computer lists. Null = none: the memo is
     *  transcribed only and the to-dos are written by hand. Recording never waits for one. */
    val organizer: String? = null,
    /** Every organiser the computer advertised, in its order. */
    val organizers: List<String> = emptyList(),
    /** The app's default agent, as a wire name — so a screen can say "your default has no adapter here". */
    val defaultAgent: String = "claude",
) {
    val canRecord: Boolean get() = block == MemoBlock.NONE
}

data class MemoListState(
    val loaded: Boolean = false,
    /** The library could not be read — shown as an error, never as an empty list. */
    val unreadable: Boolean = false,
    val rows: List<MemoHeader> = emptyList(),
    val hasMore: Boolean = false,
)

enum class MemoCapturePhase {
    IDLE,
    /** Consent sheet is up; nothing is recording. */
    CONSENT,
    PREPARING,
    RECORDING,
    SAVING,
    PERMISSION_DENIED,
    /** The OS stopped the capture; the fragment is gone. */
    INTERRUPTED,
    /** Another recorder (chat dictation) holds the microphone. */
    MIC_BUSY,
    SAVE_FAILED,
    START_FAILED,
}

data class MemoCaptureState(
    val phase: MemoCapturePhase = MemoCapturePhase.IDLE,
    val elapsedMs: Long = 0,
    /** Set from 2:45 on: whole seconds left until the 3:00 stop. */
    val secondsLeft: Int? = null,
    /** The user pressed back while recording — show continue / finish / discard. */
    val leavePrompt: Boolean = false,
)

enum class MemoStep { UPLOAD, TRANSCRIBE, ORGANIZE }
enum class MemoStepStatus {
    WAITING, RUNNING, DONE, FAILED, NOT_STARTED,
    /** The organise step of a transcribe-only attempt: nothing to run, the transcript is the result. */
    SKIPPED,
}

data class MemoStepRow(
    val step: MemoStep,
    val status: MemoStepStatus,
    /** Measured duration of a finished step; null = not measured. */
    val durationMs: Long? = null,
)

/** What stopped processing, and therefore which actions the page offers. */
enum class MemoProcessingIssue {
    /** Link lost mid-upload; on reconnect only the state is queried. */
    UPLOAD_INTERRUPTED,
    /** Reconnected, the computer holds part of the audio — offers "继续上传". */
    UPLOAD_INCOMPLETE,
    UPLOAD_FAILED,
    EMPTY_TRANSCRIPT,
    TRANSCRIBE_FAILED,
    TRANSCRIBE_TIMEOUT,
    AUDIO_REJECTED,
    ORGANIZE_FAILED,
    ORGANIZE_TIMEOUT,
    AGENT_UNAVAILABLE,
    /** The computer no longer knows this attempt. Whether it ran before is unknown. */
    RECORD_UNAVAILABLE,
    COMPUTER_BUSY,
    CANCELLED,
    /** The reply could not be understood by this build. */
    INCOMPATIBLE,
    /** No reply yet — "等待电脑回应". Not a failure. */
    WAITING_FOR_COMPUTER,
}

data class MemoProcessingState(
    val memoId: String,
    val audioDurationMs: Long,
    val steps: List<MemoStepRow>,
    /** Time since processing started, ticking while it runs; null when it could not be measured. */
    val elapsedMs: Long?,
    val issue: MemoProcessingIssue? = null,
    /** True when the recording ended because it reached 3:00. */
    val reachedLimit: Boolean = false,
    val hasTranscript: Boolean = false,
    val hasAudio: Boolean = true,
    /** The organiser this attempt runs — an agent wire name; null = transcribe only (the organise step is SKIPPED). */
    val organizer: String? = null,
    /** The organiser was gone by the time the computer got to it: transcribed, not organised. */
    val organizerLost: Boolean = false,
) {
    val running: Boolean get() = issue == null || issue == MemoProcessingIssue.WAITING_FOR_COMPUTER
}

data class MemoTodoRow(
    val todoId: String,
    val text: String,
    val selected: Boolean,
    /** A [MemoTodoState] word. */
    val state: String,
    val errorCode: String? = null,
    /** Index (1-based) of the to-do this one was copied from, for the duplicate-risk note; null = not a copy. */
    val copiedFromIndex: Int? = null,
    /** True when the source of the copy is still awaiting a receipt — the row carries the duplicate warning. */
    val copyOfUnknown: Boolean = false,
    val suggestedTarget: String? = null,
    /** See [MemoTodo.wholeTranscript]. */
    val wholeTranscript: Boolean = false,
) {
    val editable: Boolean get() = state == MemoTodoState.DRAFT
}

data class MemoDocumentState(
    val memoId: String,
    /** The stored title; blank for a memo that was not organised and not titled by hand — show [titleFallback]. */
    val title: String,
    /** The transcript's first sentence (≤ 80 code points), for a blank [title]. */
    val titleFallback: String = "",
    val createdAtMs: Long,
    val audioDurationMs: Long,
    val summary: String?,
    val summaryUnstructured: Boolean,
    /** The organiser's output was not usable — "未能整理成待办". */
    val degraded: Boolean,
    /** Which agent organised this memo; null = not organised (a transcribe-only memo, or [degraded]). */
    val organizedBy: String? = null,
    /** The memo is unorganised because the organiser it asked for was gone by the time the computer got to it
     *  (the latest attempt ended `transcribed` + `agent_unavailable`) — the "未整理" note says so. */
    val organizerLost: Boolean = false,
    /** The organiser an [MemoAction.Reorganize] would run now — [MemoReadiness.organizer]; null = none, the
     *  button is not offered. */
    val organizerAvailable: String? = null,
    /** Whether [MemoAction.AddWholeTranscriptTodo] can make one to-do of the transcript (it fits the to-do limit). */
    val wholeTranscriptFits: Boolean = false,
    val todos: List<MemoTodoRow>,
    val transcript: String?,
    val timings: MemoTimings,
    /** An edit is being stored; dispatch stays disabled until it lands. */
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val atTodoLimit: Boolean = false,
    /** A re-organise is running for this memo. */
    val reorganizing: Boolean = false,
)

/** One to-do as the confirm sheet displayed it. */
data class MemoShownItem(val todoId: String, val text: String)

/** Why the dispatch button is disabled. [NONE] = it is enabled. */
enum class MemoDispatchBlock { NONE, NO_SELECTION, ONLY_BLANK, NO_TARGET, SAVING, TARGET_UNAVAILABLE, OFFLINE, BUSY }

enum class MemoTargetStatus { IDLE, RUNNING, ARCHIVED, OBSERVING, NO_PERMISSION, OFFLINE, NEEDS_TAKEOVER }

data class MemoTargetRow(
    val target: MemoTarget,
    val status: MemoTargetStatus,
    /** The permission mode a dispatch would open the session with (a PermissionMode wire word, or the
     *  backend-native one); null = not known. Shown on the confirm sheet, never editable there. */
    val mode: String? = null,
    /** Wall-clock time of the session's last change; 0 = unknown. */
    val lastModifiedMs: Long = 0,
) {
    val selectable: Boolean get() = status == MemoTargetStatus.IDLE || status == MemoTargetStatus.RUNNING
}

/** One project of the bound computer, on the picker's first level. */
data class MemoProjectRow(
    val workdir: String,
    val name: String,
    /** Null = not counted (the list was not fetched); 0 = the project has no session yet. */
    val sessionCount: Int? = null,
    val running: Boolean = false,
)

enum class MemoListStatus { LOADING, READY, FAILED }

/** The picker's second level: one project's sessions. */
data class MemoProjectSessions(
    val workdir: String,
    val name: String,
    val status: MemoListStatus,
    val sessions: List<MemoTargetRow> = emptyList(),
)

/** What "new session in this project" may use. */
data class MemoNewSessionOptions(
    /** Agents the computer can launch, the default first. Empty = a new session cannot be created. */
    val agents: List<AgentKind> = emptyList(),
    val defaultAgent: AgentKind = AgentKind.CLAUDE,
    /** The app's default permission mode for [defaultAgent] — displayed, not editable here. */
    val mode: String? = null,
    /** The same, per agent: a backend-native mode (Claude's `auto`) does not exist for the others, which
     *  fall back to the plain default. Absent = [mode]. */
    val modeByAgent: Map<AgentKind, String> = emptyMap(),
) {
    fun modeFor(agent: AgentKind): String? = modeByAgent[agent] ?: mode
}

/** Everything the target picker shows. */
data class MemoTargetCatalog(
    val status: MemoListStatus = MemoListStatus.LOADING,
    /** Running sessions and the ones this phone opened last — at most [MAX_RECENT]. */
    val recent: List<MemoTargetRow> = emptyList(),
    val projects: List<MemoProjectRow> = emptyList(),
    /** The project the second level is showing; null = the first level. */
    val project: MemoProjectSessions? = null,
    val newSession: MemoNewSessionOptions = MemoNewSessionOptions(),
) {
    companion object { const val MAX_RECENT = 5 }
}

data class MemoSelectionState(
    val target: MemoTargetRow? = null,
    /** Selected drafts with non-blank text — what a dispatch would send, in order. */
    val items: List<MemoTodoRow> = emptyList(),
    val block: MemoDispatchBlock = MemoDispatchBlock.NO_SELECTION,
) {
    val count: Int get() = items.size
    val canDispatch: Boolean get() = block == MemoDispatchBlock.NONE
    /** The same-turn notice is shown for every batch of more than one item, busy target or not. */
    val showsMultiItemNotice: Boolean get() = items.size > 1

    /** What a confirm sheet rendering this selection shows, for [MemoAction.ConfirmDispatch]. */
    val shown: List<MemoShownItem> get() = items.map { MemoShownItem(it.todoId, it.text.trim()) }
}

enum class MemoDispatchPhase {
    /** Opening the target chat — or creating it, see [MemoDispatchState.creating]; nothing has been sent. */
    OPENING,
    SENDING,
    /** Stopped before the end: a receipt is missing, a send failed, or the user left. */
    STOPPED,
    /** Every item of the batch was delivered. */
    DONE,
    /** The target could not be opened; every item is still a draft. */
    OPEN_FAILED,
}

enum class MemoDispatchStop { NONE, RECEIPT_MISSING, NOT_SENT, LEFT_CHAT, BACKGROUND, MANUAL_MESSAGE, COMPUTER_CHANGED, FEATURE_OFF, TARGET_LOST, SAVE_FAILED }

/** The batch shown in the target chat's status strip. Counts are per item of THIS batch. */
data class MemoDispatchState(
    val batchId: String,
    val memoId: String,
    val memoTitle: String,
    val target: MemoTarget,
    val convoId: String?,
    val phase: MemoDispatchPhase,
    val stop: MemoDispatchStop = MemoDispatchStop.NONE,
    /** The batch was confirmed for a NEW session: OPENING reads "creating the session", OPEN_FAILED "the
     *  session was not created". Stays true after the session exists. */
    val creating: Boolean = false,
    /** Why the target could not be opened or created — [MemoEnterResult.Failed.reason]; null otherwise. */
    val openFailure: String? = null,
    val total: Int,
    /** 1-based index of the item being sent while [phase] is SENDING. */
    val current: Int = 0,
    val delivered: Int = 0,
    val unknown: Int = 0,
    val failed: Int = 0,
) {
    /** Items of the batch that were never submitted — "N 项未发送". */
    val unsent: Int get() = (total - delivered - unknown - failed - if (phase == MemoDispatchPhase.SENDING) 1 else 0).coerceAtLeast(0)
}

enum class MemoToast {
    COPIED, SAVE_FAILED, DELETED, DELETE_FAILED, LIBRARY_FULL, TODO_LIMIT,
    /** A confirmed dispatch was refused because the memo changed after the sheet was drawn. */
    CHANGED_SINCE_SHOWN,
}

data class MemoUiState(
    val readiness: MemoReadiness = MemoReadiness(),
    val screen: MemoScreen = MemoScreen.LIST,
    val list: MemoListState = MemoListState(),
    val capture: MemoCaptureState = MemoCaptureState(),
    val processing: MemoProcessingState? = null,
    val document: MemoDocumentState? = null,
    val selection: MemoSelectionState = MemoSelectionState(),
    val catalog: MemoTargetCatalog = MemoTargetCatalog(),
    val dispatch: MemoDispatchState? = null,
    val toast: MemoToast? = null,
)

sealed interface MemoAction {
    // lifecycle, raised by the host
    data object Opened : MemoAction
    data object Closed : MemoAction
    data object Foreground : MemoAction
    data object Background : MemoAction

    // list
    data object LoadMore : MemoAction
    data class OpenMemo(val memoId: String) : MemoAction
    /** The user confirmed the delete sheet. */
    data class DeleteMemo(val memoId: String) : MemoAction
    data object BackToList : MemoAction

    // capture
    data object NewMemo : MemoAction
    data object AcceptConsent : MemoAction
    data object DeclineConsent : MemoAction
    data object StopRecording : MemoAction
    data object DiscardRecording : MemoAction
    data object RequestLeaveRecording : MemoAction
    data object ContinueRecording : MemoAction
    data object RetryRecording : MemoAction
    data object OpenMicSettings : MemoAction

    // processing
    data object CancelProcessing : MemoAction
    /** Same attempt, whole audio again. */
    data object ResumeUpload : MemoAction
    /** A new attempt: the stored transcript when there is one, otherwise the audio. */
    data object RetryProcessing : MemoAction
    /** A new attempt from the audio even though a transcript exists (empty / failed transcription). */
    data object RetryTranscription : MemoAction
    data object ShowTranscript : MemoAction

    // result
    data class EditTitle(val title: String) : MemoAction
    data class ToggleTodo(val todoId: String) : MemoAction
    data class EditTodo(val todoId: String, val text: String) : MemoAction
    data class DeleteTodo(val todoId: String) : MemoAction
    data object AddTodo : MemoAction
    /** One selected draft holding the whole transcript — for a memo that was not organised. */
    data object AddWholeTranscriptTodo : MemoAction
    data class CopyTodo(val todoId: String) : MemoAction
    /** A FAILED item goes back to a selected draft. */
    data class RetryFailedTodo(val todoId: String) : MemoAction
    /** Organise the transcript with [MemoReadiness.organizer] — a first organisation for a transcribe-only
     *  memo, or a new one; the user confirmed that the result replaces the current drafts, if any. */
    data object Reorganize : MemoAction
    data class ViewSession(val todoId: String) : MemoAction

    // dispatch
    /** Read the picker's catalog again (the picker opened, or the host says its rows changed). */
    data object RefreshTargets : MemoAction
    /** Second level: show [workdir]'s sessions. */
    data class OpenTargetProject(val workdir: String) : MemoAction
    /** Back to the first level. */
    data object CloseTargetProject : MemoAction
    /** An existing session, from "recent" or from a project's list. */
    data class SelectTarget(val target: MemoTarget) : MemoAction
    /** "New session in this project", on [agent]. Creates nothing: the session is created by the dispatch. */
    data class SelectNewSession(val workdir: String, val agent: AgentKind) : MemoAction
    /**
     * The ONLY execution authorisation: the confirm sheet's primary button. It carries exactly what the sheet
     * showed — the items in order and the target. The action is handled a moment after the tap; if the memo
     * changed in between (a re-organise landed, another edit was stored), what would be sent is no longer what
     * the user read, and the dispatch is refused rather than sent on a guess.
     */
    data class ConfirmDispatch(val shown: List<MemoShownItem>, val target: MemoTarget) : MemoAction
    /** "返回备忘" in the target chat. */
    data object ReturnToMemo : MemoAction
    data object ToastShown : MemoAction
}

interface VoiceMemoRepository {
    val state: StateFlow<MemoUiState>
    /** 0..1 microphone envelope while recording — kept off [state] so a level tick redraws only the meter. */
    val levels: StateFlow<Float>
    /** Processed one at a time, in order. Never blocks the caller. */
    fun accept(action: MemoAction)
}

// ── ports the host implements ───────────────────────────────────────────────────────────────────────

sealed interface MemoLinkSend {
    /** Proven not written: the link, the generation or the feature was gone before the writer took it. */
    data object NotWritten : MemoLinkSend
    /** Handed to the local socket. Not a receipt. */
    data object Written : MemoLinkSend
    /** The write had started when the link died or the send was cancelled. */
    data object Indeterminate : MemoLinkSend
}

data class MemoRemoteState(val bindingId: String, val state: VoiceMemoState)

/** Memo frames in and out. A frame sent here belongs to ONE connection: it is never buffered across a
 *  reconnect, re-routed to another transport, or flushed to another computer. */
interface MemoLink {
    val readiness: StateFlow<MemoReadiness>
    val states: Flow<MemoRemoteState>
    suspend fun send(scope: MemoScope, generation: Int, frame: ToDaemon): MemoLinkSend
}

/** A capture in memory. The store must write it; the recorder keeps no file. */
class MemoRecording(val bytes: ByteArray, val mediaType: String, val durationMs: Long)

sealed interface MemoRecorderStart {
    data object Started : MemoRecorderStart
    data object PermissionDenied : MemoRecorderStart
    /** The chat recorder holds the microphone lease. */
    data object Busy : MemoRecorderStart
    data object Failed : MemoRecorderStart
}

/** The microphone, behind the lease it shares with chat dictation. */
interface MemoRecorder {
    val levels: Flow<Float>
    val interruptions: Flow<Unit>
    suspend fun start(): MemoRecorderStart
    /** Null when there was nothing to return (already stopped / interrupted). Releases the lease. */
    suspend fun stop(): MemoRecording?
    /** Discards the capture and releases the lease. Safe to call in any state. */
    fun cancel()
    fun openSettings()
}

/** Small per-scope facts that are not memo content. */
interface MemoPrefs {
    fun consentAccepted(scope: MemoScope): Boolean
    fun acceptConsent(scope: MemoScope)
}

interface MemoClock {
    /** Wall clock, for display only. */
    fun nowMs(): Long
    /** Monotonic, for durations. */
    fun monotonicMs(): Long
}

/** Proof that the exact target chat is open and writable for one batch. */
class MemoTargetLease(
    val target: MemoTarget,
    val convoId: String,
    val batchId: String,
    val connectionGeneration: Int,
)

sealed interface MemoEnterResult {
    /** [lease].target is the session that is open now. For a new-session request it is the target of the
     *  session just created (`newSession = false`; its `sessionId` may still be empty — see
     *  [MemoSessionGateway.resolved]). */
    data class Entered(val lease: MemoTargetLease) : MemoEnterResult
    /** Nothing was sent; every item stays a draft. [reason] is one of [MemoEnterFailure]. */
    data class Failed(val reason: String) : MemoEnterResult
}

/** [MemoEnterResult.Failed.reason] words the screens explain; any other word reads as [OTHER]. */
object MemoEnterFailure {
    const val OFFLINE = "offline"
    const val NOT_READY = "not_ready"
    const val REFUSED = "open_refused"
    const val FAILED = "open_failed"
    const val TIMEOUT = "open_timeout"
    const val AGENT_UNAVAILABLE = "agent_unavailable"
    const val NOT_THE_TARGET = "not_the_target"
    const val OBSERVING = "observing"
    const val OTHER = "other"
}

class ConfirmedMemoPrompt(
    val memoId: String,
    val todoId: String,
    /** Already stored by the coordinator. */
    val promptId: String,
    /** The to-do text the user confirmed. The gateway adds the fixed prefix. */
    val text: String,
    val lease: MemoTargetLease,
)

sealed interface MemoSubmitResult {
    /** Proven not to have reached a writer. */
    data object NotSubmitted : MemoSubmitResult
    data object AwaitingReceipt : MemoSubmitResult
    data object Unknown : MemoSubmitResult
}

data class MemoPromptReceipt(val bindingId: String, val convoId: String, val promptId: String)

/** Opening the target chat and sending one confirmed to-do through the app's normal prompt path, under
 *  the "confirm before resend" policy: no attachment, no watchdog resend, no fresh-id retry. */
interface MemoSessionGateway {
    /** Navigates to [target], waits for the exact session to be live and writable. Never forks or takes over.
     *  With [MemoTarget.newSession] it creates the session first — once, never again for the same batch. */
    suspend fun enter(target: MemoTarget, batchId: String): MemoEnterResult
    /** The target of the session [lease] holds, as known NOW. A session created by [enter] may learn its id only
     *  after its first message; the coordinator asks again after every delivery. Null = the lease's chat is gone. */
    fun resolved(lease: MemoTargetLease): MemoTarget?
    /** True while the lease's chat is on screen, writable, on the same connection, with the feature on. */
    fun holds(lease: MemoTargetLease): Boolean
    suspend fun submit(prompt: ConfirmedMemoPrompt): MemoSubmitResult
    val receipts: Flow<MemoPromptReceipt>
    /** Why a running batch must stop sending: the user left the chat, typed a message of their own, the
     *  app went to the background, the computer changed, or the feature was switched off. */
    val stops: Flow<MemoDispatchStop>
    /** "查看会话": show [target]'s chat without sending anything. */
    fun show(target: MemoTarget)
    /** Leave the chat and show the memo again. */
    fun showMemo(memoId: String)
    /**
     * The picker's catalog as the host knows it now, with [project]'s sessions on the second level. Never
     * suspends: what is still being fetched reads LOADING, and the host raises [MemoAction.RefreshTargets]
     * when it arrives or is given up on.
     */
    fun catalog(project: String?): MemoTargetCatalog
}

/** The text actually sent for a confirmed to-do. The natural-language prefix keeps a to-do that starts
 *  with a slash from being read as a control command. */
fun memoPromptText(todo: String): String =
    "以下是一项经我确认的语音备忘任务，请按当前会话的权限和上下文处理：\n\n" + todo.trim()
