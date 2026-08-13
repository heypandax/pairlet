package dev.ccpocket.app.ui.handoff

import dev.ccpocket.protocol.AgentKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandoffAgentSupportTest {
    @Test
    fun `zcode hides the owner-side handoff entry while baseline agents keep it`() {
        assertFalse(AgentKind.ZCODE.canInitiateSessionHandoff())
        assertTrue(AgentKind.CLAUDE.canInitiateSessionHandoff())
        assertTrue(AgentKind.CODEX.canInitiateSessionHandoff())
    }
}
