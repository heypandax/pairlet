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
import dev.ccpocket.app.data.FleetRuntime
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.theme.ThemeMode
import dev.ccpocket.app.ui.fleet.MachineOs
import dev.ccpocket.app.ui.fleet.osFromName
import dev.ccpocket.app.ui.folderName
import dev.ccpocket.app.ui.modelAlias
import dev.ccpocket.app.ui.tilde
import dev.ccpocket.app.ui.trimTrailingSep
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.update.ReleaseClient
import dev.ccpocket.protocol.update.ReleaseVersions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

/**
 * Live [DesktopModel] backed by [PocketRepository] — the real app path. Getters read the repo's snapshot
 * state so reads recompose. Note the repo is single-session: the sidebar's SESSIONS group is the *current
 * project's* sessions (set by [openProject]); a global all-computers multi-session view needs a repo change
 * and is deliberately out of scope here. The tray is likewise still seed-only.
 */
class RepoDesktopModel(private val repo: PocketRepository, scope: CoroutineScope) : DesktopModel {

    override var switcherOpen by mutableStateOf(false)
    override var showNewSession by mutableStateOf(false)
    override var showTray by mutableStateOf(false)
    override var palette by mutableStateOf<PaletteScope?>(null)
    override var showSettings by mutableStateOf(false)
    override var showAddComputer by mutableStateOf(false)
    override var showPermissionModal by mutableStateOf(false)
    override var showAttention by mutableStateOf(false)
    override var showQuickActions by mutableStateOf(false)
    override var showChanges by mutableStateOf(false)
    override var composer by mutableStateOf("")

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
        // and reloading the ≤debounce-stale draft then rolled the live text back mid-IME-composition —
        // the ChatPane mirror parked that stale snapshot and a later commit could land it (#118/#108).
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
    override fun selectChangedFile(path: String) = repo.openChangedFile(path)

    override val connected: Boolean get() = repo.sessionActive.value

    // bindings don't carry an OS on the wire — read it off the user's naming, like the mobile fleet does
    private fun PairedDaemon.dkOs(): DkOs = when (osFromName(displayName())) {
        MachineOs.WIN -> DkOs.WIN
        MachineOs.LINUX -> DkOs.LINUX
        MachineOs.MAC -> DkOs.MAC
    }

    private fun PairedDaemon.toDk(online: Boolean): DkComputer =
        DkComputer(accountId = accountId, name = displayName(), os = dkOs(), online = online, meta = if (online) "online" else "")

