package dev.ccpocket.app.ui.handoff

import dev.ccpocket.protocol.AgentKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandoffAgentSupportTest {
    @Test
    fun `zcode and dsh hide the owner-side handoff entry while baseline agents keep it`() {
        assertFalse(AgentKind.ZCODE.canInitiateSessionHandoff())
        // issue #255: v1 bridges no approvals for dsh, so a guest could not be gated at all.
        assertFalse(AgentKind.DSH.canInitiateSessionHandoff())
        assertTrue(AgentKind.CLAUDE.canInitiateSessionHandoff())
        assertTrue(AgentKind.CODEX.canInitiateSessionHandoff())
    }
}
