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
import dev.ccpocket.app.ui.ComposerState
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
    // folder-share (issue #115): set only on a GUEST's shared project (the daemon stamps DirectoryEntry) —
    // drives the sidebar's "Shared" provenance pill. Null = an ordinary local dir.
    val sharedBy: String? = null,      // owner label ("shared by panda")
    val shareExpiresAt: Long? = null,  // epoch ms — the "6d left" caption
)

data class DkSession(
    val sessionId: String,
    val cwd: String,
    val title: String,
    val agent: AgentKind = AgentKind.CLAUDE,
    val running: Boolean = false,
    val pending: Int = 0,
    val model: String? = null, // last turn's model id (row shows its alias; null = unknown/older daemon)
    // custom session-group id this row belongs to (issue #119), or null = ungrouped. Only meaningful for
    // the CURRENT project's live rows — the daemon lists groups only for the listed dir.
    val group: String? = null,
)

/** One custom session group inside a project (issue #119) — the view mirror of protocol's SessionGroup.
 *  Ordered by [order]; a project with no groups (or an older daemon that omits them) renders sessions flat. */
data class DkGroup(val id: String, val name: String, val order: Int)

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
    // folder-share (issue #115): a guest's shared project keeps its provenance on the RECENT group —
    // the header renders the "Shared" pill + owner + remaining validity. Null = an ordinary local dir.
    val sharedBy: String? = null,
    val shareExpiresAt: Long? = null,
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
    // an AskUserQuestion, not a permission gate (issue #111): its answer must ride the ALLOW as an answers
    // map — a bare ALLOW reads "did not answer" to the CLI — so summary surfaces (the tray) route these to
    // the session instead of offering a Deny/Allow that would silently drop the user's choice
    val question: Boolean = false,
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
    /** Bumps on every (re)attach to the active daemon. The Account pane keys its auth/presets fetch on this
     *  so a reconnect (e.g. a daemon restart) refills it without a manual close/reopen. 0 = seed/preview. */
    val connGen: Int get() = 0
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
    var showSkills: Boolean // the installed skills/plugins browser (issue #132; sidebar row / palette verb)

    /** Open the ⌘K palette scoped to projects — the sidebar's browse affordance for the full list. */
    fun browseProjects() { palette = PaletteScope.PROJECTS }

    /** Any dismissible overlay showing — drives "Esc closes whatever is open" without a per-flag list. */
    val anyOverlayOpen: Boolean
        get() = palette != null || showSettings || showAddComputer || showNewSession || showTray || showAttention || switcherOpen || showQuickActions || showChanges || showSkills
    /** Close every dismissible overlay (the permission modal is excluded — it needs an explicit decision). */
    fun dismissOverlays() {
        palette = null; showSettings = false; showAddComputer = false
        showNewSession = false; showTray = false; showAttention = false; switcherOpen = false; showQuickActions = false; showChanges = false; showSkills = false
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

    // ── workflow orchestration (issue #106): the docked right panel + chat-card binding ──
    // Defaults keep the seed/demo model untouched; the live model delegates to the repository.
    /** Runs for the active conversation, keyed by runId. */
    val workflowRuns: Map<String, dev.ccpocket.protocol.WorkflowRun> get() = emptyMap()

    /** Non-null = the ~360dp workflow panel is docked on this run (clicking the chat card docks it). */
    val dockedWorkflowRunId: String? get() = null
    fun openWorkflowPanel(runId: String) {}
    fun closeWorkflowPanel() {}

    /** The run a chat Workflow card binds to (live: tool_use id; replay: HistoryMessage run id). */
    fun workflowRunFor(item: ChatItem.Tool): dev.ccpocket.protocol.WorkflowRun? = null

    /** On-demand full prompt/return per agent, keyed "runId#index". */
    val workflowAgentDetails: Map<String, dev.ccpocket.protocol.WorkflowAgentDetail> get() = emptyMap()
    fun fetchWorkflowAgentDetail(runId: String, agentIndex: Int, agentId: String?) {}

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

    /** RECENT — session groups for the visited projects, most recently visited first. The keys persist
     *  across restarts (issue #102); their session lists refill from the daemon once it's reachable. */
    val sessionGroups: List<DkSessionGroup>

    /** Forget every visited project — RECENT's header clear (issue #102). Pins and hidden rows are
     *  deliberately untouched. No-op for seed/preview models. */
    fun clearRecent() {}

    // ── custom session groups (issue #119) ────────────────────────────────────────────────────────
    // These describe ONLY the current (live-listed) project — the daemon lists groups per directory, so a
    // non-current RECENT snapshot has none and stays flat. Empty [customGroups] = an older daemon that omits
    // them OR a project with no groups yet: either way the current project's rows render flat (the degrade).
    /** The current project's custom groups, ordered; empty = none / older daemon → flat list. */
    val customGroups: List<DkGroup> get() = emptyList()
    /** Owner + group-capable connection: false hides every group-edit affordance (a guest is a daemon-side
     *  no-op anyway; the seed/preview model leaves it inert). */
    val canEditGroups: Boolean get() = false
    /** Create a group in the current project (the daemon re-pushes Sessions, refreshing [customGroups]). */
    fun createGroup(name: String) {}
    fun renameGroup(groupId: String, name: String) {}
    /** Delete a group; its sessions fall back to Ungrouped (daemon-side). */
    fun deleteGroup(groupId: String) {}
    /** Move [sessionId] into [groupId], or out of any group when [groupId] is null. */
    fun assignGroup(sessionId: String, groupId: String?) {}
    /** Per project + per group collapse memory (issue #119; persisted like #102's RECENT keys). */
    fun groupCollapsed(projectPath: String, groupId: String): Boolean = false
    fun setGroupCollapsed(projectPath: String, groupId: String, collapsed: Boolean) {}

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
    /** True while an OpenSession is in flight — messages are already cleared and convoId nulled, but the
     *  daemon hasn't answered with SessionLive yet (issue #82). ChatPane shows a loading transition for the
     *  target session instead of the blank "No session open" empty state, which read as "didn't respond". */
    val opening: Boolean get() = false
    val chatTitle: String
    val chatAgent: AgentKind
    val chatWorkdir: String
    val chatBranch: String?
    val chatModel: String
    /** Raw model id (unaliased) — the quick-actions picker compares options against this. */
    val chatModelId: String get() = chatModel
    val chatMode: PermissionMode
    val chatEffort: String? get() = null
    /** The daemon's third-party ANTHROPIC_BASE_URL (issue #139) — non-null puts the gateway model
     *  presets first in the ⋯ model picker. Default null keeps Seed/test fakes compiling. */
    val gatewayBaseUrl: String? get() = null
    val messages: List<ChatItem>
    // ── older-history lazy load (issue #147) — defaults keep Seed/test fakes compiling ──
    /** Rows older than the loaded window exist on the daemon — the top-of-list loader shows. */
    val historyHasMore: Boolean get() = false
    /** True while a page request is in flight (the loader row pulses). */
    val historyLoadingOlder: Boolean get() = false
    /** Bumped when a page PREPENDED rows; [lastHistoryPrependCount] says how many — the list scrolls
     *  by that to keep the viewport anchored. */
    val historyPrependGen: Int get() = 0
    val lastHistoryPrependCount: Int get() = 0
    fun loadOlderHistory() {}
    val streaming: Boolean
    /** True when a sent prompt can't be confirmed delivered — the link is down, or it claims healthy but
     *  the delivery receipt stalled past its deadline (issue #78, common with several computers connected).
     *  ChatPane turns the pending cue from a benign "sending…" into an honest warning on it. */
    val sendUndelivered: Boolean get() = false
    /** Delivered but no turn started within the deadline (issue #104): the agent swallowed the prompt
     *  (wedged / mid-relaunch). ChatPane replaces the streaming caret with a tappable "resend" cue. */
    val turnStalled: Boolean get() = false
    /** The mid-turn-send sibling: the prompt is queued in the CLI behind a running turn that has gone
     *  quiet past the same deadline. Healthy, so ChatPane shows a calm status — never a resend cue
     *  (the queued original would double-run). */
    val turnQueued: Boolean get() = false
    /** The composer's single source of truth — ChatPane renders it and writes caret-precise edits
     *  (shift+Enter newline, @-file completion) straight into it. See [dev.ccpocket.app.ui.ComposerState]. */
    val composerState: ComposerState
    /** String facade over [composerState] for model logic and tests: every assignment is an EXPLICIT
     *  external write (caret lands at the end; a live IME composition holds it until it ends). */
    var composer: String
        get() = composerState.text
        set(value) { composerState.setText(value) }
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
    /** Received/total bytes of an in-flight chunked read (issue #134) — the loading card's determinate bar. */
    val selectedContentProgress: Pair<Long, Long>? get() = null
    fun selectChangedFile(path: String) {}
    /** Open the browser: flip the flag and refresh both the list and the remembered selection. */
    fun openChanges() { showChanges = true; fetchChangedFiles() }

    // installed skills/plugins browser (issue #132): the machine's ~/.claude catalog, plus the open
    // project's `.claude/skills` when a chat is live. Defaults keep seed/preview models inert.
    val skillCatalog: dev.ccpocket.protocol.SkillCatalog? get() = null
    val skillCatalogLoading: Boolean get() = false
    /** No reply — the daemon predates pocket/skills.*; the browser shows its "update the daemon" state. */
    val skillCatalogStale: Boolean get() = false
    fun fetchSkillCatalog() {}
    /** Open the browser: flip the flag and re-pull the catalog (cheap daemon-side disk scan). */
    fun openSkills() { showSkills = true; fetchSkillCatalog() }

    // composer image attachments (⌘V paste / attach icon → file picker); ride the next send
    val pendingImages: List<dev.ccpocket.app.data.PendingImage>
    fun attachImages(raw: List<ByteArray>)
    fun removePendingImage(id: Long)
    fun hasReadyImages(): Boolean

    // composer FILE uploads (issue #90): staged files chunk-stream into the session's workspace
    // inbox; landed paths ride the next send as `@`-references. Default no-ops keep seed/preview
    // models inert (chips simply never appear).
    val pendingFiles: List<dev.ccpocket.app.data.PendingFile> get() = emptyList()
    fun attachFiles(files: List<dev.ccpocket.app.media.PickedFile>) {}
    fun removePendingFile(id: Long) {}
    fun retryPendingFile(id: Long) {}
    /** Uploads still moving → the send button waits (spinner) until they settle. */
    fun uploadsBusy(): Boolean = false
    fun hasLandedFiles(): Boolean = false
    /** Play/open a landed workspace file in the OS default app (issue #98). The desktop app runs on the
     *  SAME machine as the daemon, so a landed video's inbox path resolves to a real local file — no
     *  re-fetch needed. Default no-op keeps seed/preview models inert. */
    fun openWorkspaceFile(path: String) {}

    // permission (live: inline card in the stream; seed: also drives the focused modal)
    val ask: PermissionAsk?
    /** The current [ask] is the one the daemon reported TIMED_OUT (issue #100): the inline card flips to its
     *  terminal "auto-denied" state (greyed + danger note + Dismiss) instead of staying actionable, and a late
     *  click can't send a verdict the CLI already stopped waiting for. Default false so seed/preview models
     *  render the ordinary actionable card. */
    val askTimedOut: Boolean get() = false
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
    // menu-bar presence (issue #151, direction 1): the OS status glyph + anchored popover. Default ON —
    // the environment layer is the point; Settings ▸ General offers the opt-out.
    var menuBarEnabled: Boolean
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
    // re-run a delivered-but-no-turn prompt (issue #104) under a fresh id; no-op unless turnStalled
    fun resendStalled() {}
    fun renameComputer(c: DkComputer, label: String?) // null clears back to the accountId fallback
    fun revokeComputer(c: DkComputer)

    // ── folder-share (issue #115): owner management + guest redeem. All default to inert so the
    //    seed/preview model needs no changes; the live [RepoDesktopModel] wires them to the repo. ──
    val shares: List<dev.ccpocket.protocol.ShareInfo> get() = emptyList()
    val sharesLoaded: Boolean get() = false
    /** The invite minted by the last [createShare] — the owner shows its QR/code, then [clearLastShare]. */
    val lastShareInvite: dev.ccpocket.protocol.ShareInvite? get() = null
    fun refreshShares() {}
    fun createShare(path: String, tier: dev.ccpocket.protocol.AccessTier, expiresInSec: Long) {}
    fun revokeShare(deviceId: String) {}
    fun clearLastShare() {}
    /** Guest: decode + redeem a pasted invite blob; false if it isn't a valid invite. */
    fun redeemShareInvite(blob: String): Boolean = false

    // scheduled tasks (issue #137): the ACTIVE computer's schedule list (management surface — the
    // creation gesture lives on mobile's composer). Defaults keep seed/preview/test fakes inert;
    // the live model rides the repo. `schedulesStale` = the daemon predates pocket/schedule.*
    // (it silently drops the request), distinct from an EMPTY list.
    val schedules: List<dev.ccpocket.protocol.ScheduleInfo> get() = emptyList()
    val schedulesLoaded: Boolean get() = false
    val schedulesStale: Boolean get() = false
    fun refreshSchedules() {}
    fun cancelSchedule(id: String) {}

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

    // API presets (issue #113, Settings ▸ Account): named env overrides for third-party API users,
    // stored on the daemon; tokens ride up write-only and only ever come back masked. Null = not
    // fetched yet or the daemon predates pocket/presets.* (show "update the daemon", hide the form).
    val presetsState: dev.ccpocket.protocol.PresetsState? get() = null
    /** Bumps on EVERY PresetsState reply (even one equal to the last) — what op-settle effects key on. */
    val presetsRev: Int get() = 0
    fun refreshPresets() {}
    /** Create ([id] null) / update one preset; a null [token] on update keeps the stored one. */
    fun savePreset(id: String?, name: String, baseUrl: String, tokenVar: String, token: String?, model: String?, smallFastModel: String?) {}
    fun deletePreset(id: String, force: Boolean = false) {}
    /** Make [id] the active preset (null = deactivate). Same refusal semantics as [switchAccount]. */
    fun activatePreset(id: String?, force: Boolean = false) {}
    /** Stop one PresetsState.blockers session (hard close) and re-attempt activating [retryId]. */
    fun stopPresetBlocker(convoId: String, retryId: String?) {}
    /** Same, when the blocked op was deleting the active preset — retries the delete instead. */
    fun stopPresetDeleteBlocker(convoId: String, deleteId: String) {}

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
