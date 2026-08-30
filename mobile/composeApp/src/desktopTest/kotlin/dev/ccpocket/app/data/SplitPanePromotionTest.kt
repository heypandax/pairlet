package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #311 — one session is EITHER the focused chat or a column, never both at once.
 *
 * The failure this pins down is not visible from [SidePanes] alone: clicking the sidebar row of a session
 * that already has a column ran an ordinary open, whose answering `SessionLive` was then claimed by that
 * column ([SidePanes.claimsSessionLive] decides before every focused-chat rule). The focused area's open
 * was therefore never answered, and the click ended on "Opening…" for eight seconds and then on an open
 * failure whose Retry failed identically, forever. Fixing it at [PocketRepository.openSession] rather than
 * at the desktop's promote verb is deliberate: openSession is the one chokepoint every way of focusing a
 * session goes through, so a sidebar click, a promotion, a push tap and a deep link all mean "promote it".
 */
class SplitPanePromotionTest {
    private val sent = mutableListOf<Frame>()
    private val repo = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        sessionsDir.value = "/w/proj"
        onSendForTest = { sent += it }
    }

    private fun column(sid: String = "sid-a") =
        repo.sidePanes.open("/w/proj", sid, "Refactor the parser", AgentKind.CLAUDE, PermissionMode.DEFAULT)!!

    @Test
    fun focusingASessionThatIsInAColumnPromotesItInsteadOfStrandingTheOpen() {
        val pane = column()
        repo.receiveForTest(SessionLive("convo-a", "/w/proj", "sid-a", executing = false))
        assertEquals("convo-a", pane.convoId.value)
        sent.clear()

        assertTrue(repo.openSession("/w/proj", resumeId = "sid-a"))
        assertTrue(repo.sidePanes.panes.isEmpty(), "the column lets go BEFORE the open goes on the wire")
        assertEquals("sid-a", sent.filterIsInstance<OpenSession>().single().resumeId)
        // detached, not closed: a CloseSession here would race the open that is promoting this very session
        assertTrue(sent.none { it is CloseSession })

        assertFalse(repo.sidePanes.claimsSessionLive(SessionLive("convo-a", "/w/proj", "sid-a")))
        repo.receiveForTest(SessionLive("convo-a", "/w/proj", "sid-a", executing = false))
        assertEquals("convo-a", repo.convoId.value, "the answer reaches the focused chat")
        assertFalse(repo.opening.value)
    }

    @Test
    fun focusingAColumnWhoseOpenIsStillInFlightAlsoPromotesIt() {
        // Same gesture, one beat earlier: the column has no convoId yet, so the claim runs off sessionId.
        val pane = column()
        assertNull(pane.convoId.value)
        sent.clear()

        assertTrue(repo.openSession("/w/proj", resumeId = "sid-a"))
        assertTrue(repo.sidePanes.panes.isEmpty())
        repo.receiveForTest(SessionLive("convo-a", "/w/proj", "sid-a", executing = false))
        assertEquals("convo-a", repo.convoId.value)
    }

    @Test
    fun aSecondClickOnThePromotedRowIsStillTheNoOpItAlwaysWas() {
        // The release rides AFTER openSession's two synchronous refusals, so a repeated gesture stays
        // idempotent: one open on the wire, and no second teardown of anything.
        column()
        repo.receiveForTest(SessionLive("convo-a", "/w/proj", "sid-a", executing = false))
        sent.clear()

        assertTrue(repo.openSession("/w/proj", resumeId = "sid-a"))
        assertFalse(repo.openSession("/w/proj", resumeId = "sid-a"), "the same target is already in flight")
        assertEquals(1, sent.filterIsInstance<OpenSession>().size)

        // an unrelated column is never disturbed by a promotion of a different session
        val other = repo.sidePanes.open("/w/proj", "sid-b", "B", AgentKind.CLAUDE, PermissionMode.DEFAULT)!!
        repo.receiveForTest(SessionLive("convo-b", "/w/proj", "sid-b", executing = false))
        assertEquals(listOf(other), repo.sidePanes.panes.toList())
        assertEquals("convo-b", other.convoId.value)
    }
}
