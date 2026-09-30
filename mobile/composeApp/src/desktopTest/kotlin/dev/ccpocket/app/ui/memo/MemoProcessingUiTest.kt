package dev.ccpocket.app.ui.memo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import dev.ccpocket.app.memo.MemoAction
import dev.ccpocket.app.memo.MemoProcessingIssue
import dev.ccpocket.app.memo.MemoProcessingState
import dev.ccpocket.app.memo.MemoStepStatus
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.str
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The processing page: every issue offers exactly the actions that can help (handoff "处理" + frozen spec
 * §9.3), nothing ever prints a percentage or a raw format argument, and the back target tells the truth
 * about whether processing continues.
 */
@OptIn(ExperimentalTestApi::class)
class MemoProcessingUiTest {

    private val every = listOf(
        Res.string.memo_proc_cancel, Res.string.memo_proc_resume_upload, Res.string.memo_proc_retry_transcribe,
        Res.string.memo_proc_rerecord, Res.string.memo_proc_delete, Res.string.memo_reorganize_with,
        Res.string.memo_proc_show_transcript, Res.string.memo_proc_reprocess, Res.string.memo_proc_to_list,
    )

    /** A button's rendered label; "用 X 重新整理" names the readiness organiser (Claude in these fixtures). */
    private fun label(res: StringResource): String =
        if (res == Res.string.memo_reorganize_with) str(res, "Claude") else str(res)

    private fun p(issue: MemoProcessingIssue?, hasAudio: Boolean = true, elapsedMs: Long? = 12_400, reachedLimit: Boolean = false) =
        MemoProcessingState(
            memoId = "m1", audioDurationMs = 75_000,
            steps = when (issue) {
                null, MemoProcessingIssue.WAITING_FOR_COMPUTER -> MemoFx.steps(MemoStepStatus.DONE, MemoStepStatus.RUNNING, MemoStepStatus.WAITING)
                MemoProcessingIssue.UPLOAD_INTERRUPTED, MemoProcessingIssue.UPLOAD_INCOMPLETE, MemoProcessingIssue.UPLOAD_FAILED ->
                    MemoFx.steps(MemoStepStatus.FAILED, MemoStepStatus.NOT_STARTED, MemoStepStatus.NOT_STARTED)
                MemoProcessingIssue.ORGANIZE_FAILED, MemoProcessingIssue.ORGANIZE_TIMEOUT, MemoProcessingIssue.AGENT_UNAVAILABLE ->
                    MemoFx.steps(MemoStepStatus.DONE, MemoStepStatus.DONE, MemoStepStatus.FAILED)
                else -> MemoFx.steps(MemoStepStatus.DONE, MemoStepStatus.FAILED, MemoStepStatus.NOT_STARTED)
            },
            elapsedMs = elapsedMs, issue = issue, reachedLimit = reachedLimit, hasTranscript = true, hasAudio = hasAudio,
            // these runs organise (v2.1 cases); transcribe-only runs are covered in MemoOrganizerUiTest
            organizer = "claude",
        )

    /** The written expectation, independent of the page's own table. [organizer]: one is available to retry with. */
    private fun expected(issue: MemoProcessingIssue?, hasAudio: Boolean, organizer: Boolean = true): List<StringResource> = when (issue) {
        null, MemoProcessingIssue.WAITING_FOR_COMPUTER, MemoProcessingIssue.UPLOAD_INTERRUPTED -> listOf(Res.string.memo_proc_cancel)
        MemoProcessingIssue.UPLOAD_INCOMPLETE -> listOf(Res.string.memo_proc_resume_upload, Res.string.memo_proc_cancel)
        MemoProcessingIssue.EMPTY_TRANSCRIPT ->
            listOfNotNull(Res.string.memo_proc_rerecord, Res.string.memo_proc_retry_transcribe.takeIf { hasAudio }, Res.string.memo_proc_delete)
        MemoProcessingIssue.TRANSCRIBE_FAILED, MemoProcessingIssue.TRANSCRIBE_TIMEOUT, MemoProcessingIssue.AUDIO_REJECTED ->
            if (hasAudio) listOf(Res.string.memo_proc_retry_transcribe, Res.string.memo_proc_cancel)
            else listOf(Res.string.memo_proc_cancel, Res.string.memo_proc_to_list)
        MemoProcessingIssue.ORGANIZE_FAILED, MemoProcessingIssue.ORGANIZE_TIMEOUT, MemoProcessingIssue.AGENT_UNAVAILABLE ->
            // with no organiser now there is nothing to re-organise with: only the transcript is offered
            if (organizer) listOf(Res.string.memo_reorganize_with, Res.string.memo_proc_show_transcript)
            else listOf(Res.string.memo_proc_show_transcript)
        MemoProcessingIssue.RECORD_UNAVAILABLE, MemoProcessingIssue.UPLOAD_FAILED, MemoProcessingIssue.COMPUTER_BUSY ->
            listOf(Res.string.memo_proc_reprocess, Res.string.memo_proc_cancel)
        MemoProcessingIssue.CANCELLED -> listOf(Res.string.memo_proc_reprocess, Res.string.memo_proc_to_list)
        MemoProcessingIssue.INCOMPATIBLE -> listOf(Res.string.memo_proc_to_list, Res.string.memo_proc_cancel)
    }

