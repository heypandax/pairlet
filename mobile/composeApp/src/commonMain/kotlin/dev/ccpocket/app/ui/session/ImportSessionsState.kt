package dev.ccpocket.app.ui.session

import dev.ccpocket.protocol.AgentKind
import kotlinx.coroutines.launch

/*
 * Issue #360 stage 2, client side: the "Import from local history…" discovery screen as a pure state machine.
 *
 * Nothing here knows the wire. [ManagedSessionsGateway] is the client-internal seam; a later slice implements it on
 * top of the daemon's managed-session frames (requestId + connection generation live THERE). This layer owns the
 * user-facing guarantees instead: debounced search, one agent at a time, paging, idempotent import, and — the one that
 * matters for #360 — a result that arrives after the user switched project or computer never lands on the new page.
 */

/** Agents the first managed-session version covers. Everything else keeps the legacy scan path (plan: never filter DSH/Kimi/…). */
val IMPORTABLE_AGENTS: List<AgentKind> = listOf(AgentKind.CLAUDE, AgentKind.CODEX)

/** Where a discovery page is scoped. [computerId] is the paired daemon's device id; [workdir] is ALWAYS explicit —
 *  importing from a non-current project must never fall back to the Repository's current directory. */
data class ManagedScope(val computerId: String, val workdir: String)

/** Identity of a native session inside one [ManagedScope]. Same id under two agents is two different sessions. */
data class DiscoveredKey(val agent: AgentKind, val nativeId: String)

data class DiscoveredSession(
    val agent: AgentKind,
    val nativeId: String,
    val title: String,
    /** First user prompt / summary, display only. */
    val firstPrompt: String?,
    val lastModified: Long,
    /** The daemon already has it in the managed list. */
    val alreadyManaged: Boolean,
) {
    val key: DiscoveredKey get() = DiscoveredKey(agent, nativeId)
}

/** Client-side error vocabulary. The wire adapter maps daemon codes onto these; unknown codes become [INTERNAL]. */
enum class ManagedSessionsError {
    UNSUPPORTED, DENIED, INVALID_WORKDIR, NOT_FOUND, DISCONNECTED, TIMEOUT, INTERNAL,
    /** The daemon could not prove the answer because its scan was incomplete (enable / import). */
    SCAN_INCOMPLETE,
    /** The managed list is full. */
    CAPACITY,
    /** The daemon's store could not be read or written right now. */
    STORE_UNAVAILABLE,
    /** The daemon's store file is corrupt or of an unknown schema: read-only until it is repaired. */
    STORE_CORRUPT,
    /** A discovery cursor went stale — the list restarts from its first page. */
    CURSOR_EXPIRED,
    /** A change (enable / import / remove) got no answer in time: it may or may not have been applied. The list is being
     *  re-read to find out — never shown as a failure. */
    UNCONFIRMED,
}

/** Whether a project's agents still need the one-time "keep my list, import later ones" switch. */
sealed interface ManagedStatusResult {
    data class Statuses(val uninitialized: List<AgentKind>, val readOnly: Boolean) : ManagedStatusResult
    data class Failure(val error: ManagedSessionsError) : ManagedStatusResult
}

sealed interface EnableResult {
    data object Enabled : EnableResult
    data class Failure(val error: ManagedSessionsError, val diagnostic: DiscoverDiagnostic? = null) : EnableResult
}

/** The first-use notice of the import screen: which agents are still on the legacy list, and the enable progress. */
data class ManagedEnableUi(
    val uninitialized: List<AgentKind>,
    val busy: AgentKind? = null,
    val error: ManagedSessionsError? = null,
    val diagnostic: DiscoverDiagnostic? = null,
)

/** Why a successful page is not the whole picture (scan error / truncation / permission). Distinct from "no results". */
enum class DiscoverDiagnostic { SCAN_ERROR, TRUNCATED, PERMISSION, UNKNOWN }

sealed interface DiscoverResult {
    data class Page(
        val items: List<DiscoveredSession>,
        val nextCursor: String?,
        val complete: Boolean,
        val diagnostic: DiscoverDiagnostic? = null,
    ) : DiscoverResult
    data class Failure(val error: ManagedSessionsError) : DiscoverResult
}

