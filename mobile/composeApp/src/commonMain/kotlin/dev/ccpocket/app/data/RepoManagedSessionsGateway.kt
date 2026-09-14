package dev.ccpocket.app.data

import dev.ccpocket.app.ui.session.DiscoverDiagnostic
import dev.ccpocket.app.ui.session.DiscoverResult
import dev.ccpocket.app.ui.session.DiscoveredKey
import dev.ccpocket.app.ui.session.EnableResult
import dev.ccpocket.app.ui.session.IMPORTABLE_AGENTS
import dev.ccpocket.app.ui.session.ImportResult
import dev.ccpocket.app.ui.session.ManagedScope
import dev.ccpocket.app.ui.session.ManagedSessionsError
import dev.ccpocket.app.ui.session.ManagedSessionsGateway
import dev.ccpocket.app.ui.session.ManagedStatusResult
import dev.ccpocket.app.ui.session.RemoveResult
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DiscoverSessions
import dev.ccpocket.protocol.DiscoveredSessions
import dev.ccpocket.protocol.EnableManagedSessions
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ImportSession
import dev.ccpocket.protocol.ListManagedSessions
import dev.ccpocket.protocol.MANAGED_DISCOVER_QUERY_MAX_CHARS
import dev.ccpocket.protocol.ManagedMigrationState
import dev.ccpocket.protocol.ManagedSessionsState
import dev.ccpocket.protocol.RemoveManagedSession
import dev.ccpocket.app.ui.session.DiscoveredSession as ClientRow

/** How one managed.* request ended at the repository seam. Only [Reply] carries a daemon answer. */
internal sealed interface ManagedCallOutcome {
    data class Reply(val frame: Frame) : ManagedCallOutcome
    /** Link dropped, computer switched, or the answer belongs to an older connection. */
    data object Disconnected : ManagedCallOutcome
    data object Timeout : ManagedCallOutcome
    /** This connection never advertised the capability (or not for that agent) — nothing was sent. */
    data object Unsupported : ManagedCallOutcome
}

/**
 * [ManagedSessionsGateway] over the repository's managed.* frames (issue #360). Every call names the computer, the
 * explicit workdir it was opened for and — always — the agent; the repository stamps a requestId + connection
 * generation and resolves the call to [ManagedCallOutcome.Disconnected] when either no longer matches, so a page never
 * outlives its computer. Rows and statuses whose agent this build cannot decode (null) are dropped, never guessed.
 *
 * A mutation (enable / import / remove) that times out is NOT a failure: the daemon may have committed it and the answer
 * got lost. It resolves as [ManagedSessionsError.UNCONFIRMED] and the repository re-reads the list to learn the truth.
 */
class RepoManagedSessionsGateway(private val repo: PocketRepository) : ManagedSessionsGateway {

    override suspend fun discover(scope: ManagedScope, agent: AgentKind, query: String, cursor: String?): DiscoverResult =
        when (val o = repo.managedCall(scope.computerId, scope.workdir, agent, mutation = false, timeoutMs = repo.managedListPageTimeoutMs) {
            DiscoverSessions(requestId = it, workdir = scope.workdir, agent = agent, query = query.take(MANAGED_DISCOVER_QUERY_MAX_CHARS), cursor = cursor)
        }) {
            is ManagedCallOutcome.Reply -> (o.frame as? DiscoveredSessions)?.let { page(it, agent) } ?: DiscoverResult.Failure(ManagedSessionsError.INTERNAL)
            else -> DiscoverResult.Failure(o.error(mutation = false))
        }

    override suspend fun import(scope: ManagedScope, agent: AgentKind, nativeId: String): ImportResult =
        when (val o = repo.managedCall(scope.computerId, scope.workdir, agent, mutation = true) {
            ImportSession(requestId = it, workdir = scope.workdir, agent = agent, sessionId = nativeId)
        }) {
            is ManagedCallOutcome.Reply -> {
                val f = o.frame as? ManagedSessionsState
                when {
                    f == null -> ImportResult.Failure(ManagedSessionsError.INTERNAL)
                    f.error != null -> ImportResult.Failure(managedErrorOf(f.error))
                    // the key is what WE asked for — the reply's agent is not trusted to widen or change it
                    else -> ImportResult.Imported(DiscoveredKey(agent, f.sessionId ?: nativeId), f.alreadyManaged)
                }
            }
            else -> ImportResult.Failure(o.error(mutation = true))
        }

