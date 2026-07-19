package dev.ccpocket.app.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.theme.ThemeMode
import dev.ccpocket.app.ui.ComposerState
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode

/**
 * Static [DesktopModel] used by the screenshot generator and UI tests — no daemon, deterministic. Renders the
 * exact same surfaces as the live app via [RepoDesktopModel], just fed canned data.
 */
class SeedDesktopModel : DesktopModel {
    override val connected = true
    override val computers = listOf(
        DkComputer("acct-mbp", "Lidapeng-MacBook", DkOs.MAC, online = true, meta = "online · active now"),
        DkComputer("acct-studio", "mac-studio", DkOs.MAC, online = true, meta = "online · 2m ago"),
        DkComputer("acct-linux", "devbox-linux", DkOs.LINUX, online = true, meta = "online · just now"),
        DkComputer("acct-win", "win-desktop", DkOs.WIN, online = false, meta = "offline · 2d ago"),
    )
    override var activeComputer: DkComputer? by mutableStateOf(computers.first())
        private set

    override val projects = listOf(
        DkProject("~/code/cc-pocket", "cc-pocket", running = true),
        DkProject("~/code/relay", "relay"),
        DkProject("~/dotfiles", "dotfiles"),
    )
    // the current project's rows, with issue-#119 custom-group membership: two under named groups + one
    // ungrouped, so the grouped sidebar zone renders every kind of section. Group membership is overlaid
    // by [groupOverride] so the seed's assignGroup() moves are observable.
    private val baseSessions = listOf(
        DkSession("s1", "~/code/cc-pocket", "Refactor auth module", running = true, model = "claude-sonnet-5-20250929", group = "g-auth"),
        DkSession("s2", "~/code/cc-pocket", "Fix stream parser test", running = true, model = "claude-opus-4-8", group = null),
        DkSession("s3", "~/code/cc-pocket", "Tidy CI workflow", AgentKind.CODEX, pending = 1, group = "g-ci"), // keeps the Codex diff-approval surface exercised
        // ungrouped on purpose: parked in a group it would push the shared "acme-api" section below
        // the test viewport (sharedGroupShowsProvenancePillAndExpiry) — here it exercises the
        // OpenCode badge/row surfaces without reshaping the grouped sections above it
        DkSession("s4", "~/code/cc-pocket", "Update docs", AgentKind.OPENCODE),
    )
    private val groupOverride = mutableStateMapOf<String, String?>()
    private val titleOverride = mutableStateMapOf<String, String>() // session rename (issue #158)
    override val sessions: List<DkSession>
        get() = baseSessions.map { s ->
            val grouped = if (groupOverride.containsKey(s.sessionId)) s.copy(group = groupOverride[s.sessionId]) else s
            titleOverride[s.sessionId]?.let { grouped.copy(title = it) } ?: grouped
        }

    // custom groups of the current project (issue #119) — mutable so create/rename/delete are observable
    private val groupList = mutableStateListOf(DkGroup("g-auth", "Auth work", 0), DkGroup("g-ci", "CI & release", 1))
    override val customGroups: List<DkGroup> get() = groupList.sortedBy { it.order }
    override val canEditGroups = true // the seed's current project (cc-pocket) is owner-editable
    override fun createGroup(name: String) { groupList.add(DkGroup("g-${groupList.size + 1}", name.trim(), groupList.size)) }
    override fun renameGroup(groupId: String, name: String) {
        val i = groupList.indexOfFirst { it.id == groupId }
        if (i >= 0) groupList[i] = groupList[i].copy(name = name.trim())
    }
    override fun deleteGroup(groupId: String) {
        groupList.removeAll { it.id == groupId }
        baseSessions.filter { (groupOverride[it.sessionId] ?: it.group) == groupId }.forEach { groupOverride[it.sessionId] = null }
    }
    override fun assignGroup(sessionId: String, groupId: String?) { groupOverride[sessionId] = groupId }
    // session rename (issue #158) — local override so the seed exercises the sidebar entry
    override val canRenameSessions = true
    override fun renameSession(sessionId: String, title: String) { titleOverride[sessionId] = title.trim() }
    private val groupCollapse = mutableStateListOf<String>()
    override fun groupCollapsed(projectPath: String, groupId: String) = "$projectPath\u0000$groupId" in groupCollapse
    override fun setGroupCollapsed(projectPath: String, groupId: String, collapsed: Boolean) {
        val k = "$projectPath\u0000$groupId"
        if (collapsed) { if (k !in groupCollapse) groupCollapse.add(k) } else groupCollapse.remove(k)
    }

