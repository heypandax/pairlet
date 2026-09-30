package dev.ccpocket.app.ui.memo

import androidx.compose.runtime.Composable
import dev.ccpocket.app.memo.MemoAction
import dev.ccpocket.app.memo.MemoBlock
import dev.ccpocket.app.memo.MemoDispatchBlock
import dev.ccpocket.app.memo.MemoEnterFailure
import dev.ccpocket.app.ui.AUTO_MODE
import dev.ccpocket.app.ui.MODES
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.app.memo.MemoHeader
import dev.ccpocket.app.memo.MemoLocalStage
import dev.ccpocket.app.memo.MemoProcessingIssue
import dev.ccpocket.app.memo.MemoProcessingState
import dev.ccpocket.app.memo.MemoReadiness
import dev.ccpocket.app.memo.MemoTargetStatus
import dev.ccpocket.app.memo.MemoToast
import dev.ccpocket.app.memo.MemoTodoState
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.ui.agentName
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.VoiceMemoError
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoStage
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// Every user-visible word the memo screens derive from contract state. The screens never print a raw
// error code, stage word or daemon message — each is mapped here to fixed, localised copy.

internal const val MEMO_SEP = " · "

// ── list rows ───────────────────────────────────────────────────────────────────────────────────────

/** A list row's written status: a processing word while there is no result, else the delivery counts. */
@Composable
internal fun memoHeaderStatus(h: MemoHeader): String {
    val stage = h.stage
    if (stage != null) {
        return when {
            h.errorCode != null -> stringResource(errorStatus(h.errorCode))
            stage == MemoLocalStage.INTERRUPTED -> stringResource(Res.string.memo_status_upload_interrupted)
            stage == VoiceMemoStage.CANCELLED -> stringResource(Res.string.memo_status_cancelled)
            stage == VoiceMemoStage.UNKNOWN -> stringResource(Res.string.memo_status_record_unavailable)
            stage == VoiceMemoStage.FAILED -> stringResource(Res.string.memo_status_stopped)
            else -> runningStep(stage)?.let { stringResource(Res.string.memo_status_processing_step, stringResource(it)) }
                ?: stringResource(Res.string.memo_status_processing)
        }
    }
    val dispatched = h.sending + h.delivered + h.unknown + h.failed
    if (dispatched == 0) {
        return if (h.drafts > 0) stringResource(Res.string.memo_count_drafts, h.drafts)
        else stringResource(Res.string.memo_status_no_todos)
    }
    val parts = buildList {
        if (h.delivered > 0) add(stringResource(Res.string.memo_count_delivered, h.delivered))
        if (h.sending > 0) add(stringResource(Res.string.memo_count_sending, h.sending))
        if (h.unknown > 0) add(stringResource(Res.string.memo_count_unknown, h.unknown))
        if (h.failed > 0) add(stringResource(Res.string.memo_count_failed, h.failed))
        if (h.drafts > 0) add(stringResource(Res.string.memo_count_drafts, h.drafts))
    }
    return parts.joinToString(MEMO_SEP)
}

private fun runningStep(stage: String): StringResource? = when (stage) {
    MemoLocalStage.SAVED, MemoLocalStage.UPLOADING, VoiceMemoStage.RECEIVING -> Res.string.memo_step_upload
    VoiceMemoStage.QUEUED, VoiceMemoStage.TRANSCRIBING -> Res.string.memo_step_transcribe
    VoiceMemoStage.SUMMARIZING -> Res.string.memo_step_organize
    else -> null
}

private fun errorStatus(code: String): StringResource = when (code) {
    VoiceMemoError.UPLOAD_TIMEOUT -> Res.string.memo_status_upload_interrupted
    VoiceMemoError.EMPTY_TRANSCRIPT -> Res.string.memo_status_empty_transcript
    VoiceMemoError.TRANSCRIBE_FAILED, VoiceMemoError.TRANSCRIBE_TIMEOUT, VoiceMemoError.AUDIO_INVALID,
    VoiceMemoError.AUDIO_TOO_LONG -> Res.string.memo_status_transcribe_failed
    VoiceMemoError.SUMMARY_FAILED, VoiceMemoError.SUMMARY_TIMEOUT, VoiceMemoError.AGENT_UNAVAILABLE,
    VoiceMemoError.INVALID_RESULT -> Res.string.memo_status_organize_failed
    VoiceMemoError.UNKNOWN_JOB -> Res.string.memo_status_record_unavailable
    VoiceMemoError.CANCELLED -> Res.string.memo_status_cancelled
    else -> Res.string.memo_status_stopped
}

