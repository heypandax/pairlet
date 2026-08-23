package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AskQuestions
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The mux ENVELOPE path (issue #291). `translateMux` used to read only `method` + `payload` and drop the
 * envelope's `rpcId` on the floor — which is the one token an ask can be answered with, since the payload
 * carries none. These tests go through the real `parse(line)` seam so a regression that loses it again
 * shows up as a missing card rather than as a silently unanswerable one.
 */
class DshBackendAskTest {

    private val rpcId = "3f1c0a55-0a9e-4d21-9d38-8b7a6e5c4d33"

    private fun backend() = DshBackend(null).apply { bindSessionForTest(SESSION) }

    private fun frame(body: String) = body.trimIndent().replace("\n", " ")

    private fun questionLine(session: String = SESSION) = frame(
        """
        {"type":"server-request","rpcId":"$rpcId","method":"question/requested",
         "payload":{"type":"question/requested","sessionId":"$session","questions":[
           {"id":"q1","question":"Ship it?","options":[{"label":"Yes"},{"label":"No"}]}]}}
        """,
    )

    private fun approvalLine(session: String = SESSION) = frame(
        """
        {"type":"server-request","rpcId":"$rpcId","method":"approval/requested",
         "payload":{"type":"approval/requested","sessionId":"$session","approvalId":"a-1",
                    "toolName":"bash","reason":"wants to write outside the workspace"}}
        """,
    )

    @Test
    fun a_question_frame_becomes_a_control_request_carrying_the_envelope_rpcId() = runBlocking<Unit> {
        val ask = assertIs<AgentEvent.ControlRequest>(backend().parse(questionLine()).single())
        assertEquals("dsh-$rpcId", ask.requestId)
        assertEquals(AskQuestions.TOOL, ask.toolName)
        // and it parses with the shared helper every other backend's questions go through
        assertEquals(1, AskQuestions.parse(ask.input)?.size)
    }

    @Test
    fun an_approval_frame_becomes_a_control_request_named_after_the_dsh_tool() = runBlocking<Unit> {
        val ask = assertIs<AgentEvent.ControlRequest>(backend().parse(approvalLine()).single())
        assertEquals("dsh-$rpcId", ask.requestId)
        assertEquals("bash", ask.toolName)
        assertEquals("wants to write outside the workspace", ask.input?.str("description"))
    }

    /** The mux is multiplexed across every session the host holds, sub-agents included. */
    @Test
    fun another_sessions_ask_never_becomes_a_card() = runBlocking<Unit> {
        // dropped by the backend's own session filter…
        assertTrue(backend().parse(approvalLine("session-someone-else")).isEmpty())
        // …and, for a frame that carries no sessionId at all to filter on, by the ledger's fail-closed
        // check — which is the half that also covers the pre-`session.create` boot window
        val noSession = frame(
            """
            {"type":"server-request","rpcId":"$rpcId","method":"approval/requested",
             "payload":{"type":"approval/requested","approvalId":"a-2","toolName":"bash","reason":"nope"}}
            """,
        )
        assertIs<AgentEvent.Ignored>(backend().parse(noSession).single())
    }

    /** Before `session.create` returns there is nothing to prove an ask is ours against. */
    @Test
    fun no_card_is_dealt_before_the_session_is_open() = runBlocking<Unit> {
        assertIs<AgentEvent.Ignored>(DshBackend(null).parse(approvalLine()).single())
    }

    @Test
    fun a_resolution_for_a_card_we_never_had_is_ignored_not_cancelled() = runBlocking<Unit> {
        val line = frame(
            """
            {"method":"question/resolved","payload":{"type":"question/resolved",
             "sessionId":"$SESSION","questionRpcId":"never-seen","outcome":"answered"}}
            """,
        )
        assertIs<AgentEvent.Ignored>(backend().parse(line).single())
    }

    private companion object {
        const val SESSION = "session-1"
    }
}
