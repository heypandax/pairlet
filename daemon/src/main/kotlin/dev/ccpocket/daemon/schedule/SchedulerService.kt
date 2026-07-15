package dev.ccpocket.daemon.schedule

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.MIN_SCHEDULE_INTERVAL_MS
import dev.ccpocket.protocol.ScheduleCreate
import dev.ccpocket.protocol.ScheduleInfo
import dev.ccpocket.protocol.ScheduleRepeat
import dev.ccpocket.protocol.ScheduleState
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Fires one due schedule: deliver [entry]'s prompt into its target session. Returns null on success,
 *  else a short user-facing reason (recorded as the entry's lastOutcome). Injected so the scheduler's
 *  timing logic is unit-testable without a session registry. */
fun interface ScheduleExecutor {
    suspend fun fire(entry: ScheduleEntry): String?
}

/**
 * The daemon-side scheduler (issue #137): one-shot + simple-repeat (fixed interval / daily-at-minute)
 * prompt deliveries, persisted through [ScheduleStore] so a daemon restart loses nothing.
 *
 * Timing model — deliberately NOT "delay until the next fire":
 *  - [runLoop] ticks every [TICK_MS] and each tick compares ABSOLUTE fire times against the injected
 *    [clock]. A laptop that slept through a fire time is caught on the first tick after wake (a long
 *    `delay(untilNext)` would instead drift by however long the process was suspended), and the same
 *    tick doubles as the periodic reconciliation pass.
 *  - Missed policy: a fire older than [missedGraceMs] when we notice it does NOT run — running a
 *    "9am standup prep" at 4pm is worse than skipping it. One-shots settle as "missed"; repeating
 *    schedules record "missed" and skip forward to their next future occurrence. Anything within the
 *    grace window runs late (the daemon was briefly down / asleep — still worth delivering).
 *
 * The clock is injected ([clock] epoch-millis + [zone] for daily-at-minute arithmetic) — never read
 * System time directly, so every timing branch is unit-testable.
 */
class SchedulerService(
    private val store: ScheduleStore,
    private val executor: ScheduleExecutor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val missedGraceMs: Long = MISSED_GRACE_MS,
) {
    private val log = logger("Scheduler")

    /** Validate + persist one schedule. Returns the updated [ScheduleState]; a validation failure
     *  rides [ScheduleState.error] and nothing is stored. [canonicalWorkdir] is the router-resolved
     *  real path (null = not a readable directory — same refusal wording as OpenSession). */
    fun create(req: ScheduleCreate, canonicalWorkdir: String?): ScheduleState {
        val err = validate(req, canonicalWorkdir)
        if (err != null) return state().copy(error = err)
        val now = clock()
        // A1 (#137): adopt the client's chosen id when present and not already taken, so the client can
        // cancel by an id it already holds (no reply-race, no signature reverse-lookup). Falls back to a
        // fresh UUID for the (legacy / colliding) empty-or-taken case — the entry.id uniqueness that
        // update()/remove() rely on is preserved either way.
        val clientId = req.clientId?.trim()?.takeIf { it.isNotEmpty() }
        val entry = ScheduleEntry(
            id = clientId ?: UUID.randomUUID().toString(),
            workdir = canonicalWorkdir!!,
            prompt = req.prompt.trim(),
            runAtMs = req.runAtMs,
            intervalMs = req.repeat?.intervalMs,
            dailyAtMinute = req.repeat?.dailyAtMinute,
            resumeId = req.resumeId,
            agent = req.agent,
            model = req.model,
            mode = req.mode,
            label = req.label?.trim()?.takeIf { it.isNotEmpty() },
            // a first fire already in the past runs at the next tick instead of being insta-missed —
            // covers client clock skew and "in 0 minutes" gestures
            nextRunAtMs = maxOf(req.runAtMs, now),
        )
        // atomic check-and-add: a client id that's already taken (or lost the race to a same-id
        // double-tap) falls back to a fresh UUID, so two entries can never share one id (#137/A1).
        val landed = if (store.addIfAbsent(entry)) entry else entry.copy(id = UUID.randomUUID().toString()).also { store.add(it) }
        log.info("schedule ${landed.id.take(8)}… created (next ${landed.nextRunAtMs}, repeat=${landed.repeating})")
        return state()
    }

    /** Delete [id] (no-op for unknown ids — the reply is simply the current list). */
    fun cancel(id: String): ScheduleState {
        if (store.remove(id)) log.info("schedule ${id.take(8)}… cancelled")
        return state()
    }

    /** The current list as the wire shape, soonest-next first (settled one-shots last). */
    fun state(): ScheduleState = ScheduleState(
        items = store.all()
            .sortedWith(compareBy(nullsLast()) { it.nextRunAtMs })
            .map {
                ScheduleInfo(
                    id = it.id,
                    workdir = it.workdir,
                    prompt = it.prompt.take(PROMPT_PREVIEW_MAX),
                    repeat = if (it.repeating) ScheduleRepeat(it.intervalMs, it.dailyAtMinute) else null,
                    resumeId = it.resumeId,
                    agent = it.agent,
                    label = it.label,
                    nextRunAtMs = it.nextRunAtMs,
                    lastRunAtMs = it.lastRunAtMs,
                    lastOutcome = it.lastOutcome,
                )
            },
    )

    /** The periodic pump: fire everything due, forever. Bounded ticks (never a long absolute delay) —
     *  see the class KDoc's sleep/wake rationale. */
    suspend fun runLoop() {
        while (true) {
            runCatching { checkDue() }.onFailure { log.warn("schedule tick failed: ${it.message}") }
            delay(TICK_MS)
        }
    }

    /** One reconciliation pass at [now]: run (or settle as missed) every entry whose fire time has
     *  passed. Sequential on purpose — deterministic under test, and concurrent fires would race the
     *  same session. Returns how many entries were EXECUTED (missed settlements don't count). */
    suspend fun checkDue(now: Long = clock()): Int {
        var fired = 0
        for (entry in store.all()) {
            val due = entry.nextRunAtMs ?: continue
            if (due > now) continue
            if (now - due > missedGraceMs) {
                // too stale to run — settle/skip, never execute
                log.info("schedule ${entry.id.take(8)}… missed its window (${now - due}ms late)")
                store.update(entry.copy(nextRunAtMs = nextOccurrence(entry, now), lastOutcome = OUTCOME_MISSED))
                continue
            }
            val outcome = runCatching { executor.fire(entry) }.getOrElse { it.message ?: "fire failed" }
            fired++
            log.info("schedule ${entry.id.take(8)}… fired → ${outcome ?: OUTCOME_OK}")
            store.update(
                entry.copy(
                    nextRunAtMs = nextOccurrence(entry, now),
                    lastRunAtMs = now,
                    lastOutcome = outcome ?: OUTCOME_OK,
                ),
            )
        }
        return fired
    }

    /** The entry's next future fire strictly after [now] (anchored to its planned cadence, so a late
     *  tick doesn't drift the schedule), or null for a one-shot (it just settled). */
    private fun nextOccurrence(entry: ScheduleEntry, now: Long): Long? {
        entry.intervalMs?.let { interval ->
            var next = entry.nextRunAtMs ?: entry.runAtMs
            while (next <= now) next += interval
            return next
        }
        entry.dailyAtMinute?.let { minute -> return nextDaily(minute, now) }
        return null
    }

    /** The next moment strictly after [afterMs] that reads [minute] past local midnight in [zone]. */
    private fun nextDaily(minute: Int, afterMs: Long): Long {
        var candidate = Instant.ofEpochMilli(afterMs).atZone(zone)
            .toLocalDate().atStartOfDay(zone).plusMinutes(minute.toLong())
        while (candidate.toInstant().toEpochMilli() <= afterMs) candidate = candidate.plusDays(1)
        return candidate.toInstant().toEpochMilli()
    }

    private fun validate(req: ScheduleCreate, canonicalWorkdir: String?): String? {
        if (req.prompt.isBlank()) return "prompt is required"
        if (canonicalWorkdir == null) return "not a readable directory: ${req.workdir}"
        if (store.all().size >= MAX_SCHEDULES) return "too many schedules (max $MAX_SCHEDULES) — cancel one first"
        val r = req.repeat
        if (r != null) {
            val interval = r.intervalMs
            val dailyAt = r.dailyAtMinute
            if ((interval != null) == (dailyAt != null)) {
                return "repeat must set exactly one of intervalMs / dailyAtMinute"
            }
            if (interval != null && interval < MIN_SCHEDULE_INTERVAL_MS) {
                return "repeat interval must be at least ${MIN_SCHEDULE_INTERVAL_MS / 1000}s"
            }
            if (dailyAt != null && dailyAt !in 0..1439) {
                return "dailyAtMinute must be 0..1439"
            }
        }
        return null
    }

    companion object {
        const val TICK_MS = 15_000L
        /** How late a fire may still run. Past this it's marked missed / skipped forward instead. */
        const val MISSED_GRACE_MS = 10 * 60 * 1000L
        const val MAX_SCHEDULES = 50
        const val OUTCOME_OK = "ok"
        const val OUTCOME_MISSED = "missed"
        /** [ScheduleInfo.prompt] cap — 50 entries × unbounded prompts must not approach the frame cap. */
        const val PROMPT_PREVIEW_MAX = 300
    }
}
