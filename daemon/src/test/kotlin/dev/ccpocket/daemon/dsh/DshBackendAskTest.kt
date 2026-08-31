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
        // …and a frame that carries no sessionId at all still deals no card. It does NOT stay silent
        // though (issue #321): `sessionId` is mandatory in dsh's own schema, so its absence is host
        // breakage rather than somebody else's session — unprovable AND probably ours, the one drop the
        // user has to hear about, because the turn behind it now waits forever.
        val noSession = frame(
            """
            {"type":"server-request","rpcId":"$rpcId","method":"approval/requested",
             "payload":{"type":"approval/requested","approvalId":"a-2","toolName":"bash","reason":"nope"}}
            """,
        )
        assertIs<AgentEvent.AssistantText>(backend().parse(noSession).single())
    }

    /** Before `session.create` returns there is nothing to prove an ask is ours against — so no CARD.
     *  But on a fresh conversation there is nobody else it could belong to either, and dsh will wait on
     *  it forever, so the drop is ANNOUNCED instead of silent (issue #326; same wedge as #321). */
    @Test
    fun no_card_is_dealt_before_the_session_is_open_but_the_drop_is_announced() = runBlocking<Unit> {
        val out = DshBackend(null).parse(approvalLine()).single()
        val notice = assertIs<AgentEvent.AssistantText>(out)
        assertTrue("approval" in notice.text) // hangNotice keys its wording off the method kind
    }

    /**
     * issue #321. A RESUME is the one case where a still-pending ask can exist BEFORE `session.create`
     * lands `sessionId`: the host replays undecided `…/requested` frames the moment a mux subscribes. The
     * resumed id is provably ours, so the card must be dealt rather than dropped into a turn that then
     * waits forever (dsh has no timeout of its own).
     */
    @Test
    fun a_resumed_session_claims_an_ask_that_beats_session_create() = runBlocking<Unit> {
        val backend = DshBackend(null).apply { bindResumeForTest(SESSION) } // note: no bindSessionForTest
        val ask = assertIs<AgentEvent.ControlRequest>(backend.parse(questionLine()).single())
        assertEquals("dsh-$rpcId", ask.requestId)
    }

    /** …but only for the resumed id: another session's ask still gets nothing (fail-closed). */
    @Test
    fun a_resumed_session_still_refuses_a_foreign_ask() = runBlocking<Unit> {
        val backend = DshBackend(null).apply { bindResumeForTest(SESSION) }
        assertTrue(backend.parse(approvalLine("session-someone-else")).isEmpty())
    }

    /**
     * issue #321. An ask we cannot answer does not "degrade" — it WEDGES the dsh turn permanently, and a
     * daemon log line is invisible from a phone. Every drop that leaves a turn hanging must reach the chat.
     */
    @Test
    fun an_unanswerable_ask_says_so_in_the_chat_instead_of_hanging_silently() = runBlocking<Unit> {
        // no rpcId → nothing to answer against
        val noRpc = frame(
            """
            {"type":"server-request","method":"question/requested",
             "payload":{"type":"question/requested","sessionId":"$SESSION","questions":[
               {"id":"q1","question":"Ship it?","options":[{"label":"Yes"}]}]}}
            """,
        )
        assertIs<AgentEvent.AssistantText>(backend().parse(noRpc).single())

        // an empty question batch is equally unanswerable
        val noQuestions = frame(
            """
            {"type":"server-request","rpcId":"$rpcId","method":"question/requested",
             "payload":{"type":"question/requested","sessionId":"$SESSION","questions":[]}}
            """,
        )
        assertIs<AgentEvent.AssistantText>(backend().parse(noQuestions).single())
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
