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
import dev.ccpocket.app.data.SidePane
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.theme.ThemeMode
import dev.ccpocket.app.ui.ComposerState
import dev.ccpocket.app.ui.tilde
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode

/** Chat-stream alignment preference (issue #213, desktop-only). LEFT = the current document flow (every turn
 *  left-aligned, unchanged — the default so existing users see no difference). BUBBLES = classic chat layout:
 *  user turns hug the right in a bubble, assistant turns stay left. Only alignment/bubble presentation moves;
 *  the row's information structure and components are untouched. Absent/garbage → LEFT. */
enum class ChatStreamAlignment { LEFT, BUBBLES;
    companion object {
        fun from(name: String?): ChatStreamAlignment = entries.firstOrNull { it.name == name } ?: LEFT
    }
}

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

/**
 * Why a first prompt typed into the empty state (issue #256) never became a turn. Kept as a KIND rather than
 * a ready-made message so the pane resolves it through the same string resources as everything else it says.
 * Every value has the same contract: the user's text survives — see [DesktopModel.newSessionPrompt].
 */
enum class NewSessionPromptError {
    /** The open request was refused before it reached the wire (unsupported agent, duplicate open). */
    OPEN_REFUSED,
    /** The session never went live inside the wait window. */
    TIMEOUT,
    /** The session opened but the send was gated (degraded session, uploads in flight). */
    SEND_REFUSED,
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
    // rewind/fork lineage (issue #282), mirroring SessionSummary: [forkedFrom] keeps both sessions
    // visible as peers, [rewindOf] folds the ORIGINAL — the one this row names — into the collapsed
    // "rewound" bucket, which is what keeps a rewind from growing the list. Null on a synthesized row
    // (a session not yet on disk has no ledger entry either).
    val forkedFrom: String? = null,
    val rewindOf: String? = null,
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

/**
 * A pinned PROJECT (issue #199) — the session pin's sibling, living in the same PINNED zone under the same
 * gesture. Deliberately a separate list rather than a nullable-session [DkPin]: the two open different
 * things (a session resumes, a project lists) and neither replaces the other. Client-local like every pin,
 * and it rides the SAME store mobile's project pins already use, so a project pinned in either shell of a
 * device stays pinned in both.
 */
data class DkProjectPin(val path: String, val name: String)

/**
 * One-shot request to reveal a pinned project's session list in RECENT. [generation] makes opening the
 * same pin again observable after the user folds that project a second time.
 */
data class DkProjectListReveal(val path: String, val generation: Long)

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

/** What the ⌘K palette shows: everything, just project rows ("All projects…"), or the cross-project
 *  archive ("Archived sessions", issue #202). A scope is a MODE of the one palette, deliberately not a
 *  second panel — a separate window would fork "find a session" into two places, which is the very
 *  fragmentation archiving exists to remove. */
enum class PaletteScope { ALL, PROJECTS, ARCHIVED }

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
    var showQuickActions: Boolean // chat-header ⋯ popover: effort/mode + compact/clear (mirrors mobile's sheet)
    var showModelPopover: Boolean // the composer chip's anchored model popover (issue #157) — the ⋯ Model row shortcuts here too
    var showChanges: Boolean // the Changes two-pane diff browser (chat-header ± pill / palette verb)
    var showGit: Boolean // the Git panel overlay (issue #280; chat-header branch pill)
    var showWorktrees: Boolean // every checkout of the open repository (issue #281; raised from the Git overlay)
    var showSkills: Boolean // the installed skills/plugins browser (issue #132; sidebar row / palette verb)
    var showHandoff: Boolean // session-handoff draft modal (design session-handoff/ Frame 11)
    var showReviewCenter: Boolean // the ReviewRequest centre (REVIEW-REQUEST.md §12; sidebar row / ⌘⇧R)
    var showFolderPicker: Boolean // remote "Open Folder" browser (issues #218/#214): the daemon-machine dir picker
    var showQuotaPopover: Boolean // the sidebar footer allowance strip's anchored detail popover

    // ── session handoff (SESSION-HANDOFF.md) — defaults are the "no handoff" seed/preview state ──
    val activeHandoff: dev.ccpocket.protocol.SessionHandoff? get() = null
    val handoffInvite: dev.ccpocket.protocol.SessionHandoff? get() = null
    val handoffCreating: Boolean get() = false
    val handoffError: String? get() = null
    fun handoffIsRecipient(): Boolean = false
    fun handoffIsInitiator(): Boolean = false
    fun handoffCreate(recipient: String, expiresHours: Int, request: String, recipientDeviceId: String? = null) {}

