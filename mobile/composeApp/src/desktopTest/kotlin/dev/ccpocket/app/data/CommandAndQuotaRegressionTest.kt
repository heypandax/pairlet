package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.*
import kotlinx.coroutines.*
import kotlin.test.*

class CommandAndQuotaRegressionTest {
    @Test fun disconnect_clears_live_redemption_state_but_reconnect_recovers_its_original_identity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sent = mutableListOf<Frame>()
            val repo = repo(scope, sent)
            val binding = repo.paired.value!!
            val quota = ClaudeQuota(agent = AgentKind.CODEX, status = CLAUDE_QUOTA_OK,
                resetCredits = QuotaResetCredits(2))
            repo.quotaByAgent[AgentKind.CODEX] = quota
            assertTrue(repo.redeemQuotaReset("card-a"))
            val original = sent.filterIsInstance<RedeemQuotaReset>().single()
            repo.disconnect()
            assertFalse(repo.quotaResetPending)
            assertFalse(repo.quotaResetBusy.value)
            assertNull(repo.quotaResetOutcome.value)
            assertFalse(repo.quotaResetNeedsRefresh.value)
            repo.receiveForTest(QuotaResetResult(original.requestId, "reset"))
            assertNull(repo.quotaResetOutcome.value, "a reply from the departed computer cannot settle another attempt")
            repo.paired.value = binding
            repo.connected.value = true
            repo.daemonQuotaAgents.value = listOf("codex")
            repo.fetchQuota(AgentKind.CODEX, forceRefresh = true)
            val refresh = sent.filterIsInstance<ClaudeQuotaGet>().last()
            repo.receiveForTest(quota.copy(requestId = refresh.requestId))
            assertTrue(repo.quotaResetPending)
            assertFalse(repo.canRedeemQuotaReset)
            assertTrue(repo.retryQuotaReset())
            assertEquals(listOf(original, original), sent.filterIsInstance<RedeemQuotaReset>())
            repo.receiveForTest(QuotaResetResult(original.requestId, "alreadyRedeemed"))
        } finally { scope.cancel() }
    }

    @Test fun update_does_not_admit_a_redemption_or_hide_it_behind_a_local_timeout() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sent = mutableListOf<Frame>()
            val repo = repo(scope, sent)
            repo.quotaByAgent[AgentKind.CODEX] = ClaudeQuota(agent = AgentKind.CODEX,
                status = CLAUDE_QUOTA_OK, resetCredits = QuotaResetCredits(1))
            repo.daemonUpdateStatus.value = DaemonUpdateStatus("update", "waiting", controlsBlocked = true)
            assertFalse(repo.canRedeemQuotaReset)
            assertFalse(repo.redeemQuotaReset("card-a"))
            assertFalse(repo.quotaResetPending)
            assertFalse(repo.quotaResetBusy.value)
            assertTrue(sent.none { it is RedeemQuotaReset })
        } finally { scope.cancel() }
    }

    @Test fun existing_approvals_and_questions_can_finish_while_new_work_is_paused() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sent = mutableListOf<Frame>()
            val repo = repo(scope, sent)
            repo.daemonUpdateStatus.value = DaemonUpdateStatus("update", "waiting", controlsBlocked = true)
            val approval = PermissionAsk("main", "approval", "Bash", "run tests", timeoutSec = 600)
            repo.pendingAsk.value = approval
            repo.resolve(Decision.ALLOW, ask = approval)
            val question = PermissionAsk("main", "question", "AskUserQuestion", "choose", timeoutSec = 600)
            repo.pendingAsk.value = question
            repo.answerQuestions(mapOf("choice" to "yes"), ask = question)
            repo.resolveAskDirect(approval.copy(convoId = "side", askId = "side-approval"), allow = false)
            repo.answerQuestionsDirect(question.copy(convoId = "side", askId = "side-question"), mapOf("choice" to "no"))
            val verdicts = sent.filterIsInstance<PermissionVerdict>()
            assertEquals(listOf("approval", "question", "side-approval", "side-question"), verdicts.map { it.askId })
            assertEquals(mapOf("choice" to "yes"), verdicts[1].answers)
            assertEquals(Decision.DENY, verdicts[2].decision)
            assertEquals(mapOf("choice" to "no"), verdicts[3].answers)
            assertFalse(repo.sendPrompt("new work"))
            assertTrue(sent.none { it is SendPrompt })
        } finally { scope.cancel() }
    }

    private fun repo(scope: CoroutineScope, sent: MutableList<Frame>) = PocketRepository(scope).also { repo ->
        repo.onSendForTest = { sent += it }
        repo.connected.value = true
        repo.paired.value = PairedDaemon("wss://test", java.util.UUID.randomUUID().toString(), "pk", "device", "credential")
        repo.convoId.value = "main"
        repo.sessionAgent.value = AgentKind.CLAUDE
        repo.daemonQuotaAgents.value = listOf("codex")
    }

    private fun side(repo: PocketRepository, agent: AgentKind = AgentKind.CODEX): SidePane {
        val pane = repo.sidePanes.open("/test", "side-session", "Side", agent, PermissionMode.DEFAULT)!!
        repo.receiveForTest(SessionLive("side", "/test", "side-session", agent = agent, executing = false))
        return pane
    }

    @Test fun unknown_redemption_locks_new_cards_and_explicit_retry_keeps_the_original_intent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sent = mutableListOf<Frame>()
            val repo = repo(scope, sent)
            val quota = ClaudeQuota(agent = AgentKind.CODEX, status = CLAUDE_QUOTA_OK,
                resetCredits = QuotaResetCredits(2, listOf(
                    QuotaResetCredit("card-a", "available", "codexRateLimits"),
                    QuotaResetCredit("card-b", "available", "codexRateLimits"),
                )))
            repo.quotaByAgent[AgentKind.CODEX] = quota
            assertTrue(repo.redeemQuotaReset("card-a"))
            val original = sent.filterIsInstance<RedeemQuotaReset>().single()
            repo.receiveForTest(QuotaResetResult(original.requestId, "unknown"))
            val refresh = sent.filterIsInstance<ClaudeQuotaGet>().last()
            repo.receiveForTest(quota.copy(requestId = refresh.requestId))
            assertFalse(repo.quotaResetBusy.value)
            assertFalse(repo.quotaResetNeedsRefresh.value)
            assertTrue(repo.quotaResetPending)
            assertFalse(repo.canRedeemQuotaReset)
            assertFalse(repo.redeemQuotaReset("card-b"))
            assertEquals(listOf(original), sent.filterIsInstance<RedeemQuotaReset>())
            // The first redemption may have succeeded even when its reply was lost. Zero available
            // credits must not prevent querying that operation through its original idempotency key.
            repo.quotaByAgent[AgentKind.CODEX] = quota.copy(resetCredits = QuotaResetCredits(0))
            assertTrue(repo.retryQuotaReset())
            assertEquals(listOf(original, original), sent.filterIsInstance<RedeemQuotaReset>())
            repo.receiveForTest(QuotaResetResult(original.requestId, "alreadyRedeemed"))
            assertFalse(repo.quotaResetPending)
            val nextRefresh = sent.filterIsInstance<ClaudeQuotaGet>().last()
            repo.receiveForTest(quota.copy(resetCredits = QuotaResetCredits(1), requestId = nextRefresh.requestId))
            assertTrue(repo.canRedeemQuotaReset)
            assertTrue(repo.redeemQuotaReset("card-b"))
            val next = sent.filterIsInstance<RedeemQuotaReset>().last()
            assertEquals("card-b", next.creditId)
            assertNotEquals(original.requestId, next.requestId)
        } finally { scope.cancel() }
    }

    @Test fun side_usage_is_local_and_uses_the_panes_agent_even_during_an_update() = runBlocking {
        for (agent in listOf(AgentKind.CLAUDE, AgentKind.CODEX)) for (updating in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val sent = mutableListOf<Frame>()
                val repo = repo(scope, sent)
                repo.sessionAgent.value = if (agent == AgentKind.CODEX) AgentKind.CLAUDE else AgentKind.CODEX
                val pane = side(repo, agent)
                pane.streaming.value = true // reading usage must not settle the actual running turn
                if (updating) repo.daemonUpdateStatus.value = DaemonUpdateStatus("update", "downloading", controlsBlocked = true)
                sent.clear()
                val before = pane.messages.toList()
                assertTrue(repo.sidePanes.sendPrompt(pane, " /usage "))
                assertTrue(repo.usageSheetRequested.value)
                assertTrue(sent.none { it is SendPrompt })
                assertEquals(agent, sent.filterIsInstance<ClaudeQuotaGet>().last().agent)
                assertEquals(before, pane.messages.toList())
                assertTrue(pane.streaming.value)
            } finally { scope.cancel() }
        }
    }

    @Test fun known_update_refuses_side_sends_and_opens_before_mutating_their_state() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sent = mutableListOf<Frame>()
            val repo = repo(scope, sent)
            val pane = side(repo)
            repo.daemonUpdateStatus.value = DaemonUpdateStatus("update", "downloading", controlsBlocked = true)
            sent.clear()
            val before = pane.messages.toList()
            assertFalse(repo.sidePanes.sendPrompt(pane, "keep this in the composer"))
            assertEquals(before, pane.messages.toList())
            assertFalse(pane.streaming.value)
            assertTrue(sent.none { it is SendPrompt })
            assertNull(repo.sidePanes.open("/other", "other-session", "Other", AgentKind.CODEX, PermissionMode.DEFAULT))
            assertTrue(sent.none { it is OpenSession })
        } finally { scope.cancel() }
    }

    @Test fun a_side_prompt_refused_after_admission_recovers_in_its_own_pane() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sent = mutableListOf<Frame>()
            val repo = repo(scope, sent)
            val pane = side(repo)
            assertTrue(repo.sendPrompt("main task"))
            assertTrue(repo.sidePanes.sendPrompt(pane, "side task"))
            val refused = sent.filterIsInstance<SendPrompt>().last()
            repo.receiveForTest(PocketError("daemon_updating", "updating", convoId = "side", promptId = refused.promptId))
            assertFalse(pane.streaming.value)
            assertTrue(pane.sendStalled.value)
            assertTrue(pane.turnStalled.value)
            assertTrue(repo.streaming.value, "a side refusal must not end the main conversation's turn")
            val before = sent.filterIsInstance<SendPrompt>().size
            val bubbles = pane.messages.size
            repo.sidePanes.resendStalled(pane)
            assertEquals(before, sent.filterIsInstance<SendPrompt>().size)
            repo.receiveForTest(DaemonInfo(daemonVersion = "2.6.1", daemonUpdateMode = "remote"))
            repo.sidePanes.resendStalled(pane)
            assertEquals(before + 1, sent.filterIsInstance<SendPrompt>().size)
            assertEquals("side task", sent.filterIsInstance<SendPrompt>().last().text)
            assertEquals(bubbles, pane.messages.size, "retry must not duplicate the user bubble")
        } finally { scope.cancel() }
    }
}