/** True when a list row's status is a stopped processing (drawn in the danger ink). */
internal fun MemoHeader.stopped(): Boolean = stage != null && (
    errorCode != null || stage == MemoLocalStage.INTERRUPTED || stage == VoiceMemoStage.FAILED || stage == VoiceMemoStage.UNKNOWN
    )

// ── readiness ───────────────────────────────────────────────────────────────────────────────────────

/** Why "新建备忘" is disabled, one sentence per [MemoBlock]; null when it is enabled. */
@Composable
internal fun newMemoBlockedReason(readiness: MemoReadiness): String? {
    val name = computerName(readiness)
    return when (readiness.block) {
        MemoBlock.NONE -> null
        MemoBlock.FEATURE_OFF -> stringResource(Res.string.memo_new_blocked_feature_off)
        MemoBlock.NOT_OWNER -> stringResource(Res.string.memo_new_blocked_not_owner, name)
        MemoBlock.OFFLINE -> stringResource(Res.string.memo_new_blocked_offline, name)
        MemoBlock.COMPUTER_OUTDATED -> stringResource(Res.string.memo_new_blocked_outdated, name)
        MemoBlock.WHISPER_MISSING -> stringResource(Res.string.memo_new_blocked_whisper, name)
        MemoBlock.MODEL_MISSING -> stringResource(Res.string.memo_new_blocked_model, name)
        MemoBlock.CONVERTER_MISSING -> stringResource(Res.string.memo_new_blocked_converter, name)
        MemoBlock.UNSUPPORTED_PLATFORM -> stringResource(Res.string.memo_new_blocked_platform, name)
        MemoBlock.NOT_ENCRYPTED -> stringResource(Res.string.memo_new_blocked_encryption)
        MemoBlock.UNKNOWN -> stringResource(Res.string.memo_new_blocked_unknown)
        MemoBlock.LIBRARY_FULL -> stringResource(Res.string.memo_new_blocked_full, VoiceMemoLimits.MAX_LOCAL_MEMOS)
    }
}

/** A readiness problem written out: what is wrong, what it means, what to do next. */
internal data class MemoProblemCopy(val title: String, val body: String, val next: String, val mark: MemoMark, val danger: Boolean)

@Composable
internal fun readinessProblem(readiness: MemoReadiness): MemoProblemCopy? {
    val name = computerName(readiness)
    val block = readiness.block
    val (title, body, next) = when (block) {
        MemoBlock.NONE, MemoBlock.FEATURE_OFF -> return null
        MemoBlock.NOT_OWNER -> Triple(stringResource(Res.string.memo_rd_not_owner_title), stringResource(Res.string.memo_rd_not_owner_body, name), stringResource(Res.string.memo_rd_not_owner_next))
        MemoBlock.OFFLINE -> Triple(stringResource(Res.string.memo_rd_offline_title, name), stringResource(Res.string.memo_rd_offline_body), stringResource(Res.string.memo_rd_offline_next))
        MemoBlock.COMPUTER_OUTDATED -> Triple(stringResource(Res.string.memo_rd_outdated_title), stringResource(Res.string.memo_rd_outdated_body, name), stringResource(Res.string.memo_rd_outdated_next))
        MemoBlock.WHISPER_MISSING -> Triple(stringResource(Res.string.memo_rd_whisper_title), stringResource(Res.string.memo_rd_whisper_body, name), stringResource(Res.string.memo_rd_whisper_next))
        MemoBlock.MODEL_MISSING -> Triple(stringResource(Res.string.memo_rd_model_title), stringResource(Res.string.memo_rd_model_body, name), stringResource(Res.string.memo_rd_model_next))
        MemoBlock.CONVERTER_MISSING -> Triple(stringResource(Res.string.memo_rd_converter_title), stringResource(Res.string.memo_rd_converter_body, name), stringResource(Res.string.memo_rd_converter_next))
        MemoBlock.UNSUPPORTED_PLATFORM -> Triple(stringResource(Res.string.memo_rd_platform_title), stringResource(Res.string.memo_rd_platform_body, name), stringResource(Res.string.memo_rd_platform_next))
        MemoBlock.NOT_ENCRYPTED -> Triple(stringResource(Res.string.memo_rd_encryption_title), stringResource(Res.string.memo_rd_encryption_body), stringResource(Res.string.memo_rd_encryption_next))
        MemoBlock.UNKNOWN -> Triple(stringResource(Res.string.memo_rd_unknown_title), stringResource(Res.string.memo_rd_unknown_body), stringResource(Res.string.memo_rd_unknown_next))
        MemoBlock.LIBRARY_FULL -> Triple(stringResource(Res.string.memo_rd_full_title), stringResource(Res.string.memo_rd_full_body, VoiceMemoLimits.MAX_LOCAL_MEMOS), stringResource(Res.string.memo_rd_full_next))
    }
    // configuration gaps the user can fix on the computer read as "attention"; a hard stop reads as danger
    val attention = block in setOf(
        MemoBlock.WHISPER_MISSING, MemoBlock.MODEL_MISSING, MemoBlock.CONVERTER_MISSING,
        MemoBlock.UNKNOWN, MemoBlock.LIBRARY_FULL,
    )
    return MemoProblemCopy(title, body, next, if (attention) MemoMark.DIAMOND else MemoMark.SQUARE, danger = !attention)
}