    // ── collaborator links (contacts increment): picker + management + one-time connect ticket ──
    val collaborators: List<dev.ccpocket.protocol.Collaborator> get() = emptyList()
    val collaboratorTicket: dev.ccpocket.protocol.CollaboratorInvite? get() = null
    val lastCollaboratorConnected: dev.ccpocket.protocol.Collaborator? get() = null
    val collaboratorError: String? get() = null
    fun listCollaborators() {}
    fun createCollaboratorTicket() {}
    fun removeCollaborator(deviceId: String) {}
    fun handoffCancel() {}
    fun handoffRecall() {}
    fun handoffComplete() {}
    fun handoffReturn(verdict: String?) {}
    fun dismissHandoffInvite() {}

    // ── ReviewRequest (REVIEW-REQUEST.md §12) ──
    //
    // The Review Center is the ONE surface that gets the live repository handed to it whole, rather than
    // a per-field pass-through like everything above. Two reasons: its UI is shared verbatim with mobile
    // (ui/review/ReviewCenterFlow), so re-projecting a dozen fields through this interface would be a
    // second copy of the same binding; and a seed/preview model has nothing meaningful to fake here —
    // an inert Center is the honest preview, which is exactly what a null gives.
    val reviewRepo: dev.ccpocket.app.data.PocketRepository? get() = null

    /** The SECOND surface whose UI is shared verbatim with mobile and therefore gets the live repository
     *  handed over whole for the same two reasons as [reviewRepo]: the Token-usage dashboard
     *  ([dev.ccpocket.app.ui.UsageScreen]). It aliases [reviewRepo] by default — there is only ever one
     *  live repository — but keeps its own name so a preview model can inert one surface without the
     *  other, and so a reader of the usage pane is not sent looking through review code. Null in
     *  seed/preview models: the pane then shows its own "can't reach your computer" state, which is the
     *  honest preview for a dashboard nobody's daemon is backing. */
    val usageRepo: dev.ccpocket.app.data.PocketRepository? get() = reviewRepo

    /** Received reviews still waiting on this machine — the sidebar count. 0 in seed/preview models. */
    val reviewPending: Int get() = 0

    /** Open the Center and re-pull, in the [openSkills] idiom: an overlay showing yesterday's ledger is
     *  worse than one that is briefly loading. */
    fun openReviewCenter() { showReviewCenter = true; refreshReviews() }
    fun refreshReviews() {}

    /** Open the ⌘K palette scoped to projects — the sidebar's browse affordance for the full list. */
    fun browseProjects() { palette = PaletteScope.PROJECTS }

    /** Any dismissible overlay showing — drives "Esc closes whatever is open" without a per-flag list. */
    val anyOverlayOpen: Boolean
        get() = palette != null || showSettings || showAddComputer || showNewSession || showTray || showAttention || switcherOpen || showQuickActions || showModelPopover || showChanges || showGit || showWorktrees || showSkills || showHandoff || showReviewCenter || showFolderPicker || showQuotaPopover || handoffInvite != null
    /** Close every dismissible overlay (the permission modal is excluded — it needs an explicit decision). */
    fun dismissOverlays() {
        palette = null; showSettings = false; showAddComputer = false
        showNewSession = false; showTray = false; showAttention = false; switcherOpen = false; showQuickActions = false; showModelPopover = false; showChanges = false; showGit = false; showWorktrees = false; showSkills = false; showHandoff = false; showReviewCenter = false; showFolderPicker = false; showQuotaPopover = false; dismissHandoffInvite()
    }

    // pinned sessions — the sidebar's top zone: ⌘1–9 jump straight to them, persisted across restarts
    val pins: List<DkPin>
    fun pin(s: DkSession)
    fun unpin(p: DkPin)
    fun movePin(from: Int, to: Int)
    /** Jump to a pin: same machine opens the session in place; another machine switches over first. */
    fun openPin(p: DkPin)
    /** ⌘1–9 runs over the session pins first, then continues into the project pins — one keycap ladder
     *  for one PINNED zone, and adding a project pin never renumbers the session pins above it. */
    fun jumpPin(i: Int) {
        val s = pins.getOrNull(i)
        if (s != null) openPin(s) else projectPins.getOrNull(i - pins.size)?.let { openProjectPin(it) }
    }
    fun isPinned(sessionId: String): Boolean = pins.any { it.sessionId == sessionId }
    val pinsFull: Boolean get() = pins.size >= MAX_PINS

    // pinned projects (issue #199) — same zone, same gesture, separate list; see [DkProjectPin].
    // Defaults are inert so seed/preview/test fakes need no changes.
    val projectPins: List<DkProjectPin> get() = emptyList()
    fun pinProject(path: String, name: String) {}
    fun unpinProject(path: String) {}
    fun isProjectPinned(path: String): Boolean = projectPins.any { it.path == path }
    /** Latest pinned-project reveal request. Null means this model has not opened a project pin. */
    val projectListReveal: DkProjectListReveal? get() = null
    /** Open a pinned project: its session LIST — the pinned entity is the project, not one session in it. */
    fun openProjectPin(p: DkProjectPin) { openProject(DkProject(p.path, p.name)) }

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

