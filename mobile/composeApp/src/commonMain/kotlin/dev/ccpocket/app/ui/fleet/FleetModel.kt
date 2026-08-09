package dev.ccpocket.app.ui.fleet

import androidx.compose.runtime.mutableStateListOf
import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.data.FleetRuntime
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.ui.tilde
import dev.ccpocket.protocol.PermissionAsk

/**
 * Fleet view-model seam ("Fleet Mobile.html" / "Fleet Desktop.html" designs): the machine-first surfaces read
 * these rows instead of the repositories directly. With the [dev.ccpocket.app.data.FleetCoordinator] installed
 * every paired machine has its own live link (primary = the focused one, satellites = the rest), so each row
 * carries real per-machine status/activity/projects; without a coordinator (tests, previews) the surfaces
 * degrade to single-repo behavior with honest "not connected" placeholders. Demo mode feeds the four-machine
 * design scenario so the whole triage flow is showable end-to-end.
 *
 * Everything here is DATA, never a sentence (Supporting Surfaces UI 2.0). The model used to concatenate
 * English fragments — "reconnecting…", "idle", "active now" — which no translation could reach; the words are
 * now chosen at the call site from resources, and only values the daemon actually owns (names, paths, tools,
 * previews) stay literal.
 */
enum class MachineOs { MAC, LINUX, WIN }

enum class MachineStatus { ONLINE, RECONNECTING, OFFLINE }

/**
 * What a machine is doing right now.
 *
 * [Unknown] is the honest case for a machine that is not online: we know its link state, not its work. It is
 * NOT a fourth status — [MachineStatus] still has exactly three.
 */
sealed interface MachineActivity {
    /** Nothing is known about this machine's work (it isn't online). The status line carries the news. */
    data object Unknown : MachineActivity

    /** Paired, but this app holds no live link for it — the row still switches to it. */
    data object NotConnected : MachineActivity

    /** Online with nothing running. */
    data object Idle : MachineActivity

    /** [count] approvals held here; [tool] and [preview] are the daemon's own tokens, rendered verbatim. */
    data class WaitingApproval(val count: Int, val tool: String, val preview: String) : MachineActivity

    /** The conversation this machine is on. [title] may be blank — the renderer then says "session". */
    data class InSession(val title: String, val path: String?) : MachineActivity

    /** [count] project folders open or busy on this machine. Folders, NOT sessions: one folder can host
     *  several live sessions, and the repository supplies no per-machine session total. */
    data class Active(val count: Int, val path: String?) : MachineActivity
}

/**
 * When the machine was last known to be there.
 *
 * A live link only ever knows [ActiveNow]; [Ago] exists because the demo scenario carries one, and is stated
 * in whole minutes so the words come from the shared `time_*` resources rather than a baked English string.
 */
sealed interface MachineLastSeen {
    data object Unknown : MachineLastSeen
    data object ActiveNow : MachineLastSeen
    data class Ago(val minutes: Int) : MachineLastSeen
}

data class FleetMachine(
    val accountId: String,
    val name: String,
    val os: MachineOs,
    val status: MachineStatus,
    val activity: MachineActivity,
    val lastSeen: MachineLastSeen,
    val pending: Int,       // approvals waiting on this machine (AttentionBadge)
    val current: Boolean,   // the binding the app is talking to right now
)

data class AttentionEntry(
    val askId: String,
    val accountId: String,
    val machineName: String,
    val os: MachineOs,
    val tool: String,          // mono tool token ("Bash" / "Write")
    val title: String,         // human verb ("Run command" / "Edit file")
    val preview: String,
    val seconds: Int,          // countdown budget when it entered the queue (PermissionSheet's 30s convention)
    val current: Boolean,      // resolvable through the live connection (repo.resolve)
    val convoId: String? = null,
    val workdir: String? = null,
    val sessionId: String? = null,
    val origin: String? = null,
)

data class FinishedEntry(
    val machineName: String,
    val os: MachineOs,
    val title: String,
    val ok: Boolean,
    val minutesAgo: Int,
)

/** The counts behind the fleet summary line. Pure, so the sentence is assembled from resources. */
data class FleetSummary(val machines: Int, val online: Int, val waiting: Int)

