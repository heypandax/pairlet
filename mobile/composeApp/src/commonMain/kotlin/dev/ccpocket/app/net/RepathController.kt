package dev.ccpocket.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What asked for an idle re-path evaluation (TRANSPORT-AUTO-REPATH-V1 4.1). Telemetry dimension — fixed enum. */
enum class RepathTrigger(val wire: String) {
    PeerOnline("peer_online"), Foreground("foreground"), DirectUrlChanged("direct_url_changed"), Timer("timer"),
}

/** How one evaluation ended (spec 4.2 / 4.8). Telemetry dimension — fixed enum, never an address. */
enum class RepathResult(val wire: String) {
    SwitchedDirect("switched_direct"), FellBackRelay("fell_back_relay"), DeferredBusy("deferred_busy"),
    ProbeFailed("probe_failed"), Ineligible("ineligible"), CoolingDown("cooling_down"), RecentlySwitched("recently_switched"),
}

/**
 * #404: while the link sits on relay, occasionally check — only when idle — whether the direct address answers,
 * and if so do one planned reconnect (which is direct-first). All inputs are injected so the timing and
 * ordering are unit-testable; the repository owns exactly one instance and [reset]s it on disconnect / switch.
 *
 * Signals only *request* an evaluation; evaluations run one at a time and requests landing while one is in
 * flight merge into a single follow-up (a conflated queue). Nothing here tears a link down except [switchNow],
 * and that only after a successful probe — probing first is what keeps a healthy relay link untouched (#145).
 */
class RepathController(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val enabled: () -> Boolean,
    private val isOnRelay: () -> Boolean,
    private val directUrl: () -> String?,
    private val coolingDown: () -> Boolean,
    private val eligible: (String) -> Boolean,
    private val probe: suspend (host: String, port: Int) -> Boolean,
    private val isIdle: () -> Boolean,
    private val switchNow: () -> Unit,
    private val report: (RepathTrigger, RepathResult) -> Unit,
) {
    private var queue = Channel<RepathTrigger>(Channel.CONFLATED)
    private var worker: Job? = null
    private val delayed = HashMap<RepathTrigger, Job>()
    private var recheckJob: Job? = null
    private var busyRechecks = 0
    private var timerJob: Job? = null
    private var timerIntervalMs = TIMER_START_MS
    private var lastSwitchAt: Long? = null
    /** Trigger of the planned switch whose outcome the next [onAttached] reports. */
    private var switchPending: RepathTrigger? = null

    /** A signal from spec 4.1's table; evaluated after that signal's delay. Same-trigger repeats replace the wait. */
    fun request(trigger: RepathTrigger) {
        if (!enabled()) return
        delayed.remove(trigger)?.cancel()
        delayed[trigger] = scope.launch {
            delay(delayFor(trigger))
            delayed.remove(trigger)
            enqueue(trigger)
        }
    }

    /**
     * The transport attached. On relay: start (or keep) the backoff timer. On direct: stop it and reset the
     * backoff. Either way, a planned switch in flight now has its outcome.
     */
    fun onAttached(direct: Boolean) {
        switchPending?.let { report(it, if (direct) RepathResult.SwitchedDirect else RepathResult.FellBackRelay) }
        switchPending = null
        if (direct) {
            timerJob?.cancel(); timerJob = null
            timerIntervalMs = TIMER_START_MS
        } else if (enabled() && timerJob?.isActive != true) {
            timerJob = scope.launch {
                while (true) {
                    delay(timerIntervalMs)
                    // back off whatever the result — a failed probe must not turn into a 60 s poll forever
                    timerIntervalMs = (timerIntervalMs * 2).coerceAtMost(TIMER_CAP_MS)
                    enqueue(RepathTrigger.Timer)
                }
            }
        }
    }

    /** Disconnect / switch computer / unpair: every pending wait, timer and in-flight evaluation is void. */
    fun reset() {
        delayed.values.forEach { it.cancel() }; delayed.clear()
        recheckJob?.cancel(); recheckJob = null
        timerJob?.cancel(); timerJob = null
        worker?.cancel(); worker = null
        queue.close()
        queue = Channel(Channel.CONFLATED)
        busyRechecks = 0
        timerIntervalMs = TIMER_START_MS
        lastSwitchAt = null
        switchPending = null
    }

    internal val timerIntervalForTest: Long get() = timerIntervalMs
    internal val timerRunningForTest: Boolean get() = timerJob?.isActive == true

    private fun enqueue(trigger: RepathTrigger) {
        if (!enabled()) return
        if (worker?.isActive != true) {
            val q = queue
            worker = scope.launch { for (t in q) evaluate(t) }
        }
        queue.trySend(trigger)
    }

    private suspend fun evaluate(trigger: RepathTrigger) {
        // spec 4.1 preconditions: off relay or nothing to switch to means there is no question to answer
        if (!enabled() || !isOnRelay() || switchPending != null) return
        val url = directUrl() ?: return
        if (coolingDown()) return report(trigger, RepathResult.CoolingDown)
        if (!eligible(url)) return report(trigger, RepathResult.Ineligible)
        val host = hostOf(url)
        val port = portOf(url)
        if (host == null || port == null) return report(trigger, RepathResult.Ineligible)
        // no cooldown on a failed probe: nothing was torn down, and the timer's own backoff paces retries
        if (!probe(host, port)) return report(trigger, RepathResult.ProbeFailed)
        if (!isIdle()) {
            report(trigger, RepathResult.DeferredBusy)
            if (busyRechecks < MAX_BUSY_RECHECKS && recheckJob?.isActive != true) {
                busyRechecks++
                recheckJob = scope.launch { delay(BUSY_RECHECK_MS); enqueue(trigger) }
            }
            return
        }
        busyRechecks = 0
        val last = lastSwitchAt
        if (last != null && now() - last < MIN_SWITCH_GAP_MS) return report(trigger, RepathResult.RecentlySwitched)
        // the probe suspended — the world may have moved (a reconnect, a reset) while it ran
        if (!isOnRelay()) return
        lastSwitchAt = now()
        switchPending = trigger
        switchNow()
    }

    companion object {
        const val PEER_ONLINE_DELAY_MS = 5_000L
        const val FOREGROUND_DELAY_MS = 2_000L
        const val DIRECT_URL_DELAY_MS = 1_000L
        const val TIMER_START_MS = 60_000L
        const val TIMER_CAP_MS = 15 * 60_000L
        const val BUSY_RECHECK_MS = 15_000L
        const val MAX_BUSY_RECHECKS = 4
        const val MIN_SWITCH_GAP_MS = 60_000L
        const val PROBE_TIMEOUT_MS = 1_500L

        fun delayFor(trigger: RepathTrigger): Long = when (trigger) {
            RepathTrigger.PeerOnline -> PEER_ONLINE_DELAY_MS
            RepathTrigger.Foreground -> FOREGROUND_DELAY_MS
            RepathTrigger.DirectUrlChanged -> DIRECT_URL_DELAY_MS
            RepathTrigger.Timer -> 0L
        }
    }
}
