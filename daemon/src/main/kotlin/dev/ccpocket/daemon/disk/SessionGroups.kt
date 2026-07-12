package dev.ccpocket.daemon.disk

import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.SessionGroup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * Optional one-level GROUPING of a project's sessions (issue #119): `project → group (optional) → session`,
 * scheme A (a session belongs to 0 or 1 group). The metadata lives HERE, on the daemon, so it is consistent
 * across every paired client (the phone/desktop hold no group truth of their own) and survives app reinstalls.
 *
 * Membership is keyed on the backend-agnostic [SessionGroup] id ↔ `sessionId` — Claude and Codex sessions
 * group identically, nothing couples to a `~/.claude` transcript path. Groups are partitioned by
 * [ProjectPaths.dirKey] so one file holds every project's groups:
 *
 *   { "<dirKey>": { "groups": [{id,name,order}, …], "assign": { "<sessionId>": "<groupId>" } } }
 *
 * Deleting a group drops its [ProjectGroups.assign] entries (its sessions fall back to "ungrouped"), never the
 * sessions themselves. Orphan assigns (a session that no longer exists) are harmless and reaped lazily — a stale
 * `sessionId → groupId` entry simply never matches a live summary, so we don't sweep them.
 *
 * Persisted like [SpawnedSessions]: owner-only file next to `identity.json`, atomic tmp+rename, all access
 * `@Synchronized`. Reads are served from an mtime-guarded in-memory snapshot so enriching a session list
 * (one [groupOf] per row) doesn't reparse the file per session.
 */
object SessionGroups {
    private val log = logger("SessionGroups")

    private const val MAX_GROUPS_PER_PROJECT = 100
    private const val MAX_PROJECTS = 1000
    private const val MAX_NAME_LEN = 60

    // groupIds are daemon-minted; sessionIds ride in from the wire — validate both before they steer a
    // stored map key (a hostile id must never influence the rewrite path — same guard as SpawnedSessions).
    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** One project's groups + its session→group assignments. */
    @Serializable
    private data class ProjectGroups(
        val groups: List<SessionGroup> = emptyList(),
        val assign: Map<String, String> = emptyMap(),
    )

    fun defaultFile(): File = File(Identity.defaultPath().parentFile, "session-groups.json")

    // mtime-guarded snapshot: skip the reparse when neither the file nor the target path changed. Keyed on the
    // file so a test's temp file and the prod default can't read each other's cache.
    private var cacheFile: File? = null
    private var cacheMtime: Long = -1
    private var cache: Map<String, ProjectGroups> = emptyMap()

    @Synchronized
    private fun load(file: File): Map<String, ProjectGroups> {
        val mtime = if (file.exists()) file.lastModified() else 0L
        if (file == cacheFile && mtime == cacheMtime) return cache
        val parsed =
            if (file.exists()) runCatching { json.decodeFromString<Map<String, ProjectGroups>>(file.readText()) }
                .getOrElse { log.warn("groups read failed (${it.message}) — starting empty"); emptyMap() }
            else emptyMap()
        cacheFile = file; cacheMtime = mtime; cache = parsed
        return parsed
    }

    @Synchronized
    private fun persist(file: File, data: Map<String, ProjectGroups>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(data))
            // owner-only, like identity.json — the file maps project paths to session ids
            runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
            runCatching { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                .recoverCatching { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                .getOrThrow()
            cacheFile = file; cacheMtime = file.lastModified(); cache = data
        }.onFailure { log.warn("groups write failed: ${it.message}") }
    }

    private fun keyOf(workdir: String) = ProjectPaths.dirKey(workdir)
    private fun newGroupId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    // ── reads ────────────────────────────────────────────────────────────────

    /** [workdir]'s groups, ordered by insertion. Empty when the project has none. */
    @Synchronized
    fun groupsFor(workdir: String, file: File = defaultFile()): List<SessionGroup> =
        load(file)[keyOf(workdir)]?.groups?.sortedBy { it.order } ?: emptyList()

    /** The group [sessionId] belongs to under [workdir], or null (ungrouped / unknown). */
    @Synchronized
    fun groupOf(workdir: String, sessionId: String, file: File = defaultFile()): String? =
        load(file)[keyOf(workdir)]?.assign?.get(sessionId)

