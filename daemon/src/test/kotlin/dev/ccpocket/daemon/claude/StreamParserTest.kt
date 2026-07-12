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
    fun assistant_message_usage_becomes_a_per_call_usage_event() {
        // the result event SUMS usage across every API call of the turn (2 tool batches ≈ 2× the real
        // window footprint — statusline read 88% on a 44% session), so each call's usage must surface
        val evs = StreamParser.parse(
            """{"type":"assistant","message":{"content":[{"type":"text","text":"hi"}],"usage":{"input_tokens":38,"cache_read_input_tokens":26854,"cache_creation_input_tokens":304,"output_tokens":3}}}""",
        )
        assertEquals(2, evs.size)
        val usage = evs.filterIsInstance<AgentEvent.AssistantUsage>().single()
        assertEquals(38, usage.inputTokens)
        assertEquals(26854, usage.cacheReadInputTokens)
        assertEquals(304, usage.cacheCreationInputTokens)
    }

    @Test
    fun subagent_assistant_usage_is_not_surfaced() {
        // a Task subagent's usage is the SUBAGENT's own window, not this session's (isSidechain rule)
        val evs = StreamParser.parse(
            """{"type":"assistant","parent_tool_use_id":"t9","message":{"content":[{"type":"text","text":"sub"}],"usage":{"input_tokens":5,"cache_read_input_tokens":100}}}""",
        )
        assertTrue(evs.filterIsInstance<AgentEvent.AssistantUsage>().isEmpty())
    }

    @Test
    fun result_success_with_usage() {
        val ev = StreamParser.parse(
            """{"type":"result","subtype":"success","is_error":false,"result":"PONG","usage":{"input_tokens":10,"output_tokens":5}}""",
        ).single()
        assertIs<AgentEvent.TurnResult>(ev)
        assertEquals("PONG", ev.finalText)
        assertEquals(10L, ev.usage?.inputTokens)
        assertEquals(5L, ev.usage?.outputTokens)
        assertEquals(false, ev.isError)
    }

    @Test
    fun result_without_usage_reports_no_usage_not_zeros() {
        // interrupted/error turns can end with a usage-less result — the zeros are placeholders, and
        // treating them as real snapped the phone's context statusline to 0% (and poisoned the resume seed)
        val ev = StreamParser.parse(
            """{"type":"result","subtype":"error_during_execution","is_error":true,"result":null}""",
        ).single()
        assertIs<AgentEvent.TurnResult>(ev)
        assertEquals(null, ev.usage)
        assertEquals(true, ev.isError)
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

    // issue #122: the replay's text is the ledger's settlement key — both content shapes must carry it
    @Test
    fun user_replay_carries_text_for_both_content_shapes() {
        val fromString = StreamParser.parse("""{"type":"user","message":{"role":"user","content":"send it"}}""").single()
        assertIs<AgentEvent.UserReplay>(fromString)
        assertEquals("send it", fromString.text)
        assertEquals(null, fromString.parentId)

        val fromBlocks = StreamParser.parse("""{"type":"user","message":{"content":[{"type":"text","text":"send it"}]}}""").single()
        assertIs<AgentEvent.UserReplay>(fromBlocks)
        assertEquals("send it", fromBlocks.text)
    }

    @Test
    fun subagent_user_replay_is_parent_tagged() {
        val ev = StreamParser.parse(
            """{"type":"user","parent_tool_use_id":"a1","message":{"content":[{"type":"text","text":"inner"}]}}""",
        ).single()
        assertIs<AgentEvent.UserReplay>(ev)
        assertEquals("a1", ev.parentId) // never settles a top-level prompt
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

    @Test
    fun subagent_events_carry_the_parent_tool_use_id() {
        // sub-agent (Task/Agent) internals stream in the same stdout, tagged with parent_tool_use_id
        // at the ROOT — the Conversation folds them into the parent's card (issue #77; probed 07-08)
        val toolUse = StreamParser.parse(
            """{"type":"assistant","parent_tool_use_id":"p1","message":{"content":[{"type":"tool_use","id":"t2","name":"Bash","input":{"command":"expr 2 + 3"}}]}}""",
        ).single()
        assertIs<AgentEvent.AssistantToolUse>(toolUse)
        assertEquals("p1", toolUse.parentId)

        val text = StreamParser.parse(
            """{"type":"assistant","parent_tool_use_id":"p1","message":{"content":[{"type":"text","text":"sub says"}]}}""",
        ).single()
        assertIs<AgentEvent.AssistantText>(text)
        assertEquals("p1", text.parentId)

        val result = StreamParser.parse(
            """{"type":"user","parent_tool_use_id":"p1","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":"5"}]}}""",
        ).single()
        assertIs<AgentEvent.ToolResult>(result)
        assertEquals("p1", result.parentId)

        // main-chain events stay untagged
        val main = StreamParser.parse(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Agent","input":{}}]}}""",
        ).single()
        assertIs<AgentEvent.AssistantToolUse>(main)
        assertEquals(null, main.parentId)
    }

    @Test
    fun task_notification_carries_tool_use_id_and_summary() {
        // for a backgrounded sub-agent this pair is the authoritative completion (issue #77)
        val ev = StreamParser.parse(
            """{"type":"system","subtype":"task_notification","task_id":"T1","tool_use_id":"u1","status":"completed","summary":"5","session_id":"s"}""",
        ).single()
        assertIs<AgentEvent.BackgroundTaskUpdated>(ev)
        assertEquals("u1", ev.toolUseId)
        assertEquals("5", ev.summary)
    }

    @Test
    fun synthetic_assistant_becomes_syntheticReply_not_a_normal_chunk() {
        // model "<synthetic>" = the CLI's API-failure placeholder ("No response requested.") — it must
        // surface as an error signal, never as AssistantText the phone renders as a real reply (issue #65)
        val evs = StreamParser.parse(
            """{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"No response requested."}],"usage":{"input_tokens":0,"output_tokens":0}}}""",
        )
        val synthetic = evs.filterIsInstance<AgentEvent.SyntheticReply>().single()
        assertEquals("No response requested.", synthetic.text)
        assertTrue(evs.filterIsInstance<AgentEvent.AssistantText>().isEmpty())
        // its zero usage must not poison the statusline either
        assertTrue(evs.filterIsInstance<AgentEvent.AssistantUsage>().isEmpty())
    }

    @Test
    fun real_model_assistant_is_untouched_by_the_synthetic_guard() {
        val ev = StreamParser.parse(
            """{"type":"assistant","message":{"model":"claude-sonnet-5","content":[{"type":"text","text":"hi"}]}}""",
        ).single()
        assertEquals(AgentEvent.AssistantText("hi"), ev)
    }
    // ── Workflow orchestration events (issue #106) — frames captured verbatim from a real run
    // (claude 2.1.206, scripts/probe-claude-wire.py workflow scenario) ──────────────────────────

    @Test
    fun task_progress_with_workflow_progress_array_becomes_workflowProgress() {
        val ev = StreamParser.parse(
            """{"type":"system","subtype":"task_progress","task_id":"wvw3rra3y","tool_use_id":"toolu_01Ly","description":"Beta: say-cherry","usage":{"total_tokens":45662,"tool_uses":0,"duration_ms":4265},"last_tool_name":"say-cherry","summary":"probe minimal workflow","workflow_progress":[{"type":"workflow_phase","index":1,"title":"Alpha"},{"type":"workflow_agent","index":1,"label":"say-apple","phaseIndex":1,"phaseTitle":"Alpha","agentId":"a878b","model":"claude-sonnet-5","state":"done","startedAt":1783769400993,"queuedAt":1783769400992,"attempt":1,"promptPreview":"Reply with…","tokens":22831,"toolCalls":0,"durationMs":3266,"resultPreview":"apple"}],"session_id":"s1"}""",
        ).single()
        assertIs<AgentEvent.WorkflowProgress>(ev)
        assertEquals("wvw3rra3y", ev.taskId)
        assertEquals("toolu_01Ly", ev.toolUseId)
        assertEquals(2, ev.items.size)
    }

    @Test
    fun task_progress_without_workflow_progress_is_ignored_not_sessionInit() {
        // pure activity ticks omit the array — they carry no per-agent state; and despite having a
        // session_id they must NOT fall through to the SessionInit branch
        val ev = StreamParser.parse(
            """{"type":"system","subtype":"task_progress","task_id":"w1","description":"Alpha: say-apple","usage":{"total_tokens":22831,"tool_uses":0,"duration_ms":3269},"session_id":"s1"}""",
        ).single()
        assertIs<AgentEvent.Ignored>(ev)
    }

    @Test
    fun task_started_carries_workflow_name() {
        val ev = StreamParser.parse(
            """{"type":"system","subtype":"task_started","task_id":"wvw3rra3y","tool_use_id":"toolu_01Ly","description":"probe minimal workflow","task_type":"local_workflow","workflow_name":"probe-mini","prompt":"export const meta = …","session_id":"s1"}""",
        ).single()
        assertIs<AgentEvent.BackgroundTaskStarted>(ev)
        assertEquals("local_workflow", ev.taskType)
        assertEquals("probe-mini", ev.workflowName)
    }

    @Test
    fun workflow_launch_ack_rides_the_user_tool_result_as_workflowLaunched() {
        // the root-level tool_use_result is the ONLY live carrier of the run id
        val evs = StreamParser.parse(
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_01Ly","type":"tool_result","content":"Workflow launched in background. Task ID: wvw3rra3y"}]},"tool_use_result":{"status":"async_launched","taskId":"wvw3rra3y","taskType":"local_workflow","workflowName":"probe-mini","runId":"wf_03737500-658","summary":"probe minimal workflow","transcriptDir":"/x","scriptPath":"/y"},"session_id":"s1"}""",
        )
        val launch = evs.filterIsInstance<AgentEvent.WorkflowLaunched>().single()
        assertEquals("wf_03737500-658", launch.runId)
        assertEquals("wvw3rra3y", launch.taskId)
        assertEquals("toolu_01Ly", launch.toolUseId)
        assertEquals("probe-mini", launch.workflowName)
        // the plain ToolResult still flows (jobs/subagent tracking is untouched)
        assertTrue(evs.any { it is AgentEvent.ToolResult })
    }

    @Test
    fun non_workflow_tool_use_result_emits_no_launch_event() {
        val evs = StreamParser.parse(
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"t1","type":"tool_result","content":"ok"}]},"tool_use_result":{"stdout":"ok"},"session_id":"s1"}""",
        )
        assertTrue(evs.none { it is AgentEvent.WorkflowLaunched })
    }
}