    // RECENT: the live project plus a previously visited one — exercises the grouped sidebar zone —
    // plus a guest's shared folder (issue #115), so the "Shared" pill surface stays exercised too
    override val sessionGroups: List<DkSessionGroup>
        get() = listOf(
            DkSessionGroup("~/code/cc-pocket", "cc-pocket", current = true, sessions = sessions),
            DkSessionGroup(
                "~/code/relay", "relay", current = false,
                sessions = listOf(
                    DkSession("s4", "~/code/relay", "Bump maxFrame to 4MB", model = "claude-sonnet-5-20250929"),
                    DkSession("s5", "~/code/relay", "Rate-limit pairing", model = "claude-opus-4-8"),
                ),
            ),
            DkSessionGroup(
                "~/work/acme-api", "acme-api", current = false,
                sessions = listOf(DkSession("s6", "~/work/acme-api", "Add rate-limit middleware", model = "claude-sonnet-5-20250929")),
                sharedBy = "panda-mbp",
                // 6 days + slack from "now" so the header's live countdown renders a stable "6d left"
                shareExpiresAt = dev.ccpocket.app.epochMillis() + (6 * 24 + 2) * 3_600_000L,
            ),
        )

    // the design's three pins: one local + running, one on devbox-linux, one Codex on mac-studio
    private val pinList = mutableStateListOf(
        DkPin("acct-mbp", "s1", "~/code/cc-pocket", "Refactor auth module"),
        DkPin("acct-linux", "m3s1", "~/src/relay", "Run integration tests"),
        DkPin("acct-studio", "m2s2", "~/work/api-server", "Port parser to Rust", AgentKind.CODEX),
    )
    override val pins: List<DkPin> get() = pinList
    override fun pin(s: DkSession) {
        if (pinList.size >= DesktopModel.MAX_PINS || pinList.any { it.sessionId == s.sessionId }) return
        pinList += DkPin(activeComputer?.accountId ?: "acct-mbp", s.sessionId, s.cwd, s.title, s.agent)
    }
    override fun unpin(p: DkPin) { pinList.removeAll { it.sessionId == p.sessionId } }
    override fun movePin(from: Int, to: Int) {
        if (from in pinList.indices && to in pinList.indices && from != to) pinList.add(to, pinList.removeAt(from))
    }
    override fun openPin(p: DkPin) {
        if (p.accountId == activeComputer?.accountId) sessions.firstOrNull { it.sessionId == p.sessionId }?.let(::selectSession)
        else computers.firstOrNull { it.accountId == p.accountId }?.let(::selectComputer)
    }

    // the fleet boards' other machines — the design's four-machine command-center scenario
    private val resolvedAttention = mutableStateListOf<String>()
    private val allAttention = listOf(
        DkAttention("ask-1", "acct-studio", "mac-studio", DkOs.MAC, "Bash", "rm -rf ./build && ./gradlew clean", seconds = 23, live = false),
        DkAttention("ask-2", "acct-linux", "devbox-linux", DkOs.LINUX, "Write", "Relay.kt  +42 −7", seconds = 41, live = false),
    )
    override val attention: List<DkAttention> get() = allAttention.filterNot { it.id in resolvedAttention }
    override fun resolveAttention(a: DkAttention, allow: Boolean) { if (a.id !in resolvedAttention) resolvedAttention.add(a.id) }

    override val machines: List<DkMachine>
        get() = listOf(
            DkMachine(computers[0], active = activeComputer == computers[0], thisMachine = true, projects = projects),
            DkMachine(
                computers[1], active = activeComputer == computers[1],
                pending = attention.count { it.accountId == "acct-studio" },
                projects = listOf(DkProject("~/work/api-server", "api-server", running = true)),
            ),
            DkMachine(
                computers[2], active = activeComputer == computers[2],
                pending = attention.count { it.accountId == "acct-linux" },
                projects = listOf(DkProject("~/src/relay", "relay", running = true)),
            ),
            DkMachine(computers[3], active = activeComputer == computers[3]),
        )

