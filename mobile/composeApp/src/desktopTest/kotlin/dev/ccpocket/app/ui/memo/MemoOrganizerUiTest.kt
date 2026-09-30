package dev.ccpocket.app.ui.memo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.ccpocket.app.memo.MemoAction
import dev.ccpocket.app.memo.MemoBlock
import dev.ccpocket.app.memo.MemoCaptureState
import dev.ccpocket.app.memo.MemoCapturePhase
import dev.ccpocket.app.memo.MemoListState
import dev.ccpocket.app.memo.MemoProcessingState
import dev.ccpocket.app.memo.MemoScreen
import dev.ccpocket.app.memo.MemoSelectionState
import dev.ccpocket.app.memo.MemoStepStatus
import dev.ccpocket.app.memo.MemoTodoState
import dev.ccpocket.app.memo.MemoUiState
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.str
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Optional organising (v3.1, board section 3 "整理可选", frames O1–O17): the organiser is Claude, Codex or none;
 * without one a memo is transcribed only and the user writes the to-dos — never a block on recording.
 */
@OptIn(ExperimentalTestApi::class)
class MemoOrganizerUiTest {

    private fun sentences(a: String, b: String) = str(Res.string.memo_two_sentences, a, b)

    // ── settings (O1–O3, O16) ───────────────────────────────────────────────────────────────────────

    @Test
    fun settingsNameTheOrganizerInEachOfItsThreeStates() {
        var readiness by mutableStateOf(MemoFx.readiness(organizer = "codex", defaultAgent = "codex"))
        memoScene(height = 1600, content = { MemoExperimentalSection(readiness, onToggle = {}) }) {
            // O1: the default agent has an adapter
            assertTrue(has(str(Res.string.memo_exp_organize_value, "Codex")))
            assertFalse(has(str(Res.string.memo_exp_organize_fallback_sub, "Codex", "Codex")))
            assertTrue(has(str(Res.string.memo_exp_data_body)), "the v3.1 data path")
            assertTrue(has(str(Res.string.memo_exp_no_model_choice, "Codex")), "the footnote names the organiser")

            // O2: the default has none, another advertised agent is used — and the screen says so
            readiness = MemoFx.readiness(organizer = "claude", defaultAgent = "codex")
            waitForIdle()
            assertTrue(has(str(Res.string.memo_exp_organize_value, "Claude")))
            assertTrue(has(str(Res.string.memo_exp_organize_fallback_sub, "Codex", "Claude")))

            // O3: none — transcription only, and it blocks nothing
            readiness = MemoFx.readiness(organizer = null, defaultAgent = "codex")
            waitForIdle()
            assertTrue(has(str(Res.string.memo_exp_organize_none)))
            assertTrue(has(str(Res.string.memo_exp_organize_none_sub)))
            assertTrue(has(str(Res.string.memo_exp_no_organizer_note)))
            assertFalse(has(str(Res.string.memo_exp_next, ""), substring = true), "no readiness problem: recording is not blocked")
            assertFalse(allScreenText().any { it.contains('%') })

            // with the computer unreadable, "none" would be a guess: both rows say it cannot be checked
            readiness = MemoFx.readiness(MemoBlock.OFFLINE, online = false, organizer = null)
            waitForIdle()
            assertEquals(2, count(str(Res.string.memo_exp_transcribe_unchecked)))
            assertFalse(has(str(Res.string.memo_exp_organize_none)))
        }
    }

    @Test
    fun atThreeTwentyAndTwoHundredPercentTheOrganizerNoteWrapsInsteadOfOverflowing() {
        val readiness = MemoFx.readiness(organizer = null)
        memoScene(width = SMALL_W, height = 2400, fontScale = 2f, content = { MemoExperimentalSection(readiness, onToggle = {}) }) {
            assertInViewport(onAllNodes(androidx.compose.ui.test.hasText(str(Res.string.memo_exp_organize_none_sub))).onFirst(), "the organiser note", SMALL_W, 2400)
        }
    }

