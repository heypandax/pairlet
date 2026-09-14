package dev.ccpocket.daemon.execution

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.EXECUTION_OUTPUT_MAX_BYTES
import dev.ccpocket.protocol.PermissionMode
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #367 G1 — the run journal's durability contract. Everything here is about what survives, what is
 * refused, and what a crash is allowed to turn into. No transport, no session, no backend.
 */
class RunJournalTest {

    private val root = createTempDirectory("ccp-run-journal").toFile()
    private var clock = 1_800_000_000_000L
    private val grant = "xg_TESTGRANT0001"
    private val other = "xg_TESTGRANT0002"

    @AfterTest
    fun cleanup() {
        root.setWritable(true)
        root.listFiles()?.forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    private fun journal() = RunJournal(File(root, "execution-runs")) { clock }

    private fun accept(
        j: RunJournal,
        requestId: String = "rq_1",
        grantId: String = grant,
        alias: String = "app",
        prompt: String = "do the thing",
        budget: Int = 10,
    ) = j.accept(
        grantId = grantId,
        requestId = requestId,
        revision = 1,
        workspaceAlias = alias,
        agent = AgentKind.CLAUDE,
        mode = PermissionMode.DEFAULT,
        model = null,
        payloadHash = RunJournal.payloadHash(grantId, requestId, alias, AgentKind.CLAUDE, PermissionMode.DEFAULT, null, prompt),
        budgetCeiling = budget,
    )

    // ---------------------------------------------------------------- accept

    @Test
    fun `accept is on disk before it is visible, and a reopened journal still sees it`() {
        val j = journal()
        val ok = assertIs<RunJournal.Accept.Ok>(accept(j))
        assertFalse(ok.duplicate)
        val file = File(root, "execution-runs/$grant/${ok.run.runId}.json")
        assertTrue(file.isFile, "the accept record must exist before accept() returns — the ACK follows it")
        // 0600: a run record is the private task data of whoever submitted it
        Files.getPosixFilePermissions(file.toPath()).let {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(it))
        }
        assertNotNull(journal().byId(ok.run.runId), "a fresh journal over the same root must find the run")
    }

    @Test
    fun `the same key with the same payload returns the ORIGINAL run, not a second one`() {
        val j = journal()
        val first = assertIs<RunJournal.Accept.Ok>(accept(j))
        val again = assertIs<RunJournal.Accept.Ok>(accept(j))
        assertTrue(again.duplicate)
        assertEquals(first.run.runId, again.run.runId)
        assertEquals(1, j.ofGrant(grant).size)
    }

    @Test
    fun `the same key with a DIFFERENT payload is run_conflict and changes nothing`() {
        val j = journal()
        val first = assertIs<RunJournal.Accept.Ok>(accept(j, prompt = "task A"))
        val clash = assertIs<RunJournal.Accept.Refused>(accept(j, prompt = "task B"))
        assertEquals("run_conflict", clash.code)
        assertEquals(1, j.ofGrant(grant).size)
        assertEquals(first.run.payloadHash, j.byId(first.run.runId)!!.payloadHash)
    }

    @Test
    fun `two grants may reuse the same requestId`() {
        val j = journal()
        assertIs<RunJournal.Accept.Ok>(accept(j, grantId = grant))
        val second = assertIs<RunJournal.Accept.Ok>(accept(j, grantId = other))
        assertEquals(other, second.run.grantId)
    }

    @Test
    fun `the per-grant budget is spent by accepts and SURVIVES retention`() {
        val j = journal()
        assertIs<RunJournal.Accept.Ok>(accept(j, requestId = "rq_1", budget = 2))
        assertIs<RunJournal.Accept.Ok>(accept(j, requestId = "rq_2", budget = 2))
        assertEquals("budget_exhausted", assertIs<RunJournal.Accept.Refused>(accept(j, requestId = "rq_3", budget = 2)).code)
        // finish + age out both runs, then re-open: a purged journal must NOT hand the budget back
        j.ofGrant(grant).forEach { j.transition(it.runId, ExecutionRunState.COMPLETED) }
        clock += 8L * 24 * 3600_000
        assertEquals(2, j.purge())
        val reopened = journal()
        assertEquals(2, reopened.budgetUsed(grant))
        assertEquals("budget_exhausted", assertIs<RunJournal.Accept.Refused>(accept(reopened, requestId = "rq_4", budget = 2)).code)
    }

    @Test
    fun `a malformed requestId is refused`() {
        assertEquals("request_id_invalid", assertIs<RunJournal.Accept.Refused>(accept(journal(), requestId = "../escape")).code)
    }

    // ---------------------------------------------------------------- state machine

    @Test
    fun `terminal states are monotonic - a late RUNNING cannot overwrite COMPLETED`() {
        val j = journal()
        val run = assertIs<RunJournal.Accept.Ok>(accept(j)).run
        j.transition(run.runId, ExecutionRunState.STARTING)
        j.transition(run.runId, ExecutionRunState.RUNNING)
        j.transition(run.runId, ExecutionRunState.COMPLETED)
        val late = j.transition(run.runId, ExecutionRunState.RUNNING)
        assertEquals(ExecutionRunState.COMPLETED, late!!.state)
        // …and a second terminal cannot rewrite the first one either
        assertEquals(ExecutionRunState.COMPLETED, j.transition(run.runId, ExecutionRunState.FAILED)!!.state)
    }

