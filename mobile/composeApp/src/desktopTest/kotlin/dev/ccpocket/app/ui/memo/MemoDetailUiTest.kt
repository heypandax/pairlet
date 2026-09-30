package dev.ccpocket.app.ui.memo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.ccpocket.app.memo.MemoAction
import dev.ccpocket.app.memo.MemoDispatchBlock
import dev.ccpocket.app.memo.MemoSelectionState
import dev.ccpocket.app.memo.MemoTargetStatus
import dev.ccpocket.app.memo.MemoTodoState
import dev.ccpocket.app.memo.MemoUiState
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.str
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The result page (voice memo → tasks, handoff "结果页" + frozen spec §5.3–§5.4): what the dispatch button says
 * when it cannot act, the single authorisation path through the confirm sheet, the five delivery states and
 * what each allows, and that the pinned actions survive 320 dp at 200 % type.
 */
@OptIn(ExperimentalTestApi::class)
class MemoDetailUiTest {

    private fun blockedSelection(block: MemoDispatchBlock, withTarget: Boolean = false) =
        MemoSelectionState(target = if (withTarget) MemoFx.targetRow else null, items = emptyList(), block = block)

    @Test
    fun theDispatchButtonIsDisabledAndSaysWhyForNothingSelectedNoTargetAndOnlyBlankItems() {
        var state by mutableStateOf(MemoFx.detail(selection = blockedSelection(MemoDispatchBlock.NO_SELECTION)))
        val actions = mutableListOf<MemoAction>()
        memoScene(content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            val bare = str(Res.string.memo_dispatch)
            for ((block, reason) in listOf(
                MemoDispatchBlock.NO_SELECTION to str(Res.string.memo_block_no_selection),
                MemoDispatchBlock.NO_TARGET to str(Res.string.memo_block_no_target),
                MemoDispatchBlock.ONLY_BLANK to str(Res.string.memo_block_only_blank),
            )) {
                state = MemoFx.detail(selection = blockedSelection(block))
                waitForIdle()
                // 0 items: the label carries no count, and the button refuses
                buttons(bare).onFirst().assertIsNotEnabled()
                assertTrue(has(reason), "$block must be written out: \"$reason\"")
                buttons(bare).onFirst().performClick() // a real touch: a disabled node may carry no OnClick at all
                waitForIdle()
                assertFalse(has(str(Res.string.memo_confirm_title)), "$block: a disabled button opens nothing")
            }
            assertTrue(actions.none { it is MemoAction.ConfirmDispatch })

            // selected, with a target: enabled, opens the confirm sheet, and only ITS button authorises
            state = MemoFx.detail()
            waitForIdle()
            val label = str(Res.string.memo_dispatch_n, 3)
            buttons(label).onFirst().assertIsEnabled()
            buttons(label).onFirst().tap()
            waitForIdle()
            assertTrue(has(str(Res.string.memo_confirm_title)), "the tap opens the confirm sheet")
            assertTrue(actions.none { it is MemoAction.ConfirmDispatch }, "opening the sheet is not the authorisation")
            assertEquals(2, buttonCount(label), "bar + sheet")
            buttons(label).onLast().tap()
            waitForIdle()
            val confirmed = actions.filterIsInstance<MemoAction.ConfirmDispatch>().single()
            assertEquals(MemoFx.threeDrafts.map { it.todoId to it.text }, confirmed.shown.map { it.todoId to it.text }, "the action carries what the sheet showed")
            assertEquals(MemoFx.target, confirmed.target)
            assertFalse(has(str(Res.string.memo_confirm_title)), "the sheet closes once confirmed")
        }
    }

    @Test
    fun theOtherBlocksAreWrittenToo() {
        var state by mutableStateOf(MemoFx.detail())
        memoScene(content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            for ((block, reason) in listOf(
                MemoDispatchBlock.SAVING to str(Res.string.memo_block_saving),
                MemoDispatchBlock.TARGET_UNAVAILABLE to str(Res.string.memo_block_target_unavailable),
                MemoDispatchBlock.OFFLINE to str(Res.string.memo_block_offline, "alex-mac"),
                MemoDispatchBlock.BUSY to str(Res.string.memo_block_busy),
            )) {
                state = MemoFx.detail(selection = MemoFx.readySelection(MemoFx.threeDrafts).copy(block = block))
                waitForIdle()
                buttons(str(Res.string.memo_dispatch_n, 3)).onFirst().assertIsNotEnabled()
                assertTrue(has(reason), "$block: \"$reason\"")
            }
        }
    }