    override suspend fun remove(scope: ManagedScope, agent: AgentKind, nativeId: String): RemoveResult =
        when (val o = repo.managedCall(scope.computerId, scope.workdir, agent, mutation = true) {
            RemoveManagedSession(requestId = it, workdir = scope.workdir, agent = agent, sessionId = nativeId)
        }) {
            is ManagedCallOutcome.Reply -> {
                val f = o.frame as? ManagedSessionsState
                when {
                    f == null -> RemoveResult.Failure(ManagedSessionsError.INTERNAL)
                    f.error != null -> RemoveResult.Failure(managedErrorOf(f.error))
                    else -> RemoveResult.Removed
                }
            }
            else -> RemoveResult.Failure(o.error(mutation = true))
        }

    /** Agent statuses only — the first page of an all-agents list read says which agents are still UNINITIALIZED. */
    override suspend fun status(scope: ManagedScope): ManagedStatusResult =
        when (val o = repo.managedCall(scope.computerId, scope.workdir, agent = null, mutation = false, timeoutMs = repo.managedListPageTimeoutMs) {
            ListManagedSessions(requestId = it, workdir = scope.workdir, allAgents = true)
        }) {
            is ManagedCallOutcome.Reply -> {
                val f = o.frame as? ManagedSessionsState
                val agents = f?.agents
                when {
                    // a corrupt store answers with agents/items = null: that is "read-only", not an unreadable reply
                    f != null && (f.readOnly || f.error == dev.ccpocket.protocol.ManagedSessionErrors.STORE_CORRUPT) ->
                        ManagedStatusResult.Statuses(
                            uninitialized = agents.orEmpty().mapNotNull { s -> s.agent?.takeIf { s.migration == ManagedMigrationState.UNINITIALIZED && it in IMPORTABLE_AGENTS } },
                            readOnly = true,
                        )
                    f?.error != null -> ManagedStatusResult.Failure(managedErrorOf(f.error))
                    agents == null -> ManagedStatusResult.Failure(ManagedSessionsError.STORE_UNAVAILABLE)
                    else -> ManagedStatusResult.Statuses(
                        uninitialized = agents.mapNotNull { s ->
                            s.agent?.takeIf {
                                s.migration == ManagedMigrationState.UNINITIALIZED && it in IMPORTABLE_AGENTS && it in repo.daemonManagedAgents.value
                            }
                        }.distinct(),
                        readOnly = f.readOnly,
                    )
                }
            }
            else -> ManagedStatusResult.Failure(o.error(mutation = false))
        }

    override suspend fun enable(scope: ManagedScope, agent: AgentKind): EnableResult =
        when (val o = repo.managedCall(scope.computerId, scope.workdir, agent, mutation = true, timeoutMs = repo.managedEnableTimeoutMs) {
            EnableManagedSessions(requestId = it, workdir = scope.workdir, agent = agent)
        }) {
            is ManagedCallOutcome.Reply -> {
                val f = o.frame as? ManagedSessionsState
                when {
                    f == null -> EnableResult.Failure(ManagedSessionsError.INTERNAL)
                    f.error != null -> EnableResult.Failure(managedErrorOf(f.error), managedDiagnosticOf(f.diagnostic))
                    else -> EnableResult.Enabled
                }
            }
            else -> EnableResult.Failure(o.error(mutation = true))
        }

    /** Whether ([agent], [nativeId]) is in [scope]'s managed list right now — one full, fresh list read. */
    override suspend fun reconcile(scope: ManagedScope, agent: AgentKind, nativeId: String): Boolean? =
        repo.readManagedMembership(scope.computerId, scope.workdir, agent, nativeId)

    private fun page(f: DiscoveredSessions, asked: AgentKind): DiscoverResult {
        if (f.error != null) return DiscoverResult.Failure(managedErrorOf(f.error))
        val diagnostic = managedDiagnosticOf(f.diagnostic)
        // "not complete" alone also means "more pages follow"; only a named reason, or a last page that still says
        // incomplete, is a partial scan the user should be told about
        val partial = diagnostic != null || (!f.complete && f.nextCursor == null)
        return DiscoverResult.Page(
            // an undecodable agent (null) is dropped; a row for an agent we did not ask about is not ours either
            items = f.items.mapNotNull { row ->
                val agent = row.agent ?: return@mapNotNull null
                if (agent != asked) return@mapNotNull null
                ClientRow(agent, row.sessionId, row.title.ifBlank { row.sessionId }, row.firstPrompt.ifBlank { null }, row.lastModified, row.alreadyManaged)
            },
            nextCursor = f.nextCursor,
            complete = !partial,
            diagnostic = diagnostic ?: if (partial) DiscoverDiagnostic.UNKNOWN else null,
        )
    }

    private fun ManagedCallOutcome.error(mutation: Boolean): ManagedSessionsError = when (this) {
        ManagedCallOutcome.Timeout -> if (mutation) ManagedSessionsError.UNCONFIRMED else ManagedSessionsError.TIMEOUT
        ManagedCallOutcome.Unsupported -> ManagedSessionsError.UNSUPPORTED
        else -> ManagedSessionsError.DISCONNECTED
    }
}
