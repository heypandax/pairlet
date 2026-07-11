package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ChatRole
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptReplayTest {

    private fun tmpFile(name: String) = Files.createTempDirectory("ccp-replay").resolve(name)

    @Test
    fun drops_harness_noise_and_keeps_real_turns() {
        val f = tmpFile("sess.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","message":{"role":"user","content":"deploy please"}}""",
                // standalone background-shell notice — pure plumbing, dropped
                """{"type":"user","message":{"role":"user","content":"<task-notification>\n<task-id>x</task-id>\n<status>stopped</status>\n</task-notification>"}}""",
                // bare resume nudge the harness injects on continuation — dropped
                """{"type":"user","message":{"role":"user","content":"Continue from where you left off."}}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"on it"}]}}""",
                // a task-notification PREPENDED to real text keeps the turn — genuine input is never eaten
                """{"type":"user","message":{"role":"user","content":"<task-notification>\n<task-id>y</task-id>\n</task-notification>\nand also bump the version"}}""",
            ).joinToString("\n"),
        )

        val msgs = TranscriptReplay.read(f)

        assertEquals(3, msgs.size) // "deploy please", assistant "on it", and the prepended real turn
        assertEquals(ChatRole.USER, msgs[0].role)
        assertEquals("deploy please", msgs[0].text)
        assertEquals(ChatRole.ASSISTANT, msgs[1].role)
        assertEquals("on it", msgs[1].text)
        assertEquals(ChatRole.USER, msgs[2].role)
        assertTrue(msgs[2].text.contains("bump the version"))
    }

    @Test
    fun subagent_run_replays_as_one_card_with_outcome_and_report() {
        // issue #77: sidechain (sub-agent internal) records collapse into the Task/Agent card, which
        // carries the run's label, ok and final report (the CLI's agentId continuation tail stripped)
        val f = tmpFile("agent.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","message":{"role":"user","content":"sum 2 and 3"}}""",
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"a1","name":"Agent","input":{"subagent_type":"general-purpose","description":"add two numbers","prompt":"add them"}}]}}""",
                // the sub-agent's own records share the file with isSidechain:true — never main-chain rows
                """{"type":"user","isSidechain":true,"parent_tool_use_id":"a1","message":{"role":"user","content":"add them"}}""",
                """{"type":"assistant","isSidechain":true,"message":{"content":[{"type":"tool_use","id":"b1","name":"Bash","input":{"command":"expr 2 + 3"}}]}}""",
                """{"type":"user","isSidechain":true,"message":{"content":[{"type":"tool_result","tool_use_id":"b1","content":"5"}]}}""",
                // the main-chain tool_result IS the sub-agent's report (+ the agentId plumbing tail)
                """{"type":"user","toolUseResult":{},"message":{"content":[{"type":"tool_result","tool_use_id":"a1","content":[{"type":"text","text":"5"},{"type":"text","text":"agentId: a05 (use SendMessage)"}]}]}}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"the answer is 5"}]}}""",
            ).joinToString("\n"),
        )

        val msgs = TranscriptReplay.read(f)

        assertEquals(3, msgs.size) // user, the Agent card, the final answer — no sidechain leakage
        val card = msgs[1]
        assertEquals(ChatRole.TOOL, card.role)
        assertEquals("Agent", card.tool)
        assertEquals("general-purpose: add two numbers", card.text)
        assertEquals(true, card.ok)
        assertEquals("5", card.output)
        assertEquals("the answer is 5", msgs[2].text)
    }

    @Test
    fun synthetic_placeholder_replays_flagged_as_error() {
        // a context-dead session's tail: the CLI's `<synthetic>` placeholders must replay as errors,
        // not as normal assistant replies the user mistakes for answers (issue #65)
        val f = tmpFile("dead.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","message":{"role":"user","content":"hello?"}}""",
                """{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"No response requested."}]}}""",
                """{"type":"user","message":{"role":"user","content":"still there?"}}""",
                """{"type":"assistant","message":{"model":"claude-sonnet-5","content":[{"type":"text","text":"yes"}]}}""",
            ).joinToString("\n"),
        )

        val msgs = TranscriptReplay.read(f)

        assertEquals(4, msgs.size)
        assertTrue(msgs[1].error) // the placeholder
        assertEquals("No response requested.", msgs[1].text)
        assertTrue(!msgs[3].error) // the real reply
    }
    @Test
    fun workflow_tool_row_gets_its_run_id_from_the_launch_acks_toolUseResult() {
        // records mirror a real probe run (claude 2.1.206): the Workflow tool_use, then the launch
        // ack whose ROOT-level toolUseResult carries the run id — the only place it appears
        val f = tmpFile("wf.jsonl")
        f.writeText(
            listOf(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_01Ly","name":"Workflow","input":{"script":"export const meta = …","description":"probe"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_01Ly","type":"tool_result","content":"Workflow launched in background. Task ID: wvw3rra3y"}]},"toolUseResult":{"status":"async_launched","taskId":"wvw3rra3y","taskType":"local_workflow","workflowName":"probe-mini","runId":"wf_03737500-658","summary":"probe minimal workflow"}}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"launched"}]}}""",
            ).joinToString("\n"),
        )

        val msgs = TranscriptReplay.read(f)

        assertEquals(2, msgs.size) // the tool row + "launched" (the ack user record is not a real turn)
        val card = msgs[0]
        assertEquals(ChatRole.TOOL, card.role)
        assertEquals("Workflow", card.tool)
        assertEquals("probe", card.text)                  // description, never 280 chars of script
        assertEquals("wf_03737500-658", card.workflowRunId)
        assertEquals(true, card.ok)
    }

    @Test
    fun plain_task_tool_results_leave_workflowRunId_null() {
        val f = tmpFile("task.jsonl")
        f.writeText(
            listOf(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Agent","input":{"subagent_type":"Explore","description":"scan"}}]}}""",
                """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"t1","type":"tool_result","content":"report"}]},"toolUseResult":{"content":"report"}}""",
            ).joinToString("\n"),
        )
        val card = TranscriptReplay.read(f).single()
        assertEquals("Explore: scan", card.text)
        assertEquals(null, card.workflowRunId)
    }
}