    @Test
    fun theConfirmSheetShowsTheSameTurnNoticeForTwoOrMoreItemsAndNeverForOne() {
        val notice = str(Res.string.memo_multi_notice)
        var state by mutableStateOf(MemoFx.detail())
        memoScene(content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            buttons(str(Res.string.memo_dispatch_n, 3)).onFirst().tap()
            waitForIdle()
            assertTrue(has(notice), "3 items: the notice, verbatim")
            MemoFx.threeDrafts.forEach { assertTrue(has(it.text), "the sheet lists the exact text: ${it.text}") }
            assertTrue(has(MemoFx.target.title), "…and the exact target")

            val one = listOf(MemoFx.todo("t1", "只做这一件事。"))
            state = MemoFx.detail(todos = one)
            waitForIdle()
            assertTrue(has(str(Res.string.memo_confirm_title)), "the sheet is still open")
            assertFalse(has(notice), "1 item: no same-turn notice")

            val two = MemoFx.threeDrafts.take(2)
            state = MemoFx.detail(todos = two)
            waitForIdle()
            assertTrue(has(notice), "2 items: the notice is back, busy target or not")
        }
    }

    @Test
    fun eachDeliveryStateHasItsOwnWordsAndOnlyItsOwnActions() {
        val todos = listOf(
            MemoFx.todo("t1", "草稿这一项", MemoTodoState.DRAFT),
            MemoFx.todo("t2", "正在发送这一项", MemoTodoState.SENDING),
            MemoFx.todo("t3", "已经送达这一项", MemoTodoState.DELIVERED),
            MemoFx.todo("t4", "等待核对这一项", MemoTodoState.UNKNOWN),
            MemoFx.todo("t5", "发送失败这一项", MemoTodoState.FAILED),
        )
        val actions = mutableListOf<MemoAction>()
        val state = MemoFx.detail(todos = todos)
        memoScene(height = 1400, content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            for (label in listOf(Res.string.memo_state_draft, Res.string.memo_state_sending, Res.string.memo_state_delivered, Res.string.memo_state_unknown, Res.string.memo_state_failed)) {
                assertTrue(has(str(label), substring = true), "state word \"${str(label)}\" is written")
            }
            assertTrue(has(str(Res.string.memo_unknown_note), substring = true), "待核对 says no receipt arrived")

            // only the draft is editable, only the draft has a checkbox
            assertEquals(1, onAllNodes(hasContentDescription(str(Res.string.memo_todo_edit_cd, 1))).fetchSemanticsNodes().size)
            for (i in 2..5) assertEquals(0, onAllNodes(hasContentDescription(str(Res.string.memo_todo_edit_cd, i))).fetchSemanticsNodes().size, "item $i is not editable")
            assertEquals(1, onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).fetchSemanticsNodes().size)
            assertTrue(has(todos[2].text) && has(todos[3].text), "delivered / to-check text is shown read-only")

            // delivered and to-check: view + copy; never retry
            assertEquals(2, buttonCount(str(Res.string.memo_view_session)))
            assertEquals(2, buttonCount(str(Res.string.memo_copy_todo)))
            assertEquals(1, buttonCount(str(Res.string.memo_retry_failed)), "only the failed item offers a retry")
            // remove ×: draft and failed
            assertEquals(2, onAllNodes(hasContentDescription(str(Res.string.memo_todo_delete_cd))).fetchSemanticsNodes().size)

            buttons(str(Res.string.memo_retry_failed)).onFirst().tap()
            buttons(str(Res.string.memo_view_session)).onLast().tap()
            buttons(str(Res.string.memo_copy_todo)).onLast().tap()
            waitForIdle()
            assertEquals(
                listOf(MemoAction.RetryFailedTodo("t5"), MemoAction.ViewSession("t4"), MemoAction.CopyTodo("t4")),
                actions.filter { it is MemoAction.RetryFailedTodo || it is MemoAction.ViewSession || it is MemoAction.CopyTodo },
            )
            assertFalse(allScreenText().any { it.contains('%') }, "no raw format argument or percentage anywhere")
        }
    }

    @Test
    fun aCopyOfAToCheckItemCarriesTheDuplicateWarning() {
        val copy = MemoFx.todo("t6", "等待核对这一项").copy(copiedFromIndex = 4, copyOfUnknown = true, selected = false)
        val state = MemoFx.detail(todos = MemoFx.threeDrafts + copy)
        memoScene(content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            assertTrue(has(str(Res.string.memo_copied_from_unknown, 4)))
        }
    }

    @Test
    fun typingIsStoredOnceWhenItPausesNotOncePerCharacter() {
        val actions = mutableListOf<MemoAction>()
        val state = MemoFx.detail()
        memoScene(autoAdvance = false, content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            val field = onAllNodes(hasContentDescription(str(Res.string.memo_todo_edit_cd, 1))).onFirst()
            field.performTextInput("再")
            field.performTextInput("补")
            field.performTextInput("一句")
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertTrue(actions.none { it is MemoAction.EditTodo }, "no save per keystroke")
            mainClock.advanceTimeBy(MEMO_EDIT_COMMIT_MS + 200)
            waitForIdle()
            val edits = actions.filterIsInstance<MemoAction.EditTodo>()
            assertEquals(1, edits.size, "one save once typing pauses: $edits")
            assertEquals("t1", edits.single().todoId)
            assertTrue(edits.single().text.endsWith("再补一句") || edits.single().text.startsWith("再补一句"), edits.single().text)
        }
    }

    @Test
    fun atThreeTwentyWideAndTwoHundredPercentTypeTheResultAndConfirmKeepTheirActionsOnScreen() {
        var state: MemoUiState = MemoFx.detail()
        memoScene(width = SMALL_W, height = SMALL_H, fontScale = 2f, content = { VoiceMemoScreen(state, 0f, {}, {}) }) {
            val label = str(Res.string.memo_dispatch_n, 3)
            assertInViewport(buttons(label).onFirst(), "the result's dispatch button", SMALL_W, SMALL_H)
            assertTrue(has(str(Res.string.memo_selected, 3), substring = true), "compact: one summary line above the button")
            buttons(label).onFirst().tap()
            waitForIdle()
            assertInViewport(onAllNodes(hasText(str(Res.string.memo_multi_notice))).onFirst(), "the same-turn notice", SMALL_W, SMALL_H)
            assertInViewport(buttons(label).onLast(), "the confirm sheet's button", SMALL_W, SMALL_H)
            assertInViewport(buttons(str(Res.string.memo_back_edit)).onFirst(), "back to edit", SMALL_W, SMALL_H)
        }
    }

    @Test
    fun aDegradedResultOffersReorganizeOnlyThroughAConfirmation() {
        val actions = mutableListOf<MemoAction>()
        val state = MemoFx.detail(todos = emptyList(), selection = MemoSelectionState()).copy(
            document = MemoFx.doc(todos = emptyList(), degraded = true, summary = "只整理出一段说明。"),
        )
        memoScene(content = { VoiceMemoScreen(state, 0f, { actions += it }, {}) }) {
            val again = str(Res.string.memo_reorganize_with, "Claude")
            assertTrue(has(str(Res.string.memo_degraded_title)))
            assertTrue(has(str(Res.string.memo_unstructured)), "a degraded summary is tagged")
            assertTrue(has(str(Res.string.memo_no_todos)))
            assertFalse(has(str(Res.string.memo_organized_by, "Claude")), "a degraded memo names no organiser as its author")
            buttons(again).onFirst().tap()
            waitForIdle()
            assertTrue(actions.none { it is MemoAction.Reorganize }, "the first tap only asks")
            assertTrue(has(str(Res.string.memo_reorg_confirm_body)))
            buttons(again).onLast().tap()
            waitForIdle()
            assertEquals(listOf<MemoAction>(MemoAction.Reorganize), actions.filter { it is MemoAction.Reorganize })
            assertTrue(has(str(Res.string.memo_not_measured)), "an unmeasured total is written as such")
        }
    }
}
