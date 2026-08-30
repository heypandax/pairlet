package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AssistantChunk
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionGone
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.StreamPiece
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop split panes' conversation channel (issue #311).
 *
 * The invariant the whole feature rests on: a pane's conversation is routed to THAT pane and nowhere
 * else, and the repository's own single-conversation state machine is left alone. These tests drive
 * [SidePanes] with the frames a daemon actually sends, because the failure this design must not have is
 * a second session's output appearing in the first session's transcript.
 */
class SplitPanesTest {

    private val sent = mutableListOf<Frame>()
    private var promptSeq = 0
    /** Unconfined by default so a launch runs inline and `sent` is filled by the time a verb returns.
     *  The open-deadline cases pass a test dispatcher instead, to get virtual time for the 8s window. */
    private fun panes(scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)) = SidePanes(
        scope = scope,
        send = { sent += it },
        newPromptId = { "p${++promptSeq}" },
    )

    private fun open(p: SidePanes, sid: String = "sid-a") =
        p.open("/w", sid, "Refactor the parser", AgentKind.CLAUDE, PermissionMode.DEFAULT)!!

    private fun live(convo: String, sid: String) = SessionLive(convoId = convo, workdir = "/w", sessionId = sid)

    private fun text(convo: String, s: String) = AssistantChunk(convo, 1, StreamPiece.Text(s))

    @Test
    fun openSendsAResumeAndBindsItsOwnSessionLive() {
        val p = panes()
        val pane = open(p)
        val frame = sent.filterIsInstance<OpenSession>().single()
        assertEquals("/w", frame.workdir)
        assertEquals("sid-a", frame.resumeId)
        assertEquals(0L, frame.lastEventSeq) // declares delta-replay support without asking for a delta yet
        assertTrue(pane.opening.value)

        p.route(live("convo-a", "sid-a"))
        assertEquals("convo-a", pane.convoId.value)
        assertFalse(pane.opening.value)
    }

    @Test
    fun streamLandsInTheOwningPaneOnly() {
        val p = panes()
        val a = open(p, "sid-a")
        val b = p.open("/w2", "sid-b", "Write the docs", AgentKind.CODEX, PermissionMode.DEFAULT)!!
        p.route(live("convo-a", "sid-a"))
        p.route(live("convo-b", "sid-b"))

        p.route(text("convo-a", "hello from A"))
        p.route(text("convo-b", "hello from B"))

        assertEquals(listOf("hello from A"), a.messages.filterIsInstance<ChatItem.Assistant>().map { it.text })
        assertEquals(listOf("hello from B"), b.messages.filterIsInstance<ChatItem.Assistant>().map { it.text })
    }

    @Test
    fun aFrameForNoPaneChangesNothing() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        p.route(text("convo-elsewhere", "not ours"))
        assertTrue(pane.messages.isEmpty())
    }

    @Test
    fun toolResultPatchesTheCardItStarted() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        p.route(ToolEvent("convo-a", 1, ToolPhase.START, "Bash", "ls -la", toolUseId = "t1"))
        p.route(ToolEvent("convo-a", 2, ToolPhase.RESULT, "Bash", toolUseId = "t1", ok = true, output = "3 files"))
        val card = pane.messages.filterIsInstance<ChatItem.Tool>().single()
        assertEquals(true, card.ok)
        assertEquals("3 files", card.output)
    }

    @Test
    fun turnDoneEndsTheTurnAndSettlesPendingBubbles() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        assertTrue(p.sendPrompt(pane, "run the tests"))
        assertTrue(pane.streaming.value)
        assertTrue(pane.messages.filterIsInstance<ChatItem.User>().single().pending)

        p.route(TurnDone("convo-a"))
        assertFalse(pane.streaming.value)
        assertFalse(pane.messages.filterIsInstance<ChatItem.User>().single().pending)
        assertEquals(1, pane.messages.count { it is ChatItem.TurnEnded })
    }

    @Test
    fun failedTurnShowsTheErrorAndNoCompletionMarker() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        p.route(text("convo-a", "starting"))
        p.route(TurnDone("convo-a", error = "API request failed"))
        assertEquals("API request failed", pane.messages.filterIsInstance<ChatItem.Sys>().single().text)
        assertEquals(0, pane.messages.count { it is ChatItem.TurnEnded })
    }

    @Test
    fun historyReplayFillsThePaneBacklog() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        p.route(
            ConvoHistory(
                "convo-a",
                listOf(
                    HistoryMessage(ChatRole.USER, "what changed?"),
                    HistoryMessage(ChatRole.ASSISTANT, "three files"),
                ),
                lastSeq = 42,
            ),
        )
        assertEquals(2, pane.messages.size)
        assertEquals("what changed?", (pane.messages[0] as ChatItem.User).text)
        assertEquals("three files", (pane.messages[1] as ChatItem.Assistant).text)
        assertEquals(42L, pane.historySeq)
    }

    @Test
    fun sendIsRefusedUntilTheOpenLands() {
        val p = panes()
        val pane = open(p)
        assertFalse(p.sendPrompt(pane, "too early")) // no convoId yet
        assertTrue(sent.filterIsInstance<SendPrompt>().isEmpty())

        p.route(live("convo-a", "sid-a"))
        assertTrue(p.sendPrompt(pane, "now"))
        val prompt = sent.filterIsInstance<SendPrompt>().single()
        assertEquals("convo-a", prompt.convoId)
        assertEquals("now", prompt.text)
    }

    @Test
    fun blankSendIsIgnored() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        assertFalse(p.sendPrompt(pane, "   "))
        assertTrue(sent.filterIsInstance<SendPrompt>().isEmpty())
    }

    @Test
    fun approvalReachesItsPaneAndAnyDecisionRetiresIt() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        p.route(PermissionAsk("convo-a", "ask-1", "Bash", "rm -rf build"))
        assertEquals("ask-1", pane.pendingAsk.value?.askId)

        p.noteApprovalResolved("convo-a", "ask-1") // decided from the bell popover / the phone
        assertNull(pane.pendingAsk.value)
    }

    @Test
    fun sessionGoneIsSaidOutLoudRatherThanLeavingABlankColumn() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        p.route(SessionGone("convo-a"))
        assertTrue(pane.gone.value)
        assertFalse(pane.streaming.value)
    }

    @Test
    fun theSplitIsCappedAndNeverShowsOneSessionTwice() {
        val p = panes()
        open(p, "sid-a")
        assertNotNull(p.open("/w", "sid-b", "B", AgentKind.CLAUDE, PermissionMode.DEFAULT))
        // MAX_SPLIT_PANES counts the focused conversation, so two side columns fill a three-way split
        assertFalse(p.canOpen())
        assertNull(p.open("/w", "sid-c", "C", AgentKind.CLAUDE, PermissionMode.DEFAULT))
        assertNull(p.open("/w", "sid-a", "A again", AgentKind.CLAUDE, PermissionMode.DEFAULT))
        assertEquals(2, p.panes.size)
    }

    @Test
    fun closingAnIdleColumnReclaimsItsSessionButAStreamingOneKeepsRunning() {
        val p = panes()
        val idle = open(p, "sid-a")
        p.route(live("convo-a", "sid-a"))
        sent.clear()
        p.close(idle.paneId)
        assertEquals("convo-a", sent.filterIsInstance<CloseSession>().single().convoId)

        val busy = p.open("/w", "sid-b", "B", AgentKind.CLAUDE, PermissionMode.DEFAULT)!!
        p.route(live("convo-b", "sid-b"))
        p.route(text("convo-b", "mid-turn output")) // a chunk means the turn is live
        sent.clear()
        p.close(busy.paneId)
        assertTrue(sent.filterIsInstance<CloseSession>().isEmpty()) // work in flight outlives its column
        assertTrue(p.panes.isEmpty())
    }

    @Test
    fun aPanesOwnSessionLiveIsClaimedSoTheFocusedChatCannotTakeIt() {
        val p = panes()
        val pane = open(p)
        assertTrue(p.claimsSessionLive(live("convo-a", "sid-a")))   // by session id, before binding
        p.route(live("convo-a", "sid-a"))
        assertTrue(p.claimsSessionLive(live("convo-a", "sid-a")))   // by convo id, after binding (reattach)
        assertFalse(p.claimsSessionLive(live("convo-other", "sid-other")))
        assertEquals("convo-a", pane.convoId.value)
    }

    @Test
    fun anOpenThatNeverLandsFailsInsteadOfHangingOnOpening() = runTest {
        val p = panes(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val pane = open(p)
        assertTrue(pane.opening.value)
        advanceTimeBy(9_000)
        assertFalse(pane.opening.value)
        assertTrue(pane.openFailed.value) // the column says so, and offers the retry

        sent.clear()
        p.retry(pane)
        assertFalse(pane.openFailed.value)
        val again = sent.filterIsInstance<OpenSession>().single()
        assertEquals("sid-a", again.resumeId) // the SAME request, not a differently-flagged one
        p.route(live("convo-a", "sid-a"))
        advanceTimeBy(9_000)
        assertFalse(pane.openFailed.value) // the first open's dead deadline cannot fail the retry
    }

    @Test
    fun aLandedOpenIsNeverFailedByItsOwnDeadline() = runTest {
        val p = panes(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        advanceTimeBy(9_000)
        assertFalse(pane.openFailed.value)
        assertEquals("convo-a", pane.convoId.value)
    }

    @Test
    fun reconnectReattachesEveryColumnAskingOnlyForTheDelta() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        p.route(ConvoHistory("convo-a", listOf(HistoryMessage(ChatRole.ASSISTANT, "earlier")), lastSeq = 77))
        sent.clear()

        p.reopenAll()
        val reopen = sent.filterIsInstance<OpenSession>().single()
        assertEquals("sid-a", reopen.resumeId)
        assertEquals(77L, reopen.lastEventSeq) // backfill past the cursor, not a whole-tail replay
    }

    @Test
    fun detachDropsAColumnWithoutReclaimingItsSession() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        sent.clear()
        p.detach(pane.paneId)
        assertTrue(p.panes.isEmpty())
        // a promotion re-opens this very session next; a CloseSession here would race that open
        assertTrue(sent.filterIsInstance<CloseSession>().isEmpty())
    }

    @Test
    fun clearDropsEveryColumnWithoutTouchingTheSessions() {
        val p = panes()
        open(p, "sid-a")
        p.route(live("convo-a", "sid-a"))
        sent.clear()
        p.clear()
        assertTrue(p.panes.isEmpty())
        assertTrue(sent.isEmpty()) // a link that died cannot be told anything anyway
    }
}
