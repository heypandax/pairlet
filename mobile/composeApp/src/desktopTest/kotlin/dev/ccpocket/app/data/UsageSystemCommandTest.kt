package dev.ccpocket.app.data

import dev.ccpocket.protocol.*
import kotlinx.coroutines.*
import kotlin.test.*

class UsageSystemCommandTest {
    @Test
    fun usage_is_local_for_both_agents_even_while_observing() = runBlocking {
        for (agent in listOf(AgentKind.CLAUDE, AgentKind.CODEX)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val frames = mutableListOf<Frame>()
                val repo = PocketRepository(scope)
                repo.onSendForTest = { frames += it }
                repo.receiveForTest(DaemonInfo(quotaAgents = listOf("claude", "codex")))
                repo.sessionAgent.value = agent
                repo.observing.value = true
                val rows = repo.messages.toList()
                assertTrue(repo.sendPrompt(" /usage "))
                assertTrue(repo.usageSheetRequested.value)
                assertEquals(agent, frames.filterIsInstance<ClaudeQuotaGet>().last().agent)
                assertTrue(frames.none { it is SendPrompt || it is OpenSession })
                assertEquals(rows, repo.messages.toList())
                assertFalse(repo.streaming.value)
            } finally { scope.cancel() }
        }
    }
}
