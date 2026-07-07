package dev.ccpocket.daemon.codex

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Codex stores rollout transcripts under `$CODEX_HOME/sessions` (default `~/.codex/sessions`), nested by
 * date as `YYYY/MM/DD/rollout-<timestamp>-<threadId>.jsonl` (verified against codex 0.124). The threadId in
 * the filename is the resume handle. Unlike Claude's per-project folders, rollouts are global — callers
 * filter by the `cwd` recorded in each file's session_meta line.
 */
object CodexPaths {
    fun codexHome(): Path =
        System.getenv("CODEX_HOME")?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".codex")

    fun sessionsRoot(): Path = codexHome().resolve("sessions")

    /** Codex's session index (`$CODEX_HOME/session_index.jsonl`): one `{id, thread_name, updated_at}` line
     *  per thread, carrying the human/AI title Codex Desktop shows. Rollouts themselves store no title (#64). */
    fun sessionIndex(): Path = codexHome().resolve("session_index.jsonl")

    /** Every rollout file under sessions/ (recursively), newest-mtime first, capped to bound a global scan. */
    fun sessionFiles(limit: Int = 800): List<Path> {
        val root = sessionsRoot()
        if (!root.isDirectory()) return emptyList()
        val files = runCatching {
            Files.walk(root).use { stream ->
                stream.filter { p ->
                    runCatching {
                        p.isRegularFile() && p.fileName.toString().let { it.startsWith("rollout-") && it.endsWith(".jsonl") }
                    }.getOrDefault(false)
                }.toList()
            }
        }.getOrDefault(emptyList())
        return files.sortedByDescending { runCatching { it.getLastModifiedTime().toMillis() }.getOrDefault(0L) }.take(limit)
    }

    /** The rollout file for a thread id (filename ends with `-<threadId>.jsonl`). Walks without the mtime
     *  sort `sessionFiles()` does — this lookup only needs the first name match, not newest-first ordering. */
    fun findSession(sessionId: String): Path? {
        val root = sessionsRoot()
        if (!root.isDirectory()) return null
        val suffix = "-$sessionId.jsonl"
        return runCatching {
            Files.walk(root).use { stream ->
                stream.filter { p ->
                    runCatching { p.isRegularFile() && p.fileName.toString().endsWith(suffix) }.getOrDefault(false)
                }.findFirst().orElse(null)
            }
        }.getOrNull()
    }
}