    // ── split panes (issue #311): more than one conversation open side by side in the main area ────
    // The shell shows [sidePanes] around the focused chat in visual order — [splitFocusedSlot] says
    // which column the chat itself is (issue #336: a drop on a column's left half lands the new column
    // to ITS left, so the chat is no longer pinned leftmost). Defaults leave the seed/preview models
    // with no split at all, so every existing screenshot and UI test is unchanged.

    /** Conversations open beside the focused one, left to right. Empty = the classic single-chat main area. */
    val sidePanes: List<SidePane> get() = emptyList()

    /** Which visual slot (0..sidePanes.size) the focused chat occupies. 0 = leftmost, the historic layout. */
    val splitFocusedSlot: Int get() = 0

    /** Room for one more column (see [MAX_SPLIT_PANES]) and a live link to open it over. */
    val canSplit: Boolean get() = false

    /**
     * Put [s] in a new column beside the current chat. A session already shown somewhere is a no-op.
     * [at] is the visual SLOT the column lands at, counted over every column including the focused
     * chat (negative = append at the right end); the drag-to-split drop passes [dropSlot]'s answer.
     */
    fun openInSplit(s: DkSession, at: Int = -1) {}

    /** Drop a column. The session keeps running — closing a view never stops an agent. */
    fun closeSplit(paneId: Long) {}

    /**
     * Make [pane] the focused conversation, which is what gives it the full chat surface (model/mode
     * switching, Changes, Git, rewind, attachments). Implemented as an ordinary session open: the daemon
     * answers by reattaching the conversation it already holds, so nothing forks and nothing restarts.
     * The outgoing focused session takes the freed column.
     */
    fun promoteSplit(pane: SidePane) {}

    /** Re-send a column's open after it timed out — the failure pane's retry. */
    fun retrySplitOpen(pane: SidePane) {}

    /** Send into a pane's own conversation. False = blank, or its open hasn't landed yet. */
    fun sendSidePrompt(pane: SidePane, text: String): Boolean = false

    /** Interrupt a pane's OWN turn (its ■ / Esc). Without this the column's stop reached the focused
     *  conversation and killed the turn the user was watching one column over. */
    fun stopSideTurn(pane: SidePane) {}

    /** Switch a pane's OWN model — the composer chip in a column. Pane-keyed like every side verb, so
     *  the popover's pick can never land on the focused conversation. */
    fun switchSideModel(pane: SidePane, name: String) {}

    /** Decide a pane's approval. Keyed by the ASK the column is holding — not by whatever the focused
     *  conversation happens to be blocked on, and not by an inbox row that may not be there. */
    fun resolvePaneApproval(ask: PermissionAsk, allow: Boolean, remember: Boolean = false) {}

    /** 允许本任务 on a pane's card: a TASK grant for THAT ask (approval design M2). */
    fun resolvePaneTaskGrant(ask: PermissionAsk) {}

    /** 换种安全方式 on a pane's card: a constrained DENY back to THAT ask's agent. */
    fun retryPaneSafer(ask: PermissionAsk, constraints: List<String>) {}

    /** Answer a pane's AskUserQuestion — the picks/free text ride an ALLOW verdict for THAT ask. A column
     *  is the ONLY surface its questions have (the bell inbox excludes them), so this cannot be inert. */
    fun answerPaneQuestions(ask: PermissionAsk, answers: Map<String, String>?, response: String?) {}

    /** Skip a pane's AskUserQuestion: a DENY carrying the note, for THAT ask. */
    fun skipPaneQuestions(ask: PermissionAsk, message: String) {}

    /** Retire a pane's card locally, sending nothing — the timed-out card's Dismiss. The daemon already
     *  answered that ask, so a verdict now would be a decision nobody is waiting for. */
    fun dismissPaneAsk(ask: PermissionAsk) {}

    /**
     * True when this model views ONE split column rather than the whole shell (issue #311).
     *
     * The chat header's Changes / Git / ⋯ controls all act on the FOCUSED conversation. Rendered inside a
     * side column they would look like they act on the column and quietly act on another session, so the
     * header swaps them for the two verbs that do belong to a column: [focusThisPane] and [closeThisPane].
     */
    val paneScoped: Boolean get() = false

    /** Promote the column this model views — see [promoteSplit]. Meaningless unless [paneScoped]. */
    fun focusThisPane() {}

    /** Close the column this model views. Meaningless unless [paneScoped]. */
    fun closeThisPane() {}

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