    // ── writes ───────────────────────────────────────────────────────────────

    /** Create a new group under [workdir]; returns it (with a freshly minted id + trailing order), or null if
     *  [name] is blank or a cap is hit. */
    @Synchronized
    fun create(workdir: String, name: String, file: File = defaultFile()): SessionGroup? {
        val nm = name.trim().take(MAX_NAME_LEN)
        if (nm.isEmpty()) return null
        val key = keyOf(workdir)
        val data = load(file).toMutableMap()
        if (key !in data && data.size >= MAX_PROJECTS) return null
        val proj = data[key] ?: ProjectGroups()
        if (proj.groups.size >= MAX_GROUPS_PER_PROJECT) return null
        val order = (proj.groups.maxOfOrNull { it.order } ?: -1) + 1
        val group = SessionGroup(id = newGroupId(), name = nm, order = order)
        data[key] = proj.copy(groups = proj.groups + group)
        persist(file, data)
        return group
    }

    /** Rename an existing group. Returns false if [name] is blank or the group doesn't exist. */
    @Synchronized
    fun rename(workdir: String, groupId: String, name: String, file: File = defaultFile()): Boolean {
        if (!ID.matches(groupId)) return false
        val nm = name.trim().take(MAX_NAME_LEN)
        if (nm.isEmpty()) return false
        val key = keyOf(workdir)
        val data = load(file).toMutableMap()
        val proj = data[key] ?: return false
        if (proj.groups.none { it.id == groupId }) return false
        data[key] = proj.copy(groups = proj.groups.map { if (it.id == groupId) it.copy(name = nm) else it })
        persist(file, data)
        return true
    }

    /** Delete a group and drop every assignment into it (its sessions fall back to ungrouped; the sessions
     *  themselves are untouched). Returns false if the group didn't exist. */
    @Synchronized
    fun delete(workdir: String, groupId: String, file: File = defaultFile()): Boolean {
        if (!ID.matches(groupId)) return false
        val key = keyOf(workdir)
        val data = load(file).toMutableMap()
        val proj = data[key] ?: return false
        if (proj.groups.none { it.id == groupId }) return false
        data[key] = proj.copy(
            groups = proj.groups.filterNot { it.id == groupId },
            assign = proj.assign.filterValues { it != groupId },
        )
        persist(file, data)
        return true
    }

    /** Move [sessionId] into [groupId] (null = out of any group). Returns false on a bad id or an assign into a
     *  group that doesn't exist. Assigning is idempotent. */
    @Synchronized
    fun assign(workdir: String, sessionId: String, groupId: String?, file: File = defaultFile()): Boolean {
        if (!ID.matches(sessionId)) return false
        val key = keyOf(workdir)
        val data = load(file).toMutableMap()
        val proj = data[key] ?: ProjectGroups()
        val assign = proj.assign.toMutableMap()
        if (groupId == null) {
            if (assign.remove(sessionId) == null) return true // already ungrouped — nothing to persist, but not an error
        } else {
            if (!ID.matches(groupId)) return false
            if (proj.groups.none { it.id == groupId }) return false
            if (assign[sessionId] == groupId) return true // no-op
            assign[sessionId] = groupId
        }
        data[key] = proj.copy(assign = assign)
        persist(file, data)
        return true
    }

    /** Copy [fromSid]'s group membership onto [toSid] — used when a session FORKS (heal / take-over / conditional
     *  fork mint a new sessionId) so the branch inherits its parent's group. No-op (false) when the parent had
     *  none or the target group has since been deleted. */
    @Synchronized
    fun inherit(workdir: String, fromSid: String, toSid: String, file: File = defaultFile()): Boolean {
        if (!ID.matches(fromSid) || !ID.matches(toSid) || fromSid == toSid) return false
        val key = keyOf(workdir)
        val data = load(file).toMutableMap()
        val proj = data[key] ?: return false
        val gid = proj.assign[fromSid] ?: return false
        if (proj.groups.none { it.id == gid }) return false
        if (proj.assign[toSid] == gid) return true
        data[key] = proj.copy(assign = proj.assign + (toSid to gid))
        persist(file, data)
        return true
    }
}
