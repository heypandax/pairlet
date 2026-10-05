package dev.ccpocket.daemon.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit U2: the daily auto-update used to download, flip and exitProcess(0) whenever its timer fired,
 * killing whatever agent turn was running. It now waits for the daemon to be idle, for at most
 * [UpdateChecker.MAX_DEFER_MS]. Driven on a fake clock — nothing here downloads or exits.
 */
class AutoUpdateIdleGateTest {
    private var clock = 0L
    private val events = mutableListOf<String>()

    private fun run(busy: () -> Boolean) = UpdateChecker.applyWhenIdle(
        isBusy = busy,
        apply = { events += "apply@$clock" },
        exit = { events += "exit@$clock" },
        maxDeferMs = 10 * 60_000L,
        pollMs = 60_000L,
        now = { clock },
        sleep = { clock += it },
    )

    @Test
    fun an_update_due_while_a_turn_runs_waits_until_the_daemon_is_idle() {
        var polls = 0
        run { polls++ < 3 } // busy for three minutes, then idle
        assertEquals(listOf("apply@180000", "exit@180000"), events, "the update must not interrupt a running turn")
    }

    @Test
    fun an_idle_daemon_updates_immediately() {
        run { false }
        assertEquals(listOf("apply@0", "exit@0"), events)
    }

    @Test
    fun a_turn_that_starts_during_the_download_still_holds_the_restart() {
        var applied = false
        var pollsAfterApply = 0
        UpdateChecker.applyWhenIdle(
            isBusy = { applied && pollsAfterApply++ < 2 },
            apply = { applied = true; events += "apply@$clock" },
            exit = { events += "exit@$clock" },
            maxDeferMs = 10 * 60_000L,
            pollMs = 60_000L,
            now = { clock },
            sleep = { clock += it },
        )
        assertEquals(listOf("apply@0", "exit@120000"), events)
    }

    @Test
    fun a_daemon_that_never_goes_idle_is_still_updated_after_the_longest_deferral() {
        run { true } // e.g. a dev server left running as a background job
        assertEquals(listOf("apply@600000", "exit@600000"), events, "deferral must be bounded")
    }

    @Test
    fun a_failing_busy_probe_counts_as_busy_but_is_still_bounded() {
        run { error("registry unavailable") }
        assertEquals(listOf("apply@600000", "exit@600000"), events)
    }

    @Test
    fun the_production_ceiling_is_one_check_interval() {
        assertEquals(24 * 60 * 60 * 1000L, UpdateChecker.MAX_DEFER_MS)
        assertTrue(UpdateChecker.IDLE_POLL_MS in 1..UpdateChecker.MAX_DEFER_MS)
    }
}
