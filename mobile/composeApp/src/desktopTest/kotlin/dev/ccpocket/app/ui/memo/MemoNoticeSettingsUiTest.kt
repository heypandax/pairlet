package dev.ccpocket.app.ui.memo

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onFirst
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.memo.MemoBlock
import dev.ccpocket.app.memo.MemoDispatchPhase
import dev.ccpocket.app.memo.MemoDispatchState
import dev.ccpocket.app.memo.MemoDispatchStop
import dev.ccpocket.app.memo.MemoReadiness
import dev.ccpocket.app.memo.MemoTodoState
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.str
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The target chat's status strip, the delivery mark under a memo bubble, and the Experimental settings block. */
@OptIn(ExperimentalTestApi::class)
class MemoNoticeSettingsUiTest {

    private fun batch(phase: MemoDispatchPhase, total: Int = 3, current: Int = 0, delivered: Int = 0, unknown: Int = 0, failed: Int = 0, stop: MemoDispatchStop = MemoDispatchStop.NONE) =
        MemoDispatchState(
            batchId = "b1", memoId = "m1", memoTitle = "出门想到的三件事", target = MemoFx.target, convoId = "c1",
            phase = phase, stop = stop, total = total, current = current, delivered = delivered, unknown = unknown, failed = failed,
        )

