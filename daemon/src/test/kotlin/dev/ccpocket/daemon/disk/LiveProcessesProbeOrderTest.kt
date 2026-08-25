package dev.ccpocket.daemon.disk

import dev.ccpocket.daemon.disk.LiveProcesses.ExternalClaude
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #303: the take-over probe used to fork an fd-holder lsof unconditionally, even for Claude, whose fd
 * is rarely held and whose cwd almost always answers on the first try. It now asks cwd first for Claude
 * (`fdFirst = false`) and keeps fd first for Codex.
 *
 * This is a SAFETY path — it decides whether a second process may write someone else's transcript — so the
 * bar for the reordering is not "looks equivalent" but "equivalent on every input". `fdFirst = true` IS the
 * old code path, so the table below drives both orders through all 3×3 probe outcomes (hit / miss / lsof
 * failed) and demands the same verdict, then pins the fork actually saved.
 */
class LiveProcessesProbeOrderTest {
    private val outcomes = listOf<Boolean?>(true, false, null) // hit / miss / probe failed

    private fun verdict(
        fdFirst: Boolean,
        holders: Boolean?,
        cwd: Boolean?,
        external: List<Long>? = listOf(42L),
        noLsof: Boolean = false,
        allowCwdMatch: Boolean = true,
        onHolders: () -> Unit = {},
        onCwd: () -> Unit = {},
    ) = LiveProcesses.agentVerdict(
        external = external,
        noLsof = noLsof,
        allowCwdMatch = allowCwdMatch,
        fdFirst = fdFirst,
        holdsTranscript = { onHolders(); holders },
        cwdMatches = { onCwd(); cwd },
    )

    @Test
    fun cwd_first_and_fd_first_agree_on_every_probe_outcome() {
        for (holders in outcomes) for (cwd in outcomes) {
            assertEquals(
                verdict(fdFirst = true, holders = holders, cwd = cwd),
                verdict(fdFirst = false, holders = holders, cwd = cwd),
                "holders=$holders cwd=$cwd — reordering the probes must not move the verdict",
            )
        }
    }

    @Test
    fun the_verdict_table_itself_is_what_it_always_was() {
        // either signal alone is sufficient for PRESENT; UNKNOWN only when a probe that could still have
        // said PRESENT failed; ABSENT when both looked and neither found anything
        for (fdFirst in listOf(true, false)) {
            assertEquals(ExternalClaude.PRESENT, verdict(fdFirst, holders = true, cwd = false))
            assertEquals(ExternalClaude.PRESENT, verdict(fdFirst, holders = false, cwd = true))
            assertEquals(ExternalClaude.PRESENT, verdict(fdFirst, holders = true, cwd = null))
            assertEquals(ExternalClaude.PRESENT, verdict(fdFirst, holders = null, cwd = true))
            assertEquals(ExternalClaude.ABSENT, verdict(fdFirst, holders = false, cwd = false))
            assertEquals(ExternalClaude.ABSENT, verdict(fdFirst, holders = null, cwd = false))
            assertEquals(ExternalClaude.UNKNOWN, verdict(fdFirst, holders = false, cwd = null))
            assertEquals(ExternalClaude.UNKNOWN, verdict(fdFirst, holders = null, cwd = null))
        }
    }

    @Test
    fun a_cwd_hit_spares_claude_the_fd_fork() {
        var fdForks = 0
        assertEquals(
            ExternalClaude.PRESENT,
            verdict(fdFirst = false, holders = false, cwd = true, onHolders = { fdForks++ }),
        )
        assertEquals(0, fdForks, "the common terminal-claude case must cost ONE lsof, not two")
    }

    @Test
    fun a_cwd_miss_still_consults_the_fd_holder() {
        // the strengthener is not optional: an agent that moved cwd but still holds the transcript owns it
        var fdForks = 0
        assertEquals(
            ExternalClaude.PRESENT,
            verdict(fdFirst = false, holders = true, cwd = false, onHolders = { fdForks++ }),
        )
        assertEquals(1, fdForks)
    }

