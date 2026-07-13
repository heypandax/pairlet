package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.QuestionAnswer
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
    fun skill_injection_isMeta_row_never_replays_as_user_input() {
        // issue #126: loading a skill writes TWO user records — the "Launching skill" tool_result ack
        // and an isMeta:true injection carrying the whole SKILL.md. Neither is the user typing; the
        // live stream never surfaced them, so replay must not render the payload as a user bubble.
        val f = tmpFile("skill.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","message":{"role":"user","content":"用 brain 沉淀一下"}}""",
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"s1","name":"Skill","input":{"skill":"brain"}}]}}""",
                """{"type":"user","toolUseResult":{},"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"s1","content":"Launching skill: brain"}]}}""",
                """{"type":"user","isMeta":true,"sourceToolUseID":"s1","message":{"role":"user","content":[{"type":"text","text":"Base directory for this skill: /Users/x/.claude/skills/brain\n\n# brain\n\n把当前会话的结论沉淀到知识库……（SKILL.md 全文）"}]}}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"已按 skill 流程沉淀"}]}}""",
            ).joinToString("\n"),
        )

        val msgs = TranscriptReplay.read(f)

        assertEquals(3, msgs.size) // the real ask, the Skill tool row, the reply — never the SKILL.md payload
        assertEquals("用 brain 沉淀一下", msgs[0].text)
        assertEquals(ChatRole.TOOL, msgs[1].role)
        assertEquals("已按 skill 流程沉淀", msgs[2].text)
        assertTrue(msgs.none { it.role == ChatRole.USER && it.text.contains("Base directory for this skill") })
    }

    @Test
    fun skill_injection_without_isMeta_is_still_dropped_by_fingerprint() {
        // an older CLI (or a variant) may omit isMeta — the "Base directory for this skill:" opening is
        // the fallback fingerprint; slash-command wrapper records are filtered the same way (issue #126)
        val f = tmpFile("skill-nometa.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"Base directory for this skill: /Users/x/.claude/skills/brain\n\nSKILL.md 全文"}]}}""",
                """{"type":"user","message":{"role":"user","content":"<command-name>/compact</command-name>\n<command-message>compact</command-message>"}}""",
                """{"type":"user","message":{"role":"user","content":"<local-command-stdout>Compacted.</local-command-stdout>"}}""",
                """{"type":"user","message":{"role":"user","content":"真正的用户输入"}}""",
            ).joinToString("\n"),
        )

        val msgs = TranscriptReplay.read(f)

        assertEquals(1, msgs.size) // only the genuine turn survives
        assertEquals("真正的用户输入", msgs[0].text)
    }

    @Test
    fun user_text_merely_quoting_the_injection_fingerprints_is_kept() {
        // conservative matching (issue #126 guard-rail): only an OPENING match counts — a user
        // genuinely discussing these phrases mid-message must never be eaten
        val f = tmpFile("skill-quote.jsonl")
        f.writeText(
            """{"type":"user","message":{"role":"user","content":"请解释 Base directory for this skill: 这行是什么意思，另外 <command-name> 标签是干嘛的"}}""",
        )

        val msgs = TranscriptReplay.read(f)

        assertEquals(1, msgs.size)
        assertEquals(ChatRole.USER, msgs[0].role)
        assertTrue(msgs[0].text.contains("什么意思"))
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
    fun askuserquestion_replays_as_answered_row_not_raw_json() {
        // issue #110: a resumed/observed AskUserQuestion must replay as the compact (question → answer)
        // row the live path leaves — not the raw questions JSON that read like a Bash dump
        val f = tmpFile("ask.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","message":{"role":"user","content":"pick a color"}}""",
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"q1","name":"AskUserQuestion","input":{"questions":[{"question":"Which color do you prefer?","header":"Color","multiSelect":false,"options":[{"label":"Red"},{"label":"Blue"}]}]}}]}}""",
                // the main-chain tool_result echoes the pick as `"<question>"="<answer>"` (CLI 2.1.206)
                """{"type":"user","toolUseResult":{},"message":{"content":[{"type":"tool_result","tool_use_id":"q1","content":"Your questions have been answered: \"Which color do you prefer?\"=\"Red\". You can now continue with these answers in mind."}]}}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"CHOSE: Red"}]}}""",
            ).joinToString("\n"),
        )

        val msgs = TranscriptReplay.read(f)

        assertEquals(3, msgs.size) // user, the answered question row, the final answer — no raw tool card
        val q = msgs[1]
        assertEquals(ChatRole.TOOL, q.role)
        assertEquals("AskUserQuestion", q.tool)
        assertEquals(listOf(QuestionAnswer("Which color do you prefer?", "Red")), q.answers)
        assertTrue(!q.text.contains("options") && !q.text.contains("{")) // never the raw questions JSON
        assertEquals("CHOSE: Red", msgs[2].text)
    }

    @Test
    fun askuserquestion_multi_question_keeps_all_pairs_in_order() {
        // a multi-question card answers into comma-separated `"q"="a", "q"="a"` — every pair survives, ordered
        val f = tmpFile("ask-multi.jsonl")
        f.writeText(
            listOf(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"m1","name":"AskUserQuestion","input":{"questions":[{"question":"Color?","options":[{"label":"Red"}]},{"question":"Size?","options":[{"label":"Large"}]}]}}]}}""",
                """{"type":"user","toolUseResult":{},"message":{"content":[{"type":"tool_result","tool_use_id":"m1","content":"Your questions have been answered: \"Color?\"=\"Red\", \"Size?\"=\"Large\". Continue."}]}}""",
            ).joinToString("\n"),
        )

        val msgs = TranscriptReplay.read(f)

        assertEquals(1, msgs.size)
        assertEquals(
            listOf(QuestionAnswer("Color?", "Red"), QuestionAnswer("Size?", "Large")),
            msgs[0].answers,
        )
    }

    @Test
    fun askuserquestion_freeform_reply_replays_as_blank_question_answer() {
        // the user answered in their own words instead of picking — a single ("" → reply) pair, like live
        val f = tmpFile("ask-free.jsonl")
        f.writeText(
            listOf(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"q2","name":"AskUserQuestion","input":{"questions":[{"question":"Which color?","options":[{"label":"Red"}]}]}}]}}""",
                """{"type":"user","toolUseResult":{},"message":{"content":[{"type":"tool_result","tool_use_id":"q2","content":"The user chose not to answer the question. Instead, the user responded: \"surprise me\"."}]}}""",
            ).joinToString("\n"),
        )

        val msgs = TranscriptReplay.read(f)

        assertEquals(1, msgs.size)
        assertEquals(listOf(QuestionAnswer("", "surprise me")), msgs[0].answers)
    }

    @Test
    fun unanswered_askuserquestion_replays_as_readable_question_not_json() {
        // session ended before the tool_result: no answers to attach, but the row still shows the
        // question text (readable), never the raw input JSON
        val f = tmpFile("ask-open.jsonl")
        f.writeText(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"q3","name":"AskUserQuestion","input":{"questions":[{"question":"Deploy now?","options":[{"label":"Yes"},{"label":"No"}]}]}}]}}""",
        )

        val msgs = TranscriptReplay.read(f)

        assertEquals(1, msgs.size)
        val q = msgs[0]
        assertEquals("AskUserQuestion", q.tool)
        assertEquals(null, q.answers)
        assertEquals("Deploy now?", q.text) // the question, not {"questions":[...]}
    }

    @Test
    fun long_reply_replays_in_full_not_clipped_at_2000() {
        // issue #81: a reply longer than the old 2000-char per-message cap must replay whole. The field
        // case was a 2343-char answer that surfaced on the phone as only its first 2000 chars, cut
        // mid-word at "## 建议（" (idx 2000). live streaming was full; only history replay clipped.
        val f = tmpFile("long.jsonl")
        val body = "备".repeat(2343) // 2343 CJK chars = 7029 UTF-8 bytes — far past the old 2000-char clip
        f.writeText(
            listOf(
                """{"type":"user","message":{"role":"user","content":"写个长回复"}}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"$body"}]}}""",
            ).joinToString("\n"),
        )

        val msgs = TranscriptReplay.read(f)

        assertEquals(2, msgs.size)
        assertEquals(2343, msgs[1].text.length) // full, not truncated to 2000
        assertEquals(body, msgs[1].text)
    }

    @Test
    fun total_frame_budget_keeps_newest_whole_and_stays_under_cap() {
        // the per-message cap is gone; frame safety now comes from a *total* byte budget. With a tiny
        // budget the guard keeps the most recent rows intact, truncates the one that straddles, and
        // drops anything older — so the summed UTF-8 size can never blow past a frame (4 MiB in prod).
        val f = tmpFile("budget.jsonl")
        val big = "x".repeat(1000)
        val lines = (0 until 10).map { i ->
            """{"type":"assistant","message":{"content":[{"type":"text","text":"m$i-$big"}]}}"""
        }
        f.writeText(lines.joinToString("\n"))

        val msgs = TranscriptReplay.read(f, maxFrameTextBytes = 2500) // ~2.5 rows of 1003 bytes each

        val total = msgs.sumOf { ReplayBudget.utf8Size(it.text) }
        assertTrue(total <= 2500, "summed text bytes $total must stay within budget")
        assertEquals(3, msgs.size) // newest two whole + one truncated straddling row; older dropped
        assertEquals("m9-$big", msgs.last().text) // newest kept whole, chronological order preserved
        assertTrue(msgs.first().text.length < 3 + big.length) // oldest kept row is the truncated one
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
