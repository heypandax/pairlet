package dev.ccpocket.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The five refresh rules for the sidebar allowance strip, pinned against a hand-advanced clock.
 *
 * Deliberately NOT a Compose test: the rules are time-driven and Compose's test clock has a standing
 * judgement in this repo for hanging `waitForIdle` when a `delay` loop is fast-forwarded (approval-system
 * M1 notes). [ClaudeQuotaRefreshPolicy] exists as a plain object precisely so the timing can be proven
 * here, and the composable that drives it stays a dumb event reporter.
 */
class ClaudeQuotaRefreshPolicyTest {

    private class Fixture {
        var clock = 1_000_000L
        val calls = mutableListOf<Boolean>() // one entry per outbound request; the value is forceRefresh
        val policy = ClaudeQuotaRefreshPolicy(now = { clock }, fetch = { force -> calls.add(force) })

        /** The common case: a request went out and the daemon answered with numbers as of `fetchedAt`. */
        fun replySuccess(fetchedAt: Long = clock) {
            policy.snapshotFetchedAt(fetchedAt)
            policy.replied()
        }

        fun advance(ms: Long) { clock += ms }
    }

    // ── rule 1: connected ──────────────────────────────────────────────────────────────────────────

    @Test
    fun a_ready_link_refreshes_immediately() {
        val f = Fixture()
        assertTrue(f.policy.event(QuotaRefreshTrigger.CONNECTED))
        assertEquals(listOf(false), f.calls, "connect refreshes, and never with forceRefresh")
    }

    @Test
    fun a_reconnect_after_a_reset_refreshes_again_even_within_the_period() {
        // switching machines (or a drop/reconnect) must re-ask: the next daemon is a different account,
        // and the previous snapshot's age says nothing about it
        val f = Fixture()
        f.policy.event(QuotaRefreshTrigger.CONNECTED); f.replySuccess()
        f.advance(5_000)
        f.policy.reset()
        assertTrue(f.policy.event(QuotaRefreshTrigger.CONNECTED))
        assertEquals(2, f.calls.size)
    }

    // ── rule 2: periodic ───────────────────────────────────────────────────────────────────────────

    @Test
    fun the_periodic_rule_fires_once_per_period_not_once_per_tick() {
        val f = Fixture()
        f.policy.event(QuotaRefreshTrigger.CONNECTED); f.replySuccess()
        // a pump that ticks every 10s must not produce a request every 10s
        repeat(29) { f.advance(10_000); if (f.policy.tick()) f.replySuccess() }
        assertEquals(1, f.calls.size, "290s in: still only the connect fetch")
        f.advance(10_000)
        f.policy.tick()
        assertEquals(2, f.calls.size, "past the 5 min period it refreshes")
    }

    @Test
    fun the_period_is_measured_from_the_last_attempt_so_a_dead_link_does_not_storm() {
        val f = Fixture()
        f.policy.event(QuotaRefreshTrigger.CONNECTED)
        f.policy.replied() // a FAILURE reply: no snapshot, so only the attempt anchors the clock
        f.advance(60_000)
        f.policy.tick()
        assertEquals(1, f.calls.size, "a failed fetch still counts as an attempt")
        f.advance(ClaudeQuotaRefreshPolicy.PERIOD_MS)
        f.policy.tick()
        assertEquals(2, f.calls.size)
    }

    @Test
    fun a_failure_schedules_no_retry_of_its_own() {
        val f = Fixture()
        f.policy.event(QuotaRefreshTrigger.CONNECTED)
        f.policy.replied()
        // no tick, no event: nothing should happen on its own
        f.advance(ClaudeQuotaRefreshPolicy.PERIOD_MS * 3)
        assertEquals(1, f.calls.size)
    }

