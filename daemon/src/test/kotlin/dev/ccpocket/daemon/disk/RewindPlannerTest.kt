package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.RewindRefusal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * issue #282: translating "the user long-pressed THIS message" into a CLI launch.
 *
 * The behaviour being pinned is the CLI's, established by `scripts/probe-claude-wire.py`
 * `scenario_rewind` on 2.1.228: `--resume-session-at` keeps everything up to and INCLUDING the anchor
 * entry, and `--resume-drops-turn` names the single user turn a one-turn cut discards. The planner's job
 * is to hand those two uuids over correctly — and to refuse rather than guess when it cannot.
 */
class RewindPlannerTest {

    /** Three turns, so a cut can land in the MIDDLE: u0 → a0 · u1 → a1 + a tool · u2 → a tool + a2. */
    private fun seed(): Path {
        val dir = Files.createTempDirectory("ccp-rewind-plan")
        val f = dir.resolve("s.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","uuid":"u0","parentUuid":null,"message":{"role":"user","content":"hello"}}""",
                """{"type":"assistant","uuid":"a0","parentUuid":"u0","message":{"role":"assistant","content":[{"type":"text","text":"hi"}]}}""",
                """{"type":"user","uuid":"u1","parentUuid":"a0","message":{"role":"user","content":"remember seven"}}""",
                """{"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"role":"assistant","content":[{"type":"text","text":"ok"}]}}""",
                """{"type":"assistant","uuid":"a1b","parentUuid":"a1","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}""",
                """{"type":"user","uuid":"u2","parentUuid":"a1b","message":{"role":"user","content":"remember blue"}}""",
                """{"type":"assistant","uuid":"a2","parentUuid":"u2","message":{"role":"assistant","content":[{"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"/x"}}]}}""",
                """{"type":"assistant","uuid":"a3","parentUuid":"a2","message":{"role":"assistant","content":[{"type":"text","text":"done"}]}}""",
            ).joinToString("\n") + "\n",
        )
        return f
    }

    private fun ok(r: RewindPlanner.Result) = (r as RewindPlanner.Result.Ok).plan
    private fun refused(r: RewindPlanner.Result) = (r as RewindPlanner.Result.Refused).reason

    @Test
    fun anchor_is_the_selected_rows_parent_not_the_row_itself() {
        // "drop u2 and everything after it" ⇒ keep up to u2's PARENT. Sending u2 itself would keep the
        // very message the user asked to take back — the difference between rewinding and doing nothing.
        val plan = ok(RewindPlanner.plan(seed(), anchorUuid = "u2", anchorSeq = 6))
        assertEquals("a1b", plan.anchorUuid)
    }

    @Test
    fun a_one_turn_cut_declares_the_turn_it_drops() {
        // Exactly one user turn leaves ⇒ the CLI guard rail applies, and its value is the SELECTED
        // message (the turn being discarded), per the probe's ⑥.
        val plan = ok(RewindPlanner.plan(seed(), "u2", 6))
        assertEquals(1, plan.dropTurns)
        assertEquals("u2", plan.dropsTurnUuid)
    }

    @Test
    fun a_multi_turn_cut_goes_unguarded_on_purpose() {
        // Declaring ONE turn while dropping two is always rejected by the CLI, so the flag is omitted and
        // the dry-run preview carries the safety instead. Omitting it must be deliberate, not incidental.
        val plan = ok(RewindPlanner.plan(seed(), "u1", 3))
        assertEquals(2, plan.dropTurns)
        assertNull(plan.dropsTurnUuid)
    }

    @Test
    fun counts_match_what_the_transcript_replay_shows() {
        // The sheet's numbers have to be checkable against the screen, so they count the SAME rows the
        // replay renders — from the anchor inclusive to the end.
        val f = seed()
        val plan = ok(RewindPlanner.plan(f, "u1", 3))
        assertEquals(2, plan.dropTurns)
        assertEquals(2, plan.dropToolCalls)

        val dropped = TranscriptReplay.read(f).filter { (it.seq ?: 0L) >= 3L }
        assertEquals(dropped.count { it.role == ChatRole.USER }, plan.dropTurns)
        assertEquals(dropped.count { it.role == ChatRole.TOOL }, plan.dropToolCalls)
    }

    @Test
    fun a_uuid_on_the_wrong_line_is_stale_not_resolved() {
        // The pair is a double anchor precisely so a moved row fails loudly. Resolving to "the row with
        // that uuid, wherever it is now" is the one failure mode this feature must never have.
        assertEquals(RewindRefusal.STALE, refused(RewindPlanner.plan(seed(), "u2", anchorSeq = 99)))
    }

    @Test
    fun an_unknown_uuid_is_stale() {
        assertEquals(RewindRefusal.STALE, refused(RewindPlanner.plan(seed(), "u-gone", 6)))
    }

    @Test
    fun the_first_message_has_no_anchor_to_keep() {
        val dir = Files.createTempDirectory("ccp-rewind-first")
        val f = dir.resolve("s.jsonl")
        f.writeText("""{"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"hi"}}""" + "\n")
        assertEquals(RewindRefusal.FIRST_MESSAGE, refused(RewindPlanner.plan(f, "u1", 1)))
    }

    @Test
    fun a_missing_file_is_not_an_exception() {
        val missing = Files.createTempDirectory("ccp-rewind-none").resolve("nope.jsonl")
        assertEquals(RewindRefusal.NO_TRANSCRIPT, refused(RewindPlanner.plan(missing, "u1", 1)))
    }

    @Test
    fun the_replay_stamps_coordinates_on_user_rows_only() {
        val rows = TranscriptReplay.read(seed())
        val users = rows.filter { it.role == ChatRole.USER }
        assertEquals(listOf("u0", "u1", "u2"), users.map { it.uuid })
        assertTrue(users.all { it.seq != null })
        // every row gets a cursor (the client needs it for nothing else here, but the numbering must be
        // one scheme); only USER rows get a uuid
        assertTrue(rows.all { it.seq != null })
        assertTrue(rows.filter { it.role != ChatRole.USER }.all { it.uuid == null })
    }

    @Test
    fun the_cli_refusal_shape_is_recognised_by_its_missing_init() {
        // probe ⑤/⑥: exit 1, NO init frame (the turn never started), structured error result on stdout.
        assertTrue(
            RewindPlanner.isTruncationRefusal(1, listOf("result"), listOf("error_during_execution")),
        )
        // an ordinary failed turn DOES init first — that is a different problem and must not be reported
        // as "the CLI rejected the anchor"
        assertTrue(
            !RewindPlanner.isTruncationRefusal(1, listOf("system", "result"), listOf("init", "error_during_execution")),
        )
        assertTrue(!RewindPlanner.isTruncationRefusal(0, listOf("result"), listOf("error_during_execution")))
    }
}