    @Test
    fun withoutAnOrganizerANewMemoCanStillBeRecorded() {
        val state = MemoUiState(readiness = MemoFx.readiness(organizer = null), screen = MemoScreen.LIST, list = MemoListState(loaded = true))
        val actions = mutableListOf<MemoAction>()
        memoScene(content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            buttons(str(Res.string.memo_new)).onFirst().assertIsEnabled()
            buttons(str(Res.string.memo_new)).onFirst().tap()
            waitForIdle()
            assertEquals(listOf<MemoAction>(MemoAction.NewMemo), actions)
        }
    }

    // ── consent (O4) and recording ──────────────────────────────────────────────────────────────────

    @Test
    fun theFirstUseSheetSaysWhoOrganisesOrThatNobodyDoes() {
        var state by mutableStateOf(
            MemoUiState(
                readiness = MemoFx.readiness(organizer = null), screen = MemoScreen.LIST,
                list = MemoListState(loaded = true), capture = MemoCaptureState(phase = MemoCapturePhase.CONSENT),
            ),
        )
        memoScene(content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            assertTrue(has(str(Res.string.memo_consent_2)), "the v3.1 wording of the second data path")
            assertTrue(has(str(Res.string.memo_consent_note_none)))
            state = state.copy(readiness = MemoFx.readiness(organizer = "codex"))
            waitForIdle()
            assertTrue(has(str(Res.string.memo_consent_note, "Codex")))
            assertFalse(has(str(Res.string.memo_consent_note_none)))
        }
    }

    @Test
    fun withoutAnOrganizerRecordingFinishesIntoTranscription() {
        var state by mutableStateOf(
            MemoUiState(
                readiness = MemoFx.readiness(organizer = null), screen = MemoScreen.RECORDING,
                capture = MemoCaptureState(phase = MemoCapturePhase.RECORDING, elapsedMs = 168_000, secondsLeft = 12),
            ),
        )
        memoScene(content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            assertEquals(1, buttonCount(str(Res.string.memo_rec_finish_transcribe)))
            assertEquals(0, buttonCount(str(Res.string.memo_rec_finish)), "no promise of an organise step that will be skipped")
            assertTrue(has(str(Res.string.memo_rec_seconds_left_transcribe, 12, "3:00")))
            assertFalse(has(str(Res.string.memo_rec_seconds_left, 12, "3:00")))

            state = state.copy(capture = state.capture.copy(leavePrompt = true))
            waitForIdle()
            assertEquals(2, buttonCount(str(Res.string.memo_rec_finish_transcribe)), "the leave sheet says the same")

            state = state.copy(readiness = MemoFx.readiness(organizer = "codex"), capture = state.capture.copy(leavePrompt = false))
            waitForIdle()
            assertEquals(1, buttonCount(str(Res.string.memo_rec_finish)))
            assertTrue(has(str(Res.string.memo_rec_seconds_left, 12, "3:00")))
        }
    }

    // ── processing (O5, O6, O11) ────────────────────────────────────────────────────────────────────

    private fun proc(organize: MemoStepStatus, organizer: String?, lost: Boolean = false, reachedLimit: Boolean = false) =
        MemoProcessingState(
            memoId = "m1", audioDurationMs = 75_000,
            steps = MemoFx.steps(MemoStepStatus.DONE, MemoStepStatus.DONE, organize),
            elapsedMs = 9_600, reachedLimit = reachedLimit, hasTranscript = true, hasAudio = true,
            organizer = organizer, organizerLost = lost,
        )

