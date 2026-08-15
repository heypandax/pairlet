package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.availableAgentsFromDaemon
import dev.ccpocket.protocol.AGENT_WIRE_DSH
import dev.ccpocket.protocol.AGENT_WIRE_ZCODE
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DAEMON_SUPPORTED_AGENT_WIRES
import dev.ccpocket.protocol.DaemonInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #252: the desktop agent pickers (New session + Settings > Default agent) once read a static
 * desktop-only whitelist, so Kimi — shipped, supported by the daemon, selectable on mobile — was
 * unreachable from the desktop app. Both surfaces now read [DesktopModel.availableAgents], which is
 * the SAME projection mobile uses ([availableAgentsFromDaemon]: the whole enum minus what this daemon
 * can't take). These tests pin that: a new [AgentKind] must show up on desktop without touching any
 * desktop file, and if someone reintroduces a hand-maintained list it turns red here.
 */
class DesktopAgentChoicesTest {

    private fun model(scope: CoroutineScope): Pair<PocketRepository, RepoDesktopModel> {
        val repo = PocketRepository(scope)
        // FakeDesktopStore: never touch the developer's real store file from tests (issue #102)
        return repo to RepoDesktopModel(repo, scope, store = FakeDesktopStore())
    }

    @Test
    fun currentDaemonOffersEveryAgentKind() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val (repo, model) = model(scope)
            repo.receiveForTest(DaemonInfo(supportedAgents = DAEMON_SUPPORTED_AGENT_WIRES))

            assertEquals(AgentKind.entries, model.availableAgents)
            assertTrue(AgentKind.KIMI in model.availableAgents, "Kimi must be selectable on desktop")
            // issue #255: DSH was added to the enum with NO desktop file touched — this is the assertion
            // that proves the #252 projection really is the single source of desktop candidates.
            assertTrue(AgentKind.DSH in model.availableAgents, "DeepSeek Harness must be selectable on desktop")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun seedAndPreviewModelsAlsoCoverTheWholeEnum() {
        // The interface default backs previews/screenshots; a whitelist there would hide agents too.
        assertEquals(AgentKind.entries, SeedDesktopModel().availableAgents)
    }

    @Test
    fun choicesAreExactlyTheSharedProjectionNotADesktopList() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val (repo, model) = model(scope)

            // Old daemon: nothing advertised — deny-by-omission drops the post-baseline ZCode and DSH.
            assertEquals(availableAgentsFromDaemon(emptySet()), model.availableAgents)
            assertEquals(model.availableAgents, repo.availableAgents, "desktop must not diverge from mobile")
            assertTrue(AgentKind.KIMI in model.availableAgents, "Kimi predates the advertisement")
            assertTrue(AgentKind.ZCODE !in model.availableAgents)
            assertTrue(AgentKind.DSH !in model.availableAgents, "dsh is post-baseline: hidden until advertised")

            repo.receiveForTest(DaemonInfo(supportedAgents = listOf(AGENT_WIRE_ZCODE)))

            assertEquals(availableAgentsFromDaemon(setOf(AGENT_WIRE_ZCODE)), model.availableAgents)
            assertEquals(model.availableAgents, repo.availableAgents, "desktop must not diverge from mobile")
            assertTrue(AgentKind.ZCODE in model.availableAgents)
            // each post-baseline agent is gated INDEPENDENTLY — advertising ZCode must not smuggle in DSH
            assertTrue(AgentKind.DSH !in model.availableAgents)

            repo.receiveForTest(DaemonInfo(supportedAgents = listOf(AGENT_WIRE_ZCODE, AGENT_WIRE_DSH)))
            assertTrue(AgentKind.DSH in model.availableAgents)
        } finally {
            scope.cancel()
        }
    }
}