    // ── window chrome: the sidebar's collapsed state ──────────────────────────────────────────────
    // Lifted out of DesktopApp's local state by the desktop-chrome-v2 redesign: with no window-wide title
    // bar left, the four top controls live in the SIDEBAR's own control row while it is open and re-home
    // into the leftmost chat sub-header once it is collapsed. That second surface is rendered from a
    // SidePaneModel in a split column, so "is the sidebar collapsed" has to be a MODEL fact rather than a
    // value only the shell composable can see. Inert default: seed/preview models always render expanded.
    val sidebarCollapsed: Boolean get() = false
    /** Hide/show the sidebar — the control row's panel-left button, ⌘\, and the divider's collapse drag. */
    fun setSidebarCollapsed(v: Boolean) {}

    // ── session navigation history: the sidebar control row's ‹ › and ⌘[ / ⌘] ────────────────────
    // Browser semantics over sessions that actually OPENED (recorded at the SessionLive echo, so every
    // entry point counts — sidebar rows, pins, the palette, brand-new sessions). Inert defaults keep
    // the seed/preview/test models untouched.
    val canGoBack: Boolean get() = false
    val canGoForward: Boolean get() = false
    /** Reopen the previously visited session; no-op when the history has nothing behind the cursor. */
    fun goBack() {}
    /** Reopen the next session after a [goBack]; a fresh navigation truncates this branch. */
    fun goForward() {}

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

    // ── session archive (issue #202) ──────────────────────────────────────────────────────────────
    // Daemon-side truth (unlike pins/hidden, which are client-local): archiving on the phone hides the row
    // here too. Defaults keep the seed/preview models entirely inert.
    /** Every archived session across ALL projects, newest-archived first. Populated by [refreshArchived]. */
    val archivedSessions: List<DkSession> get() = emptyList()
    /** Owner + archive-capable connection (Sessions.archiveSupported): false hides every archive entry. */
    val canArchiveSessions: Boolean get() = false
    fun archiveSession(s: DkSession) {}
    fun unarchiveSession(s: DkSession) {}
    fun refreshArchived() {}
    /** Open the ⌘K palette in its ARCHIVED scope (the sidebar's "Archived sessions" row). */
    fun browseArchived() {}

    // ── session rename (issue #158) ───────────────────────────────────────────────────────────────
    /** Owner on a rename-aware daemon (the daemon stamps Sessions.renameSupported): false hides the
     *  row's Rename entry (an older daemon would silently drop the frame). Claude rows only — the row
     *  itself skips Codex sessions (their rename write path is out of #158's scope). */
    val canRenameSessions: Boolean get() = false
    /** Rename [sessionId]'s title — lands claude's own `custom-title` record on the daemon, which
     *  re-pushes Sessions to refresh the row (no optimistic local edit). */
    /** [wd] = the ROW's own project dir — the RenameSession frame resolves against a directory, and
     *  defaulting it to the live-listed one mis-targets a rename issued from another project's row
     *  (which is why the verb used to be gated to the current group). Null keeps the old default. */
    fun renameSession(sessionId: String, title: String, wd: String? = null) {}
    /** The daemon's refusal of the last rename, iff it targeted [sessionId] (else null) — the sidebar
     *  row re-enters its edit state and shows this inline. Session-scoped feedback because the failure
     *  frame is session-independent: it must land on the row that ASKED, not in whatever chat happens
     *  to be open (the common refusal — a terminal-held session — is renamed with no chat at all). */
    fun renameError(sessionId: String): String? = null
    /** Dismiss the inline rename refusal (the rename row's Esc). */
    fun dismissRenameError() {}

    // ── session rewind / fork (issue #282) ────────────────────────────────────────────────────────
    // Defaults leave Seed/preview models entirely inert: [canRewind] false means the chat's user turns
    // grow no context menu at all, so a fake model never has to answer a frame it has no transport for.
    /** May THIS user turn be rewound? False when the row carries no transcript coordinates (older
     *  daemon / non-Claude backend), which is the capability probe — not a version check. */
    fun canRewind(item: ChatItem.User): Boolean = false
    /** True when the entry is shown but disabled, with the "stop the current turn first" reason. */
    val rewindBlockedByTurn: Boolean get() = false
    /** Open the confirmation and ask the daemon what the cut would cost. Never cuts anything. */
    fun startRewind(item: ChatItem.User, mode: String) {}
    /** The confirmation currently on screen (counts null while the dry run is out), or null. */
    val rewindSheet: PocketRepository.RewindSheet? get() = null
    fun confirmRewind() {}
    fun cancelRewind() {}
    /** The daemon's machine-readable refusal of the last attempt — a `RewindRefusal` value, mapped to
     *  copy by the renderer (never shown raw: the vocabulary is a protocol, not user-facing text). */
    val rewindError: String? get() = null
    fun dismissRewindError() {}
    /** Where the OPEN conversation was branched from, when this app is the one that branched it. Null
     *  once the view moves on — the banner is scoped to the exact conversation the rewind produced. */
    val sessionLineage: PocketRepository.SessionLineage? get() = null

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
    /** Start a session at [dir] (display form; "~" is expanded against the daemon host's home).
     *  [model] is the popover's per-creation pick (issue #199); null = the usual default ladder. */
    fun newSession(dir: String, agent: AgentKind, mode: PermissionMode, permissionMode: String? = null, model: String? = null)