    @Test
    fun theOrganiseStepIsNamedOrSkippedWithItsReason() {
        var state by mutableStateOf(MemoFx.processing(proc(MemoStepStatus.SKIPPED, organizer = null, reachedLimit = true)).copy(readiness = MemoFx.readiness(organizer = null)))
        memoScene(content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            // O5: no organiser
            assertTrue(has(str(Res.string.memo_step_skipped_none)))
            assertTrue(has(str(Res.string.memo_step_skipped_detail)))
            assertTrue(has(str(Res.string.memo_proc_limit_note_transcribe)), "3:00 hands over to transcription")
            assertFalse(has(str(Res.string.memo_proc_limit_note)))

            // O6: the organiser was gone by the time the computer got to it
            state = MemoFx.processing(proc(MemoStepStatus.SKIPPED, organizer = "codex", lost = true))
            waitForIdle()
            assertTrue(has(str(Res.string.memo_step_skipped_lost, "Codex")))
            assertFalse(has(str(Res.string.memo_step_skipped_none)))

            // with an organiser the step says which one runs
            state = MemoFx.processing(proc(MemoStepStatus.RUNNING, organizer = "codex"))
            waitForIdle()
            assertTrue(has(str(Res.string.memo_step_organize_detail, "Codex")))
            assertFalse(has(str(Res.string.memo_step_skipped_detail)))
        }
    }

    @Test
    fun anOrganiseOnlyRunReadsOrganisingAndCancelsOrganising() {
        val doc = MemoFx.rawDoc(organizerAvailable = "codex").copy(reorganizing = true)
        var state by mutableStateOf(MemoFx.processing(proc(MemoStepStatus.RUNNING, organizer = "codex")).copy(document = doc))
        val actions = mutableListOf<MemoAction>()
        memoScene(content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            assertTrue(has(str(Res.string.memo_proc_title_organizing)))
            assertFalse(has(str(Res.string.memo_proc_title_running)))
            assertEquals(0, buttonCount(str(Res.string.memo_proc_cancel)))
            buttons(str(Res.string.memo_proc_cancel_organize)).onFirst().tap()
            waitForIdle()
            assertEquals(listOf<MemoAction>(MemoAction.CancelProcessing), actions)

            // a full processing run of the same memo keeps its own words
            state = state.copy(document = null)
            waitForIdle()
            assertTrue(has(str(Res.string.memo_proc_title_running)) && buttonCount(str(Res.string.memo_proc_cancel)) == 1)
        }
    }

    // ── the unorganised result (O7–O10, O15) ────────────────────────────────────────────────────────

    @Test
    fun anUnorganisedMemoWithoutAnOrganiserIsSplitByHandOrSentWhole() {
        val actions = mutableListOf<MemoAction>()
        val state = MemoFx.rawDetail(MemoFx.rawDoc(organizerAvailable = null))
        memoScene(height = 1400, content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            assertTrue(has(str(Res.string.memo_unorganized_title)))
            assertTrue(
                has(sentences(str(Res.string.memo_unorganized_body), str(Res.string.memo_unorganized_install))),
                "no organiser: the note says what would make organising possible",
            )
            assertTrue(has(MemoFx.RAW_TRANSCRIPT), "the transcript is open by default")
            assertTrue(has(str(Res.string.memo_no_todos_raw)))
            assertTrue(has("嗯，出门前想到三件事"), "the title field shows the transcript's first sentence as its placeholder")
            assertEquals(0, buttonCount(str(Res.string.memo_organize_with, "Claude")) + buttonCount(str(Res.string.memo_organize_with, "Codex")))
            assertFalse(has(str(Res.string.memo_summary).uppercase()), "no summary block")

            buttons(str(Res.string.memo_whole_as_todo)).onFirst().assertIsEnabled()
            buttons(str(Res.string.memo_whole_as_todo)).onFirst().tap()
            waitForIdle()
            assertEquals(listOf<MemoAction>(MemoAction.AddWholeTranscriptTodo), actions)

            tapText(str(Res.string.memo_timings))
            assertTrue(has(str(Res.string.memo_timing_skipped)), "the organise timing reads skipped, not unmeasured")
            assertFalse(allScreenText().any { it.contains('%') })
        }
    }

