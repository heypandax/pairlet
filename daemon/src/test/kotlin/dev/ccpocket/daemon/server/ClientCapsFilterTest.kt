package dev.ccpocket.daemon.server

import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DirectoryEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ClientCaps wire gate: a client built before `AgentKind.OPENCODE` existed hard-fails the WHOLE
 * Envelope on that value (pinned in `SerializationRoundTripTest`), and every ingress swallows the
 * failure — so the frame just vanishes. The daemon therefore must not put an opencode row in front of a
 * peer that never declared support.
 *
 * Filtering the `activeSessions` LIST alone was not enough, and that gap was a shipped-user P0: the
 * sibling scalars (`open`, `activeSessionId`, …) are filled from `live.firstOrNull()` regardless of
 * agent, so the old client still saw a "running" row pointing at an opencode session id. Tapping it
 * resolved the agent off the now-empty list (→ CLAUDE), reattached by resumeId, and got back a
 * `SessionLive` carrying `agent="opencode"` that it dropped whole — a row that says running, taps that
 * do nothing, and no error anywhere.
 *
 * These pin the row a filtered client sees. [RequestRouter.filterDirs] is `internal` for exactly this.
 */
class ClientCapsFilterTest {

    private fun undeclared() = RequestRouter.ClientCapsHolder() // default: supportsOpencode = false
    private fun declared() = RequestRouter.ClientCapsHolder().apply { supportsOpencode = true }

    private fun entry(vararg live: ActiveSession) = DirectoryEntry(
        path = "/w/proj", name = "proj", isDir = true, hasSessions = true,
        open = live.isNotEmpty(),
        executing = live.any { it.executing },
        activeSessionId = live.firstOrNull()?.sessionId,
        activeSessionTitle = live.firstOrNull()?.title,
        gitBranch = live.firstOrNull()?.gitBranch,
        activeSessions = live.toList(),
    )

    private fun oc(id: String, executing: Boolean = false) =
        ActiveSession(sessionId = id, title = "oc", agent = AgentKind.OPENCODE, executing = executing, gitBranch = "main")

    private fun claude(id: String, executing: Boolean = false) =
        ActiveSession(sessionId = id, title = "cl", agent = AgentKind.CLAUDE, executing = executing, gitBranch = "dev")

    /** THE P0 regression: an opencode-only project must reach an undeclared client with NO tap target. */
    @Test
    fun `an opencode-only project leaves no live session for an undeclared client`() {
        val out = RequestRouter.filterDirs(listOf(entry(oc("oc-sid-1", executing = true))), undeclared())
        val e = out.single()
        assertTrue(e.activeSessions.isEmpty(), "opencode row must be stripped")
        assertNull(e.activeSessionId, "a stripped session must not stay reachable through the scalar")
        assertNull(e.activeSessionTitle)
        assertNull(e.gitBranch)
        assertFalse(e.open, "the row must not claim to be open — that is the tap target")
        assertFalse(e.executing, "executing came from the session that was stripped")
        assertEquals("/w/proj", e.path, "the project row itself stays visible")
    }

    /** A mixed project keeps its claude session — and the scalars must describe THAT one, not the
     *  opencode session that happened to sort first. */
    @Test
    fun `a mixed project keeps the surviving session and describes it in the scalars`() {
        val out = RequestRouter.filterDirs(listOf(entry(oc("oc-sid", executing = true), claude("cl-sid"))), undeclared())
        val e = out.single()
        assertEquals(listOf("cl-sid"), e.activeSessions.map { it.sessionId })
        assertEquals("cl-sid", e.activeSessionId, "scalar must follow the survivor, not the stripped row")
        assertEquals("cl", e.activeSessionTitle)
        assertEquals("dev", e.gitBranch)
        assertTrue(e.open)
        assertFalse(e.executing, "only the stripped opencode session was executing")
    }

    /** A declared client is untouched — the gate must cost nothing once support is announced. */
    @Test
    fun `a declared client sees the opencode rows unchanged`() {
        val input = listOf(entry(oc("oc-sid", executing = true), claude("cl-sid")))
        assertEquals(input, RequestRouter.filterDirs(input, declared()))
    }

    /** No opencode anywhere = identity, so ordinary fleets pay nothing for this. */
    @Test
    fun `projects without opencode pass through untouched`() {
        val input = listOf(entry(claude("cl-sid", executing = true)))
        assertEquals(input, RequestRouter.filterDirs(input, undeclared()))
        assertEquals(input, RequestRouter.filterDirs(input, null))
    }

    /** A null holder is a legacy ingress / bridge — it must filter like an undeclared client, never
     *  fail open, because those peers are exactly the ones least likely to understand the new value. */
    @Test
    fun `a null caps holder filters like an undeclared client`() {
        val e = RequestRouter.filterDirs(listOf(entry(oc("oc-sid"))), null).single()
        assertTrue(e.activeSessions.isEmpty())
        assertNull(e.activeSessionId)
        assertFalse(e.open)
    }
}
