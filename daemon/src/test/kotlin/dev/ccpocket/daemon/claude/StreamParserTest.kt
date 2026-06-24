package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.agent.AgentEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamParserTest {

    @Test
    fun system_hook_with_session_id_becomes_sessionInit() {
        val ev = StreamParser.parse("""{"type":"system","subtype":"hook_started","session_id":"abc","uuid":"x"}""").single()
        assertIs<AgentEvent.SessionInit>(ev)
        assertEquals("abc", ev.sessionId)
    }

    @Test
    fun system_init_carries_session_id_cwd() {
        val ev = StreamParser.parse("""{"type":"system","subtype":"init","session_id":"s1","cwd":"/x","model":"m"}""").single()
        assertIs<AgentEvent.SessionInit>(ev)
        assertEquals("s1", ev.sessionId)
        assertEquals("/x", ev.cwd)
    }

    @Test
    fun assistant_text_and_tool_use_blocks() {
        assertEquals(
            AgentEvent.AssistantText("hi"),
            StreamParser.parse("""{"type":"assistant","message":{"content":[{"type":"text","text":"hi"}]}}""").single(),
        )
        val tool = StreamParser.parse(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"echo hi"}}]}}""",
        ).single()
        assertIs<AgentEvent.AssistantToolUse>(tool)
        assertEquals("Bash", tool.name)
    }

    @Test
    fun result_success_with_usage() {
        val ev = StreamParser.parse(
            """{"type":"result","subtype":"success","is_error":false,"result":"PONG","usage":{"input_tokens":10,"output_tokens":5}}""",
        ).single()
        assertIs<AgentEvent.TurnResult>(ev)
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
        assertIs<AgentEvent.ControlRequest>(ev)
        assertEquals("r1", ev.requestId)
        assertEquals("Bash", ev.toolName)
    }

    @Test
    fun drift_and_bad_input_never_throw() {
        assertIs<AgentEvent.Ignored>(StreamParser.parse("""{"type":"rate_limit_event"}""").single())
        assertIs<AgentEvent.Unparseable>(StreamParser.parse("not json").single())
        assertTrue(StreamParser.parse("   ").isEmpty())
    }

    @Test
    fun user_tool_result_becomes_toolResult() {
        val ev = StreamParser.parse(
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"done","is_error":false}]}}""",
        ).single()
        assertIs<AgentEvent.ToolResult>(ev)
        assertEquals("t1", ev.toolUseId)
        assertEquals("done", ev.content)
        assertEquals(false, ev.isError)
    }

    @Test
    fun user_tool_result_array_content_is_joined() {
        val ev = StreamParser.parse(
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":[{"type":"text","text":"line"}],"is_error":true}]}}""",
        ).single()
        assertIs<AgentEvent.ToolResult>(ev)
        assertEquals("line", ev.content)
        assertTrue(ev.isError)
    }

    @Test
    fun plain_user_line_stays_replay() {
        assertIs<AgentEvent.UserReplay>(StreamParser.parse("""{"type":"user","message":{"content":[{"type":"text","text":"hi"}]}}""").single())
        assertIs<AgentEvent.UserReplay>(StreamParser.parse("""{"type":"user"}""").single())
    }

    @Test
    fun system_task_events_become_background_task_events_not_sessionInit() {
        val started = StreamParser.parse(
            """{"type":"system","subtype":"task_started","task_id":"T1","tool_use_id":"u1","description":"Sleep","task_type":"local_bash","session_id":"s"}""",
        ).single()
        assertIs<AgentEvent.BackgroundTaskStarted>(started)
        assertEquals("T1", started.taskId)
        assertEquals("u1", started.toolUseId)

        val notif = StreamParser.parse(
            """{"type":"system","subtype":"task_notification","task_id":"T1","status":"completed","session_id":"s"}""",
        ).single()
        assertIs<AgentEvent.BackgroundTaskUpdated>(notif)
        assertEquals("completed", notif.status)

        val updated = StreamParser.parse(
            """{"type":"system","subtype":"task_updated","task_id":"T1","patch":{"status":"completed"},"session_id":"s"}""",
        ).single()
        assertIs<AgentEvent.BackgroundTaskUpdated>(updated)
        assertEquals("completed", updated.status)
    }
}
