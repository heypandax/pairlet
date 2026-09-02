package dev.ccpocket.app.desktop

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.ccpocket.app.APP_VERSION
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.data.FleetCoordinator
import dev.ccpocket.app.data.FleetRuntime
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.SidePane
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.update_failed
import dev.ccpocket.app.resources.update_reach_failed
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.theme.ThemeMode
import dev.ccpocket.app.ui.ComposerState
import dev.ccpocket.app.ui.fleet.MachineOs
import dev.ccpocket.app.ui.fleet.osFromName
import dev.ccpocket.app.ui.folderName
import dev.ccpocket.app.ui.modelLabelForAgent
import dev.ccpocket.app.ui.normalizedDirKey
import dev.ccpocket.app.ui.sameDirPath
import dev.ccpocket.app.ui.tilde
import dev.ccpocket.app.ui.trimTrailingSep
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.isQuestion
import dev.ccpocket.protocol.update.ReleaseClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/** Persisted form of a [DkPin] — decoupled from the view type so the store format stays stable. */
@Serializable
private data class PinRec(
    val accountId: String,
    val sessionId: String,
    val cwd: String,
    val title: String,
    val agent: AgentKind = AgentKind.CLAUDE,
)

/** A session the user removed from RECENT (issue #62). [cwd] scopes the "reopen the project resurfaces it"
 *  recovery — opening that directory clears its hidden entries. Persisted so the ✕ survives refresh/restart. */
@Serializable
private data class HiddenRec(val accountId: String, val sessionId: String, val cwd: String)

/** Persisted form of a RECENT visit (issue #102) — the KEY only: account + path, list order = recency.
 *  A visit's session snapshot is a re-pullable cache and deliberately not stored. */
@Serializable
private data class VisitRec(val accountId: String, val path: String)

/** Persistence seam for the sidebar's durable state (pins / hidden / visits) — the app reads and writes
 *  [SecureStore]; tests inject a map so they never touch the developer's real store file. */
interface DesktopStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

internal object SecureDesktopStore : DesktopStore {
    override fun getString(key: String): String? = SecureStore.getString(key)
    override fun putString(key: String, value: String) = SecureStore.putString(key, value)
}

private val storeJson = Json { ignoreUnknownKeys = true }

/**
 * Live [DesktopModel] backed by [PocketRepository] — the real app path. Getters read the repo's snapshot
 * state so reads recompose. Note the repo is single-session: the sidebar's SESSIONS group is the *current
 * project's* sessions (set by [openProject]); a global all-computers multi-session view needs a repo change
 * and is deliberately out of scope here. The tray is likewise still seed-only.
 *
 * With a [fleet], [repo] FOLLOWS the coordinator's observable primary (issue #103): switching machines
 * promotes the target's hot satellite to primary, and every getter here re-reads through the swap — the
 * sidebar, chat and settings re-derive against the new machine's already-loaded state instead of waiting
 * out a cold handshake. Model-local state (pins, RECENT visits, hidden rows, composer, focus) is keyed by
 * accountId, not by repo instance, so it survives the swap by construction. Without a fleet (tests), the
 * model drives the one fixed repo exactly as before.
 */
