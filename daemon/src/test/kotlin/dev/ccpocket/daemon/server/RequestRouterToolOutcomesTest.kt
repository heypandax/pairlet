package dev.ccpocket.daemon.server

import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #380 live folding: the bare outcome RESULT is opt-in per connection; every other tool frame is not gated. */
class RequestRouterToolOutcomesTest {
    private val outcome = ToolEvent("c", 2, ToolPhase.RESULT, "Bash", ok = true, toolUseId = "t1", outcomeOnly = true)
    private val start = ToolEvent("c", 1, ToolPhase.START, "Bash", "ls", toolUseId = "t1")
    private val subagent = ToolEvent("c", 3, ToolPhase.RESULT, "Agent", ok = true, toolUseId = "a1", output = "report")
    private val picture = ToolEvent("c", 4, ToolPhase.RESULT, "Read", ok = true, toolUseId = "r1", images = listOf(ImageData("image/png", "AA==")))

    @Test
    fun a_bare_outcome_reaches_only_a_connection_that_declared_it() {
        assertFalse(RequestRouter.allowedForCaps(outcome, null), "no holder = fail closed")
        assertFalse(RequestRouter.allowedForCaps(outcome, RequestRouter.ClientCapsHolder()), "undeclared = fail closed")
        assertTrue(RequestRouter.allowedForCaps(outcome, RequestRouter.ClientCapsHolder().apply { supportsToolOutcomes = true }))
    }

    @Test
    fun the_pre_existing_tool_frames_are_not_gated() {
        for (frame in listOf(start, subagent, picture)) {
            assertTrue(RequestRouter.allowedForCaps(frame, null), "${'$'}{frame.phase} ${'$'}{frame.tool} to a legacy ingress")
            assertTrue(RequestRouter.allowedForCaps(frame, RequestRouter.ClientCapsHolder()))
        }
    }
}
