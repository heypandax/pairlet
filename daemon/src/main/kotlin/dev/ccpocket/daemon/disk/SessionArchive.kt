package dev.ccpocket.daemon.disk

import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.util.logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Which sessions the user has ARCHIVED — filed away from the regular lists without being deleted (issue #202).
 * Daemon-side truth like [SessionGroups]: archiving on the phone must hide the row on the desktop too, and it
 * has to survive an app reinstall, so no client keeps an archive of its own.
 *
 *   { "<canonical workdir>": { "workdir": "/Users/x/proj", "sessions": { "<sessionId>": <archivedAt> } } }
 *
 * Deliberately its OWN file rather than a field on [SessionGroups]'s:
 *  - a machine that rolls back to a pre-#202 daemon would silently DROP every archive entry the next time it
 *    rewrote a shared file (its schema lacks the key, `ignoreUnknownKeys` strips it, `persist` writes back the
 *    stripped map). A separate file an old daemon never opens survives the round trip untouched.
 *  - the cross-project view has to RE-LIST each project, which needs a real path to hand back to the
 *    backends. Hence the stored [workdir] (and the canonical key — see [keyOf]).
 * It also means this object owns a separate cache trio, so neither store's mtime snapshot invalidates the other.
 *
 * The key set is load-bearing: `sessions` is pruned to nothing ⇒ the project entry is REMOVED, which keeps
 * "the store's keys == exactly the projects that have an archived session" true. [all] relies on that to
 * enumerate the archive without walking every project on disk.
 *
 * Orphans are not swept, same as [SessionGroups]: an entry whose transcript is gone simply never matches a
 * live summary. Persisted owner-only next to `identity.json`, atomic tmp+rename, all access `@Synchronized`.
 */
object SessionArchive {
    private val log = logger("SessionArchive")

    private const val MAX_PROJECTS = 1000
    private const val MAX_SESSIONS_PER_PROJECT = 1000

    // sessionIds ride in from the wire — validate before one steers a stored map key (same guard as
    // SessionGroups / SpawnedSessions: a hostile id must never influence the rewrite path).
    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** One project's archived sessions: id → when it was archived (drives the archive view's ordering). */
    @Serializable
    private data class ArchivedProject(
        val workdir: String = "",
        val sessions: Map<String, Long> = emptyMap(),
    )

    /** One project that has archived sessions, for the cross-project view. */
    data class ArchivedEntry(val workdir: String, val sessions: Map<String, Long>)

    fun defaultFile(): File = File(Identity.defaultPath().parentFile, "session-archive.json")

    private var cacheFile: File? = null
    private var cacheMtime: Long = -1
    private var cache: Map<String, ArchivedProject> = emptyMap()

    @Synchronized
    private fun load(file: File): Map<String, ArchivedProject> {
        val mtime = if (file.exists()) file.lastModified() else 0L
        if (file == cacheFile && mtime == cacheMtime) return cache
        val parsed =
            if (file.exists()) runCatching { json.decodeFromString<Map<String, ArchivedProject>>(file.readText()) }
                .getOrElse { log.warn("archive read failed (${it.message}) — starting empty"); emptyMap() }
            else emptyMap()
        cacheFile = file; cacheMtime = mtime; cache = parsed
        return parsed
    }

    @Synchronized
    private fun persist(file: File, data: Map<String, ArchivedProject>) {
        runCatching {
            file.parentFile?.mkdirs()
            // a UNIQUE tmp name: two daemons running at once is a documented recurring state here, and a
            // shared fixed name lets them interleave writeText+move into a torn file — which `load` then
            // silently reads as an empty archive, un-archiving everything with no visible error
            val tmp = Files.createTempFile(file.parentFile.toPath(), file.name, ".tmp").toFile()
            tmp.writeText(json.encodeToString(data))
            // owner-only, like identity.json — the file maps project paths to session ids
            runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
            runCatching { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                .recoverCatching { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                .onFailure { runCatching { tmp.delete() } }
                .getOrThrow()
            cacheFile = file; cacheMtime = file.lastModified(); cache = data
        }.onFailure { log.warn("archive write failed: ${it.message}") }
    }

    // Keyed on the CANONICAL path, not [ProjectPaths.dirKey]. dirKey collapses every non-alphanumeric to
    // '-', so `~/x/my-app` and `~/x/my_app` share one key — and since each entry keeps a single [workdir],
    // the second project to be archived would overwrite the first's path and the enumeration would re-list
    // only one of them. The other project's archived rows would then be invisible in BOTH the regular list
    // (filtered) and the archive view (never listed), with no way to restore them. That directly breaks
    // "archiving never loses anything", so the key has to be lossless.
    private fun keyOf(workdir: String) = ProjectPaths.canonicalKey(workdir)

    // ── reads ────────────────────────────────────────────────────────────────

    /** [workdir]'s archived session ids — one load per listing, then an O(1) membership test per row. */
    @Synchronized
    fun archivedIds(workdir: String, file: File = defaultFile()): Set<String> =
        load(file)[keyOf(workdir)]?.sessions?.keys ?: emptySet()

    /** Whether [sessionId] under [workdir] is archived. */
    @Synchronized
    fun isArchived(workdir: String, sessionId: String, file: File = defaultFile()): Boolean =
        load(file)[keyOf(workdir)]?.sessions?.containsKey(sessionId) == true

    /** Every project holding an archived session. This IS the cross-project index — the enumeration re-lists
     *  only these workdirs instead of scanning every project directory on the machine. */
    @Synchronized
    fun all(file: File = defaultFile()): List<ArchivedEntry> =
        load(file).values.filter { it.workdir.isNotEmpty() && it.sessions.isNotEmpty() }
            .map { ArchivedEntry(it.workdir, it.sessions) }

    // ── writes ───────────────────────────────────────────────────────────────

    /**
     * Archive / unarchive [sessionId] under [workdir]. Returns false only on a bad id or a cap — an already-
     * matching state is a successful no-op. [workdir] must be the VALIDATED absolute path (the router's
     * `groupWorkdir`), or `~/p` and `/Users/x/p` would archive into two different entries.
     */
    @Synchronized
    fun setArchived(
        workdir: String,
        sessionId: String,
        archived: Boolean,
        file: File = defaultFile(),
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!ID.matches(sessionId)) return false
        val key = keyOf(workdir)
        val data = load(file).toMutableMap()
        val proj = data[key]
        if (archived) {
            if (proj == null && data.size >= MAX_PROJECTS) return false
            val sessions = proj?.sessions ?: emptyMap()
            if (sessionId in sessions) return true
            if (sessions.size >= MAX_SESSIONS_PER_PROJECT) return false
            data[key] = ArchivedProject(workdir = workdir, sessions = sessions + (sessionId to now))
        } else {
            if (proj == null || sessionId !in proj.sessions) return true // already visible
            val left = proj.sessions - sessionId
            // prune the project entry when its last archive goes, so `all()` never walks a dead workdir
            if (left.isEmpty()) data.remove(key) else data[key] = proj.copy(workdir = workdir, sessions = left)
        }
        persist(file, data)
        return true
    }
}
