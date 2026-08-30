package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AskOption
import dev.ccpocket.protocol.AskQuestion
import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AskWithdrawnReason
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

    private fun ask(convo: String, askId: String, tool: String = "Edit") =
        PermissionAsk(convo, askId, tool, inputPreview = "…", rule = tool)

    /** An AskUserQuestion: same frame type, `questions` non-null — which is the whole difference. */
    private fun question(convo: String, askId: String) = PermissionAsk(
        convo, askId, "AskUserQuestion", inputPreview = "…",
        questions = listOf(AskQuestion("Which parser?", options = listOf(AskOption("recursive descent"), AskOption("PEG")))),
    )

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

    // ── AskUserQuestion + the ask life cycle in a column (W3) ─────────────────────────────────────────

    @Test
    fun aQuestionReachesItsPaneInsteadOfDyingInSilence() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))

        p.route(question("convo-a", "q-1"))

        // Filtered out, this frame had NO surface anywhere: the machine-wide bell inbox excludes questions
        // by design, and the focused question card is gated on the focused conversation. The column just
        // streamed until the daemon timed the ask out — the turn died without a word.
        assertEquals("q-1", pane.pendingAsk.value?.askId)
    }

    @Test
    fun aSecondAskQueuesBehindTheCardAndSurfacesWhenItIsDecided() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))

        p.route(ask("convo-a", "ask-1"))
        p.route(ask("convo-a", "ask-2"))
        // the daemon holds several asks per conversation at once; overwriting kept only the last, and the
        // agent waited out a verdict for the first that no surface would ever offer
        assertEquals("ask-1", pane.pendingAsk.value?.askId)
        assertEquals(listOf("ask-2"), pane.askQueue.map { it.askId })

        p.noteApprovalResolved("convo-a", "ask-1")
        assertEquals("ask-2", pane.pendingAsk.value?.askId)
        assertTrue(pane.askQueue.isEmpty())

        p.noteApprovalResolved("convo-a", "ask-2")
        assertNull(pane.pendingAsk.value)
    }

    @Test
    fun aResurfacedAskRefreshesInPlaceInsteadOfQueueingTwice() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        p.route(ask("convo-a", "ask-1"))
        p.route(ask("convo-a", "ask-2"))

        // a reattach resurfaces EVERY pending ask of the conversation, current card included
        p.route(ask("convo-a", "ask-1", tool = "Bash"))
        p.route(ask("convo-a", "ask-2", tool = "Write"))

        assertEquals("Bash", pane.pendingAsk.value?.tool, "the card on screen refreshes where it stands")
        assertEquals(listOf("ask-2" to "Write"), pane.askQueue.map { it.askId to it.tool })
    }

    @Test
    fun aTimedOutCardStaysUpUntilANewAskOrATapRetiresIt() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        p.route(ask("convo-a", "ask-1"))

        p.route(AskWithdrawn("convo-a", "ask-1", AskWithdrawnReason.TIMED_OUT))
        // issue #100: a card that simply vanishes reads as "my decision went through"
        assertEquals("ask-1", pane.pendingAsk.value?.askId)
        assertEquals("ask-1", pane.timedOutAskId.value)

        p.route(ask("convo-a", "ask-2"))
        assertEquals("ask-2", pane.pendingAsk.value?.askId, "a live ask retires the terminal card")
        assertNull(pane.timedOutAskId.value, "and the terminal state dies with the card it described")
        assertTrue(pane.askQueue.isEmpty(), "the timed-out card must not dam the queue")

        p.route(AskWithdrawn("convo-a", "ask-2", AskWithdrawnReason.TIMED_OUT))
        p.noteApprovalResolved("convo-a", "ask-2") // the terminal card's Dismiss
        assertNull(pane.pendingAsk.value)
        assertNull(pane.timedOutAskId.value)
    }

    @Test
    fun aWithdrawnQuestionLeavesANoteAndSurfacesTheNextAsk() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        p.route(question("convo-a", "q-1"))
        p.route(ask("convo-a", "ask-2"))

        // a question has no "timed out" card state — it goes, and says so, so the column does not look
        // like the agent stopped talking for no reason
        p.route(AskWithdrawn("convo-a", "q-1", AskWithdrawnReason.TIMED_OUT))

        assertTrue(pane.messages.any { it is ChatItem.QuestionsWithdrawn })
        assertEquals("ask-2", pane.pendingAsk.value?.askId)
        assertNull(pane.timedOutAskId.value)
    }

    @Test
    fun aQueuedAskWithdrawnBeforeItWasEverDrawnGoesSilently() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        p.route(ask("convo-a", "ask-1"))
        p.route(question("convo-a", "q-2"))

        p.route(AskWithdrawn("convo-a", "q-2", AskWithdrawnReason.WITHDRAWN))

        assertEquals("ask-1", pane.pendingAsk.value?.askId, "the card on screen is untouched")
        assertTrue(pane.askQueue.isEmpty())
        // nothing to explain about a card the user never saw — a note here would be about a question that
        // was never asked of them
        assertTrue(pane.messages.none { it is ChatItem.QuestionsWithdrawn })
    }

    @Test
    fun answeringAQuestionNotesThePicksInThatColumnAndAdvances() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        p.route(question("convo-a", "q-1"))
        p.route(ask("convo-a", "ask-2"))

        p.noteQuestionsAnswered("convo-a", "q-1", listOf("Which parser?" to "recursive descent"))

        assertEquals(
            listOf(listOf("Which parser?" to "recursive descent")),
            pane.messages.filterIsInstance<ChatItem.QuestionsAnswered>().map { it.items },
        )
        assertEquals("ask-2", pane.pendingAsk.value?.askId)
    }

    @Test
    fun sessionGoneRetiresEveryAskTheColumnWasHolding() {
        val p = panes()
        val pane = open(p)
        p.route(live("convo-a", "sid-a"))
        p.route(ask("convo-a", "ask-1"))
        p.route(question("convo-a", "q-2"))
        p.route(AskWithdrawn("convo-a", "ask-1", AskWithdrawnReason.TIMED_OUT))

        p.route(SessionGone("convo-a"))

        // no verdict can reach a conversation that is gone, so nothing may keep offering one
        assertNull(pane.pendingAsk.value)
        assertTrue(pane.askQueue.isEmpty())
        assertNull(pane.timedOutAskId.value)
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
    fun aColumnRebindsWhenTheReconnectAnswersWithADifferentConversation() {
        // The daemon restarted while the link was down, so the resume lands on a FRESH convoId. Holding
        // the dead one used to freeze the column forever: it matched neither the bind fallback nor the
        // claim, and the answer was taken by the focused chat instead.
        val p = panes()
        val pane = open(p)
        p.route(live("convo-old", "sid-a"))
        p.route(ConvoHistory("convo-old", listOf(HistoryMessage(ChatRole.ASSISTANT, "earlier")), lastSeq = 12))
        sent.clear()

        p.reopenAll()
        assertNull(pane.convoId.value, "the conversation died with the link it was announced on")
        assertTrue(pane.opening.value)
        assertEquals(12L, sent.filterIsInstance<OpenSession>().single().lastEventSeq) // still a delta

        p.route(live("convo-new", "sid-a"))
        assertEquals("convo-new", pane.convoId.value)
        assertFalse(pane.opening.value)
        assertTrue(p.claimsSessionLive(live("convo-new", "sid-a")))

        // and the new conversation's frames route into THIS pane (streaming appends to the replayed tail)
        p.route(text("convo-new", " back online"))
        assertEquals(
            listOf("earlier back online"),
            pane.messages.filterIsInstance<ChatItem.Assistant>().map { it.text },
        )
    }

    @Test
    fun aReconnectClearsTheFailedOpenItIsAboutToReplace() = runTest {
        val p = panes(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val pane = open(p)
        advanceTimeBy(9_000)
        assertTrue(pane.openFailed.value)

        p.reopenAll()
        // "opening AND failed" is a contradiction the user can see — the re-attach owns the state now
        assertFalse(pane.openFailed.value)
        assertTrue(pane.opening.value)
    }

    @Test
    fun closingAColumnMidOpenHandsTheLateAnswerBackToTheDaemon() {
        val p = panes()
        val pane = open(p)
        sent.clear()
        p.close(pane.paneId)
        assertTrue(p.panes.isEmpty())
        assertTrue(sent.isEmpty(), "no conversation exists yet, so there is nothing to close")

        // the answer to an open nobody can call back: it must not be adopted by the focused chat…
        assertTrue(p.claimsSessionLive(live("convo-a", "sid-a")))
        p.route(live("convo-a", "sid-a"))
        // …and it must not be left mounted on the daemon with no viewer either
        assertEquals("convo-a", sent.filterIsInstance<CloseSession>().single().convoId)
        assertTrue(p.panes.isEmpty(), "a disowned answer never manufactures a column")

        // consumed once: asking for the same session again binds normally
        sent.clear()
        val again = open(p, "sid-a")
        p.route(live("convo-a2", "sid-a"))
        assertEquals("convo-a2", again.convoId.value)
        assertTrue(sent.filterIsInstance<CloseSession>().isEmpty())
    }

    @Test
    fun detachingAColumnMidOpenLeavesTheAnswerForTheFocusedChat() {
        // A promotion clicked before the column's own open landed: the session is WANTED, so this must
        // stay the mirror image of close() — no claim, and above all no CloseSession racing the promotion.
        val p = panes()
        val pane = open(p)
        sent.clear()
        p.detach(pane.paneId)
        assertFalse(p.claimsSessionLive(live("convo-a", "sid-a")))
        p.route(live("convo-a", "sid-a"))
        assertTrue(sent.filterIsInstance<CloseSession>().isEmpty())
        assertTrue(p.panes.isEmpty())
    }

    @Test
    fun focusingASessionReleasesItsColumnAndUndoesADisowning() {
        val p = panes()
        val bound = open(p, "sid-a")
        p.route(live("convo-a", "sid-a"))
        sent.clear()
        p.releaseToFocus(bound.sessionId)
        assertTrue(p.panes.isEmpty())
        assertTrue(sent.filterIsInstance<CloseSession>().isEmpty(), "a promotion never reclaims the session")
        assertFalse(p.claimsSessionLive(live("convo-a", "sid-a")), "the focused chat may have its answer")

        // …and the change of mind: closed while opening, then focused anyway
        val b = p.open("/w", "sid-b", "B", AgentKind.CLAUDE, PermissionMode.DEFAULT)!!
        p.close(b.paneId)
        p.releaseToFocus("sid-b")
        assertFalse(p.claimsSessionLive(live("convo-b", "sid-b")))
        sent.clear()
        p.route(live("convo-b", "sid-b"))
        assertTrue(sent.filterIsInstance<CloseSession>().isEmpty(), "the focused chat is waiting for this one")
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
