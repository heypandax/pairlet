package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.RewindRefusal
import java.nio.file.Path

/**
 * Turns "the user long-pressed THIS message" into the CLI launch that drops it and everything after
 * (issue #282, docs/design/REWIND-FORK.md §3.3), and into the numbers the confirmation sheet shows.
 *
 * Pure and file-driven on purpose: the phone sends a (uuid, seq) pair it read out of a replay that may
 * be minutes old, and the transcript has kept growing since. Every field the launch depends on is
 * re-derived here from the file as it is RIGHT NOW, and a pair that no longer agrees with it is
 * refused ([RewindRefusal.STALE]) rather than resolved to a best guess — cutting a conversation somewhere the
 * person did not point at is the one failure this feature must never have.
 */
object RewindPlanner {

    /**
     * A validated cut. [anchorUuid] is what `--resume-session-at` gets: the chain entry BEFORE the
     * selected message, i.e. the last one that survives. [dropsTurnUuid] is the `--resume-drops-turn`
     * guard rail, non-null ONLY when the cut is exactly one turn wide — declaring a single turn while
     * dropping several is always rejected by the CLI, so a multi-turn cut deliberately goes unguarded
     * and leans on the dry-run preview instead ([dropTurns] > 1 is exactly that case).
     */
    data class Plan(
        val anchorUuid: String,
        val dropsTurnUuid: String?,
        val dropTurns: Int,
        val dropToolCalls: Int,
    )

    sealed interface Result {
        data class Ok(val plan: Plan) : Result
        data class Refused(val reason: String) : Result
    }

    /**
     * Plan the cut at the USER row identified by [anchorUuid] AND [anchorSeq] — both must match the
     * same row. [dropTurns]/[dropToolCalls] count that row and everything after it, on the MAIN chain
     * only (the rows a person can scroll back and see), because a preview whose numbers cannot be
     * checked against the screen is worse than no preview.
     */
    fun plan(file: Path, anchorUuid: String, anchorSeq: Long): Result {
        val rows = runCatching { TranscriptReplay.chain(file) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return Result.Refused(RewindRefusal.NO_TRANSCRIPT)
        val idx = rows.indexOfFirst { it.msg.uuid == anchorUuid && it.line == anchorSeq }
        if (idx < 0) return Result.Refused(RewindRefusal.STALE)
        val row = rows[idx]
        // parentUuid == null is the transcript's own root: there is no entry to keep, so this is "start
        // over", which the client offers as New session instead.
        val parent = row.parentUuid ?: return Result.Refused(RewindRefusal.FIRST_MESSAGE)
        val dropped = rows.subList(idx, rows.size)
        val turns = dropped.count { it.msg.role == ChatRole.USER }
        val tools = dropped.count { it.msg.role == ChatRole.TOOL }
        return Result.Ok(
            Plan(
                anchorUuid = parent,
                // the selected message IS the single dropped turn in the one-turn case
                dropsTurnUuid = anchorUuid.takeIf { turns == 1 },
                dropTurns = turns,
                dropToolCalls = tools,
            ),
        )
    }

    /**
     * Does a dead launch look like the CLI REFUSING a truncated resume, rather than any other failure?
     * The refusal shape is structural and was probed directly (2.1.228, `scenario_rewind` ⑤/⑥): exit 1,
     * NO init frame at all — the turn never starts — and a structured `error_during_execution` result
     * on stdout. Recognising it matters because the daemon pre-validates against the file and a refusal
     * here therefore means the CLI and our parse disagree, which must surface as a specific failure and
     * not as a generic "the agent didn't start".
     *
     * [stdoutTypes]/[stdoutSubtypes] are the `type`/`subtype` fields of whatever JSON lines did arrive.
     */
    fun isTruncationRefusal(exitCode: Int, stdoutTypes: List<String>, stdoutSubtypes: List<String>): Boolean =
        exitCode == 1 &&
            "system" !in stdoutTypes && // the init frame's type — its ABSENCE is the load-bearing half
            "result" in stdoutTypes &&
            "error_during_execution" in stdoutSubtypes
}