    // ── empty-state session starter (issue #256) ─────────────────────────────────────────────────
    // The main pane's "no session open" state used to be a dead end: it named the situation and offered
    // nothing to do about it. It is now the fastest way INTO a session — type the first prompt, hit ⏎, and
    // the default agent/mode open a session in the current project with that prompt queued as turn one.
    /**
     * The empty state's input text. Owned by the MODEL, not by a `remember` inside the composable, for one
     * reason: when the queued first prompt can't be delivered (open refused, never landed, send gated) the
     * text has to come back to the user intact. A composable-local field would be gone by then — the pane
     * recomposes through open/fail — and silently eating what someone typed is the one outcome this feature
     * must never have. Defaults are inert so seed/preview models compile untouched.
     */
    var newSessionPrompt: String
        get() = ""
        set(_) {}

    /** A first prompt is queued and the session it belongs to hasn't landed yet — the empty state locks its
     *  field and says so, instead of looking idle while an open is in flight. */
    val startingSession: Boolean get() = false

    /** Why the last [startSessionWithPrompt] didn't deliver; null = nothing to report. The text is still in
     *  [newSessionPrompt] (or, for [NewSessionPromptError.SEND_REFUSED], in the live composer). */
    val newSessionPromptError: NewSessionPromptError? get() = null

    /** Open a session at [dir] on [agent] and send [prompt] as its first turn once the session is live.
     *  Blank prompts and re-entry while one is already queued are no-ops. [agent] became explicit with
     *  issue #260 — the empty state now shows WHICH backend it is about to use and lets you change it, so
     *  the pick has to reach the open. It defaults to [defaultAgent], the pre-#260 behavior. */
    fun startSessionWithPrompt(dir: String, prompt: String, agent: AgentKind = defaultAgent) {}

    /** Clear the inline failure line (the user edited the prompt / picked another project). */
    fun dismissNewSessionPromptError() {}
    /**
     * True when the ACTIVE computer is the one this desktop app runs on (issue #163). Gates the native
     * directory chooser: a local Finder panel can only browse local disk, so a remote machine has to fall
     * back to the typed-path popover. Default false = "assume remote", the safe direction — a wrong false
     * costs a typed path, a wrong true offers folders the daemon can't see.
     */
    val activeIsThisMachine: Boolean get() = false
    /**
     * Open an already-existing folder picked from disk (issue #163), splitting on whether it has history:
     * a folder with sessions opens as that project; a folder with none seeds the new-session popover.
     * Default implementation is the seed path — models with no directory listing can't tell the two apart.
     */
    fun openFolderPath(path: String) { openNewSession(path) }

    // ── remote directory browser (issues #218/#214) ──────────────────────────────────────────────────
    // When the ACTIVE daemon is another machine, a local native chooser browses the WRONG filesystem — so
    // the folder picks (⌘O / the bridge project list) drive the daemon-side #152 browse wire instead: the
    // same ListPathEntries frame the @-completer uses, anchored at "~" (the daemon home) or a reported fs
    // root. These reuse the pure helpers in ui/DirectoryPicker.kt. Defaults are inert for seed/preview.
    /** Latest anchored folder-browse reply (match its (workdir, subPath) before use). */
    val browseListing: dev.ccpocket.protocol.PathEntries? get() = null
    /** The daemon machine's filesystem roots, latched from the "~" reply (owner-only; empty on old daemon/guest). */
    val browseRoots: List<String> get() = emptyList()
    /** The daemon's known project directories — feeds recents/home inference + the "already a project" badge. */
    val browseDirectories: List<dev.ccpocket.protocol.DirectoryEntry> get() = emptyList()
    /** Request the children under ([anchor] + [subPath]); the reply lands in [browseListing]. Resets the
     *  held listing to null first so a reopened picker can't flash the previous level's stale rows. */
    fun requestBrowse(anchor: String, subPath: String) {}

    // main pane: the open chat
    val hasChat: Boolean
    /** True while an OpenSession is in flight — messages are already cleared and convoId nulled, but the
     *  daemon hasn't answered with SessionLive yet (issue #82). ChatPane shows a loading transition for the
     *  target session instead of the blank "No session open" empty state, which read as "didn't respond". */
    val opening: Boolean get() = false

    /** True when the last open never landed — the daemon didn't answer inside the repo's 8s window (issue
     *  #235). The phone shows this as a slim banner over its own screens; the desktop's main pane had no
     *  consumer at all, so a timed-out open dropped straight back to the blank "No session open" state,
     *  which reads as "your click never happened". Distinct from [opening] and from an ordinary empty pane. */
    val openFailed: Boolean get() = false