sealed interface ImportResult {
    /** [alreadyManaged] = the daemon returned the existing member (repeat import); still a success. */
    data class Imported(val key: DiscoveredKey, val alreadyManaged: Boolean) : ImportResult
    data class Failure(val error: ManagedSessionsError) : ImportResult
}

sealed interface RemoveResult {
    data object Removed : RemoveResult
    data class Failure(val error: ManagedSessionsError) : RemoveResult
}

/** The seam the wire slice implements. Every call carries the explicit scope; implementations stamp requestId and
 *  connection generation and should return [ManagedSessionsError.DISCONNECTED] rather than hang when the link drops. */
interface ManagedSessionsGateway {
    suspend fun discover(scope: ManagedScope, agent: AgentKind, query: String, cursor: String?): DiscoverResult
    suspend fun import(scope: ManagedScope, agent: AgentKind, nativeId: String): ImportResult
    suspend fun remove(scope: ManagedScope, agent: AgentKind, nativeId: String): RemoveResult

    /** Which agents of [scope] are still on the legacy list. Default: none (nothing to switch on). */
    suspend fun status(scope: ManagedScope): ManagedStatusResult = ManagedStatusResult.Statuses(emptyList(), readOnly = false)

    /** The one-time switch of [agent] under [scope] to the managed list. Default: unsupported. */
    suspend fun enable(scope: ManagedScope, agent: AgentKind): EnableResult = EnableResult.Failure(ManagedSessionsError.UNSUPPORTED)

    /** Re-read [scope]'s managed list and say whether ([agent], [nativeId]) is a member: true / false, null = could not
     *  read. Settles an import whose answer never came. Default: unknown. */
    suspend fun reconcile(scope: ManagedScope, agent: AgentKind, nativeId: String): Boolean? = null
}

/** Stamp on every outgoing request; a result whose tag is stale is dropped. [generation] bumps on scope change / close. */
data class RequestTag(val generation: Long, val seq: Long)

sealed interface ListPhase {
    data object Idle : ListPhase
    /** First page of the current query/agent. Existing rows (if any) were cleared. */
    data object Loading : ListPhase
    data object Loaded : ListPhase
    data class Failed(val error: ManagedSessionsError) : ListPhase
}

sealed interface ImportPhase {
    data object InFlight : ImportPhase
    /** The import got no answer; the managed list is being re-read to learn whether it landed (bounded by that read). */
    data object Checking : ImportPhase
    data class Failed(val error: ManagedSessionsError) : ImportPhase
}

data class ImportSessionsState(
    val scope: ManagedScope? = null,
    val generation: Long = 0,
    val seq: Long = 0,
    val agents: List<AgentKind> = IMPORTABLE_AGENTS,
    val agent: AgentKind = AgentKind.CLAUDE,
    /** What is in the text field right now. */
    val queryInput: String = "",
    /** The query the visible rows belong to. */
    val appliedQuery: String = "",
    val debounceToken: Long = 0,
    val items: List<DiscoveredSession> = emptyList(),
    val nextCursor: String? = null,
    val complete: Boolean = true,
    val diagnostic: DiscoverDiagnostic? = null,
    val list: ListPhase = ListPhase.Idle,
    val loadingMore: Boolean = false,
    val loadMoreError: ManagedSessionsError? = null,
    /** Tag of the ONE discover request whose answer is still wanted. */
    val discoverTag: RequestTag? = null,
    val imports: Map<DiscoveredKey, ImportPhase> = emptyMap(),
    /** Imported in this screen (or learned from the host), on top of each row's [DiscoveredSession.alreadyManaged]. */
    val importedKeys: Set<DiscoveredKey> = emptySet(),
) {
    fun isImported(s: DiscoveredSession): Boolean = s.alreadyManaged || s.key in importedKeys
    val canLoadMore: Boolean get() = list == ListPhase.Loaded && nextCursor != null && !loadingMore
    val isEmptyResult: Boolean get() = list == ListPhase.Loaded && items.isEmpty()
}

