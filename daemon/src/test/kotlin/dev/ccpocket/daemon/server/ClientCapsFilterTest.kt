package dev.ccpocket.daemon.server

import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.AuthorizedActionRecorded
import dev.ccpocket.protocol.HandoffUpdated
import dev.ccpocket.protocol.PermissionRiskUpdated
import dev.ccpocket.protocol.SessionHandoff
import dev.ccpocket.protocol.Usage
import dev.ccpocket.protocol.UsageDay
import dev.ccpocket.protocol.UsageModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    private fun entry(vararg live: ActiveSession, sessionAgents: List<AgentKind> = live.map { it.agent }) = DirectoryEntry(
        path = "/w/proj", name = "proj", isDir = true, hasSessions = true,
        open = live.isNotEmpty(),
        executing = live.any { it.executing },
        busy = live.any { it.busy },
        activeSessionId = live.firstOrNull()?.sessionId,
        activeSessionTitle = live.firstOrNull()?.title,
        gitBranch = live.firstOrNull()?.gitBranch,
        activeSessions = live.toList(),
        sessionAgents = sessionAgents,
    )

    private fun oc(id: String, executing: Boolean = false, busy: Boolean = false) =
        ActiveSession(
            sessionId = id, title = "oc", agent = AgentKind.OPENCODE, executing = executing,
            busy = busy, gitBranch = "main", executingAuthoritative = true,
        )

    private fun zcode(id: String, executing: Boolean = false, busy: Boolean = false) =
        ActiveSession(
            sessionId = id, title = "zc", agent = AgentKind.ZCODE, executing = executing,
            busy = busy, gitBranch = "zcode", executingAuthoritative = true,
        )

    private fun claude(id: String, executing: Boolean = false, busy: Boolean = false) =
        ActiveSession(
            sessionId = id, title = "cl", agent = AgentKind.CLAUDE, executing = executing,
            busy = busy, gitBranch = "dev", executingAuthoritative = true,
        )

    /** THE P0 regression: an opencode-only project must reach an undeclared client with NO tap target. */
    @Test
    fun `an opencode-only project leaves no live session for an undeclared client`() {
        val out = RequestRouter.filterDirs(
            listOf(entry(oc("oc-sid-1", executing = true, busy = true))),
            undeclared(),
        )
        val e = out.single()
        assertTrue(e.activeSessions.isEmpty(), "opencode row must be stripped")
        assertNull(e.activeSessionId, "a stripped session must not stay reachable through the scalar")
        assertNull(e.activeSessionTitle)
        assertNull(e.gitBranch)
        assertFalse(e.open, "the row must not claim to be open — that is the tap target")
        assertFalse(e.executing, "executing came from the session that was stripped")
        assertFalse(e.busy, "background work from the stripped session must not keep the row Running")
        assertEquals("/w/proj", e.path, "the project row itself stays visible")
    }

    /** A mixed project keeps its claude session — and the scalars must describe THAT one, not the
     *  opencode session that happened to sort first. */
    @Test
    fun `a mixed project keeps the surviving session and describes it in the scalars`() {
        val out = RequestRouter.filterDirs(
            listOf(entry(oc("oc-sid", executing = true, busy = true), claude("cl-sid"))),
            undeclared(),
        )
        val e = out.single()
        assertEquals(listOf("cl-sid"), e.activeSessions.map { it.sessionId })
        assertEquals("cl-sid", e.activeSessionId, "scalar must follow the survivor, not the stripped row")
        assertEquals("cl", e.activeSessionTitle)
        assertEquals("dev", e.gitBranch)
        assertTrue(e.open)
        assertFalse(e.executing, "only the stripped opencode session was executing")
        assertFalse(e.busy, "only the stripped opencode session had background work")
        assertTrue(e.activeSessions.single().executingAuthoritative, "filtering preserves the survivor's work source")
    }

    /** A declared client is untouched — the gate must cost nothing once support is announced. */
    @Test
    fun `a declared client sees the opencode rows unchanged`() {
        val input = listOf(entry(oc("oc-sid", executing = true), claude("cl-sid")))
        assertEquals(input, RequestRouter.filterDirs(input, declared()))
    }

    @Test
    fun `zcode live rows require the independent zcode capability`() {
        val input = listOf(entry(zcode("zc-sid", executing = true), claude("cl-sid")))
        val undeclared = RequestRouter.filterDirs(input, RequestRouter.ClientCapsHolder()).single()
        assertEquals(listOf("cl-sid"), undeclared.activeSessions.map { it.sessionId })
        assertEquals(listOf(AgentKind.CLAUDE), undeclared.sessionAgents)
        assertEquals("cl-sid", undeclared.activeSessionId)
        assertFalse(undeclared.executing)

        val declared = RequestRouter.ClientCapsHolder().apply { supportsZcode = true }
        assertEquals(input, RequestRouter.filterDirs(input, declared))
    }

    @Test
    fun `zcode history provenance is stripped even when there is no live zcode row`() {
        val input = listOf(entry(claude("cl-sid"), sessionAgents = listOf(AgentKind.CLAUDE, AgentKind.ZCODE)))
        val out = RequestRouter.filterDirs(input, RequestRouter.ClientCapsHolder()).single()
        assertEquals(listOf(AgentKind.CLAUDE), out.sessionAgents)
        assertEquals(listOf("cl-sid"), out.activeSessions.map { it.sessionId })
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

    @Test
    fun `approval v2 filtering is independent for mixed attached devices`() {
        val legacy = RequestRouter.ClientCapsHolder()
        val modern = RequestRouter.ClientCapsHolder().apply { supportsApprovalV2 = true }
        val chip = AuthorizedActionRecorded("c1", "e1", "git status", "task-grant", 1L)
        val risk = PermissionRiskUpdated("c1", "a1", "high")

        for (frame in listOf(chip, risk)) {
            assertFalse(RequestRouter.allowedForCaps(frame, legacy), "legacy sibling must not receive ${frame::class.simpleName}")
            assertTrue(RequestRouter.allowedForCaps(frame, modern), "modern sibling should receive ${frame::class.simpleName}")
        }
    }

    @Test
    fun `handoff listings strip only zcode rows for an undeclared client`() {
        val claude = SessionHandoff(id = "h-claude", sourceSessionId = "s-claude", agent = AgentKind.CLAUDE)
        val zcode = SessionHandoff(id = "h-zcode", sourceSessionId = "s-zcode", agent = AgentKind.ZCODE)

        assertEquals(
            listOf(claude),
            RequestRouter.filterHandoffs(listOf(zcode, claude), RequestRouter.ClientCapsHolder()),
        )
        assertEquals(
            listOf(claude),
            RequestRouter.filterHandoffs(listOf(zcode, claude), null),
            "no caps holder is a legacy client and must fail closed",
        )

        val modern = RequestRouter.ClientCapsHolder().apply { supportsZcode = true }
        assertEquals(listOf(zcode, claude), RequestRouter.filterHandoffs(listOf(zcode, claude), modern))
    }

    @Test
    fun `zcode handoff updates are gated independently per connection`() {
        val update = HandoffUpdated(SessionHandoff(id = "h-zcode", sourceSessionId = "s-zcode", agent = AgentKind.ZCODE))
        val legacy = RequestRouter.ClientCapsHolder()
        val modern = RequestRouter.ClientCapsHolder().apply { supportsZcode = true }

        assertFalse(RequestRouter.allowedForCaps(update, legacy))
        assertFalse(RequestRouter.allowedForCaps(update, null), "a legacy ingress without a holder must fail closed")
        assertTrue(RequestRouter.allowedForCaps(update, modern))
        assertTrue(
            RequestRouter.allowedForCaps(
                HandoffUpdated(SessionHandoff(id = "h-claude", sourceSessionId = "s-claude", agent = AgentKind.CLAUDE)),
                legacy,
            ),
            "baseline-agent handoffs remain compatible",
        )
    }

    // ── issue #258: the usage reply's by-model rows carry the same AgentKind vocabulary ──────────

    private fun usage(vararg models: UsageModel) = Usage(
        days = listOf(UsageDay("Mon", 300)),
        models = models.toList(),
        tokensToday = 300,
    )

    /**
     * An undeclared peer gets the post-baseline badges DOWNGRADED to CLAUDE, not dropped: those tokens
     * are already inside the hero total and the trend, so removing the bars would make the page
     * contradict itself. Everything else about the reply must survive untouched.
     */
    @Test
    fun `usage model badges degrade to the baseline agent for an undeclared peer`() {
        val reply = usage(
            UsageModel("claude-opus-5", 100, AgentKind.CLAUDE),
            UsageModel("anthropic/glm-5", 120, AgentKind.ZCODE),
            UsageModel("kimi-code/k3", 80, AgentKind.KIMI),
        )
        for (caps in listOf(null, undeclared())) {
            val gated = RequestRouter.gateUsageAgents(reply, caps)
            assertEquals(3, gated.models.size, "row COUNT never changes — only the badge degrades")
            assertTrue(gated.models.all { it.agent == AgentKind.CLAUDE })
            // the honest parts of the row survive: model id, tokens, and the window statistics
            assertEquals(listOf("claude-opus-5", "anthropic/glm-5", "kimi-code/k3"), gated.models.map { it.model })
            assertEquals(listOf(100L, 120L, 80L), gated.models.map { it.tokens })
            assertEquals(reply.tokensToday, gated.tokensToday)
            assertEquals(reply.days, gated.days)
        }
    }

    @Test
    fun `a peer that declared the vocabulary keeps every badge verbatim`() {
        val reply = usage(
            UsageModel("anthropic/glm-5", 120, AgentKind.ZCODE),
            UsageModel("kimi-code/k3", 80, AgentKind.KIMI),
            UsageModel("openai/gpt-5.1", 60, AgentKind.OPENCODE),
        )
        val modern = RequestRouter.ClientCapsHolder().apply {
            supportsOpencode = true; supportsKimi = true; supportsZcode = true
        }
        assertEquals(reply, RequestRouter.gateUsageAgents(reply, modern), "a declared peer's reply is returned as-is")
    }

    /** A Claude/Codex-only reply is baseline vocabulary, so the gate must not even copy the frame. */
    @Test
    fun `a baseline-only reply is passed through untouched`() {
        val reply = usage(
            UsageModel("claude-opus-5", 100, AgentKind.CLAUDE),
            UsageModel("gpt-5.1-codex", 50, AgentKind.CODEX),
        )
        assertSame(reply, RequestRouter.gateUsageAgents(reply, undeclared()))
    }
}
