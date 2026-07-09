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
import dev.ccpocket.app.theme.ThemeMode
import dev.ccpocket.app.ui.tilde
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode

// ── view types (carry the ids/paths the actions need) ───────────────────────────────────────────

enum class DkOs { MAC, LINUX, WIN }

/** How the desktop app itself was installed — decides the "Check for updates" action (issue #87). */
enum class DkInstallSource { STANDALONE, BREW, SCOOP, UNKNOWN }

/**
 * The Settings ▸ About update-check state machine (issue #87). Always starts [Idle] and only advances on an
 * explicit [DesktopModel.checkForUpdates] — no auto-fire, so seed/preview models and UI tests stay offline.
 */
sealed interface DkUpdateState {
    data object Idle : DkUpdateState
    data object Checking : DkUpdateState
    data class UpToDate(val current: String) : DkUpdateState
    data class Available(val latest: String, val source: DkInstallSource) : DkUpdateState
    data class Downloading(val latest: String) : DkUpdateState // standalone self-update in progress
    data class Failed(val message: String) : DkUpdateState
}

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
 * current (live-listed) group's rows refresh with the repo; the others are snapshots from their
 * last listing (a header refresh re-lists them), kept so the sidebar shows work across projects
 * without a per-directory protocol round-trip.
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
    var showQuickActions: Boolean // chat-header ⋯ popover: model/effort/mode + compact/clear (mirrors mobile's sheet)
    var showChanges: Boolean // the Changes two-pane diff browser (chat-header ± pill / palette verb)

    /** Open the ⌘K palette scoped to projects — the sidebar's browse affordance for the full list. */
    fun browseProjects() { palette = PaletteScope.PROJECTS }

    /** Any dismissible overlay showing — drives "Esc closes whatever is open" without a per-flag list. */
    val anyOverlayOpen: Boolean
        get() = palette != null || showSettings || showAddComputer || showNewSession || showTray || showAttention || switcherOpen || showQuickActions || showChanges
    /** Close every dismissible overlay (the permission modal is excluded — it needs an explicit decision). */
    fun dismissOverlays() {
        palette = null; showSettings = false; showAddComputer = false
        showNewSession = false; showTray = false; showAttention = false; switcherOpen = false; showQuickActions = false; showChanges = false
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

    /** Browse a RUNNING row's project (issue #49): its session LIST, without auto-resuming the live one —
     *  the hover affordance for picking a historical session next to a running turn. */
    fun browseRunning(m: DkMachine, p: DkProject) {
        if (m.active) openProject(p) else selectComputer(m.computer)
    }

    // sidebar: projects + the current project's sessions
    val projects: List<DkProject>
    val sessions: List<DkSession>
    val selectedSessionId: String?
    fun openProject(p: DkProject)
    fun selectSession(s: DkSession)

    /** Remove a session row from the RECENT list — the row's hover ✕ (issue #62). Non-destructive: the
     *  transcript stays on the host and reopening its project resurfaces it. No-op for seed/preview models. */
    fun hideSession(s: DkSession) {}

    /** RECENT — session groups for the projects visited this run, most recently visited first. */
    val sessionGroups: List<DkSessionGroup>

    /** True while a session-list re-scan is in flight — the sidebar's refresh affordances spin on it. */
    val sessionsRefreshing: Boolean get() = false

    /** Sync the sidebar with the daemon (⌘R / a RECENT header's hover refresh): re-pull the project list
     *  and re-list [g]'s sessions (null = the current group). The repo lists one directory at a time, so
     *  refreshing a non-current group makes it the live-listed one — its RECENT position doesn't change. */
    fun refresh(g: DkSessionGroup? = null) {}

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
    /** Raw model id (unaliased) — the quick-actions picker compares options against this. */
    val chatModelId: String get() = chatModel
    val chatMode: PermissionMode
    val chatEffort: String? get() = null
    val messages: List<ChatItem>
    val streaming: Boolean
    /** True when a sent prompt can't be confirmed delivered — the link is down, or it claims healthy but
     *  the delivery receipt stalled past its deadline (issue #78, common with several computers connected).
     *  ChatPane turns the pending cue from a benign "sending…" into an honest warning on it. */
    val sendUndelivered: Boolean get() = false
    var composer: String
    fun send(text: String)

    // session health (issue #65): degraded = recent turns were all API failures (likely past the context
    // window); used/window feed the header's context readout. Defaults keep demo/preview models untouched.
    val sessionDegraded: Boolean get() = false
    val contextUsed: Long? get() = null
    val contextWindow: Long? get() = null

    // live-session switches (the ⋯ quick-actions popover; same repo verbs mobile's sheet drives)
    fun switchMode(m: PermissionMode) {}
    fun switchModel(name: String) {}
    fun switchEffort(level: String) {}
    fun compactConversation() {}
    fun clearConversation() {}

    /** Daemon-pushed "/" commands for the open session — the composer's slash autocomplete reads this. */
    val slashCommands: List<dev.ccpocket.protocol.SlashCommand> get() = emptyList()

    // composer @-file completion (issue #75): the completer browses the open session's cwd through the
    // daemon. [pathListing] is the latest reply (the completer matches its subPath before using it);
    // [browsePath] requests a directory's children. Default no-ops keep seed/preview models inert.
    val pathListing: dev.ccpocket.protocol.PathEntries? get() = null
    /** The daemon host's path separator ('\\' on a Windows daemon, '/' elsewhere) — the completer splits
     *  the typed query and composes inserted paths with it (the repo's one separator discipline, #19/#22). */
    val pathSep: Char get() = '/'
    fun browsePath(sub: String) {}

    // changes (changed-files v2): the chat header's ± pill count + the two-pane Changes browser.
    // Defaults are inert so seed/preview models compile untouched; the live model rides the repo.
    val changedFiles: List<dev.ccpocket.protocol.ChangedFile> get() = emptyList()
    val changedFilesLoading: Boolean get() = false
    /** No reply — the daemon predates the messages; the overlay shows its "update the daemon" state. */
    val changedFilesStale: Boolean get() = false
    /** Re-pull the changed list for the open session (overlay open / turn end / ⌘R while open). */
    fun fetchChangedFiles() {}
    /** The overlay's selected file (drives the right pane); null until the first row is picked. */
    val selectedChangedPath: String? get() = null
    val selectedDiff: dev.ccpocket.protocol.FileDiff? get() = null
    val selectedContent: dev.ccpocket.protocol.FileContent? get() = null
    fun selectChangedFile(path: String) {}
    /** Open the browser: flip the flag and refresh both the list and the remembered selection. */
    fun openChanges() { showChanges = true; fetchChangedFiles() }

    // composer image attachments (⌘V paste / attach icon → file picker); ride the next send
    val pendingImages: List<dev.ccpocket.app.data.PendingImage>
    fun attachImages(raw: List<ByteArray>)
    fun removePendingImage(id: Long)
    fun hasReadyImages(): Boolean

    // permission (live: inline card in the stream; seed: also drives the focused modal)
    val ask: PermissionAsk?
    fun resolve(allow: Boolean, remember: Boolean)
    fun dismissAsk()
    // AskUserQuestion (ask.questions != null): the picks/free-text ride an ALLOW verdict; skip DENIES with a
    // note. Kept distinct from resolve() because a bare ALLOW carries no answers → the CLI reads "did not
    // answer" and the model never sees the choice. Default no-ops so seed/preview models can ignore them.
    fun answerQuestions(answers: Map<String, String>?, response: String?) {}
    fun skipQuestions(message: String) {}

    // settings (general prefs + paired-computer management)
    val appVersion: String
    val relayUrl: String

    // self-update (Settings ▸ About "Check for updates", issue #87). Reuses the daemon's shared release-check
    // (version compare + SHA256 verify). Button-triggered so seed/preview + UI tests never hit the network;
    // the defaults keep those models inert. The live model branches on install source: a standalone dmg/msi
    // self-updates (download → verify → replace → relaunch), a brew/scoop copy exposes its upgrade command
    // instead of self-overwriting, and an unrecognized/dev build opens the releases page.
    val updateState: DkUpdateState get() = DkUpdateState.Idle
    /** Check GitHub releases for a newer app version, then classify how this install can take it. */
    fun checkForUpdates() {}
    /** STANDALONE installs only: download the new dmg/msi, verify its SHA256, replace this app and relaunch. */
    fun applyUpdate() {}
    /** The upgrade command to copy for a package-manager install (brew/scoop), else null. */
    val updateCommand: String? get() = null
    /** Releases page for the "can't self-update from here" fallback (brew/scoop/unknown). */
    val updateReleasesUrl: String get() = DesktopUpdater.RELEASES_URL
    var defaultAgent: AgentKind
    var defaultMode: PermissionMode
    // default model new Claude sessions start under (null = the CLI's own default). Codex sessions ignore it.
    var defaultModel: String?
    // context-window override (tokens) for the usage statusline's 100% mark; null = follow the derived window (#60)
    var contextWindowOverride: Long?
    var terminalApp: TerminalApp // which terminal the ">_" chat-header button opens (issue #44)
    // appearance (issue #63): force light/dark or follow the OS. The window root reads this into PocketTheme;
    // RepoDesktopModel persists it through the shared repo, seed/preview models just hold it in memory.
    var themeMode: ThemeMode

    // phone-push switch (pocket/push.prefs.*): daemon truth; null = daemon predates it (toggle hidden)
    val phonePush: Boolean? get() = null
    fun setPhonePush(enabled: Boolean) {}
    fun refreshPushPrefs() {}

    // read-only OBSERVE view (the session is owned by a terminal/VS Code on the computer): the composer
    // must yield — a prompt sent into an observe convo is silently unroutable on the daemon (issue #45 ②)
    val observing: Boolean get() = false
    fun takeOver() {}

    // interrupt the running turn (■ beside send / Esc); the interrupted prompt returns to the composer (#48)
    fun stopTurn() {}
    fun renameComputer(c: DkComputer, label: String?) // null clears back to the accountId fallback
    fun revokeComputer(c: DkComputer)

    // account (Settings ▸ Account): the ACTIVE computer's Claude CLI login, driven over pocket/auth.*.
    // Null = not fetched yet, or the daemon predates the messages (it silently drops the request).
    val authState: dev.ccpocket.protocol.AuthState? get() = null
    fun refreshAuth() {}
    /** Switch account: daemon logs out (when needed) + starts `claude auth login`; state updates stream in.
     *  [force] = the user saw the blocker list and chose "stop them & switch". */
    fun switchAccount(force: Boolean = false) {}
    /** Stop one AuthState.blockers session (hard close) and re-attempt the switch. */
    fun stopAuthBlocker(convoId: String) {}
    fun submitAuthCode(code: String) {}
    fun cancelAuthLogin() {}
    fun logoutAccount() {}

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
