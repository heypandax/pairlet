package dev.ccpocket.app.ui

import dev.ccpocket.app.data.agentAvailableFromDaemon
import dev.ccpocket.app.ui.entry.agentDefaultMode
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DAEMON_SUPPORTED_AGENT_WIRES
import dev.ccpocket.protocol.PermissionMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `when`s that used to end in a permissive `else` (agent availability, the agent glyph, the fallback
 * mode rung) are exhaustive now, so a new [AgentKind] fails the build until it is placed. These pin the
 * six backends' results to exactly what the `else` versions produced, for an older daemon that advertises
 * nothing and for today's daemon that advertises every wire it routes.
 */
class AgentDefaultBranchesTest {

    @Test
    fun availability_matches_the_former_else_true_for_every_backend() {
        val oldDaemon = emptySet<String>()
        val currentDaemon = DAEMON_SUPPORTED_AGENT_WIRES.toSet()
        val expectedOld = mapOf(
            AgentKind.CLAUDE to true, AgentKind.CODEX to true, AgentKind.OPENCODE to true,
            AgentKind.KIMI to true, AgentKind.ZCODE to false, AgentKind.DSH to false,
        )
        assertEquals(AgentKind.entries.toSet(), expectedOld.keys, "the table must name every backend")
        for (agent in AgentKind.entries) {
            assertEquals(expectedOld.getValue(agent), agentAvailableFromDaemon(agent, oldDaemon), "$agent / old daemon")
            assertEquals(true, agentAvailableFromDaemon(agent, currentDaemon), "$agent / current daemon")
        }
    }

    @Test
    fun fallback_mode_rung_is_unchanged_for_every_backend() {
        for (agent in AgentKind.entries) {
            val expected = if (agent == AgentKind.OPENCODE) PermissionMode.BYPASS_PERMISSIONS else PermissionMode.DEFAULT
            assertEquals(expected, agentDefaultMode(agent), "$agent")
        }
    }
}
