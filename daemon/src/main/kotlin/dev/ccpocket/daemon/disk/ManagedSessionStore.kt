package dev.ccpocket.daemon.disk

import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.pins.DurablePinFiles
import dev.ccpocket.daemon.pins.PinStoreWrite
import dev.ccpocket.daemon.session.SessionScan
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.MANAGED_SESSIONS_MAX
import dev.ccpocket.protocol.MANAGED_TITLE_MAX_BYTES
import dev.ccpocket.protocol.truncateUtf8
import dev.ccpocket.protocol.ManagedMigrationState
import dev.ccpocket.protocol.ManagedSessionErrors
import dev.ccpocket.protocol.ManagedSessionOrigin
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.isValidManagedId
import dev.ccpocket.protocol.isValidManagedWorkdir
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Agents whose sessions the managed list covers in this build (issue #360). Other backends keep the legacy
 *  scan-driven list and are never filtered by it. */
val MANAGED_SESSION_AGENTS: Set<AgentKind> = setOf(AgentKind.CLAUDE, AgentKind.CODEX)

/** A native session's identity on this daemon: which backend, which canonical project, which native id. */
@Serializable
data class ManagedSessionKey(
    val agent: AgentKind,
    val canonicalWorkdir: String,
    val nativeSessionId: String,
)

/** Display fallback for a member whose native record is currently unavailable. Never a path or a permission. */
@Serializable
data class LastKnownSummary(
    val title: String = "",
    val lastModified: Long = 0,
    val messageCount: Int = 0,
)

@Serializable
data class ManagedMember(
    val key: ManagedSessionKey,
    val origin: ManagedSessionOrigin,
    val createdAt: Long,
    val lastKnownSummary: LastKnownSummary? = null,
)

/**
 * One project's durable managed state (issue #360). [members] and [order] describe the same key set: [order] is
 * the persistent display order (newest registration first by default) and is never recomputed from transcript
 * mtimes. [perAgentMigration] missing an agent means UNINITIALIZED for it.
 */
@Serializable
data class ManagedProjectState(
    val schemaVersion: Int = SCHEMA_VERSION,
    val revision: Long = 0,
    val canonicalWorkdir: String,
    val perAgentMigration: Map<AgentKind, ManagedMigrationState> = emptyMap(),
    val members: List<ManagedMember> = emptyList(),
    val order: List<ManagedSessionKey> = emptyList(),
) {
    fun migration(agent: AgentKind): ManagedMigrationState = perAgentMigration[agent] ?: ManagedMigrationState.UNINITIALIZED

    /** [agent]'s members in persisted order. */
    fun orderedMembers(agent: AgentKind): List<ManagedMember> {
        val byKey = members.associateBy { it.key }
        return order.filter { it.agent == agent }.mapNotNull { byKey[it] }
    }

    fun member(agent: AgentKind, sessionId: String): ManagedMember? =
        members.firstOrNull { it.key.agent == agent && it.key.nativeSessionId == sessionId }

    /** Native ids registered under more than one agent: a bare-id group assignment cannot be attributed to one
     *  of them, so projections mark them ambiguous instead of guessing. */
    fun ambiguousSessionIds(): Set<String> =
        members.groupBy { it.key.nativeSessionId }.filterValues { rows -> rows.map { it.key.agent }.distinct().size > 1 }.keys

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** What reading one project's state found. Only [Missing] and [Loaded] may ever be mutated. */
sealed interface ManagedProjectRead {
    /** The workdir fails validation / canonicalization / scope; nothing was read. */
    data object InvalidWorkdir : ManagedProjectRead

    /** No file yet: every agent is UNINITIALIZED and there are no members. */
    data class Missing(val canonicalWorkdir: String) : ManagedProjectRead {
        val empty: ManagedProjectState get() = ManagedProjectState(canonicalWorkdir = canonicalWorkdir)
    }

    data class Loaded(val state: ManagedProjectState) : ManagedProjectRead

    /** The file exists but this build cannot vouch for it (undecodable, unknown schema, broken invariant). It is
     *  preserved untouched; the project is read-only until someone repairs or removes it. */
    data class Corrupt(val canonicalWorkdir: String, val reason: String) : ManagedProjectRead

    /** The file could not be read, or a previous write's outcome is unknown. Nothing may be committed. */
    data class Unreadable(val canonicalWorkdir: String, val reason: String) : ManagedProjectRead
}

/** The outcome of a mutation. Only [Committed] may be acknowledged; its [state] is durable (or unchanged). */
sealed interface ManagedMutation {
    data class Committed(val state: ManagedProjectState, val changed: Boolean) : ManagedMutation

    /** Nothing was committed. [error] is one of [ManagedSessionErrors]. */
    data class Refused(val error: String, val reason: String? = null) : ManagedMutation
}

/**
 * The daemon's managed session list (issue #360): one owner-only JSON file per canonical project under the
 * daemon's private app directory (beside identity.json), named by a hash of the canonical path so no path
 * string steers a filename.
 *
 * Invariants this class keeps:
 *  - every write is the crash-atomic [DurablePinFiles.replace] (0600 temp + fsync + ATOMIC_MOVE + dir sync); a
 *    mutation returns [ManagedMutation.Committed] only after [PinStoreWrite.Durable]. An unchanged-failure is a
 *    plain refusal; an indeterminate one poisons the project in memory until a new instance re-reads the file.
 *  - a corrupt / unknown-schema / unreadable file is reported and never overwritten — no empty state is written
 *    back over it.
 *  - migration commits members + order + READY for ONE agent in ONE write, only from a COMPLETE scan, and a READY
 *    agent never absorbs later scans. Import is idempotent; remove only drops the registration. A member whose
 *    native record vanished stays (availability is the projection's call).
 *  - SessionGroups / SessionArchive / SpawnedSessions are neither read as truth nor rewritten here.
 */
class ManagedSessionStore internal constructor(
    val root: File,
    private val files: DurablePinFiles,
    private val canonicalize: (String) -> String?,
    private val clock: () -> Long,
) {
    constructor(root: File = defaultRoot()) : this(root, DurablePinFiles(), ::canonicalManagedWorkdir, System::currentTimeMillis)

    /** Canonical projects whose last write ended indeterminate: refused until restart. */
    private val poisoned = HashSet<String>()

    /** The daemon's canonical identity of [workdir], or null when it is malformed, not an existing readable
     *  absolute directory, or — when [scopeRoots] is given — not equal to / inside one of those canonical roots. */
    fun resolveProject(workdir: String, scopeRoots: List<String>? = null): String? {
        val canonical = canonicalize(workdir) ?: return null
        if (scopeRoots == null) return canonical
        return canonical.takeIf { c ->
            scopeRoots.mapNotNull(canonicalize).any { r -> c == r || c.startsWith(if (r.endsWith("/")) r else "$r/") }
        }
    }

    @Synchronized
    fun read(workdir: String, scopeRoots: List<String>? = null): ManagedProjectRead {
        val canonical = resolveProject(workdir, scopeRoots) ?: return ManagedProjectRead.InvalidWorkdir
        return readCanonical(canonical)
    }

    /** Register a session Pairlet created, once its backend reported the native id. Idempotent; an existing
     *  member keeps its original origin and position. */
    @Synchronized
    fun recordCreated(workdir: String, agent: AgentKind, sessionId: String, summary: SessionSummary? = null): ManagedMutation =
        mutate(workdir, agent, sessionId) { state, canonical ->
            if (state.member(agent, sessionId) != null) return@mutate ManagedMutation.Committed(state, changed = false)
            insertTop(state, ManagedMember(ManagedSessionKey(agent, canonical, sessionId), ManagedSessionOrigin.CREATED_HERE, clock(), summary?.let(::lastKnown)))
        }

    /** Register [sessionId], a branch this daemon observed its backend mint from managed member [parentSessionId]
     *  (fork / heal / rewind), directly above the parent. A parent that is not a member proves nothing: NOT_FOUND. */
    @Synchronized
    fun recordDerived(workdir: String, agent: AgentKind, parentSessionId: String, sessionId: String): ManagedMutation =
        mutate(workdir, agent, sessionId) { state, canonical ->
            if (!isValidManagedId(parentSessionId)) return@mutate ManagedMutation.Refused(ManagedSessionErrors.INVALID_REQUEST)
            if (state.member(agent, sessionId) != null) return@mutate ManagedMutation.Committed(state, changed = false)
            val parent = state.member(agent, parentSessionId)
                ?: return@mutate ManagedMutation.Refused(ManagedSessionErrors.NOT_FOUND, "parent not managed")
            if (state.members.count { it.key.agent == agent } >= MANAGED_SESSIONS_MAX) return@mutate ManagedMutation.Refused(ManagedSessionErrors.CAPACITY)
            val member = ManagedMember(ManagedSessionKey(agent, canonical, sessionId), ManagedSessionOrigin.CREATED_HERE, clock(), parent.lastKnownSummary)
            val at = state.order.indexOf(parent.key)
            ManagedMutation.Committed(
                state.copy(members = state.members + member, order = state.order.take(at) + member.key + state.order.drop(at)),
                changed = true,
            )
        }

    /**
     * Explicitly import [sessionId]. [scan] must be the daemon's own fresh scan of (agent, workdir): a row found in
     * it proves the session exists (even in a PARTIAL scan); absence proves it does not only when the scan is
     * COMPLETE. Importing an existing member commits nothing and reports `changed = false`.
     */
    @Synchronized
    fun import(workdir: String, agent: AgentKind, sessionId: String, scan: SessionScan): ManagedMutation =
        mutate(workdir, agent, sessionId) { state, canonical ->
            if (state.member(agent, sessionId) != null) return@mutate ManagedMutation.Committed(state, changed = false)
            scanMismatch(scan, agent, canonical)?.let { return@mutate it }
            val row = scan.items.firstOrNull { it.sessionId == sessionId }
                ?: return@mutate ManagedMutation.Refused(
                    if (scan.isComplete) ManagedSessionErrors.NOT_FOUND else ManagedSessionErrors.SCAN_INCOMPLETE,
                    scan.completeness.wire,
                )
            insertTop(state, ManagedMember(ManagedSessionKey(agent, canonical, sessionId), ManagedSessionOrigin.EXPLICIT_IMPORT, clock(), lastKnown(row)))
        }

    /** Drop [sessionId]'s registration only. Removing a non-member commits nothing. */
    @Synchronized
    fun remove(workdir: String, agent: AgentKind, sessionId: String): ManagedMutation =
        mutate(workdir, agent, sessionId) { state, _ ->
            val member = state.member(agent, sessionId) ?: return@mutate ManagedMutation.Committed(state, changed = false)
            ManagedMutation.Committed(
                state.copy(members = state.members - member, order = state.order - member.key),
                changed = true,
            )
        }

    /**
     * First migration of [agent] under [workdir]: adopt every session of a COMPLETE [scan] as LEGACY_ADOPTED, in
     * [visibleOrder] (the legacy list's session ids as the user saw them; rows it omits follow in scan order), with
     * members registered earlier kept. Members + order + READY land in one durable write; any failure leaves the
     * agent UNINITIALIZED. Already READY: answered as is, absorbing nothing.
     */
    @Synchronized
    fun migrate(workdir: String, agent: AgentKind, scan: SessionScan, visibleOrder: List<String>? = null): ManagedMutation =
        mutate(workdir, agent, sessionId = null) { state, canonical ->
            if (state.migration(agent) == ManagedMigrationState.READY) return@mutate ManagedMutation.Committed(state, changed = false)
            scanMismatch(scan, agent, canonical)?.let { return@mutate it }
            if (!scan.isComplete) return@mutate ManagedMutation.Refused(ManagedSessionErrors.SCAN_INCOMPLETE, scan.completeness.wire)
            // a row this store cannot key would silently vanish from the user's list: stay on the legacy list instead
            if (scan.items.any { !isValidManagedId(it.sessionId) }) {
                return@mutate ManagedMutation.Refused(ManagedSessionErrors.SCAN_INCOMPLETE, "unkeyable session id")
            }
            val rows = scan.items.distinctBy { it.sessionId }.associateBy { it.sessionId }
            val rank = visibleOrder.orEmpty().withIndex().associate { (i, id) -> id to i }
            val scanOrder = rows.keys.withIndex().associate { (i, id) -> id to i }
            val adoptedIds = rows.keys.sortedWith(compareBy({ rank[it] ?: Int.MAX_VALUE }, { scanOrder.getValue(it) }))
            val existing = state.orderedMembers(agent)
            val existingIds = existing.map { it.key.nativeSessionId }.toSet()
            val now = clock()
            val adopted = adoptedIds.filter { it !in existingIds }.map { id ->
                ManagedMember(ManagedSessionKey(agent, canonical, id), ManagedSessionOrigin.LEGACY_ADOPTED, now, lastKnown(rows.getValue(id)))
            }
            val adoptedById = adopted.associateBy { it.key.nativeSessionId }
            val existingById = existing.associateBy { it.key.nativeSessionId }
            // visible legacy order first (existing members that were visible keep their visible slot), then
            // registered members the scan did not show, in their previous relative order
            val agentOrder = adoptedIds.map { id -> (existingById[id] ?: adoptedById.getValue(id)).key } +
                existing.filter { it.key.nativeSessionId !in rows }.map { it.key }
            if (agentOrder.size > MANAGED_SESSIONS_MAX) return@mutate ManagedMutation.Refused(ManagedSessionErrors.CAPACITY)
            ManagedMutation.Committed(
                state.copy(
                    perAgentMigration = state.perAgentMigration + (agent to ManagedMigrationState.READY),
                    members = state.members + adopted,
                    order = agentOrder + state.order.filter { it.agent != agent },
                ),
                changed = true,
            )
        }

    // ── internals ────────────────────────────────────────────────────────────────────────────────────────

    private fun insertTop(state: ManagedProjectState, member: ManagedMember): ManagedMutation {
        if (state.members.count { it.key.agent == member.key.agent } >= MANAGED_SESSIONS_MAX) {
            return ManagedMutation.Refused(ManagedSessionErrors.CAPACITY)
        }
        return ManagedMutation.Committed(state.copy(members = state.members + member, order = listOf(member.key) + state.order), changed = true)
    }

    private fun scanMismatch(scan: SessionScan, agent: AgentKind, canonical: String): ManagedMutation.Refused? = when {
        scan.agent != agent || scan.items.any { it.agent != null && it.agent != agent } ->
            ManagedMutation.Refused(ManagedSessionErrors.SCAN_INCOMPLETE, "scan of another agent")
        canonicalize(scan.workdir) != canonical ->
            ManagedMutation.Refused(ManagedSessionErrors.SCAN_INCOMPLETE, "scan of another project")
        else -> null
    }

    private inline fun mutate(
        workdir: String,
        agent: AgentKind,
        sessionId: String?,
        change: (ManagedProjectState, String) -> ManagedMutation,
    ): ManagedMutation {
        if (agent !in MANAGED_SESSION_AGENTS) return ManagedMutation.Refused(ManagedSessionErrors.UNSUPPORTED)
        if (sessionId != null && !isValidManagedId(sessionId)) return ManagedMutation.Refused(ManagedSessionErrors.INVALID_REQUEST)
        val canonical = resolveProject(workdir) ?: return ManagedMutation.Refused(ManagedSessionErrors.INVALID_WORKDIR)
        val current = when (val r = readCanonical(canonical)) {
            is ManagedProjectRead.Loaded -> r.state
            is ManagedProjectRead.Missing -> r.empty
            is ManagedProjectRead.Corrupt -> return ManagedMutation.Refused(ManagedSessionErrors.STORE_CORRUPT, r.reason)
            is ManagedProjectRead.Unreadable -> return ManagedMutation.Refused(ManagedSessionErrors.STORE_UNAVAILABLE, r.reason)
            ManagedProjectRead.InvalidWorkdir -> return ManagedMutation.Refused(ManagedSessionErrors.INVALID_WORKDIR)
        }
        val outcome = change(current, canonical)
        if (outcome !is ManagedMutation.Committed || !outcome.changed) return outcome
        val next = outcome.state.copy(revision = current.revision + 1)
        invariantViolation(next, canonical)?.let {
            log.warn("refusing to persist a managed state that breaks its own invariant: $it")
            return ManagedMutation.Refused(ManagedSessionErrors.STORE_UNAVAILABLE, it)
        }
        // the store directory maps project paths to session ids: owner-only. Tightened before a write into an existing
        // directory, and after one that had to create it (creation itself is left to the durable writer, which syncs it)
        // security review R4: a missing directory is created 0700 up front (never a umask-wide window before a chmod)
        if (!createPrivateDirectory(root) || !ensurePrivateDirectory(root)) return ManagedMutation.Refused(ManagedSessionErrors.STORE_UNAVAILABLE, "store directory not private")
        val written = files.replace(fileFor(canonical), JSON.encodeToString(next).encodeToByteArray())
        if (written == PinStoreWrite.Durable && !ensurePrivateDirectory(root)) log.warn("could not restrict ${root.name} to its owner")
        return when (written) {
            PinStoreWrite.Durable -> ManagedMutation.Committed(next, changed = true)
            PinStoreWrite.UnchangedFailure -> ManagedMutation.Refused(ManagedSessionErrors.STORE_UNAVAILABLE, "write failed")
            PinStoreWrite.IndeterminateFailure -> {
                poisoned += canonical
                ManagedMutation.Refused(ManagedSessionErrors.STORE_UNAVAILABLE, "write outcome unknown")
            }
        }
    }

    private fun readCanonical(canonical: String): ManagedProjectRead {
        if (canonical in poisoned) return ManagedProjectRead.Unreadable(canonical, "previous write outcome unknown")
        val file = fileFor(canonical)
        if (!file.exists()) {
            // exists() is false both for "absent" and "cannot tell": only a provable absence reads as empty
            return if (Files.notExists(file.toPath())) ManagedProjectRead.Missing(canonical)
            else ManagedProjectRead.Unreadable(canonical, "existence undeterminable")
        }
        val text = try { file.readText() } catch (e: Exception) {
            return ManagedProjectRead.Unreadable(canonical, e::class.simpleName ?: "io")
        }
        val tree = try { JSON.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }
            ?: return ManagedProjectRead.Corrupt(canonical, "undecodable")
        val version = (tree["schemaVersion"] as? JsonPrimitive)?.intOrNull
        if (version != ManagedProjectState.SCHEMA_VERSION) return ManagedProjectRead.Corrupt(canonical, "unsupported schema $version")
        val state = try { JSON.decodeFromJsonElement(ManagedProjectState.serializer(), tree) } catch (e: Exception) {
            return ManagedProjectRead.Corrupt(canonical, "undecodable (${e::class.simpleName})")
        }
        return invariantViolation(state, canonical)?.let { ManagedProjectRead.Corrupt(canonical, it) } ?: ManagedProjectRead.Loaded(state)
    }

    internal fun fileFor(canonical: String): File = File(root, "${sha256(canonical)}.json")

    companion object {
        private val log = logger("ManagedSessionStore")

        /** Strict on purpose: an unknown key or enum value means a newer build wrote it — read-only, not rewritten. */
        private val JSON = Json { ignoreUnknownKeys = false; encodeDefaults = true; coerceInputValues = false; explicitNulls = true }

        fun defaultRoot(): File = File(Identity.defaultPath().parentFile, "managed-sessions")

        /**
         * Create [dir] (and missing parents) with owner-only 0700 permissions AT creation, so it never exists with
         * umask-wide permissions (security review R4). Where the filesystem has no POSIX attributes it is created
         * plainly. The new entry's parent directory is synced so the creation is durable before the first write (the
         * durable writer only syncs directories IT creates). True when [dir] exists afterwards.
         */
        fun createPrivateDirectory(dir: File): Boolean = runCatching {
            val path = dir.toPath()
            if (Files.isDirectory(path)) return@runCatching true
            try {
                Files.createDirectories(
                    path,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")),
                )
            } catch (_: UnsupportedOperationException) {
                Files.createDirectories(path)
            }
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
                path.toAbsolutePath().parent?.let { parent ->
                    java.nio.channels.FileChannel.open(parent, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
                }
            }
            Files.isDirectory(path)
        }.getOrDefault(false)

        /** Make [dir] owner-only (0700) where the filesystem has POSIX permissions. False when it cannot be ensured. */
        fun ensurePrivateDirectory(dir: File): Boolean = runCatching {
            val path = dir.toPath()
            if (!Files.isDirectory(path)) return@runCatching false
            if (Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java) != null) {
                val ownerOnly = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
                if (Files.getPosixFilePermissions(path) != ownerOnly) Files.setPosixFilePermissions(path, ownerOnly)
            }
            true
        }.getOrDefault(false)

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.encodeToByteArray()).joinToString("") { "%02x".format(it) }

        private fun lastKnown(s: SessionSummary) = LastKnownSummary(truncateUtf8(s.title, MANAGED_TITLE_MAX_BYTES), s.lastModified, s.messageCount)

        internal fun invariantViolation(state: ManagedProjectState, canonical: String): String? {
            val keys = state.members.map { it.key }
            return when {
                state.schemaVersion != ManagedProjectState.SCHEMA_VERSION -> "unsupported schema ${state.schemaVersion}"
                state.canonicalWorkdir != canonical -> "state names another project"
                state.revision < 0 -> "negative revision"
                state.perAgentMigration.keys.any { it !in MANAGED_SESSION_AGENTS } -> "migration state for an unmanaged agent"
                keys.any { it.agent !in MANAGED_SESSION_AGENTS || it.canonicalWorkdir != canonical || !isValidManagedId(it.nativeSessionId) } -> "invalid member key"
                keys.toSet().size != keys.size -> "duplicate member"
                state.order.toSet().size != state.order.size || state.order.toSet() != keys.toSet() -> "order does not match members"
                MANAGED_SESSION_AGENTS.any { a -> keys.count { it.agent == a } > MANAGED_SESSIONS_MAX } -> "too many members"
                else -> null
            }
        }
    }
}

/**
 * Canonical identity of a client-supplied project path, with the same rules the daemon already applies to a
 * workdir ([DirectoryService.validateWorkdir]: tilde expanded here, real path, existing readable directory) and
 * the same key normalization as [ProjectPaths.canonicalKey] for an existing directory. Relative paths are refused
 * rather than resolved against the daemon's own working directory.
 */
fun canonicalManagedWorkdir(workdir: String): String? {
    if (!isValidManagedWorkdir(workdir)) return null
    val parsed = runCatching { Path.of(ProjectPaths.expandTilde(workdir)) }.getOrNull() ?: return null
    if (!parsed.isAbsolute) return null
    val real = runCatching { parsed.toRealPath() }.getOrNull() ?: return null
    if (!Files.isDirectory(real) || !Files.isReadable(real)) return null
    return ProjectPaths.normCwd(real.toString())
}
