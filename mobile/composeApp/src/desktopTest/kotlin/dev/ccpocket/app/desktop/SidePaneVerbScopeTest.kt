package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.SidePane
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AgentKind
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
}
