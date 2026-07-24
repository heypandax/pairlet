package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultModelTest {

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
}
