package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_QUOTA_NETWORK
import dev.ccpocket.protocol.CLAUDE_QUOTA_NO_TOKEN
import dev.ccpocket.protocol.CLAUDE_QUOTA_OK
import dev.ccpocket.protocol.ClaudeQuota
import dev.ccpocket.protocol.ClaudeQuotaGet
import dev.ccpocket.protocol.ClaudeQuotaLimit
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client half of the per-agent allowance contract (issue #348) — the two defences that keep one
 * backend's billing numbers from being drawn under another backend's name.
 *
 * **The gate (outbound).** A non-Claude request goes out ONLY for a backend [DaemonInfo.quotaAgents]
 * advertised. A daemon that predates #348 drops the unknown `agent` key and answers with the CLAUDE
 * allowance; asking it about Codex at all is what creates the mis-attribution risk.
 *
 * **The echo check (inbound).** A [ClaudeQuota] reply carries no request id, so its own `agent` is the
 * only correlation available. A reply is accepted only against an OUTSTANDING request for the agent it
 * names — which drops both the old daemon's claude-labelled answer to a codex-only ask and a reply that
 * arrives after its deadline.
 */
class QuotaAgentRoutingTest {

    private fun repo(): Pair<PocketRepository, MutableList<Frame>> {
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        repo.onSendForTest = { sent += it }
        return repo to sent
    }

    private fun advertise(repo: PocketRepository, vararg wires: String) =
        repo.receiveForTest(DaemonInfo(quotaAgents = wires.toList()))

    private fun codexReading(percent: Int = 50) = ClaudeQuota(
        limits = listOf(ClaudeQuotaLimit(kind = "weekly_all", group = "weekly", percent = percent, resetsAt = 1789179749_000, isActive = true)),
        fetchedAt = 1_700_000_000_000,
        status = CLAUDE_QUOTA_OK,
        agent = AgentKind.CODEX,
        planType = "pro",
    )

    private fun claudeReading(percent: Int = 7) = ClaudeQuota(
        limits = listOf(ClaudeQuotaLimit(kind = "weekly_all", group = "weekly", percent = percent)),
        fetchedAt = 1_700_000_000_000,
        status = CLAUDE_QUOTA_OK,
        agent = AgentKind.CLAUDE,
    )

    // -- the outbound gate --------------------------------------------------------------------------

    @Test
    fun a_codex_request_is_never_sent_to_a_daemon_that_did_not_advertise_codex() {
        val (repo, sent) = repo()
        // an older daemon: no quotaAgents at all
        advertise(repo)
        repo.fetchQuota(AgentKind.CODEX)
        assertTrue(sent.none { it is ClaudeQuotaGet }, "nothing may go out: the daemon would answer with Claude's numbers")
    }

    @Test
    fun claude_is_always_askable_even_when_the_daemon_advertised_nothing() {
        val (repo, sent) = repo()
        advertise(repo)
        repo.fetchQuota(AgentKind.CLAUDE)
        val f = sent.filterIsInstance<ClaudeQuotaGet>().single()
        assertEquals(AgentKind.CLAUDE, f.agent, "the pre-#348 world is Claude-only and still works")
    }

    @Test
    fun an_advertised_codex_is_asked_with_the_codex_agent_on_the_frame() {
        val (repo, sent) = repo()
        advertise(repo, "claude", "codex")
        repo.fetchQuota(AgentKind.CODEX, forceRefresh = true)
        val f = sent.filterIsInstance<ClaudeQuotaGet>().single()
        assertEquals(AgentKind.CODEX, f.agent)
        assertTrue(f.forceRefresh)
    }

    @Test
    fun one_refresh_trigger_asks_every_advertised_backend_exactly_once() {
        val (repo, sent) = repo()
        advertise(repo, "claude", "codex")
        repo.fetchAllQuotas()
        assertEquals(
            listOf(AgentKind.CLAUDE, AgentKind.CODEX),
            sent.filterIsInstance<ClaudeQuotaGet>().map { it.agent },
        )
    }

    // -- the inbound echo check ---------------------------------------------------------------------

    @Test
    fun a_codex_reply_lands_in_the_codex_slot_and_leaves_the_claude_slot_untouched() {
        val (repo, _) = repo()
        advertise(repo, "claude", "codex")
        repo.fetchQuota(AgentKind.CLAUDE)
        repo.fetchQuota(AgentKind.CODEX)
        repo.receiveForTest(claudeReading(7))
        repo.receiveForTest(codexReading(50))
        assertEquals(7, repo.quotaByAgent[AgentKind.CLAUDE]!!.limits.single().percent)
        assertEquals(50, repo.quotaByAgent[AgentKind.CODEX]!!.limits.single().percent)
        assertEquals("pro", repo.quotaByAgent[AgentKind.CODEX]!!.planType)
        // the legacy accessor is a VIEW of the Claude slot, not a third copy
        assertEquals(7, repo.claudeQuota.value!!.limits.single().percent)
    }

    @Test
    fun an_old_daemons_claude_labelled_answer_to_a_codex_only_ask_is_DROPPED() {
        val (repo, _) = repo()
        advertise(repo, "claude", "codex")
        repo.fetchQuota(AgentKind.CODEX) // only Codex is outstanding
        // …and back comes a CLAUDE reading (what a pre-#348 daemon would send)
        repo.receiveForTest(claudeReading(7))
        assertNull(repo.quotaByAgent[AgentKind.CODEX], "Claude's numbers must never fill the Codex slot")
        assertNull(repo.quotaByAgent[AgentKind.CLAUDE], "…nor be adopted as a Claude reading we never asked for")
    }

    @Test
    fun an_entirely_unsolicited_reply_is_dropped() {
        val (repo, _) = repo()
        advertise(repo, "claude", "codex")
        repo.receiveForTest(codexReading())
        assertNull(repo.quotaByAgent[AgentKind.CODEX])
    }

    @Test
    fun a_second_reply_for_a_single_request_is_dropped_as_a_late_duplicate() {
        val (repo, _) = repo()
        advertise(repo, "claude", "codex")
        repo.fetchQuota(AgentKind.CODEX)
        repo.receiveForTest(codexReading(50))
        repo.receiveForTest(codexReading(99))
        assertEquals(50, repo.quotaByAgent[AgentKind.CODEX]!!.limits.single().percent)
    }

    @Test
    fun a_transient_failure_keeps_the_previous_snapshot_but_a_no_token_replaces_it() {
        val (repo, _) = repo()
        advertise(repo, "claude", "codex")
        repo.fetchQuota(AgentKind.CODEX)
        repo.receiveForTest(codexReading(50))

        repo.fetchQuota(AgentKind.CODEX)
        repo.receiveForTest(ClaudeQuota(status = CLAUDE_QUOTA_NETWORK, agent = AgentKind.CODEX, error = "offline"))
        assertEquals(50, repo.quotaByAgent[AgentKind.CODEX]!!.limits.single().percent, "a wifi blip must not blank the bar")

        repo.fetchQuota(AgentKind.CODEX)
        repo.receiveForTest(ClaudeQuota(status = CLAUDE_QUOTA_NO_TOKEN, agent = AgentKind.CODEX))
        assertEquals(CLAUDE_QUOTA_NO_TOKEN, repo.quotaByAgent[AgentKind.CODEX]!!.status)
        assertTrue(repo.quotaByAgent[AgentKind.CODEX]!!.limits.isEmpty(), "signed out is authoritative: the old numbers go")
    }

    // -- the pure agent-list rule -------------------------------------------------------------------

    @Test
    fun quotaAgentsToFetch_keeps_claude_first_and_treats_an_empty_advertisement_as_claude_only() {
        assertEquals(listOf(AgentKind.CLAUDE), quotaAgentsToFetch(emptyList()))
        assertEquals(listOf(AgentKind.CLAUDE), quotaAgentsToFetch(listOf("claude")))
        assertEquals(listOf(AgentKind.CLAUDE, AgentKind.CODEX), quotaAgentsToFetch(listOf("claude", "codex")))
        // Claude leads even when the daemon lists it last, or not at all
        assertEquals(listOf(AgentKind.CLAUDE, AgentKind.CODEX), quotaAgentsToFetch(listOf("codex")))
    }

    @Test
    fun quotaAgentsToFetch_skips_a_name_this_app_build_has_no_enum_value_for() {
        // a newer daemon advertising a backend we cannot label must not produce a request we cannot
        // attribute; and a duplicate must not produce two
        assertEquals(
            listOf(AgentKind.CLAUDE, AgentKind.CODEX),
            quotaAgentsToFetch(listOf("claude", "codex", "codex", "some-future-agent")),
        )
    }
}
