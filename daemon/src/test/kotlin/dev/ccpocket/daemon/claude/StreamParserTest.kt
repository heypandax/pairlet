package dev.ccpocket.daemon.claude

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamParserTest {

    @Test
    fun system_hook_with_session_id_becomes_sessionInit() {
        val ev = StreamParser.parse("""{"type":"system","subtype":"hook_started","session_id":"abc","uuid":"x"}""").single()
        assertIs<ClaudeEvent.SessionInit>(ev)
        assertEquals("abc", ev.sessionId)
    }

    @Test
    fun system_init_carries_session_id_cwd() {
        val ev = StreamParser.parse("""{"type":"system","subtype":"init","session_id":"s1","cwd":"/x","model":"m"}""").single()
        assertIs<ClaudeEvent.SessionInit>(ev)
        assertEquals("s1", ev.sessionId)
        assertEquals("/x", ev.cwd)
    }

    @Test
    fun assistant_text_and_tool_use_blocks() {
        assertEquals(
            ClaudeEvent.AssistantText("hi"),
            StreamParser.parse("""{"type":"assistant","message":{"content":[{"type":"text","text":"hi"}]}}""").single(),
        )
        val tool = StreamParser.parse(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"echo hi"}}]}}""",
        ).single()
        assertIs<ClaudeEvent.AssistantToolUse>(tool)
        assertEquals("Bash", tool.name)
    }

    @Test
    fun result_success_with_usage() {
        val ev = StreamParser.parse(
            """{"type":"result","subtype":"success","is_error":false,"result":"PONG","usage":{"input_tokens":10,"output_tokens":5}}""",
        ).single()
        assertIs<ClaudeEvent.TurnResult>(ev)
        assertEquals("PONG", ev.finalText)
        assertEquals(10, ev.inputTokens)
        assertEquals(5, ev.outputTokens)
        assertEquals(false, ev.isError)
    }

    @Test
    fun control_request_can_use_tool() {
        val ev = StreamParser.parse(
            """{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"x"}}}""",
        ).single()
        assertIs<ClaudeEvent.ControlRequest>(ev)
        assertEquals("r1", ev.requestId)
        assertEquals("Bash", ev.toolName)
    }

    @Test
    fun drift_and_bad_input_never_throw() {
        assertIs<ClaudeEvent.Ignored>(StreamParser.parse("""{"type":"rate_limit_event"}""").single())
        assertIs<ClaudeEvent.Unparseable>(StreamParser.parse("not json").single())
        assertTrue(StreamParser.parse("   ").isEmpty())
    }
}
