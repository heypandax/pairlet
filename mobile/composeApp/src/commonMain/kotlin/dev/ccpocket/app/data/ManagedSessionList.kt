package dev.ccpocket.app.data

import dev.ccpocket.app.ui.session.DiscoverDiagnostic
import dev.ccpocket.app.ui.session.ManagedSessionsError
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ManagedAgentStatus
import dev.ccpocket.protocol.ManagedAvailability
import dev.ccpocket.protocol.ManagedMigrationState
import dev.ccpocket.protocol.ManagedScanDiagnostics
import dev.ccpocket.protocol.ManagedSessionEntry
import dev.ccpocket.protocol.ManagedSessionErrors
import dev.ccpocket.protocol.SessionSummary

/**
 * Issue #360 stage 2: one project's managed list as this client last accepted it. Only built from a
 * [dev.ccpocket.protocol.ManagedSessionsState] whose [agents] and [items] were both readable — a null list means "the
 * store could not be read" and is never turned into an empty one.
 */
data class ManagedProjectList(
    /** The spelling this client asked with (or the push's canonical path). */
    val workdir: String,
    val canonicalWorkdir: String?,
    val revision: Long?,
    val agents: List<ManagedAgentStatus>,
    val items: List<ManagedSessionEntry>,
    val readOnly: Boolean = false,
) {
    /** Agents whose rows come from this list: READY here AND managed by the daemon (per [DaemonInfo.managedAgents]). */
    fun readyAgents(managedAgents: Set<AgentKind>): Set<AgentKind> =
        agents.mapNotNullTo(HashSet()) { s -> s.agent?.takeIf { s.migration == ManagedMigrationState.READY && it in managedAgents } }

    fun uninitializedAgents(managedAgents: Set<AgentKind>): List<AgentKind> =
        agents.mapNotNull { s -> s.agent?.takeIf { s.migration == ManagedMigrationState.UNINITIALIZED && it in managedAgents } }
}

/** Most pages one managed list read follows before it is treated as failed (the list itself is bounded daemon-side). */
internal const val MANAGED_LIST_MAX_PAGES: Int = 100

/** How often one list read may start over after `managed_cursor_invalid` before it counts as failed. */
internal const val MANAGED_LIST_MAX_RESTARTS: Int = 2

/** How long a timed-out change's late answer still triggers a re-read. */
internal const val MANAGED_TOMBSTONE_MS: Long = 2 * 60_000L

/** [rows] replace the legacy [dev.ccpocket.protocol.Sessions] rows; [missing] are managed sessions whose native record a
 *  complete scan did not find ("original record unavailable"). */
data class MergedSessions(
    val rows: List<SessionSummary>,
    /** [managedRowKey]s of members whose native record a complete scan did not find. */
    val missing: Set<String>,
    val loading: Boolean = false,
    /** [managedRowKey]s whose group placement cannot be attributed to one agent (same id under two agents). */
    val ambiguous: Set<String> = emptySet(),
)

/**
 * Row identity (#360): the same native id under two agents is two sessions. Claude — the default and the only agent an
 * older daemon reports — keeps the bare id, so every existing list key and test key stays exactly what it was.
 */
fun managedRowKeyOf(agent: AgentKind?, sessionId: String): String =
    if (agent == null || agent == AgentKind.CLAUDE) sessionId else "${agent.name.lowercase()}:$sessionId"

fun SessionSummary.managedRowKey(): String = managedRowKeyOf(agent, sessionId)

/** A managed list accepted for a project that is NOT the listed one any more — its RECENT snapshot should be rewritten
 *  from [rows] (issue #360: a read that outlived a project switch). [seq] makes repeated events distinct. */
data class ManagedAccepted(
    val accountId: String,
    val workdir: String,
    val rows: List<SessionSummary>,
    val missing: Set<String>,
    val ambiguous: Set<String>,
    val seq: Long,
)

/**
 * The list a capable client shows for one project.
 *
 * - No managed list, or no agent READY → exactly [legacy] (the same instance), so a daemon without the capability — or an
 *   agent not migrated yet — renders precisely what it did before.
 * - Otherwise READY agents come from [managed] in its fixed order (the daemon's, never re-sorted by mtime), followed by the
 *   legacy rows of every other agent in their usual order. A member without a live scan row is rebuilt from its last-known
 *   display fields and filed under [ManagedSessionEntry.group].
 */