/** Paired bindings don't carry an OS (the QR has only account identity), so read it off the user's own naming. */
fun osFromName(name: String): MachineOs {
    val n = name.lowercase()
    return when {
        "win" in n -> MachineOs.WIN
        "linux" in n || "ubuntu" in n || "debian" in n || "nix" in n -> MachineOs.LINUX
        else -> MachineOs.MAC
    }
}

/** One machine's live row, read off whichever repo (primary or satellite) holds its link. */
private fun liveMachine(repo: PocketRepository, binding: dev.ccpocket.app.pairing.PairedDaemon, current: Boolean): FleetMachine {
    val name = binding.displayName()
    val status = when (repo.phase.value) {
        ConnPhase.Ready -> MachineStatus.ONLINE
        ConnPhase.Reconnecting, ConnPhase.Connecting -> MachineStatus.RECONNECTING
        else -> MachineStatus.OFFLINE
    }
    val waiting = repo.pendingApprovals.values.toList()
    val ask = waiting.firstOrNull()?.ask
    val open = repo.directories.count { it.open || it.busy }
    val activity = when {
        // a machine we are not talking to reports its LINK, not its work — inventing an activity for it
        // would be the one place this surface shows something no daemon said
        status != MachineStatus.ONLINE -> MachineActivity.Unknown
        ask != null -> MachineActivity.WaitingApproval(waiting.size, ask.tool, ask.inputPreview.take(28))
        current && repo.convoId.value != null ->
            MachineActivity.InSession(repo.chatTitle.value.orEmpty(), repo.workdir.value?.let(::tilde))
        open > 0 -> MachineActivity.Active(open, repo.directories.firstOrNull { it.open || it.busy }?.path?.let(::tilde))
        else -> MachineActivity.Idle
    }
    return FleetMachine(
        accountId = binding.accountId, name = name, os = osFromName(name), status = status,
        activity = activity,
        lastSeen = if (status == MachineStatus.ONLINE) MachineLastSeen.ActiveNow else MachineLastSeen.Unknown,
        pending = waiting.size, current = current,
    )
}

fun PocketRepository.fleetMachines(): List<FleetMachine> {
    if (demoMode.value) return DemoFleet.machines()
    val fleet = FleetRuntime.forPrimary(this)
    val activeId = paired.value?.accountId
    return pairedList.map { d ->
        val current = d.accountId == activeId
        val repo = if (current) this else fleet?.satellites?.get(d.accountId)
        if (repo != null) {
            liveMachine(repo, d, current)
        } else {
            // no live link for this binding (no coordinator installed, or its satellite is being rebuilt):
            // status is genuinely unknown — say so instead of inventing one
            FleetMachine(
                accountId = d.accountId, name = d.displayName(), os = osFromName(d.displayName()),
                status = MachineStatus.OFFLINE, activity = MachineActivity.NotConnected,
                lastSeen = MachineLastSeen.Unknown, pending = 0, current = false,
            )
        }
    }
}

/**
 * Approvals waiting across every live link. Each daemon snapshot is account-wide, so this stays complete
 * even when a bridge-created conversation has no ordinary session row and its push never reached the phone.
 */
fun PocketRepository.fleetAttention(): List<AttentionEntry> {
    if (demoMode.value) return DemoFleet.attention()
    val links = FleetRuntime.forPrimary(this)?.repos() ?: listOf(this)
    val now = dev.ccpocket.app.epochMillis()
    return links.flatMap { repo ->
        val d = repo.paired.value ?: return@flatMap emptyList()
        val name = d.displayName()
        repo.pendingApprovals.values.map { row ->
            val ask = row.ask
            AttentionEntry(
                askId = ask.askId, accountId = d.accountId, machineName = name, os = osFromName(name),
                tool = ask.tool, title = ask.title, preview = ask.diff ?: ask.inputPreview,
                seconds = row.expiresAt?.let { ((it - now + 999) / 1000).toInt().coerceAtLeast(0) }
                    ?: ask.timeoutSec ?: 30,
                current = repo === this,
                convoId = ask.convoId,
                workdir = row.workdir ?: repo.workdir.value,
                sessionId = row.sessionId,
                origin = row.origin,
            )
        }
    }
}

fun PocketRepository.fleetFinished(): List<FinishedEntry> =
    if (demoMode.value) DemoFleet.finished else emptyList()

