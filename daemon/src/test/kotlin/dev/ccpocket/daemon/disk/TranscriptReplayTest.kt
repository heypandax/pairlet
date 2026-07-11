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
}