// ── organiser (v3.1: optional, pluggable — Claude / Codex / none) ─────────────────────────────────────

/** An organiser's display name from its wire word ("codex" → Codex), by the app's own agent names; a word
 *  this build does not know is shown as it came. */
internal fun organizerDisplayName(wire: String): String =
    AgentKind.entries.firstOrNull { it.name.equals(wire, ignoreCase = true) }?.let { agentName(it) } ?: wire

/** [wire]'s display name, or "整理 Agent" when there is none to name. */
@Composable
internal fun organizerNameOrGeneric(wire: String?): String =
    wire?.let(::organizerDisplayName) ?: stringResource(Res.string.memo_organizer_generic)

// ── dispatch ────────────────────────────────────────────────────────────────────────────────────────

/** The written reason the dispatch button is disabled; null when it is enabled. */
@Composable
internal fun dispatchBlockReason(block: MemoDispatchBlock, readiness: MemoReadiness): String? = when (block) {
    MemoDispatchBlock.NONE -> null
    MemoDispatchBlock.NO_SELECTION -> stringResource(Res.string.memo_block_no_selection)
    MemoDispatchBlock.ONLY_BLANK -> stringResource(Res.string.memo_block_only_blank)
    MemoDispatchBlock.NO_TARGET -> stringResource(Res.string.memo_block_no_target)
    MemoDispatchBlock.SAVING -> stringResource(Res.string.memo_block_saving)
    MemoDispatchBlock.TARGET_UNAVAILABLE -> stringResource(Res.string.memo_block_target_unavailable)
    MemoDispatchBlock.OFFLINE -> stringResource(Res.string.memo_block_offline, computerName(readiness))
    MemoDispatchBlock.BUSY -> stringResource(Res.string.memo_block_busy)
}

@Composable
internal fun dispatchLabel(count: Int, newSession: Boolean = false): String = when {
    newSession && count > 0 -> stringResource(Res.string.memo_dispatch_new_n, count)
    newSession -> stringResource(Res.string.memo_dispatch_new)
    count > 0 -> stringResource(Res.string.memo_dispatch_n, count)
    else -> stringResource(Res.string.memo_dispatch)
}

/**
 * A permission mode's readable name, from the SAME resources the chat's mode sheet uses ([MODES] /
 * [AUTO_MODE]). [mode] may be a [dev.ccpocket.protocol.PermissionMode] enum name, its wire word, or the
 * backend-native "auto"; anything else is shown as it came.
 */
