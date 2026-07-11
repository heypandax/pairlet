package dev.ccpocket.daemon.presets

import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.protocol.PresetEnv
import dev.ccpocket.protocol.PresetSummary
import dev.ccpocket.protocol.SavePreset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * Persistence + rules for API presets (issue #113): named env overrides (base URL / auth token /
 * optional model routing) a third-party API user switches between; the ACTIVE one is injected into
 * every NEW claude session launch.
 *
 * Secrets red line: the PLAINTEXT tokens live here and only here — ~/.cc-pocket/presets.json with
 * 0600 perms, the same bar as identity.json's private keys (and the claude CLI's own credential
 * store). Outward this class only hands out [PresetSummary]s whose token is reduced to a display
 * [mask]; there is deliberately no API that returns a stored token, so no caller can even build a
 * frame that leaks one. Log discipline: nothing in here logs, so no value can hit the daemon log.
 */
class PresetStore private constructor(private val path: File) {

    @Serializable
    private data class StoredPreset(
        val id: String,
        val name: String,
        val baseUrl: String,
        val tokenVar: String = PresetEnv.AUTH_TOKEN,
        val token: String = "",
        val model: String? = null,
        val smallFastModel: String? = null,
    )

    @Serializable
    private data class Stored(
        val v: Int = 1,
        val activeId: String? = null,
        val presets: List<StoredPreset> = emptyList(),
    )

    private val lock = Any()
    private var state: Stored = Stored()

    val activeId: String? get() = synchronized(lock) { state.activeId }

    /** The masked, client-safe view of every preset (stored order = creation order). */
    fun summaries(): List<PresetSummary> = synchronized(lock) {
        state.presets.map {
            PresetSummary(
                id = it.id, name = it.name, baseUrl = it.baseUrl, tokenVar = it.tokenVar,
                tokenMask = mask(it.token), model = it.model, smallFastModel = it.smallFastModel,
            )
        }
    }

    /**
     * The env the ACTIVE preset injects into a new session launch, or null when none is active
     * (the launch then inherits the daemon's own environment untouched — today's behavior).
     */
    fun activeEnv(): Map<String, String>? = synchronized(lock) {
        val p = state.presets.firstOrNull { it.id == state.activeId } ?: return null
        buildMap {
            put(PresetEnv.BASE_URL, p.baseUrl)
            put(if (p.tokenVar == PresetEnv.API_KEY) PresetEnv.API_KEY else PresetEnv.AUTH_TOKEN, p.token)
            p.model?.takeIf { it.isNotBlank() }?.let { put(PresetEnv.MODEL, it) }
            p.smallFastModel?.takeIf { it.isNotBlank() }?.let { put(PresetEnv.SMALL_FAST_MODEL, it) }
        }
    }

    /**
     * Create ([SavePreset.id] null) or update one preset. On update a null/blank token keeps the
     * stored one ("leave blank to keep"). Returns null on success, else (userError, fieldName) —
     * fieldName ∈ name|baseUrl|token so the client form can mark the offending field inline.
     * Editing the ACTIVE preset is allowed and needs no switch guard: running sessions keep the env
     * they were launched with either way; the next launch simply reads the new values.
     */
    fun save(req: SavePreset): Pair<String, String?>? = synchronized(lock) {
        val name = req.name.trim()
        val baseUrl = req.baseUrl.trim()
        val token = req.token?.value?.trim().orEmpty()
        val existing = req.id?.let { id -> state.presets.firstOrNull { it.id == id } }
        if (req.id != null && existing == null) return "Preset not found — it may have been deleted." to null
        if (name.isEmpty()) return "Name is required." to "name"
        if (state.presets.any { it.id != req.id && it.name.equals(name, ignoreCase = true) }) {
            return "A preset named '$name' already exists." to "name"
        }
        if (!validBaseUrl(baseUrl)) return "Enter a valid http(s) URL." to "baseUrl"
        if (req.tokenVar !in PresetEnv.TOKEN_VARS) return "Unknown token variable '${req.tokenVar}'." to "token"
        if (token.isEmpty() && existing == null) return "Paste the API key or token." to "token"

        val saved = StoredPreset(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            baseUrl = baseUrl,
            tokenVar = req.tokenVar,
            token = token.ifEmpty { existing!!.token }, // empty only reachable on update (guard above)
            model = req.model?.trim()?.takeIf { it.isNotEmpty() },
            smallFastModel = req.smallFastModel?.trim()?.takeIf { it.isNotEmpty() },
        )
        state = state.copy(
            presets = if (existing == null) state.presets + saved
            else state.presets.map { if (it.id == saved.id) saved else it },
        )
        persist()
        null
    }

    /** Remove a preset; when it was the active one the active pointer clears (caller runs the switch
     *  guard first — see PresetService). Returns false when the id is unknown. */
    fun delete(id: String): Boolean = synchronized(lock) {
        if (state.presets.none { it.id == id }) return false
        state = state.copy(
            presets = state.presets.filterNot { it.id == id },
            activeId = state.activeId.takeIf { it != id },
        )
        persist()
        true
    }

    /** Point the active marker (null = deactivate). Returns a user error for an unknown id. */
    fun activate(id: String?): String? = synchronized(lock) {
        if (id != null && state.presets.none { it.id == id }) return "Preset not found — it may have been deleted."
        state = state.copy(activeId = id)
        persist()
        null
    }

    private fun persist() {
        path.parentFile?.mkdirs()
        // create the file 0600 BEFORE any bytes land — writeText would otherwise create it under the
        // process umask (e.g. 0644) and only narrow it afterwards, a window where a same-machine
        // process could open the world-readable token file (hardening consistent with issue #90)
        if (!path.exists()) runCatching {
            Files.createFile(path.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }
        path.writeText(JSON.encodeToString(Stored.serializer(), state))
        // re-assert owner-only (an older file predating the createFile path, or a non-atomic FS); same
        // bar as identity.json. No-op on non-POSIX (Windows home dirs are user-private by ACL).
        runCatching { Files.setPosixFilePermissions(path.toPath(), PosixFilePermissions.fromString("rw-------")) }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun defaultPath(): File = File(Identity.defaultPath().parentFile, "presets.json")

        fun load(path: File = defaultPath()): PresetStore = PresetStore(path).apply {
            if (path.exists()) runCatching { state = JSON.decodeFromString(Stored.serializer(), path.readText()) }
        }

        /** Display mask: short prefix + last 4, middle elided (`sk-…••••3f9a`). Short tokens flatten
         *  to bullets — the 7 echoed chars must stay a small fraction of the secret (≥16 leaves ≥9
         *  hidden, ~54 bits at 6 bits/char; real API keys run 40+ chars). Never reversible. */
        fun mask(token: String): String =
            if (token.length >= 16) "${token.take(3)}…••••${token.takeLast(4)}" else "••••"

        private fun validBaseUrl(s: String): Boolean = runCatching {
            val u = URI(s)
            (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}
