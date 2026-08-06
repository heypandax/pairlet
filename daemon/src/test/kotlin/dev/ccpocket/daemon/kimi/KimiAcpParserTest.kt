package dev.ccpocket.daemon.kimi

import dev.ccpocket.daemon.agent.AgentEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Unit-tests the ACP `session/update` → AgentEvent translation (issue #206). Pure, no process/auth. */
class KimiAcpParserTest {

    @Test
    fun `empty and blank lines produce nothing`() {
        assertTrue(KimiAcpParser.parseLine("").isEmpty())
        assertTrue(KimiAcpParser.parseLine("   ").isEmpty())
    }

    @Test
    fun `invalid json is unparseable, never throws`() {
        val r = KimiAcpParser.parseLine("not json {")
        assertEquals(1, r.size)
        assertIs<AgentEvent.Unparseable>(r[0])
    }

    @Test
    fun `agent_message_chunk becomes assistant text`() {
        val line = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hello"}}}}"""
        val r = KimiAcpParser.parseLine(line)
        val ev = r.single() as? AgentEvent.AssistantText
        assertNotNull(ev)
        assertEquals("hello", ev.text)
    }

    @Test
    fun `agent_thought_chunk becomes thinking`() {
        val u = """{"update":{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"pondering"}}}"""
        val ev = KimiAcpParser.parseLine(u).single() as? AgentEvent.AssistantThinking
        assertNotNull(ev)
        assertEquals("pondering", ev.text)
    }

    @Test
    fun `user_message_chunk is a UserReplay receipt`() {
        val u = """{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"do it"}}"""
        val ev = KimiAcpParser.parseLine(u).single() as? AgentEvent.UserReplay
        assertNotNull(ev)
        assertEquals("do it", ev.text)
    }

    @Test
    fun `tool_call maps kind execute to Bash and carries rawInput`() {
        val u = """{"update":{"sessionUpdate":"tool_call","toolCallId":"t1","kind":"execute","title":"Run echo","rawInput":{"command":"echo hi"}}}"""
        val ev = KimiAcpParser.parseLine(u).single() as? AgentEvent.AssistantToolUse
        assertNotNull(ev)
        assertEquals("t1", ev.id)
        assertEquals("Bash", ev.name) // execute → Bash via ToolNameMapper
        assertEquals("echo hi", ev.input?.get("command").toString().trim('"'))
    }

    @Test
    fun `tool_call_update completed becomes a tool result`() {
        val u = """{"update":{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed","content":[{"type":"text","text":"hi"}]}}"""
        val ev = KimiAcpParser.parseLine(u).single() as? AgentEvent.ToolResult
        assertNotNull(ev)
        assertEquals("t1", ev.toolUseId)
        assertTrue(!ev.isError)
    }

    @Test
    fun `tool_call_update failed marks error`() {
        val u = """{"update":{"sessionUpdate":"tool_call_update","toolCallId":"t9","status":"failed"}}"""
        val ev = KimiAcpParser.parseLine(u).single() as? AgentEvent.ToolResult
        assertNotNull(ev)
        assertTrue(ev.isError)
    }

    @Test
    fun `unknown session update degrades to Ignored`() {
        val u = """{"update":{"sessionUpdate":"some_future_thing","payload":{}}}"""
        assertIs<AgentEvent.Ignored>(KimiAcpParser.parseLine(u).single())
    }

    @Test
    fun `plan is ignored in P1`() {
        val u = """{"update":{"sessionUpdate":"plan","entries":[]}}"""
        assertIs<AgentEvent.Ignored>(KimiAcpParser.parseLine(u).single())
    }
}