@Composable
internal fun permissionModeName(mode: String): String {
    val info = MODES.firstOrNull { it.key.name == mode || it.tech == mode }
        ?: AUTO_MODE.takeIf { mode == AUTO_MODE.tech || mode == AUTO_MODE.nativeMode || mode.equals(CLAUDE_PERMISSION_MODE_AUTO, ignoreCase = true) }
    return info?.let { stringResource(it.label) } ?: mode
}

/** Why the target could not be opened or created, one sentence per [MemoEnterFailure] word. */
@Composable
internal fun enterFailureReason(reason: String?, readiness: MemoReadiness): String {
    val name = computerName(readiness)
    return when (reason) {
        MemoEnterFailure.OFFLINE -> stringResource(Res.string.memo_enter_offline, name)
        MemoEnterFailure.NOT_READY -> stringResource(Res.string.memo_enter_not_ready, name)
        MemoEnterFailure.REFUSED -> stringResource(Res.string.memo_enter_refused, name)
        MemoEnterFailure.FAILED -> stringResource(Res.string.memo_enter_failed, name)
        MemoEnterFailure.TIMEOUT -> stringResource(Res.string.memo_enter_timeout, name)
        MemoEnterFailure.AGENT_UNAVAILABLE -> stringResource(Res.string.memo_enter_agent_unavailable, name)
        MemoEnterFailure.NOT_THE_TARGET -> stringResource(Res.string.memo_enter_not_the_target)
        MemoEnterFailure.OBSERVING -> stringResource(Res.string.memo_enter_observing)
        else -> stringResource(Res.string.memo_enter_other)
    }
}

/** The word for one delivery state. */
@Composable
internal fun todoStateLabel(todoState: String): String = stringResource(
    when (todoState) {
        MemoTodoState.DRAFT -> Res.string.memo_state_draft
        MemoTodoState.SENDING -> Res.string.memo_state_sending
        MemoTodoState.DELIVERED -> Res.string.memo_state_delivered
        MemoTodoState.FAILED -> Res.string.memo_state_failed
        else -> Res.string.memo_state_unknown
    },
)

@Composable
internal fun targetStatusLabel(status: MemoTargetStatus): String = stringResource(
    when (status) {
        MemoTargetStatus.IDLE -> Res.string.memo_target_status_idle
        MemoTargetStatus.RUNNING -> Res.string.memo_target_status_running
        MemoTargetStatus.ARCHIVED -> Res.string.memo_target_status_archived
        MemoTargetStatus.OBSERVING -> Res.string.memo_target_status_observing
        MemoTargetStatus.NO_PERMISSION -> Res.string.memo_target_status_no_permission
        MemoTargetStatus.OFFLINE -> Res.string.memo_target_status_offline
        MemoTargetStatus.NEEDS_TAKEOVER -> Res.string.memo_target_status_takeover
    },
)

@Composable
internal fun targetStatusReason(status: MemoTargetStatus): String? = when (status) {
    MemoTargetStatus.IDLE -> null
    MemoTargetStatus.RUNNING -> stringResource(Res.string.memo_target_reason_running)
    MemoTargetStatus.ARCHIVED -> stringResource(Res.string.memo_target_reason_archived)
    MemoTargetStatus.OBSERVING -> stringResource(Res.string.memo_target_reason_observing)
    MemoTargetStatus.NO_PERMISSION -> stringResource(Res.string.memo_target_reason_no_permission)
    MemoTargetStatus.OFFLINE -> stringResource(Res.string.memo_target_reason_offline)
    MemoTargetStatus.NEEDS_TAKEOVER -> stringResource(Res.string.memo_target_reason_takeover)
}

@Composable
internal fun toastText(toast: MemoToast): String = when (toast) {
    MemoToast.COPIED -> stringResource(Res.string.memo_toast_copied)
    MemoToast.SAVE_FAILED -> stringResource(Res.string.memo_toast_save_failed)
    MemoToast.DELETED -> stringResource(Res.string.memo_toast_deleted)
    MemoToast.DELETE_FAILED -> stringResource(Res.string.memo_toast_delete_failed)
    MemoToast.LIBRARY_FULL -> stringResource(Res.string.memo_toast_library_full, VoiceMemoLimits.MAX_LOCAL_MEMOS)
    MemoToast.TODO_LIMIT -> stringResource(Res.string.memo_toast_todo_limit, VoiceMemoLimits.MAX_TODOS)
    MemoToast.CHANGED_SINCE_SHOWN -> stringResource(Res.string.memo_toast_changed_since_shown)
}

