package dev.ccpocket.daemon

import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.disk.SpawnedSessions
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.server.RequestRouter
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AgentKind
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
    val router = RequestRouter(registry, dirs, transcribe, inbox, shell, exports, scope, auth, prefs, presets)

    suspend fun shutdown() = registry.closeAll()
}
