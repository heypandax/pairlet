package dev.ccpocket.daemon.disk

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PR #296 review, performance: the ~10-second project refresh ran [LiveProcesses.codexCwds] with zero
 * caching, and each run was a full OS process enumeration PLUS an lsof fork. It is memoized for 5s now,
 * and an expired memo whose pid set is unchanged reuses the old cwds instead of forking lsof again.
 *
 * The clock and both probes are injected, so this pins the policy rather than the machine's process table.
 * (Deliberately NOT covered here because it must never exist: a cache on externalClaudeAt/externalCodexAt —
 * those decide whether a transcript may be written by a second process. See the iron rule on
 * `LiveProcesses.externalAgentAt`.)
 */
class LiveProcessesCodexCwdCacheTest {
    private var pidCalls = 0
    private var lsofCalls = 0

    @BeforeTest
    fun reset() {
        LiveProcesses.clearForTest()
        pidCalls = 0
        lsofCalls = 0
    }

    @AfterTest
    fun cleanup() = LiveProcesses.clearForTest()

    private fun probe(nowMs: Long, pids: Set<Long>, cwds: Set<String> = setOf("/repo")) =
        LiveProcesses.codexCwds(
            nowMs,
            { pidCalls++; pids },
            { lsofCalls++; cwds },
        )

    @Test
    fun within_the_ttl_nothing_is_probed_at_all() {
        assertEquals(setOf("/repo"), probe(10_000, setOf(1L, 2L)))
        assertEquals(1, pidCalls)
        assertEquals(1, lsofCalls)

        assertEquals(setOf("/repo"), probe(14_999, setOf(1L, 2L)), "cached answer")
        assertEquals(1, pidCalls, "not even the process walk runs inside the TTL")
        assertEquals(1, lsofCalls)
    }

    @Test
    fun an_expired_memo_with_the_same_pids_skips_the_lsof_fork() {
        probe(10_000, setOf(1L, 2L))
        assertEquals(setOf("/repo"), probe(15_000, setOf(1L, 2L)), "same processes ⇒ same cwds")
        assertEquals(2, pidCalls, "pids are re-enumerated — that part is in-process and cheap")
        assertEquals(1, lsofCalls, "the fork is what gets skipped")
    }

    @Test
    fun a_changed_pid_set_re_runs_lsof() {
        probe(10_000, setOf(1L, 2L))
        assertEquals(setOf("/other"), probe(15_000, setOf(1L, 3L), cwds = setOf("/other")))
        assertEquals(2, lsofCalls)
    }

    @Test
    fun no_codex_processes_answers_empty_without_lsof_and_is_itself_cached() {
        assertEquals(emptySet<String>(), probe(10_000, emptySet()))
        assertEquals(0, lsofCalls, "nothing to ask lsof about")
        assertEquals(emptySet<String>(), probe(12_000, setOf(1L, 2L)), "the empty answer is cached like any other")
        assertEquals(1, pidCalls)
    }

    @Test
    fun a_process_exiting_is_reflected_once_the_ttl_expires() {
        assertEquals(setOf("/repo"), probe(10_000, setOf(1L, 2L)))
        assertEquals(emptySet<String>(), probe(16_000, emptySet()), "liveness must still drop away, just a beat later")
    }
}