    // ── rule 3: focus ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun refocusing_refreshes_only_when_the_snapshot_is_stale() {
        val f = Fixture()
        f.policy.event(QuotaRefreshTrigger.CONNECTED); f.replySuccess()
        f.advance(30_000)
        assertFalse(f.policy.event(QuotaRefreshTrigger.FOCUSED), "alt-tabbing must not be a request generator")
        assertEquals(1, f.calls.size)
        f.advance(ClaudeQuotaRefreshPolicy.FOCUS_STALE_MS)
        assertTrue(f.policy.event(QuotaRefreshTrigger.FOCUSED))
        assertEquals(2, f.calls.size)
    }

    @Test
    fun focus_before_anything_is_known_always_refreshes() {
        val f = Fixture()
        assertTrue(f.policy.event(QuotaRefreshTrigger.FOCUSED), "no snapshot at all is maximally stale")
    }

    // ── rule 4: turn-done debounce ─────────────────────────────────────────────────────────────────

    @Test
    fun a_finished_turn_never_refreshes_immediately() {
        val f = Fixture()
        f.policy.event(QuotaRefreshTrigger.CONNECTED); f.replySuccess()
        assertFalse(f.policy.event(QuotaRefreshTrigger.TURN_DONE))
        assertEquals(1, f.calls.size)
    }

    @Test
    fun a_burst_of_turns_produces_exactly_one_refresh_after_the_quiet() {
        val f = Fixture()
        f.policy.event(QuotaRefreshTrigger.CONNECTED); f.replySuccess()
        // ten turns over 100s — a trailing debounce means the due moment keeps moving
        repeat(10) {
            f.policy.event(QuotaRefreshTrigger.TURN_DONE)
            f.advance(10_000)
            f.policy.tick()
        }
        assertEquals(1, f.calls.size, "nothing goes out during the burst")
        f.advance(ClaudeQuotaRefreshPolicy.TURN_DEBOUNCE_MS)
        f.policy.tick()
        assertEquals(2, f.calls.size, "one request follows the last turn's quiet period")
        f.replySuccess()
        f.advance(ClaudeQuotaRefreshPolicy.TURN_DEBOUNCE_MS * 3)
        f.policy.tick()
        assertEquals(2, f.calls.size, "the debounce does not re-arm itself")
    }

    @Test
    fun the_debounce_waits_the_full_window_after_the_last_turn() {
        val f = Fixture()
        f.policy.event(QuotaRefreshTrigger.CONNECTED); f.replySuccess()
        f.policy.event(QuotaRefreshTrigger.TURN_DONE)
        f.advance(ClaudeQuotaRefreshPolicy.TURN_DEBOUNCE_MS - 1)
        f.policy.tick()
        assertEquals(1, f.calls.size)
        f.advance(1)
        f.policy.tick()
        assertEquals(2, f.calls.size)
    }

    // ── rule 5: manual ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun manual_always_goes_out_and_is_the_only_forced_refresh() {
        val f = Fixture()
        f.policy.event(QuotaRefreshTrigger.CONNECTED); f.replySuccess()
        assertTrue(f.policy.event(QuotaRefreshTrigger.MANUAL))
        assertTrue(f.policy.event(QuotaRefreshTrigger.MANUAL), "even twice in a row, with a reply outstanding")
        assertEquals(listOf(false, true, true), f.calls)
    }

    // ── in-flight de-duplication ───────────────────────────────────────────────────────────────────

    @Test
    fun concurrent_triggers_collapse_into_one_request() {
        val f = Fixture()
        // CONNECTED is the always-fires rule, which makes it the clean probe for the in-flight gate:
        // anything it is suppressed BY can only be the de-duplication.
        assertTrue(f.policy.event(QuotaRefreshTrigger.CONNECTED))
        assertFalse(f.policy.event(QuotaRefreshTrigger.CONNECTED), "a second trigger rides the in-flight request")
        assertFalse(f.policy.event(QuotaRefreshTrigger.FOCUSED))
        f.advance(ClaudeQuotaRefreshPolicy.IN_FLIGHT_TIMEOUT_MS - 1)
        assertFalse(f.policy.event(QuotaRefreshTrigger.CONNECTED), "still one in flight, still suppressed")
        assertEquals(1, f.calls.size)
        f.replySuccess()
        assertTrue(f.policy.event(QuotaRefreshTrigger.CONNECTED), "once the reply lands the gate opens again")
        assertEquals(2, f.calls.size)
    }

    @Test
    fun a_reply_that_never_arrives_does_not_wedge_the_gate_forever() {
        // an old daemon drops the unknown frame entirely — "no reply" is a normal outcome, not an anomaly
        val f = Fixture()
        f.policy.event(QuotaRefreshTrigger.CONNECTED)
        f.advance(ClaudeQuotaRefreshPolicy.IN_FLIGHT_TIMEOUT_MS - 1)
        assertFalse(f.policy.event(QuotaRefreshTrigger.FOCUSED))
        f.advance(ClaudeQuotaRefreshPolicy.PERIOD_MS)
        f.policy.tick()
        assertEquals(2, f.calls.size, "past the in-flight backstop the policy may ask again")
    }

    @Test
    fun a_failure_reply_keeps_no_snapshot_basis_but_still_opens_the_gate() {
        val f = Fixture()
        f.policy.event(QuotaRefreshTrigger.CONNECTED)
        f.policy.snapshotFetchedAt(0)   // a failure frame carries fetchedAt = 0
        f.policy.snapshotFetchedAt(null)
        f.policy.replied()
        f.advance(ClaudeQuotaRefreshPolicy.FOCUS_STALE_MS)
        assertTrue(f.policy.event(QuotaRefreshTrigger.FOCUSED), "the attempt is old enough to retry on focus")
        assertEquals(2, f.calls.size)
    }

    @Test
    fun the_age_basis_is_the_daemons_fetch_moment_not_our_request_moment() {
        // the daemon caches for 60s, so a reply can legitimately carry numbers older than the request
        val f = Fixture()
        f.policy.event(QuotaRefreshTrigger.CONNECTED)
        f.policy.snapshotFetchedAt(f.clock)
        f.policy.replied()
        f.advance(ClaudeQuotaRefreshPolicy.PERIOD_MS - 1)
        f.policy.tick()
        assertEquals(1, f.calls.size)
        f.advance(1)
        f.policy.tick()
        assertEquals(2, f.calls.size)
    }
}
