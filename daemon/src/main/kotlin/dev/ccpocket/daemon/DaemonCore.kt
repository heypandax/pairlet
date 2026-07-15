package dev.ccpocket.daemon

import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.disk.SpawnedSessions
import dev.ccpocket.daemon.conversation.KeyedSink
import dev.ccpocket.daemon.conversation.OutboundSink
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
    presetStore: PresetStore = PresetStore.load(),
    scheduleStore: ScheduleStore = ScheduleStore.load(),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val registry = SessionRegistry(scope, backends)

    init {
        // unhide transcripts a crashed previous instance stranded hidden (issue #70) — off the
        // constructor path (file IO over up to 200 journal entries must not delay startup)
        scope.launch(Dispatchers.IO) { runCatching { SpawnedSessions.sweepAtBoot() } }
    }

    val dirs = DirectoryService()
    val transcribe = TranscribeService(scope, registry::workdirOf)
    val inbox = FileInboxService(registry::workdirOf)
    val shell = ShellService(scope)
    val exports = FileExportService(scope, registry::workdirOf)
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
            )
            when {
                convoId.isEmpty() -> "agent unavailable"
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

    val router = RequestRouter(registry, dirs, transcribe, inbox, shell, exports, scope, auth, prefs, presets, scheduler)

    suspend fun shutdown() = registry.closeAll()
}
