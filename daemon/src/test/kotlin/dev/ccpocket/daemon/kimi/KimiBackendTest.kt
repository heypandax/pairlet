package dev.ccpocket.daemon.kimi

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives [KimiBackend] with synthetic ACP JSON-RPC lines (no real `kimi` binary) to lock down the
 * probe-verified 0.34.0 behaviors (2026-08-08): the mid-turn prompt FIFO (ACP has no stdin queue —
 * `-32600 turn.agent_busy`), the streamed-cumulative tool input (no `rawInput` on `tool_call`), the
 * `rawOutput` string result, and the permission card's content-text description.
 * Request ids are deterministic (idSeq starts at 1: initialize=1, session/new=2, first prompt=3, …).
 */
class KimiBackendTest {

    private fun update(sessionUpdate: String) =
        """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":$sessionUpdate}}"""

    /** attach + handshake through to a live session "s1"; [w] collects every line the backend writes. */
    private suspend fun ready(w: MutableList<String>): KimiBackend {
        val b = KimiBackend(null)
        b.attach(AgentIo(writeLine = { w += it }, emit = {}), AgentSpec(Path.of("/repo"), mode = PermissionMode.DEFAULT))
        b.parse("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""")          // → session/new (id 2)
        b.parse("""{"jsonrpc":"2.0","id":2,"result":{"sessionId":"s1"}}""")             // session live
        return b
    }

    @Test
    fun `mid-turn prompt is FIFO-queued and flushed on settle, never errored`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.sendPrompt("first", emptyList())
        assertEquals(1, w.count { "\"session/prompt\"" in it }, "first prompt goes straight out")
        b.sendPrompt("second", emptyList())
        b.sendPrompt("third", emptyList())
        assertEquals(1, w.count { "\"session/prompt\"" in it }, "mid-turn prompts must queue (ACP -32600 otherwise)")
        // turn settles → oldest queued flushes
        b.parse("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}""")
        assertEquals(2, w.count { "\"session/prompt\"" in it })
        assertTrue(w.last { "\"session/prompt\"" in it }.contains("second"))
        // next settle → last queued flushes
        b.parse("""{"jsonrpc":"2.0","id":4,"result":{"stopReason":"cancelled"}}""")
        assertEquals(3, w.count { "\"session/prompt\"" in it })
        assertTrue(w.last { "\"session/prompt\"" in it }.contains("third"))
    }

    @Test
    fun `queued prompt flushes even when the in-flight one errors`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.sendPrompt("first", emptyList())
        b.sendPrompt("second", emptyList())
        val events = b.parse("""{"jsonrpc":"2.0","id":3,"error":{"code":-32001,"message":"auth required"}}""")
        assertTrue(events.any { it is AgentEvent.TurnResult && it.isError })
        assertEquals(2, w.count { "\"session/prompt\"" in it }, "an error must not stall the FIFO")
    }

    @Test
    fun `tool start waits for the streamed input and result carries rawOutput`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        // tool_call: pending, NO rawInput (probe 0.34.0) → nothing emitted yet
        val ev0 = b.parse(update("""{"sessionUpdate":"tool_call","toolCallId":"0:tool_A","title":"Bash","kind":"execute","status":"pending","content":[{"type":"content","content":{"type":"text","text":""}}]}"""))
        assertTrue(ev0.isEmpty(), "no START before the input is known (empty card bug)")
        // in_progress: input JSON streams in cumulatively, still incomplete → nothing
        val ev1 = b.parse(update("""{"sessionUpdate":"tool_call_update","toolCallId":"0:tool_A","status":"in_progress","content":[{"type":"content","content":{"type":"text","text":"{\"command\":\"echo"}}]}"""))
        assertTrue(ev1.isEmpty())
        // in_progress: input completes → START with the parsed input
        val ev2 = b.parse(update("""{"sessionUpdate":"tool_call_update","toolCallId":"0:tool_A","status":"in_progress","content":[{"type":"content","content":{"type":"text","text":"{\"command\":\"echo hi\"}"}}]}"""))
        val start = ev2.single() as? AgentEvent.AssistantToolUse
        assertNotNull(start)
        assertEquals("Bash", start.name)
        assertEquals("\"echo hi\"", start.input?.get("command").toString())
        // settled: rawOutput is a plain STRING → ToolResult
        val ev3 = b.parse(update("""{"sessionUpdate":"tool_call_update","toolCallId":"0:tool_A","status":"completed","content":[{"type":"content","content":{"type":"text","text":"hi\n"}}],"rawOutput":"hi\n"}"""))
        val result = ev3.single() as? AgentEvent.ToolResult
        assertNotNull(result)
        assertEquals("0:tool_A", result.toolUseId)
        assertEquals("hi\n", result.content)
        assertTrue(!result.isError)
    }

    @Test
    fun `settle without a complete input still opens the card (title fallback) then fails`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        b.parse(update("""{"sessionUpdate":"tool_call","toolCallId":"0:tool_B","title":"Read","kind":"read","status":"pending"}"""))
        val events = b.parse(update("""{"sessionUpdate":"tool_call_update","toolCallId":"0:tool_B","status":"failed","content":[{"type":"content","content":{"type":"text","text":"\"README.md\" does not exist."}}],"rawOutput":"\"README.md\" does not exist."}"""))
        assertIs<AgentEvent.AssistantToolUse>(events[0])
        assertEquals("Read", (events[0] as AgentEvent.AssistantToolUse).name)
        val result = events[1] as? AgentEvent.ToolResult
        assertNotNull(result)
        assertTrue(result.isError)
        assertEquals("\"README.md\" does not exist.", result.content)
    }

    @Test
    fun `permission card surfaces the content-text description`() = runBlocking {
        val w = mutableListOf<String>()
        val b = ready(w)
        val events = b.parse(
            """{"jsonrpc":"2.0","id":0,"method":"session/request_permission","params":{"sessionId":"s1","options":[{"optionId":"approve_once","name":"Approve once","kind":"allow_once"},{"optionId":"reject","name":"Reject","kind":"reject_once"}],"toolCall":{"toolCallId":"0:tool_A","title":"Bash","content":[{"type":"content","content":{"type":"text","text":"Requesting approval to Running: echo hi"}}]}}}""",
        )
        val ask = events.single() as? AgentEvent.ControlRequest
        assertNotNull(ask)
        assertEquals("Bash", ask.toolName)
        assertEquals("Requesting approval to Running: echo hi", ask.input?.get("description").toString().trim('"'))
        b.respondPermission(ask.requestId, allow = true, remember = false, null, null, null)
        assertTrue(w.last().contains("approve_once"), w.last())
    }
}
