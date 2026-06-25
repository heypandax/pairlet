package dev.ccpocket.app.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ccpocket.app.APP_VERSION
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.ui.modelAlias
import dev.ccpocket.app.ui.tilde
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode

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
    override var showPalette by mutableStateOf(false)
    override var showSettings by mutableStateOf(false)
    override var showAddComputer by mutableStateOf(false)
    override var showPermissionModal by mutableStateOf(false)
    override var composer by mutableStateOf("")

    override val connected: Boolean get() = repo.sessionActive.value

    private fun PairedDaemon.toDk(online: Boolean): DkComputer =
        DkComputer(accountId = accountId, name = displayName(), os = DkOs.MAC, online = online, meta = if (online) "online" else "")

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

    override val projects: List<DkProject>
        get() = repo.directories.map {
            DkProject(path = it.path, name = it.name.ifBlank { it.path }, running = it.open || it.busy, history = it.hasSessions)
        }

    private fun openSummary() = repo.sessions.firstOrNull { it.cwd == repo.workdir.value && it.title == repo.chatTitle.value }

    override val sessions: List<DkSession>
        get() {
            val askWd = repo.pendingAsk.value?.let { repo.workdir.value }
            return repo.sessions.map {
                DkSession(
                    sessionId = it.sessionId, cwd = it.cwd, title = it.title, agent = it.agent ?: AgentKind.CLAUDE,
                    running = it.live || it.busy,
                    pending = if (askWd != null && it.cwd == askWd && it.title == repo.chatTitle.value) 1 else 0,
                )
            }
        }

    override val selectedSessionId: String? get() = openSummary()?.sessionId

    override fun openProject(p: DkProject) { repo.listSessions(p.path) }

    override fun selectSession(s: DkSession) {
        repo.openSession(wd = s.cwd, resumeId = s.sessionId, title = s.title, agent = s.agent)
    }

    override fun newSession(agent: AgentKind, mode: PermissionMode) {
        showNewSession = false
        repo.sessionsDir.value?.let { repo.openSession(wd = it, startMode = mode, agent = agent) }
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
        if (text.isBlank()) return
        repo.sendPrompt(text)
        composer = ""
    }

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
}