    @Test
    fun everyIssueOffersExactlyItsOwnButtonsAndNothingPrintsAPercentage() {
        var state by mutableStateOf(MemoFx.processing(p(null)))
        memoScene(height = 1200, content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            val cases = listOf<MemoProcessingIssue?>(null) + MemoProcessingIssue.entries
            for (issue in cases) for (audio in listOf(true, false)) for (organizer in listOf(true, false)) {
                state = MemoFx.processing(p(issue, hasAudio = audio))
                    .copy(readiness = MemoFx.readiness(organizer = if (organizer) "claude" else null))
                waitForIdle()
                val want = expected(issue, audio, organizer).map { label(it) }.toSet()
                val shown = every.map { label(it) }.filter { buttonCount(it) > 0 }.toSet()
                assertEquals(want, shown, "issue=$issue hasAudio=$audio organizer=$organizer")
                val stray = allScreenText().filter { it.contains('%') }
                assertTrue(stray.isEmpty(), "issue=$issue: no percentage / raw format text, found $stray")
                assertFalse(has("ETA", substring = true))
            }
        }
    }

    @Test
    fun runningAndStoppedTellTheTruthAboutLeaving() {
        var state by mutableStateOf(MemoFx.processing(p(null, reachedLimit = true)))
        val actions = mutableListOf<MemoAction>()
        memoScene(content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            assertTrue(has(str(Res.string.memo_proc_title_running)))
            assertTrue(has(str(Res.string.memo_proc_elapsed, 12)), "the measured elapsed time")
            assertTrue(has(str(Res.string.memo_proc_footer)), "running: leaving does not cancel")
            assertTrue(has(str(Res.string.memo_proc_limit_note)), "the 3:00 stop is explained")
            assertTrue(has(str(Res.string.memo_step_seconds, "1.2")), "a finished stage shows its measured duration")
            assertEquals(1, onAllNodes(hasContentDescription(str(Res.string.memo_back_to_list_running_cd))).fetchSemanticsNodes().size)

            state = MemoFx.processing(p(MemoProcessingIssue.TRANSCRIBE_FAILED, elapsedMs = null))
            waitForIdle()
            assertTrue(has(str(Res.string.memo_proc_title_stopped)))
            assertTrue(has(str(Res.string.memo_not_measured)), "an unmeasured time is written as such")
            assertFalse(has(str(Res.string.memo_proc_footer)), "a stopped page does not promise it continues")
            assertEquals(0, onAllNodes(hasContentDescription(str(Res.string.memo_back_to_list_running_cd))).fetchSemanticsNodes().size)
            tapDescription(str(Res.string.memo_back_to_list_cd))
            assertEquals(MemoAction.BackToList, actions.last())

            state = MemoFx.processing(p(MemoProcessingIssue.WAITING_FOR_COMPUTER))
            waitForIdle()
            assertTrue(has(str(Res.string.memo_issue_waiting_title)), "waiting is its own words")
            assertTrue(has(str(Res.string.memo_proc_title_running)), "…and still counts as in progress")
        }
    }

    @Test
    fun theButtonsEmitTheirActionsAndDeleteAsksFirst() {
        var state by mutableStateOf(MemoFx.processing(p(MemoProcessingIssue.UPLOAD_INCOMPLETE)))
        val actions = mutableListOf<MemoAction>()
        memoScene(content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            buttons(str(Res.string.memo_proc_resume_upload)).onFirst().tap()
            state = MemoFx.processing(p(MemoProcessingIssue.ORGANIZE_TIMEOUT))
            waitForIdle()
            buttons(str(Res.string.memo_reorganize_with, "Claude")).onFirst().tap()
            buttons(str(Res.string.memo_proc_show_transcript)).onFirst().tap()
            state = MemoFx.processing(p(MemoProcessingIssue.EMPTY_TRANSCRIPT))
            waitForIdle()
            buttons(str(Res.string.memo_proc_retry_transcribe)).onFirst().tap()
            buttons(str(Res.string.memo_proc_rerecord)).onFirst().tap()
            buttons(str(Res.string.memo_proc_delete)).onFirst().tap()
            waitForIdle()
            assertTrue(actions.none { it is MemoAction.DeleteMemo }, "delete asks first")
            assertTrue(has(str(Res.string.memo_delete_body)))
            buttons(str(Res.string.memo_delete)).onLast().tap()
            waitForIdle()
            assertEquals(
                listOf(
                    MemoAction.ResumeUpload, MemoAction.RetryProcessing, MemoAction.ShowTranscript,
                    MemoAction.RetryTranscription, MemoAction.NewMemo, MemoAction.DeleteMemo("m1"),
                ),
                actions,
            )
        }
    }

    @Test
    fun theButtonTableMatchesTheWrittenExpectation() {
        for (issue in listOf<MemoProcessingIssue?>(null) + MemoProcessingIssue.entries) for (audio in listOf(true, false)) {
            for (organizer in listOf("codex", null)) assertEquals(
                expected(issue, audio, organizer != null),
                processingButtons(p(issue, hasAudio = audio), organizer).map { it.first.label },
                "issue=$issue audio=$audio organizer=$organizer",
            )
        }
        assertEquals(null, MemoProcButton.DELETE.action(), "delete always goes through the confirmation")
    }
}
