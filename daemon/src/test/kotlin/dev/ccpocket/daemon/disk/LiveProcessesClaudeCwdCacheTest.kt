package dev.ccpocket.daemon.disk

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #303: [LiveProcesses.claudeCwds] carried the same cost [LiveProcesses.codexCwds] was memoized for
 * in PR #296 — a full OS process enumeration plus an lsof fork on every ~10s project refresh — and got the
 * same 5s memo. This pins the Claude instance's policy and, above all, that the two memos are INDEPENDENT:
 * one agent's process churn must never expire (or answer for) the other's.
 *
 * (Still deliberately absent, and must stay absent: any cache on externalClaudeAt/externalCodexAt. See the
 * iron rule on `LiveProcesses.externalAgentAt`.)
 */
class LiveProcessesClaudeCwdCacheTest {
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
        LiveProcesses.claudeCwds(
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
    fun a_terminal_claude_exiting_is_reflected_once_the_ttl_expires() {
        assertEquals(setOf("/repo"), probe(10_000, setOf(1L, 2L)))
        assertEquals(emptySet<String>(), probe(16_000, emptySet()), "liveness must still drop away, just a beat later")
    }

    @Test
    fun the_two_agents_memos_do_not_share_state() {
        assertEquals(setOf("/claude-repo"), probe(10_000, setOf(1L), cwds = setOf("/claude-repo")))
        // a Codex probe at the same instant answers from its OWN (empty) memo, and leaves Claude's alone
        assertEquals(setOf("/codex-repo"), LiveProcesses.codexCwds(10_000, { setOf(9L) }, { setOf("/codex-repo") }))
        assertEquals(setOf("/claude-repo"), probe(11_000, setOf(1L), cwds = setOf("/claude-repo")))
        assertEquals(1, pidCalls, "the Codex probe must not have expired the Claude memo")
        assertEquals(1, lsofCalls)
    }
}
