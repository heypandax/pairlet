package dev.ccpocket.app.ui

import dev.ccpocket.app.data.availableAgentsFromDaemon
import dev.ccpocket.protocol.AGENT_WIRE_ZCODE
import dev.ccpocket.protocol.AgentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentAvailabilityUiTest {

    @Test
    fun oldDaemonHidesOnlyZcodeFromAgentPickers() {
        val choices = availableAgentsFromDaemon(emptySet())

        assertFalse(AgentKind.ZCODE in choices)
        assertTrue(AgentKind.CLAUDE in choices)
        assertTrue(AgentKind.CODEX in choices)
        assertTrue(AgentKind.OPENCODE in choices)
        assertTrue(AgentKind.KIMI in choices)
    }

    @Test
    fun zcodeAdvertisementRestoresTheFullPicker() {
        assertEquals(
            AgentKind.entries,
            availableAgentsFromDaemon(setOf(AGENT_WIRE_ZCODE)),
        )
    }
}
