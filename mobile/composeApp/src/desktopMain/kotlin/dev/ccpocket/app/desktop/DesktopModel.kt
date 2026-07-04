package dev.ccpocket.app.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.ui.tilde
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode

// ── view types (carry the ids/paths the actions need) ───────────────────────────────────────────

enum class DkOs { MAC, LINUX, WIN }

data class DkComputer(
    val accountId: String,
    val name: String,
    val os: DkOs,
    val online: Boolean,
    val meta: String,
)

data class DkProject(
    val path: String,
    val name: String,
    val running: Boolean = false,
)

data class DkSession(
    val sessionId: String,
    val cwd: String,
    val title: String,
    val agent: AgentKind = AgentKind.CLAUDE,
    val running: Boolean = false,
    val pending: Int = 0,
    val model: String? = null, // last turn's model id (row shows its alias; null = unknown/older daemon)
)

/**
 * One RECENT group — a project the user listed this run, with the sessions we know it has. The
 * current (live-listed) group's rows refresh with the repo; the others are this run's snapshots,
 * kept so the sidebar shows work across projects without a per-directory protocol round-trip.
 */
data class DkSessionGroup(
    val path: String,
    val name: String,
    val current: Boolean,
    val sessions: List<DkSession>,
)

/**
 * A pinned session — the sidebar's ⌘1–9 fast-switch list ("Sidebar Redesign" board). Carries only
 * durable identity; live state (running / pending) and the machine's display name are looked up at
 * render time so renames and reconnects don't stale the pin.
 */
data class DkPin(
    val accountId: String,
    val sessionId: String,
    val cwd: String,
    val title: String,
    val agent: AgentKind = AgentKind.CLAUDE,
)

// ── fleet ("Fleet Desktop" board): machine-grouped sidebar · cross-machine attention · watch pane ──

/**
 * A machine group in the sidebar. The ACTIVE machine renders the live projects+sessions panes inside its
 * group; other bindings render their [projects] or a "not connected" line — clicking them switches.
 */
data class DkMachine(
    val computer: DkComputer,
    val active: Boolean = false,      // the binding the shell is driving right now
    val thisMachine: Boolean = false, // the daemon on the machine this desktop app runs on ("this Mac" tag)
    val pending: Int = 0,             // approvals waiting on this machine (AttentionBadge)
    val projects: List<DkProject> = emptyList(), // a non-active machine's live directory list (its satellite link)
)

/** One approval waiting somewhere in the fleet — a bell-popover / palette row. */
data class DkAttention(
    val id: String,
    val accountId: String,
    val machine: String,
    val os: DkOs,
    val tool: String,
    val preview: String,
    val seconds: Int?, // countdown when the deadline is known (seed); null = don't invent one (live)
    val live: Boolean, // resolvable through the live connection
)

/** What the ⌘K palette shows: everything, or just project rows ("All projects…"). */
enum class PaletteScope { ALL, PROJECTS }

/** A second session watched read-only beside the open chat (split pane). */
data class DkWatch(
    val machine: String,
    val os: DkOs,
    val title: String,
    val mode: String,
    val output: String,
    val waiting: DkAttention?,
)

/**
 * The desktop shell reads everything through this — so the UI is agnostic to whether it is driven by a live
 * [dev.ccpocket.app.data.PocketRepository] ([RepoDesktopModel]) or by static seed data ([SeedDesktopModel],
 * used by the screenshot generator and UI tests). Getters read snapshot state, so reads recompose normally.
 */
interface DesktopModel {
    // connection + computer switcher
    val connected: Boolean
    val activeComputer: DkComputer?
    val computers: List<DkComputer>
    fun selectComputer(c: DkComputer)
    fun addComputer()

    // ui-local overlay flags
    var switcherOpen: Boolean
    var showNewSession: Boolean
    var showTray: Boolean
    var palette: PaletteScope? // ⌘K command palette; null = closed — the scope can't outlive the open
    var showSettings: Boolean
    var showAddComputer: Boolean // pair a new computer in a modal without dropping the live session
    var showPermissionModal: Boolean // seed/demo only; the live model surfaces [ask] inline instead
    var showAttention: Boolean // bell popover: cross-machine approvals without leaving the session

    /** Open the ⌘K palette scoped to projects — the sidebar's browse affordance for the full list. */
    fun browseProjects() { palette = PaletteScope.PROJECTS }

    /** Any dismissible overlay showing — drives "Esc closes whatever is open" without a per-flag list. */
    val anyOverlayOpen: Boolean
        get() = palette != null || showSettings || showAddComputer || showNewSession || showTray || showAttention || switcherOpen
    /** Close every dismissible overlay (the permission modal is excluded — it needs an explicit decision). */
    fun dismissOverlays() {
        palette = null; showSettings = false; showAddComputer = false
        showNewSession = false; showTray = false; showAttention = false; switcherOpen = false
    }