    private fun DirectoryEntry.toDkProject() =
        DkProject(path = path, name = name.ifBlank { path }, running = open || busy)

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
        repo.pairedList.firstOrNull { it.accountId == c.accountId }?.let { if (it.accountId != repo.paired.value?.accountId) repo.switchDaemon(it) }
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
                val ask = r.pendingAsk.value ?: return@mapNotNull null
                val d = r.paired.value ?: return@mapNotNull null
                DkAttention(
                    id = ask.askId, accountId = d.accountId, machine = d.displayName(), os = d.dkOs(),
                    tool = ask.tool, preview = ask.diff ?: ask.inputPreview,
                    seconds = null, live = true, // no invented deadline — the inline card carries the real one
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

    override val projects: List<DkProject>
        get() = repo.directories.map { it.toDkProject() }

    private fun openSummary() = repo.sessions.firstOrNull { it.cwd == repo.workdir.value && it.title == repo.chatTitle.value }

    // derived so the many per-row readers (pin rows, RECENT rows, runningVisible) share one mapping
    // per snapshot instead of re-mapping the whole repo list on every read
    private val sessionsDerived = derivedStateOf {
        val askWd = repo.pendingAsk.value?.let { repo.workdir.value }
        val openId = repo.sessionKey.value.takeIf { repo.convoId.value != null }
        val listed = repo.sessions.map {
            DkSession(
                sessionId = it.sessionId, cwd = it.cwd, title = it.title, agent = it.agent ?: AgentKind.CLAUDE,
                // the open chat's row uses the LIVE streaming state — the listed `live` is a snapshot
                // from listing time and kept a finished turn's dot pulsing until a manual refresh
                running = if (it.sessionId == openId) repo.streaming.value || it.busy else it.live || it.busy,
                pending = if (askWd != null && it.cwd == askWd && it.title == repo.chatTitle.value) 1 else 0,
                model = it.model,
            )
        }
        // a just-created session isn't on disk until its first turn persists, so ListSessions can't
        // return it — synthesize its row at the top of its group until a later listing has it (#42)
        // openChatUnlisted() already returns null once the listing contains the session, so no re-check here
        val synth = openChatUnlisted()
        if (synth != null) listOf(synth) + listed else listed
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
    private val visits = mutableStateListOf<Visit>() // most recent first

    /** Canonical identity of a workdir for RECENT-group dedup. Collapses $HOME → ~ (so the daemon's
     *  absolute cwd like /Users/x/P and the new-session popover's tilde reseed ~/P name the SAME project
     *  instead of splitting into two groups — issue #58), unifies separators, drops a trailing one, and
     *  squeezes repeats. [tilde] is structural (matches /Users|/home layouts), so it converges even against
     *  a REMOTE daemon whose $HOME the client can't expand. Comparison-only — never stored, so case is left
     *  intact (a remote FS's case sensitivity is unknown, and the daemon already toRealPath()-canonicalizes). */
    private val repeatSlash = Regex("/{2,}") // compiled once, not per normCwd call

    private fun normCwd(path: String): String =
        tilde(trimTrailingSep(path)).replace('\\', '/').replace(repeatSlash, "/")

    /** Whether two paths name the same project — the RECENT-group dedup identity (issue #58). */
    private fun sameDir(a: String, b: String): Boolean = normCwd(a) == normCwd(b)

    /** Upsert the live list under its dir before [openProject] points the repo somewhere else — this is
     *  also how a dir listed outside openProject (e.g. a restored chat's) enters RECENT. Converges the stored
     *  key to the daemon's ABSOLUTE workdir once the open session resolved it (sessionsDir only echoes the raw,
     *  maybe-tilde request), so a tilde reseed and a later absolute directory entry don't split (issue #58). */
    private fun snapshotCurrent() {
        val acct = repo.paired.value?.accountId ?: return
        val dir = repo.sessionsDir.value ?: return
        val key = repo.workdir.value?.takeIf { repo.convoId.value != null && sameDir(it, dir) } ?: dir
        val i = visits.indexOfFirst { it.accountId == acct && sameDir(it.path, key) }
        if (i >= 0) visits[i] = visits[i].copy(path = key, snapshot = sessions)
        else visits.add(0, Visit(acct, key, sessions))
    }

    override fun openProject(p: DkProject) {
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
        keys.map { v ->
            val current = normLive != null && normCwd(v.path) == normLive
            val rows = (if (current) sessions else v.snapshot)
            DkSessionGroup(
                path = v.path,
                name = folderName(v.path),
                current = current,
                sessions = if (hidden.isEmpty()) rows else rows.filterNot { it.sessionId in hidden },
            )
        }
    }
    override val sessionGroups: List<DkSessionGroup> get() = sessionGroupsDerived.value

    override val sessionsRefreshing: Boolean get() = repo.sessionsRefreshing.value

    override fun refresh(g: DkSessionGroup?) {
        repo.refreshDirectoriesSilently() // manual refresh means "sync the sidebar" — projects/running state rides along
        if (g != null && !g.current) snapshotCurrent() // keep the outgoing live group's rows before repointing
        repo.refreshSessions(g?.path) // null → the current dir; no-op when nothing is listed yet
    }

    override fun selectSession(s: DkSession) {
        focusDir(s.cwd) // clicking a session focuses its project too, so a following ⌘N lands there
        optimisticSelectedId = s.sessionId // light the clicked row NOW, don't wait out the open (#82)
        repo.openSession(wd = s.cwd, resumeId = s.sessionId, title = s.title, agent = s.agent)
    }

    // ── pinned sessions: persisted in the SecureStore beside the pairing list ────────────────────
    private val pinJson = Json { ignoreUnknownKeys = true }
    private val pinsState = mutableStateListOf<DkPin>().apply {
        runCatching {
            SecureStore.getString(K_PINS)?.takeIf { it.isNotBlank() }?.let { s ->
                addAll(pinJson.decodeFromString<List<PinRec>>(s).map { DkPin(it.accountId, it.sessionId, it.cwd, it.title, it.agent) })
            }
        }
    }

    private fun savePins() {
        SecureStore.putString(K_PINS, pinJson.encodeToString(pinsState.map { PinRec(it.accountId, it.sessionId, it.cwd, it.title, it.agent) }))
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

    // ── hidden sessions: the RECENT row's ✕ (issue #62) — a persisted, account-scoped remove-from-list ──
    private val hiddenState = mutableStateListOf<HiddenRec>().apply {
        runCatching {
            SecureStore.getString(K_HIDDEN)?.takeIf { it.isNotBlank() }?.let {
                addAll(pinJson.decodeFromString<List<HiddenRec>>(it))
            }
        }
    }

    private fun saveHidden() = SecureStore.putString(K_HIDDEN, pinJson.encodeToString(hiddenState.toList()))

    override fun hideSession(s: DkSession) {
        val acct = repo.paired.value?.accountId ?: return
        if (hiddenState.none { it.accountId == acct && it.sessionId == s.sessionId }) {
            hiddenState += HiddenRec(acct, s.sessionId, s.cwd)
            saveHidden()
        }
    }

    override fun openPin(p: DkPin) {
        if (p.accountId == repo.paired.value?.accountId) {
            focusDir(p.cwd) // jumping to a pin focuses its project, so a following ⌘N lands there
            optimisticSelectedId = p.sessionId // same as selectSession: light the target row through the open (#82)
            repo.openSession(wd = p.cwd, resumeId = p.sessionId, title = p.title, agent = p.agent)
            return
        }
        optimisticSelectedId = null // another machine's session — nothing in the current list to pre-light
        val target = repo.pairedList.firstOrNull { it.accountId == p.accountId } ?: return
        repo.switchDaemon(target)
        // open once the switched link lands — the repo's push-tap seam (pendingOpen) owns "open when
        // Ready", including abandonment when the user disconnects or switches again meanwhile
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

    override fun newSession(dir: String, agent: AgentKind, mode: PermissionMode) {
        // "~" ships raw, exactly like mobile's NewPathSheet: the daemon owns the expansion
        // (DirectoryService.expandTilde) — only it knows the remote machine's home
        val typed = trimTrailingSep(dir.trim())
        if (typed.isEmpty()) return
        showNewSession = false
        optimisticSelectedId = null // a brand-new session has no listed row yet — don't re-light a stale one (#82)
        // the project enters RECENT (visit + live listing) exactly as if it had been clicked — without
        // this the group never appeared for a dir typed straight into the popover (#42)
        openProject(DkProject(path = typed, name = folderName(typed)))
        repo.openSession(wd = typed, startMode = mode, agent = agent)
    }

    override val hasChat: Boolean get() = repo.convoId.value != null
    override val opening: Boolean get() = repo.opening.value // OpenSession in flight — ChatPane shows a loading transition (#82)
    override val chatTitle: String get() = repo.chatTitle.value ?: "Chat"
    override val chatAgent: AgentKind get() = repo.sessionAgent.value ?: AgentKind.CLAUDE
    override val chatWorkdir: String get() = repo.workdir.value?.let { tilde(it) } ?: ""
    override val chatBranch: String? get() = openSummary()?.gitBranch
    override val chatModel: String get() = modelAlias(repo.model.value)
    override val chatModelId: String get() = repo.model.value ?: ""
    override val chatMode: PermissionMode get() = repo.mode.value
    override val chatEffort: String? get() = repo.effort.value
    override val messages: List<ChatItem> get() = repo.messages
    override val streaming: Boolean get() = repo.streaming.value
    // mirrors mobile's under-bubble cue: link not Ready, or receipts stalled on a Ready-looking link (#78)
    override val sendUndelivered: Boolean get() = repo.phase.value != ConnPhase.Ready || repo.sendStalled.value

    override fun switchMode(m: PermissionMode) = repo.switchMode(m)
    override fun switchModel(name: String) = repo.switchModel(name)
    override fun switchEffort(level: String) = repo.switchEffort(level)
    override fun compactConversation() { repo.sendPrompt("/compact") }
    override fun clearConversation() = repo.clearConversation()

    override fun send(text: String) {
        if (text.isBlank() && !repo.hasReadyImages()) return // an image-only send is legitimate
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

    override val pendingImages: List<dev.ccpocket.app.data.PendingImage> get() = repo.pendingImages
    override fun attachImages(raw: List<ByteArray>) = repo.attachImages(raw)
    override fun removePendingImage(id: Long) = repo.removePendingImage(id)
    override fun hasReadyImages(): Boolean = repo.hasReadyImages()

    override val ask: PermissionAsk? get() = repo.pendingAsk.value
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
    override val updateCommand: String?
        get() = (updateStateInternal as? DkUpdateState.Available)?.let { DesktopUpdater.upgradeCommandFor(it.source) }

    override fun checkForUpdates() {
        if (updateStateInternal is DkUpdateState.Checking || updateStateInternal is DkUpdateState.Downloading) return
        updateStateInternal = DkUpdateState.Checking
        updateScope.launch {
            val rel = DesktopUpdater.latest()
            updateStateInternal = when {
                rel == null -> DkUpdateState.Failed("Couldn't reach GitHub releases — check your network.")
                ReleaseVersions.isNewer(rel.version, APP_VERSION) -> {
                    pendingRelease = rel
                    DkUpdateState.Available(rel.version, DesktopUpdater.currentSource())
                }
                else -> DkUpdateState.UpToDate(APP_VERSION)
            }
        }
    }

    override fun applyUpdate() {
        val rel = pendingRelease ?: return
        // only a standalone install self-overwrites; brew/scoop show a command and unknown opens the page (UI-side)
        if ((updateStateInternal as? DkUpdateState.Available)?.source != DkInstallSource.STANDALONE) return
        updateStateInternal = DkUpdateState.Downloading(rel.version)
        updateScope.launch {
            // applyStandalone() does not return on success — it exits so the swap helper / installer can proceed
            runCatching { DesktopUpdater.applyStandalone(rel) }
                .onFailure { updateStateInternal = DkUpdateState.Failed(it.message ?: "Update failed.") }
        }
    }
    override var defaultAgent: AgentKind
        get() = repo.defaultAgent.value
        set(v) { repo.setDefaultAgent(v) }
    override var defaultMode: PermissionMode
        get() = repo.defaultMode.value
        set(v) { repo.setDefaultMode(v) }
    override var defaultModel: String?
        get() = repo.defaultModel.value
        set(v) { repo.setDefaultModel(v) }
    override var contextWindowOverride: Long?
        get() = repo.contextWindowOverride.value
        set(v) { repo.setContextWindowOverride(v) }
    override var themeMode: ThemeMode
        get() = repo.themeMode.value
        set(v) { repo.setThemeMode(v) }
    // desktop-only pref (the daemon/mobile never open local terminals) — persisted beside the pins
    private var terminalAppState by mutableStateOf(TerminalApp.fromId(SecureStore.getString(K_TERMINAL_APP)))
    override var terminalApp: TerminalApp
        get() = terminalAppState
        set(v) { terminalAppState = v; SecureStore.putString(K_TERMINAL_APP, v.id) }

    override val phonePush: Boolean? get() = repo.pushPrefs.value
    override fun setPhonePush(enabled: Boolean) { repo.setPushEnabled(enabled) }
    override fun refreshPushPrefs() { repo.fetchPushPrefs() }

    override val observing: Boolean get() = repo.observing.value
    override fun takeOver() { repo.takeOver() }

    override fun stopTurn() {
        // hand the interrupted prompt back for editing/resending (#48) — never clobber a typed draft.
        // The transcript keeps its User bubble: the daemon-side transcript already recorded the turn.
        if (composer.isBlank()) {
            (repo.messages.lastOrNull { it is ChatItem.User } as? ChatItem.User)
                ?.text?.takeIf { it.isNotBlank() }?.let { composer = it }
        }
        repo.cancelTurn()
    }

    override val authState: dev.ccpocket.protocol.AuthState? get() = repo.authState.value
    override fun refreshAuth() { repo.fetchAuthStatus() }
    override fun switchAccount(force: Boolean) { repo.authLogin(force) }
    override fun stopAuthBlocker(convoId: String) { repo.authStopBlocker(convoId) }
    override fun submitAuthCode(code: String) { repo.authSubmitCode(code) }
    override fun cancelAuthLogin() { repo.authCancelLogin() }
    override fun logoutAccount() { repo.authLogout() }

    private fun paired(c: DkComputer) = repo.pairedList.firstOrNull { it.accountId == c.accountId }
    override fun renameComputer(c: DkComputer, label: String?) { paired(c)?.let { repo.renameDaemon(it, label) } }
    override fun revokeComputer(c: DkComputer) { paired(c)?.let { repo.unpair(it) } }

    private companion object {
        const val K_PINS = "desktop_pins"
        const val K_HIDDEN = "desktop_hidden_sessions" // sessions removed from RECENT via the row ✕ (#62)
        const val K_TERMINAL_APP = "desktop_terminal_app"
        const val MAX_RECENT = 6 // RECENT groups kept per machine — enough context, never a wall
        const val DRAFT_DEBOUNCE_MS = 400L // composer draft persist debounce — matches the mobile composer (#88)
    }
}