sealed interface ImportSessionsEvent {
    /** Open the screen, or retarget it (other project / other computer). Always a new generation. */
    data class Open(val scope: ManagedScope, val agents: List<AgentKind> = IMPORTABLE_AGENTS, val agent: AgentKind? = null) : ImportSessionsEvent
    data object Close : ImportSessionsEvent
    data class QueryTyped(val text: String) : ImportSessionsEvent
    data class DebounceElapsed(val token: Long) : ImportSessionsEvent
    data class SelectAgent(val agent: AgentKind) : ImportSessionsEvent
    data object LoadMore : ImportSessionsEvent
    data object Retry : ImportSessionsEvent
    data class DiscoverReturned(val tag: RequestTag, val result: DiscoverResult) : ImportSessionsEvent
    data class ImportClicked(val key: DiscoveredKey) : ImportSessionsEvent
    data class ImportReturned(val tag: RequestTag, val key: DiscoveredKey, val result: ImportResult) : ImportSessionsEvent
    /** The host learned membership changed elsewhere (e.g. "remove from list" then re-import must be possible). */
    data class MembershipChanged(val scope: ManagedScope, val key: DiscoveredKey, val managed: Boolean) : ImportSessionsEvent
    /** The re-read after an unanswered import: member (true), not a member (false), or still unreadable (null). */
    data class ReconcileReturned(val tag: RequestTag, val key: DiscoveredKey, val managed: Boolean?) : ImportSessionsEvent
}

sealed interface ImportSessionsEffect {
    data class Debounce(val token: Long) : ImportSessionsEffect
    /** An import got no answer: re-read the managed list to learn whether it landed. */
    data class Reconcile(val tag: RequestTag, val scope: ManagedScope, val key: DiscoveredKey) : ImportSessionsEffect
    data class Discover(val tag: RequestTag, val scope: ManagedScope, val agent: AgentKind, val query: String, val cursor: String?) : ImportSessionsEffect
    data class Import(val tag: RequestTag, val scope: ManagedScope, val key: DiscoveredKey) : ImportSessionsEffect
    /** Tell the host to locate the session in the managed list. Never a prompt, never a takeover. */
    data class Imported(val scope: ManagedScope, val key: DiscoveredKey, val alreadyManaged: Boolean) : ImportSessionsEffect
}

data class ImportSessionsStep(val state: ImportSessionsState, val effects: List<ImportSessionsEffect> = emptyList())

/**
 * The whole screen as one pure function. Stale-answer rule: a discover result counts only if its tag is the current
 * [ImportSessionsState.discoverTag]; an import result counts only if its tag's generation is the current generation.
 * [ImportSessionsEvent.Open] and [ImportSessionsEvent.Close] bump the generation, so nothing from a previous project
 * or computer can touch the page — not rows, not import marks, not the host's "locate it" callback.
 */
