package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.daemon.util.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Makes a session cc-pocket opened show up in DSH's own Web/desktop sidebar (issue #388).
 *
 * WHY. DSH groups sessions by project through its workspace registry, `$DSH_HOME/storages/workspace.json`:
 * each workspace record holds an ordered `sessionIds` list that DSH's docs call "the ownership truth". Only
 * the DSH host that created a session writes it there. A session created over ACP — every session the phone
 * starts — is in the shared session store but in no workspace, and DSH Web never adopts it: verified on
 * 0.1.5-rc.3 and 0.2.0-rc.2, it stays invisible across a Web restart. The phone could resume the
 * computer's sessions, but the computer could not see the phone's, which defeats picking work back up.
 *
 * WHAT. When a session starts, its id is put at the head of `sessionIds` of the EXISTING workspace whose
 * path is the session's directory — the same position DSH gives a session it creates. Verified on
 * 0.2.0-rc.2: after that write the session is listed under its project.
 *
 * Deliberately narrow, because this is another product's private file:
 *  - only `unit = {name:"workspace", version:2}` (the layout of both verified versions) is touched; any
 *    other shape is left alone, as is a file carrying a `pendingMutation` marker (DSH is mid-operation);
 *  - no workspace is ever created, reordered or removed — a directory DSH has no project for is skipped
 *    (creating one is a two-write operation of DSH's own; a first DSH Web start seeds projects itself);
 *  - every other key is preserved as read, and the file is replaced atomically.
 *
 * LIMIT. A running DSH Web holds the registry in memory: it shows the session only after it restarts, and
 * its next write can drop the entry. [adopt] is idempotent and is repeated when the session's process
 * ends, which restores a dropped entry; it cannot make a running Web refresh.
 */
object DshWorkspaceRegistry {
    private val log = logger("DshWorkspaceRegistry")
    private val json = Json { prettyPrint = true }
    private val lock = Any()

    fun registryFile(home: Path = DshPaths.dshHome()): Path = home.resolve("storages").resolve("workspace.json")

    /** True when [sessionId] was added to the workspace of [cwd]; false when nothing needed or could be done. */
    fun adopt(sessionId: String, cwd: String, file: Path = registryFile()): Boolean = synchronized(lock) {
        runCatching {
            if (sessionId.isBlank() || cwd.isBlank() || !file.isRegularFile()) return false
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return false
            val updated = withSession(root, sessionId, ProjectPaths.canonicalKey(cwd)) ?: return false
            val tmp = file.resolveSibling(file.fileName.toString() + ".cc-pocket.tmp")
            Files.writeString(tmp, json.encodeToString(JsonElement.serializer(), updated))
            runCatching {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING) }
            log.info("dsh workspace registry: listed session ${sessionId.take(16)} under its project")
            true
        }.getOrElse {
            log.warn("dsh workspace registry left untouched: ${it.message}")
            false
        }
    }

    /** [root] with [sessionId] heading the matching workspace's `sessionIds`, or null when there is nothing
     *  to change: unknown layout, DSH mid-mutation, no workspace for the directory, or already a member of
     *  some workspace (a session belongs to at most one). */
    internal fun withSession(root: JsonObject, sessionId: String, cwdKey: String): JsonObject? {
        val unit = root["unit"] as? JsonObject ?: return null
        if ((unit["name"] as? JsonPrimitive)?.contentOrNull != "workspace") return null
        if ((unit["version"] as? JsonPrimitive)?.intOrNull != 2) return null
        if (hasPendingMutation(root)) return null
        val tables = root["tables"] as? JsonObject ?: return null
        val workspaces = tables["workspaces"] as? JsonObject ?: return null
        val archived = ((root["global"] as? JsonObject)?.get("archivedSessionIds") as? JsonArray).orEmpty()
        if (archived.any { (it as? JsonPrimitive)?.contentOrNull == sessionId }) return null // the user hid it in DSH
        var targetId: String? = null
        for ((id, value) in workspaces) {
            val record = value as? JsonObject ?: return null
            val ids = record["sessionIds"] as? JsonArray ?: return null
            if (ids.any { (it as? JsonPrimitive)?.contentOrNull == sessionId }) return null
            val path = (record["path"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (targetId == null && ProjectPaths.canonicalKey(path) == cwdKey) targetId = id
        }
        val id = targetId ?: return null
        val record = workspaces.getValue(id) as JsonObject
        val ids = JsonArray(listOf(JsonPrimitive(sessionId)) + (record["sessionIds"] as JsonArray))
        val newRecord = JsonObject(record + ("sessionIds" to ids))
        val newTables = JsonObject(tables + ("workspaces" to JsonObject(workspaces + (id to newRecord))))
        return JsonObject(root + ("tables" to newTables))
    }

    private fun hasPendingMutation(element: JsonElement, depth: Int = 0): Boolean = when {
        depth > 3 -> false
        element is JsonObject -> element.any { (key, value) ->
            (key == "pendingMutation" && value !is kotlinx.serialization.json.JsonNull) || hasPendingMutation(value, depth + 1)
        }
        else -> false
    }
}
