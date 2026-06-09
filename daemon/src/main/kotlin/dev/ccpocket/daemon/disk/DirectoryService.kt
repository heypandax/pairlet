package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.DirectoryEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable

/** Enumerate candidate working directories + validate a chosen cwd. M0 = in-memory recents. */
class DirectoryService {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val recents = LinkedHashSet<String>()

    fun noteRecent(workdir: String) {
        recents.remove(workdir)
        recents.add(workdir)
        while (recents.size > 10) {
            val it = recents.iterator()
            it.next()
            it.remove()
        }
    }

    /**
     * M0: list directories that have Claude history (from ~/.claude/projects), recents flagged.
     * We read the authoritative `cwd` from each project's newest .jsonl rather than decoding the
     * (lossy) dir-key.
     */
    fun listDirectories(root: String?): List<DirectoryEntry> {
        val projects = ProjectPaths.projectsRoot()
        if (!projects.isDirectory()) return emptyList()
        val dirs = Files.newDirectoryStream(projects).use { it.toList() }.filter { it.isDirectory() }
        return dirs.mapNotNull { dir -> cwdOf(dir) }
            .map { cwd ->
                DirectoryEntry(
                    path = cwd,
                    name = Path.of(cwd).fileName?.toString() ?: cwd,
                    isDir = true,
                    hasSessions = true,
                    recent = cwd in recents,
                )
            }
            .distinctBy { it.path }
            .sortedWith(compareByDescending<DirectoryEntry> { it.recent }.thenBy { it.path })
    }

    private fun cwdOf(projectDir: Path): String? {
        val newest = Files.newDirectoryStream(projectDir, "*.jsonl").use { it.toList() }
            .maxByOrNull { it.getLastModifiedTime().toMillis() } ?: return null
        newest.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val obj = runCatching { json.parseToJsonElement(raw.trim()) }.getOrNull() as? JsonObject ?: continue
                (obj["cwd"] as? JsonPrimitive)?.contentOrNull?.let { return it }
            }
        }
        return null
    }

    fun validateWorkdir(path: String): Path? {
        val p = runCatching { Path.of(path).toRealPath() }.getOrNull() ?: return null
        return if (p.isDirectory() && p.isReadable()) p else null
    }
}
