package dev.ccpocket.app.data

import dev.ccpocket.protocol.WorkflowAgentSnap
import dev.ccpocket.protocol.WorkflowAgentState
import dev.ccpocket.protocol.WorkflowPhaseInfo
import dev.ccpocket.protocol.WorkflowRun
import dev.ccpocket.protocol.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Card-variant + phase-group derivation (issue #106) — the pure layer both the mobile card/tree
 *  and the desktop docked panel render from. */
class WorkflowUiTest {

    private fun agent(
        i: Int, state: WorkflowAgentState, phase: Int? = 1,
        dur: Long? = null, started: Long? = null, err: String? = null,
    ) = WorkflowAgentSnap(
        index = i, label = "a$i", state = state, phaseIndex = phase,
        durationMs = dur, startedAt = started, error = err,
    )

    private fun run(
        status: WorkflowRunStatus,
        agents: List<WorkflowAgentSnap>,
        phases: List<WorkflowPhaseInfo> = listOf(WorkflowPhaseInfo(1, "resolve"), WorkflowPhaseInfo(2, "analyze")),
    ) = WorkflowRun("wf_1", "release-pipeline", status, phases = phases, agents = agents, startedAt = 1)

    @Test
    fun variant_live_vs_live_unknown_vs_terminal_faces() {
        // queued agents exist → the total is known → LIVE ("12/34 agents")
        val live = run(WorkflowRunStatus.RUNNING, listOf(agent(1, WorkflowAgentState.RUNNING, started = 1), agent(2, WorkflowAgentState.QUEUED)))
        assertEquals(WorkflowCardVariant.LIVE, WorkflowUi.variant(live))

        // nothing queued but agents still running → fan-out may still grow → LIVE_UNKNOWN ("N done · M running")
        val unknown = run(WorkflowRunStatus.RUNNING, listOf(agent(1, WorkflowAgentState.DONE, dur = 5), agent(2, WorkflowAgentState.RUNNING, started = 1)))
        assertEquals(WorkflowCardVariant.LIVE_UNKNOWN, WorkflowUi.variant(unknown))

        val ok = run(WorkflowRunStatus.COMPLETED, listOf(agent(1, WorkflowAgentState.DONE, dur = 5)))
        assertEquals(WorkflowCardVariant.OK, WorkflowUi.variant(ok))

        val okFail = run(WorkflowRunStatus.COMPLETED, listOf(agent(1, WorkflowAgentState.DONE, dur = 5), agent(2, WorkflowAgentState.FAILED, err = "boom")))
        assertEquals(WorkflowCardVariant.OK_FAIL, WorkflowUi.variant(okFail))

        assertEquals(WorkflowCardVariant.ABORTED, WorkflowUi.variant(run(WorkflowRunStatus.KILLED, emptyList())))
        assertEquals(WorkflowCardVariant.ABORTED, WorkflowUi.variant(run(WorkflowRunStatus.FAILED, emptyList())))
    }

    @Test
    fun phase_groups_carry_status_counts_and_declared_but_empty_phases_are_pending() {
        val r = run(
            WorkflowRunStatus.RUNNING,
            listOf(
                agent(1, WorkflowAgentState.DONE, phase = 1, dur = 48_000),
                agent(2, WorkflowAgentState.DONE, phase = 1, dur = 30_000),
                agent(3, WorkflowAgentState.RUNNING, phase = 2, started = 9),
                agent(4, WorkflowAgentState.FAILED, phase = 2, err = "x"),
                agent(5, WorkflowAgentState.QUEUED, phase = 2),
            ),
            phases = listOf(WorkflowPhaseInfo(1, "resolve"), WorkflowPhaseInfo(2, "analyze"), WorkflowPhaseInfo(3, "package")),
        )
        val groups = WorkflowUi.phaseGroups(r)
        assertEquals(listOf("resolve", "analyze", "package"), groups.map { it.title })
        assertEquals(WorkflowPhaseStatus.DONE, groups[0].status)
        assertEquals(48_000, groups[0].durationMs) // longest member ≈ the parallel span
        assertEquals(WorkflowPhaseStatus.ACTIVE, groups[1].status)
        assertEquals(1, groups[1].failed)
        assertEquals(0, groups[1].done) // one running, one failed, one queued — none done yet
        assertEquals(3, groups[1].total)
        // declared in meta.phases but no agents yet → pending
        assertEquals(WorkflowPhaseStatus.PENDING, groups[2].status)
        assertEquals(0, groups[2].total)

        val cur = assertNotNull(WorkflowUi.currentPhase(groups))
        assertEquals("analyze", cur.title)
        assertEquals(2 to 3, WorkflowUi.phasePosition(groups, cur))
    }

    @Test
    fun agents_without_a_phase_group_under_phase_zero() {
        val r = run(
            WorkflowRunStatus.RUNNING,
            listOf(agent(1, WorkflowAgentState.RUNNING, phase = null, started = 2)),
            phases = emptyList(),
        )
        val groups = WorkflowUi.phaseGroups(r)
        assertEquals(1, groups.size)
        assertEquals(0, groups[0].index)
        assertEquals("Phase 0", groups[0].title)
        assertEquals(WorkflowPhaseStatus.ACTIVE, groups[0].status)
    }

    @Test
    fun settled_run_current_phase_is_its_last_populated_group() {
        val r = run(
            WorkflowRunStatus.COMPLETED,
            listOf(agent(1, WorkflowAgentState.DONE, phase = 1, dur = 3), agent(2, WorkflowAgentState.DONE, phase = 2, dur = 4)),
        )
        val cur = assertNotNull(WorkflowUi.currentPhase(WorkflowUi.phaseGroups(r)))
        assertEquals("analyze", cur.title)
    }

    @Test
    fun journal_rows_are_chronological_by_call_index() {
        val r = run(
            WorkflowRunStatus.COMPLETED,
            listOf(agent(3, WorkflowAgentState.DONE), agent(1, WorkflowAgentState.DONE), agent(2, WorkflowAgentState.FAILED)),
        )
        assertEquals(listOf(1, 2, 3), WorkflowUi.journalRows(r).map { it.index })
    }

    @Test
    fun durations_format_like_the_handoff() {
        assertEquals("—", WorkflowUi.formatDuration(null))
        assertEquals("48s", WorkflowUi.formatDuration(48_000))
        assertEquals("2m 24s", WorkflowUi.formatDuration(144_000))
        assertEquals("6m 32s", WorkflowUi.formatDuration(392_000))
        assertEquals("1h 05m", WorkflowUi.formatDuration(3_900_000))
    }
}
