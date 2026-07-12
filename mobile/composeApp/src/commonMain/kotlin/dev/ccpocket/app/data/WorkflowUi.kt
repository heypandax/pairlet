package dev.ccpocket.app.data

import dev.ccpocket.protocol.WorkflowAgentSnap
import dev.ccpocket.protocol.WorkflowAgentState
import dev.ccpocket.protocol.WorkflowRun
import dev.ccpocket.protocol.WorkflowRunStatus

// ════════════════════════════════════════════════════════════════════
//  Workflow view-state derivation (issue #106) — pure functions shared
//  by the mobile card/tree and the desktop docked panel, matching the
//  design handoff (docs/design/claude-design-handoff/workflow-view/).
//  No Compose here: everything is unit-testable on any target.
// ════════════════════════════════════════════════════════════════════

/** The chat card's five states (workflow-core.jsx `WorkflowCard` variants). */
enum class WorkflowCardVariant { LIVE, LIVE_UNKNOWN, OK, OK_FAIL, ABORTED }

enum class WorkflowPhaseStatus { DONE, ACTIVE, PENDING }

/** One phase disclosure group of the progress tree. [durationMs] approximates the phase span with
 *  its longest member (agents run in parallel); null while nothing in it has finished. */
data class WorkflowPhaseGroup(
    val index: Int,
    val title: String,
    val agents: List<WorkflowAgentSnap>,
    val status: WorkflowPhaseStatus,
    val done: Int,
    val failed: Int,
    val total: Int,
    val durationMs: Long?,
)

object WorkflowUi {

    private val WorkflowAgentSnap.terminal: Boolean
        get() = state == WorkflowAgentState.DONE || state == WorkflowAgentState.FAILED

    fun doneCount(run: WorkflowRun): Int = run.agents.count { it.state == WorkflowAgentState.DONE }
    fun failedCount(run: WorkflowRun): Int = run.agents.count { it.state == WorkflowAgentState.FAILED }
    fun runningCount(run: WorkflowRun): Int = run.agents.count { it.state == WorkflowAgentState.RUNNING }
    fun queuedCount(run: WorkflowRun): Int = run.agents.count { it.state == WorkflowAgentState.QUEUED }

    /**
     * Which card face to draw. Danger only ever lands in the tile/chip — never a red card border
     * (design stance). LIVE_UNKNOWN is the fan-out-still-growing face: nothing queued locally but
     * agents still running means `parallel()` may mint more — the total would lie, so the card
     * says "N done · M running" instead of "N/total".
     */
    fun variant(run: WorkflowRun): WorkflowCardVariant = when (run.status) {
        WorkflowRunStatus.RUNNING, WorkflowRunStatus.UNKNOWN ->
            if (queuedCount(run) == 0 && runningCount(run) > 0) WorkflowCardVariant.LIVE_UNKNOWN
            else WorkflowCardVariant.LIVE
        WorkflowRunStatus.COMPLETED ->
            if (failedCount(run) > 0) WorkflowCardVariant.OK_FAIL else WorkflowCardVariant.OK
        WorkflowRunStatus.FAILED, WorkflowRunStatus.KILLED -> WorkflowCardVariant.ABORTED
    }

    /**
     * Phase disclosure groups, ordered by phase index. Declared-but-empty phases render PENDING;
     * agents whose phaseIndex the CLI never set group under a synthetic "Phase 0". A group is
     * ACTIVE while anything in it runs, DONE once non-empty and fully settled, PENDING otherwise.
     */
    fun phaseGroups(run: WorkflowRun): List<WorkflowPhaseGroup> {
        val titles = run.phases.associate { it.index to it.title }
        val byPhase = run.agents.groupBy { it.phaseIndex ?: 0 }
        val indices = (titles.keys + byPhase.keys).distinct().sorted() // no toSortedSet: JVM-only, breaks the iOS target
        return indices.map { idx ->
            val agents = byPhase[idx].orEmpty().sortedBy { it.index }
            val status = when {
                agents.any { it.state == WorkflowAgentState.RUNNING } -> WorkflowPhaseStatus.ACTIVE
                agents.isNotEmpty() && agents.all { it.terminal } -> WorkflowPhaseStatus.DONE
                else -> WorkflowPhaseStatus.PENDING
            }
            WorkflowPhaseGroup(
                index = idx,
                title = titles[idx] ?: "Phase $idx",
                agents = agents,
                status = status,
                done = agents.count { it.state == WorkflowAgentState.DONE },
                failed = agents.count { it.state == WorkflowAgentState.FAILED },
                total = agents.size,
                durationMs = agents.mapNotNull { it.durationMs }.maxOrNull(),
            )
        }
    }

    /** The phase named on the card's meta line: the first ACTIVE group, else the last one with any
     *  agents (a settled run points at its final phase), else the first declared. */
    fun currentPhase(groups: List<WorkflowPhaseGroup>): WorkflowPhaseGroup? =
        groups.firstOrNull { it.status == WorkflowPhaseStatus.ACTIVE }
            ?: groups.lastOrNull { it.agents.isNotEmpty() }
            ?: groups.firstOrNull()

    /** 1-based position of [group] for the "phase 2/4" caption (declaration order, not raw index). */
    fun phasePosition(groups: List<WorkflowPhaseGroup>, group: WorkflowPhaseGroup): Pair<Int, Int> =
        (groups.indexOfFirst { it.index == group.index } + 1) to groups.size

    /** Journal order — every agent() call, chronological (call index IS the chronology). */
    fun journalRows(run: WorkflowRun): List<WorkflowAgentSnap> = run.agents.sortedBy { it.index }

    /** "48s" · "2m 24s" · "1h 04m" — the handoff's mono duration shorthand. */
    fun formatDuration(ms: Long?): String {
        if (ms == null || ms < 0) return "—"
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${(s % 60).toString().padStart(2, '0')}s"
            else -> "${s / 3600}h ${((s % 3600) / 60).toString().padStart(2, '0')}m"
        }
    }
}