// ── processing ──────────────────────────────────────────────────────────────────────────────────────

/** One button of the processing page's action area. */
internal enum class MemoProcButton(val label: StringResource) {
    CANCEL(Res.string.memo_proc_cancel),
    RESUME_UPLOAD(Res.string.memo_proc_resume_upload),
    RETRY_TRANSCRIPTION(Res.string.memo_proc_retry_transcribe),
    RERECORD(Res.string.memo_proc_rerecord),
    DELETE(Res.string.memo_proc_delete),
    /** "用 <organiser> 重新整理" — its label takes the organiser a retry would run now. */
    REORGANIZE(Res.string.memo_reorganize_with),
    SHOW_TRANSCRIPT(Res.string.memo_proc_show_transcript),
    REPROCESS(Res.string.memo_proc_reprocess),
    BACK_TO_LIST(Res.string.memo_proc_to_list),
    ;

    /** What a tap emits. [DELETE] returns null: it opens the delete confirmation first. */
    fun action(): MemoAction? = when (this) {
        CANCEL -> MemoAction.CancelProcessing
        RESUME_UPLOAD -> MemoAction.ResumeUpload
        RETRY_TRANSCRIPTION -> MemoAction.RetryTranscription
        RERECORD -> MemoAction.NewMemo
        DELETE -> null
        REORGANIZE, REPROCESS -> MemoAction.RetryProcessing
        SHOW_TRANSCRIPT -> MemoAction.ShowTranscript
        BACK_TO_LIST -> MemoAction.BackToList
    }
}

internal enum class MemoButtonKind { PRIMARY, SECONDARY, DANGER }

/**
 * The action set for a processing state, primary first. The one table both the page and its tests read:
 * every issue offers only what can actually help, and none offers a retry that would silently re-spend.
 * [organizerNow] is the organiser a new attempt would run ([dev.ccpocket.app.memo.MemoReadiness.organizer]):
 * with none, an organise failure offers no "re-organise" — there is nothing to organise with.
 */
internal fun processingButtons(p: MemoProcessingState, organizerNow: String?): List<Pair<MemoProcButton, MemoButtonKind>> {
    val primary = MemoButtonKind.PRIMARY
    val secondary = MemoButtonKind.SECONDARY
    return when (p.issue) {
        null, MemoProcessingIssue.WAITING_FOR_COMPUTER, MemoProcessingIssue.UPLOAD_INTERRUPTED ->
            listOf(MemoProcButton.CANCEL to secondary)
        MemoProcessingIssue.UPLOAD_INCOMPLETE ->
            listOf(MemoProcButton.RESUME_UPLOAD to primary, MemoProcButton.CANCEL to secondary)
        MemoProcessingIssue.UPLOAD_FAILED, MemoProcessingIssue.RECORD_UNAVAILABLE, MemoProcessingIssue.COMPUTER_BUSY ->
            listOf(MemoProcButton.REPROCESS to primary, MemoProcButton.CANCEL to secondary)
        MemoProcessingIssue.EMPTY_TRANSCRIPT -> buildList {
            add(MemoProcButton.RERECORD to primary)
            if (p.hasAudio) add(MemoProcButton.RETRY_TRANSCRIPTION to secondary)
            add(MemoProcButton.DELETE to MemoButtonKind.DANGER)
        }
        MemoProcessingIssue.TRANSCRIBE_FAILED, MemoProcessingIssue.TRANSCRIBE_TIMEOUT, MemoProcessingIssue.AUDIO_REJECTED ->
            if (p.hasAudio) listOf(MemoProcButton.RETRY_TRANSCRIPTION to primary, MemoProcButton.CANCEL to secondary)
            else listOf(MemoProcButton.CANCEL to secondary, MemoProcButton.BACK_TO_LIST to secondary)
        MemoProcessingIssue.ORGANIZE_FAILED, MemoProcessingIssue.ORGANIZE_TIMEOUT, MemoProcessingIssue.AGENT_UNAVAILABLE ->
            if (organizerNow != null) listOf(MemoProcButton.REORGANIZE to primary, MemoProcButton.SHOW_TRANSCRIPT to secondary)
            else listOf(MemoProcButton.SHOW_TRANSCRIPT to primary)
        MemoProcessingIssue.CANCELLED ->
            listOf(MemoProcButton.REPROCESS to primary, MemoProcButton.BACK_TO_LIST to secondary)
        MemoProcessingIssue.INCOMPATIBLE ->
            listOf(MemoProcButton.BACK_TO_LIST to secondary, MemoProcButton.CANCEL to secondary)
    }
}

