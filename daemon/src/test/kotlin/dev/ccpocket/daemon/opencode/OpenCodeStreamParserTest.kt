package dev.ccpocket.daemon.opencode

import dev.ccpocket.daemon.agent.AgentEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenCodeStreamParserTest {

    @Test
    fun `empty line returns empty list`() {
        assertTrue(OpenCodeStreamParser.parse("").isEmpty())
        assertTrue(OpenCodeStreamParser.parse("  ").isEmpty())
        assertTrue(OpenCodeStreamParser.parse("\n").isEmpty())
    }

    @Test
    fun `invalid json returns unparseable`() {
        val result = OpenCodeStreamParser.parse("not json")
        assertEquals(1, result.size)
        assertIs<AgentEvent.Unparseable>(result[0])
    }

    @Test
    fun `step_start emits session init`() {
        val line = """{"type":"step_start","part":{"sessionID":"sess_abc123"}}"""
        val result = OpenCodeStreamParser.parse(line)
        assertEquals(1, result.size)
        val event = result[0] as? AgentEvent.SessionInit
        assertNotNull(event)
        assertEquals("sess_abc123", event.sessionId)
    }

    @Test
    fun `step_start without session id returns empty`() {
        val line = """{"type":"step_start","part":{}}"""
        assertTrue(OpenCodeStreamParser.parse(line).isEmpty())
    }

    @Test
    fun `text emits assistant text`() {
        val line = """{"type":"text","part":{"text":"Hello, world!"}}"""
        val result = OpenCodeStreamParser.parse(line)
        assertEquals(1, result.size)
        val event = result[0] as? AgentEvent.AssistantText
        assertNotNull(event)
        assertEquals("Hello, world!", event.text)
    }

    @Test
    fun `completed tool use emits tool use and tool result`() {
        val line = """{"type":"tool_use","part":{"tool":"bash","callID":"call_1","state":{"status":"completed","input":{"command":"ls"},"output":"file1.txt"}}}"""
        val result = OpenCodeStreamParser.parse(line)
        assertEquals(2, result.size)
        assertIs<AgentEvent.AssistantToolUse>(result[0])
        assertIs<AgentEvent.ToolResult>(result[1])
        assertEquals("call_1", (result[0] as AgentEvent.AssistantToolUse).id)
    }

    @Test
    fun `incomplete tool use emits only tool use`() {
        val line = """{"type":"tool_use","part":{"tool":"read","callID":"call_2","state":{"input":{"file_path":"/tmp/test.txt"}}}}"""
        val result = OpenCodeStreamParser.parse(line)
        assertEquals(1, result.size)
        assertIs<AgentEvent.AssistantToolUse>(result[0])
    }

    @Test
    fun `step_finish with stop reason emits turn result with usage`() {
        val line = """{"type":"step_finish","part":{"reason":"stop","tokens":{"input":100,"output":50,"cache":{"read":10}}}}"""
        val result = OpenCodeStreamParser.parse(line)
        assertEquals(1, result.size)
        val event = result[0] as? AgentEvent.TurnResult
        assertNotNull(event)
        assertNotNull(event.usage)
        assertEquals(100L, event.usage?.inputTokens)
        assertEquals(50L, event.usage?.outputTokens)
        assertEquals(10L, event.usage?.cacheReadInputTokens)
    }

    @Test
    fun `step_finish with tool-calls reason returns ignored`() {
        val line = """{"type":"step_finish","part":{"reason":"tool-calls"}}"""
        val result = OpenCodeStreamParser.parse(line)
        assertEquals(1, result.size)
        assertIs<AgentEvent.Ignored>(result[0])
    }

    @Test
    fun `error emits assistant text with warning`() {
        val line = """{"type":"error","error":{"name":"ModelError","data":{"message":"model not available"}}}"""
        val result = OpenCodeStreamParser.parse(line)
        assertEquals(1, result.size)
        val event = result[0] as? AgentEvent.AssistantText
        assertNotNull(event)
        assertTrue(event.text.contains("model not available"))
    }

    @Test
    fun `unknown type returns ignored`() {
        val line = """{"type":"unknown_event","data":{}}"""
        val result = OpenCodeStreamParser.parse(line)
        assertEquals(1, result.size)
        assertIs<AgentEvent.Ignored>(result[0])
    }

    @Test
    fun `tool use maps to claude shaped input`() {
        val line = """{"type":"tool_use","part":{"tool":"bash","callID":"c1","state":{"status":"completed","input":{"command":"ls -la","description":"list files"},"output":"total 42"}}}"""
        val result = OpenCodeStreamParser.parse(line)
        val toolUse = result[0] as? AgentEvent.AssistantToolUse ?: return
        assertNotNull(toolUse.input)
        assertEquals("ls -la", toolUse.input["command"]?.toString()?.trim('"'))
        assertEquals("list files", toolUse.input["description"]?.toString()?.trim('"'))
    }

    @Test
    fun `all known tool types are parseable`() {
        val tools = listOf("bash", "read", "write", "edit", "glob", "grep", "webfetch", "websearch", "task")
        for (tool in tools) {
            val line = """{"type":"tool_use","part":{"tool":"$tool","callID":"c_$tool","state":{"status":"completed","input":{},"output":"ok"}}}"""
            val result = OpenCodeStreamParser.parse(line)
            assertEquals(2, result.size, "tool=$tool should produce 2 events")
            assertIs<AgentEvent.AssistantToolUse>(result[0], "tool=$tool first event should be AssistantToolUse")
            assertIs<AgentEvent.ToolResult>(result[1], "tool=$tool second event should be ToolResult")
        }
    }
}