/** "4 computers · 3 online · 2 approvals waiting" as COUNTS — the words are chosen by the renderer. */
fun PocketRepository.fleetSummary(): FleetSummary {
    val machines = fleetMachines()
    return FleetSummary(
        machines = machines.size,
        online = machines.count { it.status == MachineStatus.ONLINE },
        waiting = fleetAttention().size,
    )
}

/** Cross-machine pulls only: what the Chat banner shows (never the ask already on screen for this machine). */
fun PocketRepository.crossMachineAttention(): List<AttentionEntry> = fleetAttention().filter { !it.current }

/**
 * PREVIEW/demo fleet — the four-machine scenario from the design boards, so App Store reviewers and the
 * screenshot pipeline can walk the whole triage loop without four real daemons. Demo Allow/Deny resolves
 * locally (drops the row); nothing touches the network in demo mode.
 */
object DemoFleet {
    private val resolved = mutableStateListOf<String>()

    private val allMachines = listOf(
        FleetMachine(
            "demo-mbp", "Lidapeng-MacBook", MachineOs.MAC, MachineStatus.ONLINE,
            MachineActivity.Active(2, "~/proj/app/cc-pocket"), MachineLastSeen.ActiveNow, 0, current = true,
        ),
        FleetMachine(
            "demo-studio", "mac-studio", MachineOs.MAC, MachineStatus.ONLINE,
            MachineActivity.WaitingApproval(1, "Bash", "./gradlew clean"), MachineLastSeen.Ago(2), 1, current = false,
        ),
        FleetMachine(
            "demo-devbox", "devbox-linux", MachineOs.LINUX, MachineStatus.ONLINE,
            MachineActivity.InSession("pytest -x", "~/src/relay"), MachineLastSeen.ActiveNow, 1, current = false,
        ),
        FleetMachine(
            "demo-win", "win-desktop", MachineOs.WIN, MachineStatus.OFFLINE,
            MachineActivity.Unknown, MachineLastSeen.Ago(2 * 24 * 60), 0, current = false,
        ),
    )
    private val allAttention = listOf(
        AttentionEntry("demo-ask-1", "demo-studio", "mac-studio", MachineOs.MAC, "Bash", "Run command", "rm -rf ./build && ./gradlew clean", 23, current = false),
        AttentionEntry("demo-ask-2", "demo-devbox", "devbox-linux", MachineOs.LINUX, "Write", "Edit file", "~/src/relay/src/main/kotlin/Relay.kt  +42 −7", 41, current = false),
    )
    val finished = listOf(
        FinishedEntry("Lidapeng-MacBook", MachineOs.MAC, "Refactor auth module", ok = true, minutesAgo = 4),
        FinishedEntry("devbox-linux", MachineOs.LINUX, "Fix stream parser test", ok = false, minutesAgo = 12),
    )

    fun attention(): List<AttentionEntry> = allAttention.filterNot { it.askId in resolved }

    fun machines(): List<FleetMachine> = allMachines.map { m ->
        val pending = attention().count { it.accountId == m.accountId }
        if (pending != m.pending) m.copy(
            pending = pending,
            activity = if (pending == 0 && m.activity is MachineActivity.WaitingApproval) MachineActivity.Idle else m.activity,
        ) else m
    }

    fun resolve(askId: String) { if (askId !in resolved) resolved.add(askId) }

    /** Test hook: demo decisions accumulate for the process (approved = gone) — suites need a clean slate. */
    fun reset() { resolved.clear() }
}

/** Route a decision to the machine's own live link (primary or satellite), or the demo store. */
fun PocketRepository.resolveAttention(entry: AttentionEntry, allow: Boolean) {
    if (demoMode.value) { DemoFleet.resolve(entry.askId); return }
    val repo = FleetRuntime.forPrimary(this)?.repoFor(entry.accountId) ?: this.takeIf { entry.current }
    repo?.resolvePendingApproval(entry.convoId, entry.askId, allow)
}

/** The full ask behind an attention row, from whichever link holds it (composite-keyed, P1-3). */
fun PocketRepository.attentionAsk(entry: AttentionEntry): PermissionAsk? {
    val repo = FleetRuntime.forPrimary(this)?.repoFor(entry.accountId) ?: this.takeIf { entry.current }
        ?: return null
    val key = entry.convoId?.let { dev.ccpocket.app.data.ApprovalKey(it, entry.askId) }
        ?: repo.pendingApprovals.keys.firstOrNull { it.askId == entry.askId } ?: return null
    return repo.pendingApprovals[key]?.ask
}
