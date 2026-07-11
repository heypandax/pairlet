package dev.ccpocket.app.data

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.Pairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps one live relay link per paired computer ("我同时用多台电脑工作"): the PRIMARY repository keeps its
 * existing semantics untouched — it follows the global active binding and owns pairing, switching, settings,
 * push, and every content screen — while this coordinator maintains a pinned SATELLITE [PocketRepository]
 * for each OTHER binding, so the whole fleet is live at once (per-machine status/projects/approvals).
 *
 * The invariant is `satellites == bindings − primary.paired`, re-derived from observable primary state.
 * The collector-driven [sync] alone CANNOT uphold "never two links to one daemon": switchDaemon's whole
 * disconnect→re-point→dial sequence runs synchronously, so the snapshot collector only observes the final
 * state — after the primary already started dialing the machine whose satellite is still up. That overlap
 * is fatal, not cosmetic (the daemon keeps ONE E2E session per device; the second handshake deafens the
 * first link), so switchDaemon calls [retireSatellite] synchronously before dialing; sync() then spawns
 * the satellite for the machine being switched AWAY from as usual.
 *
 * [switchTo] (issue #103) is the other way to keep that invariant while switching: instead of retiring the
 * target's hot satellite and re-dialing cold (a several-second handshake that threw away a Ready link), it
 * PROMOTES the satellite to primary and demotes the outgoing primary to satellite — two live links swap
 * roles, nobody dials, so each daemon still holds exactly one link throughout. The whole swap runs
 * synchronously on the UI thread for the same snapshot-conflation reason switchDaemon relies on. Promotion
 * is gated on [promoteHotSatellites] because the shell must FOLLOW the swap: only a UI whose repo reference
 * tracks [primary] (the desktop shell) may enable it — mobile binds one repo instance for the app's life
 * and keeps the cold path.
 *
 * Deliberately NOT here yet: per-machine push (the platform singleton stays with the primary — moot on
 * desktop, where push is a no-op), satellite session-opening (watch pane), and the always-on/on-demand
 * connection policy — those follow the "真并发交互缺口" design round.
 */
class FleetCoordinator(private val scope: CoroutineScope, initialPrimary: PocketRepository) {

    private val primaryState = mutableStateOf(initialPrimary)

    /** The repository the shell is driving. Snapshot-observable: a [switchTo] promote re-points it, and
     *  every Compose read (or snapshotFlow lambda) tracking it re-derives against the new instance. */
    val primary: PocketRepository get() = primaryState.value

    /** Opt-in for [switchTo]'s hot-satellite promotion (issue #103). Off by default: a shell whose UI holds
     *  a FIXED repo reference (mobile) must never have its primary swapped out from under it. */
    var promoteHotSatellites = false

    /** Live links for every binding except the primary's, keyed by accountId. Compose-observable. */
    val satellites = mutableStateMapOf<String, PocketRepository>()

    /**
     * Last loaded directory list per ACCOUNT — data outlives the link that fetched it. Switching machines
     * tears links down and rebuilds them (primary even clears its lists on disconnect), which used to blank
     * the fleet RUNNING rows for the seconds until the new handshake + list landed; reads fall back here so
     * the fleet keeps showing last-known state while the status dots tell the connection story.
     */
    private val lastDirs = mutableStateMapOf<String, List<dev.ccpocket.protocol.DirectoryEntry>>()

    private var watchJob: Job? = null
    private var dirsJob: Job? = null

    /** A machine's directories: its live link when loaded, else the last snapshot any link fetched. */
    fun dirsFor(accountId: String): List<dev.ccpocket.protocol.DirectoryEntry> {
        val link = repoFor(accountId)
        if (link != null && link.directoriesLoaded.value && link.directories.isNotEmpty()) return link.directories.toList()
        return lastDirs[accountId] ?: emptyList()
    }

    /** The repo that speaks for [accountId] right now — the primary when focused, else its satellite. */
    fun repoFor(accountId: String): PocketRepository? =
        if (primary.paired.value?.accountId == accountId) primary else satellites[accountId]

    /** Every live link, primary first — the aggregation surfaces iterate this. */
    fun repos(): List<PocketRepository> = listOf(primary) + satellites.values

    /** Tear down [accountId]'s satellite NOW — called by [PocketRepository.switchDaemon] BEFORE it dials
     *  that machine (see the class doc: the collector-driven [sync] would run too late to prevent the
     *  primary and the satellite from holding two same-device links to one daemon). Idempotent. */
    fun retireSatellite(accountId: String) {
        satellites.remove(accountId)?.disconnect()
    }

    /**
     * Switch the shell to [target], reusing its HOT satellite link when one exists (issue #103): the
     * satellite becomes the primary (link, loaded directories and all — no handshake, no re-list), the
     * outgoing primary becomes its own machine's satellite (link kept; chat-scoped state cleared exactly
     * like a cold switch would). No hot satellite — target still dialing, fleet not up, promotion not
     * enabled — falls back to the cold [PocketRepository.switchDaemon].
     *
     * Single-link invariant: nothing here ever dials. The target's only link changes owner; the outgoing
     * primary's only link is re-keyed into [satellites] within this same synchronous call, so the
     * collector-driven [sync] that follows sees it already present and spawns nothing. A promoted
     * satellite that isn't Ready yet (machine offline / reconnecting) is still promoted — the UI shows its
     * honest phase instantly and its own battle-tested reconnect loop keeps working, which is exactly what
     * the cold path would converge to after paying the handshake.
     */
    fun switchTo(target: PairedDaemon) {
        val current = primary
        if (current.paired.value?.accountId == target.accountId && current.sessionActive.value) return
        val hot = if (promoteHotSatellites) satellites[target.accountId] else null
        if (hot == null || !hot.sessionActive.value) { current.switchDaemon(target); return }

        satellites.remove(target.accountId) // it's becoming the primary — never two owners for one link
        hot.adoptShellState(current)        // prefs/pairing-list mirrors seeded at construction — see its doc
        current.demoteToSatellite()         // close the chat view (idle convos reclaimed), keep the link
        current.onBeforeSwitch = null       // only the primary owns the retire-before-dial invariant
        hot.onBeforeSwitch = ::retireSatellite
        Pairing.setActive(target.accountId) // same persistence a cold switch does — restart lands here
        primaryState.value = hot
        // the outgoing primary is its machine's satellite from THIS instant — before any sync() runs —
        // so the invariant loop finds the slot filled instead of dialing a second link to that daemon
        current.paired.value?.accountId?.let { satellites[it] = current }
        // freshen the preloaded list (it may be minutes old); doubles as a liveness write on the adopted
        // link — a silently-dead socket trips the reconnect path now rather than on the first user action
        hot.refreshDirectoriesSilently()
    }

    /** Re-derive the satellite set from the primary's observable state. Idempotent. */
    fun sync() {
        // onboarding/demo/disconnected: single-connection mode — the fleet lights up only alongside a
        // healthy primary (satellites REALLY connect, which a demo or the pairing screen must never do)
        val fleetMode = primary.sessionActive.value && !primary.demoMode.value
        val want: Map<String, PairedDaemon> =
            if (!fleetMode) emptyMap()
            else primary.pairedList.filterNot { it.accountId == primary.paired.value?.accountId }.associateBy { it.accountId }
        satellites.keys.toList().forEach { id ->
            if (id !in want) satellites.remove(id)?.disconnect()
        }
        want.forEach { (id, binding) ->
            if (id !in satellites) {
                satellites[id] = PocketRepository(scope, pinnedTo = binding).also { it.startRelay() }
            }
        }
        // an unpaired binding's snapshot goes with it (retired satellites keep theirs — that's the point)
        val bound = primary.pairedList.map { it.accountId }.toSet()
        lastDirs.keys.toList().forEach { if (it !in bound) lastDirs.remove(it) }
    }

    /** Observe the primary's binding/connection state and keep the satellite set in step. */
    fun start() {
        if (watchJob != null) return
        // the switch-order invariant lives with the component that owns it (see retireSatellite doc)
        primary.onBeforeSwitch = ::retireSatellite
        watchJob = scope.launch {
            snapshotFlow {
                Triple(primary.pairedList.toList(), primary.paired.value?.accountId, primary.sessionActive.value to primary.demoMode.value)
            }.collect {
                // one bad sync (e.g. a satellite failing to construct) must not kill the collector —
                // a dead invariant loop would silently strand a stale same-credential satellite
                runCatching { sync() }
            }
        }
        dirsJob = scope.launch {
            // harvest every link's loaded directory list into the per-account snapshot store
            snapshotFlow {
                repos().mapNotNull { r ->
                    val id = r.paired.value?.accountId ?: return@mapNotNull null
                    if (r.directoriesLoaded.value && r.directories.isNotEmpty()) id to r.directories.toList() else null
                }
            }.collect { loaded -> loaded.forEach { (id, dirs) -> lastDirs[id] = dirs } }
        }
    }

    /** iOS suspends every socket in the background — fan the foreground reconnect out to the fleet. */
    fun onAppForeground() {
        satellites.values.forEach { it.onAppForeground() }
    }

    /**
     * Jump to a running project on ANY machine (the fleet "Running" rows): on the focused machine it
     * opens straight into the project's LIVE session; on another it switches the primary over first and
     * does the same the moment the link is Ready — same wait-for-Ready idea as the push-tap deep link.
     * Bounded so a machine that never comes up doesn't leave a ghost navigation.
     */
    fun focusProject(accountId: String, path: String) = onMachine(accountId) { openLiveSession(path) }

    /** The RUNNING row's secondary affordance (issue #49): list the project's sessions — switching machines
     *  when needed — WITHOUT auto-resuming the live one, so the user can pick a historical session. */
    fun browseProject(accountId: String, path: String) = onMachine(accountId) { primary.listSessions(path) }

    /** Run [act] with the primary parked on [accountId]: immediately when already there, else switch and
     *  act the moment the link is Ready — bounded so a machine that never comes up leaves no ghost nav.
     *  Switching goes through [switchTo]: with a hot satellite the promoted link is Ready at once, so the
     *  snapshotFlow below completes on its first emission and [act] runs without any visible wait. */
    private fun onMachine(accountId: String, act: () -> Unit) {
        if (primary.paired.value?.accountId == accountId) { act(); return }
        val target = primary.pairedList.firstOrNull { it.accountId == accountId } ?: return
        switchTo(target)
        scope.launch {
            val ready = withTimeoutOrNull(30_000) {
                snapshotFlow { primary.phase.value }.first { it == ConnPhase.Ready }
            }
            // only act if we're still parked on that machine (the user may have switched again meanwhile)
            if (ready != null && primary.paired.value?.accountId == accountId) act()
        }
    }

    /**
     * A RUNNING row means "take me to what's working", not "show me a list": list the project's sessions,
     * and the moment the reply lands (the Sessions handler writes sessionsDir+items together) resume the
     * live one. No live session in the reply → the list stays up and the user picks — never a dead end.
     */
    private fun openLiveSession(path: String) {
        primary.listSessions(path)
        scope.launch {
            val arrived = withTimeoutOrNull(10_000) {
                snapshotFlow { primary.sessionsDir.value to primary.sessions.toList() }
                    .first { (dir, items) -> dir == path && items.isNotEmpty() }
            } ?: return@launch
            if (primary.sessionsDir.value != path) return@launch // user navigated elsewhere meanwhile
            // the dir-level RUNNING badge (an open convo in the registry — possibly another device's, or
            // idle) is broader than the summary-level live flag (transcript written just now), so a live
            // match can legitimately be absent — fall back to the newest session rather than doing nothing
            val target = arrived.second.firstOrNull { it.live || it.busy } ?: arrived.second.first()
            // already sitting in that session → don't churn the process (reopen risks a needless fork)
            if (primary.convoId.value != null && primary.sessionKey.value == target.sessionId) return@launch
            primary.openSession(
                wd = path, resumeId = target.sessionId, title = target.title,
                agent = target.agent ?: dev.ccpocket.protocol.AgentKind.CLAUDE,
            )
        }
    }
}

/**
 * Process-wide handle so the fleet read-model (extensions on the primary repo) can aggregate across links
 * without threading the coordinator through every screen signature. Set once at app root; null in unit
 * tests and previews, where the surfaces degrade to single-repo behavior.
 */
object FleetRuntime {
    var coordinator: FleetCoordinator? = null

    /** Non-null only when [repo] is the primary of the installed coordinator. */
    fun forPrimary(repo: PocketRepository): FleetCoordinator? = coordinator?.takeIf { it.primary === repo }
}