    override val watch: DkWatch?
        get() = DkWatch(
            machine = "devbox-linux", os = DkOs.LINUX, title = "Run integration tests", mode = "acceptEdits",
            output = """
                $ pytest -x tests/integration
                ============ test session starts ============
                platform linux · python 3.12.1
                collected 48 items

                tests/integration/test_relay.py ......   [ 12%]
                tests/integration/test_ws.py ........    [ 29%]
                tests/integration/test_pairing.py ....   [ 37%]
                tests/integration/test_e2e.py ....F

                FAILED test_e2e.py::test_reconnect_backoff
                  socket closed before backoff timer fired
                  retrying with --lf
            """.trimIndent(),
            waiting = attention.firstOrNull { it.id == "ask-2" },
        )

    private var selectedIndex by mutableStateOf(0)
    private var askResolved by mutableStateOf(false)
    override val selectedSessionId: String get() = sessions[selectedIndex].sessionId
    private val selected: DkSession get() = sessions[selectedIndex]

    override var switcherOpen by mutableStateOf(false)
    override var showNewSession by mutableStateOf(false)
    override var showTray by mutableStateOf(false)
    override var palette by mutableStateOf<PaletteScope?>(null)
    override var showSettings by mutableStateOf(false)
    override var showAddComputer by mutableStateOf(false)
    override var showPermissionModal by mutableStateOf(false)
    override var showAttention by mutableStateOf(false)
    override var showQuickActions by mutableStateOf(false)
    override var showModelPopover by mutableStateOf(false)
    override var showChanges by mutableStateOf(false)
    override var showSkills by mutableStateOf(false)

    override val appVersion = "1.4.0"
    override val relayUrl = "wss://pocket.ark-nexus.cc"
    override var defaultAgent by mutableStateOf(AgentKind.CLAUDE)
    override var defaultMode by mutableStateOf(PermissionMode.DEFAULT)
    override var defaultModel: String? by mutableStateOf(null)
    override var contextWindowOverride: Long? by mutableStateOf(null)
    override var terminalApp by mutableStateOf(TerminalApp.SYSTEM)
    override var terminalDefaultEmbedded by mutableStateOf(true) // issue #153; no engine factory → chrome only
    override val terminalPanel = TerminalPanelController()
    override var menuBarEnabled by mutableStateOf(true)
    override var themeMode by mutableStateOf(ThemeMode.DARK)
    private var phonePushState by mutableStateOf(true)
    override val phonePush: Boolean? get() = phonePushState
    override fun setPhonePush(enabled: Boolean) { phonePushState = enabled }
    override fun renameComputer(c: DkComputer, label: String?) {}
    override fun revokeComputer(c: DkComputer) {}
    override val composerState = ComposerState()

    // account + API presets (issue #113): canned masked state, exactly the shape a daemon replies with —
    // demos the whole Account pane (design 1a/1c) and lets the UI tests drive activate/save/delete for real
    override val authState = dev.ccpocket.protocol.AuthState(
        loggedIn = true, email = "jordan@example.com", orgName = "Acme Robotics",
        subscriptionType = "max", authMethod = "claude.ai",
    )
    private var presetsSeed by mutableStateOf<dev.ccpocket.protocol.PresetsState?>(
        dev.ccpocket.protocol.PresetsState(
            presets = listOf(
                dev.ccpocket.protocol.PresetSummary(
                    "pr-1", "Work proxy", "https://api.example-proxy.com/v1",
                    dev.ccpocket.protocol.PresetEnv.AUTH_TOKEN, "sk-…••••3f9a", model = "gpt-4o", smallFastModel = "gpt-4o-mini",
                ),
                dev.ccpocket.protocol.PresetSummary(
                    "pr-2", "Personal key", "https://api.anthropic.com",
                    dev.ccpocket.protocol.PresetEnv.API_KEY, "sk-…••••a71c",
                ),
                dev.ccpocket.protocol.PresetSummary(
                    "pr-3", "Local llama", "http://localhost:11434",
                    dev.ccpocket.protocol.PresetEnv.API_KEY, "••••",
                ),
            ),
            activeId = "pr-1",
        ),
    )
    override val presetsState: dev.ccpocket.protocol.PresetsState? get() = presetsSeed
    private var presetsRevSeed by mutableStateOf(0)
    override val presetsRev: Int get() = presetsRevSeed

    /** Test hook: null = simulate a daemon that predates pocket/presets.* (request silently dropped). */
    fun seedPresets(v: dev.ccpocket.protocol.PresetsState?) { presetsSeed = v }

    override fun activatePreset(id: String?, force: Boolean) {
        presetsSeed = presetsSeed?.copy(activeId = id, error = null, blockers = emptyList())
        presetsRevSeed++
    }

