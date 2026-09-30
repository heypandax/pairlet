package dev.ccpocket.app.memo

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.VoiceMemoStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Batch dispatch (code design §7.5): prompt ids stored before anything is sent, one item in flight at a time,
 * receipts matched on binding + convo + prompt, unknown never resent, late receipts never resume a batch, stops
 * pause what was not submitted, and a restart turns `sending` into `unknown`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoDispatchTest {

    private val memo = uuid(800)
    private var harness: MemoHarness? = null

    @AfterTest
    fun noActorFailures() {
        harness?.let { assertEquals(emptyList(), it.errors, "the actor threw") }
    }

    private fun TestScope.open(vararg todos: String, setup: MemoHarness.() -> Unit = {}): MemoHarness {
        val h = MemoHarness(this).also { harness = it }
        h.seed(seededDoc(memo, *todos.mapIndexed { i, text -> "t${i + 1}" to text }.toTypedArray()))
        h.gateway.rows = listOf(TARGET_ROW)
        h.setup()
        h.start()
        h.act(MemoAction.OpenMemo(memo))
        h.act(MemoAction.SelectTarget(TARGET))
        return h
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun MemoHarness.records() = checkNotNull(stored(memo)).dispatches
    private fun MemoHarness.states() = records().associate { it.promptId to it.state }
    private fun MemoHarness.rowStates() = state.document!!.todos.map { it.state }

    @Test
    fun oneItemIsSubmittedOnceAndDeliveredOnItsReceipt() = runTest {
        val h = open("Fix the build")
        assertEquals(MemoDispatchBlock.NONE, h.state.selection.block)
        h.confirm()
        val prompt = h.gateway.submitted.single()
        assertEquals("prompt-1", prompt.promptId)
        assertEquals("Fix the build", prompt.text, "the gateway adds the fixed prefix")
        assertEquals("t1", prompt.todoId)
        assertEquals("convo-1", prompt.lease.convoId)
        assertEquals(MemoDispatchPhase.SENDING, h.state.dispatch!!.phase)
        assertEquals(1, h.state.dispatch!!.current)
        assertEquals(MemoDispatchBlock.BUSY, h.state.selection.block)

        h.receipt("prompt-1")
        assertEquals(MemoDispatchPhase.DONE, h.state.dispatch!!.phase)
        assertEquals(1, h.state.dispatch!!.delivered)
        val record = h.records().single()
        assertEquals(MemoTodoState.DELIVERED, record.state)
        assertEquals("convo-1", record.convoId)
        assertEquals(TARGET, record.target)
        assertEquals("Fix the build", record.confirmedText)
        assertEquals(listOf(MemoTodoState.DELIVERED), h.rowStates())
        assertEquals(MemoDispatchBlock.NO_SELECTION, h.state.selection.block, "a delivered item is not sendable again")
    }

    @Test
    fun threeItemsGoOneReceiptAtATime() = runTest {
        val h = open("a", "b", "c")
        h.confirm()
        assertEquals(listOf("prompt-1"), h.gateway.submitted.map { it.promptId })
        assertEquals(mapOf("prompt-1" to MemoTodoState.SENDING, "prompt-2" to MemoTodoState.DRAFT, "prompt-3" to MemoTodoState.DRAFT), h.states())
        h.receipt("prompt-1")
        assertEquals(listOf("prompt-1", "prompt-2"), h.gateway.submitted.map { it.promptId })
        assertEquals(2, h.state.dispatch!!.current)
        h.receipt("prompt-2")
        h.receipt("prompt-3")
        assertEquals(listOf("prompt-1", "prompt-2", "prompt-3"), h.gateway.submitted.map { it.promptId })
        assertEquals(MemoDispatchPhase.DONE, h.state.dispatch!!.phase)
        assertEquals(3, h.state.dispatch!!.delivered)
        assertEquals(0, h.state.dispatch!!.unsent)
        assertTrue(h.states().values.all { it == MemoTodoState.DELIVERED })
    }

    @Test
    fun aMissingSecondReceiptStopsTheBatchAndTheThirdStaysADraft() = runTest {
        val h = open("a", "b", "c")
        h.confirm()
        h.receipt("prompt-1")
        advance(9_999)
        assertEquals(MemoDispatchPhase.SENDING, h.state.dispatch!!.phase)
        advance(1)
        val dispatch = h.state.dispatch!!
        assertEquals(MemoDispatchPhase.STOPPED, dispatch.phase)
        assertEquals(MemoDispatchStop.RECEIPT_MISSING, dispatch.stop)
        assertEquals(listOf(1, 1, 0, 1), listOf(dispatch.delivered, dispatch.unknown, dispatch.failed, dispatch.unsent))
        assertEquals(listOf(MemoTodoState.DELIVERED, MemoTodoState.UNKNOWN, MemoTodoState.DRAFT), h.rowStates())
        advance(60_000)
        assertEquals(2, h.gateway.submitted.size, "nothing further is sent")

        // The late receipt records the truth and resumes nothing.
        h.receipt("prompt-2")
        assertEquals(MemoTodoState.DELIVERED, h.states()["prompt-2"])
        assertEquals(MemoDispatchPhase.STOPPED, h.state.dispatch!!.phase)
        advance(30_000)
        assertEquals(2, h.gateway.submitted.size)

        // Only the user's next confirmation sends the remaining draft, as a new batch.
        assertEquals(listOf("t3"), h.state.selection.items.map { it.todoId })
        h.confirm()
        val third = h.gateway.submitted.last()
        assertEquals("t3", third.todoId)
        assertNotEquals(h.records().first().batchId, h.records().last().batchId)
    }

    @Test
    fun aSubmitProvenNotSentFailsTheItemAndStops() = runTest {
        val h = open("a", "b") { gateway.submitResult = { MemoSubmitResult.NotSubmitted } }
        h.confirm()
        assertEquals(MemoDispatchPhase.STOPPED, h.state.dispatch!!.phase)
        assertEquals(MemoDispatchStop.NOT_SENT, h.state.dispatch!!.stop)
        assertEquals(listOf(MemoTodoState.FAILED, MemoTodoState.DRAFT), h.rowStates())
        assertEquals(1, h.gateway.submitted.size)
        assertEquals(MemoDispatchCoordinator.NOT_SUBMITTED, h.state.document!!.todos.first().errorCode)
    }

    @Test
    fun anUnknownSubmitIsNeverRetried() = runTest {
        val h = open("a", "b") { gateway.submitResult = { MemoSubmitResult.Unknown } }
        h.confirm()
        assertEquals(MemoDispatchStop.RECEIPT_MISSING, h.state.dispatch!!.stop)
        assertEquals(listOf(MemoTodoState.UNKNOWN, MemoTodoState.DRAFT), h.rowStates())
        advance(60_000)
        assertEquals(1, h.gateway.submitted.size)
    }

    @Test
    fun aTargetThatCannotBeOpenedSendsNothing() = runTest {
        val h = open("a", "b") { gateway.enterResult = { _, _ -> MemoEnterResult.Failed("timeout") } }
        h.confirm()
        assertEquals(MemoDispatchPhase.OPEN_FAILED, h.state.dispatch!!.phase)
        assertTrue(h.gateway.submitted.isEmpty())
        assertEquals(listOf(MemoTodoState.DRAFT, MemoTodoState.DRAFT), h.rowStates())
        assertTrue(h.states().values.all { it == MemoTodoState.DRAFT })
        assertEquals(MemoDispatchBlock.NONE, h.state.selection.block, "the drafts can be dispatched again")
    }

    @Test
    fun aLeaseForADifferentSessionIsAnOpenFailure() = runTest {
        val h = open("a") {
            gateway.enterResult = { _, batch -> MemoEnterResult.Entered(MemoTargetLease(TARGET.copy(sessionId = "forked"), "c9", batch, 1)) }
        }
        h.confirm()
        assertEquals(MemoDispatchPhase.OPEN_FAILED, h.state.dispatch!!.phase)
        assertTrue(h.gateway.submitted.isEmpty())
    }

    @Test
    fun promptIdsAreDurableBeforeAnythingIsSent() = runTest {
        val h = open("a", "b")
        h.confirm()
        h.receipt("prompt-1")
        val log = h.log.toList()
        val drafts = log.indexOfFirst { it == "commit:prompt-1=draft,prompt-2=draft" }
        val sending1 = log.indexOfFirst { it.startsWith("commit:") && "prompt-1=sending" in it && !it.endsWith(":failed") }
        val submit1 = log.indexOf("submit:prompt-1")
        val delivered1 = log.indexOfFirst { it.startsWith("commit:") && "prompt-1=delivered" in it && !it.endsWith(":failed") }
        val sending2 = log.indexOfFirst { it.startsWith("commit:") && "prompt-2=sending" in it }
        val submit2 = log.indexOf("submit:prompt-2")
        assertTrue(drafts in 0 until sending1, "$log")
        assertTrue(sending1 < submit1 && submit1 < delivered1 && delivered1 <= sending2 && sending2 < submit2, "$log")
        // The draft records were stored before the target was even opened.
        assertEquals(1, h.gateway.entered.size)
        assertEquals("convo-1", h.records().first().convoId)
    }

    @Test
    fun whenThePromptIdsCannotBeStoredNothingIsOpenedOrSent() = runTest {
        val h = open("a") { }
        h.store.commitResult = { if (it.dispatches.isNotEmpty()) MemoWrite.NotWritten() else null }
        h.confirm()
        assertTrue(h.gateway.entered.isEmpty())
        assertTrue(h.gateway.submitted.isEmpty())
        assertNull(h.state.dispatch)
        assertEquals(MemoToast.SAVE_FAILED, h.state.toast)
        assertEquals(MemoDispatchBlock.NONE, h.state.selection.block)
    }

    @Test
    fun whenSendingCannotBeStoredTheItemIsNotSubmitted() = runTest {
        val h = open("a")
        h.store.commitResult = { doc -> if (doc.dispatches.any { it.state == MemoTodoState.SENDING }) MemoWrite.Indeterminate else null }
        h.confirm()
        assertTrue(h.gateway.submitted.isEmpty())
        assertEquals(MemoDispatchStop.SAVE_FAILED, h.state.dispatch!!.stop)
        assertEquals(listOf(MemoTodoState.DRAFT), h.rowStates())
    }

    @Test
    fun neverMoreThanOneItemIsSending() = runTest {
        val h = open("a", "b", "c", "d")
        val seen = mutableListOf<Int>()
        h.gateway.submitResult = { prompt ->
            // At the moment of each submit, exactly this item is `sending` in memory and on disk.
            val stored = h.records()
            seen += stored.count { it.state == MemoTodoState.SENDING }
            check(stored.single { it.state == MemoTodoState.SENDING }.promptId == prompt.promptId)
            MemoSubmitResult.AwaitingReceipt
        }
        h.confirm()
        (1..4).forEach { h.receipt("prompt-$it") }
        assertEquals(listOf(1, 1, 1, 1), seen)
        h.log.filter { it.startsWith("commit:") }.forEach { line ->
            assertTrue(Regex("=sending").findAll(line).count() <= 1, line)
        }
    }

    @Test
    fun aReceiptMustMatchBindingConvoAndPrompt() = runTest {
        val h = open("a")
        h.confirm()
        h.receipt("prompt-1", convoId = "another-chat")
        h.receipt("prompt-1", bindingId = "another-computer")
        h.receipt("prompt-9")
        assertEquals(MemoDispatchPhase.SENDING, h.state.dispatch!!.phase)
        advance(10_000)
        assertEquals(MemoTodoState.UNKNOWN, h.states()["prompt-1"])
    }

    @Test
    fun aReceiptThatBeatsItsSubmitIsNotLost() = runTest {
        val h = open("a", "b")
        val gate = CompletableDeferred<MemoSubmitResult>()
        h.gateway.submitGate = gate
        h.confirm()
        h.receipt("prompt-1")
        assertEquals(MemoTodoState.SENDING, h.states()["prompt-1"])
        h.gateway.submitGate = null
        gate.complete(MemoSubmitResult.AwaitingReceipt)
        runCurrent()
        assertEquals(MemoTodoState.DELIVERED, h.states()["prompt-1"])
        assertEquals(listOf("prompt-1", "prompt-2"), h.gateway.submitted.map { it.promptId })
    }

    @Test
    fun aStopPausesTheRestButTheItemInFlightStillGetsItsReceipt() = runTest {
        val h = open("a", "b", "c")
        h.confirm()
        h.gateway.stops.tryEmit(MemoDispatchStop.LEFT_CHAT)
        runCurrent()
        assertEquals(MemoDispatchPhase.SENDING, h.state.dispatch!!.phase, "still waiting for the item in flight")
        h.receipt("prompt-1")
        val d = h.state.dispatch!!
        assertEquals(MemoDispatchPhase.STOPPED, d.phase)
        assertEquals(MemoDispatchStop.LEFT_CHAT, d.stop)
        assertEquals(listOf(MemoTodoState.DELIVERED, MemoTodoState.DRAFT, MemoTodoState.DRAFT), h.rowStates())
        assertEquals(1, h.gateway.submitted.size)
        assertEquals(2, d.unsent)
    }

    @Test
    fun aStopWhileAwaitingThenNoReceiptLeavesItUnknown() = runTest {
        val h = open("a", "b")
        h.confirm()
        h.gateway.stops.tryEmit(MemoDispatchStop.BACKGROUND)
        runCurrent()
        advance(10_000)
        assertEquals(MemoDispatchStop.BACKGROUND, h.state.dispatch!!.stop)
        assertEquals(listOf(MemoTodoState.UNKNOWN, MemoTodoState.DRAFT), h.rowStates())
    }

    @Test
    fun theLeaseIsCheckedBeforeEveryItem() = runTest {
        val h = open("a", "b")
        h.confirm()
        h.gateway.holds = false
        h.receipt("prompt-1")
        assertEquals(MemoDispatchStop.TARGET_LOST, h.state.dispatch!!.stop)
        assertEquals(1, h.gateway.submitted.size)
        assertEquals(listOf(MemoTodoState.DELIVERED, MemoTodoState.DRAFT), h.rowStates())
    }

    @Test
    fun aDeliveryThatCannotBeStoredPausesTheBatch() = runTest {
        val h = open("a", "b")
        h.confirm()
        h.store.commitResult = { doc -> if (doc.dispatches.any { it.state == MemoTodoState.DELIVERED }) MemoWrite.NotWritten() else null }
        h.receipt("prompt-1")
        assertEquals(MemoDispatchStop.SAVE_FAILED, h.state.dispatch!!.stop)
        assertEquals(MemoTodoState.DELIVERED, h.state.document!!.todos.first().state, "delivered in memory")
        assertEquals(1, h.gateway.submitted.size, "the next item is not sent on an unsaved ledger")
    }

    @Test
    fun deliveredItemsAreNeverInTheNextBatch() = runTest {
        val h = open("a", "b")
        h.act(MemoAction.ToggleTodo("t2"))
        h.confirm()
        h.receipt("prompt-1")
        assertEquals(MemoDispatchPhase.DONE, h.state.dispatch!!.phase)
        h.act(MemoAction.ToggleTodo("t2"))
        assertEquals(listOf("t2"), h.state.selection.items.map { it.todoId })
        h.confirm()
        assertEquals(listOf("t1", "t2"), h.gateway.submitted.map { it.todoId })
        assertEquals(2, h.records().map { it.batchId }.distinct().size)
    }

    @Test
    fun aRestartTurnsSendingIntoUnknownAndLeavesTheRest() = runTest {
        val record = { p: String, todo: String, state: String -> MemoDispatchRecord("b1", todo, p, "x", TARGET, "c", state, 1) }
        val h = MemoHarness(this).also { harness = it }
        h.seed(
            seededDoc(
                memo, "t1" to "a", "t2" to "b", "t3" to "c",
                dispatches = listOf(record("p1", "t1", MemoTodoState.DELIVERED), record("p2", "t2", MemoTodoState.SENDING), record("p3", "t3", MemoTodoState.DRAFT)),
            ),
        )
        h.start()
        assertEquals(mapOf("p1" to MemoTodoState.DELIVERED, "p2" to MemoTodoState.UNKNOWN, "p3" to MemoTodoState.DRAFT), h.states())
        assertTrue(h.gateway.submitted.isEmpty(), "nothing is resent")
        assertEquals(1, h.state.list.rows.single().unknown)
    }

    @Test
    fun returningToTheMemoKeepsTheBatchAndViewSessionShowsTheTarget() = runTest {
        val h = open("a", "b")
        h.confirm()
        advance(10_000)
        h.act(MemoAction.BackToList)
        h.act(MemoAction.ReturnToMemo)
        assertEquals(listOf(memo), h.gateway.shownMemos)
        assertEquals(MemoScreen.DETAIL, h.state.screen)
        assertEquals(memo, h.state.document!!.memoId)
        assertEquals(MemoDispatchPhase.STOPPED, h.state.dispatch!!.phase)
        h.act(MemoAction.ViewSession("t1"))
        assertEquals(listOf(TARGET), h.gateway.shown)
        h.act(MemoAction.Closed)
        assertNull(h.state.dispatch, "a finished batch is forgotten when the memo is closed")
    }

    // ── security review follow-ups ────────────────────────────────────────────────────────────────────

    @Test
    fun aConfirmationThatNoLongerMatchesWhatWasShownIsRefused() = runTest {
        val h = open("a", "b", "c")
        val shown = h.state.selection.shown
        assertEquals(listOf(MemoShownItem("t1", "a"), MemoShownItem("t2", "b"), MemoShownItem("t3", "c")), shown)
        val refused = listOf(
            listOf(MemoShownItem("t1", "a"), MemoShownItem("t2", "b")) to TARGET, // fewer items
            listOf(shown[1], shown[0], shown[2]) to TARGET, // another order
            listOf(MemoShownItem("t1", "A"), shown[1], shown[2]) to TARGET, // other text
            listOf(MemoShownItem("t9", "a"), shown[1], shown[2]) to TARGET, // another id
            shown to TARGET.copy(sessionId = "session-2"),
            shown to TARGET.copy(workdir = "/work/other"),
            shown to TARGET.copy(bindingId = "binding-b"),
            shown to TARGET.copy(agent = AgentKind.CODEX),
        )
        for ((items, target) in refused) {
            h.act(MemoAction.ConfirmDispatch(items, target))
            assertEquals(MemoToast.CHANGED_SINCE_SHOWN, h.state.toast, "$items $target")
            h.act(MemoAction.ToastShown)
            assertNull(h.state.dispatch)
            assertTrue(h.records().isEmpty(), "no record is written")
            assertTrue(h.gateway.entered.isEmpty())
        }

        // The memo changed after the sheet was drawn: the old sheet's confirmation is refused.
        h.act(MemoAction.EditTodo("t2", "b, but different"))
        h.act(MemoAction.ConfirmDispatch(shown, TARGET))
        assertEquals(MemoToast.CHANGED_SINCE_SHOWN, h.state.toast)
        assertTrue(h.records().isEmpty())

        // Only surrounding whitespace and display labels are tolerated; a matching confirmation dispatches.
        h.act(MemoAction.ToastShown)
        val now = h.state.selection.shown.map { it.copy(text = "  ${it.text} ") }
        h.act(MemoAction.ConfirmDispatch(now, TARGET.copy(project = "display only", title = "labels differ")))
        assertNull(h.state.toast)
        assertEquals(listOf("t1"), h.gateway.submitted.map { it.todoId })
    }

    @Test
    fun nothingIsDispatchedWhileAReorganiseIsRunning() = runTest {
        val h = open("a")
        h.act(MemoAction.Reorganize)
        assertEquals(MemoDispatchBlock.BUSY, h.state.selection.block)
        h.confirm()
        assertTrue(h.gateway.entered.isEmpty())
        assertTrue(h.records().isEmpty())
        val attempt = h.link.frames<VoiceMemoStart>().last().attemptId
        h.push(readyState(memo, attempt, 1, "new item"))
        assertEquals(MemoDispatchBlock.NONE, h.state.selection.block)
    }

    @Test
    fun aReorganiseResultNeverRemovesItemsOfARunningBatch() = runTest {
        val h = open("a", "b", "c")
        h.act(MemoAction.ToggleTodo("t3"))
        h.confirm()
        assertEquals(mapOf("prompt-1" to MemoTodoState.SENDING, "prompt-2" to MemoTodoState.DRAFT), h.states())
        h.act(MemoAction.Reorganize)
        val attempt = h.link.frames<VoiceMemoStart>().last().attemptId
        h.push(readyState(memo, attempt, 1, "fresh"))
        val todos = h.stored(memo)!!.todos
        assertEquals(listOf("t1" to "a", "t2" to "b"), todos.take(2).map { it.todoId to it.text }, "batch items keep id and text")
        assertEquals(listOf("fresh"), todos.drop(2).map { it.text }, "the unselected draft t3 was replaced")
        h.receipt("prompt-1")
        assertEquals("t2", h.gateway.submitted.last().todoId)
        assertEquals("b", h.gateway.submitted.last().text)
    }

    @Test
    fun aSubmitThatNeverReturnsBecomesUnknownAndItsLateResultIsIgnored() = runTest {
        val h = open("a", "b")
        val gate = CompletableDeferred<MemoSubmitResult>()
        h.gateway.submitGate = gate
        h.confirm()
        advance(9_999)
        assertEquals(MemoTodoState.SENDING, h.states()["prompt-1"])
        advance(1)
        assertEquals(MemoTodoState.UNKNOWN, h.states()["prompt-1"])
        assertEquals(MemoDispatchPhase.STOPPED, h.state.dispatch!!.phase)
        assertEquals(MemoDispatchStop.RECEIPT_MISSING, h.state.dispatch!!.stop)
        // A late NotSubmitted is ignored: the user was already told to check the chat, and unknown is never resent.
        gate.complete(MemoSubmitResult.NotSubmitted)
        runCurrent()
        assertEquals(MemoTodoState.UNKNOWN, h.states()["prompt-1"])
        assertEquals(listOf(MemoTodoState.UNKNOWN, MemoTodoState.DRAFT), h.rowStates())
        assertEquals(MemoDispatchPhase.STOPPED, h.state.dispatch!!.phase)
        assertEquals(1, h.gateway.submitted.size)
        // A real receipt later still records the truth, and resumes nothing.
        h.receipt("prompt-1")
        assertEquals(MemoTodoState.DELIVERED, h.states()["prompt-1"])
        assertEquals(1, h.gateway.submitted.size)
    }

    @Test
    fun aReceiptBeforeAnUnknownSubmitMeansDelivered() = runTest {
        val h = open("a", "b")
        val gate = CompletableDeferred<MemoSubmitResult>()
        h.gateway.submitGate = gate
        h.confirm()
        h.receipt("prompt-1")
        h.gateway.submitGate = null
        gate.complete(MemoSubmitResult.Unknown)
        runCurrent()
        assertEquals(MemoTodoState.DELIVERED, h.states()["prompt-1"])
        assertEquals(listOf("prompt-1", "prompt-2"), h.gateway.submitted.map { it.promptId })
        h.receipt("prompt-2")
        assertEquals(MemoDispatchPhase.DONE, h.state.dispatch!!.phase)
    }
}
