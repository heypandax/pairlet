package dev.ccpocket.daemon

import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.SpawnedSessions
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
 *  backends, wired by the caller) operate on the daemon's own CLAUDE_CONFIG_DIR. */
class DaemonCore(
    backends: Map<AgentKind, AgentBackendFactory>,
    val prefs: DaemonPrefs = DaemonPrefs.load(),
    claudeConfigDir: java.nio.file.Path? = null,
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
    val shell = ShellService(scope)
    val auth = AuthService(
        scope, registry::busyForAuth, registry::closeIdleForAuth, registry::closeBusyForAuth,
        claudeConfigDir = claudeConfigDir,
    )
    val router = RequestRouter(registry, dirs, transcribe, shell, scope, auth, prefs)

    suspend fun shutdown() = registry.closeAll()
}