    @Test
    fun theOrganiserLostOnTheWayIsSaidFirst() {
        val state = MemoFx.rawDetail(MemoFx.rawDoc(organizerAvailable = null, organizerLost = true))
        memoScene(content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            val body = sentences(
                sentences(str(Res.string.memo_unorganized_lost), str(Res.string.memo_unorganized_body)),
                str(Res.string.memo_unorganized_install),
            )
            assertTrue(has(body), "lost + the manual way + what would enable organising")
        }
    }

    @Test
    fun aTranscriptTooLongForOneItemDisablesWholeAndSaysWhy() {
        val actions = mutableListOf<MemoAction>()
        var state by mutableStateOf(MemoFx.rawDetail(MemoFx.rawDoc(wholeTranscriptFits = false)))
        memoScene(height = 1400, content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            val whole = buttons(str(Res.string.memo_whole_as_todo)).onFirst()
            whole.assertIsNotEnabled()
            assertTrue(has(str(Res.string.memo_whole_too_long)), "the reason is written under the actions")
            assertEquals(
                1,
                onAllNodes(hasContentDescription(str(Res.string.memo_unavailable_cd, str(Res.string.memo_whole_as_todo), str(Res.string.memo_whole_too_long)))).fetchSemanticsNodes().size,
                "…and spoken on the control",
            )
            whole.performClick()
            waitForIdle()
            assertTrue(actions.none { it == MemoAction.AddWholeTranscriptTodo })

            // once anything is written, "whole as one item" is no longer offered
            state = MemoFx.rawDetail(MemoFx.rawDoc(todos = listOf(MemoFx.todo("t1", "先看构建日志"))))
            waitForIdle()
            assertEquals(0, buttonCount(str(Res.string.memo_whole_as_todo)))
        }
    }

    @Test
    fun anOrganiserThatAppearsLaterCanOrganiseAndOnlyAsksWhenThereAreDrafts() {
        val actions = mutableListOf<MemoAction>()
        var state by mutableStateOf(MemoFx.rawDetail(MemoFx.rawDoc(organizerAvailable = "codex")))
        memoScene(height = 1400, content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            val organize = str(Res.string.memo_organize_with, "Codex")
            // O8: the note loses the "install" sentence and gains the action; the to-do row carries it too
            assertTrue(has(str(Res.string.memo_unorganized_body)))
            assertFalse(has(str(Res.string.memo_unorganized_install), substring = true))
            assertEquals(2, buttonCount(organize), "in the note and in the to-do actions")
            buttons(organize).onFirst().tap()
            waitForIdle()
            assertEquals(listOf<MemoAction>(MemoAction.Reorganize), actions, "nothing to replace: no confirmation")
            assertFalse(has(str(Res.string.memo_organize_confirm_title)))

            // hand-written drafts would be replaced: the existing confirmation comes first
            state = MemoFx.rawDetail(MemoFx.rawDoc(organizerAvailable = "codex", todos = listOf(MemoFx.todo("t1", "先看构建日志"))))
            waitForIdle()
            buttons(organize).onFirst().tap()
            waitForIdle()
            assertEquals(1, actions.count { it == MemoAction.Reorganize }, "the tap only asks")
            assertTrue(has(str(Res.string.memo_organize_confirm_title)) && has(str(Res.string.memo_reorg_confirm_body)))
            buttons(organize).onLast().tap()
            waitForIdle()
            assertEquals(2, actions.count { it == MemoAction.Reorganize })

            // after a send, organising would bring the delivered items back as drafts: not offered (prototype)
            state = MemoFx.rawDetail(MemoFx.rawDoc(organizerAvailable = "codex", todos = listOf(MemoFx.todo("t1", "先看构建日志", MemoTodoState.DELIVERED))))
            waitForIdle()
            assertEquals(0, buttonCount(organize))
        }
    }

    @Test
    fun atThreeTwentyAndTwoHundredPercentTheUnorganisedActionsStayReachable() {
        val state = MemoFx.rawDetail(MemoFx.rawDoc(organizerAvailable = "codex"))
        memoScene(width = SMALL_W, height = SMALL_H, fontScale = 2f, content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            for (label in listOf(str(Res.string.memo_whole_as_todo), str(Res.string.memo_organize_with, "Codex"))) {
                val node = buttons(label).onLast()
                node.performScrollTo()
                waitForIdle()
                assertInViewport(node, label, SMALL_W, SMALL_H)
            }
        }
    }

