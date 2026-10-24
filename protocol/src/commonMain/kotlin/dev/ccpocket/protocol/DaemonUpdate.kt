package dev.ccpocket.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Owner only; sent after the connected daemon advertises daemonUpdateMode="remote". */
@Serializable
@SerialName("pocket/daemon.update.start")
data class DaemonUpdateStart(val requestId: String) : ToDaemon

/** Read-only progress query, including after a connection interruption. */
@Serializable
@SerialName("pocket/daemon.update.get")
data class DaemonUpdateGet(val requestId: String) : ToDaemon

/** Request-scoped progress. "restarting" is not success; DaemonInfo after reconnect proves the version. */
@Serializable
@SerialName("pocket/daemon.update.status")
data class DaemonUpdateStatus(
    val requestId: String,
    val state: String,
    val targetVersion: String? = null,
    val controlsBlocked: Boolean = false,
) : ToPhone

/** Pause new work during install; existing work must still be able to finish or be stopped. */
object DaemonUpdateAccess {
    fun allowed(frame: Frame): Boolean = frame !is ToDaemon || when (frame) {
        is ClientCaps, is ListDirectories, is ListSessions, is ListArchivedSessions,
        is FetchHistoryPage, is HistoryApplied, is FetchModels, is ClaudeQuotaGet,
        is FetchImage, is ListSessionFiles, is ReadFile, is ReadFileDiff, is ListPathEntries,
        is FetchAuthStatus, is FetchPresets, is FetchUsage, is ListPendingApprovals,
        is DaemonUpdateGet, is DaemonUpdateStart -> true
        // Background/scheduled work can start while a download is in flight. Refusing its answers
        // would keep both the task and an updater waiting for idle until the ask times out.
        is PermissionVerdict, is CancelTurn, is StopBackgroundJob -> true
        is OpenSession -> frame.observeOnly
        else -> false
    }
}
