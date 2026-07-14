package dev.ccpocket.daemon.schedule

import dev.ccpocket.protocol.ScheduleCreate
import dev.ccpocket.protocol.ScheduleRepeat
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scheduler's timing core (issue #137), driven by an injected fake clock — due detection,
 * one-shot settlement, the missed-window policy (grace back-run vs. missed/skip-forward),
 * interval + daily-at-minute advancement, cancellation, and create validation.
 */
class SchedulerServiceTest {

    private val zone = ZoneId.of("UTC")

    private class Harness(grace: Long = SchedulerService.MISSED_GRACE_MS) {
        var now = 1_000_000_000_000L // an arbitrary fixed epoch
        val fired = mutableListOf<ScheduleEntry>()
        var outcome: String? = null // what the executor reports back
        val store = ScheduleStore.load(Files.createTempDirectory("ccp-schedt").resolve("s.json").toFile())
        val svc = SchedulerService(
            store,
            executor = { e -> fired += e; outcome },
            clock = { now },
            zone = ZoneId.of("UTC"),
            missedGraceMs = grace,
        )
    }

    private fun create(h: Harness, runAtMs: Long, repeat: ScheduleRepeat? = null, prompt: String = "go") =
        h.svc.create(ScheduleCreate(workdir = "/w", prompt = prompt, runAtMs = runAtMs, repeat = repeat), canonicalWorkdir = "/w")

    // ---- due detection + one-shot lifecycle ----

    @Test
    fun one_shot_fires_at_its_moment_and_settles() = runBlocking {
        val h = Harness()
        create(h, runAtMs = h.now + 60_000)
        assertEquals(0, h.svc.checkDue(), "not due yet — nothing fires")
        h.now += 59_999
        assertEquals(0, h.svc.checkDue(), "1ms early is still early")
        h.now += 1
        assertEquals(1, h.svc.checkDue(), "due exactly at runAt")
        assertEquals("go", h.fired.single().prompt)
        val info = h.svc.state().items.single()
        assertNull(info.nextRunAtMs, "a fired one-shot settles (no next run)")
        assertEquals("ok", info.lastOutcome)
        assertEquals(0, h.svc.checkDue(), "a settled one-shot never fires again")
    }

    @Test
    fun executor_failure_is_recorded_as_the_outcome() = runBlocking {
        val h = Harness()
        h.outcome = "session unavailable"
        create(h, runAtMs = h.now)
        h.svc.checkDue()
        assertEquals("session unavailable", h.svc.state().items.single().lastOutcome)
    }

    // ---- missed policy: grace back-run vs. missed ----

    @Test
    fun a_fire_within_the_grace_window_runs_late() = runBlocking {
        val h = Harness(grace = 10 * 60_000)
        create(h, runAtMs = h.now + 1_000)
        h.now += 9 * 60_000 // e.g. the laptop slept through the moment, woke 9 min later
        assertEquals(1, h.svc.checkDue(), "within grace → back-run")
        assertEquals("ok", h.svc.state().items.single().lastOutcome)
    }

    @Test
    fun a_one_shot_past_the_grace_window_is_marked_missed_not_run() = runBlocking {
        val h = Harness(grace = 10 * 60_000)
        create(h, runAtMs = h.now + 1_000)
        h.now += 60 * 60_000 // an hour late — running now would be wrong
        assertEquals(0, h.svc.checkDue(), "missed settlements never execute")
        assertTrue(h.fired.isEmpty())
        val info = h.svc.state().items.single()
        assertEquals("missed", info.lastOutcome)
        assertNull(info.nextRunAtMs)
    }