    @Test
    fun theKeyPartialCaseReadsDeliveredToCheckAndNotSentInChinese() {
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE)
        try {
            memoScene(content = {
                MemoDispatchNotice(batch(MemoDispatchPhase.STOPPED, delivered = 1, unknown = 1, stop = MemoDispatchStop.RECEIPT_MISSING), onReturnToMemo = {})
            }) {
                assertPresent("1 项已送达 · 1 项待核对 · 1 项未发送")
                assertPresent("语音备忘 · 出门想到的三件事")
                assertPresent("返回备忘")
            }
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun theStripSummarisesEachPhaseFromCountsAndLeadsBackToTheMemo() {
        var state by mutableStateOf(batch(MemoDispatchPhase.STOPPED, delivered = 1, unknown = 1, stop = MemoDispatchStop.RECEIPT_MISSING))
        var returned = 0
        memoScene(content = { MemoDispatchNotice(state, onReturnToMemo = { returned++ }) }) {
            assertTrue(has(listOf(str(Res.string.memo_count_delivered, 1), str(Res.string.memo_count_unknown, 1), str(Res.string.memo_count_unsent, 1)).joinToString(" · ")))
            assertTrue(has(str(Res.string.memo_notice_stop_receipt)))

            state = batch(MemoDispatchPhase.STOPPED, failed = 1, stop = MemoDispatchStop.NOT_SENT)
            waitForIdle()
            assertTrue(has(listOf(str(Res.string.memo_count_failed, 1), str(Res.string.memo_count_unsent, 2)).joinToString(" · ")), "zero segments are left out")

            state = batch(MemoDispatchPhase.SENDING, current = 2, delivered = 1)
            waitForIdle()
            assertTrue(has(str(Res.string.memo_notice_sending, 2, 3)) && has(str(Res.string.memo_notice_sending_sub)))

            state = batch(MemoDispatchPhase.OPENING)
            waitForIdle()
            assertTrue(has(str(Res.string.memo_notice_opening)))

            state = batch(MemoDispatchPhase.DONE, delivered = 3)
            waitForIdle()
            assertTrue(has(str(Res.string.memo_count_delivered, 3)) && has(str(Res.string.memo_notice_done_sub, "Claude")))

            state = batch(MemoDispatchPhase.OPEN_FAILED)
            waitForIdle()
            assertTrue(has(str(Res.string.memo_notice_open_failed)))

            state = batch(MemoDispatchPhase.STOPPED, delivered = 1, stop = MemoDispatchStop.LEFT_CHAT)
            waitForIdle()
            assertTrue(has(str(Res.string.memo_notice_stop_left)))
            assertFalse(allScreenText().any { it.contains('%') })

            buttons(str(Res.string.memo_return)).onFirst().tap()
            waitForIdle()
            assertEquals(1, returned)
        }
    }

    @Test
    fun atLargeTypeTheStripShrinksToTheSummaryAndTheWayBack() {
        val state = batch(MemoDispatchPhase.STOPPED, delivered = 1, unknown = 1, stop = MemoDispatchStop.RECEIPT_MISSING)
        memoScene(width = SMALL_W, height = SMALL_H, fontScale = 2f, content = { MemoDispatchNotice(state, onReturnToMemo = {}) }) {
            assertTrue(has(memoCounts(1, 1, 1)))
            assertFalse(has(str(Res.string.memo_notice_stop_receipt)), "the explanation drops out at 200 %")
            assertInViewport(buttons(str(Res.string.memo_return)).onFirst(), "返回备忘", SMALL_W, SMALL_H)
        }
    }

    private fun memoCounts(d: Int, u: Int, n: Int) =
        listOf(str(Res.string.memo_count_delivered, d), str(Res.string.memo_count_unknown, u), str(Res.string.memo_count_unsent, n)).joinToString(" · ")

    @Test
    fun theDeliveryMarkWritesEveryState() {
        memoScene(content = {
            Column {
                listOf(MemoTodoState.DRAFT, MemoTodoState.SENDING, MemoTodoState.DELIVERED, MemoTodoState.UNKNOWN, MemoTodoState.FAILED).forEach {
                    MemoDeliveryMark(it)
                }
                MemoDeliveryMark(MemoTodoState.UNKNOWN, detail = "未收到接收确认")
            }
        }) {
            for (res in listOf(Res.string.memo_state_draft, Res.string.memo_state_sending, Res.string.memo_state_delivered, Res.string.memo_state_unknown, Res.string.memo_state_failed)) {
                assertTrue(has(str(res), substring = true), str(res))
            }
            assertTrue(has("未收到接收确认", substring = true))
        }
    }

    @Test
    fun theExperimentalSwitchIsOffByDefaultAndOnShowsReadinessProblemAndDataPath() {
        var readiness by mutableStateOf(MemoReadiness())
        val toggles = mutableListOf<Boolean>()
        val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        memoScene(height = 1400, content = { MemoExperimentalSection(readiness, onToggle = { toggles += it }) }) {
            assertTrue(has(str(Res.string.memo_exp_toggle)))
            assertEquals(ToggleableState.Off, onAllNodes(switch).onFirst().fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
            assertTrue(has(str(Res.string.memo_exp_off_note)))
            assertFalse(has(str(Res.string.memo_exp_readiness).uppercase()))
            onAllNodes(switch).onFirst().tap()
            waitForIdle()
            assertEquals(listOf(true), toggles)

            readiness = MemoFx.readiness(MemoBlock.WHISPER_MISSING)
            waitForIdle()
            assertEquals(ToggleableState.On, onAllNodes(switch).onFirst().fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
            assertTrue(has(str(Res.string.memo_exp_online, "alex-mac")))
            assertTrue(has(str(Res.string.memo_exp_transcribe_whisper_missing)))
            assertTrue(has(str(Res.string.memo_exp_organize_value, "Claude")))
            assertTrue(has(str(Res.string.memo_rd_whisper_title)))
            assertTrue(has(str(Res.string.memo_exp_next, str(Res.string.memo_rd_whisper_next))), "the next step is written")
            assertTrue(has(str(Res.string.memo_exp_data_body)))
            assertTrue(has(str(Res.string.memo_exp_no_model_choice, "Claude")))

            readiness = MemoFx.readiness(MemoBlock.OFFLINE, online = false)
            waitForIdle()
            assertTrue(has(str(Res.string.memo_exp_offline, "alex-mac")) && has(str(Res.string.memo_rd_offline_title, "alex-mac")))

            readiness = MemoFx.readiness(MemoBlock.NONE)
            waitForIdle()
            assertTrue(has(str(Res.string.memo_exp_transcribe_ready)))
            assertFalse(has(str(Res.string.memo_exp_next, str(Res.string.memo_rd_offline_next))), "no problem block when ready")
        }
    }
}