    @Test
    fun codex_keeps_asking_the_fd_first() {
        var cwdForks = 0
        assertEquals(
            ExternalClaude.PRESENT,
            verdict(fdFirst = true, holders = true, cwd = true, onCwd = { cwdForks++ }),
        )
        assertEquals(0, cwdForks, "an idle Codex is found by its held rollout — no cwd fork needed")
    }

    @Test
    fun an_old_codex_rollout_still_ignores_cwd_entirely() {
        // allowCwdMatch = false: one unrelated Codex in the same project must not make every old rollout
        // there look live. Only the fd may speak, and its failure is UNKNOWN ("assume it is still held").
        var cwdForks = 0
        for (fdFirst in listOf(true, false)) {
            assertEquals(
                ExternalClaude.ABSENT,
                verdict(fdFirst, holders = false, cwd = true, allowCwdMatch = false, onCwd = { cwdForks++ }),
            )
            assertEquals(
                ExternalClaude.UNKNOWN,
                verdict(fdFirst, holders = null, cwd = true, allowCwdMatch = false, onCwd = { cwdForks++ }),
            )
            assertEquals(
                ExternalClaude.PRESENT,
                verdict(fdFirst, holders = true, cwd = false, allowCwdMatch = false, onCwd = { cwdForks++ }),
            )
        }
        assertEquals(0, cwdForks, "the cwd probe must never even run when cwd may not decide")
    }

    @Test
    fun nothing_is_probed_before_the_process_walk_has_spoken() {
        var probes = 0
        val count: () -> Unit = { probes++ }
        // enumeration failed ⇒ we know nothing
        assertEquals(ExternalClaude.UNKNOWN, verdict(false, true, true, external = null, onHolders = count, onCwd = count))
        // no matching agent outside the daemon at all ⇒ ABSENT without touching lsof (keeps Windows sharp)
        assertEquals(ExternalClaude.ABSENT, verdict(false, true, true, external = emptyList(), onHolders = count, onCwd = count))
        // no lsof AND an fd-only decision (old codex rollout): no probe can speak → UNKNOWN, no fork
        assertEquals(ExternalClaude.UNKNOWN, verdict(false, true, true, noLsof = true, allowCwdMatch = false, onHolders = count, onCwd = count))
        assertEquals(0, probes, "these three answers are decided before any fork, in both orders")
        assertTrue(
            listOf(true, false).all { fd ->
                verdict(fd, true, true, external = null) == ExternalClaude.UNKNOWN &&
                    verdict(fd, true, true, external = emptyList()) == ExternalClaude.ABSENT &&
                    verdict(fd, true, true, noLsof = true, allowCwdMatch = false) == ExternalClaude.UNKNOWN
            },
        )
    }

    @Test
    fun no_lsof_but_cwd_eligible_defers_to_the_cwd_probe_and_never_forks_the_fd_probe() {
        // Windows (issue #302): the fd/lsof probe can't run, but ProcessCwd can read cwd. When cwd matching
        // is allowed the cwd probe alone decides — three-valued, so a read miss stays UNKNOWN, never ABSENT.
        for (fdFirst in listOf(true, false)) {
            var fdProbes = 0
            val fdCount: () -> Unit = { fdProbes++ }
            assertEquals(ExternalClaude.PRESENT, verdict(fdFirst, holders = true, cwd = true, noLsof = true, onHolders = fdCount))
            assertEquals(ExternalClaude.ABSENT, verdict(fdFirst, holders = true, cwd = false, noLsof = true, onHolders = fdCount))
            assertEquals(ExternalClaude.UNKNOWN, verdict(fdFirst, holders = true, cwd = null, noLsof = true, onHolders = fdCount))
            assertEquals(0, fdProbes, "no lsof means the fd probe must never be forked — cwd alone answers")
        }
    }
}