fun reduceImportSessions(state: ImportSessionsState, event: ImportSessionsEvent): ImportSessionsStep = when (event) {
    is ImportSessionsEvent.Open -> {
        val agents = event.agents.filter { it in IMPORTABLE_AGENTS }.distinct()
        val agent = event.agent?.takeIf { it in agents } ?: agents.firstOrNull() ?: AgentKind.CLAUDE
        val fresh = ImportSessionsState(
            scope = event.scope, generation = state.generation + 1, seq = state.seq,
            agents = agents, agent = agent, debounceToken = state.debounceToken + 1,
        )
        if (agents.isEmpty()) ImportSessionsStep(fresh.copy(list = ListPhase.Failed(ManagedSessionsError.UNSUPPORTED)))
        else firstPage(fresh)
    }

    ImportSessionsEvent.Close -> ImportSessionsStep(
        ImportSessionsState(generation = state.generation + 1, seq = state.seq, debounceToken = state.debounceToken + 1),
    )

    is ImportSessionsEvent.QueryTyped -> {
        if (state.scope == null) ImportSessionsStep(state)
        else {
            val token = state.debounceToken + 1
            ImportSessionsStep(state.copy(queryInput = event.text, debounceToken = token), listOf(ImportSessionsEffect.Debounce(token)))
        }
    }

    is ImportSessionsEvent.DebounceElapsed -> {
        val query = state.queryInput.trim()
        if (state.scope == null || event.token != state.debounceToken || query == state.appliedQuery) ImportSessionsStep(state)
        else firstPage(state)
    }

    is ImportSessionsEvent.SelectAgent -> {
        if (state.scope == null || event.agent !in state.agents || event.agent == state.agent) ImportSessionsStep(state)
        else firstPage(state.copy(agent = event.agent))
    }

    ImportSessionsEvent.LoadMore -> {
        if (state.scope == null || !state.canLoadMore) ImportSessionsStep(state) else nextPage(state)
    }

    ImportSessionsEvent.Retry -> when {
        state.scope == null -> ImportSessionsStep(state)
        state.list is ListPhase.Failed && state.agents.isNotEmpty() -> firstPage(state)
        // a stale cursor cannot be resumed: the list starts over from its first page
        state.loadMoreError == ManagedSessionsError.CURSOR_EXPIRED && !state.loadingMore -> firstPage(state)
        state.loadMoreError != null && state.nextCursor != null && !state.loadingMore -> nextPage(state)
        else -> ImportSessionsStep(state)
    }

    is ImportSessionsEvent.DiscoverReturned -> {
        if (state.scope == null || event.tag != state.discoverTag) ImportSessionsStep(state)
        else ImportSessionsStep(applyPage(state.copy(discoverTag = null), event.result))
    }

    is ImportSessionsEvent.ImportClicked -> {
        val scope = state.scope
        val row = state.items.firstOrNull { it.key == event.key }
        val busy = state.imports[event.key].let { it == ImportPhase.InFlight || it == ImportPhase.Checking }
        if (scope == null || row == null || state.isImported(row) || busy) ImportSessionsStep(state)
        else {
            val tag = RequestTag(state.generation, state.seq + 1)
            ImportSessionsStep(
                state.copy(seq = tag.seq, imports = state.imports + (event.key to ImportPhase.InFlight)),
                listOf(ImportSessionsEffect.Import(tag, scope, event.key)),
            )
        }
    }

    is ImportSessionsEvent.ImportReturned -> {
        val scope = state.scope
        if (scope == null || event.tag.generation != state.generation || state.imports[event.key] != ImportPhase.InFlight) ImportSessionsStep(state)
        else when (val r = event.result) {
            is ImportResult.Imported -> ImportSessionsStep(
                state.copy(imports = state.imports - event.key, importedKeys = state.importedKeys + event.key),
                listOf(ImportSessionsEffect.Imported(scope, event.key, r.alreadyManaged)),
            )
            // no answer is not "no": check the list once (bounded by that read) before calling it anything
            is ImportResult.Failure -> if (r.error == ManagedSessionsError.UNCONFIRMED) {
                val tag = RequestTag(state.generation, state.seq + 1)
                ImportSessionsStep(
                    state.copy(seq = tag.seq, imports = state.imports + (event.key to ImportPhase.Checking)),
                    listOf(ImportSessionsEffect.Reconcile(tag, scope, event.key)),
                )
            } else ImportSessionsStep(state.copy(imports = state.imports + (event.key to ImportPhase.Failed(r.error))))
        }
    }

    is ImportSessionsEvent.ReconcileReturned -> {
        val scope = state.scope
        if (scope == null || event.tag.generation != state.generation || state.imports[event.key] != ImportPhase.Checking) ImportSessionsStep(state)
        else if (event.managed == true) ImportSessionsStep(
            state.copy(imports = state.imports - event.key, importedKeys = state.importedKeys + event.key),
            listOf(ImportSessionsEffect.Imported(scope, event.key, alreadyManaged = false)),
        )
        // not in the list, or still unreadable: unconfirmed — and retryable, since a repeat import never makes a copy
        else ImportSessionsStep(state.copy(imports = state.imports + (event.key to ImportPhase.Failed(ManagedSessionsError.UNCONFIRMED))))
    }

    is ImportSessionsEvent.MembershipChanged -> {
        if (event.scope != state.scope) ImportSessionsStep(state)
        else if (event.managed) ImportSessionsStep(state.copy(importedKeys = state.importedKeys + event.key, imports = state.imports - event.key))
        else ImportSessionsStep(
            state.copy(
                importedKeys = state.importedKeys - event.key,
                items = state.items.map { if (it.key == event.key) it.copy(alreadyManaged = false) else it },
            ),
        )
    }
}

private fun firstPage(state: ImportSessionsState): ImportSessionsStep {
    val scope = state.scope ?: return ImportSessionsStep(state)
    val query = state.queryInput.trim()
    val tag = RequestTag(state.generation, state.seq + 1)
    return ImportSessionsStep(
        state.copy(
            seq = tag.seq, appliedQuery = query, items = emptyList(), nextCursor = null, complete = true, diagnostic = null,
            list = ListPhase.Loading, loadingMore = false, loadMoreError = null, discoverTag = tag,
        ),
        listOf(ImportSessionsEffect.Discover(tag, scope, state.agent, query, cursor = null)),
    )
}