/** Title + body of a processing issue, written for the user; never the daemon's own text. */
@Composable
internal fun issueCopy(p: MemoProcessingState, readiness: MemoReadiness): Pair<String, String>? {
    val name = computerName(readiness)
    // the organiser THIS attempt ran is the one that failed — not whichever is configured now
    val organizer = organizerNameOrGeneric(p.organizer)
    val noAudio = stringResource(Res.string.memo_issue_no_audio_note)
    fun withAudio(body: String) = if (p.hasAudio) body else "$body $noAudio"
    return when (p.issue ?: return null) {
        MemoProcessingIssue.UPLOAD_INTERRUPTED -> stringResource(Res.string.memo_issue_upload_interrupted_title) to stringResource(Res.string.memo_issue_upload_interrupted_body, name)
        MemoProcessingIssue.UPLOAD_INCOMPLETE -> stringResource(Res.string.memo_issue_upload_incomplete_title) to stringResource(Res.string.memo_issue_upload_incomplete_body)
        MemoProcessingIssue.UPLOAD_FAILED -> stringResource(Res.string.memo_issue_upload_failed_title) to stringResource(Res.string.memo_issue_upload_failed_body)
        MemoProcessingIssue.EMPTY_TRANSCRIPT -> stringResource(Res.string.memo_issue_empty_title) to
            stringResource(if (p.hasAudio) Res.string.memo_issue_empty_body else Res.string.memo_issue_empty_body_no_audio)
        MemoProcessingIssue.TRANSCRIBE_FAILED -> stringResource(Res.string.memo_issue_transcribe_failed_title) to withAudio(stringResource(Res.string.memo_issue_transcribe_failed_body))
        MemoProcessingIssue.TRANSCRIBE_TIMEOUT -> stringResource(Res.string.memo_issue_transcribe_timeout_title) to withAudio(stringResource(Res.string.memo_issue_transcribe_timeout_body))
        MemoProcessingIssue.AUDIO_REJECTED -> stringResource(Res.string.memo_issue_audio_rejected_title) to withAudio(stringResource(Res.string.memo_issue_audio_rejected_body))
        MemoProcessingIssue.ORGANIZE_FAILED -> stringResource(Res.string.memo_issue_organize_failed_title) to stringResource(Res.string.memo_issue_organize_failed_body, organizer)
        MemoProcessingIssue.ORGANIZE_TIMEOUT -> stringResource(Res.string.memo_issue_organize_timeout_title) to stringResource(Res.string.memo_issue_organize_timeout_body, organizer)
        MemoProcessingIssue.AGENT_UNAVAILABLE -> stringResource(Res.string.memo_issue_agent_title) to stringResource(Res.string.memo_issue_agent_body, organizer)
        MemoProcessingIssue.RECORD_UNAVAILABLE -> stringResource(Res.string.memo_issue_record_title) to stringResource(Res.string.memo_issue_record_body)
        MemoProcessingIssue.COMPUTER_BUSY -> stringResource(Res.string.memo_issue_busy_title) to stringResource(Res.string.memo_issue_busy_body)
        MemoProcessingIssue.CANCELLED -> stringResource(Res.string.memo_issue_cancelled_title) to stringResource(Res.string.memo_issue_cancelled_body)
        MemoProcessingIssue.INCOMPATIBLE -> stringResource(Res.string.memo_issue_incompatible_title) to stringResource(Res.string.memo_issue_incompatible_body)
        MemoProcessingIssue.WAITING_FOR_COMPUTER -> stringResource(Res.string.memo_issue_waiting_title) to stringResource(Res.string.memo_issue_waiting_body)
    }
}