    /** Re-send the open that failed — the failure pane's retry. Replays the same request (same workdir,
     *  session, agent/mode/model), so a retry can't land under different flags than the click that failed. */
    fun retryOpen() {}

    val chatTitle: String
    val chatAgent: AgentKind
    val chatWorkdir: String
    val chatBranch: String?
    val chatModel: String
    /** Raw model id (unaliased) — the quick-actions picker compares options against this. */
    val chatModelId: String get() = chatModel
    val chatMode: PermissionMode
    val chatPermissionMode: String? get() = null
    val chatEffort: String? get() = null
    val chatServiceTier: String? get() = null
    /** The daemon's third-party ANTHROPIC_BASE_URL (issue #139) — non-null puts the gateway model
     *  presets first in the ⋯ model picker. Default null keeps Seed/test fakes compiling. */
    val gatewayBaseUrl: String? get() = null
    /** Ids the gateway itself reported (issue #167 ②). Authoritative where non-empty; empty falls back
     *  to the built-in seed table. Desktop ships on its own cadence, so without this the retired ids
     *  #167 exists to kill would keep showing here after mobile stopped showing them. */
    val gatewayModels: List<String> get() = emptyList()
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
    fun switchMode(m: PermissionMode, permissionMode: String?) { switchMode(m) }
    fun switchModel(name: String) {}
    fun switchEffort(level: String?) {}
    fun switchServiceTier(tier: String?) {}
    fun effortOptions(): List<String> = emptyList()
    fun serviceTierOptions(): List<dev.ccpocket.protocol.ModelServiceTier> = emptyList()
    fun effortOptionsFor(agent: AgentKind, model: String?): List<String> = emptyList()
    fun serviceTierOptionsFor(agent: AgentKind, model: String?): List<dev.ccpocket.protocol.ModelServiceTier> = emptyList()
    fun permissionModeAvailable(id: String): Boolean = false
    /** Agent model lists from the daemon — fetched by [fetchModels]. */
    fun modelsForAgent(agent: AgentKind): List<String> = emptyList()
    fun fetchModels(agent: AgentKind) {}
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
    fun openChanges() { showChanges = true; fetchChangedFiles(); loadFilesShowHidden() }

    // ── 文件浏览「全部」视角（files-browser-dual-view）───────────────────────────────────────────
    // 同一个 Changes overlay 的第二个视角，不是第二个实体：逐层缓存住在 repo（['fileTree']），
    // 这里只是把它投过来。inert 默认值保持 seed/preview 模型零改动——它们的树永远是空的。
    /** '/'-keyed subPath → 那一层的清单（"" = workdir 本身）。 */
    val fileTree: Map<String, dev.ccpocket.protocol.PathEntries> get() = emptyMap()
    /** 请求某一层；已缓存 / 在途则 no-op。 */
    fun browseFileTree(subPath: String) {}
    /** 丢弃逐层缓存（overlay 关闭）——下次打开重新读到最新磁盘状态。 */
    fun clearFileTree() {}
    /** 显示 `.` 开头的隐藏项；按 workdir 持久化。 */
    val filesShowHidden: Boolean get() = false
    fun toggleFilesShowHidden() {}
    fun loadFilesShowHidden() {}

    // ── Git panel (issue #280) + worktrees (issue #281) ──────────────────────────────────────────
    // Same shape as `changes` above: inert defaults so seed/preview models compile untouched, live
    // values ride the repository. Every one of these is a NEW frame family, so a daemon that predates
    // them simply never answers and the repo's deadline lands the overlay in its "update" state.
    val gitStatus: dev.ccpocket.protocol.GitStatus? get() = null
    val gitStatusLoading: Boolean get() = false
    /** No reply — the daemon predates pocket/git.*; the overlay shows its "update the computer" state. */
    val gitStatusStale: Boolean get() = false
    val gitDiff: dev.ccpocket.protocol.GitDiff? get() = null
    val gitDiffPath: String? get() = null
    val gitDiffStaged: Boolean get() = false
    /** The verb currently running — drives the spinner in the button that started it, nothing else. */
    val gitBusyOp: String? get() = null
    val gitError: dev.ccpocket.protocol.GitActionResult? get() = null
    /** A two-step verb's preview: non-null raises the confirm dialog and nothing else. */
    val gitPendingConfirm: dev.ccpocket.protocol.GitActionPreview? get() = null
    fun fetchGitStatus(withBranches: Boolean = false) {}
    fun openGitDiff(path: String, staged: Boolean) {}
    fun gitAct(op: String, paths: List<String> = emptyList(), message: String? = null, branch: String? = null) {}
    fun confirmPendingGit() {}
    fun dismissGitConfirm() {}
    fun dismissGitError() {}
    /** Open the panel: flip the flag and re-read, in the [openChanges] idiom. Branches ride along so
     *  the branch popover has its data before it is asked for. */
    fun openGit() { showGit = true; fetchGitStatus(withBranches = true) }

