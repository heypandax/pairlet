package dev.ccpocket.app.ui

import dev.ccpocket.app.data.availableAgentsFromDaemon
import dev.ccpocket.protocol.AGENT_WIRE_DSH
import dev.ccpocket.protocol.AGENT_WIRE_ZCODE
import dev.ccpocket.protocol.AgentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentAvailabilityUiTest {

    @Test
    fun oldDaemonHidesPostBaselineAgentsFromAgentPickers() {
        val choices = availableAgentsFromDaemon(emptySet())

        assertFalse(AgentKind.ZCODE in choices)
        assertFalse(AgentKind.DSH in choices, "dsh is post-baseline too — deny by omission (issue #255)")
        assertTrue(AgentKind.CLAUDE in choices)
        assertTrue(AgentKind.CODEX in choices)
        assertTrue(AgentKind.OPENCODE in choices)
        assertTrue(AgentKind.KIMI in choices)
    }

    @Test
    fun advertisingEveryGatedWireRestoresTheFullPicker() {
        assertEquals(
            AgentKind.entries,
            availableAgentsFromDaemon(setOf(AGENT_WIRE_ZCODE, AGENT_WIRE_DSH)),
        )
    }

    @Test
    fun eachGatedWireOnlyUnlocksItsOwnAgent() {
        // A daemon that ships zcode but predates dsh (or the reverse) must not have the other ride along:
        // the whole point of the advertisement is that one gate never speaks for another.
        val zcodeOnly = availableAgentsFromDaemon(setOf(AGENT_WIRE_ZCODE))
        assertTrue(AgentKind.ZCODE in zcodeOnly)
        assertFalse(AgentKind.DSH in zcodeOnly)

        val dshOnly = availableAgentsFromDaemon(setOf(AGENT_WIRE_DSH))
        assertTrue(AgentKind.DSH in dshOnly)
        assertFalse(AgentKind.ZCODE in dshOnly)
    }
}
