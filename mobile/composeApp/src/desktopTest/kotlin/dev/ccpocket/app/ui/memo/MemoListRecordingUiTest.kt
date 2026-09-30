package dev.ccpocket.app.ui.memo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.memo.MemoAction
import dev.ccpocket.app.memo.MemoBlock
import dev.ccpocket.app.memo.MemoCaptureState
import dev.ccpocket.app.memo.MemoCapturePhase
import dev.ccpocket.app.memo.MemoHeader
import dev.ccpocket.app.memo.MemoListState
import dev.ccpocket.app.memo.MemoScreen
import dev.ccpocket.app.memo.MemoToast
import dev.ccpocket.app.memo.MemoUiState
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.str
import dev.ccpocket.protocol.VoiceMemoError
import dev.ccpocket.protocol.VoiceMemoStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The memo list, the first-use sheet and the recording page. */
@OptIn(ExperimentalTestApi::class)
class MemoListRecordingUiTest {

    private val now = epochMillis()
    private fun header(id: String, title: String, stage: String? = null, error: String? = null, drafts: Int = 0, delivered: Int = 0, unknown: Int = 0, failed: Int = 0) =
        MemoHeader(id, title, now - 3_600_000, now, stage = stage, errorCode = error, drafts = drafts, delivered = delivered, unknown = unknown, failed = failed)

    private fun list(rows: List<MemoHeader>, unreadable: Boolean = false, hasMore: Boolean = false, block: MemoBlock = MemoBlock.NONE) =
        MemoUiState(readiness = MemoFx.readiness(block), screen = MemoScreen.LIST, list = MemoListState(loaded = true, unreadable = unreadable, rows = rows, hasMore = hasMore))

    @Test
    fun rowsSpellTheirStatusOutAndAnUnreadableLibraryIsNeverShownAsEmpty() {
        var state by mutableStateOf(
            list(
                listOf(
                    header("a", "周会前要确认的点", delivered = 2),
                    header("b", "给发布说明补的内容", drafts = 1),
                    header("c", "出门想到的三件事", delivered = 1, unknown = 1, drafts = 1),
                    header("d", "还在处理的备忘", stage = VoiceMemoStage.TRANSCRIBING),
                    header("e", "没整理出来的备忘", stage = VoiceMemoStage.FAILED, error = VoiceMemoError.SUMMARY_FAILED),
                ),
            ),
        )
        memoScene(content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            assertTrue(has(str(Res.string.memo_title)) && has(str(Res.string.memo_list_subtitle)))
            assertTrue(has(str(Res.string.memo_count_delivered, 2)))
            assertTrue(has(str(Res.string.memo_count_drafts, 1)))
            assertTrue(has(listOf(str(Res.string.memo_count_delivered, 1), str(Res.string.memo_count_unknown, 1), str(Res.string.memo_count_drafts, 1)).joinToString(" · ")))
            assertTrue(has(str(Res.string.memo_status_processing_step, str(Res.string.memo_step_transcribe))))
            assertTrue(has(str(Res.string.memo_status_organize_failed)))
            assertFalse(has(VoiceMemoError.SUMMARY_FAILED, substring = true), "never the raw error code")

            state = list(emptyList())
            waitForIdle()
            assertTrue(has(str(Res.string.memo_list_empty_title)))

            state = list(emptyList(), unreadable = true)
            waitForIdle()
            assertTrue(has(str(Res.string.memo_list_unreadable_title)), "a read failure is an error…")
            assertFalse(has(str(Res.string.memo_list_empty_title)), "…never the empty state")
        }
    }

    @Test
    fun newMemoIsDisabledWithAWrittenReasonForEveryBlock() {
        var state by mutableStateOf(list(emptyList()))
        val actions = mutableListOf<MemoAction>()
        memoScene(content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            val newMemo = str(Res.string.memo_new)
            buttons(newMemo).onFirst().assertIsEnabled()
            buttons(newMemo).onFirst().tap()
            waitForIdle()
            assertEquals(listOf<MemoAction>(MemoAction.NewMemo), actions)

            val expected = mapOf(
                MemoBlock.FEATURE_OFF to str(Res.string.memo_new_blocked_feature_off),
                MemoBlock.NOT_OWNER to str(Res.string.memo_new_blocked_not_owner, "alex-mac"),
                MemoBlock.OFFLINE to str(Res.string.memo_new_blocked_offline, "alex-mac"),
                MemoBlock.COMPUTER_OUTDATED to str(Res.string.memo_new_blocked_outdated, "alex-mac"),
                MemoBlock.WHISPER_MISSING to str(Res.string.memo_new_blocked_whisper, "alex-mac"),
                MemoBlock.MODEL_MISSING to str(Res.string.memo_new_blocked_model, "alex-mac"),
                MemoBlock.CONVERTER_MISSING to str(Res.string.memo_new_blocked_converter, "alex-mac"),
                MemoBlock.UNSUPPORTED_PLATFORM to str(Res.string.memo_new_blocked_platform, "alex-mac"),
                MemoBlock.NOT_ENCRYPTED to str(Res.string.memo_new_blocked_encryption),
                MemoBlock.UNKNOWN to str(Res.string.memo_new_blocked_unknown),
                MemoBlock.LIBRARY_FULL to str(Res.string.memo_new_blocked_full, 100),
            )
            assertEquals((MemoBlock.entries - MemoBlock.NONE).toSet(), expected.keys, "every block has its own sentence")
            assertEquals(expected.size, expected.values.toSet().size, "…and no two blocks share one")
            for ((block, reason) in expected) {
                state = list(emptyList(), block = block)
                waitForIdle()
                buttons(newMemo).onFirst().assertIsNotEnabled()
                buttons(newMemo).onFirst().performClick()
                waitForIdle()
                assertTrue(has(reason), "$block: \"$reason\"")
            }
            assertEquals(listOf<MemoAction>(MemoAction.NewMemo), actions, "a disabled button emits nothing")
        }
    }

