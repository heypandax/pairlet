package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.SidePane
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AskOption
import dev.ccpocket.protocol.AskQuestion
import dev.ccpocket.protocol.AskWithdrawn
import dev.ccpocket.protocol.AskWithdrawnReason
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PendingApprovals
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #311 — a split column's controls act on the COLUMN's conversation, on the wire.
 *
 * [SidePaneModel] is a `DesktopModel by base` delegate, so anything it does not override reaches the
 * FOCUSED conversation. The card renders the column's ask, the ■ renders the column's streaming state —
 * and then the click landed one column over, which is the single worst way for a split view to fail: it
 * looks like it worked. Every case below therefore asserts on the frames, with the focused session
 * deliberately live and blocked at the same time, so a regression to plain delegation cannot pass.
 *
 * Drive is [PocketRepository.onSendForTest] / [PocketRepository.receiveForTest], as in
 * [dev.ccpocket.app.data.SplitPanePromotionTest]: outbound frames are collected, inbound ones are fed
 * straight to `handle`, and Unconfined makes both synchronous.
 */
class SidePaneVerbScopeTest {

    private val sent = mutableListOf<Frame>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val repo = PocketRepository(scope).apply {
        paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        sessionsDir.value = "/w/proj"
        onSendForTest = { sent += it }
    }