class RepoDesktopModel(
    private val fixedRepo: PocketRepository,
    private val scope: CoroutineScope,
    private val fleet: FleetCoordinator? = null,
    private val store: DesktopStore = SecureDesktopStore,
) : DesktopModel {

    private val repo: PocketRepository get() = fleet?.primary ?: fixedRepo

    /** Machine switch — the fleet promotes a hot satellite when it can; standalone falls back cold. */
    private fun switchMachine(target: PairedDaemon) {
        fleet?.switchTo(target) ?: repo.switchDaemon(target)
    }

    override var switcherOpen by mutableStateOf(false)
    override var showNewSession by mutableStateOf(false)
    override var showTray by mutableStateOf(false)
    override var palette by mutableStateOf<PaletteScope?>(null)
    override var showSettings by mutableStateOf(false)
    override var showAddComputer by mutableStateOf(false)
    override var showPermissionModal by mutableStateOf(false)
    override var showAttention by mutableStateOf(false)
    override var showQuickActions by mutableStateOf(false)
    override var showHandoff by mutableStateOf(false)
    override var showFolderPicker by mutableStateOf(false)

    // ── session handoff: straight repo passthrough (daemon truth) ──
    override val activeHandoff get() = repo.activeHandoff.value
    override val handoffInvite get() = repo.lastHandoffInvite.value
    override val handoffCreating get() = repo.handoffCreating.value
    override val handoffError get() = repo.handoffError.value
    override fun handoffIsRecipient() = activeHandoff?.let { repo.isHandoffRecipient(it) } == true
    override fun handoffIsInitiator() = activeHandoff?.let { repo.isHandoffInitiator(it) } == true
    override fun handoffCreate(recipient: String, expiresHours: Int, request: String, recipientDeviceId: String?) =
        repo.createHandoff(recipient, expiresHours, request, recipientDeviceId)

    // ── collaborator links: repo passthrough ──
    override val collaborators get() = repo.collaborators.toList()
    override val collaboratorTicket get() = repo.collaboratorTicket.value
    override val lastCollaboratorConnected get() = repo.lastCollaboratorConnected.value
    override val collaboratorError get() = repo.collaboratorError.value
    override fun listCollaborators() { repo.listCollaborators() }
    override fun createCollaboratorTicket() { repo.lastCollaboratorConnected.value = null; repo.createCollaboratorTicket() }
    override fun removeCollaborator(deviceId: String) { repo.removeCollaborator(deviceId) }
    override fun handoffCancel() { activeHandoff?.let { repo.cancelHandoff(it.id) } }
    override fun handoffRecall() { activeHandoff?.let { repo.recallHandoff(it.id) } }
    override fun handoffComplete() { activeHandoff?.let { repo.completeHandoff(it.id) } }
    override fun handoffReturn(verdict: String?) {
        activeHandoff?.let { repo.returnHandoff(it.id, dev.ccpocket.protocol.HandoffResult(summary = verdict ?: "", verdict = verdict)) }
    }
    override fun dismissHandoffInvite() { repo.lastHandoffInvite.value = null }
    override var showModelPopover by mutableStateOf(false)
    override var showQuotaPopover by mutableStateOf(false)
    override var showChanges by mutableStateOf(false)
    override var showGit by mutableStateOf(false)
    override var showWorktrees by mutableStateOf(false)
    override var showSkills by mutableStateOf(false)
    override var showReviewCenter by mutableStateOf(false)
    override val composerState = ComposerState()

    // ── composer draft follows the session (issue #88) ────────────────────────────────────────────
    // The composer is a single field, but its TEXT is per-session — keyed by the repo's composerKey()
    // (most-durable-first like the mobile composer, #29), the same chain [openSession] re-keys via
    // sessionKey = resumeId.
    private fun composerKey(): String? = repo.composerKey()
    // the key the in-memory [composer] currently belongs to — drives save-old/restore-new on key change
    private var composerDraftKey: String? = composerKey()
    // the open-generation [composer] belongs to — tells a REAL switch from an in-place identity flip
    private var composerEpochSeen = repo.composerEpoch.value

    init {
        // save-old + restore-new as ONE invariant of the key transition (not a flush contract every open
        // entry point must remember): when the composer key changes, the outgoing text is still in
        // [composer] and its key in [composerDraftKey] — persist it (covers a draft typed inside the
        // debounce window), then load the new session's saved draft. The repo's migrateDraft (SessionLive)
        // carries a brand-new session's draft onto its freshly minted sessionId before this fires.
        // Only a REAL switch (composerEpoch bumped by openSession) reloads: the key also flips in place
        // while the user types (brand-new session materializing, forked resume corrected by SessionLive),
        // and reloading the ≤debounce-stale draft then rolled the live text back — a stale whole-text
        // write [ComposerState.setText] would faithfully land at composition end (#118/#108), so the
        // epoch gate keeps it from being ISSUED at all; identity flips only re-home the live text.
        scope.launch {
            snapshotFlow { composerKey() to repo.composerEpoch.value }.collect { (key, epoch) ->
                val switched = epoch != composerEpochSeen
                composerEpochSeen = epoch
                if (key != composerDraftKey) {
                    repo.saveDraft(composerDraftKey, composer)
                    composerDraftKey = key
                    if (switched) composer = repo.draftFor(key) // adopt the target session's draft (#88/#29)
                    else repo.saveDraft(key, composer) // identity flip mid-typing: the live text wins — re-home it
                }
            }
        }
        // debounced persist of composer edits under the current session's key (mirrors the mobile composer)
        scope.launch {
            snapshotFlow { composer }.collectLatest { text -> delay(DRAFT_DEBOUNCE_MS); repo.saveDraft(composerKey(), text) }
        }
        // A freshly-minted sessionId (brand-new session, /clear, a forked resume) is never in the group
        // listing — the list was pulled BEFORE the daemon created the session, and the desktop has no
        // "back to the list" moment to re-pull on (mobile's backToBrowse). When an id materializes that
        // the live rows don't carry, silently re-pull so RECENT shows the new row without a manual ⌘R.
        // Keyed on the id, so no loop: a list fetch never changes sessionKey, and a known id is a no-op.
        scope.launch {
            snapshotFlow { repo.sessionKey.value }.collect { id ->
                if (id == null || sessions.any { it.sessionId == id }) return@collect
                val dir = repo.workdir.value ?: return@collect
                delay(500) // let the agent flush the transcript the daemon's listing reads
                repo.listSessions(dir)
            }
        }
    }

    // ── changes (changed-files v2): straight repo pass-throughs — the repo already scopes them
    // to the open session and re-arms its 8s stale-daemon deadlines on every request
    override val changedFiles: List<dev.ccpocket.protocol.ChangedFile> get() = repo.changedFiles
    override val changedFilesLoading: Boolean get() = repo.changedFilesLoading.value
    override val changedFilesStale: Boolean get() = repo.changedFilesUnavailable.value
    override fun fetchChangedFiles() = repo.fetchChangedFiles()
    override val selectedChangedPath: String? get() = repo.viewedFilePath.value
    override val selectedDiff: dev.ccpocket.protocol.FileDiff? get() = repo.viewedFileDiff.value
    override val selectedContent: dev.ccpocket.protocol.FileContent? get() = repo.viewedFile.value
    override val selectedContentProgress: Pair<Long, Long>? get() = repo.viewedFileProgress.value
    override fun selectChangedFile(path: String) = repo.openChangedFile(path)

    // 「全部」视角：逐层缓存 + 隐藏项开关也都住在 repo，这里同样只是投影
    override val fileTree: Map<String, dev.ccpocket.protocol.PathEntries> get() = repo.fileTree
    override fun browseFileTree(subPath: String) = repo.browseFileTree(subPath)
    override fun clearFileTree() = repo.clearFileTree()
    override val filesShowHidden: Boolean get() = repo.filesShowHidden.value
    override fun toggleFilesShowHidden() = repo.toggleFilesShowHidden()
    override fun loadFilesShowHidden() = repo.loadFilesShowHidden()

    // ── Git panel (#280) / worktrees (#281): straight repo pass-throughs. The repo already scopes
    // every reply to (convoId, workdir) and arms the 8s stale-daemon deadline on every request.
    override val gitStatus: dev.ccpocket.protocol.GitStatus? get() = repo.gitStatus.value
    override val gitStatusLoading: Boolean get() = repo.gitStatusLoading.value
    override val gitStatusStale: Boolean get() = repo.gitStatusUnavailable.value
    override val gitDiff: dev.ccpocket.protocol.GitDiff? get() = repo.gitDiff.value
    override val gitDiffPath: String? get() = repo.gitDiffPath.value
    override val gitDiffStaged: Boolean get() = repo.gitDiffStaged.value
    override val gitBusyOp: String? get() = repo.gitBusyOp.value
    override val gitError: dev.ccpocket.protocol.GitActionResult? get() = repo.gitError.value
    override val gitPendingConfirm: dev.ccpocket.protocol.GitActionPreview? get() = repo.gitPendingConfirm.value
    override fun fetchGitStatus(withBranches: Boolean) = repo.fetchGitStatus(withBranches)
    override fun openGitDiff(path: String, staged: Boolean) = repo.openGitDiff(path, staged)
    override fun gitAct(op: String, paths: List<String>, message: String?, branch: String?) =
        repo.gitAct(op, paths, message, branch)
    override fun confirmPendingGit() = repo.confirmPendingGit()
    override fun dismissGitConfirm() = repo.dismissGitConfirm()
    override fun dismissGitError() = repo.dismissGitError()
    override val worktrees: dev.ccpocket.protocol.WorktreeList? get() = repo.worktrees.value
    override val worktreesLoading: Boolean get() = repo.worktreesLoading.value
    override val worktreesStale: Boolean get() = repo.worktreesUnavailable.value
    override fun fetchWorktrees() = repo.fetchWorktrees()
    override fun addWorktree(branch: String, createBranch: Boolean) = repo.addWorktree(branch, createBranch)
    override val worktreeCreated get() = repo.worktreeCreated.value
    override fun dismissWorktreeCreated() = repo.dismissWorktreeCreated()
    override fun openWorktreeSession(path: String) {
        showWorktrees = false; showGit = false
        // same bookkeeping as startSession: the checkout enters RECENT like any clicked project, and
        // the open itself is the ordinary OpenSession the mobile card overflow sends (#281 §2)
        openProject(DkProject(path = path, name = folderName(path)))
        repo.openSession(wd = path)
    }
    override fun removeWorktree(path: String) = repo.removeWorktree(path)

    // ── ReviewRequest (REVIEW-REQUEST.md §12) ──
    // The whole repository, not field-by-field: the Center's UI is shared verbatim with mobile, and
    // `repo` already follows the fleet's primary — so switching machines re-points the ledger for free,
    // which is exactly the "the active daemon owns what you see" rule the Center needs.
    override val reviewRepo: PocketRepository get() = repo
    override val reviewPending: Int get() = repo.reviewPendingCount
    override fun refreshReviews() { repo.refreshReviews() }

    // ── installed skills/plugins browser (issue #132): straight repo pass-throughs ──
    override val skillCatalog: dev.ccpocket.protocol.SkillCatalog? get() = repo.skillCatalog.value
    override val skillCatalogLoading: Boolean get() = repo.skillCatalogLoading.value
    override val skillCatalogStale: Boolean get() = repo.skillCatalogUnavailable.value
    override fun fetchSkillCatalog() = repo.fetchSkillCatalog()

    // ── headless bridges (issue #91 follow-up): straight repo pass-throughs ──
    override val bridges: List<dev.ccpocket.protocol.BridgeInfo> get() = repo.bridges
    override val bridgesLoaded: Boolean get() = repo.bridgesLoaded.value
    override val bridgesStale: Boolean get() = repo.bridgesUnavailable.value
    override val bridgeBusy: Boolean get() = repo.bridgeBusy.value
    override val bridgeError: String? get() = repo.bridgeError.value
    override val bridgeMergeLost: List<String>? get() = repo.bridgeMergeLost.value
    override val bridgeCredential: dev.ccpocket.protocol.BridgeCredential? get() = repo.bridgeCredential.value
    override fun fetchBridges() = repo.fetchBridges()
    override fun createBridge(
        name: String,
        workdirs: List<String>,
        tier: dev.ccpocket.protocol.AccessTier,
        maxSessions: Int?,
        runner: dev.ccpocket.protocol.BridgeRunnerSpec?,
        allowedCommands: List<String>,
    ) = repo.createBridge(name, workdirs, tier, maxSessions, runner, allowedCommands)
    override fun revokeBridge(name: String) = repo.revokeBridge(name)
    override fun controlBridgeRunner(name: String, action: String) = repo.controlBridgeRunner(name, action)
    override fun configureBridgeRunner(name: String, spec: dev.ccpocket.protocol.BridgeRunnerSpec, mergeEnv: Boolean, workdirs: List<String>?, allowedCommands: List<String>?) =
        repo.configureBridgeRunner(name, spec, mergeEnv, workdirs, allowedCommands)
    override fun clearBridgeCredential() = repo.clearBridgeCredential()

    override val connected: Boolean get() = repo.sessionActive.value
    override val connGen: Int get() = repo.connGen.value

    // bindings don't carry an OS on the wire — read it off the user's naming, like the mobile fleet does
    private fun PairedDaemon.dkOs(): DkOs = when (osFromName(displayName())) {
        MachineOs.WIN -> DkOs.WIN
        MachineOs.LINUX -> DkOs.LINUX
        MachineOs.MAC -> DkOs.MAC
    }

    private fun PairedDaemon.toDk(online: Boolean): DkComputer =
        DkComputer(accountId = accountId, name = displayName(), os = dkOs(), online = online, meta = if (online) "online" else "")

    private fun DirectoryEntry.toDkProject() =
        DkProject(
            path = path, name = name.ifBlank { path }, running = open || busy,
            // guest share provenance (issue #115) rides along so every project surface can render the pill
            sharedBy = sharedBy, shareExpiresAt = shareExpiresAt,
        )

    override val activeComputer: DkComputer?
        get() = repo.paired.value?.toDk(online = repo.phase.value == ConnPhase.Ready)

    override val computers: List<DkComputer>
        get() {
            val activeId = repo.paired.value?.accountId
            val ready = repo.phase.value == ConnPhase.Ready
            return repo.pairedList.map { it.toDk(online = it.accountId == activeId && ready) }
        }

    override fun selectComputer(c: DkComputer) {
        switcherOpen = false
        repo.pairedList.firstOrNull { it.accountId == c.accountId }?.let { if (it.accountId != repo.paired.value?.accountId) switchMachine(it) }
    }

    // pair a new computer in a modal over the live shell (no disconnect); the overlay lives in Main with the repo
    override fun addComputer() { switcherOpen = false; showSettings = false; showAddComputer = true }

    // ── fleet: one live link per binding via the FleetCoordinator — the active machine is the primary
    // repo, every other paired machine reads off its pinned satellite (status, projects, pending).
    override val machines: List<DkMachine>
        get() {
            val activeId = repo.paired.value?.accountId
            val fleet = FleetRuntime.forPrimary(repo)
            return repo.pairedList.map { d ->
                val active = d.accountId == activeId
                val link = if (active) repo else fleet?.satellites?.get(d.accountId)
                DkMachine(
                    computer = d.toDk(online = link?.phase?.value == ConnPhase.Ready),
                    active = active,
                    // issue #163: never actually wired before — the "this Mac" pill and the palette's
                    // same label existed but could not render, since nothing ever set this true outside
                    // the seed model. Gating the native folder chooser needed a real answer, so it is
                    // computed here now. Unknown either side (old daemon with no hostname) stays false.
                    thisMachine = d.hostName?.takeIf { it.isNotBlank() }
                        ?.equals(localHostName(), ignoreCase = true) == true,
                    pending = if (link?.pendingAsk?.value != null) 1 else 0,
                    // per-account directories (live when loaded, else the coordinator's last snapshot):
                    // RUNNING rows + non-active group content keep showing through a machine switch,
                    // instead of blanking while links tear down and re-handshake
                    projects = (fleet?.dirsFor(d.accountId) ?: if (active) repo.directories.toList() else emptyList())
                        .map { it.toDkProject() },
                )
            }
        }

    override fun openRunning(m: DkMachine, p: DkProject) {
        navGen++ // user navigation — stop an in-flight RECENT refill from repointing the list (#102)
        optimisticSelectedId = null // this path bypasses selectSession — don't let a stale pick re-light mid-open (#82)
        FleetRuntime.forPrimary(repo)?.focusProject(m.computer.accountId, p.path) ?: super.openRunning(m, p)
    }

    override fun browseRunning(m: DkMachine, p: DkProject) {
        // same machine: the ordinary project open (RECENT bookkeeping included); another machine:
        // switch over and list — but never auto-resume, that's what separates this from openRunning
        if (repo.paired.value?.accountId == m.computer.accountId) openProject(p)
        else FleetRuntime.forPrimary(repo)?.browseProject(m.computer.accountId, p.path) ?: super.browseRunning(m, p)
    }

    override val attention: List<DkAttention>
        get() {
            // aggregated across every live link; satellites carry asks once the daemon broadcasts them
            val links = FleetRuntime.forPrimary(repo)?.repos() ?: listOf(repo)
            return links.mapNotNull { r ->
                // a timed-out ask (issue #100) is terminal — dismiss-only on its inline card — so it's no
                // longer "waiting": drop it from the bell/palette/badge instead of offering a Deny/Allow that
                // would only hit the daemon's ask_expired. Matched by id (askIds are unique per request).
                val ask = r.pendingAsk.value?.takeIf { !r.askTimedOut(it) } ?: return@mapNotNull null
                val d = r.paired.value ?: return@mapNotNull null
                DkAttention(
                    id = ask.askId, accountId = d.accountId, machine = d.displayName(), os = d.dkOs(),
                    tool = ask.tool, preview = ask.diff ?: ask.inputPreview,
                    seconds = null, live = true, // no invented deadline — the inline card carries the real one
                    question = ask.isQuestion, // tray hides Deny/Allow for these (bare ALLOW = "did not answer")
                )
            }
        }

    override fun resolveAttention(a: DkAttention, allow: Boolean) {
        val r = FleetRuntime.forPrimary(repo)?.repoFor(a.accountId) ?: repo
        if (a.live && r.pendingAsk.value?.askId == a.id) {
            r.resolve(if (allow) Decision.ALLOW else Decision.DENY, remember = false)
        }
    }

    override val watch: DkWatch? get() = null // needs a second live stream — multi-connection repo work

    // ── split panes (issue #311) ──────────────────────────────────────────────────────────────────
    // The repository keeps the extra conversations (SidePanes); this maps them onto the shell's verbs.
    // Everything here rides wire and state that already existed: a pane opens with the same OpenSession
    // the sidebar sends, and promotion is literally [selectSession] on the pane's session.

    override val sidePanes: List<SidePane> get() = repo.sidePanes.panes

    override val canSplit: Boolean get() = connected && repo.sidePanes.canOpen()

    override fun openInSplit(s: DkSession, at: Int) {
        if (s.sessionId == repo.sessionKey.value) return // already the focused chat
        repo.sidePanes.open(s.cwd, s.sessionId, s.title, s.agent, repo.defaultMode.value, at)
    }

    override fun closeSplit(paneId: Long) = repo.sidePanes.close(paneId)

    override val splitFocusedSlot: Int get() = repo.sidePanes.focusedSlot.value

    override fun promoteSplit(pane: SidePane) {
        // Promotion is an ordinary session open: the daemon answers by reattaching the conversation it
        // already holds, so nothing forks and nothing restarts. The column release rides the repository's
        // open chokepoint ([SidePanes.releaseToFocus]) rather than a manual detach here, because that is
        // also what moves the focus INTO this column's slot (issue #336) — the chat walks over to the
        // column the user promoted instead of yanking its conversation to wherever the chat was.
        //
        // The outgoing session is deliberately NOT auto-moved into the freed column. openSession already
        // reclaims an idle conversation as it switches, so re-opening it as a column in the same breath
        // would put a CloseSession and an OpenSession for one session in flight together. Sending it back
        // to a column is one gesture away (the sidebar's "Open in split"), and that gesture is ordered.
        //
        // A refusal never comes back, so releaseToFocus never runs for it — openSession's synchronous
        // gates (#235 in-flight/already-open, unsupported agent) return false BEFORE the chokepoint.
        // Without the fallback the column stayed up beside a focused chat showing the same session
        // (already-open is exactly the double-render state), and repeated clicks stayed refused forever.
        // The old unconditional detach was precisely the repair for that state — keep it for the refusal.
        if (!selectSessionReporting(DkSession(pane.sessionId, pane.workdir, pane.title.value, agent = pane.agent))) {
            repo.sidePanes.detach(pane.paneId)
        }
    }

    override fun retrySplitOpen(pane: SidePane) = repo.sidePanes.retry(pane)

    override fun sendSidePrompt(pane: SidePane, text: String): Boolean = repo.sidePanes.sendPrompt(pane, text)

    override fun stopSideTurn(pane: SidePane) = repo.sidePanes.stopTurn(pane)

    override fun switchSideModel(pane: SidePane, name: String) = repo.sidePanes.switchModel(pane, name)

    // The three verdict verbs all ride [PocketRepository.resolveAskDirect]: the ask the COLUMN is showing
    // decides, and the verdict goes out whether or not the machine-wide inbox still carries a row for it
    // (it legitimately may not — see that function's doc). The verdict fields mirror the focused path's
    // [resolveTaskGrant] / [retrySafer] one-for-one, so a decision means the same thing on the wire
    // wherever it was taken.
    override fun resolvePaneApproval(ask: PermissionAsk, allow: Boolean, remember: Boolean) =
        repo.resolveAskDirect(ask, allow, remember = remember)

    override fun resolvePaneTaskGrant(ask: PermissionAsk) =
        repo.resolveAskDirect(ask, allow = true, grantScope = "task")

    override fun retryPaneSafer(ask: PermissionAsk, constraints: List<String>) =
        repo.resolveAskDirect(ask, allow = false, retrySafer = true, constraints = constraints)

    // AskUserQuestion in a column (W3): same wire as the focused card — answers ride an ALLOW, a skip
    // denies with the note — but addressed by the column's own ask.
    override fun answerPaneQuestions(ask: PermissionAsk, answers: Map<String, String>?, response: String?) =
        repo.answerQuestionsDirect(ask, answers, response)

    override fun skipPaneQuestions(ask: PermissionAsk, message: String) = repo.skipQuestionsDirect(ask, message)

    override fun dismissPaneAsk(ask: PermissionAsk) = repo.sidePanes.noteApprovalResolved(ask.convoId, ask.askId)

    // ── workflow orchestration (issue #106): delegate to the repository; dock state is ui-local ──
    override val workflowRuns: Map<String, dev.ccpocket.protocol.WorkflowRun> get() = repo.workflowRuns
    override val dockedWorkflowRunId: String? get() = repo.viewedWorkflowRunId.value
    override fun openWorkflowPanel(runId: String) = repo.openWorkflow(runId)
    override fun closeWorkflowPanel() = repo.closeWorkflow()
    override fun workflowRunFor(item: ChatItem.Tool): dev.ccpocket.protocol.WorkflowRun? = repo.workflowFor(item)
    override val workflowAgentDetails: Map<String, dev.ccpocket.protocol.WorkflowAgentDetail> get() = repo.workflowAgentDetails
    override fun fetchWorkflowAgentDetail(runId: String, agentIndex: Int, agentId: String?) =
        repo.fetchWorkflowAgentDetail(runId, agentIndex, agentId)

    override val projects: List<DkProject>
        get() = repo.directories.map { it.toDkProject() }

    private fun openSummary() = repo.sessions.firstOrNull { it.cwd == repo.workdir.value && it.title == repo.chatTitle.value }

    /**
     * Daemon truth for "which sessions are alive RIGHT NOW", keyed by session id, taken from every project
     * row's [DirectoryEntry.activeSessions] instead of one directory's session listing — the only source
     * here that is both cross-project and re-pullable (see the desktop shell's directory poll in Main).
     *
     * Null = this daemon's answer carries no information, so callers must infer NOTHING from an absent id:
     *  - the list hasn't been pulled yet (fresh link, just-switched machine) — nothing to say either way;
     *  - the daemon predates the array (< v1.3.1) and reports a live session through the pre-array scalars
     *    alone. Reading its empty list as "nothing is running" would put out every dot on the sidebar.
     * Detected structurally, not by version: [dev.ccpocket.protocol.DaemonInfo.daemonVersion] only started
     * shipping in v1.6.0 — far LATER than activeSessions — so a version gate would also blank the dots on
     * every daemon in between, which do report the truth. Same fallback shape DirList/SessionWorkingSet use.
     */
    private fun daemonLiveSessions(): Map<String, ActiveSession>? {
        val dirs = repo.directories
        if (dirs.isEmpty()) return null
        val live = dirs.flatMap { it.activeSessions }.associateBy { it.sessionId }
        if (live.isEmpty() && dirs.any { it.open || it.activeSessionId != null }) return null
        return live
    }

    /** This row's dot re-decided from [live] (daemon truth). The OPEN chat keeps the live push instead —
     *  [PocketRepository.streaming] sees a turn start that the last project-list pull can't know about yet;
     *  its background work still comes from the daemon, the one side that tracks it. Absent from [live]
     *  means NOT running: that is the whole point of the override, and why the caller passes null (not an
     *  empty map) whenever the daemon can't answer. Idempotent, so a row may pass through twice (the RECENT
     *  groups re-apply it over rows this list already corrected). */
    private fun DkSession.runningFromDaemon(live: Map<String, ActiveSession>, openId: String?, streaming: Boolean): DkSession {
        val s = live[sessionId]
        val fresh = if (sessionId == openId) streaming || s?.busy == true else s != null && (s.executing || s.busy)
        return if (fresh == running) this else copy(running = fresh)
    }

    // derived so the many per-row readers (pin rows, RECENT rows, runningVisible) share one mapping
    // per snapshot instead of re-mapping the whole repo list on every read
    private val sessionsDerived = derivedStateOf {
        val askWd = repo.pendingAsk.value?.let { repo.workdir.value }
        val openId = repo.sessionKey.value.takeIf { repo.convoId.value != null }
        val listed = repo.sessions.map {
            DkSession(
                sessionId = it.sessionId, cwd = it.cwd, title = it.title, agent = it.agent ?: AgentKind.CLAUDE,
                // the open chat's row uses the LIVE streaming state — the listed `live` is a snapshot
                // from listing time and kept a finished turn's dot pulsing until a manual refresh.
                // Superseded below wherever the daemon can answer; this stays the fallback for the ones
                // that can't (see [daemonLiveSessions]).
                running = if (it.sessionId == openId) repo.streaming.value || it.busy else it.live || it.busy,
                pending = if (askWd != null && it.cwd == askWd && it.title == repo.chatTitle.value) 1 else 0,
                model = it.model,
                group = it.group, // custom session-group membership (issue #119)
                forkedFrom = it.forkedFrom, rewindOf = it.rewindOf, // #282 lineage
            )
        }
        // a just-created session isn't on disk until its first turn persists, so ListSessions can't
        // return it — synthesize its row at the top of its group until a later listing has it (#42)
        // openChatUnlisted() already returns null once the listing contains the session, so no re-check here
        val synth = openChatUnlisted()
        val rows = if (synth != null) listOf(synth) + listed else listed
        // The dot follows the daemon HERE too, not only in the RECENT groups: [liveSession] resolves out of
        // this list before it ever looks at a group, so the CURRENT project's pinned rows (the sidebar's pin
        // zone, runningVisible) were still reading the listing-time `live` while every other project's pin
        // had already been corrected group-side.
        //
        // The synthesized row survives this by construction, and it MUST: a brand-new session hasn't
        // persisted its first turn, so it is in no listing and usually in no activeSessions either —
        // "absent ⇒ not running" would put out the dot on the one session that is certainly alive. It is
        // the open chat by definition ([openChatUnlisted] only ever returns repo.sessionKey under a live
        // convoId), so it takes the openId branch, which never consults the index for the verdict.
        val live = daemonLiveSessions() ?: return@derivedStateOf rows
        rows.map { it.runningFromDaemon(live, openId, repo.streaming.value) }
    }
    override val sessions: List<DkSession> get() = sessionsDerived.value

    /** The open chat as a DkSession when it belongs to the listed dir but the listing doesn't know it yet
     *  (brand-new session pre-first-persist). Null once ListSessions returns it — the real row wins. */
    private fun openChatUnlisted(): DkSession? {
        // real sessionId only (SessionLive echoes it moments after open): the row's id doubles as the
        // resumeId when clicked, and a convoId there would send the daemon a bogus resume
        val id = repo.sessionKey.value ?: return null
        val wd = repo.workdir.value ?: return null
        val dir = repo.sessionsDir.value ?: return null
        if (repo.convoId.value == null || (wd != dir && tilde(wd) != dir)) return null
        if (repo.sessions.any { it.sessionId == id }) return null
        if (openSummary() != null) return null // already listed under (cwd, title) — e.g. resumed before SessionLive echoes the id
        return DkSession(
            sessionId = id, cwd = wd, title = repo.chatTitle.value ?: "Chat",
            agent = repo.sessionAgent.value ?: AgentKind.CLAUDE,
            running = repo.streaming.value, // live truth — a hardcoded true kept the dot pulsing after the turn
            model = repo.model.value,
        )
    }

    // Optimistic selection (issue #82): the sessionId the user just asked to open, highlighted the instant
    // selectSession/openPin fires so the sidebar row/group moves off the previous session immediately —
    // instead of lagging (or showing nothing) through the async opening window while workdir still points at
    // the old session and neither openSummary nor openChatUnlisted resolves the new one yet. Gated on
    // repo.opening so it only wins WHILE an open is in flight: once SessionLive lands (opening→false,
    // convoId+workdir updated together) the real resolution takes over; a failed/timed-out open clears
    // opening too, so a stale value can never keep a phantom row lit. Cleared on new/cross-machine opens
    // (no listed row to point at yet).
    private var optimisticSelectedId by mutableStateOf<String?>(null)

    override val selectedSessionId: String? get() =
        optimisticSelectedId?.takeIf { repo.opening.value }
            ?: openSummary()?.sessionId ?: openChatUnlisted()?.sessionId

    // ── RECENT groups: session lists cached per visited project (per account) ─────────────────────
    // The protocol only lists sessions per directory (ListSessions), so cross-project RECENT is built
    // client-side: each visit carries a snapshot of its list, and the current dir always reads live.
    private data class Visit(val accountId: String, val path: String, val snapshot: List<DkSession> = emptyList())

    // most recent first. The KEYS survive restarts (issue #102): loaded from the store here, persisted on
    // every reorder — snapshots stay empty until [refillRecent] (or a fresh visit) re-lists the dir.
    private val visits = mutableStateListOf<Visit>().apply {
        runCatching {
            store.getString(K_VISITS)?.takeIf { it.isNotBlank() }?.let { s ->
                val seen = HashMap<String, Int>() // per-account cap — the same MAX_RECENT openProject enforces
                storeJson.decodeFromString<List<VisitRec>>(s).forEach { r ->
                    val n = seen.getOrElse(r.accountId) { 0 }
                    if (n < MAX_RECENT) { add(Visit(r.accountId, r.path)); seen[r.accountId] = n + 1 }
                }
            }
        }
    }

    /** Persist the visit keys (issue #102) — order carries recency; snapshots are never stored. */
    private fun saveVisits() =
        store.putString(K_VISITS, storeJson.encodeToString(visits.map { VisitRec(it.accountId, it.path) }))

    // user-navigation generation — bumped by every explicit sidebar navigation; an in-flight RECENT
    // refill sweep (issue #102) checks it between steps so it never repoints the list under the user
    private var navGen = 0
    private val refilled = HashSet<String>() // accounts whose restored visits were already swept this run

    /** Canonical identity of a workdir for RECENT-group dedup (issue #58) — see [normalizedDirKey], which
     *  the repo's already-open guard (issue #235) reads too, so the two can't drift apart. */
    private fun normCwd(path: String): String = normalizedDirKey(path)

    /** Whether two paths name the same project — the RECENT-group dedup identity (issue #58). */
    private fun sameDir(a: String, b: String): Boolean = sameDirPath(a, b)

    /** Upsert the live list under its dir before [openProject] points the repo somewhere else — this is
     *  also how a dir listed outside openProject (e.g. a restored chat's) enters RECENT. Converges the stored
     *  key to the daemon's ABSOLUTE workdir once the open session resolved it (sessionsDir only echoes the raw,
     *  maybe-tilde request), so a tilde reseed and a later absolute directory entry don't split (issue #58). */
    private fun snapshotCurrent() {
        val acct = repo.paired.value?.accountId ?: return
        val dir = repo.sessionsDir.value ?: return
        val key = repo.workdir.value?.takeIf { repo.convoId.value != null && sameDir(it, dir) } ?: dir
        val i = visits.indexOfFirst { it.accountId == acct && sameDir(it.path, key) }
        if (i >= 0) {
            val converged = visits[i].path != key
            visits[i] = visits[i].copy(path = key, snapshot = sessions)
            if (converged) saveVisits() // the stored key changed (tilde → absolute, #58) — keep the disk form in step
        } else {
            visits.add(0, Visit(acct, key, sessions))
            saveVisits() // a dir listed outside openProject just entered RECENT (issue #102)
        }
    }

    // Restored visits render empty until re-listed (snapshots aren't persisted — issue #102), and the
    // sidebar hides empty non-current groups, so a restart would LOOK like RECENT was lost. Once the
    // machine is Ready and the app is still cold-idle (nothing listed or open — never hijack a view the
    // user already has), re-pull each restored dir through the ordinary listing path. One sweep per
    // account per run: a reconnect mid-use must not sweep (restoreAfterReconnect owns the current dir).
    init {
        scope.launch {
            // demo mode counts as ready: it loops listings back locally and never attaches a transport
            // (a real demo run has no binding, so acct is null there and the sweep stays off)
            snapshotFlow { (repo.phase.value == ConnPhase.Ready || repo.demoMode.value) to repo.paired.value?.accountId }
                .collect { (ready, acct) ->
                    if (!ready || acct == null || acct in refilled) return@collect
                    refilled += acct
                    if (repo.convoId.value == null && repo.sessionsDir.value == null) refillRecent(acct)
                }
        }
    }

    /** Re-list [acct]'s restored, snapshot-empty visits, oldest first — the most-recent group's echo lands
     *  last, leaving it the live one (the state the user quit in). Each echo is archived into its visit by
     *  [snapshotCurrent] (position preserved by the upsert); user navigation stops the remainder, and an
     *  unanswered dir just stays empty — the sweep must never wedge the sidebar. */
    private suspend fun refillRecent(acct: String) {
        val gen = navGen
        val targets = visits.filter { it.accountId == acct && it.snapshot.isEmpty() }.map { it.path }
        for (dir in targets.asReversed()) {
            if (navGen != gen || repo.convoId.value != null) return
            repo.listSessions(dir)
            // poll instead of snapshotFlow: the echo must be awaitable before the UI's snapshot-apply
            // pump exists (and under a nested Unconfined launch the listing itself only runs once this
            // coroutine suspends — the delay below is that suspension point)
            val echoed = withTimeoutOrNull(REFILL_ECHO_TIMEOUT_MS) {
                while (true) {
                    val d = repo.sessionsDir.value
                    if (d != null && sameDir(d, dir)) break
                    delay(REFILL_POLL_MS)
                }
            } != null
            if (echoed) snapshotCurrent()
        }
    }

    override fun openProject(p: DkProject) {
        navGen++ // user navigation — an in-flight RECENT refill (issue #102) must stop repointing the list
        focusDir(p.path) // the New-session target follows the project the user just opened
        val acct = repo.paired.value?.accountId
        if (acct != null) {
            // deliberately reopening a project resurfaces any of its sessions removed from RECENT (#62 — the ✕ is non-destructive)
            if (hiddenState.removeAll { it.accountId == acct && sameDir(it.cwd, p.path) }) saveHidden()
            snapshotCurrent()
            // normCwd dedup so a tilde-reseeded new session (~/P) reuses the absolute directory-list visit
            // (/Users/x/P) instead of adding a twin group; the surviving visit keeps its absolute path (#58)
            val i = visits.indexOfFirst { it.accountId == acct && sameDir(it.path, p.path) }
            val v = if (i >= 0) visits.removeAt(i) else Visit(acct, p.path)
            visits.add(0, v)
            visits.filter { it.accountId == acct }.drop(MAX_RECENT).forEach { visits.remove(it) }
            saveVisits() // order (recency) changed — keep the persisted keys in step (issue #102)
        }
        repo.listSessions(p.path)
    }

    private val sessionGroupsDerived = derivedStateOf {
        val acct = repo.paired.value?.accountId ?: return@derivedStateOf emptyList()
        val liveDir = repo.sessionsDir.value
        val normLive = liveDir?.let(::normCwd) // constant across this derive — normalize once, not per visit
        val keys = visits.filter { it.accountId == acct }.toMutableList()
        // a list opened outside openProject shows before its first snapshotCurrent lands it in visits.
        // normCwd match so a live tilde dir (~/P) folds into its absolute visit (/Users/x/P) — no twin (#58)
        if (liveDir != null && keys.none { normCwd(it.path) == normLive }) keys.add(0, Visit(acct, liveDir))
        // sessions the user removed from RECENT via the row ✕ (issue #62) — filtered out of every group
        val hidden = hiddenState.filter { it.accountId == acct }.mapTo(HashSet()) { it.sessionId }
        // guest share provenance (issue #115): visits carry only account+path, so the "Shared" pill's
        // owner/expiry re-derive from the directory list (the daemon stamps a guest's shared roots there)
        val sharedDirs = repo.directories.filter { it.sharedBy != null }.associateBy { normCwd(it.path) }
        // ── the running dot ──────────────────────────────────────────────────────────────────────
        // This layer stays even though [sessionsDerived] now corrects its own rows: only the CURRENT group
        // reads that list. Every other group renders [Visit.snapshot] — rows frozen the moment the user left
        // that project (refillRecent sweeps once per account per run, and no global ListSessions ever
        // re-lists the others), which nothing upstream will ever touch again. Re-applying over the current
        // group's already-corrected rows is a no-op ([runningFromDaemon] is idempotent).
        val live = daemonLiveSessions()
        val openId = repo.sessionKey.value.takeIf { repo.convoId.value != null }
        val streaming = repo.streaming.value
        keys.map { v ->
            val norm = normCwd(v.path)
            val current = normLive != null && norm == normLive
            var rows = if (current) sessions else v.snapshot
            if (live != null) rows = rows.map { it.runningFromDaemon(live, openId, streaming) }
            if (hidden.isNotEmpty()) rows = rows.filterNot { it.sessionId in hidden }
            val share = sharedDirs[norm]
            DkSessionGroup(
                path = v.path,
                name = folderName(v.path),
                current = current,
                sessions = rows,
                sharedBy = share?.sharedBy,
                shareExpiresAt = share?.shareExpiresAt,
            )
        }
    }
    override val sessionGroups: List<DkSessionGroup> get() = sessionGroupsDerived.value

    override val sessionsRefreshing: Boolean get() = repo.sessionsRefreshing.value

    override fun refresh(g: DkSessionGroup?) {
        navGen++ // manual refresh repoints the list deliberately — stop any RECENT refill sweep (#102)
        repo.refreshDirectoriesSilently() // manual refresh means "sync the sidebar" — projects/running state rides along
        if (g != null && !g.current) snapshotCurrent() // keep the outgoing live group's rows before repointing
        repo.refreshSessions(g?.path) // null → the current dir; no-op when nothing is listed yet
    }

    /** Re-pull ONLY the daemon's project list — the desktop shell's low-frequency poll while its window is
     *  on screen (see `shouldPollDirectories` in Main), which is what keeps [daemonLiveSessions] honest.
     *  Deliberately not [refresh]: no navGen bump and no session re-listing, so a background tick can never
     *  repoint the list under the user or cancel an in-flight RECENT refill the way a ⌘R is meant to.
     *  Silent on anything but a Ready link — writing into a dead transport only feeds the reconnect ladder
     *  from outside its backoff, and a reconnect re-syncs the list itself. */
    fun syncDirectories() {
        if (repo.phase.value != ConnPhase.Ready) return
        repo.refreshDirectoriesSilently()
    }

    /** RECENT's header clear (issue #102): forget every visited project. Pins and hidden entries are
     *  deliberately untouched (a pin is an explicit keep; hidden rows must stay hidden on a re-visit).
     *  The currently LISTED dir re-enters as the synthetic live group — that list is genuinely open. */
    override fun clearRecent() {
        visits.clear()
        saveVisits()
    }

    // ── custom session groups (issue #119): the current project's groups + mutations ───────────────
    // repo.sessionGroups already tracks the listed dir's groups (null/older-daemon collapsed to empty
    // upstream — so an empty list is the flat-render signal). Every mutation targets the current dir; the
    // daemon answers by re-pushing Sessions, so no optimistic local edit is needed here.
    override val customGroups: List<DkGroup>
        get() = repo.sessionGroups.map { DkGroup(it.id, it.name, it.order) }.sortedBy { it.order }

    // owner-only AND group-aware daemon: groupsSupported is true only when the daemon sent a groups array
    // (owner on a group-aware daemon) — so this shows "+ New group" even at zero groups (first one creatable)
    // yet hides it on an older daemon / guest that omits groups. The sharedBy check is belt-and-suspenders
    // (a guest already reports groups=null → groupsSupported false). Editable requires a listed current dir.
    override val canEditGroups: Boolean
        get() {
            if (!repo.groupsSupported.value) return false
            val dir = repo.sessionsDir.value ?: return false
            return repo.directories.none { sameDir(it.path, dir) && it.sharedBy != null }
        }

    override fun createGroup(name: String) { repo.createGroup(name) }
    override fun renameGroup(groupId: String, name: String) { repo.renameGroup(groupId, name) }
    override fun deleteGroup(groupId: String) { repo.deleteGroup(groupId) }
    override fun assignGroup(sessionId: String, groupId: String?) { repo.assignGroup(sessionId, groupId) }

    // session rename (issue #158) — same gating shape as canEditGroups: the daemon's capability stamp,
    // plus the belt-and-suspenders guest check (a guest's Sessions already comes stamped false)
    override val canRenameSessions: Boolean
        get() {
            if (!repo.renameSupported.value) return false
            val dir = repo.sessionsDir.value ?: return false
            return repo.directories.none { sameDir(it.path, dir) && it.sharedBy != null }
        }
    override fun renameSession(sessionId: String, title: String, wd: String?) {
        // acting on another project's row makes that project the listed one FIRST (the navigation
        // convention) — the daemon answers these verbs with Sessions(thatDir), which repoints the
        // listing anyway; going through focusListedDir means the outgoing group gets its snapshot
        // instead of being left empty and hidden (#102's "empty non-current groups are hidden").
        wd?.let { focusListedDir(it) }
        repo.renameSession(sessionId, title, wd)
    }
    override fun renameError(sessionId: String): String? =
        repo.renameError.value?.takeIf { it.sessionId == sessionId }?.message
    override fun dismissRenameError() { repo.dismissRenameError() }

    // ── session rewind / fork (issue #282): straight delegation, so the two clients can never drift on
    // when the entry appears or on what a refusal means. All the judgement lives in the repository.
    override fun canRewind(item: ChatItem.User): Boolean = repo.canRewind(item)
    override val rewindBlockedByTurn: Boolean get() = repo.rewindBlockedByTurn()
    override fun startRewind(item: ChatItem.User, mode: String) = repo.startRewind(item, mode)
    override val rewindSheet: PocketRepository.RewindSheet? get() = repo.rewindSheet.value
    override fun confirmRewind() = repo.confirmRewind()
    override fun cancelRewind() = repo.cancelRewind()
    override val rewindError: String? get() = repo.rewindError.value
    override fun dismissRewindError() { repo.dismissRewindError() }
    override val sessionLineage: PocketRepository.SessionLineage?
        // scoped to the conversation it named: a banner surviving a switch would label the wrong session
        get() = repo.sessionLineage.value?.takeIf { it.convoId == repo.convoId.value }

    // collapse memory keyed by (canonical project path, group id) — persisted like the RECENT visit keys
    // (issue #102): a snapshot list so reads recompose, written through the same DesktopStore.
    private val groupCollapsedState = mutableStateListOf<String>().apply {
        runCatching {
            store.getString(K_GROUP_COLLAPSED)?.takeIf { it.isNotBlank() }?.let { addAll(storeJson.decodeFromString<List<String>>(it)) }
        }
    }
    private fun groupCollapseKey(path: String, groupId: String) = normCwd(path) + "\u0000" + groupId
    private fun saveGroupCollapsed() = store.putString(K_GROUP_COLLAPSED, storeJson.encodeToString(groupCollapsedState.toList()))
    override fun groupCollapsed(projectPath: String, groupId: String): Boolean = groupCollapseKey(projectPath, groupId) in groupCollapsedState
    override fun setGroupCollapsed(projectPath: String, groupId: String, collapsed: Boolean) {
        val k = groupCollapseKey(projectPath, groupId)
        val has = k in groupCollapsedState
        if (collapsed && !has) { groupCollapsedState.add(k); saveGroupCollapsed() }
        else if (!collapsed && has) { groupCollapsedState.remove(k); saveGroupCollapsed() }
    }

    override fun selectSession(s: DkSession) { selectSessionReporting(s) }

    /**
     * Make [dir]'s project the LIVE-LISTED one when a session navigation lands outside it — the
     * [PocketRepository.switchToSession] convention (repoint sessionsDir + fresh list), extended to the
     * sidebar's session paths. Without it, `g.current` — and with it the #158/#202/#119 right-click
     * verbs — only ever followed PROJECT clicks and the ⌘K switcher; a session click, a pin jump or a
     * ⌘[/⌘] history step into another project left the previous dir "current" and the one the user is
     * demonstrably in reduced to its non-current menu (just "Open in split").
     * The OLD group is snapshotted first, so falling back from live rows keeps rows (an unsnapshotted
     * group would render empty and the sidebar hides empty non-current groups).
     */
    private fun focusListedDir(dir: String) {
        val cur = repo.sessionsDir.value
        if (cur != null && sameDir(cur, dir)) return
        snapshotCurrent()
        repo.sessionsDir.value = dir
        repo.listSessions(dir)
    }

    // The backstop for the paths that never touch a sidebar verb: a push tap, a deep link, a launch
    // restore. workdir only CHANGES when a conversation actually moves project (a reattach or a
    // mid-chat re-announce re-sets the same value, which snapshotFlow dedups), so this cannot hijack
    // a list the user browsed to while chatting — the failure that sank the commonMain SessionLive
    // version of this rule (and phone routing derives screens from sessionsDir, so the rule must not
    // live in shared code at all). Observe views excluded: watching must not repoint the listing.
    init {
        scope.launch {
            snapshotFlow { repo.workdir.value }.collect { wd ->
                if (wd != null && !repo.observing.value) focusListedDir(wd)
            }
        }
    }

    // ── session navigation history (the title bar's ‹ ›) ─────────────────────────────────────────
    // Recorded off the sessionKey echo rather than the click handlers: every way a session opens —
    // rows, pins, the palette, a brand-new ⌘N session — lands here through one seam, id in hand.
    // The cursor-entry guard doubles as the back/forward re-record suppressor: [openNavEntry] moves
    // the cursor BEFORE opening, so the landing echo matches the cursor entry and is not a new visit.
    // In-memory on purpose — history is a within-run trail, not state worth resurrecting on launch.
    private data class NavEntry(val accountId: String, val sessionId: String, val cwd: String, val title: String?, val agent: AgentKind)
    private val navStack = mutableStateListOf<NavEntry>()
    private var navCursor by mutableStateOf(-1)

    init {
        scope.launch {
            // the PAIR, not the key alone: an open pins sessionKey before SessionLive brings workdir, so
            // the very first session of a run would slip through a key-only flow (wd still null at pin time)
            snapshotFlow { repo.sessionKey.value to repo.workdir.value }.collect { (id, wd) ->
                val acct = repo.paired.value?.accountId ?: return@collect
                if (id == null || wd == null) return@collect
                val cur = navStack.getOrNull(navCursor)
                if (cur?.sessionId == id) {
                    // Not a new visit — but a cross-project open pins sessionKey a full RTT before
                    // SessionLive moves workdir, so the entry recorded at pin time carries the PREVIOUS
                    // project's dir. The settled pair lands here: correct the entry in place (title too,
                    // same reason), or a later ⌘[ would resume this session against the wrong dirKey.
                    if (cur.cwd != wd || cur.title != repo.chatTitle.value) {
                        navStack[navCursor] = cur.copy(cwd = wd, title = repo.chatTitle.value ?: cur.title)
                    }
                    return@collect
                }
                while (navStack.size > navCursor + 1) navStack.removeAt(navStack.size - 1) // truncate the forward branch
                navStack += NavEntry(acct, id, wd, repo.chatTitle.value, repo.sessionAgent.value ?: AgentKind.CLAUDE)
                if (navStack.size > MAX_NAV_HISTORY) navStack.removeAt(0)
                navCursor = navStack.size - 1
            }
        }
    }

    override val canGoBack: Boolean get() = navCursor > 0
    override val canGoForward: Boolean get() = navCursor < navStack.size - 1
    override fun goBack() { if (canGoBack) openNavEntry(navCursor - 1) }
    override fun goForward() { if (canGoForward) openNavEntry(navCursor + 1) }

    /** Reopen [navStack]'s [i]th entry — [openPin]'s open path (same or cross machine), minus recording. */
    private fun openNavEntry(i: Int) {
        val e = navStack[i]
        val from = navCursor
        navCursor = i // before the open — the landing echo must read this entry (see the recorder above)
        navGen++ // user navigation — stop an in-flight RECENT refill from repointing the list (#102)
        if (e.accountId == repo.paired.value?.accountId) {
            focusDir(e.cwd)
            focusListedDir(e.cwd) // a history step into another project brings its listing (and menus) along
            optimisticSelectedId = e.sessionId
            // A synchronously refused open (unsupported agent, duplicate target) moved nothing on
            // screen — the cursor must not stay on a position the user isn't at, or the arrows lie
            // and the NEXT navigation truncates live forward entries at the phantom spot.
            if (!repo.openSession(wd = e.cwd, resumeId = e.sessionId, title = e.title, agent = e.agent)) navCursor = from
            return
        }
        optimisticSelectedId = null // another machine's session — nothing in the current list to pre-light
        val target = repo.pairedList.firstOrNull { it.accountId == e.accountId }
        if (target == null) { navCursor = from; return } // machine unpaired since — same rollback rule
        switchMachine(target)
        repo.requestOpenSession(e.cwd, e.sessionId, title = e.title, agent = e.agent)
    }

    /** [selectSession] with [PocketRepository.openSession]'s verdict kept — promotion needs it. */
    private fun selectSessionReporting(s: DkSession): Boolean {
        navGen++ // user navigation — stop an in-flight RECENT refill from repointing the list (#102)
        focusDir(s.cwd) // clicking a session focuses its project too, so a following ⌘N lands there
        focusListedDir(s.cwd) // …and makes it the listed one, so its right-click verbs come along
        optimisticSelectedId = s.sessionId // light the clicked row NOW, don't wait out the open (#82)
        return repo.openSession(wd = s.cwd, resumeId = s.sessionId, title = s.title, agent = s.agent)
    }

    // ── pinned sessions: persisted in the SecureStore beside the pairing list ────────────────────
    private val pinsState = mutableStateListOf<DkPin>().apply {
        runCatching {
            store.getString(K_PINS)?.takeIf { it.isNotBlank() }?.let { s ->
                addAll(storeJson.decodeFromString<List<PinRec>>(s).map { DkPin(it.accountId, it.sessionId, it.cwd, it.title, it.agent) })
            }
        }
    }

    private fun savePins() {
        store.putString(K_PINS, storeJson.encodeToString(pinsState.map { PinRec(it.accountId, it.sessionId, it.cwd, it.title, it.agent) }))
    }

    override val pins: List<DkPin> get() = pinsState

    override fun pin(s: DkSession) {
        val acct = repo.paired.value?.accountId ?: return
        if (pinsState.size >= DesktopModel.MAX_PINS || pinsState.any { it.sessionId == s.sessionId }) return
        pinsState += DkPin(acct, s.sessionId, s.cwd, s.title, s.agent)
        savePins()
    }

    override fun unpin(p: DkPin) {
        if (pinsState.removeAll { it.sessionId == p.sessionId }) savePins()
    }

    override fun movePin(from: Int, to: Int) {
        if (from !in pinsState.indices || to !in pinsState.indices || from == to) return
        pinsState.add(to, pinsState.removeAt(from))
        savePins()
    }

    // ── pinned projects (issue #199) ────────────────────────────────────────────────────────────
    // No store of its own: the repo ALREADY keeps a project-pin list (the phone shell's PINNED section),
    // persisted client-side under the same SecureStore this model's own keys live in. Riding it means a
    // project pinned in either shell of this device is pinned in both — and there is exactly one answer
    // to "is this project pinned", instead of two lists drifting apart.
    override val projectPins: List<DkProjectPin> get() = repo.pinnedPaths.map { DkProjectPin(it, folderName(it)) }
    override fun isProjectPinned(path: String): Boolean = repo.isPinned(path)
    override fun pinProject(path: String, name: String) { if (!repo.isPinned(path)) repo.togglePin(path) }
    override fun unpinProject(path: String) { if (repo.isPinned(path)) repo.togglePin(path) }
    private var projectRevealGeneration = 0L
    private var projectListRevealState by mutableStateOf<DkProjectListReveal?>(null)
    override val projectListReveal: DkProjectListReveal? get() = projectListRevealState
    override fun openProjectPin(p: DkProjectPin) {
        // RECENT owns fold/scroll state inside the composable, so listing alone cannot reveal a group the
        // user folded earlier. Publish an explicit one-shot request before re-listing; the generation is
        // required because the same pinned project may be opened, folded, and opened again.
        projectListRevealState = DkProjectListReveal(p.path, ++projectRevealGeneration)
        openProject(DkProject(path = p.path, name = p.name))
    }

    // ── hidden sessions: the RECENT row's ✕ (issue #62) — a persisted, account-scoped remove-from-list ──
    private val hiddenState = mutableStateListOf<HiddenRec>().apply {
        runCatching {
            store.getString(K_HIDDEN)?.takeIf { it.isNotBlank() }?.let {
                addAll(storeJson.decodeFromString<List<HiddenRec>>(it))
            }
        }
    }

    private fun saveHidden() = store.putString(K_HIDDEN, storeJson.encodeToString(hiddenState.toList()))

    override fun hideSession(s: DkSession) {
        val acct = repo.paired.value?.accountId ?: return
        if (hiddenState.none { it.accountId == acct && it.sessionId == s.sessionId }) {
            hiddenState += HiddenRec(acct, s.sessionId, s.cwd)
            saveHidden()
        }
    }

    override fun openPin(p: DkPin) {
        navGen++ // user navigation — stop an in-flight RECENT refill from repointing the list (#102)
        if (p.accountId == repo.paired.value?.accountId) {
            focusDir(p.cwd) // jumping to a pin focuses its project, so a following ⌘N lands there
            focusListedDir(p.cwd) // …and lists it, so the landing project's right-click verbs work
            optimisticSelectedId = p.sessionId // same as selectSession: light the target row through the open (#82)
            repo.openSession(wd = p.cwd, resumeId = p.sessionId, title = p.title, agent = p.agent)
            return
        }
        optimisticSelectedId = null // another machine's session — nothing in the current list to pre-light
        val target = repo.pairedList.firstOrNull { it.accountId == p.accountId } ?: return
        switchMachine(target)
        // open once the switched link lands — the repo's push-tap seam (pendingOpen) owns "open when
        // Ready", including abandonment when the user disconnects or switches again meanwhile. After a
        // promote, [repo] already reads the NEW primary and it's already Ready — the open fires at once.
        repo.requestOpenSession(p.cwd, p.sessionId, title = p.title, agent = p.agent)
    }

    // the project the New-session button targets. Set synchronously the moment the user focuses a project —
    // by opening it (palette / All projects) OR by clicking one of its sessions — so ⌘N follows sidebar
    // navigation instead of lagging on the async ListSessions reply (which set sessionsDir late, leaving a
    // just-switched project's ⌘N pointed at the PREVIOUS project until a session there was clicked). Scoped
    // to the account so a stale path from another machine can't leak in after a computer switch.
    private var focus by mutableStateOf<Pair<String, String>?>(null) // accountId → dir
    private fun focusDir(dir: String) { repo.paired.value?.accountId?.let { focus = it to dir } }

    // the focused project's dir, else the open list's / current chat's — so ⌘N works before any project click.
    // disconnect() (switchDaemon / leaving a machine) clears workdir alongside convoId + sessionsDir, so a
    // just-switched machine starts clean instead of inheriting the PREVIOUS machine's path (which the target
    // daemon would reject as bad_workdir — issue #56). Nothing focused/open on the new machine → null → the
    // popover falls back to "~/", which the target daemon can always resolve.
    override val newSessionDir: String?
        get() {
            val acct = repo.paired.value?.accountId
            return focus?.takeIf { it.first == acct }?.second ?: repo.sessionsDir.value ?: repo.workdir.value
        }
    override var newSessionSeed: String? by mutableStateOf(null)

    /** Issue #163: a folder the daemon already lists has agent history (the listing is Claude ∪ Codex,
     *  so both count) → open it as a project, reusing the exact path string the daemon keys its listing
     *  by. Anything else seeds the new-session popover: the user still chooses agent + permission mode,
     *  because picking a folder says nothing about either and guessing would decide them silently. */
    override fun openFolderPath(path: String) {
        val p = trimTrailingSep(path.trim())
        if (p.isEmpty()) return
        val known = repo.directories.firstOrNull { sameDir(it.path, p) }
        if (known != null) openProject(known.toDkProject()) else openNewSession(p)
    }

    override val activeIsThisMachine: Boolean
        get() = machines.firstOrNull { it.active }?.thisMachine == true

    override fun newSession(dir: String, agent: AgentKind, mode: PermissionMode, permissionMode: String?, model: String?) {
        startSession(dir, agent, mode, permissionMode, model)
    }

    /** [newSession]'s body, plus the one fact the empty-state starter (#256) needs and the popover doesn't:
     *  whether the open actually went out. A refusal is decided SYNCHRONOUSLY inside [PocketRepository.openSession]
     *  (unsupported agent, duplicate target), so a queued first prompt can fail fast instead of waiting out its
     *  whole window for a session nobody is opening. */
    private fun startSession(dir: String, agent: AgentKind, mode: PermissionMode, permissionMode: String?, model: String?): Boolean {
        // "~" ships raw, exactly like mobile's NewPathSheet: the daemon owns the expansion
        // (DirectoryService.expandTilde) — only it knows the remote machine's home
        val typed = trimTrailingSep(dir.trim())
        if (typed.isEmpty()) return false
        showNewSession = false
        optimisticSelectedId = null // a brand-new session has no listed row yet — don't re-light a stale one (#82)
        // a tilde path (the popover's own seed is tilde'd) that names an already-listed project swaps to
        // that entry's ABSOLUTE path: the listing below is keyed by the daemon on the workdir string, and
        // daemons predating the tilde-expanding ListSessions answer a `~/…` list EMPTY — which blanked the
        // project's sessions the moment ⌘N confirmed. Unknown dirs stay as typed (the daemon expands).
        val target = repo.directories.firstOrNull { sameDir(it.path, typed) }?.path ?: typed
        // the project enters RECENT (visit + live listing) exactly as if it had been clicked — without
        // this the group never appeared for a dir typed straight into the popover (#42)
        openProject(DkProject(path = target, name = folderName(target)))
        return repo.openSession(wd = target, startMode = mode, agent = agent, startPermissionMode = permissionMode, startModel = model)
    }

    // ── empty-state session starter (issue #256) ─────────────────────────────────────────────────────
    override var newSessionPrompt: String by mutableStateOf("")
    override var startingSession: Boolean by mutableStateOf(false)
    override var newSessionPromptError: NewSessionPromptError? by mutableStateOf(null)
    override fun dismissNewSessionPromptError() { newSessionPromptError = null }

    /** How long a queued first prompt waits for its session to go live. Generous on purpose — a cold
     *  `claude` start on a big repo is seconds, and the alternative to waiting is throwing the prompt
     *  back at a user whose session is about to open anyway. Tests shrink it. */
    internal var firstPromptTimeoutMs: Long = 30_000L

    /**
     * Open a session and send [prompt] into it as turn one.
     *
     * There is no protocol support for "open with a prompt" and this deliberately doesn't invent one: the
     * queue lives entirely here, as a coroutine that waits for the SAME `convoId` the chat pane waits for
     * and then takes the ordinary [PocketRepository.sendPrompt] path. So the prompt is subject to every gate
     * a typed prompt is, and a daemon of any vintage works unchanged.
     *
     * The text is never dropped on any branch: it stays in [newSessionPrompt] until a send actually succeeds,
     * and only then is the field cleared. Note what this deliberately does NOT do — write into the live
     * composer. That field is owned by the draft collector above, which re-homes it on every composer-key
     * flip; a queued prompt landing there would race the target session's own draft restore and could be
     * saved away under the previous key on the very next flip. Staying out of it keeps a restored draft (#88)
     * untouched by construction, in the success case as much as the failure one.
     */
    override fun startSessionWithPrompt(dir: String, prompt: String, agent: AgentKind) {
        if (prompt.isBlank() || startingSession) return
        newSessionPromptError = null
        newSessionPrompt = prompt // keep it visible through the open; cleared only once it is actually sent
        // Captured BEFORE the open: openSession nulls convoId from a coroutine that can suspend on the
        // outgoing CloseSession first, so "convoId is non-null" alone could still be the PREVIOUS session
        // and would send this prompt into it. (The empty state can only be on screen with no conversation,
        // so this is belt-and-braces — but it is what makes the wait safe for any future caller.)
        val previousConvo = repo.convoId.value
        if (!startSession(dir, agent, defaultMode, defaultPermissionMode, defaultModelFor(agent))) {
            newSessionPromptError = NewSessionPromptError.OPEN_REFUSED
            return
        }
        startingSession = true
        scope.launch {
            // Both outcomes come off the same repo state the pane renders: convoId non-null = SessionLive
            // landed, openTimedOut = the repo's own 8s net already gave up. Waiting on anything else (a
            // fixed delay, `opening` alone) would either fire before the session can take a prompt or hang
            // past the point the repo has already declared failure. The wait itself moved to the repository
            // with issue #260, so the phone's new-task sheet queues against exactly this logic.
            val live = repo.awaitOpenedConvo(previousConvo, firstPromptTimeoutMs)
            when {
                live == null -> newSessionPromptError = NewSessionPromptError.TIMEOUT
                !live -> newSessionPromptError = NewSessionPromptError.OPEN_REFUSED
                // straight to sendPrompt, never through the composer — so a draft the new session restored
                // is untouched by a successful queue as well as by a failed one
                repo.sendPrompt(prompt) -> newSessionPrompt = ""
                // gated (degraded session / uploads in flight): the session is open, so the empty state is
                // gone — but the text is still held here, and re-entering an empty pane shows it again
                else -> newSessionPromptError = NewSessionPromptError.SEND_REFUSED
            }
            // Published LAST (same ordering as the repo's startTaskWithPrompt, same lesson as #245): the
            // outcome must be readable before "starting" flips, or an observer sees "finished, no error"
            // for a queue that actually failed.
            startingSession = false
        }
    }

    override val hasChat: Boolean get() = repo.convoId.value != null
    override val opening: Boolean get() = repo.opening.value // OpenSession in flight — ChatPane shows a loading transition (#82)
    // #235: deliberately NOT auto-dismissed the way the phone's banner is — on the desktop this IS the main
    // pane's content, and fading it out would put the user back on the blank empty state the report is about.
    // It clears when the next open is asked for (openSession) or lands (SessionLive).
    override val openFailed: Boolean get() = repo.openTimedOut.value
    override fun retryOpen() { repo.retryOpen() }
    override val chatTitle: String get() = repo.chatTitle.value ?: "Chat"
    override val chatAgent: AgentKind get() = repo.sessionAgent.value ?: AgentKind.CLAUDE
    override val chatWorkdir: String get() = repo.workdir.value?.let { tilde(it) } ?: ""
    override val chatBranch: String? get() = openSummary()?.gitBranch
    override val chatModel: String get() = modelLabelForAgent(repo.sessionAgent.value, repo.model.value)
    override val chatModelId: String get() = repo.model.value ?: ""
    override val chatMode: PermissionMode get() = repo.mode.value
    override val chatPermissionMode: String? get() = repo.permissionMode.value
    override val chatEffort: String? get() = repo.effort.value
    override val chatServiceTier: String? get() = repo.serviceTier.value
    override val gatewayBaseUrl: String? get() = repo.gatewayBaseUrl.value // issue #139: DaemonInfo's gateway hint
    // issue #167 ②: the gateway's own model list, same source the mobile picker reads
    override val gatewayModels: List<String>
        get() = repo.agentModels[dev.ccpocket.protocol.AgentKind.CLAUDE]?.gatewayModels.orEmpty()
    override val messages: List<ChatItem> get() = repo.messages
    // older-history lazy load (issue #147) — straight delegation to the shared repository
    override val historyHasMore: Boolean get() = repo.historyHasMore.value
    override val historyLoadingOlder: Boolean get() = repo.historyLoadingOlder.value
    override val historyPrependGen: Int get() = repo.historyPrependGen.value
    override val lastHistoryPrependCount: Int get() = repo.lastHistoryPrependCount
    override fun loadOlderHistory() = repo.loadOlderHistory()
    override val streaming: Boolean get() = repo.streaming.value
    // mirrors mobile's under-bubble cue: link not Ready, or receipts stalled on a Ready-looking link (#78)
    override val sendUndelivered: Boolean get() = repo.phase.value != ConnPhase.Ready || repo.sendStalled.value
    // delivered but no turn started within the deadline (issue #104) — the resend cue's driver
    override val turnStalled: Boolean get() = repo.turnStalled.value
    override val turnQueued: Boolean get() = repo.turnQueued.value
    override fun resendStalled() = repo.resendStalledPrompt()

    override fun switchMode(m: PermissionMode) = repo.switchMode(m)
    override fun switchMode(m: PermissionMode, permissionMode: String?) = repo.switchMode(m, permissionMode)
    override fun switchModel(name: String) = repo.switchModel(name)
    override fun switchEffort(level: String?) = repo.switchEffort(level)
    override fun switchServiceTier(tier: String?) = repo.switchServiceTier(tier)
    override fun effortOptions(): List<String> = repo.effortOptions()
    override fun serviceTierOptions() = repo.serviceTierOptions()
    override fun effortOptionsFor(agent: AgentKind, model: String?): List<String> = repo.effortOptions(agent, model)
    override fun serviceTierOptionsFor(agent: AgentKind, model: String?) = repo.serviceTierOptions(agent, model)
    override fun permissionModeAvailable(id: String): Boolean = repo.supportsPermissionMode(id)
    override fun compactConversation() { repo.sendPrompt("/compact") }
    override fun modelsForAgent(agent: AgentKind): List<String> = repo.agentModels[agent]?.models ?: emptyList()
    override fun fetchModels(agent: AgentKind) = repo.fetchModels(agent)
    override fun clearConversation() = repo.clearConversation()

    override fun send(text: String) {
        if (repo.uploadsBusy()) return // send waits for uploads to settle (composer shows the spinner)
        if (text.isBlank() && !repo.hasReadyImages() && !repo.hasLandedFiles()) return // media-only sends are legitimate
        // a gated send (degraded session, issue #65) returns false — keep the composer text for the retry
        if (repo.sendPrompt(text)) { composer = ""; repo.clearDraft(composerKey()) } // clear the persisted draft too (#88)
    }

    override val sessionDegraded: Boolean get() = repo.sessionDegraded.value
    override val contextUsed: Long? get() = repo.contextUsed.value
    override val contextWindow: Long? get() = repo.contextWindow.value

    override val slashCommands: List<dev.ccpocket.protocol.SlashCommand> get() = repo.slashCommands

    // @-file completion (issue #75): browse the open session's cwd via the daemon; separator sniffed off
    // the raw (untilded) workdir so a Windows daemon's "\" paths compose natively (issue #19/#22).
    override val pathListing: dev.ccpocket.protocol.PathEntries? get() = repo.pathListing.value
    override val pathSep: Char get() = repo.workdir.value?.let { if (it.contains('\\')) '\\' else '/' } ?: '/'
    override fun browsePath(sub: String) = repo.browseFiles(sub)

    // remote folder picker (issues #218/#214): the same anchored #152 browse the mobile DirectoryPickerSheet
    // drives — arbitrary ("~" / fs-root) anchor, not the open session's cwd like browsePath above.
    override val browseListing: dev.ccpocket.protocol.PathEntries? get() = repo.browseListing.value
    override val browseRoots: List<String> get() = repo.browseRoots.value
    override val browseDirectories: List<dev.ccpocket.protocol.DirectoryEntry> get() = repo.directories.toList()
    override fun requestBrowse(anchor: String, subPath: String) {
        repo.browseListing.value = null
        repo.browseDirs(anchor, subPath)
    }

    override val pendingImages: List<dev.ccpocket.app.data.PendingImage> get() = repo.pendingImages
    override fun attachImages(raw: List<ByteArray>) = repo.attachImages(raw)
    override fun removePendingImage(id: Long) = repo.removePendingImage(id)
    override fun hasReadyImages(): Boolean = repo.hasReadyImages()

    override val pendingFiles: List<dev.ccpocket.app.data.PendingFile> get() = repo.pendingFiles
    override fun attachFiles(files: List<dev.ccpocket.app.media.PickedFile>) = repo.attachFiles(files)
    override fun removePendingFile(id: Long) = repo.removePendingFile(id)
    override fun retryPendingFile(id: Long) = repo.retryPendingFile(id)
    override fun uploadsBusy(): Boolean = repo.uploadsBusy()
    override fun hasLandedFiles(): Boolean = repo.hasLandedFiles()
    // issue #98: a landed video's inbox path is relative to the session cwd; on desktop the daemon is
    // local, so resolve it against the workdir and hand it to the OS default player (mac `open` plays it
    // in QuickTime; elsewhere Desktop.open). A remote/absent workdir or a not-yet-synced file just no-ops.
    override fun openWorkspaceFile(path: String) {
        val base = repo.workdir.value ?: return
        runCatching {
            val raw = java.io.File(path)
            val f = if (raw.isAbsolute) raw else java.io.File(base, path)
            if (!f.isFile) return@runCatching
            if (System.getProperty("os.name").lowercase().contains("mac")) ProcessBuilder("open", f.absolutePath).start()
            else java.awt.Desktop.getDesktop().open(f)
        }
    }

    override val ask: PermissionAsk? get() = repo.pendingAsk.value
    // issue #100: forward the daemon's TIMED_OUT verdict to the inline card. The repo keeps the pendingAsk and
    // stamps timedOutAskId on AskWithdrawn(TIMED_OUT); matched by id, so a stale id can never bleed onto the
    // next ask (askIds are unique per request) — mirrors the phone's `timedOutAskId == ask.askId` check.
    override val askTimedOut: Boolean
        get() = repo.pendingAsk.value?.let { repo.askTimedOut(it) } ?: false
    override val askQueuePosition: Pair<Int, Int>? get() = repo.askQueueProgress.value
    override val askRisk: String? get() = repo.pendingAsk.value?.let { repo.riskFor(it) }
    override fun resolveTaskGrant() {
        showPermissionModal = false
        repo.resolve(Decision.ALLOW, grantScope = "task")
    }
    override fun retrySafer(constraints: List<String>) {
        showPermissionModal = false
        repo.resolve(Decision.DENY, retrySafer = true, constraints = constraints)
    }
    override fun tightenAutoRun(item: ChatItem.AutoRun) = repo.tightenAutoRun(item)
    override fun askHeartbeat() = repo.sendAskHeartbeat(visible = true)
    override fun askHeartbeatRelease() = repo.sendAskHeartbeat(visible = false)
    override fun resolve(allow: Boolean, remember: Boolean) {
        showPermissionModal = false
        repo.resolve(if (allow) Decision.ALLOW else Decision.DENY, remember)
    }
    override fun dismissAsk() { showPermissionModal = false; repo.dismissAsk() }
    // AskUserQuestion: answers ride an ALLOW verdict (the daemon merges them into claude's updatedInput);
    // skip denies with a note so claude learns the user opted out rather than silently timing out (#57)
    override fun answerQuestions(answers: Map<String, String>?, response: String?) = repo.answerQuestions(answers, response)
    override fun skipQuestions(message: String) = repo.resolve(Decision.DENY, remember = false, message = message)

    override val appVersion: String get() = APP_VERSION
    override val relayUrl: String get() = repo.paired.value?.relay ?: ""

    // ── self-update (Settings ▸ About, issue #87) ─────────────────────────────────────────────────
    // Its own IO scope: the check is a GitHub round-trip and applyUpdate() runs a download that ends by
    // exiting the process, neither of which should ride a UI/composition scope. Snapshot-state writes from a
    // background thread are safe — Compose observes them on the next frame.
    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var updateStateInternal by mutableStateOf<DkUpdateState>(DkUpdateState.Idle)
    private var pendingRelease: ReleaseClient.Release? = null

    override val updateState: DkUpdateState get() = updateStateInternal
    override val daemonVersion: String? get() = repo.versionStatus.value.daemonVersion
    override val daemonUpdateCommand: String?
        get() = repo.versionStatus.value.let { if (it.daemonBehind) it.updateCommand else null }
    override val updateCommand: String?
        get() = (updateStateInternal as? DkUpdateState.Available)?.let { DesktopUpdater.upgradeCommandFor(it.source) }

    // issue #245: Checking / Downloading double as the re-entry guard, and their UI branches are
    // spinner-only — so a state that never leaves them locks "Check for updates" until the app restarts.
    // Every path below therefore ends on a terminal state, with a try/finally backstop for the ones nobody
    // foresaw (see DesktopUpdateCheck.kt for the deadline and the detached blocking probe).
    override fun checkForUpdates() {
        if (updateStateInternal is DkUpdateState.Checking || updateStateInternal is DkUpdateState.Downloading) return
        updateStateInternal = DkUpdateState.Checking
        updateScope.launch {
            try {
                val outcome = resolveUpdateCheck(
                    current = APP_VERSION,
                    probeScope = updateScope,
                    latest = { DesktopUpdater.latest() },
                    source = { DesktopUpdater.currentSource() },
                    failureText = { updateFailureText(Res.string.update_reach_failed, UPDATE_CHECK_FALLBACK_MSG, it) },
                )
                outcome.release?.let { pendingRelease = it }
                updateStateInternal = outcome.state
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                updateStateInternal = DkUpdateState.Failed(t.message?.takeIf { it.isNotBlank() } ?: UPDATE_CHECK_FALLBACK_MSG)
            } finally {
                // backstop: whatever happened (including cancellation), the spinner must not be the last word
                if (updateStateInternal is DkUpdateState.Checking) {
                    updateStateInternal = DkUpdateState.Failed(UPDATE_CHECK_FALLBACK_MSG)
                }
            }
        }
    }

    override fun applyUpdate() {
        val rel = pendingRelease ?: return
        // only a standalone install self-overwrites; brew/scoop show a command and unknown opens the page (UI-side)
        if ((updateStateInternal as? DkUpdateState.Available)?.source != DkInstallSource.STANDALONE) return
        updateStateInternal = DkUpdateState.Downloading(rel.version)
        updateScope.launch {
            try {
                // applyStandalone() does not return on success — it exits so the swap helper / installer can proceed
                runCatching { DesktopUpdater.applyStandalone(rel) }
                    .onFailure { updateStateInternal = DkUpdateState.Failed(updateFailureText(Res.string.update_failed, UPDATE_APPLY_FALLBACK_MSG, it)) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                updateStateInternal = DkUpdateState.Failed(t.message?.takeIf { it.isNotBlank() } ?: UPDATE_APPLY_FALLBACK_MSG)
            } finally {
                if (updateStateInternal is DkUpdateState.Downloading) {
                    updateStateInternal = DkUpdateState.Failed(UPDATE_APPLY_FALLBACK_MSG)
                }
            }
        }
    }

    /** Localized failure line + a short cause hint. suspend getString: the model isn't composable, but the
     *  line IS user-facing copy (the non-composable route PocketRepository already uses for preview asks).
     *  Resource loading can itself fail, so [fallback] is a plain literal — fetching copy must never throw. */
    private suspend fun updateFailureText(res: StringResource, fallback: String, cause: Throwable?): String {
        val base = runCatching { getString(res) }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
        val detail = cause?.let { it.message?.trim()?.takeIf(String::isNotEmpty) ?: it::class.simpleName }
        return if (detail == null) base else "$base ($detail)"
    }
    override var defaultAgent: AgentKind
        get() = repo.sessionDefaultAgent
        set(v) { repo.setDefaultAgent(v) }
    // Single source of truth with mobile (issue #252): the full AgentKind enum minus what this daemon
    // can't take. No desktop-side whitelist — a new backend reaches both pickers the day it lands.
    override val availableAgents: List<AgentKind>
        get() = repo.availableAgents
    override var defaultMode: PermissionMode
        get() = repo.defaultMode.value
        set(v) { repo.setDefaultMode(v) }
    override val defaultPermissionMode: String? get() = repo.defaultPermissionMode.value
    override fun setDefaultMode(mode: PermissionMode, permissionMode: String?) {
        if (permissionMode == dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO) repo.setDefaultAutoMode()
        else repo.setDefaultMode(mode)
    }
    override var defaultEffort: String?
        get() = repo.defaultEffortFor(defaultAgent)
        set(v) { repo.setDefaultEffortFor(defaultAgent, v) }
    override var defaultServiceTier: String?
        get() = repo.defaultServiceTier.value
        set(v) { repo.setDefaultServiceTier(v) }
    override fun defaultModelFor(agent: AgentKind): String? = repo.defaultModelFor(agent)
    override fun setDefaultModelFor(agent: AgentKind, model: String?) { repo.setDefaultModelFor(agent, model) }
    override var contextWindowOverride: Long?
        get() = repo.contextWindowOverride.value
        set(v) { repo.setContextWindowOverride(v) }
    override var themeMode: ThemeMode
        get() = repo.themeMode.value
        set(v) { repo.setThemeMode(v) }
    override var accentTheme: dev.ccpocket.app.theme.AccentTheme
        get() = repo.accentTheme.value
        set(v) { repo.setAccentTheme(v) }
    // desktop-only pref (the daemon/mobile never open local terminals) — persisted beside the pins
    private var terminalAppState by mutableStateOf(TerminalApp.fromId(store.getString(K_TERMINAL_APP)))
    override var terminalApp: TerminalApp
        get() = terminalAppState
        set(v) { terminalAppState = v; store.putString(K_TERMINAL_APP, v.id) }
    // chat-stream alignment (issue #213): desktop-only, persisted beside the pins like terminalApp
    private var chatAlignmentState by mutableStateOf(ChatStreamAlignment.from(store.getString(K_CHAT_ALIGN)))
    override var chatAlignment: ChatStreamAlignment
        get() = chatAlignmentState
        set(v) { chatAlignmentState = v; store.putString(K_CHAT_ALIGN, v.name) }
    // embedded terminal (issue #153): default-open pref + dock height, persisted like terminalApp.
    // Absent key = embedded — the new default holds for existing users too (the issue's call).
    private var terminalEmbedState by mutableStateOf(store.getString(K_TERMINAL_EMBED) != "0")
    override var terminalDefaultEmbedded: Boolean
        get() = terminalEmbedState
        set(v) { terminalEmbedState = v; store.putString(K_TERMINAL_EMBED, if (v) "1" else "0") }
    override val terminalPanel = TerminalPanelController(
        loadHeight = { store.getString(K_TERMINAL_HEIGHT)?.toFloatOrNull() },
        saveHeight = { store.putString(K_TERMINAL_HEIGHT, it.toString()) },
    )

    // sidebar collapsed (desktop chrome v2) — desktop-only pref, persisted beside the pins under the SAME
    // key DesktopApp used while it owned the state, so the setting survives the move. Absent = expanded.
    private var sidebarCollapsedState by mutableStateOf(store.getString(K_SIDEBAR_COLLAPSED) == "1")
    override val sidebarCollapsed: Boolean get() = sidebarCollapsedState
    override fun setSidebarCollapsed(v: Boolean) {
        sidebarCollapsedState = v
        store.putString(K_SIDEBAR_COLLAPSED, if (v) "1" else "0")
    }

    // menu-bar presence (issue #151) — desktop-only pref, persisted beside the pins. Absent = ON (the
    // environment layer defaults on; only an explicit "0" opts out, so upgrades gain the glyph).
    private var menuBarEnabledState by mutableStateOf(store.getString(K_MENUBAR) != "0")
    override var menuBarEnabled: Boolean
        get() = menuBarEnabledState
        set(v) { menuBarEnabledState = v; store.putString(K_MENUBAR, if (v) "1" else "0") }

    override val phonePush: Boolean? get() = repo.pushPrefs.value
    override fun setPhonePush(enabled: Boolean) { repo.setPushEnabled(enabled) }
    override fun refreshPushPrefs() { repo.fetchPushPrefs() }

    override val approvalNoAutoDeny: Boolean? get() = repo.approvalPrefs.value
    override fun setApprovalNoAutoDeny(enabled: Boolean) { repo.setAskNoAutoDeny(enabled) }
    override fun refreshApprovalPrefs() { repo.fetchApprovalPrefs() }
    override val approvalFullControlExpiryMs: Long? get() = repo.approvalFullControlExpiryMs.value
    override fun setFullControlExpiryMs(ms: Long) { repo.setFullControlExpiryMs(ms) }

    // ── session archive (issue #202): daemon truth, so nothing is cached in a second client store ──
    override val archivedSessions: List<DkSession>
        get() = repo.archivedSessions.map {
            DkSession(
                it.sessionId, it.cwd, it.title, it.agent ?: dev.ccpocket.protocol.AgentKind.CLAUDE,
                running = it.live || it.busy, model = it.model,
            )
        }

    /** Mirrors [canRenameSessions]: the daemon's capability stamp AND not a guest's shared directory. */
    override val canArchiveSessions: Boolean
        get() {
            if (!repo.archiveSupported.value) return false
            val dir = repo.sessionsDir.value ?: return false
            return repo.directories.none { sameDir(it.path, dir) && it.sharedBy != null }
        }

    override fun archiveSession(s: DkSession) {
        focusListedDir(s.cwd) // same rule as renameSession — snapshot the outgoing group before the echo repoints
        repo.setSessionArchived(s.cwd, s.sessionId, archived = true, title = s.title, running = s.running)
    }

    override fun unarchiveSession(s: DkSession) {
        // fromArchiveView: answering with Sessions(cwd) here would repoint the listed directory to
        // whatever project the restored row belonged to
        repo.setSessionArchived(
            s.cwd, s.sessionId, archived = false, fromArchiveView = true,
            title = s.title, running = s.running,
        )
    }

    override fun refreshArchived() { repo.listArchivedSessions() }

    override fun browseArchived() { refreshArchived(); palette = PaletteScope.ARCHIVED }

    override val observing: Boolean get() = repo.observing.value
    override fun takeOver() { repo.takeOver() }

    // stop-refill (#48) applies only this close to the prompt's own send — the CLI-style "oops" beat
    // (grab it back before the run really gets going), not a revise-anytime affordance. A test seam.
    internal var stopRefillWindowMs = 5_000L
    internal var stopRefillElapsedMsForTest: (() -> Long?)? = null

    override fun stopTurn() {
        // hand the interrupted prompt back for editing/resending (#48) — never clobber a typed draft,
        // and only within the quick-regret window of its own send: seconds later a stop means
        // "that's enough", not "let me rephrase", and the long-gone prompt reappearing then reads as
        // the composer typing by itself. Null elapsed = the turn wasn't sent from this app (attached
        // to an already-running session), so there is nothing of the user's to hand back either.
        // The transcript keeps its User bubble: the daemon-side transcript already recorded the turn.
        val elapsed = stopRefillElapsedMsForTest?.invoke() ?: repo.turnElapsedMs()
        if (composer.isBlank() && elapsed != null && elapsed < stopRefillWindowMs) {
            (repo.messages.lastOrNull { it is ChatItem.User } as? ChatItem.User)
                ?.text?.takeIf { it.isNotBlank() }?.let { composer = it }
        }
        repo.cancelTurn()
    }

    // ── scheduled tasks (issue #137): the management list, straight off the repo ──
    override val schedules get() = repo.schedules.toList()
    override val schedulesLoaded get() = repo.schedulesLoaded.value
    override val schedulesStale get() = repo.schedulesUnavailable.value
    override fun refreshSchedules() { repo.fetchSchedules() }
    override fun cancelSchedule(id: String) { repo.cancelSchedule(id) }

    override val authState: dev.ccpocket.protocol.AuthState? get() = repo.authState.value
    override fun refreshAuth() { repo.fetchAuthStatus() }
    override fun switchAccount(force: Boolean) { repo.authLogin(force) }
    override fun stopAuthBlocker(convoId: String) { repo.authStopBlocker(convoId) }
    override fun submitAuthCode(code: String) { repo.authSubmitCode(code) }
    override fun cancelAuthLogin() { repo.authCancelLogin() }
    override fun logoutAccount() { repo.authLogout() }

    override val presetsState: dev.ccpocket.protocol.PresetsState? get() = repo.presetsState.value
    override val presetsRev: Int get() = repo.presetsStateRev.value
    override fun refreshPresets() { repo.fetchPresets() }
    override fun savePreset(id: String?, name: String, baseUrl: String, tokenVar: String, token: String?, model: String?, smallFastModel: String?) {
        repo.savePreset(id, name, baseUrl, tokenVar, token, model, smallFastModel)
    }
    override fun deletePreset(id: String, force: Boolean) { repo.deletePreset(id, force) }
    override fun activatePreset(id: String?, force: Boolean) { repo.activatePreset(id, force) }
    override fun stopPresetBlocker(convoId: String, retryId: String?) { repo.presetStopBlocker(convoId, retryId) }
    override fun stopPresetDeleteBlocker(convoId: String, deleteId: String) { repo.presetStopBlockerForDelete(convoId, deleteId) }

    private fun paired(c: DkComputer) = repo.pairedList.firstOrNull { it.accountId == c.accountId }
    override fun renameComputer(c: DkComputer, label: String?) { paired(c)?.let { repo.renameDaemon(it, label) } }
    override fun removeComputer(c: DkComputer) { paired(c)?.let { repo.unpair(it) } }

    // ── folder-share (issue #115) ──
    override val shares get() = repo.shares.toList()
    override val sharesLoaded get() = repo.sharesLoaded.value
    override val lastShareInvite get() = repo.lastShareCreated.value?.takeUnless { it.ok == false }?.invite
    override fun refreshShares() { repo.listShares() }
    override fun createShare(path: String, tier: dev.ccpocket.protocol.AccessTier, expiresInSec: Long) { repo.createShare(path, tier, expiresInSec) }
    override fun revokeShare(deviceId: String) { repo.revokeShare(deviceId) }
    override fun clearLastShare() { repo.lastShareCreated.value = null }
    override fun redeemShareInvite(blob: String): Boolean {
        val inv = dev.ccpocket.app.pairing.decodeShareInvite(blob) ?: return false
        repo.redeemShareInvite(inv); return true
    }

    private companion object {
        const val K_PINS = "desktop_pins"
        const val K_HIDDEN = "desktop_hidden_sessions" // sessions removed from RECENT via the row ✕ (#62)
        const val K_VISITS = "desktop_recent_visits" // RECENT visit keys (issue #102) — account + path, order = recency
        const val K_GROUP_COLLAPSED = "desktop_group_collapsed" // per project+group collapse memory (issue #119)
        const val K_TERMINAL_APP = "desktop_terminal_app"
        const val K_CHAT_ALIGN = "desktop_chat_alignment" // ChatStreamAlignment name (LEFT/BUBBLES; issue #213)
        const val K_TERMINAL_EMBED = "desktop_terminal_embed" // "1"/absent = embedded default, "0" = external (#153)
        const val K_TERMINAL_HEIGHT = "desktop_terminal_height" // dock height as a ChatPane fraction (#153)
        const val K_MENUBAR = "desktop_menubar_enabled" // menu-bar presence opt-out (issue #151); absent = on
        // the sidebar's hidden-to-the-edge state (issue #62). Unchanged key/values: DesktopApp wrote exactly
        // this before the chrome-v2 redesign lifted the state onto the model, so nobody's sidebar flips back.
        const val K_SIDEBAR_COLLAPSED = "desktop_sidebar_collapsed"
        const val MAX_RECENT = 6 // RECENT groups kept per machine — enough context, never a wall
        const val MAX_NAV_HISTORY = 50 // back/forward entries kept — a within-run trail, oldest dropped first
        const val DRAFT_DEBOUNCE_MS = 400L // composer draft persist debounce — matches the mobile composer (#88)
        const val REFILL_ECHO_TIMEOUT_MS = 4_000L // per-dir wait for the restored-RECENT sweep's listing echo (#102)
        const val REFILL_POLL_MS = 50L // echo poll cadence — snapshot notifications may not be pumping yet (#102)
    }
}
