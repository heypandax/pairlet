package dev.ccpocket.app.desktop

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ccpocket.app.APP_VERSION
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.data.FleetRuntime
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.secure.SecureStore
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

/**
 * Live [DesktopModel] backed by [PocketRepository] — the real app path. Getters read the repo's snapshot
 * state so reads recompose. Note the repo is single-session: the sidebar's SESSIONS group is the *current
 * project's* sessions (set by [openProject]); a global all-computers multi-session view needs a repo change
 * and is deliberately out of scope here. The tray is likewise still seed-only.
 */
class RepoDesktopModel(private val repo: PocketRepository) : DesktopModel {

    override var switcherOpen by mutableStateOf(false)
    override var showNewSession by mutableStateOf(false)
    override var showTray by mutableStateOf(false)
    override var palette by mutableStateOf<PaletteScope?>(null)
    override var showSettings by mutableStateOf(false)
    override var showAddComputer by mutableStateOf(false)
    override var showPermissionModal by mutableStateOf(false)
    override var showAttention by mutableStateOf(false)
    override var composer by mutableStateOf("")

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
        FleetRuntime.forPrimary(repo)?.focusProject(m.computer.accountId, p.path) ?: super.openRunning(m, p)
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
        repo.sessions.map {
            DkSession(
                sessionId = it.sessionId, cwd = it.cwd, title = it.title, agent = it.agent ?: AgentKind.CLAUDE,
                running = it.live || it.busy,
                pending = if (askWd != null && it.cwd == askWd && it.title == repo.chatTitle.value) 1 else 0,
                model = it.model,
            )
        }
    }
    override val sessions: List<DkSession> get() = sessionsDerived.value

    override val selectedSessionId: String? get() = openSummary()?.sessionId

    // ── RECENT groups: session lists cached per visited project (per account) ─────────────────────
    // The protocol only lists sessions per directory (ListSessions), so cross-project RECENT is built
    // client-side: each visit carries a snapshot of its list, and the current dir always reads live.
    private data class Visit(val accountId: String, val path: String, val snapshot: List<DkSession> = emptyList())
    private val visits = mutableStateListOf<Visit>() // most recent first

    /** Upsert the live list under its dir before [openProject] points the repo somewhere else — this is
     *  also how a dir listed outside openProject (e.g. a restored chat's) enters RECENT. */
    private fun snapshotCurrent() {
        val acct = repo.paired.value?.accountId ?: return
        val dir = repo.sessionsDir.value ?: return
        val i = visits.indexOfFirst { it.accountId == acct && it.path == dir }
        if (i >= 0) visits[i] = visits[i].copy(snapshot = sessions)
        else visits.add(0, Visit(acct, dir, sessions))
    }

    override fun openProject(p: DkProject) {
        val acct = repo.paired.value?.accountId
        if (acct != null) {
            snapshotCurrent()
            val i = visits.indexOfFirst { it.accountId == acct && it.path == p.path }
            val v = if (i >= 0) visits.removeAt(i) else Visit(acct, p.path)
            visits.add(0, v)
            visits.filter { it.accountId == acct }.drop(MAX_RECENT).forEach { visits.remove(it) }
        }
        repo.listSessions(p.path)
    }

    private val sessionGroupsDerived = derivedStateOf {
        val acct = repo.paired.value?.accountId ?: return@derivedStateOf emptyList()
        val liveDir = repo.sessionsDir.value
        val keys = visits.filter { it.accountId == acct }.toMutableList()
        // a list opened outside openProject shows before its first snapshotCurrent lands it in visits
        if (liveDir != null && keys.none { it.path == liveDir }) keys.add(0, Visit(acct, liveDir))
        keys.map { v ->
            val current = v.path == liveDir
            DkSessionGroup(
                path = v.path,
                name = folderName(v.path),
                current = current,
                sessions = if (current) sessions else v.snapshot,
            )
        }
    }
    override val sessionGroups: List<DkSessionGroup> get() = sessionGroupsDerived.value

    override fun selectSession(s: DkSession) {
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

    override fun openPin(p: DkPin) {
        if (p.accountId == repo.paired.value?.accountId) {
            repo.openSession(wd = p.cwd, resumeId = p.sessionId, title = p.title, agent = p.agent)
            return
        }
        val target = repo.pairedList.firstOrNull { it.accountId == p.accountId } ?: return
        repo.switchDaemon(target)
        // open once the switched link lands — the repo's push-tap seam (pendingOpen) owns "open when
        // Ready", including abandonment when the user disconnects or switches again meanwhile
        repo.requestOpenSession(p.cwd, p.sessionId, title = p.title, agent = p.agent)
    }

    // the open project's dir, else wherever the current chat lives — so ⌘N works before any project click
    override val newSessionDir: String? get() = repo.sessionsDir.value ?: repo.workdir.value
    override var newSessionSeed: String? by mutableStateOf(null)

    override fun newSession(dir: String, agent: AgentKind, mode: PermissionMode) {
        // "~" ships raw, exactly like mobile's NewPathSheet: the daemon owns the expansion
        // (DirectoryService.expandTilde) — only it knows the remote machine's home
        val typed = trimTrailingSep(dir.trim())
        if (typed.isEmpty()) return
        showNewSession = false
        repo.openSession(wd = typed, startMode = mode, agent = agent)
    }

    override val hasChat: Boolean get() = repo.convoId.value != null
    override val chatTitle: String get() = repo.chatTitle.value ?: "Chat"
    override val chatAgent: AgentKind get() = repo.sessionAgent.value ?: AgentKind.CLAUDE
    override val chatWorkdir: String get() = repo.workdir.value?.let { tilde(it) } ?: ""
    override val chatBranch: String? get() = openSummary()?.gitBranch
    override val chatModel: String get() = modelAlias(repo.model.value)
    override val chatMode: PermissionMode get() = repo.mode.value
    override val messages: List<ChatItem> get() = repo.messages
    override val streaming: Boolean get() = repo.streaming.value

    override fun send(text: String) {
        if (text.isBlank() && !repo.hasReadyImages()) return // an image-only send is legitimate
        repo.sendPrompt(text)
        composer = ""
    }

    override val slashCommands: List<dev.ccpocket.protocol.SlashCommand> get() = repo.slashCommands

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

    override val appVersion: String get() = APP_VERSION
    override val relayUrl: String get() = repo.paired.value?.relay ?: ""
    override var defaultAgent: AgentKind
        get() = repo.defaultAgent.value
        set(v) { repo.setDefaultAgent(v) }
    override var defaultMode: PermissionMode
        get() = repo.defaultMode.value
        set(v) { repo.setDefaultMode(v) }

    private fun paired(c: DkComputer) = repo.pairedList.firstOrNull { it.accountId == c.accountId }
    override fun renameComputer(c: DkComputer, label: String?) { paired(c)?.let { repo.renameDaemon(it, label) } }
    override fun revokeComputer(c: DkComputer) { paired(c)?.let { repo.unpair(it) } }

    private companion object {
        const val K_PINS = "desktop_pins"
        const val MAX_RECENT = 6 // RECENT groups kept per machine — enough context, never a wall
    }
}