    @Test
    fun a_repeat_past_the_grace_window_skips_forward_instead_of_settling() = runBlocking {
        val h = Harness(grace = 10 * 60_000)
        val first = h.now + 1_000
        create(h, runAtMs = first, repeat = ScheduleRepeat(intervalMs = 3_600_000))
        h.now = first + 2 * 3_600_000 // slept through two occurrences
        assertEquals(0, h.svc.checkDue())
        val info = h.svc.state().items.single()
        assertEquals("missed", info.lastOutcome)
        assertEquals(first + 3 * 3_600_000, info.nextRunAtMs, "skips to the next FUTURE occurrence on the original cadence")
    }

    // ---- repeat advancement ----

    @Test
    fun interval_repeat_advances_on_the_planned_cadence_not_the_late_tick() = runBlocking {
        val h = Harness()
        val first = h.now + 1_000
        create(h, runAtMs = first, repeat = ScheduleRepeat(intervalMs = 60_000))
        h.now = first + 30_000 // fired 30s late (within grace)
        assertEquals(1, h.svc.checkDue())
        assertEquals(first + 60_000, h.svc.state().items.single().nextRunAtMs, "anchored to runAt, no drift")
        h.now = first + 60_000
        assertEquals(1, h.svc.checkDue(), "and the next occurrence fires on time")
    }

    @Test
    fun daily_at_minute_advances_to_the_next_local_occurrence() = runBlocking {
        val h = Harness()
        // 1_000_000_000_000ms = 2001-09-09T01:46:40Z; schedule daily at 02:00 UTC (minute 120)
        val first = h.now + 800_000 // ≈02:00 that same day
        create(h, runAtMs = first, repeat = ScheduleRepeat(dailyAtMinute = 120))
        h.now = first
        assertEquals(1, h.svc.checkDue())
        val next = h.svc.state().items.single().nextRunAtMs
        assertNotNull(next)
        // the next fire is 02:00 UTC the FOLLOWING day
        val z = java.time.Instant.ofEpochMilli(next).atZone(zone)
        assertEquals(2, z.hour); assertEquals(0, z.minute)
        assertTrue(next > h.now && next <= h.now + 24 * 3_600_000)
    }

    // ---- cancel ----

    @Test
    fun cancel_removes_and_the_entry_never_fires() = runBlocking {
        val h = Harness()
        create(h, runAtMs = h.now + 1_000)
        val id = h.svc.state().items.single().id
        assertTrue(h.svc.cancel(id).items.isEmpty())
        h.now += 5_000
        assertEquals(0, h.svc.checkDue())
        // unknown id: a plain no-op reply, never an exception
        assertTrue(h.svc.cancel("nope").items.isEmpty())
    }

    // ---- create validation + immediate-past clamp ----

    @Test
    fun create_validates_prompt_workdir_and_repeat_shape() {
        val h = Harness()
        assertNotNull(create(h, h.now, prompt = "  ").error, "blank prompt refused")
        assertNotNull(
            h.svc.create(ScheduleCreate("/nope", "go", h.now), canonicalWorkdir = null).error,
            "unresolvable workdir refused",
        )
        assertNotNull(create(h, h.now, repeat = ScheduleRepeat()).error, "repeat with neither field refused")
        assertNotNull(
            create(h, h.now, repeat = ScheduleRepeat(intervalMs = 1_000, dailyAtMinute = 10)).error,
            "repeat with both fields refused",
        )
        assertNotNull(create(h, h.now, repeat = ScheduleRepeat(intervalMs = 5_000)).error, "sub-minute interval refused")
        assertNotNull(create(h, h.now, repeat = ScheduleRepeat(dailyAtMinute = 1440)).error, "minute out of range refused")
        assertTrue(h.svc.state().items.isEmpty(), "nothing was stored by the refused creates")
    }

    @Test
    fun a_run_time_already_past_fires_on_the_next_tick_instead_of_insta_missing() = runBlocking {
        val h = Harness(grace = 10 * 60_000)
        create(h, runAtMs = h.now - 60 * 60_000) // client clock skew / "now" gestures
        assertEquals(1, h.svc.checkDue(), "clamped to creation time — runs, never born-missed")
    }
}
