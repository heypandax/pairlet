package dev.ccpocket.daemon.schedule

import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One persisted schedule (issue #137). Kept as its own on-disk shape (not the wire [dev.ccpocket.protocol.ScheduleInfo])
 * so the file format and the protocol can evolve independently; [SchedulerService] maps between them.
 *
 * [nextRunAtMs] null = a settled one-shot (fired or missed — [lastOutcome] says which). Repeating
 * schedules always carry a next fire time.
 */
@Serializable
data class ScheduleEntry(
    val id: String,
    val workdir: String,
    val prompt: String,
    val runAtMs: Long,
    val intervalMs: Long? = null,
    val dailyAtMinute: Int? = null,
    val resumeId: String? = null,
    val agent: AgentKind = AgentKind.CLAUDE,
    val model: String? = null,
    val mode: PermissionMode = PermissionMode.DEFAULT,
    val label: String? = null,
    val nextRunAtMs: Long? = null,
    val lastRunAtMs: Long? = null,
    val lastOutcome: String? = null,
) {
    val repeating: Boolean get() = intervalMs != null || dailyAtMinute != null
}

/**
 * Persistence for scheduled tasks (issue #137): ~/.cc-pocket/schedules.json beside identity.json —
 * the same store-directory pattern as [dev.ccpocket.daemon.presets.PresetStore] / DaemonPrefs, so a
 * daemon restart (or crash) never loses a schedule. No secrets live here (prompts are the user's own
 * text), so no permission narrowing is needed. All mutations go through [SchedulerService], which
 * serializes them; this class only owns load/persist and the in-memory list.
 */
class ScheduleStore private constructor(private val path: File) {

    @Serializable
    private data class Stored(val v: Int = 1, val entries: List<ScheduleEntry> = emptyList())

    private val lock = Any()
    private var state: Stored = Stored()

    fun all(): List<ScheduleEntry> = synchronized(lock) { state.entries }

    fun byId(id: String): ScheduleEntry? = synchronized(lock) { state.entries.firstOrNull { it.id == id } }

    fun add(entry: ScheduleEntry) = synchronized(lock) {
        state = state.copy(entries = state.entries + entry)
        persist()
    }

    /** Add [entry] only if its id is free — the check and the append happen under ONE lock so two
     *  concurrent creates carrying the same client-chosen id (a rapid auto-continue double-tap, #137/A1)
     *  can't both observe "free" and land two entries under one id. Returns false when the id was taken. */
    fun addIfAbsent(entry: ScheduleEntry): Boolean = synchronized(lock) {
        if (state.entries.any { it.id == entry.id }) return@synchronized false
        state = state.copy(entries = state.entries + entry)
        persist()
        true
    }

    /** Replace the entry with [entry]'s id (no-op when it was removed concurrently). */
    fun update(entry: ScheduleEntry) = synchronized(lock) {
        if (state.entries.none { it.id == entry.id }) return@synchronized
        state = state.copy(entries = state.entries.map { if (it.id == entry.id) entry else it })
        persist()
    }

    /** Remove [id]. Returns false for an unknown id. */
    fun remove(id: String): Boolean = synchronized(lock) {
        if (state.entries.none { it.id == id }) return false
        state = state.copy(entries = state.entries.filterNot { it.id == id })
        persist()
        true
    }

    private fun persist() {
        runCatching {
            path.parentFile?.mkdirs()
            path.writeText(JSON.encodeToString(Stored.serializer(), state))
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun defaultPath(): File = File(Identity.defaultPath().parentFile, "schedules.json")

        /** Load from [path]; a missing or corrupt file yields an empty store (never a crash at boot). */
        fun load(path: File = defaultPath()): ScheduleStore = ScheduleStore(path).apply {
            if (path.exists()) runCatching { state = JSON.decodeFromString(Stored.serializer(), path.readText()) }
        }
    }
}