private fun nextPage(state: ImportSessionsState): ImportSessionsStep {
    val scope = state.scope ?: return ImportSessionsStep(state)
    val tag = RequestTag(state.generation, state.seq + 1)
    return ImportSessionsStep(
        state.copy(seq = tag.seq, loadingMore = true, loadMoreError = null, discoverTag = tag),
        listOf(ImportSessionsEffect.Discover(tag, scope, state.agent, state.appliedQuery, state.nextCursor)),
    )
}

private fun applyPage(state: ImportSessionsState, result: DiscoverResult): ImportSessionsState {
    val appending = state.loadingMore
    return when (result) {
        is DiscoverResult.Failure ->
            if (appending) state.copy(loadingMore = false, loadMoreError = result.error)
            else state.copy(list = ListPhase.Failed(result.error), items = emptyList(), nextCursor = null)
        is DiscoverResult.Page -> {
            // the agent filter is ours: a row for another agent is not shown under this tab
            val incoming = result.items.filter { it.agent == state.agent }
            val merged = if (appending) {
                val seen = state.items.mapTo(HashSet()) { it.key }
                state.items + incoming.filter { seen.add(it.key) }
            } else incoming.distinctBy { it.key }
            state.copy(
                items = merged, nextCursor = result.nextCursor, list = ListPhase.Loaded, loadingMore = false,
                loadMoreError = null,
                // partial-ness is sticky across pages: one truncated page means the list as a whole is not complete
                complete = if (appending) state.complete && result.complete else result.complete,
                diagnostic = result.diagnostic ?: if (appending) state.diagnostic else null,
            )
        }
    }
}

/**
 * Runs the reducer against a real [ManagedSessionsGateway]. Hosts create one per visible import screen and call
 * [dispatch]; [onImported] is where the host locates the session in the managed list (no prompt, no takeover).
 */
class ImportSessionsController(
    private val gateway: ManagedSessionsGateway,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS,
    private val onImported: (ImportSessionsEffect.Imported) -> Unit = {},
) {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(ImportSessionsState())
    val state: kotlinx.coroutines.flow.StateFlow<ImportSessionsState> = _state
    private var debounceJob: kotlinx.coroutines.Job? = null

    fun dispatch(event: ImportSessionsEvent) {
        var effects: List<ImportSessionsEffect> = emptyList()
        while (true) {
            val before = _state.value
            val step = reduceImportSessions(before, event)
            if (_state.compareAndSet(before, step.state)) { effects = step.effects; break }
        }
        if (event is ImportSessionsEvent.Open || event == ImportSessionsEvent.Close) debounceJob?.cancel()
        effects.forEach(::run)
    }

    private fun run(effect: ImportSessionsEffect) {
        when (effect) {
            is ImportSessionsEffect.Debounce -> {
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    kotlinx.coroutines.delay(debounceMillis)
                    dispatch(ImportSessionsEvent.DebounceElapsed(effect.token))
                }
            }
            is ImportSessionsEffect.Discover -> scope.launch {
                val r = guard({ DiscoverResult.Failure(it) }) { gateway.discover(effect.scope, effect.agent, effect.query, effect.cursor) }
                dispatch(ImportSessionsEvent.DiscoverReturned(effect.tag, r))
            }
            is ImportSessionsEffect.Import -> scope.launch {
                val r = guard({ ImportResult.Failure(it) }) { gateway.import(effect.scope, effect.key.agent, effect.key.nativeId) }
                dispatch(ImportSessionsEvent.ImportReturned(effect.tag, effect.key, r))
            }
            is ImportSessionsEffect.Reconcile -> scope.launch {
                val managed = guard({ null }) { gateway.reconcile(effect.scope, effect.key.agent, effect.key.nativeId) }
                dispatch(ImportSessionsEvent.ReconcileReturned(effect.tag, effect.key, managed))
            }
            is ImportSessionsEffect.Imported -> onImported(effect)
        }
    }

    private suspend fun <T> guard(onError: (ManagedSessionsError) -> T, call: suspend () -> T): T = try {
        call()
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (_: Throwable) {
        // a gateway that throws is a bug in the adapter, never a hang for the user
        onError(ManagedSessionsError.INTERNAL)
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 300L
    }
}