fun mergeManagedSessions(
    legacy: List<SessionSummary>,
    managed: ManagedProjectList?,
    managedAgents: Set<AgentKind>,
    workdir: String,
    /** No managed list accepted yet for this computer + project, and its first read is on its way. */
    awaitingFirstRead: Boolean = false,
    /** [managedRowKey]s of daemon rows shown even when their agent is READY and [managed] lacks them — used while a
     *  failing read keeps the last list: sessions this app created/opened and sessions running right now. */
    keepFromDaemon: Set<String> = emptySet(),
): MergedSessions {
    if (managed == null) {
        if (!awaitingFirstRead || managedAgents.isEmpty()) return MergedSessions(legacy, emptySet())
        // READY is still unknown: showing the managed agents' legacy rows now would flash outside sessions that the
        // list may remove a moment later. They wait (loading); every other agent shows as before.
        return MergedSessions(legacy.filter { (it.agent ?: AgentKind.CLAUDE) !in managedAgents }, emptySet(), loading = true)
    }
    val ready = managed.readyAgents(managedAgents)
    if (ready.isEmpty()) return MergedSessions(legacy, emptySet())
    // v2 rows carry cwd "". The daemon's canonical path is a realpath (/private/tmp for /tmp), which would not equal the
    // workdir the client matches rows against (selection, approval badges) — so the same session's own daemon row
    // names the path, and the workdir the client asked with covers a member the daemon rows don't list.
    val daemonRows = legacy.associateBy { it.managedRowKey() }
    val missing = HashSet<String>()
    val ambiguous = HashSet<String>()
    val fromManaged = managed.items.mapNotNull { e ->
        val agent = e.agent?.takeIf { it in ready } ?: return@mapNotNull null // undecodable or not READY: not from here
        val key = managedRowKeyOf(agent, e.sessionId)
        if (e.isMissing()) missing += key
        if (e.groupAmbiguous) ambiguous += key
        val path = daemonRows[key]?.cwd?.takeIf { it.isNotBlank() } ?: workdir
        val live = e.summary
        // a live summary is authoritative; the last-known fields are only the fallback for a row without one
        if (live != null) live.copy(agent = agent, group = e.group ?: live.group, cwd = live.cwd.ifBlank { path })
        else SessionSummary(
            sessionId = e.sessionId,
            title = e.lastKnownTitle?.takeIf { it.isNotBlank() } ?: e.sessionId,
            firstPrompt = "",
            messageCount = 0,
            cwd = path,
            lastModified = e.lastKnownModified ?: 0L,
            agent = agent,
            group = e.group,
        )
    }
    val others = legacy.filter { (it.agent ?: AgentKind.CLAUDE) !in ready || it.managedRowKey() in keepFromDaemon }
    // one row per (agent, id): the same native id under two agents is two sessions, and a duplicated key would crash
    // a lazy list natively — lists key rows by [managedRowKey] for exactly this reason
    return MergedSessions((fromManaged + others).distinctBy { it.managedRowKey() }, missing, ambiguous = ambiguous)
}

/** Daemon refusal code → client error. Unknown codes are [ManagedSessionsError.INTERNAL]. */
fun managedErrorOf(code: String?): ManagedSessionsError = when (code) {
    ManagedSessionErrors.FORBIDDEN -> ManagedSessionsError.DENIED
    ManagedSessionErrors.UNSUPPORTED -> ManagedSessionsError.UNSUPPORTED
    ManagedSessionErrors.INVALID_WORKDIR -> ManagedSessionsError.INVALID_WORKDIR
    ManagedSessionErrors.NOT_FOUND -> ManagedSessionsError.NOT_FOUND
    ManagedSessionErrors.SCAN_INCOMPLETE -> ManagedSessionsError.SCAN_INCOMPLETE
    ManagedSessionErrors.CAPACITY -> ManagedSessionsError.CAPACITY
    // corrupt = read-only until repaired; unavailable = a read/write that failed this time
    ManagedSessionErrors.STORE_CORRUPT -> ManagedSessionsError.STORE_CORRUPT
    ManagedSessionErrors.STORE_UNAVAILABLE -> ManagedSessionsError.STORE_UNAVAILABLE
    ManagedSessionErrors.CURSOR_INVALID -> ManagedSessionsError.CURSOR_EXPIRED
    else -> ManagedSessionsError.INTERNAL
}

/** Scan diagnostic → client reason. Null / "complete" → null (nothing to warn about). */
fun managedDiagnosticOf(code: String?): DiscoverDiagnostic? = when (code) {
    null, ManagedScanDiagnostics.COMPLETE -> null
    ManagedScanDiagnostics.ERROR, ManagedScanDiagnostics.PARTIAL -> DiscoverDiagnostic.SCAN_ERROR
    ManagedScanDiagnostics.TRUNCATED, ManagedScanDiagnostics.PAGE_LIMIT -> DiscoverDiagnostic.TRUNCATED
    ManagedScanDiagnostics.PERMISSION_DENIED -> DiscoverDiagnostic.PERMISSION
    else -> DiscoverDiagnostic.UNKNOWN
}

/** Wire names from [dev.ccpocket.protocol.DaemonInfo.managedAgents] → kinds; unknown names are ignored. */
fun managedAgentsOf(wire: List<String>): Set<AgentKind> =
    wire.mapNotNullTo(HashSet()) { name -> AgentKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }

internal fun ManagedSessionEntry.isMissing(): Boolean = availability == ManagedAvailability.MISSING
