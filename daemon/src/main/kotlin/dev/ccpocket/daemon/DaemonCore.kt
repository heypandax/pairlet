package dev.ccpocket.daemon

import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.server.RequestRouter
import dev.ccpocket.daemon.session.SessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Path

/** The transport-agnostic core: registry + services + router. Shared by the local server and the relay client. */
class DaemonCore(claudeExe: Path) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val registry = SessionRegistry(scope, claudeExe)
    val dirs = DirectoryService()
    val router = RequestRouter(registry, dirs)

    suspend fun shutdown() = registry.closeAll()
}