    // Production order: the model exists before any session opens, so its init collectors' first pass
    // sees a null sessionKey (see RepoDesktopModelStopTurnTest's note).
    private val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())

    /** The focused conversation: live, running a turn, and blocked on its own approval. Everything a
     *  leaked verb would land on is real here — that is what makes the assertions mean something. */
    private fun focusedSession(): PermissionAsk {
        repo.openSession("/w/proj", resumeId = "sid-focused")
        repo.receiveForTest(SessionLive("convo-focused", "/w/proj", "sid-focused", executing = true))
        repo.streaming.value = true
        val ask = ask("convo-focused", "ask-focused", "Bash")
        repo.receiveForTest(ask)
        return ask
    }

    private fun column(streaming: Boolean = false): SidePane {
        val pane = repo.sidePanes.open("/w/proj", "sid-pane", "Column", AgentKind.CLAUDE, PermissionMode.DEFAULT)!!
        repo.receiveForTest(SessionLive("convo-pane", "/w/proj", "sid-pane", executing = streaming))
        return pane
    }

    private fun ask(convoId: String, askId: String, tool: String) =
        PermissionAsk(convoId = convoId, askId = askId, tool = tool, inputPreview = "…", rule = tool)

    /** An AskUserQuestion for [convoId]: same frame, `questions` non-null. */
    private fun question(convoId: String, askId: String) = PermissionAsk(
        convoId = convoId, askId = askId, tool = "AskUserQuestion", inputPreview = "…",
        questions = listOf(
            AskQuestion("Which parser?", options = listOf(AskOption("recursive descent"), AskOption("PEG"))),
        ),
    )

    private fun verdicts() = sent.filterIsInstance<PermissionVerdict>()

    @Test
    fun stopInAColumnCancelsThatColumnsTurnAndLeavesTheFocusedOneRunning() {
        focusedSession()
        val pane = column(streaming = true)
        sent.clear()

        SidePaneModel(model, pane).stopTurn()

        assertEquals(
            listOf(CancelTurn("convo-pane")), sent.filterIsInstance<CancelTurn>(),
            "the column's ■ must interrupt its OWN conversation — delegation cancelled convo-focused",
        )
        assertTrue(repo.streaming.value, "the focused turn is untouched")
    }

    @Test
    fun stopInAnIdleColumnSendsNothing() {
        focusedSession()
        val pane = column(streaming = false)
        sent.clear()

        SidePaneModel(model, pane).stopTurn()

        assertTrue(sent.isEmpty(), "no turn to interrupt here, and certainly not the focused one")
    }

    @Test
    fun alwaysAllowInAColumnKeepsItsRemember() {
        focusedSession()
        val pane = column()
        repo.receiveForTest(ask("convo-pane", "ask-pane", "Edit"))
        sent.clear()

        SidePaneModel(model, pane).resolve(allow = true, remember = true)

        val v = verdicts().single()
        assertEquals("convo-pane", v.convoId)
        assertEquals("ask-pane", v.askId)
        assertEquals(Decision.ALLOW, v.decision)
        // the first cut dropped the flag on the floor: 始终允许 became a silent allow-once, and the very
        // next matching action asked again with nothing to explain why
        assertTrue(v.remember, "『始终允许』must reach the daemon as remember=true")
        assertNull(pane.pendingAsk.value)
    }

    @Test
    fun aColumnsVerdictGoesOutEvenWhenTheInboxHasNoRowForIt() {
        focusedSession()
        val pane = column()
        repo.receiveForTest(ask("convo-pane", "ask-pane", "Edit"))
        // A `PendingApprovals` reply clears and refills the inbox wholesale (a reconnect, or a snapshot
        // that raced this ask). The card is still on screen; the row backing it is not. The old
        // row-keyed path returned here and destroyed the card with NO verdict on the wire — the CLI then
        // waited out its whole timeout on a decision the user had already made.
        repo.receiveForTest(PendingApprovals(emptyList()))
        sent.clear()

        SidePaneModel(model, pane).resolve(allow = false, remember = false)

        val v = verdicts().single()
        assertEquals("convo-pane", v.convoId)
        assertEquals("ask-pane", v.askId)
        assertEquals(Decision.DENY, v.decision)
    }

    @Test
    fun taskGrantInAColumnDecidesThatColumnsAskNotTheFocusedOne() {
        val focused = focusedSession()
        val pane = column()
        repo.receiveForTest(ask("convo-pane", "ask-pane", "Edit"))
        sent.clear()

        SidePaneModel(model, pane).resolveTaskGrant()

        val v = verdicts().single()
        assertEquals("convo-pane", v.convoId, "『允许本任务』granted the FOCUSED session's tool before this")
        assertEquals("ask-pane", v.askId)
        assertEquals(Decision.ALLOW, v.decision)
        assertEquals("task", v.grantScope)
        assertEquals(focused, repo.pendingAsk.value, "the focused card is still waiting for its own answer")
    }

    @Test
    fun retrySaferInAColumnDeniesThatColumnsAskUnderItsConstraints() {
        val focused = focusedSession()
        val pane = column()
        repo.receiveForTest(ask("convo-pane", "ask-pane", "Bash"))
        sent.clear()

        SidePaneModel(model, pane).retrySafer(listOf("no network"))

        val v = verdicts().single()
        assertEquals("convo-pane", v.convoId)
        assertEquals("ask-pane", v.askId)
        assertEquals(Decision.DENY, v.decision)
        assertTrue(v.retrySafer)
        assertEquals(listOf("no network"), v.constraints)
        assertEquals(focused, repo.pendingAsk.value)
    }

    // ── AskUserQuestion in a column (W3) ─────────────────────────────────────────────────────────────

    /**
     * The answer must be shaped EXACTLY like the focused path's, because that shape is a wire contract:
     * the daemon merges `answers` into claude's `updatedInput.answers`, keyed by the verbatim question
     * text, and a bare ALLOW without it reads to the CLI as "the user did not answer" (#57). So this
     * answers a column's question and the focused session's question with the same picks, and compares
     * the two verdicts field by field — only the address may differ.
     */
    @Test
    fun answeringInAColumnSendsTheFocusedPathsVerdictAddressedToTheColumnsAsk() {
        repo.openSession("/w/proj", resumeId = "sid-focused")
        repo.receiveForTest(SessionLive("convo-focused", "/w/proj", "sid-focused", executing = true))
        repo.receiveForTest(question("convo-focused", "q-focused"))
        val pane = column()
        repo.receiveForTest(question("convo-pane", "q-pane"))
        val picks = mapOf("Which parser?" to "recursive descent")
        sent.clear()

        SidePaneModel(model, pane).answerQuestions(picks, null)
        model.answerQuestions(picks, null) // the focused card, same picks — the reference shape

        val (column, focused) = verdicts().let { it[0] to it[1] }
        assertEquals("convo-pane", column.convoId, "the column answered the FOCUSED question before this")
        assertEquals("q-pane", column.askId)
        assertEquals(picks, column.answers, "the answers map IS the contract — a bare ALLOW means 'unanswered'")
        assertEquals(
            focused.copy(convoId = column.convoId, askId = column.askId), column,
            "a column's answer must be the focused path's verdict, addressed differently — nothing else",
        )
        assertNull(pane.pendingAsk.value, "the answered card is retired")
        // the answer reads back where it was given: the agent quotes nothing back, so without the note the
        // column shows a question and then, apparently, nothing
        assertEquals(
            listOf(listOf("Which parser?" to "recursive descent")),
            pane.messages.filterIsInstance<dev.ccpocket.app.data.ChatItem.QuestionsAnswered>().map { it.items },
        )
    }

    @Test
    fun skippingInAColumnDeniesThatColumnsQuestionWithTheNote() {
        val focused = focusedSession()
        val pane = column()
        repo.receiveForTest(question("convo-pane", "q-pane"))
        sent.clear()

        SidePaneModel(model, pane).skipQuestions("User skipped the questions")

        val v = verdicts().single()
        assertEquals("convo-pane", v.convoId)
        assertEquals("q-pane", v.askId)
        assertEquals(Decision.DENY, v.decision)
        // the note is why the skip is a DENY and not a dropped card: claude learns the user opted out
        // instead of waiting out the whole timeout (#57)
        assertEquals("User skipped the questions", v.message)
        assertEquals(focused, repo.pendingAsk.value)
    }

    /** The queue and the issue-#100 terminal state are read off the COLUMN, not the repository — the
     *  delegated answers described the focused conversation's burst and the focused ask's timeout. */
    @Test
    fun theColumnsCardReportsItsOwnQueueAndItsOwnTimeout() {
        focusedSession()
        val pane = column()
        val m = SidePaneModel(model, pane)
        repo.receiveForTest(ask("convo-pane", "ask-1", "Edit"))
        assertNull(m.askQueuePosition, "one card, no chip — exactly the old single-ask card")

        repo.receiveForTest(ask("convo-pane", "ask-2", "Write"))
        assertEquals(1 to 2, m.askQueuePosition)
        assertFalse(m.askTimedOut)

        repo.receiveForTest(AskWithdrawn("convo-pane", "ask-1", AskWithdrawnReason.TIMED_OUT))
        assertTrue(m.askTimedOut, "the card flips to its terminal state instead of vanishing")
        sent.clear()

        m.dismissAsk()
        assertTrue(sent.isEmpty(), "the daemon already auto-denied — a verdict now decides nothing")
        assertEquals("ask-2", m.ask?.askId, "and the queued card comes up")
        assertFalse(m.askTimedOut, "a stale timeout cannot grey out the next ask")
    }
}