    @Test
    fun deletingAMemoNeedsTheConfirmationSheet() {
        val actions = mutableListOf<MemoAction>()
        val state = list(listOf(header("a", "周会前要确认的点", delivered = 2)))
        memoScene(content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            tapDescription(str(Res.string.memo_delete_cd, "周会前要确认的点"))
            assertTrue(actions.isEmpty(), "the row's delete only asks")
            assertTrue(has(str(Res.string.memo_delete_title, "周会前要确认的点")))
            assertTrue(has(str(Res.string.memo_delete_body)), "…and says dispatched tasks are not recalled")
            buttons(str(Res.string.memo_cancel)).onFirst().tap()
            waitForIdle()
            assertTrue(actions.isEmpty())
            assertFalse(has(str(Res.string.memo_delete_body)))

            tapDescription(str(Res.string.memo_delete_cd, "周会前要确认的点"))
            buttons(str(Res.string.memo_delete)).onLast().tap()
            waitForIdle()
            assertEquals(listOf<MemoAction>(MemoAction.DeleteMemo("a")), actions)
        }
    }

    @Test
    fun theListAsksForMoreOnlyWhenThereIsMoreAndRowsOpen() {
        val actions = mutableListOf<MemoAction>()
        var state by mutableStateOf(list(listOf(header("a", "周会前要确认的点", delivered = 2))))
        memoScene(content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            assertTrue(actions.none { it == MemoAction.LoadMore })
            state = list(listOf(header("a", "周会前要确认的点", delivered = 2)), hasMore = true)
            waitForIdle()
            assertEquals(1, actions.count { it == MemoAction.LoadMore })
            tapText("周会前要确认的点")
            assertEquals(MemoAction.OpenMemo("a"), actions.last())
        }
    }

    @Test
    fun theFirstUseSheetListsTheDataPathsAndStartsOnlyOnConsent() {
        val actions = mutableListOf<MemoAction>()
        val state = list(emptyList()).copy(capture = MemoCaptureState(phase = MemoCapturePhase.CONSENT))
        memoScene(content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            for (res in listOf(Res.string.memo_consent_1, Res.string.memo_consent_2, Res.string.memo_consent_3)) {
                assertTrue(has(str(res)), str(res))
            }
            // the fixture's readiness has Claude as organiser: the note names it
            assertTrue(has(str(Res.string.memo_consent_note, "Claude")))
            buttons(str(Res.string.memo_consent_decline)).onFirst().tap()
            buttons(str(Res.string.memo_consent_accept)).onFirst().tap()
            waitForIdle()
            assertEquals(listOf<MemoAction>(MemoAction.DeclineConsent, MemoAction.AcceptConsent), actions)
        }
    }

    private fun recording(phase: MemoCapturePhase, elapsed: Long = 47_000, left: Int? = null, prompt: Boolean = false) =
        MemoUiState(
            readiness = MemoFx.readiness(), screen = MemoScreen.RECORDING,
            capture = MemoCaptureState(phase = phase, elapsedMs = elapsed, secondsLeft = left, leavePrompt = prompt),
        )