    @Test
    fun `repeating a cancel or reading a finished run is idempotent`() {
        val j = journal()
        val run = assertIs<RunJournal.Accept.Ok>(accept(j)).run
        j.transition(run.runId, ExecutionRunState.STARTING)
        j.transition(run.runId, ExecutionRunState.RUNNING)
        assertNotNull(j.noteCancelRequested(run.runId)?.cancelRequestedAt)
        val firstMark = j.byId(run.runId)!!.cancelRequestedAt
        clock += 1_000
        assertEquals(firstMark, j.noteCancelRequested(run.runId)?.cancelRequestedAt, "a repeated cancel must not move the mark")
        assertEquals(ExecutionRunState.CANCELLED, j.transition(run.runId, ExecutionRunState.CANCELLED)!!.state)
        assertEquals(ExecutionRunState.CANCELLED, j.transition(run.runId, ExecutionRunState.CANCELLED)!!.state)
    }

    @Test
    fun `WAITING_APPROVAL and RUNNING may alternate`() {
        val j = journal()
        val run = assertIs<RunJournal.Accept.Ok>(accept(j)).run
        j.transition(run.runId, ExecutionRunState.STARTING)
        j.transition(run.runId, ExecutionRunState.RUNNING)
        assertTrue(j.transition(run.runId, ExecutionRunState.WAITING_APPROVAL)!!.approvalPending)
        assertFalse(j.transition(run.runId, ExecutionRunState.RUNNING, approvalPending = false)!!.approvalPending)
        assertTrue(j.transition(run.runId, ExecutionRunState.WAITING_APPROVAL)!!.approvalPending)
    }

    @Test
    fun `a run cannot jump straight from ACCEPTED to RUNNING`() {
        val j = journal()
        val run = assertIs<RunJournal.Accept.Ok>(accept(j)).run
        assertEquals(ExecutionRunState.ACCEPTED, j.transition(run.runId, ExecutionRunState.RUNNING)!!.state)
    }

    // ---------------------------------------------------------------- recovery

    @Test
    fun `a run interrupted mid-flight recovers as INTERRUPTED_UNKNOWN and is NEVER re-run`() {
        val j = journal()
        val started = assertIs<RunJournal.Accept.Ok>(accept(j, requestId = "rq_started")).run
        val queued = assertIs<RunJournal.Accept.Ok>(accept(j, requestId = "rq_queued")).run
        j.transition(started.runId, ExecutionRunState.STARTING)
        j.transition(started.runId, ExecutionRunState.RUNNING)

        val recovered = journal() // "the daemon restarted"
        assertEquals(ExecutionRunState.INTERRUPTED_UNKNOWN, recovered.byId(started.runId)!!.state)
        // an ACCEPTED run never reached a backend, so it is honestly failed rather than "unknown"
        assertEquals(ExecutionRunState.FAILED, recovered.byId(queued.runId)!!.state)
        assertEquals("interrupted_before_start", recovered.byId(queued.runId)!!.error)
        assertTrue(recovered.live().isEmpty(), "recovery must leave nothing runnable — no automatic retry")
    }

    @Test
    fun `an unreadable row is quarantined, not silently dropped, and the rest still load`() {
        val j = journal()
        val good = assertIs<RunJournal.Accept.Ok>(accept(j, requestId = "rq_good")).run
        val bad = assertIs<RunJournal.Accept.Ok>(accept(j, requestId = "rq_bad")).run
        j.transition(good.runId, ExecutionRunState.STARTING)
        j.transition(good.runId, ExecutionRunState.COMPLETED)
        File(root, "execution-runs/$grant/${bad.runId}.json").writeText("{ not json")

        val recovered = journal()
        assertNotNull(recovered.byId(good.runId))
        assertNull(recovered.byId(bad.runId))
        assertTrue(File(root, "execution-runs/$grant/${bad.runId}.json.corrupt").isFile, "the bytes must be kept as evidence")
    }

    // ---------------------------------------------------------------- output

    @Test
    fun `output is capped at the wire budget, flagged truncated, and cut on a character boundary`() {
        val j = journal()
        val run = assertIs<RunJournal.Accept.Ok>(accept(j)).run
        j.transition(run.runId, ExecutionRunState.STARTING)
        j.transition(run.runId, ExecutionRunState.RUNNING)
        // 3-byte characters, so a naive byte cut would split one and produce U+FFFD
        val chunk = "漢".repeat(EXECUTION_OUTPUT_MAX_BYTES) // ~3x over the cap
        val after = j.appendOutput(run.runId, chunk)!!
        assertTrue(after.truncated)
        assertTrue(after.outputBytes <= EXECUTION_OUTPUT_MAX_BYTES)
        assertFalse(after.output.contains('�'), "the cut must land on a character boundary")
        assertEquals(after.output, journal().byId(run.runId)!!.output, "the truncated text is what is on disk")
    }

    @Test
    fun `clampUtf8 keeps whole characters`() {
        val (text, cut) = RunJournal.clampUtf8("aä漢", 3)
        assertTrue(cut)
        assertEquals("aä", text)
        assertEquals("abc" to false, RunJournal.clampUtf8("abc", 3))
    }

    // ---------------------------------------------------------------- retention

    @Test
    fun `retention drops finished runs and keeps live ones`() {
        val j = journal()
        val old = assertIs<RunJournal.Accept.Ok>(accept(j, requestId = "rq_old")).run
        j.transition(old.runId, ExecutionRunState.STARTING)
        j.transition(old.runId, ExecutionRunState.COMPLETED)
        clock += RunJournal.RETENTION_MS + 1
        val fresh = assertIs<RunJournal.Accept.Ok>(accept(j, requestId = "rq_fresh")).run
        j.transition(fresh.runId, ExecutionRunState.STARTING)

        assertEquals(1, j.purge())
        assertNull(j.byId(old.runId))
        assertNotNull(j.byId(fresh.runId))
        assertFalse(File(root, "execution-runs/$grant/${old.runId}.events").exists())
    }
}
