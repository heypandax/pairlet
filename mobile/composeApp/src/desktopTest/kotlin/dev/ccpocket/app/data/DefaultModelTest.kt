package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AGENT_WIRE_ZCODE
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultModelTest {

    /**
     * Issue #199: a model picked in the new-session step outranks the Settings default for THAT session —
     * and stays out of the store, so the next new session is back on the default. The regression this
     * guards is the easy one to write: threading the pick into openSession but letting the default win
     * (or quietly persisting the pick, which the issue rules out as a project-level memory).
     */
    @Test
    fun newSessionModelPickBeatsDefaultAndIsNotPersisted() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        val sent = mutableListOf<Frame>()
        try {
            repo.setDefaultModelFor(AgentKind.CLAUDE, "sonnet")
            repo.onSendForTest = { sent += it }

            repo.openSession("/tmp/project", startModel = "haiku")
            assertEquals("haiku", sent.filterIsInstance<OpenSession>().single().model)
            assertEquals("sonnet", repo.defaultModelFor(AgentKind.CLAUDE), "the pick must not become the default")

            sent.clear()
            repo.openSession("/tmp/other")
            assertEquals("sonnet", sent.filterIsInstance<OpenSession>().single().model, "the next session follows the default again")
        } finally {
            repo.setDefaultModelFor(AgentKind.CLAUDE, null)
            scope.cancel()
        }
    }

    /** A pick the backend can't run is dropped by the same compatibility guard the rest of the ladder
     *  uses — the new-session row must not be a way around it (a Claude alias would hang Codex). */
    @Test
    fun newSessionModelPickStillHonorsTheAgentGuard() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        val sent = mutableListOf<Frame>()
        try {
            repo.onSendForTest = { sent += it }
            repo.openSession("/tmp/project", agent = AgentKind.CODEX, startModel = "sonnet")
            assertEquals(null, sent.filterIsInstance<OpenSession>().single().model)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun codexDefaultSeedsNewCodexSession() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        val sent = mutableListOf<Frame>()
        try {
            repo.setDefaultModelFor(AgentKind.CODEX, "gpt-5.1-codex")
            repo.onSendForTest = { sent += it }

            repo.openSession("/tmp/project", agent = AgentKind.CODEX)

            val open = sent.filterIsInstance<OpenSession>().single()
            assertEquals(AgentKind.CODEX, open.agent)
            assertEquals("gpt-5.1-codex", open.model)
            assertEquals("gpt-5.1-codex", repo.model.value)
        } finally {
            repo.setDefaultModelFor(AgentKind.CODEX, null)
            scope.cancel()
        }
    }

    @Test
    fun zcodeDefaultIsBackendScopedAndSeedsNewZcodeSessions() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        val sent = mutableListOf<Frame>()
        try {
            repo.receiveForTest(DaemonInfo(supportedAgents = listOf(AGENT_WIRE_ZCODE)))
            repo.setDefaultModelFor(AgentKind.CODEX, "gpt-5.1-codex")
            repo.setDefaultModelFor(AgentKind.ZCODE, "zai/glm-5")
            repo.onSendForTest = { sent += it }

            repo.openSession("/tmp/zcode-project", agent = AgentKind.ZCODE)

            val open = sent.filterIsInstance<OpenSession>().single()
            assertEquals(AgentKind.ZCODE, open.agent)
            assertEquals("zai/glm-5", open.model)
            assertEquals("zai/glm-5", repo.defaultModelFor(AgentKind.ZCODE))
            assertEquals("gpt-5.1-codex", repo.defaultModelFor(AgentKind.CODEX), "ZCode must not share another backend's storage key")
        } finally {
            repo.setDefaultModelFor(AgentKind.ZCODE, null)
            repo.setDefaultModelFor(AgentKind.CODEX, null)
            scope.cancel()
        }
    }
}