    val worktrees: dev.ccpocket.protocol.WorktreeList? get() = null
    val worktreesLoading: Boolean get() = false
    val worktreesStale: Boolean get() = false
    fun fetchWorktrees() {}
    fun addWorktree(branch: String, createBranch: Boolean) {}
    // the post-create receipt (#281 功能范围, restored by #294 真机反馈) and its one verb
    val worktreeCreated: dev.ccpocket.app.data.WorktreeCreated? get() = null
    fun dismissWorktreeCreated() {}
    fun openWorktreeSession(path: String) {}
    fun removeWorktree(path: String) {}
    fun openWorktrees() { showWorktrees = true; fetchWorktrees() }

    // installed skills/plugins browser (issue #132): the machine's ~/.claude catalog, plus the open
    // project's `.claude/skills` when a chat is live. Defaults keep seed/preview models inert.
    val skillCatalog: dev.ccpocket.protocol.SkillCatalog? get() = null
    val skillCatalogLoading: Boolean get() = false
    /** No reply — the daemon predates pocket/skills.*; the browser shows its "update the daemon" state. */
    val skillCatalogStale: Boolean get() = false
    fun fetchSkillCatalog() {}
    /** Open the browser: flip the flag and re-pull the catalog (cheap daemon-side disk scan). */
    fun openSkills() { showSkills = true; fetchSkillCatalog() }

    // headless bridges (issue #91 follow-up): mint / manage the IM bots this machine answers to.
    // Defaults keep seed/preview models inert, like the skills browser above.
    val bridges: List<dev.ccpocket.protocol.BridgeInfo> get() = emptyList()
    val bridgesLoaded: Boolean get() = false
    /** No reply — the daemon predates pocket/bridge.*; the page shows its "update the daemon" state. */
    val bridgesStale: Boolean get() = false
    val bridgeBusy: Boolean get() = false
    val bridgeError: String? get() = null
    /** Keys a MERGE edit came back WITHOUT — an old daemon replaced wholesale; they must be re-entered. */
    val bridgeMergeLost: List<String>? get() = null
    /** A just-minted UNMANAGED credential to copy out; null for managed bridges (nothing to hand over). */
    val bridgeCredential: dev.ccpocket.protocol.BridgeCredential? get() = null
    fun fetchBridges() {}
    fun createBridge(
        name: String,
        workdirs: List<String>,
        tier: dev.ccpocket.protocol.AccessTier,
        maxSessions: Int?,
        runner: dev.ccpocket.protocol.BridgeRunnerSpec?,
        allowedCommands: List<String> = emptyList(),
    ) {}
    fun revokeBridge(name: String) {}
    fun controlBridgeRunner(name: String, action: String) {}
    fun configureBridgeRunner(name: String, spec: dev.ccpocket.protocol.BridgeRunnerSpec, mergeEnv: Boolean = false, workdirs: List<String>? = null, allowedCommands: List<String>? = null) {}
    fun clearBridgeCredential() {}

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
    /** "n / m" while several asks queued behind one another (approval design M1); null for the single-ask
     *  case, which renders exactly the old card. */
    val askQueuePosition: Pair<Int, Int>? get() = null
    /** M3 advisory risk of the current [ask] ("low"/"medium"/"high"/"unknown"); null = no assessment. */
    val askRisk: String? get() = null
    fun resolve(allow: Boolean, remember: Boolean)
    // ── approval design M2 (defaults keep seed/preview models inert) ──
    /** 允许本任务: issue a TASK grant — matching actions auto-run until this task ends. */
    fun resolveTaskGrant() {}
    /** 换种安全方式: structured retry-under-constraints rides a DENY back to the agent. */
    fun retrySafer(constraints: List<String>) {}
    /** "收紧" on an autorun chip: revoke its grant / clear its session rule. */
    fun tightenAutoRun(item: ChatItem.AutoRun) {}
    /** AttentionLease heartbeat while the approval card is on screen (30s cadence). */
    fun askHeartbeat() {}
    /** Release the lease early (window unfocused / card left composition) — P2-1. */
    fun askHeartbeatRelease() {}
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

