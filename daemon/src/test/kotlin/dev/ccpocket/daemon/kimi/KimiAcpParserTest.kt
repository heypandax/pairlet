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
    fun `tool updates are Ignored here — the stateful backend owns them (probe 0_34_0)`() {
        val u = """{"update":{"sessionUpdate":"tool_call","toolCallId":"t1","kind":"execute","title":"Bash"}}"""
        assertIs<AgentEvent.Ignored>(KimiAcpParser.parseLine(u).single())
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
