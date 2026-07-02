package dev.ccpocket.app.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ccpocket.app.data.ChatItem
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
        DkComputer("acct-mac", "Lidapeng-MBP", DkOs.MAC, online = true, meta = "online · active now"),
        DkComputer("acct-linux", "devbox-linux", DkOs.LINUX, online = true, meta = "online · 3m ago"),
        DkComputer("acct-win", "win-desktop", DkOs.WIN, online = false, meta = "offline · 2d ago"),
    )
    override var activeComputer: DkComputer? by mutableStateOf(computers.first())
        private set

    override val projects = listOf(
        DkProject("~/code/cc-pocket", "cc-pocket", running = true),
        DkProject("~/code/relay", "relay", history = true),
        DkProject("~/dotfiles", "dotfiles"),
    )
    override val sessions = listOf(
        DkSession("s1", "~/code/cc-pocket", "Fix relay reconnect", running = true),
        DkSession("s2", "~/code/cc-pocket", "Port parser to Rust", AgentKind.CODEX, running = true, pending = 1),
        DkSession("s3", "~/code/cc-pocket", "Add WS reconnect test", running = true),
        DkSession("s4", "~/code/cc-pocket", "Refactor auth module"),
        DkSession("s5", "~/code/cc-pocket", "Tidy CI workflow", AgentKind.CODEX),
    )

    private var selectedIndex by mutableStateOf(0)
    private var askResolved by mutableStateOf(false)
    override val selectedSessionId: String get() = sessions[selectedIndex].sessionId
    private val selected: DkSession get() = sessions[selectedIndex]

    override var switcherOpen by mutableStateOf(false)
    override var showNewSession by mutableStateOf(false)
    override var showTray by mutableStateOf(false)
    override var showPalette by mutableStateOf(false)
    override var showSettings by mutableStateOf(false)
    override var showAddComputer by mutableStateOf(false)
    override var showPermissionModal by mutableStateOf(false)

    override val appVersion = "1.2.0"
    override val relayUrl = "wss://pocket.ark-nexus.cc"
    override var defaultAgent by mutableStateOf(AgentKind.CLAUDE)
    override var defaultMode by mutableStateOf(PermissionMode.DEFAULT)
    override fun renameComputer(c: DkComputer, label: String?) {}
    override fun revokeComputer(c: DkComputer) {}
    override var composer by mutableStateOf("")

    override val hasChat = true
    override val chatTitle: String get() = selected.title
    override val chatAgent: AgentKind get() = selected.agent
    override val chatWorkdir = "~/code/cc-pocket"
    override val chatBranch = "main"
    override val chatModel: String get() = if (selected.agent == AgentKind.CODEX) "gpt-5.1-codex" else "sonnet"
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
    override fun newSession(agent: AgentKind, mode: PermissionMode) { showNewSession = false }
    override fun send(text: String) { composer = "" }
    override fun resolve(allow: Boolean, remember: Boolean) { askResolved = true; showPermissionModal = false }
    override fun dismissAsk() { askResolved = true; showPermissionModal = false }
}