    /** The connected daemon's version and how to update IT (issue #200), from `DaemonInfo`. Null before
     *  the first one lands or from a daemon too old to report — shown as "unknown", never as "current".
     *  Worth its own row here because the desktop app usually shares a machine with the daemon, so a
     *  stale daemon behind a fresh app is the common case. */
    val daemonVersion: String? get() = null
    /** Non-null only when that daemon is actually behind: the exact command for ITS install layout. */
    val daemonUpdateCommand: String? get() = null
    /** Check GitHub releases for a newer app version, then classify how this install can take it. */
    fun checkForUpdates() {}
    /** STANDALONE installs only: download the new dmg/msi, verify its SHA256, replace this app and relaunch. */
    fun applyUpdate() {}
    /** The upgrade command to copy for a package-manager install (brew/scoop), else null. */
    val updateCommand: String? get() = null
    /** Releases page for the "can't self-update from here" fallback (brew/scoop/unknown). */
    val updateReleasesUrl: String get() = DesktopUpdater.RELEASES_URL
    /** Agent choices accepted by the active daemon. Seed/preview models emulate a current daemon, so they
     *  offer the full enum — the live adapter narrows it through the same projection mobile uses. */
    val availableAgents: List<AgentKind> get() = AgentKind.entries
    var defaultAgent: AgentKind
    var defaultMode: PermissionMode
    val defaultPermissionMode: String? get() = null
    fun setDefaultMode(mode: PermissionMode, permissionMode: String?) { defaultMode = mode }
    var defaultEffort: String?
        get() = null
        set(_) {}
    var defaultServiceTier: String?
        get() = null
        set(_) {}
    // Backend-scoped model defaults for new sessions; null follows that CLI's own configured default.
    fun defaultModelFor(agent: AgentKind): String?
    fun setDefaultModelFor(agent: AgentKind, model: String?)
    // context-window override (tokens) for the usage statusline's 100% mark; null = follow the derived window (#60)
    var contextWindowOverride: Long?
    var terminalApp: TerminalApp // which terminal the ">_" chat-header button opens (issue #44)
    // ── embedded terminal (issue #153) ──
    /** True (the default) = terminal gestures open the embedded ChatPane dock; false = the external
     *  app ([terminalApp]). Settings ▸ Terminal owns the flip; the header menu picks per-gesture only. */
    var terminalDefaultEmbedded: Boolean
        get() = true
        set(_) {}
    /** The embedded dock's state — ONE shell, bound to the session cwd that opened it. Null = this
     *  model can't host a dock (bare fakes); Seed and Repo both provide a controller (Seed's has no
     *  engine factory, so tests/previews drive the chrome without ever spawning a PTY). */
    val terminalPanel: TerminalPanelController? get() = null
    // menu-bar presence (issue #151, direction 1): the OS status glyph + anchored popover. Default ON —
    // the environment layer is the point; Settings ▸ General offers the opt-out.
    var menuBarEnabled: Boolean
    // appearance (issue #63): force light/dark or follow the OS. The window root reads this into PocketTheme;
    // RepoDesktopModel persists it through the shared repo, seed/preview models just hold it in memory.
    var themeMode: ThemeMode
    // global accent source (issue #204): POCKET terracotta or Codex teal. Same persistence path as themeMode;
    // the window root passes it to PocketTheme(accent = …) so every Tok.accent slot follows.
    var accentTheme: dev.ccpocket.app.theme.AccentTheme
    // chat-stream alignment (issue #213, desktop-only): all-left document flow (default) or left/right bubbles.
    // ChatPane reads this per message; persisted desktop-locally like terminalApp, seed models hold it in memory.
    var chatAlignment: ChatStreamAlignment

    // phone-push switch (pocket/push.prefs.*): daemon truth; null = daemon predates it (toggle hidden)
    val phonePush: Boolean? get() = null
    fun setPhonePush(enabled: Boolean) {}
    fun refreshPushPrefs() {}

    // "wait for my decision" (pocket/approval.prefs.*, issue #201): daemon truth, same null-means-old-daemon
    // contract as the push toggle — a daemon that predates #201 never replies, so the row stays hidden.
    val approvalNoAutoDeny: Boolean? get() = null
    fun setApprovalNoAutoDeny(enabled: Boolean) {}
    fun refreshApprovalPrefs() {}

    // Full Control expiry (pocket/approval.prefs.*, issue #220): daemon truth, 0 = never expires (default).
    // Rides the same capability gate as [approvalNoAutoDeny] — shown only once an ApprovalPrefs reply arrives.
    val approvalFullControlExpiryMs: Long? get() = null
    fun setFullControlExpiryMs(ms: Long) {}

    // read-only OBSERVE view (the session is owned by a terminal/VS Code on the computer): the composer
    // must yield — a prompt sent into an observe convo is silently unroutable on the daemon (issue #45 ②)
    val observing: Boolean get() = false
    fun takeOver() {}

    // interrupt the running turn (■ beside send / Esc); the interrupted prompt returns to the composer (#48)
    fun stopTurn() {}
    // re-run a delivered-but-no-turn prompt (issue #104) under a fresh id; no-op unless turnStalled
    fun resendStalled() {}
    fun renameComputer(c: DkComputer, label: String?) // null clears back to the accountId fallback
    /** Remove this daemon binding from the desktop's local credential list; the daemon itself is unchanged. */
    fun removeComputer(c: DkComputer)

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
