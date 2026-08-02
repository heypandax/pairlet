package dev.ccpocket.daemon

import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.disk.SpawnedSessions
import dev.ccpocket.daemon.conversation.KeyedSink
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.claude.ClaudeModelService
import dev.ccpocket.daemon.codex.CodexModelService
import dev.ccpocket.daemon.opencode.OpenCodeModelService
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.schedule.ScheduleExecutor
import dev.ccpocket.daemon.schedule.ScheduleStore
import dev.ccpocket.daemon.schedule.SchedulerService
import dev.ccpocket.daemon.server.RequestRouter
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SendPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The transport-agnostic core: registry + services + router. Shared by the local server and the relay client.
 *  [backends] maps each agent kind to a factory that builds a fresh per-conversation driver.
 *  [claudeConfigDir] non-null = credential isolation (issue #69): auth commands (and the claude
 *  backends, wired by the caller) operate on the daemon's own CLAUDE_CONFIG_DIR.
 *  [presetStore] holds the API presets (issue #113); the caller shares the SAME instance with its
 *  claude backend factory so activation and session-launch injection can't diverge. */
class DaemonCore(
    backends: Map<AgentKind, AgentBackendFactory>,
    val prefs: DaemonPrefs = DaemonPrefs.load(),
    claudeConfigDir: java.nio.file.Path? = null,
    /** The owner's --claude-bin override — must reach every auxiliary claude process (see [claudeRuntime]). */
    claudeBin: String? = null,
    presetStore: PresetStore = PresetStore.load(),
    scheduleStore: ScheduleStore = ScheduleStore.load(),
    openCodeModels: OpenCodeModelService = OpenCodeModelService(),
    codexModels: CodexModelService = CodexModelService(),
    /** Session Handoff (SESSION-HANDOFF.md): registry + guard + fan-out, shared by both transports.
     *  Installed onto [SessionRegistry.handoffs] below so the router's drive gate, the §4.1 create
     *  checks, the graceful-recall turn control and the idle-reaper protection all read one truth.
     *  Injectable so a test can hand in a temp-store instance instead of the real ~/.cc-pocket one. */
    val handoffs: dev.ccpocket.daemon.handoff.HandoffService = dev.ccpocket.daemon.handoff.HandoffService(),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The shared claude launch context (binary override + credential store + preset env) for auxiliary
     *  claude processes — e.g. the Feishu Guardian Reviewer (reviewed-trust §21.2): a helper that resolved
     *  its own binary or inherited raw env would diverge from the main backend on all three. */
    val claudeRuntime = dev.ccpocket.daemon.claude.ClaudeRuntime(claudeBin, claudeConfigDir) {
        runCatching { presetStore.activeEnv() }.getOrNull()
    }

    /** ONE pending-approval ledger for the whole daemon (approval design M1): agent tool asks, bridge
     *  request approvals, quick-shell commands and file exports all register here, so a verdict routes by
     *  askId in one place and timeout/withdraw/snapshot semantics can't drift between the gates. */
    val approvalHistory = dev.ccpocket.daemon.approval.ApprovalHistoryStore.load()
    val approvals = dev.ccpocket.daemon.approval.ApprovalCoordinator(scope, history = approvalHistory)

    /** ONE task-grant engine for the whole daemon (approval design M2): the agent's Bash tool and the
     *  quick terminal share it, so "允许本任务" from either surface covers both. */
    val grants = dev.ccpocket.daemon.approval.ApprovalGrantStore()
    val registry = SessionRegistry(scope, backends, approvals = approvals, grants = grants)

    init {
        registry.handoffs = handoffs
        // issue #201: mirror the persisted "wait for my decision" preference into the per-ask read. Done
        // here (not lazily in ApprovalTimeout) so the object never has to know about DaemonPrefs — the
        // router writes the same pair whenever a client flips it.
        dev.ccpocket.daemon.agent.ApprovalTimeout.noAutoDeny = prefs.askNoAutoDeny
        // unhide transcripts a crashed previous instance stranded hidden (issue #70) — off the
        // constructor path (file IO over up to 200 journal entries must not delay startup)
        scope.launch(Dispatchers.IO) { runCatching { SpawnedSessions.sweepAtBoot() } }
        // periodic handoff expiry sweep + HandoffUpdated fan-out — on the core scope like the schedule
        // pump below, so BOTH transports (relay client + local server) get it for free
        scope.launch { handoffs.sweepLoop() }
    }

    val dirs = DirectoryService()
    val transcribe = TranscribeService(scope, registry::workdirOf)
    val inbox = FileInboxService(registry::workdirOf)
    val shell = ShellService(scope, coordinator = approvals, grants = grants)
    val exports = FileExportService(scope, registry::workdirOf, coordinator = approvals)
    val auth = AuthService(
        scope, registry::busyForAuth, registry::closeIdleForAuth, registry::closeBusyForAuth,
        claudeConfigDir = claudeConfigDir,
    )
    // same switch suppliers as auth: activating a preset swaps what new sessions run on, so the same
    // mid-task guard + idle auto-close semantics apply (issue #113)
    val presets = PresetService(presetStore, registry::busyForAuth, registry::closeIdleForAuth, registry::closeBusyForAuth)

    /**
     * Scheduled tasks (issue #137). The executor reuses the EXACT interactive session paths — no
     * parallel channel: [SessionRegistry.open] (reattaches a live conversation / lazy-spawns a cold
     * one) then [SessionRegistry.sendPrompt] (which queues into a running turn exactly like a mid-turn
     * phone send). The sink is a keyed no-op: nobody is watching the fire itself — a phone that opens
     * the session later replays the transcript, and the turn's completion/error rides the normal
     * [dev.ccpocket.daemon.conversation.PushHook] push closure. A session live in an OUTSIDE terminal
     * opens as a read-only observe (no prompt path) — reported as a miss, never a second writer.
     */
    val scheduler = SchedulerService(
        scheduleStore,
        executor = ScheduleExecutor { entry ->
            // watching=false: this sink is a black hole (headless fire, no client attached). Counting it
            // as a watcher would suppress the owner ask-push while nobody can see/answer the card (C1).
            val sink = KeyedSink("scheduler", OutboundSink { /* headless fire — no client is attached */ }, watching = false)
            val wd = dirs.validateWorkdir(entry.workdir)
                ?: return@ScheduleExecutor "not a readable directory: ${entry.workdir}"
            val convoId = registry.open(
                OpenSession(
                    workdir = wd.toString(), resumeId = entry.resumeId,
                    model = entry.model, mode = entry.mode, agent = entry.agent,
                ),
                sink,
                // headless: same reason as watching=false above — an ask nobody can see must keep its
                // bounded window, or issue #201's wait would pin a process per fire (repeating schedules).
                headless = true,
            )
            // the handoff drive gate covers scheduled fires too (SESSION-HANDOFF.md §5.3: a WAITING/
            // handed-off session accepts input from its controller only — the scheduler is never that)
            val handoffDeny = if (convoId.isEmpty()) null else registry.driveDenied(convoId, "scheduler")
            when {
                convoId.isEmpty() -> "agent unavailable"
                handoffDeny != null -> handoffDeny.message
                !registry.sendPrompt(SendPrompt(convoId, entry.prompt, promptId = "sched-${entry.id}")) ->
                    "session unavailable (live in another client?)"
                else -> null
            }
        },
    )

    init {
        // the schedule pump lives on the core scope so BOTH transports (relay client + local server)
        // get scheduling for free; it ticks absolute times, so a boot after downtime settles/back-runs
        // whatever came due while the daemon was off (see SchedulerService's missed policy)
        scope.launch { scheduler.runLoop() }
    }

    val router = RequestRouter(
        registry, dirs, transcribe, inbox, shell, exports, scope, auth, prefs, presets, scheduler,
        // presetEnv shares PresetStore with the DaemonInfo gateway pill (Main.kt): the host we ask for a
        // model list must be the host the client is showing, with that layer's own credential (#167 ②).
        openCodeModels, codexModels,
        ClaudeModelService(claudeConfigDir, presetEnv = { runCatching { presetStore.activeEnv() }.getOrNull() }),
        approvals = approvals,
        grants = grants,
        approvalHistory = approvalHistory,
    )

    /**
     * The OWNER control planes (folder-share #115, bridges #91 follow-up), installed by RelayClient once
     * the relay link is up (minting a redeem ticket needs it) and null until then / on a LAN-only `serve`.
     *
     * They live HERE — not on the relay's DeviceSessions — because the relay is not the only transport an
     * owner arrives on: the desktop app on the daemon's own machine connects over the loopback LAN path,
     * and a control plane reachable only via the relay made Settings ▸ Shared/Bridges dead exactly where
     * they're most used. Every LAN peer is a full-power owner by construction (bridge/guest credentials
     * are structurally barred from the LAN gate — see BridgeStore), so both transports may serve these.
     */
    @Volatile
    var shareControl: dev.ccpocket.daemon.relay.ShareControl? = null
    @Volatile
    var bridgeControl: dev.ccpocket.daemon.relay.BridgeControl? = null
    /** The Collaborator Link contact plane (SESSION-HANDOFF.md §4.1) — same install/lifetime terms as
     *  the two above (minting a connect ticket needs the relay link). */
    @Volatile
    var collaboratorControl: dev.ccpocket.daemon.handoff.CollaboratorControl? = null

    suspend fun shutdown() = registry.closeAll()
}
