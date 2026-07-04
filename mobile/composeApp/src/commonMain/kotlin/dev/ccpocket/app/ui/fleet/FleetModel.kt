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
 */
enum class MachineOs { MAC, LINUX, WIN }

enum class MachineStatus { ONLINE, RECONNECTING, OFFLINE }

data class FleetMachine(
    val accountId: String,
    val name: String,
    val os: MachineOs,
    val status: MachineStatus,
    val activity: String,   // the mono ActivityLine ("▶ 2 running · ~/proj/…" / "not connected · tap to switch")
    val lastSeen: String,   // "active now" / "2m ago" / "" when the activity line already says it
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
)

data class FinishedEntry(
    val machineName: String,
    val os: MachineOs,
    val title: String,
    val ok: Boolean,
    val timeAgo: String,
)

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
    val ask = repo.pendingAsk.value
    val open = repo.directories.count { it.open || it.busy }
    val activity = when {
        status == MachineStatus.RECONNECTING -> "reconnecting…"
        status == MachineStatus.OFFLINE -> "offline"
        ask != null -> "⏸ 1 waiting approval · ${ask.tool}: ${ask.inputPreview.take(28)}"
        current && repo.convoId.value != null -> "▶ ${repo.chatTitle.value ?: "session"} · ${repo.workdir.value?.let(::tilde) ?: ""}"
        open > 0 -> "▶ $open active · ${repo.directories.firstOrNull { it.open || it.busy }?.path?.let(::tilde) ?: ""}"
        else -> "idle"
    }
    return FleetMachine(
        accountId = binding.accountId, name = name, os = osFromName(name), status = status,
        activity = activity, lastSeen = if (status == MachineStatus.ONLINE) "active now" else "",
        pending = if (ask != null) 1 else 0, current = current,
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
                accountId = d.accountId, name = d.displayName(), os = osFromName(d.displayName()), status = MachineStatus.OFFLINE,
                activity = "not connected · tap to switch", lastSeen = "", pending = 0, current = false,
            )
        }
    }
}

/**
 * Approvals waiting across every live link. Today only the focused machine's link ever carries an ask —
 * the daemon binds a conversation's asks to the device connection that opened it — so satellite entries
 * light up once the daemon learns to broadcast asks account-wide (follow-up on the daemon side).
 */
fun PocketRepository.fleetAttention(): List<AttentionEntry> {
    if (demoMode.value) return DemoFleet.attention()
    val links = FleetRuntime.forPrimary(this)?.repos() ?: listOf(this)
    return links.mapNotNull { repo ->
        val ask = repo.pendingAsk.value ?: return@mapNotNull null
        val d = repo.paired.value ?: return@mapNotNull null
        val name = d.displayName()
        AttentionEntry(
            askId = ask.askId, accountId = d.accountId, machineName = name, os = osFromName(name),
            tool = ask.tool, title = ask.title.ifBlank { "Needs permission" }, preview = ask.diff ?: ask.inputPreview,
            seconds = 30, current = repo === this,
        )
    }
}

fun PocketRepository.fleetFinished(): List<FinishedEntry> =
    if (demoMode.value) DemoFleet.finished else emptyList()

/** "4 machines · 3 online · 2 waiting approval" — the FleetStrip line, shared by every fleet surface. */
fun PocketRepository.fleetStrip(): String {
    val machines = fleetMachines()
    val online = machines.count { it.status == MachineStatus.ONLINE }
    val waiting = fleetAttention().size
    val head = "${machines.size} machine${if (machines.size == 1) "" else "s"} · $online online"
    return if (waiting > 0) "$head · $waiting waiting approval" else head
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
        FleetMachine("demo-mbp", "Lidapeng-MacBook", MachineOs.MAC, MachineStatus.ONLINE, "▶ 2 running · ~/proj/app/cc-pocket", "active now", 0, current = true),
        FleetMachine("demo-studio", "mac-studio", MachineOs.MAC, MachineStatus.ONLINE, "⏸ 1 waiting approval · Bash: ./gradlew clean", "2m ago", 1, current = false),
        FleetMachine("demo-devbox", "devbox-linux", MachineOs.LINUX, MachineStatus.ONLINE, "▶ pytest -x · running 12m", "just now", 1, current = false),
        FleetMachine("demo-win", "win-desktop", MachineOs.WIN, MachineStatus.OFFLINE, "offline · 2d ago", "", 0, current = false),
    )
    private val allAttention = listOf(
        AttentionEntry("demo-ask-1", "demo-studio", "mac-studio", MachineOs.MAC, "Bash", "Run command", "rm -rf ./build && ./gradlew clean", 23, current = false),
        AttentionEntry("demo-ask-2", "demo-devbox", "devbox-linux", MachineOs.LINUX, "Write", "Edit file", "~/src/relay/src/main/kotlin/Relay.kt  +42 −7", 41, current = false),
    )
    val finished = listOf(
        FinishedEntry("Lidapeng-MacBook", MachineOs.MAC, "Refactor auth module", ok = true, timeAgo = "4m ago"),
        FinishedEntry("devbox-linux", MachineOs.LINUX, "Fix stream parser test", ok = false, timeAgo = "12m ago"),
    )

    fun attention(): List<AttentionEntry> = allAttention.filterNot { it.askId in resolved }

    fun machines(): List<FleetMachine> = allMachines.map { m ->
        val pending = attention().count { it.accountId == m.accountId }
        if (pending != m.pending) m.copy(
            pending = pending,
            activity = if (pending == 0 && m.activity.startsWith("⏸")) "idle · approval handled" else m.activity,
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
    if (repo?.pendingAsk?.value?.askId == entry.askId) {
        repo.resolve(if (allow) dev.ccpocket.protocol.Decision.ALLOW else dev.ccpocket.protocol.Decision.DENY)
    }
}

/** The full ask behind an attention row, from whichever link holds it. */
fun PocketRepository.attentionAsk(entry: AttentionEntry): PermissionAsk? {
    val repo = FleetRuntime.forPrimary(this)?.repoFor(entry.accountId) ?: this.takeIf { entry.current }
    return repo?.pendingAsk?.value?.takeIf { it.askId == entry.askId }
}
