package dev.ccpocket.app.data

import dev.ccpocket.protocol.AGENT_WIRE_DSH
import dev.ccpocket.protocol.AGENT_WIRE_ZCODE
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonAgentCapabilitiesTest {

    @Test
    fun zcodeOpenIsRefusedUntilDaemonAdvertisesIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(scope).apply { onSendForTest = { sent += it } }
        try {
            assertFalse(repo.supportsAgent(AgentKind.ZCODE))
            assertFalse(repo.openSession("/tmp/old-daemon", agent = AgentKind.ZCODE))
            assertTrue(sent.filterIsInstance<OpenSession>().isEmpty())
            assertFalse(repo.opening.value, "a locally refused open must not leave a loading state")

            repo.receiveForTest(DaemonInfo(supportedAgents = listOf(AGENT_WIRE_ZCODE)))

            assertTrue(repo.supportsAgent(AgentKind.ZCODE))
            assertTrue(repo.openSession("/tmp/new-daemon", agent = AgentKind.ZCODE))
            assertEquals(AgentKind.ZCODE, sent.filterIsInstance<OpenSession>().single().agent)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun dshOpenIsRefusedUntilDaemonAdvertisesIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(scope).apply { onSendForTest = { sent += it } }
        try {
            assertFalse(repo.supportsAgent(AgentKind.DSH))
            assertFalse(repo.openSession("/tmp/old-daemon", agent = AgentKind.DSH))
            assertTrue(sent.filterIsInstance<OpenSession>().isEmpty())
            assertFalse(repo.opening.value, "a locally refused open must not leave a loading state")

            repo.receiveForTest(DaemonInfo(supportedAgents = listOf(AGENT_WIRE_DSH)))

            assertTrue(repo.supportsAgent(AgentKind.DSH))
            assertTrue(repo.openSession("/tmp/new-daemon", agent = AgentKind.DSH))
            assertEquals(AgentKind.DSH, sent.filterIsInstance<OpenSession>().single().agent)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun legacyAgentsRemainSendableWithoutAnAdvertisement() {
        // ZCode and DSH are the two post-baseline agents: both are deny-by-omission, so neither belongs
        // in the set that an old daemon must still accept.
        AgentKind.entries.filterNot { it == AgentKind.ZCODE || it == AgentKind.DSH }.forEach { agent ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val sent = mutableListOf<Frame>()
            val repo = PocketRepository(scope).apply { onSendForTest = { sent += it } }
            try {
                assertTrue(repo.openSession("/tmp/${agent.name.lowercase()}", agent = agent), agent.name)
                assertEquals(agent, sent.filterIsInstance<OpenSession>().single().agent)
            } finally {
                scope.cancel()
            }
        }
    }
}
