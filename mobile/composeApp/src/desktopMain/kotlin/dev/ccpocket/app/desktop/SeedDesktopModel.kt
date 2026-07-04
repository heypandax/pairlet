package dev.ccpocket.app.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
    override val sessions = listOf(
        DkSession("s1", "~/code/cc-pocket", "Refactor auth module", running = true, model = "claude-sonnet-5-20250929"),
        DkSession("s2", "~/code/cc-pocket", "Fix stream parser test", running = true, model = "claude-opus-4-8"),
        DkSession("s3", "~/code/cc-pocket", "Tidy CI workflow", AgentKind.CODEX, pending = 1), // keeps the Codex diff-approval surface exercised
    )

    // RECENT: the live project plus a previously visited one — exercises the grouped sidebar zone
    override val sessionGroups = listOf(
        DkSessionGroup("~/code/cc-pocket", "cc-pocket", current = true, sessions = sessions),
        DkSessionGroup(
            "~/code/relay", "relay", current = false,
            sessions = listOf(
                DkSession("s4", "~/code/relay", "Bump maxFrame to 4MB", model = "claude-sonnet-5-20250929"),
                DkSession("s5", "~/code/relay", "Rate-limit pairing", model = "claude-opus-4-8"),
            ),
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

    override val appVersion = "1.2.0"
    override val relayUrl = "wss://pocket.ark-nexus.cc"
    override var defaultAgent by mutableStateOf(AgentKind.CLAUDE)
    override var defaultMode by mutableStateOf(PermissionMode.DEFAULT)
    override fun renameComputer(c: DkComputer, label: String?) {}
    override fun revokeComputer(c: DkComputer) {}
    override var composer by mutableStateOf("")

    // same canned set the mobile demo uses — keeps the slash menu renderable without a daemon
    override val slashCommands: List<dev.ccpocket.protocol.SlashCommand> = dev.ccpocket.app.data.DemoData.commands()

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
