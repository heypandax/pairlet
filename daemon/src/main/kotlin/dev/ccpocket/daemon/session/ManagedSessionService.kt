package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.MANAGED_SESSION_AGENTS
import dev.ccpocket.daemon.disk.ManagedMember
import dev.ccpocket.daemon.disk.ManagedMutation
import dev.ccpocket.daemon.disk.ManagedProjectRead
import dev.ccpocket.daemon.disk.ManagedProjectState
import dev.ccpocket.daemon.disk.ManagedSessionStore
import dev.ccpocket.daemon.disk.SessionArchive
import dev.ccpocket.daemon.disk.SessionGroups
import dev.ccpocket.daemon.pins.DurablePinFiles
import dev.ccpocket.daemon.pins.PinStoreWrite
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DiscoverSessions
import dev.ccpocket.protocol.DiscoveredSession
import dev.ccpocket.protocol.DiscoveredSessions
import dev.ccpocket.protocol.EnableManagedSessions
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.ImportSession
import dev.ccpocket.protocol.ListManagedSessions
import dev.ccpocket.protocol.MANAGED_CURSOR_MAX_CHARS
import dev.ccpocket.protocol.MANAGED_DISCOVER_MAX_PAGES
import dev.ccpocket.protocol.MANAGED_DISCOVER_PAGE_DEFAULT
import dev.ccpocket.protocol.MANAGED_DISCOVER_PAGE_MAX
import dev.ccpocket.protocol.MANAGED_DISCOVER_QUERY_MAX_CHARS
import dev.ccpocket.protocol.MANAGED_FRAME_BUDGET_BYTES
import dev.ccpocket.protocol.MANAGED_GROUPS_MAX
import dev.ccpocket.protocol.MANAGED_META_MAX_BYTES
import dev.ccpocket.protocol.MANAGED_PROMPT_PREVIEW_MAX_BYTES
import dev.ccpocket.protocol.MANAGED_TITLE_MAX_BYTES
import dev.ccpocket.protocol.MANAGED_WORKDIR_MAX_CHARS
import dev.ccpocket.protocol.ManagedAgentStatus
import dev.ccpocket.protocol.ManagedAvailability
import dev.ccpocket.protocol.ManagedScanDiagnostics
import dev.ccpocket.protocol.ManagedSessionEntry
import dev.ccpocket.protocol.ManagedSessionErrors
import dev.ccpocket.protocol.ManagedSessionsState
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.RemoveManagedSession
import dev.ccpocket.protocol.SessionGroup
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.ToPhone
import dev.ccpocket.protocol.isValidManagedId
import dev.ccpocket.protocol.isValidManagedWorkdir
import dev.ccpocket.protocol.truncateUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A backend reported a trusted native session id for a conversation this daemon runs (issue #360). */
fun interface NativeSessionHook {
    suspend fun onNativeSession(report: NativeSessionReport)
}

/**
 * One trusted native-id report. [parentSessionId] is the id the conversation was running or resuming when the
 * backend reported a DIFFERENT id (a fork / heal / rewind branch); null for a brand-new session. [isCurrent] is
 * evaluated when the report is processed: false once the process generation that produced it was replaced, so a
 * late init from a retired process never registers anything.
 *
 * [ownerCreated] is the three-way owner fact fixed when the conversation was OPENED (no bridge origin, no guest
 * scope, no collaborator grant). Only owner-created sessions are registered; a restricted credential's sessions stay
 * in discovery for the owner to import explicitly.
 */
data class NativeSessionReport(
    val convoId: String,
    val agent: AgentKind,
    val workdir: String,
    val sessionId: String,
    val parentSessionId: String?,
    val isCurrent: () -> Boolean,
    val ownerCreated: Boolean,
)

/** A registration the daemon has evidence for but has not yet seen committed (crash / write-failure window). */
@Serializable
data class PendingRegistration(
    val agent: AgentKind,
    val workdir: String,
    val sessionId: String,
    val parentSessionId: String? = null,
    val at: Long = 0,
)

@Serializable
private data class PendingDoc(val schemaVersion: Int = 1, val entries: List<PendingRegistration> = emptyList())

/**
 * The managed session list's owner-side service (issue #360): answers the five managed.* requests, projects the
 * store onto live scans, pages lists and discovery under the frame byte budget, fans durable changes out to OWNER
 * connections, and turns trusted native-id reports of OWNER-created sessions into CREATED_HERE registrations.
 *
 * Authority is never decided here: the router admits only OWNER connections that declared the capability, the
 * transports attach only owner connections as subscribers, and each transport gates what it emits per connection.
 *
 * Creation association, per report: (1) drop unless owner-created, the agent is managed and the report's process
 * generation is still current; (2) durably record a [PendingRegistration]; (3) commit the store; (4) clear the pending
 * record. A crash after (2) is finished by [recoverPending] at the next boot (idempotent); a crash before (2) leaves
 * no evidence, and the native session simply stays in discovery. A failed (3) keeps the pending record unless the
 * failure can never succeed by retrying (CAPACITY, no evidence); the conversation is never stopped or recreated, and
 * owner subscribers get a neutral `managed_register_failed` notice.
 *
 * Cursors are MACed with a per-process random key over every field they carry, so a client can neither forge a page
 * number past [MANAGED_DISCOVER_MAX_PAGES] nor replay a cursor across projects, scopes, queries or store revisions.
 * Reads are capped per connection ([maxReadsPerConnection]) and scans are reused across pages for a short time.
 */
class ManagedSessionService internal constructor(
    val store: ManagedSessionStore,
    private val scope: CoroutineScope,
    private val scan: (workdir: String, agent: AgentKind) -> SessionScan,
    /** The daemon's usable spelling of a workdir (real path) for scans and group lookups, or null when invalid. */
    private val validateWorkdir: (String) -> String?,
    agents: Set<AgentKind>,
    private val pendingFile: File,
    private val files: DurablePinFiles = DurablePinFiles(),
    private val groupsFor: (String) -> List<SessionGroup> = { SessionGroups.groupsFor(it) },
    private val groupOf: (String, String) -> String? = { w, s -> SessionGroups.groupOf(w, s) },
    private val archivedIds: (String) -> Set<String> = { emptySet() },
    private val busyIds: suspend () -> Set<String> = { emptySet() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val deliveryTimeoutMs: Long = DELIVERY_TIMEOUT_MS,
    private val frameBudgetBytes: Int = MANAGED_FRAME_BUDGET_BYTES,
    /** How long a (project, agent, store revision) scan is reused by later pages / reads; 0 disables reuse. */
    private val scanCacheTtlMs: Long = SCAN_CACHE_TTL_MS,
    private val maxReadsPerConnection: Int = MAX_READS_PER_CONNECTION,
    cursorKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
) : NativeSessionHook {
    private val log = logger("ManagedSessions")

    /** Managed agents this daemon can actually scan, in wire order. */
    val agents: List<AgentKind> = MANAGED_SESSION_AGENTS.filter { it in agents }.sortedBy { it.ordinal }

    fun agentWires(): List<String> = agents.map { it.name.lowercase() }

    /** Serializes every store mutation and pending-record write in the process. */
    private val mutations = Mutex()

    /** FIFO of mutation requests: one worker, so two requests commit in the order they were accepted. */
    private val queue = Channel<suspend () -> Unit>(QUEUE_CAPACITY)

    private val cursorMacKey = SecretKeySpec(cursorKey.copyOf(), "HmacSHA256")

    init {
        scope.launch { for (job in queue) runCatching { job() }.onFailure { log.warn("managed mutation failed: ${it.message}") } }
    }

    /** Which request a reply answers: exactly one agent, or all managed agents. */
    private data class Scope(val agent: AgentKind?, val all: Boolean) {
        fun agents(managed: List<AgentKind>) = if (all) managed else listOfNotNull(agent)
        val tag get() = agent?.name ?: "*"
    }

    // ── push fan-out (owner connections only) ───────────────────────────────────────────────────────────

    private class Subscriber(val deliver: suspend (ManagedSessionsState) -> Unit, val onRegisterError: suspend (PocketError) -> Unit)

    private val subscribers = ConcurrentHashMap<Any, Subscriber>()

    /**
     * One OWNER connection's push target, keyed like the transport keys its sink. [deliver] must gate on that
     * connection's CURRENT capability declaration and filter rows with [filterAgents]; [onRegisterError] receives the
     * neutral registration-failure notice. The service never knows which connection a key stands for.
     */
    fun attach(key: Any, onRegisterError: suspend (PocketError) -> Unit = {}, deliver: suspend (ManagedSessionsState) -> Unit) {
        subscribers[key] = Subscriber(deliver, onRegisterError)
    }

    fun detach(key: Any) { subscribers.remove(key) }

    internal fun subscriberCount() = subscribers.size

    /** Test seam: runs after a push was projected and its targets captured, before any delivery. */
    internal var beforePushDelivery: (suspend () -> Unit)? = null

    private fun broadcast(canonical: String, exceptKey: Any?) {
        val targets = subscribers.filterKeys { it != exceptKey }
        if (targets.isEmpty()) return
        scope.launch {
            val push = runCatching { projectFresh(null, canonical, Scope(null, all = true), 0) }.getOrNull() ?: return@launch
            if (push.error != null) return@launch
            beforePushDelivery?.invoke()
            for ((_, s) in targets) scope.launch { withTimeoutOrNull(deliveryTimeoutMs) { s.deliver(push) } }
        }
    }

    // ── requests ────────────────────────────────────────────────────────────────────────────────────────

    private val reads = ConcurrentHashMap<Any, AtomicInteger>()

    /**
     * Answer one admitted request on [sink]. Reads run off the caller (at most [maxReadsPerConnection] in flight per
     * [requesterKey]); mutations go through the FIFO worker. A request whose requestId is malformed cannot be
     * correlated and is dropped (its reply would read as a push). [allows] is the requesting connection's agent
     * vocabulary; [requesterKey] is excluded from the resulting push.
     */
    fun accept(frame: ToDaemon, sink: OutboundSink, requesterKey: Any?, allows: (AgentKind) -> Boolean = { true }) {
        val requestId = when (frame) {
            is ListManagedSessions -> frame.requestId
            is EnableManagedSessions -> frame.requestId
            is DiscoverSessions -> frame.requestId
            is ImportSession -> frame.requestId
            is RemoveManagedSession -> frame.requestId
            else -> return
        }
        if (!isValidManagedId(requestId)) { log.warn("managed request with a malformed requestId dropped"); return }
        suspend fun reply(f: ToPhone) {
            val out = when (f) {
                is ManagedSessionsState -> filterAgents(f, allows)
                is DiscoveredSessions -> filterAgents(f, allows)
                else -> f
            }
            withTimeoutOrNull(deliveryTimeoutMs) { sink.emit(out) }
        }
        when (frame) {
            is ListManagedSessions, is DiscoverSessions -> {
                val key = requesterKey ?: sink
                val counter = reads.compute(key) { _, c -> (c ?: AtomicInteger()).also { it.incrementAndGet() } }!!
                if (counter.get() > maxReadsPerConnection) {
                    release(key)
                    val busy = "too many concurrent managed reads on this connection; retry"
                    scope.launch {
                        reply(
                            if (frame is DiscoverSessions) DiscoveredSessions(requestId, echo(frame.workdir), frame.agent, error = ManagedSessionErrors.STORE_UNAVAILABLE, message = busy)
                            else refusal(requestId, (frame as ListManagedSessions).workdir, Scope(frame.agent, frame.allAgents), ManagedSessionErrors.STORE_UNAVAILABLE, message = busy),
                        )
                    }
                    return
                }
                scope.launch {
                    try {
                        reply(if (frame is DiscoverSessions) discover(frame) else list(frame as ListManagedSessions))
                    } finally {
                        release(key)
                    }
                }
            }
            else -> {
                val job: suspend () -> Unit = {
                    val (state, changedProject) = mutate(frame)
                    // pushed to EVERY owner subscriber, the requester included: a requester whose request timed out has
                    // dropped the late reply, and the push is its only signal to re-read (duplicates are harmless)
                    changedProject?.let { broadcast(it, null) }
                    reply(state)
                }
                if (queue.trySend(job).isFailure) {
                    val (wd, agent) = when (frame) {
                        is EnableManagedSessions -> frame.workdir to frame.agent
                        is ImportSession -> frame.workdir to frame.agent
                        is RemoveManagedSession -> frame.workdir to frame.agent
                        else -> return
                    }
                    scope.launch { reply(refusal(requestId, wd, Scope(agent, false), ManagedSessionErrors.STORE_UNAVAILABLE, message = "too many pending changes; retry")) }
                }
            }
        }
    }

    private fun release(key: Any) {
        reads.computeIfPresent(key) { _, c -> if (c.decrementAndGet() <= 0) null else c }
    }

    internal suspend fun list(frame: ListManagedSessions): ManagedSessionsState {
        val scope = Scope(frame.agent, frame.allAgents)
        if (!isValidManagedWorkdir(frame.workdir)) return refusal(frame.requestId, frame.workdir, scope, ManagedSessionErrors.INVALID_WORKDIR)
        if ((frame.agent == null) == !frame.allAgents) return refusal(frame.requestId, frame.workdir, scope, ManagedSessionErrors.INVALID_REQUEST, message = "name exactly one of agent / allAgents")
        preflight(frame.requestId, frame.workdir, scope)?.let { return it }
        val canonical = store.resolveProject(frame.workdir)!!
        val read = store.read(frame.workdir)
        var offset = 0
        val cursor = frame.cursor
        if (cursor != null) {
            val revision = (read as? ManagedProjectRead.Loaded)?.state?.revision ?: 0L
            offset = parseListCursor(cursor, canonical, scope, revision)
                ?: return refusal(frame.requestId, frame.workdir, scope, ManagedSessionErrors.CURSOR_INVALID, canonical = canonical)
        }
        return project(frame.requestId, frame.workdir, scope, canonical, read, emptyMap(), offset)
    }

    /** Enable / import / remove. Returns the reply and, when the store durably changed, the canonical project. */
    internal suspend fun mutate(frame: ToDaemon): Pair<ManagedSessionsState, String?> = mutations.withLock {
        when (frame) {
            is EnableManagedSessions -> {
                if (!isValidManagedWorkdir(frame.workdir)) return@withLock refusal(frame.requestId, frame.workdir, Scope(frame.agent, false), ManagedSessionErrors.INVALID_WORKDIR) to null
                val agent = frame.agent ?: return@withLock invalidAgent(frame.requestId, frame.workdir) to null
                preflight(frame.requestId, frame.workdir, Scope(agent, false))?.let { return@withLock it to null }
                val s = scanOf(validateWorkdir(frame.workdir)!!, agent)
                // the legacy list's own presentation order for this agent: newest transcript first
                val visible = s.items.sortedByDescending { it.lastModified }.map { it.sessionId }
                finish(frame.requestId, frame.workdir, agent, null, s, store.migrate(frame.workdir, agent, s, visible))
            }
            is ImportSession -> {
                if (!isValidManagedWorkdir(frame.workdir)) return@withLock refusal(frame.requestId, frame.workdir, Scope(frame.agent, false), ManagedSessionErrors.INVALID_WORKDIR) to null
                val agent = frame.agent ?: return@withLock invalidAgent(frame.requestId, frame.workdir) to null
                preflight(frame.requestId, frame.workdir, Scope(agent, false), frame.sessionId)?.let { return@withLock it to null }
                val s = scanOf(validateWorkdir(frame.workdir)!!, agent)
                finish(frame.requestId, frame.workdir, agent, frame.sessionId, s, store.import(frame.workdir, agent, frame.sessionId, s))
            }
            is RemoveManagedSession -> {
                if (!isValidManagedWorkdir(frame.workdir)) return@withLock refusal(frame.requestId, frame.workdir, Scope(frame.agent, false), ManagedSessionErrors.INVALID_WORKDIR) to null
                val agent = frame.agent ?: return@withLock invalidAgent(frame.requestId, frame.workdir) to null
                preflight(frame.requestId, frame.workdir, Scope(agent, false), frame.sessionId)?.let { return@withLock it to null }
                finish(frame.requestId, frame.workdir, agent, frame.sessionId, null, store.remove(frame.workdir, agent, frame.sessionId))
            }
            else -> error("not a managed mutation")
        }
    }

    private fun invalidAgent(requestId: String, workdir: String) =
        refusal(requestId, workdir, Scope(null, false), ManagedSessionErrors.INVALID_REQUEST, message = "missing or unknown agent")

    private suspend fun finish(
        requestId: String, workdir: String, agent: AgentKind, sessionId: String?, scanned: SessionScan?, out: ManagedMutation,
    ): Pair<ManagedSessionsState, String?> {
        val sc = Scope(agent, false)
        val canonical = store.resolveProject(workdir)
        return when (out) {
            is ManagedMutation.Refused -> refusal(
                requestId, workdir, sc, out.error, canonical = canonical,
                diagnostic = if (out.error == ManagedSessionErrors.SCAN_INCOMPLETE) (scanned?.completeness?.wire ?: out.reason) else null,
                readOnly = out.error == ManagedSessionErrors.STORE_CORRUPT, message = out.reason,
            ).copy(sessionId = sessionId) to null
            is ManagedMutation.Committed -> {
                val scans = if (scanned != null) mapOf(agent to scanned) else emptyMap()
                val state = project(
                    requestId, workdir, sc, canonical!!, ManagedProjectRead.Loaded(out.state), scans, 0,
                    focus = sessionId?.let { agent to it },
                )
                state.copy(
                    sessionId = sessionId,
                    changed = out.changed,
                    alreadyManaged = sessionId != null && !out.changed && out.state.member(agent, sessionId) != null,
                ) to canonical.takeIf { out.changed }
            }
        }
    }

    internal suspend fun discover(frame: DiscoverSessions): DiscoveredSessions {
        fun refuse(code: String, message: String? = null) =
            DiscoveredSessions(frame.requestId, echo(frame.workdir), frame.agent, error = code, message = message)
        if (!isValidManagedWorkdir(frame.workdir)) return refuse(ManagedSessionErrors.INVALID_WORKDIR)
        val agent = frame.agent ?: return refuse(ManagedSessionErrors.INVALID_REQUEST, "missing or unknown agent")
        if (agent !in agents) return refuse(ManagedSessionErrors.UNSUPPORTED)
        if (frame.query.length > MANAGED_DISCOVER_QUERY_MAX_CHARS) return refuse(ManagedSessionErrors.INVALID_REQUEST, "query too long")
        if ((frame.cursor?.length ?: 0) > MANAGED_CURSOR_MAX_CHARS) return refuse(ManagedSessionErrors.INVALID_REQUEST, "cursor too long")
        val canonical = store.resolveProject(frame.workdir) ?: return refuse(ManagedSessionErrors.INVALID_WORKDIR)
        val scanWd = validateWorkdir(frame.workdir) ?: return refuse(ManagedSessionErrors.INVALID_WORKDIR)
        val query = frame.query.trim()
        val cursor = frame.cursor
        val (offset, page) = if (cursor == null) 0 to 0 else parseDiscoverCursor(cursor, canonical, agent, query) ?: return refuse(ManagedSessionErrors.CURSOR_INVALID)
        if (page >= MANAGED_DISCOVER_MAX_PAGES) {
            return DiscoveredSessions(frame.requestId, frame.workdir, agent, complete = false, diagnostic = ManagedScanDiagnostics.PAGE_LIMIT)
        }
        val limit = frame.limit.let { if (it <= 0) MANAGED_DISCOVER_PAGE_DEFAULT else minOf(it, MANAGED_DISCOVER_PAGE_MAX) }
        val loaded = (store.read(frame.workdir) as? ManagedProjectRead.Loaded)?.state
        val s = cachedScan(scanWd, agent, loaded?.revision ?: 0L)
        val managed = loaded?.orderedMembers(agent)?.map { it.key.nativeSessionId }?.toSet().orEmpty()
        val needle = query.lowercase()
        val addressable = s.items.filter { isValidManagedId(it.sessionId) }
        val unaddressable = addressable.size != s.items.size // can never be imported: the scan is not fully usable
        val rows = addressable
            .filter { needle.isEmpty() || it.title.lowercase().contains(needle) || it.firstPrompt.lowercase().contains(needle) || it.sessionId.lowercase().contains(needle) }
            .sortedWith(compareByDescending<SessionSummary> { it.lastModified }.thenBy { it.sessionId })
        val base = DiscoveredSessions(frame.requestId, frame.workdir, agent, nextCursor = "x".repeat(MANAGED_CURSOR_MAX_CHARS), diagnostic = ManagedScanDiagnostics.PERMISSION_DENIED)
        val candidates = rows.drop(offset).take(limit).map {
            DiscoveredSession(
                it.sessionId, agent, truncateUtf8(it.title, MANAGED_TITLE_MAX_BYTES), truncateUtf8(it.firstPrompt, MANAGED_PROMPT_PREVIEW_MAX_BYTES),
                it.lastModified, it.messageCount, it.live, alreadyManaged = it.sessionId in managed,
            )
        }
        val pageRows = pack(frameBytes(base), candidates.asSequence()) { PocketJson.encodeToString(DiscoveredSession.serializer(), it) }
        val end = offset + pageRows.size
        val more = end < rows.size
        val pageLimited = more && page + 1 >= MANAGED_DISCOVER_MAX_PAGES
        val next = if (more && !pageLimited) discoverCursor(canonical, agent, query, end, page + 1) else null
        return DiscoveredSessions(
            frame.requestId, frame.workdir, agent,
            items = pageRows,
            nextCursor = next,
            complete = s.isComplete && !unaddressable && !more,
            diagnostic = when {
                pageLimited -> ManagedScanDiagnostics.PAGE_LIMIT
                unaddressable && s.isComplete -> ManagedScanDiagnostics.PARTIAL
                else -> s.completeness.wire
            },
        )
    }

    // ── projection ──────────────────────────────────────────────────────────────────────────────────────

    private fun preflight(requestId: String, workdir: String, sc: Scope, sessionId: String? = null): ManagedSessionsState? = when {
        !isValidManagedWorkdir(workdir) -> refusal(requestId, workdir, sc, ManagedSessionErrors.INVALID_WORKDIR)
        agents.isEmpty() -> refusal(requestId, workdir, sc, ManagedSessionErrors.UNSUPPORTED)
        sc.agent != null && sc.agent !in agents -> refusal(requestId, workdir, sc, ManagedSessionErrors.UNSUPPORTED)
        sessionId != null && !isValidManagedId(sessionId) -> refusal(requestId, workdir, sc, ManagedSessionErrors.INVALID_REQUEST)
        store.resolveProject(workdir) == null || validateWorkdir(workdir) == null -> refusal(requestId, workdir, sc, ManagedSessionErrors.INVALID_WORKDIR)
        else -> null
    }

    private suspend fun projectFresh(requestId: String?, workdir: String, sc: Scope, offset: Int): ManagedSessionsState {
        val canonical = store.resolveProject(workdir) ?: return refusal(requestId, workdir, sc, ManagedSessionErrors.INVALID_WORKDIR)
        return project(requestId, workdir, sc, canonical, store.read(workdir), emptyMap(), offset)
    }

    /**
     * Membership and order come ONLY from the store; scans contribute metadata and availability. An outside writer
     * touching a member changes its summary/live/busy fields, never its position. One page from [offset], packed to
     * the frame budget; [focus] names the member [ManagedSessionsState.entry] carries.
     */
    private suspend fun project(
        requestId: String?, workdir: String, sc: Scope, canonical: String, read: ManagedProjectRead,
        known: Map<AgentKind, SessionScan>, offset: Int, focus: Pair<AgentKind, String>? = null,
    ): ManagedSessionsState {
        val state: ManagedProjectState = when (read) {
            is ManagedProjectRead.Loaded -> read.state
            is ManagedProjectRead.Missing -> read.empty
            is ManagedProjectRead.Corrupt -> return refusal(requestId, workdir, sc, ManagedSessionErrors.STORE_CORRUPT, canonical = canonical, readOnly = true, message = read.reason)
            is ManagedProjectRead.Unreadable -> return refusal(requestId, workdir, sc, ManagedSessionErrors.STORE_UNAVAILABLE, canonical = canonical, message = read.reason)
            ManagedProjectRead.InvalidWorkdir -> return refusal(requestId, workdir, sc, ManagedSessionErrors.INVALID_WORKDIR)
        }
        val scanWd = validateWorkdir(canonical) ?: canonical
        val inScope = sc.agents(agents)
        val scans = inScope.associateWith { a -> known[a] ?: cachedScan(scanWd, a, state.revision) }
        val busy = runCatching { busyIds() }.getOrDefault(emptySet())
        val archived = runCatching { archivedIds(scanWd) }.getOrDefault(emptySet())
        val rowsByAgent = scans.mapValues { (_, s) -> s.items.associateBy { it.sessionId } }
        val ambiguous = state.ambiguousSessionIds() +
            scans.values.flatMap { s -> s.items.map { it.sessionId }.distinct() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val byKey = state.members.associateBy { it.key }
        val all = state.order.filter { it.agent in inScope && it.nativeSessionId !in archived }.mapNotNull { byKey[it] }
        fun entry(m: ManagedMember) = entryOf(m, scans.getValue(m.key.agent), rowsByAgent.getValue(m.key.agent)[m.key.nativeSessionId], scanWd, busy, m.key.nativeSessionId in ambiguous)
        val focused = focus?.let { (a, sid) -> state.member(a, sid)?.let(::entry) }
        val shell = ManagedSessionsState(
            requestId = requestId, workdir = echo(workdir), agent = sc.agent, allAgents = sc.all, canonicalWorkdir = canonical,
            revision = state.revision,
            agents = inScope.map { a -> ManagedAgentStatus(a, state.migration(a), scans.getValue(a).isComplete, scans.getValue(a).completeness.wire) },
            items = emptyList(),
            groups = runCatching { groupsFor(scanWd) }.getOrDefault(emptyList()).take(MANAGED_GROUPS_MAX),
            entry = focused,
        )
        val start = offset.coerceIn(0, all.size)
        // worst-case shell: the cursor and page flags this reply may still gain, and room for an idempotency message
        val shellBytes = frameBytes(shell.copy(nextCursor = "x".repeat(MANAGED_CURSOR_MAX_CHARS), sessionId = "x".repeat(128), changed = true, alreadyManaged = true))
        val page = pack(shellBytes, all.drop(start).asSequence().map(::entry)) { PocketJson.encodeToString(ManagedSessionEntry.serializer(), it) }
        val end = start + page.size
        return shell.copy(
            items = page,
            nextCursor = if (end < all.size) listCursor(canonical, sc, end, state.revision) else null,
            complete = end >= all.size,
        )
    }

    private fun entryOf(m: ManagedMember, s: SessionScan, row: SessionSummary?, scanWd: String, busy: Set<String>, ambiguous: Boolean): ManagedSessionEntry {
        val sid = m.key.nativeSessionId
        val group = runCatching { groupOf(scanWd, sid) }.getOrNull()
        return ManagedSessionEntry(
            sessionId = sid,
            agent = m.key.agent,
            origin = m.origin,
            createdAt = m.createdAt,
            availability = when {
                row != null -> ManagedAvailability.AVAILABLE
                s.isComplete -> ManagedAvailability.MISSING
                else -> ManagedAvailability.UNKNOWN
            },
            summary = row?.let { boundedSummary(it, m.key.agent, group, it.busy || sid in busy) },
            lastKnownTitle = if (row == null) m.lastKnownSummary?.title?.let { truncateUtf8(it, MANAGED_TITLE_MAX_BYTES) } else null,
            lastKnownModified = if (row == null) m.lastKnownSummary?.lastModified else null,
            group = group,
            groupAmbiguous = ambiguous,
        )
    }

    /** Every refusal echoes a bounded workdir and bounded free text, whatever the request carried. */
    private fun refusal(
        requestId: String?, workdir: String, sc: Scope, code: String,
        canonical: String? = null, diagnostic: String? = null, readOnly: Boolean = false, message: String? = null,
    ) = ManagedSessionsState(
        requestId = requestId, workdir = echo(workdir), agent = sc.agent, allAgents = sc.all, canonicalWorkdir = canonical,
        readOnly = readOnly, error = code,
        diagnostic = diagnostic?.let { truncateUtf8(it, MANAGED_META_MAX_BYTES) },
        message = message?.let { truncateUtf8(it, MANAGED_META_MAX_BYTES) },
    )

    private suspend fun scanOf(scanWd: String, agent: AgentKind): SessionScan =
        withContext(Dispatchers.IO) { runCatching { scan(scanWd, agent) }.getOrElse { SessionScan.failed(agent, scanWd, it) } }

    private data class ScanKey(val workdir: String, val agent: AgentKind, val revision: Long)

    private val scanCache = object : LinkedHashMap<ScanKey, Pair<Long, SessionScan>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ScanKey, Pair<Long, SessionScan>>) = size > SCAN_CACHE_MAX
    }

    /** A read's scan: reused for [scanCacheTtlMs] under the same project, agent and store revision, so paging a large
     *  history does not rescan it per page. Mutations never use this — they verify against a fresh scan. */
    private suspend fun cachedScan(scanWd: String, agent: AgentKind, revision: Long): SessionScan {
        if (scanCacheTtlMs <= 0) return scanOf(scanWd, agent)
        val key = ScanKey(scanWd, agent, revision)
        val now = clock()
        synchronized(scanCache) { scanCache[key]?.takeIf { now - it.first in 0 until scanCacheTtlMs }?.second }?.let { return it }
        val fresh = scanOf(scanWd, agent)
        synchronized(scanCache) { scanCache[key] = clock() to fresh }
        return fresh
    }

    /** Leading [candidates] whose encoded rows fit beside [shellBytes] under the budget; always at least one row. */
    private fun <T> pack(shellBytes: Int, candidates: Sequence<T>, encode: (T) -> String): List<T> {
        val out = ArrayList<T>()
        var used = shellBytes
        for (c in candidates) {
            val size = encode(c).encodeToByteArray().size + 1 // + the separating comma
            if (out.isNotEmpty() && used + size > frameBudgetBytes) break
            out += c
            used += size
        }
        return out
    }

    // ── cursors: MACed over everything they carry and everything they are bound to ─────────────────────

    private fun mac(vararg parts: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(cursorMacKey)
        return m.doFinal(macInput(parts.asList()).encodeToByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }

    private fun macMatches(expected: String, actual: String) =
        MessageDigest.isEqual(expected.encodeToByteArray(), actual.encodeToByteArray())

    private fun discoverCursor(canonical: String, agent: AgentKind, query: String, offset: Int, page: Int) =
        "v1.$offset.$page.${mac("d", canonical, agent.name, query, offset.toString(), page.toString())}"

    internal fun parseDiscoverCursor(cursor: String, canonical: String, agent: AgentKind, query: String): Pair<Int, Int>? {
        val m = DISCOVER_CURSOR.matchEntire(cursor) ?: return null
        val (offset, page) = m.groupValues[1].toIntOrNull() to m.groupValues[2].toIntOrNull()
        if (offset == null || page == null) return null
        if (!macMatches(mac("d", canonical, agent.name, query, offset.toString(), page.toString()), m.groupValues[3])) return null
        return offset to page
    }

    private fun listCursor(canonical: String, sc: Scope, offset: Int, revision: Long) =
        "m1.$offset.$revision.${mac("l", canonical, sc.tag, offset.toString(), revision.toString())}"

    private fun parseListCursor(cursor: String, canonical: String, sc: Scope, revision: Long): Int? {
        val m = LIST_CURSOR.matchEntire(cursor) ?: return null
        val offset = m.groupValues[1].toIntOrNull() ?: return null
        if (m.groupValues[2].toLongOrNull() != revision) return null
        if (!macMatches(mac("l", canonical, sc.tag, offset.toString(), revision.toString()), m.groupValues[3])) return null
        return offset
    }

    // ── creation association ────────────────────────────────────────────────────────────────────────────

    override suspend fun onNativeSession(report: NativeSessionReport) {
        if (!report.ownerCreated) return // a restricted credential's session stays in discovery for the owner to import
        if (report.agent !in agents || !isValidManagedId(report.sessionId)) return
        if (report.parentSessionId == report.sessionId) return // an in-place resume / repeated init: nothing new
        if (!report.isCurrent()) { log.info("late init from a retired process generation ignored (${report.sessionId.take(8)}…)"); return }
        if (store.resolveProject(report.workdir) == null) return
        val entry = PendingRegistration(report.agent, report.workdir, report.sessionId, report.parentSessionId, clock())
        val (outcome, canonical) = mutations.withLock {
            val recorded = addPending(entry)
            val out = apply(entry)
            if (recorded && settled(out)) removePending(entry)
            out to store.resolveProject(report.workdir)
        }
        when (outcome) {
            is ManagedMutation.Committed -> if (outcome.changed && canonical != null) broadcast(canonical, null)
            is ManagedMutation.Refused -> if (outcome.error != ManagedSessionErrors.NOT_FOUND) {
                log.warn("could not register ${report.agent} session ${report.sessionId.take(8)}… as managed: ${outcome.error}")
                val notice = PocketError(
                    REGISTER_FAILED,
                    "This session was created, but it could not be added to the managed session list (${outcome.error}).",
                    report.convoId,
                )
                // owner subscribers only — never the conversation's own fan-out, which may include restricted viewers
                for ((_, s) in subscribers) scope.launch { withTimeoutOrNull(deliveryTimeoutMs) { s.onRegisterError(notice) } }
            }
        }
    }

    /** A registration that needs no retry: committed, provably without evidence, or unable to succeed by retrying. */
    private fun settled(out: ManagedMutation) = out is ManagedMutation.Committed ||
        (out as ManagedMutation.Refused).error in SETTLED_REFUSALS

    /** Finish every registration a previous process recorded but may not have committed. Idempotent. */
    suspend fun recoverPending() = mutations.withLock {
        val doc = readPending() ?: return@withLock
        if (doc.entries.isEmpty()) return@withLock
        val remaining = doc.entries.filterNot { settled(apply(it)) }
        if (remaining.size != doc.entries.size) writePending(PendingDoc(entries = remaining))
        log.info("recovered ${doc.entries.size - remaining.size} pending managed registration(s), ${remaining.size} still pending")
    }

    private fun apply(e: PendingRegistration): ManagedMutation =
        if (e.parentSessionId == null) store.recordCreated(e.workdir, e.agent, e.sessionId)
        else store.recordDerived(e.workdir, e.agent, e.parentSessionId, e.sessionId)

    internal fun pendingEntries(): List<PendingRegistration>? = readPending()?.entries

    /** null = the file exists but cannot be trusted; it is then never rewritten. */
    private fun readPending(): PendingDoc? {
        if (!pendingFile.exists()) return if (Files.notExists(pendingFile.toPath())) PendingDoc() else null
        return runCatching { PENDING_JSON.decodeFromString<PendingDoc>(pendingFile.readText()) }.getOrNull()
            ?.takeIf { it.schemaVersion == 1 && it.entries.size <= PENDING_MAX }
            .also { if (it == null) log.warn("pending managed registrations file unreadable — left untouched") }
    }

    private fun writePending(doc: PendingDoc): Boolean {
        // security review R4: created 0700 up front, never a umask-wide window before the chmod below
        pendingFile.parentFile?.let { if (!ManagedSessionStore.createPrivateDirectory(it) || !ManagedSessionStore.ensurePrivateDirectory(it)) return false }
        val ok = files.replace(pendingFile, PENDING_JSON.encodeToString(doc).encodeToByteArray()) == PinStoreWrite.Durable
        pendingFile.parentFile?.let { ManagedSessionStore.ensurePrivateDirectory(it) }
        return ok
    }

    private fun addPending(e: PendingRegistration): Boolean {
        val doc = readPending() ?: return false
        if (doc.entries.any { it.same(e) }) return true
        if (doc.entries.size >= PENDING_MAX) return false
        return writePending(doc.copy(entries = doc.entries + e))
    }

    private fun removePending(e: PendingRegistration) {
        val doc = readPending() ?: return
        if (doc.entries.none { it.same(e) }) return
        writePending(doc.copy(entries = doc.entries.filterNot { it.same(e) }))
    }

    private fun PendingRegistration.same(o: PendingRegistration) = agent == o.agent && workdir == o.workdir && sessionId == o.sessionId

    companion object {
        const val REGISTER_FAILED = "managed_register_failed"
        const val DELIVERY_TIMEOUT_MS = 5_000L
        const val SCAN_CACHE_TTL_MS = 10_000L
        const val MAX_READS_PER_CONNECTION = 2
        private const val SCAN_CACHE_MAX = 32
        private const val QUEUE_CAPACITY = 64
        private const val PENDING_MAX = 256
        private val PENDING_JSON = Json { ignoreUnknownKeys = false; encodeDefaults = true }
        private val SETTLED_REFUSALS = setOf(
            ManagedSessionErrors.NOT_FOUND, ManagedSessionErrors.INVALID_WORKDIR, ManagedSessionErrors.UNSUPPORTED,
            ManagedSessionErrors.INVALID_REQUEST, ManagedSessionErrors.CAPACITY,
        )
        private val DISCOVER_CURSOR = Regex("^v1\\.(\\d{1,9})\\.(\\d{1,4})\\.([0-9a-f]{24})$")
        private val LIST_CURSOR = Regex("^m1\\.(\\d{1,9})\\.(\\d{1,18})\\.([0-9a-f]{24})$")

        fun create(
            root: File, scope: CoroutineScope, registry: SessionRegistry, dirs: DirectoryService, registered: Set<AgentKind>,
        ) = ManagedSessionService(
            store = ManagedSessionStore(root),
            scope = scope,
            scan = registry::scanSessions,
            validateWorkdir = { dirs.validateWorkdir(it)?.toString() },
            agents = registered,
            pendingFile = File(root, "pending-registrations.json"),
            archivedIds = { SessionArchive.archivedIds(it) },
            busyIds = { registry.busySessionIds() },
        )

        /** The exact bytes a cursor MAC covers: every field length-prefixed (`<length>:<field>`), so no field content -
         *  a query holding separators, digits or colons - can shift a boundary and let one cursor verify for another
         *  (project, agent, query, offset, page) tuple (security review R1). */
        internal fun macInput(parts: List<String>): String = parts.joinToString("") { "${it.length}:$it" }

        /** The request's workdir as a reply may echo it: never longer than the contract bound. */
        internal fun echo(workdir: String) = if (workdir.length <= MANAGED_WORKDIR_MAX_CHARS) workdir else workdir.take(MANAGED_WORKDIR_MAX_CHARS)

        /** A summary row as the managed surface may send it: no repeated project path, text cut by UTF-8 bytes,
         *  ids that do not validate dropped rather than truncated. */
        internal fun boundedSummary(row: SessionSummary, agent: AgentKind, group: String?, busy: Boolean) = row.copy(
            title = truncateUtf8(row.title, MANAGED_TITLE_MAX_BYTES),
            firstPrompt = truncateUtf8(row.firstPrompt, MANAGED_PROMPT_PREVIEW_MAX_BYTES),
            cwd = "",
            gitBranch = row.gitBranch?.let { truncateUtf8(it, MANAGED_META_MAX_BYTES) },
            version = row.version?.let { truncateUtf8(it, MANAGED_META_MAX_BYTES) },
            model = row.model?.let { truncateUtf8(it, MANAGED_META_MAX_BYTES) },
            group = group?.takeIf { isValidManagedId(it) },
            forkedFrom = row.forkedFrom?.takeIf { isValidManagedId(it) },
            rewindOf = row.rewindOf?.takeIf { isValidManagedId(it) },
            busy = busy,
            agent = agent,
        )

        /** Encoded size of [frame] inside an envelope, with headroom for a real envelope id / timestamp. */
        internal fun frameBytes(frame: ToPhone): Int =
            PocketJson.encodeToString(Envelope("x".repeat(20), Long.MAX_VALUE, body = frame)).encodeToByteArray().size + 64

        /** Drop every row (and status / entry) whose agent the receiving connection cannot decode. */
        fun filterAgents(state: ManagedSessionsState, allows: (AgentKind) -> Boolean): ManagedSessionsState = state.copy(
            agents = state.agents?.filter { s -> s.agent?.let(allows) == true },
            items = state.items?.filter { e -> e.agent?.let(allows) == true },
            entry = state.entry?.takeIf { e -> e.agent?.let(allows) == true },
        )

        fun filterAgents(page: DiscoveredSessions, allows: (AgentKind) -> Boolean): DiscoveredSessions =
            page.copy(items = page.items.filter { d -> d.agent?.let(allows) == true })
    }
}