    @Test
    fun recordingShowsTheRealTimerTheLimitAndTheLastSecondsWarning() {
        var state by mutableStateOf(recording(MemoCapturePhase.RECORDING))
        val actions = mutableListOf<MemoAction>()
        memoScene(content = { VoiceMemoScreen(state, 0.5f, { actions += it }, {}) }) {
            assertTrue(has("0:47"))
            assertTrue(has(str(Res.string.memo_rec_limit, "3:00")))
            val warning = str(Res.string.memo_rec_seconds_left, 12, "3:00")
            assertFalse(has(warning))
            state = recording(MemoCapturePhase.RECORDING, elapsed = 168_000, left = 12)
            waitForIdle()
            assertTrue(has(warning), "from 2:45 the seconds left are written")
            assertTrue(
                onAllNodes(androidx.compose.ui.test.hasContentDescription(str(Res.string.memo_rec_level_mid))).fetchSemanticsNodes().isNotEmpty(),
                "the meter is described for screen readers",
            )

            buttons(str(Res.string.memo_rec_finish)).onFirst().tap()
            buttons(str(Res.string.memo_rec_discard)).onFirst().tap()
            tapDescription(str(Res.string.memo_back_cd))
            assertEquals(listOf<MemoAction>(MemoAction.StopRecording, MemoAction.DiscardRecording, MemoAction.RequestLeaveRecording), actions)
        }
    }

    @Test
    fun backWhileRecordingOffersContinueFinishOrDiscard() {
        val actions = mutableListOf<MemoAction>()
        val state = recording(MemoCapturePhase.RECORDING, prompt = true)
        memoScene(content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            assertTrue(has(str(Res.string.memo_leave_title)))
            assertTrue(has(str(Res.string.memo_leave_body, "0:47")))
            buttons(str(Res.string.memo_leave_continue)).onFirst().tap()
            buttons(str(Res.string.memo_rec_finish)).onLast().tap() // the sheet's, above the page's own
            buttons(str(Res.string.memo_leave_discard)).onFirst().tap()
            waitForIdle()
            assertEquals(listOf<MemoAction>(MemoAction.ContinueRecording, MemoAction.StopRecording, MemoAction.DiscardRecording), actions)
        }
    }

    @Test
    fun everyCaptureProblemIsWrittenWithItsOwnWayOut() {
        var state by mutableStateOf(recording(MemoCapturePhase.PERMISSION_DENIED))
        val actions = mutableListOf<MemoAction>()
        memoScene(content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            assertTrue(has(str(Res.string.memo_rec_denied_title)))
            buttons(str(Res.string.memo_rec_open_settings)).onFirst().tap()
            buttons(str(Res.string.memo_rec_retry)).onFirst().tap()
            buttons(str(Res.string.memo_back_to_list)).onFirst().tap()
            waitForIdle()
            assertEquals(listOf<MemoAction>(MemoAction.OpenMicSettings, MemoAction.RetryRecording, MemoAction.DiscardRecording), actions)
            assertFalse(has(str(Res.string.memo_rec_finish)), "no pretend recording state")

            state = recording(MemoCapturePhase.INTERRUPTED)
            waitForIdle()
            assertTrue(has(str(Res.string.memo_rec_interrupted_title)) && has(str(Res.string.memo_rec_interrupted_body)))
            assertTrue(buttonCount(str(Res.string.memo_rec_rerecord)) == 1 && buttonCount(str(Res.string.memo_back_to_list)) == 1)
            assertFalse(has(str(Res.string.memo_rec_finish)), "an interrupted fragment cannot be organized")

            for ((phase, title) in listOf(
                MemoCapturePhase.MIC_BUSY to Res.string.memo_rec_busy_title,
                MemoCapturePhase.SAVE_FAILED to Res.string.memo_rec_save_failed_title,
                MemoCapturePhase.START_FAILED to Res.string.memo_rec_start_failed_title,
                MemoCapturePhase.PREPARING to Res.string.memo_rec_preparing,
                MemoCapturePhase.SAVING to Res.string.memo_rec_saving,
            )) {
                state = recording(phase)
                waitForIdle()
                assertTrue(has(str(title)), "$phase")
                assertFalse(has("0:47"), "$phase: the timer is only shown while really recording")
            }
            assertEquals(0, onAllNodes(androidx.compose.ui.test.hasContentDescription(str(Res.string.memo_back_cd))).fetchSemanticsNodes().size, "no way back while the file is being written")
        }
    }

    @Test
    fun aToastIsShownAndAcknowledged() {
        val actions = mutableListOf<MemoAction>()
        val state = list(emptyList()).copy(toast = MemoToast.COPIED)
        memoScene(autoAdvance = false, content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            assertTrue(has(str(Res.string.memo_toast_copied)))
            assertTrue(actions.isEmpty(), "shown first")
            mainClock.advanceTimeBy(MEMO_TOAST_MS + 100)
            waitForIdle()
            assertEquals(listOf<MemoAction>(MemoAction.ToastShown), actions)
        }
    }

    @Test
    fun theBackTableNeverLeavesALiveRecordingSilently() {
        assertEquals(MemoAction.RequestLeaveRecording, recordingBackAction(MemoCapturePhase.RECORDING))
        assertEquals(null, recordingBackAction(MemoCapturePhase.SAVING))
        assertEquals(MemoAction.DiscardRecording, recordingBackAction(MemoCapturePhase.PERMISSION_DENIED))
    }
}
