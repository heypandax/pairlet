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
}