    override fun deletePreset(id: String, force: Boolean) {
        presetsSeed = presetsSeed?.let { s ->
            s.copy(presets = s.presets.filterNot { it.id == id }, activeId = s.activeId.takeIf { it != id })
        }
        presetsRevSeed++
    }

    override fun savePreset(id: String?, name: String, baseUrl: String, tokenVar: String, token: String?, model: String?, smallFastModel: String?) {
        val mask = token?.let { if (it.length >= 16) "${it.take(3)}…••••${it.takeLast(4)}" else "••••" }
        presetsSeed = presetsSeed?.let { s ->
            if (id == null) {
                val p = dev.ccpocket.protocol.PresetSummary("pr-${s.presets.size + 1}", name, baseUrl, tokenVar, mask ?: "••••", model, smallFastModel)
                s.copy(presets = s.presets + p, error = null)
            } else s.copy(
                presets = s.presets.map {
                    if (it.id == id) it.copy(name = name, baseUrl = baseUrl, tokenVar = tokenVar, tokenMask = mask ?: it.tokenMask, model = model, smallFastModel = smallFastModel) else it
                },
                error = null,
            )
        }
        presetsRevSeed++
    }

    // same canned set the mobile demo uses — keeps the slash menu renderable without a daemon
    override val slashCommands: List<dev.ccpocket.protocol.SlashCommand> = dev.ccpocket.app.data.DemoData.commands()

    override val hasChat = true
    override val chatTitle: String get() = selected.title
    override val chatAgent: AgentKind get() = selected.agent
    override val chatWorkdir = "~/code/cc-pocket"
    override val chatBranch = "main"
    override val chatModel: String get() = when (selected.agent) {
        AgentKind.CODEX -> "gpt-5.1-codex"
        AgentKind.OPENCODE -> "auto"
        else -> "sonnet"
    }
    override val chatMode = PermissionMode.DEFAULT
    override val streaming = true
    override val messages: List<ChatItem> = listOf(
        ChatItem.User("the websocket reconnect dies after the 3rd retry — can you find why and add a regression test?"),
        ChatItem.Assistant(
            "The reconnect loop is scheduled on the socket's own `CoroutineScope`, which is cancelled the moment the " +
                "socket closes — so the backoff timer never fires. Moving it to an app-level scope fixes it:\n\n" +
                "```kotlin\nfun reconnect() {\n  appScope.launch {\n    delay(backoff)\n    open()\n  }\n}\n```",
        ),
        ChatItem.Tool("Bash", "gradle :relay:test"),
        ChatItem.Assistant("Tests pass. The reconnect now survives socket-scope cancellation and backs off correctly."),
    )

    private val sampleAsk: PermissionAsk
        get() = if (selected.agent == AgentKind.CODEX) {
            PermissionAsk(
                convoId = "demo", askId = "ask-diff", tool = "Edit", inputPreview = "src/relay/WsClient.kt",
                title = "Edit files",
                diff = " fun reconnect() {\n-  scope.launch { open() }\n+  appScope.launch {\n+    delay(backoff)\n+    open()\n+  }\n }",
            )
        } else {
            PermissionAsk(
                convoId = "demo", askId = "ask-cmd", tool = "Bash", inputPreview = "rm -rf ./build && ./gradlew clean",
                title = "Run command",
            )
        }
    override val ask: PermissionAsk? get() = sampleAsk.takeIf { selected.pending > 0 && !askResolved }

    override fun selectComputer(c: DkComputer) { activeComputer = c; switcherOpen = false }
    override fun addComputer() {}
    override fun openProject(p: DkProject) {}
    override fun selectSession(s: DkSession) { sessions.indexOfFirst { it.sessionId == s.sessionId }.takeIf { it >= 0 }?.let { selectedIndex = it; askResolved = false } }
    override val newSessionDir = "~/code/cc-pocket"
    override var newSessionSeed: String? by mutableStateOf(null)
    override fun newSession(dir: String, agent: AgentKind, mode: PermissionMode) { showNewSession = false }
    override fun send(text: String) { composer = "" }

    override val pendingImages: List<dev.ccpocket.app.data.PendingImage> = emptyList()
    override fun attachImages(raw: List<ByteArray>) {}
    override fun removePendingImage(id: Long) {}
    override fun hasReadyImages(): Boolean = false
    override fun resolve(allow: Boolean, remember: Boolean) { askResolved = true; showPermissionModal = false }
    override fun dismissAsk() { askResolved = true; showPermissionModal = false }
}