    // ── organised memos (O12, O13) ──────────────────────────────────────────────────────────────────

    @Test
    fun anOrganisedMemoSaysWhoOrganisedIt() {
        var state by mutableStateOf(MemoFx.detail())
        memoScene(content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            assertTrue(has(str(Res.string.memo_organized_by, "Claude")))
            state = state.copy(document = state.document!!.copy(organizedBy = "codex"))
            waitForIdle()
            assertTrue(has(str(Res.string.memo_organized_by, "Codex")))
            assertFalse(has(str(Res.string.memo_unorganized_title)), "an organised memo is not 'unorganised'")
        }
    }

    @Test
    fun aDegradedMemoWithoutAnOrganiserOffersOnlyTheManualWay() {
        val state = MemoFx.detail(todos = emptyList(), selection = MemoSelectionState()).copy(
            document = MemoFx.doc(todos = emptyList(), degraded = true, summary = "只整理出一段说明。", organizerAvailable = null),
            readiness = MemoFx.readiness(organizer = null),
        )
        memoScene(content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            assertTrue(has(str(Res.string.memo_degraded_title)))
            assertTrue(has(sentences(str(Res.string.memo_degraded_body), str(Res.string.memo_degraded_next_manual))))
            assertEquals(0, buttonCount(str(Res.string.memo_reorganize_with, "Claude")), "nothing to re-organise with")
            assertEquals(1, buttonCount(str(Res.string.memo_add_todo)), "the manual way stays")
        }
    }

    // ── confirm (O14, O17) ──────────────────────────────────────────────────────────────────────────

    @Test
    fun theConfirmSheetMarksAWholeTranscriptItem() {
        val whole = MemoFx.todo("t1", MemoFx.RAW_TRANSCRIPT).copy(wholeTranscript = true)
        var state by mutableStateOf(MemoFx.detail(todos = listOf(whole)).copy(document = MemoFx.rawDoc(todos = listOf(whole))))
        memoScene(content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            buttons(str(Res.string.memo_dispatch_n, 1)).onFirst().tap()
            waitForIdle()
            assertTrue(has(str(Res.string.memo_whole_transcript_note)))
            assertFalse(has(str(Res.string.memo_multi_notice)), "N = 1: no same-turn notice")
            buttons(str(Res.string.memo_back_edit)).onFirst().tap()
            waitForIdle()

            val plain = MemoFx.todo("t1", "先看构建日志")
            state = MemoFx.detail(todos = listOf(plain))
            waitForIdle()
            buttons(str(Res.string.memo_dispatch_n, 1)).onFirst().tap()
            waitForIdle()
            assertFalse(has(str(Res.string.memo_whole_transcript_note)), "an ordinary item carries no note")
        }
    }

    @Test
    fun atThreeTwentyAndTwoHundredPercentTheWholeTranscriptConfirmStaysReadable() {
        val whole = MemoFx.todo("t1", MemoFx.RAW_TRANSCRIPT).copy(wholeTranscript = true)
        val state = MemoFx.detail(todos = listOf(whole)).copy(document = MemoFx.rawDoc(todos = listOf(whole)))
        memoScene(width = SMALL_W, height = SMALL_H, fontScale = 2f, content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            val label = str(Res.string.memo_dispatch_n, 1)
            buttons(label).onFirst().tap()
            waitForIdle()
            assertInViewport(buttons(label).onLast(), "the confirm button", SMALL_W, SMALL_H)
            val note = onAllNodes(androidx.compose.ui.test.hasText(str(Res.string.memo_whole_transcript_note))).onFirst()
            note.performScrollTo()
            waitForIdle()
            assertInViewport(note, "the whole-transcript note", SMALL_W, SMALL_H)
        }
    }
}
