package dev.ccpocket.daemon.codex

import dev.ccpocket.daemon.conversation.ObservedProgressReducer
import dev.ccpocket.daemon.conversation.TurnEvidence
import dev.ccpocket.protocol.ObservedStates
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Turn-lifecycle evidence off a REAL (redacted) codex-cli 0.155.1 rollout — `rollout-observed-turns.jsonl` is the
 * probe's fixture of a thread with three completed turns and one interrupted turn (scripts/probe-dots-observation.py
 * --fixture) — plus hand-built tails for the states the corpus does not contain.
 */
class CodexTurnEvidenceTest {
    @BeforeTest
    fun reset() = CodexTranscriptScanner.clearForTest()

    private fun fixture(): Path {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/codex/rollout-observed-turns.jsonl")) { "fixture missing" }.readBytes()
        return Files.createTempFile("rollout-2026-10-05T20-01-13-01a10f28-7b3c-7611-bf29-46d1e7ed7b3a", ".jsonl").also { Files.write(it, bytes) }
    }

    @Test
    fun real_rollout_ends_on_a_completed_turn_with_its_turn_id() {
        val t = assertNotNull(CodexTranscriptScanner.turnEvidence(fixture()))
        assertEquals("01a10f33-f92b-7220-a04a-b71319850601", t.turnId)
        assertEquals(TurnEvidence.END_COMPLETE, t.endKind)
        assertTrue(t.ended)
        assertNotNull(t.startedAt); assertNotNull(t.endedAt)
        assertTrue(t.endedAt!! >= t.startedAt!!)
        assertNull(t.lastAction) // task_complete closes any action
        // the record's own timestamp, not the read clock: 2026-10-06T03:14:56Z-ish
        assertTrue(t.endedAt!! in 1_791_000_000_000L..1_792_000_000_000L)
        val p = ObservedProgressReducer.reduce(t, fileMtime = t.endedAt, exists = true, now = t.endedAt!! + 10_000)
        assertEquals(ObservedStates.IDLE, p.state)
    }

    @Test
    fun an_interrupted_turn_reads_as_cancelled_until_the_next_turn_starts() {
        val file = fixture()
        // cut the fixture right after the turn_aborted line: the newest turn is the interrupted one
        val lines = Files.readAllLines(file)
        val cut = lines.indexOfFirst { it.contains("\"turn_aborted\"") }
        file.writeText(lines.take(cut + 1).joinToString("\n") + "\n")
        CodexTranscriptScanner.clearForTest()
        val t = assertNotNull(CodexTranscriptScanner.turnEvidence(file))
        assertEquals(TurnEvidence.END_ABORTED, t.endKind)
        assertEquals("interrupted", t.endReason)
        assertEquals(ObservedStates.CANCELLED, ObservedProgressReducer.reduce(t, t.endedAt, true, t.endedAt!! + 1).state)
    }

    @Test
    fun a_turn_in_flight_is_running_with_its_current_tool() {
        val file = Files.createTempFile("rollout-2026-01-01T00-00-00-thr-run", ".jsonl")
        file.writeText(
            """
            {"timestamp":"2026-10-06T03:00:00.000Z","type":"session_meta","payload":{"id":"thr-run","cwd":"/repo","cli_version":"0.155.1"}}
            {"timestamp":"2026-10-06T03:00:01.000Z","type":"event_msg","payload":{"type":"task_started","turn_id":"turn-a","started_at":1791255601}}
            {"timestamp":"2026-10-06T03:00:02.000Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"run the tests"}]}}
            {"timestamp":"2026-10-06T03:00:03.000Z","type":"response_item","payload":{"type":"custom_tool_call","name":"shell","call_id":"c1","input":"./gradlew test\n--info"}}
            """.trimIndent() + "\n",
        )
        val t = assertNotNull(CodexTranscriptScanner.turnEvidence(file))
        assertTrue(t.running)
        assertEquals("turn-a", t.turnId)
        assertEquals("shell · ./gradlew test", t.lastAction)
        val startedMs = java.time.Instant.parse("2026-10-06T03:00:01.000Z").toEpochMilli()
        assertEquals(startedMs, t.startedAt)
        val p = ObservedProgressReducer.reduce(t, fileMtime = t.lastActivityAt, exists = true, now = t.lastActivityAt!! + 1_000)
        assertEquals(ObservedStates.RUNNING, p.state)
        assertEquals("shell · ./gradlew test", p.currentAction)

        // the tool returns (non-zero is still a tool outcome, not a turn failure) → running, no current action
        file.appendText("""{"timestamp":"2026-10-06T03:00:09.000Z","type":"response_item","payload":{"type":"custom_tool_call_output","call_id":"c1","output":"exit 1"}}""" + "\n")
        CodexTranscriptScanner.clearForTest()
        val after = assertNotNull(CodexTranscriptScanner.turnEvidence(file))
        assertTrue(after.running)
        assertNull(after.lastAction)
        assertEquals(ObservedStates.RUNNING, ObservedProgressReducer.reduce(after, after.lastActivityAt, true, after.lastActivityAt!! + 1).state)

        // a half-written trailing line is ignored for now, not fatal — the complete prefix still answers
        file.appendText("""{"timestamp":"2026-10-06T03:00:10.000Z","type":"event_msg","payload":{"type":"task_comp""")
        CodexTranscriptScanner.clearForTest()
        val partial = assertNotNull(CodexTranscriptScanner.turnEvidence(file))
        assertTrue(partial.running)

        // …and once the line is completed, the turn is idle
        file.appendText("""lete","turn_id":"turn-a"}}""" + "\n")
        CodexTranscriptScanner.clearForTest()
        val done = assertNotNull(CodexTranscriptScanner.turnEvidence(file))
        assertEquals(TurnEvidence.END_COMPLETE, done.endKind)
        assertEquals(ObservedStates.IDLE, ObservedProgressReducer.reduce(done, done.endedAt, true, done.endedAt!! + 1).state)
    }

    @Test
    fun a_stray_error_record_cannot_fail_a_finished_turn() {
        val file = Files.createTempFile("rollout-2026-01-01T00-00-00-thr-err", ".jsonl")
        file.writeText(
            """
            {"timestamp":"2026-10-06T03:00:00.000Z","type":"session_meta","payload":{"id":"thr-err","cwd":"/repo"}}
            {"timestamp":"2026-10-06T03:00:01.000Z","type":"event_msg","payload":{"type":"task_started","turn_id":"t"}}
            {"timestamp":"2026-10-06T03:00:02.000Z","type":"event_msg","payload":{"type":"task_complete","turn_id":"t"}}
            {"timestamp":"2026-10-06T03:00:03.000Z","type":"event_msg","payload":{"type":"error","message":"late"}}
            """.trimIndent() + "\n",
        )
        val t = assertNotNull(CodexTranscriptScanner.turnEvidence(file))
        assertEquals(TurnEvidence.END_COMPLETE, t.endKind)
    }

    @Test
    fun a_rollout_with_no_lifecycle_records_yields_no_turn_claims() {
        val file = Files.createTempFile("rollout-2026-01-01T00-00-00-thr-none", ".jsonl")
        file.writeText("""{"timestamp":"2026-10-06T03:00:00.000Z","type":"session_meta","payload":{"id":"thr-none","cwd":"/repo"}}""" + "\n")
        val t = CodexTranscriptScanner.turnEvidence(file)
        assertTrue(t == null || (t.startedAt == null && t.endedAt == null))
        assertEquals(ObservedStates.UNKNOWN, ObservedProgressReducer.reduce(t, 1L, true, 2L).state)
    }
}