    // pinned sessions — the sidebar's top zone: ⌘1–9 jump straight to them, persisted across restarts
    val pins: List<DkPin>
    fun pin(s: DkSession)
    fun unpin(p: DkPin)
    fun movePin(from: Int, to: Int)
    /** Jump to a pin: same machine opens the session in place; another machine switches over first. */
    fun openPin(p: DkPin)
    fun jumpPin(i: Int) { pins.getOrNull(i)?.let { openPin(it) } }
    fun isPinned(sessionId: String): Boolean = pins.any { it.sessionId == sessionId }
    val pinsFull: Boolean get() = pins.size >= MAX_PINS

    // fleet: the sidebar's machine groups, the attention queue, and the read-only watch pane
    val machines: List<DkMachine>
    val attention: List<DkAttention>
    val watch: DkWatch?
    fun resolveAttention(a: DkAttention, allow: Boolean)
    /** ⌘1–⌘4 — jump to the n-th machine group (switching the active binding when it isn't already). */
    fun jumpMachine(i: Int) {
        machines.getOrNull(i)?.takeIf { !it.active }?.let { selectComputer(it.computer) }
    }

    /** The cross-machine RUNNING rows — every live project on every machine, no expanding required. */
    val running: List<Pair<DkMachine, DkProject>>
        get() = machines.flatMap { m -> m.projects.filter { it.running }.map { m to it } }

    /**
     * RUNNING rows minus projects already represented by a pinned session known to be running there —
     * so one piece of live work never shows twice in the sidebar. Unknown state (remote pins) keeps the row.
     */
    val runningVisible: List<Pair<DkMachine, DkProject>>
        get() = running.filterNot { (m, p) ->
            pins.any { it.accountId == m.computer.accountId && it.cwd == p.path && liveSession(it.sessionId)?.running == true }
        }

    /** Open a RUNNING row: the focused machine opens in place; another machine switches over then opens. */
    fun openRunning(m: DkMachine, p: DkProject) {
        if (m.active) openProject(p) else selectComputer(m.computer)
    }

    // sidebar: projects + the current project's sessions
    val projects: List<DkProject>
    val sessions: List<DkSession>
    val selectedSessionId: String?
    fun openProject(p: DkProject)
    fun selectSession(s: DkSession)

    /** RECENT — session groups for the projects visited this run, most recent first (head = current). */
    val sessionGroups: List<DkSessionGroup>

    /** A session's live row anywhere we know it — the current list first, then the recent groups. */
    fun liveSession(sessionId: String): DkSession? =
        sessions.firstOrNull { it.sessionId == sessionId }
            ?: sessionGroups.firstNotNullOfOrNull { g -> g.sessions.firstOrNull { it.sessionId == sessionId } }
    /** The current project's folder (the open session list's, else the active chat's). Null = none yet. */
    val newSessionDir: String?
    /** Seed for the new-session popover's editable path field, display form ("~/…"). */
    var newSessionSeed: String?
    /** Open the new-session popover. Null [seed] targets the CURRENT project (⌘N, the Sessions-pane row,
     *  the palette verb); pass "~/" to type a fresh path under the daemon's home (the Projects-group row). */
    fun openNewSession(seed: String? = null) {
        newSessionSeed = seed ?: newSessionDir?.let { tilde(it) } ?: "~/"
        showNewSession = true
    }
    /** Start a session at [dir] (display form; "~" is expanded against the daemon host's home). */
    fun newSession(dir: String, agent: AgentKind, mode: PermissionMode)

    // main pane: the open chat
    val hasChat: Boolean
    val chatTitle: String
    val chatAgent: AgentKind
    val chatWorkdir: String
    val chatBranch: String?
    val chatModel: String
    val chatMode: PermissionMode
    val messages: List<ChatItem>
    val streaming: Boolean
    var composer: String
    fun send(text: String)

    /** Daemon-pushed "/" commands for the open session — the composer's slash autocomplete reads this. */
    val slashCommands: List<dev.ccpocket.protocol.SlashCommand> get() = emptyList()

    // composer image attachments (⌘V paste / attach icon → file picker); ride the next send
    val pendingImages: List<dev.ccpocket.app.data.PendingImage>
    fun attachImages(raw: List<ByteArray>)
    fun removePendingImage(id: Long)
    fun hasReadyImages(): Boolean

    // permission (live: inline card in the stream; seed: also drives the focused modal)
    val ask: PermissionAsk?
    fun resolve(allow: Boolean, remember: Boolean)
    fun dismissAsk()

    // settings (general prefs + paired-computer management)
    val appVersion: String
    val relayUrl: String
    var defaultAgent: AgentKind
    var defaultMode: PermissionMode
    fun renameComputer(c: DkComputer, label: String?) // null clears back to the accountId fallback
    fun revokeComputer(c: DkComputer)

    companion object {
        /** Pin cap — ⌘1–9 is the whole affordance, so the list never outgrows the keycaps. */
        const val MAX_PINS = 9
    }
}

/** A status dot that gently pulses (scale + alpha + soft glow) — "this is live / working". */
@Composable
fun PulseDot(color: Color, size: Dp = 7.dp) {
    val t by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse),
    )
    Box(
        Modifier
            .size(size)
            .scale(0.6f + 0.4f * t)
            .graphicsLayer { alpha = t }
            .clip(RoundedCornerShape(999.dp))
            .background(color),
    )
}
