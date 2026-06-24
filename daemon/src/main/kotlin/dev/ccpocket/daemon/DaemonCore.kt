package dev.ccpocket.daemon

import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.server.RequestRouter
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AgentKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The transport-agnostic core: registry + services + router. Shared by the local server and the relay client.
 *  [backends] maps each agent kind to a factory that builds a fresh per-conversation driver. */
class DaemonCore(backends: Map<AgentKind, AgentBackendFactory>) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val registry = SessionRegistry(scope, backends)
    val dirs = DirectoryService()
    val transcribe = TranscribeService(scope, registry::workdirOf)
    val shell = ShellService(scope)
    val router = RequestRouter(registry, dirs, transcribe, shell)

    suspend fun shutdown() = registry.closeAll()
}
